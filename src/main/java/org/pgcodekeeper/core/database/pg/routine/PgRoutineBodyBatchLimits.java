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
 * Hard upper bounds for one same-snapshot residual body request. A single body
 * larger than the byte bound is fetched alone so progress remains possible.
 */
public record PgRoutineBodyBatchLimits(int maxCount, long maxPredictedUtf8Bytes) {

    public PgRoutineBodyBatchLimits {
        if (maxCount <= 0) {
            throw new IllegalArgumentException("Residual body batch count must be positive");
        }
        if (maxPredictedUtf8Bytes <= 0L) {
            throw new IllegalArgumentException("Residual body batch bytes must be positive");
        }
    }
}
