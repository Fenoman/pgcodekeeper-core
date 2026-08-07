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

import java.util.Objects;

/**
 * One final project-model routine that may authorize exact body reuse.
 * The underlying body is owned by either its project analysis launcher or one
 * independent catalog created from a reusable snapshot.
 */
public final class ProjectRoutineBodyCandidate {

    private final RoutineIdentity identity;
    private final OwnedRoutineBodySource source;

    ProjectRoutineBodyCandidate(RoutineIdentity identity,
                                OwnedRoutineBodySource source) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.source = Objects.requireNonNull(source, "source");
    }

    public RoutineIdentity identity() {
        return identity;
    }

    ReusableProjectRoutineBodySnapshot.Entry snapshotEntry() {
        RoutineBody body = source.snapshotBody();
        if (body == null) {
            throw new IllegalStateException(
                    "Project routine body is unavailable for snapshot");
        }
        return new ReusableProjectRoutineBodySnapshot.Entry(
                identity, body, source.requireAuthorization());
    }

    public RoutineBodyAuthorization authorization() {
        return source.requireAuthorization();
    }

    /**
     * Resolves the JDBC lease with the exact project payload while leaving the
     * independent project lease available for its own analysis.
     */
    public RoutineBody shareTo(DeferredRoutineBodySource target) {
        return source.shareTo(target);
    }
}
