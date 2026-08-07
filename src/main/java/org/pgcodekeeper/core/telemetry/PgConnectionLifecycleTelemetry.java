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
package org.pgcodekeeper.core.telemetry;

import java.util.Objects;

/**
 * Lifecycle marker for a PostgreSQL connection. It deliberately contains no
 * target, user, SQL, credentials, or object names.
 *
 * @param side logical comparison side
 * @param role connection purpose
 * @param lane one-based catalog lane, or zero for non-lane connections
 * @param lifecycle lifecycle transition
 * @param backendPid PostgreSQL backend PID, or zero when unavailable
 */
public record PgConnectionLifecycleTelemetry(
        LogicalSide side,
        PgConnectionRole role,
        int lane,
        Lifecycle lifecycle,
        int backendPid) {

    public enum LogicalSide {
        OLD,
        NEW,
        UNBOUND
    }

    public enum Lifecycle {
        OPENED,
        CLOSE_REQUESTED
    }

    public PgConnectionLifecycleTelemetry {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (role == PgConnectionRole.CATALOG_LANE
                ? lane < 1
                : lane != 0) {
            throw new IllegalArgumentException(
                    "lane must be one-based only for CATALOG_LANE");
        }
        if (backendPid < 0) {
            throw new IllegalArgumentException(
                    "backendPid must not be negative");
        }
    }
}
