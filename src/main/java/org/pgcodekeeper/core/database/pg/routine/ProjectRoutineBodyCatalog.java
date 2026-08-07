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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;

/**
 * Final, single-owner index of exchange-eligible project routine bodies.
 * Duplicate identities are retained only as compact ambiguity markers.
 */
public final class ProjectRoutineBodyCatalog implements AutoCloseable {

    private final Map<RoutineIdentity, ProjectRoutineBodyCandidate> candidates;
    private final Set<RoutineIdentity> ambiguous;

    private ProjectRoutineBodyCatalog(
            Map<RoutineIdentity, ProjectRoutineBodyCandidate> candidates,
            Set<RoutineIdentity> ambiguous) {
        this.candidates = candidates;
        this.ambiguous = ambiguous;
    }

    static ProjectRoutineBodyCatalog fromSnapshot(
            Map<RoutineIdentity, ProjectRoutineBodyCandidate> candidates,
            Set<RoutineIdentity> ambiguous) {
        return new ProjectRoutineBodyCatalog(candidates, ambiguous);
    }

    /**
     * Builds an index from launchers attached to the already final project
     * model. No routine text is copied while building the index.
     */
    public static ProjectRoutineBodyCatalog build(PgDatabase database) {
        Objects.requireNonNull(database, "database");
        var launchers = database.getAnalysisLaunchers();
        int potentialCandidates = countPotentialCandidates(launchers);

        var candidates = new HashMap<RoutineIdentity, ProjectRoutineBodyCandidate>(
                hashCapacity(potentialCandidates));
        Set<RoutineIdentity> ambiguous = database.takeProjectRoutineBodyDuplicates();
        for (IAnalysisLauncher launcher : launchers) {
            if (!(launcher instanceof PgFuncProcAnalysisLauncher routineLauncher)) {
                continue;
            }
            ProjectRoutineBodyCandidate candidate =
                    routineLauncher.projectRoutineBodyCandidate(database);
            if (candidate == null) {
                continue;
            }

            RoutineIdentity identity = candidate.identity();
            if (ambiguous.contains(identity)) {
                continue;
            }
            ProjectRoutineBodyCandidate previous = candidates.putIfAbsent(identity, candidate);
            if (previous != null) {
                candidates.remove(identity);
                ambiguous.add(identity);
            }
        }
        return new ProjectRoutineBodyCatalog(candidates, ambiguous);
    }

    static int countPotentialCandidates(
            Iterable<? extends IAnalysisLauncher> launchers) {
        int count = 0;
        for (IAnalysisLauncher launcher : launchers) {
            if (launcher instanceof PgFuncProcAnalysisLauncher routineLauncher
                    && routineLauncher.isPotentialProjectRoutineBodyCandidate()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Removes and transfers a unique candidate. Ambiguous identities always
     * return {@code null} and remain available to the ambiguity counter until
     * {@link #removeAmbiguous(RoutineIdentity)} is called.
     */
    public ProjectRoutineBodyCandidate removeCandidate(RoutineIdentity identity) {
        return candidates.remove(Objects.requireNonNull(identity, "identity"));
    }

    /**
     * Removes an ambiguity marker for a residual JDBC row.
     */
    public boolean removeAmbiguous(RoutineIdentity identity) {
        return ambiguous.remove(Objects.requireNonNull(identity, "identity"));
    }

    public int candidateCount() {
        return candidates.size();
    }

    public int ambiguousCount() {
        return ambiguous.size();
    }

    /**
     * Releases all candidate and ambiguity references owned by this catalog.
     */
    @Override
    public void close() {
        candidates.clear();
        ambiguous.clear();
    }

    static int hashCapacity(int expectedSize) {
        if (expectedSize < 3) {
            return expectedSize + 1;
        }
        if (expectedSize >= 1 << 29) {
            return Integer.MAX_VALUE;
        }
        return expectedSize + expectedSize / 3 + 1;
    }
}
