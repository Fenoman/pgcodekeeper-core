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
package org.pgcodekeeper.core.database.pg.jdbc;

/** Hard primitive-array memory gate for a warm changed reader. */
final class PgCatalogRowCacheMemoryBudget {

    static final long MAX_WARM_CHANGED_BYTES = 128L << 20;
    private static final long FIXED_BYTES = 1L << 20;

    private PgCatalogRowCacheMemoryBudget() {
    }

    static boolean withinBudget(int cachedRows, int incomingRows) {
        if (cachedRows < 0 || incomingRows < 0
                || cachedRows > PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS
                || incomingRows > PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS) {
            return false;
        }
        return estimatedPeakBytes(cachedRows, incomingRows)
                <= MAX_WARM_CHANGED_BYTES;
    }

    static long estimatedPeakBytes(int cachedRows, int incomingRows) {
        if (cachedRows < 0 || incomingRows < 0) {
            return Long.MAX_VALUE;
        }
        try {
            long cachedHashes = Math.multiplyExact((long) cachedRows,
                    PgPackedCatalogHashes.MD5_BYTES);
            long cachedReplay = Math.multiplyExact((long) cachedRows,
                    Long.BYTES + Integer.BYTES);
            long cachedLookupSlots = Math.multiplyExact((long)
                    PgPackedCatalogHashIndex.tableCapacityFor(cachedRows),
                    Integer.BYTES);
            long incomingHashes = Math.multiplyExact((long) incomingRows,
                    PgPackedCatalogHashes.MD5_BYTES);
            long incomingDriverPayload = driverPayloadBytes(incomingRows);
            long incomingValidationSlots = Math.multiplyExact((long)
                    PgPackedCatalogHashIndex.tableCapacityFor(incomingRows),
                    Integer.BYTES);
            long mapping = Math.multiplyExact((long) incomingRows,
                    Integer.BYTES);
            long borrowedWriter = PgCatalogWriterIndex
                    .borrowedRetainedBytesForRows(incomingRows);

            // Do not assume that clearing a reference triggers GC before the
            // next primitive array is allocated. Every array allocated by the
            // changed-reader pipeline is therefore part of the same hard
            // peak. The borrowed writer skips its own duplicate-slot pass
            // because incomingValidationSlots already established uniqueness.
            long total = Math.addExact(cachedHashes, cachedReplay);
            total = Math.addExact(total, cachedLookupSlots);
            total = Math.addExact(total, incomingHashes);
            // pgJDBC may retain the ResultSet's bytea value while the cache
            // streams it into its validated destination array. Both arrays
            // are live until the result set is closed.
            total = Math.addExact(total, incomingDriverPayload);
            total = Math.addExact(total, incomingValidationSlots);
            total = Math.addExact(total, mapping);
            total = Math.addExact(total, borrowedWriter);
            return Math.addExact(total, FIXED_BYTES);
        } catch (ArithmeticException | IllegalArgumentException ex) {
            return Long.MAX_VALUE;
        }
    }

    static long driverPayloadBytes(int incomingRows) {
        if (incomingRows < 0) {
            return Long.MAX_VALUE;
        }
        try {
            return Math.min(Math.multiplyExact((long) incomingRows,
                    PgPackedCatalogHashes.MD5_BYTES),
                    PgCatalogRowCache.HASH_CHUNK_BYTES);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }
}
