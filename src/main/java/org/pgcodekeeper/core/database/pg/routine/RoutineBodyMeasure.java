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

/**
 * Result of measuring raw PostgreSQL routine parser input.
 */
public sealed interface RoutineBodyMeasure permits RoutineFingerprint, RoutineBodyMeasure.Unreusable {

    long utf8Length();

    enum Reason {
        MALFORMED_UTF16
    }

    /**
     * A body whose exact UTF-8 size is known but which must never authorize
     * cross-loader reuse.
     */
    record Unreusable(long utf8Length, Reason reason) implements RoutineBodyMeasure {

        public Unreusable {
            if (utf8Length < 0) {
                throw new IllegalArgumentException("UTF-8 length must be nonnegative");
            }
            java.util.Objects.requireNonNull(reason, "reason");
        }
    }
}
