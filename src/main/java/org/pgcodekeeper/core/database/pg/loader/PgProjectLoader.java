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
package org.pgcodekeeper.core.database.pg.loader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;

import org.pgcodekeeper.core.analysis.AnalysisReplay;
import org.pgcodekeeper.core.analysis.AnalysisReplayPayload;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionContext;
import org.pgcodekeeper.core.database.api.loader.IComparisonAnalysisLifecycle;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.base.loader.AbstractDumpLoader;
import org.pgcodekeeper.core.database.base.loader.AbstractLibraryLoader;
import org.pgcodekeeper.core.database.base.loader.AbstractProjectLoader;
import org.pgcodekeeper.core.database.base.project.AbstractWorkDirs;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.project.PgWorkDirs;
import org.pgcodekeeper.core.database.pg.routine.ReusableProjectRoutineBodySnapshot;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * PostgreSQL project loader for loading database schemas from project directory structures.
 */
public class PgProjectLoader extends AbstractProjectLoader<PgDatabase>
        implements IComparisonAnalysisLifecycle {

    private volatile boolean reusableModelCaptureEnabled;
    private volatile AnalysisReplayPayload analysisReplayPayload;
    private volatile boolean analysisReplayed;
    private boolean loadStarted;
    private boolean comparisonAnalysisApproved;
    private ReusableProjectRoutineBodySnapshot pendingRoutineSnapshot;
    private ReusableProjectRoutineBodySnapshot completedRoutineSnapshot;

    public PgProjectLoader(Path dirPath, ISettings settings) {
        super(dirPath, settings, new PgWorkDirs(AbstractWorkDirs.resolveAltDirsFile(dirPath)));
    }

    public PgProjectLoader(Path dirPath, ISettings settings, Collection<String> libXmls,
                           Collection<String> libs, Collection<String> libsWithoutPriv, Path metaPath) {
        super(dirPath, settings, new PgWorkDirs(AbstractWorkDirs.resolveAltDirsFile(dirPath)),
                libXmls, libs, libsWithoutPriv, metaPath);
    }

    @Override
    protected PgDatabase createDatabase() {
        return new PgDatabase(settings.isCollectObjectReferences());
    }

    @Override
    protected AbstractDumpLoader<PgDatabase> createDumpLoader(Path file) {
        return new PgDumpLoader(file, settings, false, antlrTasks);
    }

    @Override
    protected AbstractLibraryLoader<PgDatabase> createLibraryLoader(PgDatabase db) {
        return new PgLibraryLoader(
                db, metaPath, new HashSet<>(), settings, antlrTasks);
    }

    @Override
    public void registerComparisonExtensions(ComparisonExtensionContext context) {
        // Registration is allocation-only. The catalog is built later and only
        // when a feature-enabled JDBC endpoint completes this typed pair.
        PgRoutineBodyComparisonExtension.registerProject(context);
    }

    @Override
    public PgDatabase load() throws IOException, InterruptedException {
        markLoadStarted();
        return super.load();
    }

    @Override
    public PgDatabase loadInternal() throws InterruptedException, IOException {
        markLoadStarted();
        PgDatabase db = super.loadInternal();
        db.sortColumns();
        if (reusableModelCaptureEnabled) {
            ReusableProjectRoutineBodySnapshot snapshot =
                    ReusableProjectRoutineBodySnapshot.capture(db);
            // Intended trade-off: this cold run pays for every later warm one.
            // The matched-body skip drops the analysis of a routine body that
            // is proven byte-identical to the database side, which leaves
            // that routine only partially analyzed in the project model. Such
            // a model is correct for this one comparison but not for the next
            // one against a different database, so a model that is going to be
            // retained must be analyzed in full. The extra cold-run cost is
            // reported through isReusableModelCaptureEnabled() on the side
            // stage telemetry.
            for (IAnalysisLauncher launcher : db.getAnalysisLaunchers()) {
                if (launcher instanceof PgFuncProcAnalysisLauncher routineLauncher) {
                    routineLauncher.disableSkipMatchedBodyAnalysis();
                }
            }
            synchronized (this) {
                comparisonAnalysisApproved = false;
                pendingRoutineSnapshot = snapshot;
                completedRoutineSnapshot = null;
            }
        }
        return db;
    }

    @Override
    protected boolean replayAnalysis(PgDatabase db, IMonitor monitor)
            throws InterruptedException {
        AnalysisReplayPayload payload = analysisReplayPayload;
        if (payload == null) {
            return false;
        }
        // One payload serves one load. Dropping it here keeps a rejected
        // replay from being retried against the same mismatching model.
        analysisReplayPayload = null;
        boolean replayed = AnalysisReplay.apply(db, payload, monitor);
        analysisReplayed = replayed;
        return replayed;
    }

    /**
     * Installs a cached analysis result for this load.
     * <p>
     * The result is applied instead of the full analysis, but only if it fits
     * the model this loader parses. A payload that does not fit is discarded
     * and the model is analyzed normally, so the caller never has to prove the
     * payload matches before handing it over - only that it was captured from
     * the same project under the same settings.
     *
     * @param payload analysis result captured from an equivalent model
     * @throws IllegalStateException if the load already started
     */
    public synchronized void enableAnalysisReplay(AnalysisReplayPayload payload) {
        if (loadStarted) {
            throw new IllegalStateException(
                    "Analysis replay must be enabled before loading");
        }
        analysisReplayPayload = Objects.requireNonNull(payload, "payload");
        analysisReplayed = false;
    }

    /**
     * Reports whether this load served its analysis from the installed cached
     * result instead of analyzing the model.
     *
     * @return true if the analysis phase was replayed
     */
    public boolean isAnalysisReplayed() {
        return analysisReplayed;
    }

    /**
     * Enables one reusable-model snapshot for this coordinated load.
     */
    public synchronized void enableReusableModelCapture() {
        if (loadStarted) {
            throw new IllegalStateException(
                    "Reusable model capture must be enabled before loading");
        }
        // Additional schema exclusions drop project files before parsing, so
        // the resulting model is only valid for the very same exclusion set.
        // Refuse to produce a reusable model rather than rely on the caller's
        // reuse key to carry the exclusions, see PreanalyzedProjectLoader.
        if (!settings.getAdditionalExcludedSchemas().isEmpty()) {
            throw new IllegalStateException(
                    "Reusable model capture is not supported with additional schema exclusions");
        }
        reusableModelCaptureEnabled = true;
        comparisonAnalysisApproved = false;
        pendingRoutineSnapshot = null;
        completedRoutineSnapshot = null;
    }

    @Override
    public boolean isReusableModelCaptureEnabled() {
        return reusableModelCaptureEnabled;
    }

    /**
     * Transfers the snapshot approved after both comparison sides completed
     * full analysis. Calls made earlier return empty without consuming it.
     */
    public synchronized Optional<ReusableProjectRoutineBodySnapshot>
            takeReusableProjectRoutineBodySnapshot() {
        ReusableProjectRoutineBodySnapshot current = completedRoutineSnapshot;
        completedRoutineSnapshot = null;
        return Optional.ofNullable(current);
    }

    @Override
    public synchronized void comparisonAnalysisSucceeded() {
        if (reusableModelCaptureEnabled && pendingRoutineSnapshot != null) {
            comparisonAnalysisApproved = true;
        }
    }

    @Override
    public synchronized void comparisonSucceeded() {
        if (reusableModelCaptureEnabled && comparisonAnalysisApproved
                && pendingRoutineSnapshot != null) {
            completedRoutineSnapshot = pendingRoutineSnapshot;
            pendingRoutineSnapshot = null;
        }
        comparisonAnalysisApproved = false;
    }

    @Override
    public synchronized void comparisonFailed() {
        comparisonAnalysisApproved = false;
        pendingRoutineSnapshot = null;
        completedRoutineSnapshot = null;
    }

    @Override
    public void cancel() throws IOException {
        markLoadStarted();
        comparisonFailed();
        super.cancel();
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            loadStarted = true;
            if (!comparisonAnalysisApproved) {
                pendingRoutineSnapshot = null;
            }
        }
        super.close();
    }

    private synchronized void markLoadStarted() {
        loadStarted = true;
    }
}
