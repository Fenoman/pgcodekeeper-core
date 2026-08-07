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
package org.pgcodekeeper.core.database.pg.routine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;

/**
 * Immutable routine-body payload retained with one fully analyzed project
 * model. Each comparison creates a fresh one-shot catalog from this snapshot.
 * The owner must hold the same exclusive generation lease as the associated
 * model while a catalog is in use.
 */
public final class ReusableProjectRoutineBodySnapshot {

    private final PgDatabase sourceDatabase;
    private final Map<RoutineIdentity, Entry> entries;
    private final Set<RoutineIdentity> ambiguous;

    private ReusableProjectRoutineBodySnapshot(PgDatabase sourceDatabase,
            Map<RoutineIdentity, Entry> entries,
            Set<RoutineIdentity> ambiguous) {
        this.sourceDatabase = Objects.requireNonNull(
                sourceDatabase, "sourceDatabase");
        this.entries = Map.copyOf(entries);
        this.ambiguous = Set.copyOf(ambiguous);
    }

    /**
     * Captures exact final-model identities and immutable body payloads before
     * full analysis releases the project launchers.
     */
    public static ReusableProjectRoutineBodySnapshot capture(PgDatabase database) {
        Objects.requireNonNull(database, "database");
        var entries = new LinkedHashMap<RoutineIdentity, Entry>();
        var ambiguous = new HashSet<>(
                database.copyProjectRoutineBodyDuplicates());

        for (IAnalysisLauncher launcher : database.getAnalysisLaunchers()) {
            if (!(launcher instanceof PgFuncProcAnalysisLauncher routineLauncher)) {
                continue;
            }
            ProjectRoutineBodyCandidate candidate =
                    routineLauncher.projectRoutineBodyCandidate(database);
            if (candidate == null) {
                continue;
            }
            Entry entry = candidate.snapshotEntry();
            RoutineIdentity identity = entry.identity();
            if (ambiguous.contains(identity)) {
                continue;
            }
            Entry previous = entries.putIfAbsent(identity, entry);
            if (previous != null) {
                entries.remove(identity);
                ambiguous.add(identity);
            }
        }
        return new ReusableProjectRoutineBodySnapshot(
                database, entries, ambiguous);
    }

    /**
     * Creates an independent catalog whose candidates may be consumed once.
     * Entries no longer attached to the exact model object are omitted so the
     * JDBC side falls back to residual body fetching.
     */
    public ProjectRoutineBodyCatalog newCatalog(PgDatabase database) {
        Objects.requireNonNull(database, "database");
        if (database != sourceDatabase) {
            return ProjectRoutineBodyCatalog.fromSnapshot(
                    new HashMap<>(), new HashSet<>(ambiguous));
        }
        var candidates = new HashMap<RoutineIdentity, ProjectRoutineBodyCandidate>(
                ProjectRoutineBodyCatalog.hashCapacity(entries.size()));
        for (Entry entry : entries.values()) {
            if (!entry.isAttachedTo(database)) {
                continue;
            }
            var source = OwnedRoutineBodySource.exchangeCandidate(
                    entry.body(), entry.authorization());
            candidates.put(entry.identity(),
                    new ProjectRoutineBodyCandidate(entry.identity(), source));
        }
        return ProjectRoutineBodyCatalog.fromSnapshot(
                candidates, new HashSet<>(ambiguous));
    }

    /**
     * Verifies that every reusable candidate still belongs to the exact model.
     */
    public boolean isCompatibleWith(PgDatabase database) {
        Objects.requireNonNull(database, "database");
        if (database != sourceDatabase) {
            return false;
        }
        for (Entry entry : entries.values()) {
            if (!entry.isAttachedTo(database)) {
                return false;
            }
        }
        return true;
    }

    record Entry(RoutineIdentity identity, RoutineBody body,
                 RoutineBodyAuthorization authorization) {

        Entry {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(authorization, "authorization");
            if (!body.measure().equals(authorization.fingerprint())) {
                throw new IllegalArgumentException(
                        "Routine body and authorization fingerprints differ");
            }
        }

        private boolean isAttachedTo(PgDatabase database) {
            PgSchema schema = database.getSchema(identity.schemaName());
            if (schema == null) {
                return false;
            }
            Object statement = schema.getChild(
                    identity.signature(), identity.kind());
            return statement instanceof PgAbstractFunction routine
                    && routine.hasBodyReference(body.canonical());
        }
    }
}
