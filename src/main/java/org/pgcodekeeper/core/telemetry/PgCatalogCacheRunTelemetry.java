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
 * Aggregate result of a PostgreSQL catalog cache run. Byte counters describe
 * cache payloads only; they are not measurements of full network traffic.
 */
public record PgCatalogCacheRunTelemetry(long readers, long bypassedReaders, long rows,
        long hits, long misses, long fetchedRows, long publishedRows, long hashPayloadBytes,
        long encodedRowBytes, long packBytesRead, long packBytesWritten, long prunedBytes,
        long elapsedNanos) {

    /** Validates aggregate counters. */
    public PgCatalogCacheRunTelemetry {
        requireNonNegative(readers, "readers");
        requireNonNegative(bypassedReaders, "bypassedReaders");
        requireNonNegative(rows, "rows");
        requireNonNegative(hits, "hits");
        requireNonNegative(misses, "misses");
        requireNonNegative(fetchedRows, "fetchedRows");
        requireNonNegative(publishedRows, "publishedRows");
        requireNonNegative(hashPayloadBytes, "hashPayloadBytes");
        requireNonNegative(encodedRowBytes, "encodedRowBytes");
        requireNonNegative(packBytesRead, "packBytesRead");
        requireNonNegative(packBytesWritten, "packBytesWritten");
        requireNonNegative(prunedBytes, "prunedBytes");
        requireNonNegative(elapsedNanos, "elapsedNanos");
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
