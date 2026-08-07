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

/**
 * Aggregate result of PostgreSQL routine body cache activity. {@code savedUtf8Bytes}
 * is payload-level and is not a measurement of full network traffic.
 */
public record PgRoutineBodyCacheTelemetry(long hits, long misses, long stored,
        long savedUtf8Bytes, long prunedBytes, long elapsedNanos) {

    /** Validates aggregate counters. */
    public PgRoutineBodyCacheTelemetry {
        requireNonNegative(hits, "hits");
        requireNonNegative(misses, "misses");
        requireNonNegative(stored, "stored");
        requireNonNegative(savedUtf8Bytes, "savedUtf8Bytes");
        requireNonNegative(prunedBytes, "prunedBytes");
        requireNonNegative(elapsedNanos, "elapsedNanos");
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
