/*******************************************************************************
 * Copyright 2017-2026 TAXTELECOM, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.pgcodekeeper.core.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.pgcodekeeper.core.analysis.AnalysisReplayPayload.FileReferences;
import org.pgcodekeeper.core.analysis.AnalysisReplayPayload.StatementDependencies;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;

/**
 * Captures the result of a full analysis and replays it onto a structurally
 * loaded model of the same project.
 * <p>
 * Replay is all-or-nothing. Every address of the payload is resolved against
 * the target model and the model shape is checked before a single edge is
 * written, so a payload that does not fit its target leaves the model exactly
 * as the structural load produced it and the caller can still run the ordinary
 * analysis. A partially replayed model is never produced.
 *
 * @see AnalysisReplayPayload
 */
public final class AnalysisReplay {

    /** Cancellation is polled once per this many published items. */
    private static final int CANCELLATION_BATCH = 4096;

    private AnalysisReplay() {
    }

    /**
     * Captures the analysis result of one fully analyzed model.
     *
     * @param database analyzed model to read
     * @return payload that reproduces this model's analysis result
     */
    public static AnalysisReplayPayload capture(IDatabase database) {
        Objects.requireNonNull(database, "database");
        var dependencies = new ArrayList<StatementDependencies>();
        var suppressed = new ArrayList<StatementAddress>();
        int statements = 0;
        // Walks columns too, see StatementAddress.statements: a column default
        // contributes dependency edges that the plain child walk cannot reach.
        var walk = StatementAddress.statements(database).iterator();
        while (walk.hasNext()) {
            IStatement statement = walk.next();
            statements++;
            Set<ObjectReference> deps = statement.getDependencies();
            boolean routineSuppressed = statement instanceof PgAbstractFunction routine
                    && routine.isBodyDependencyStateSuppressed();
            if ((deps == null || deps.isEmpty()) && !routineSuppressed) {
                continue;
            }
            StatementAddress address = StatementAddress.of(statement);
            if (deps != null && !deps.isEmpty()) {
                dependencies.add(new StatementDependencies(
                        address, new ArrayList<>(deps)));
            }
            if (routineSuppressed) {
                suppressed.add(address);
            }
        }

        var references = new ArrayList<FileReferences>();
        for (Map.Entry<String, Set<ObjectLocation>> entry
                : database.getObjReferences().entrySet()) {
            references.add(new FileReferences(
                    entry.getKey(), new ArrayList<>(entry.getValue())));
        }

        // Canonical outer order. Which statement or file comes first in the
        // payload cannot change the replayed model - statements own separate
        // dependency sets and files separate location sets - but it does change
        // the bytes, and a cache whose bytes are a function of its content
        // alone is one that can be compared, deduplicated and tested by
        // equality. The inner lists keep their model order untouched: those
        // land in insertion-ordered containers and are part of the result.
        dependencies.sort(Comparator.comparing(StatementDependencies::address));
        references.sort(Comparator.comparing(FileReferences::filePath));
        suppressed.sort(Comparator.naturalOrder());
        return new AnalysisReplayPayload(
                dependencies, references, suppressed, statements);
    }

    /**
     * Replays a captured analysis result onto a structurally loaded model.
     * <p>
     * On success the model carries the dependency edges, object locations and
     * routine body-suppression flags of the model the payload was captured
     * from, and its analysis launchers are released exactly as a full analysis
     * would have released them.
     *
     * @param database structurally loaded target model
     * @param payload  captured analysis result
     * @param monitor  cancellation monitor, may be {@code null}
     * @return {@code true} when the payload was applied in full, {@code false}
     *         when it does not fit this model and nothing was written
     * @throws InterruptedException if the monitor was cancelled
     */
    public static boolean apply(IDatabase database, AnalysisReplayPayload payload,
            IMonitor monitor) throws InterruptedException {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(payload, "payload");
        IMonitor effective = monitor == null ? new NullMonitor() : monitor;
        IMonitor.checkCancelled(effective);

        Map<StatementAddress, IStatement> index = StatementAddress.index(database);
        if (index == null || index.size() != payload.statementCount()) {
            return false;
        }
        IMonitor.checkCancelled(effective);

        // Resolve first, write later: a payload that does not fit must leave
        // the structural model untouched so the caller can still analyze it.
        List<StatementDependencies> deps = payload.dependencies();
        var depTargets = new IStatement[deps.size()];
        for (int i = 0; i < deps.size(); i++) {
            IStatement target = index.get(deps.get(i).address());
            if (target == null) {
                return false;
            }
            depTargets[i] = target;
        }
        List<StatementAddress> suppressed = payload.suppressedRoutines();
        var routineTargets = new PgAbstractFunction[suppressed.size()];
        for (int i = 0; i < suppressed.size(); i++) {
            if (!(index.get(suppressed.get(i)) instanceof PgAbstractFunction routine)) {
                return false;
            }
            routineTargets[i] = routine;
        }
        IMonitor.checkCancelled(effective);

        int published = 0;
        for (int i = 0; i < deps.size(); i++) {
            IStatement target = depTargets[i];
            for (ObjectReference reference : deps.get(i).references()) {
                target.addDependency(reference);
                if (++published % CANCELLATION_BATCH == 0) {
                    IMonitor.checkCancelled(effective);
                }
            }
        }
        for (FileReferences file : payload.references()) {
            for (ObjectLocation location : file.locations()) {
                database.addReference(file.filePath(), location);
                if (++published % CANCELLATION_BATCH == 0) {
                    IMonitor.checkCancelled(effective);
                }
            }
        }
        for (PgAbstractFunction routine : routineTargets) {
            routine.suppressBodyDependencyState();
        }
        releaseLaunchers(database);
        return true;
    }

    /**
     * Releases the deferred parser input of every launcher and drops the
     * launcher list, which is what marks a model as analyzed.
     * <p>
     * A skipped analysis must still free the routine body sources the
     * structural load handed to its launchers; dropping the list alone would
     * leave them for the collector to finalize.
     *
     * @param database model whose launchers are no longer needed
     */
    private static void releaseLaunchers(IDatabase database) {
        for (IAnalysisLauncher launcher : database.getAnalysisLaunchers()) {
            if (launcher != null) {
                launcher.releaseWithoutAnalysis();
            }
        }
        database.clearAnalysisLaunchers();
    }
}
