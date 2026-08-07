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

import java.util.Objects;

import org.pgcodekeeper.core.monitor.IMonitor;

/** Compact open-addressed lookup from a binary MD5 to its ordered row index. */
final class PgPackedCatalogHashIndex {

    private static final int MAX_TABLE_CAPACITY = 1 << 30;

    private final PgPackedCatalogHashes hashes;
    private final int[] slots;

    private PgPackedCatalogHashIndex(PgPackedCatalogHashes hashes,
            int[] slots) {
        this.hashes = hashes;
        this.slots = slots;
    }

    static PgPackedCatalogHashIndex build(PgPackedCatalogHashes hashes,
            IMonitor monitor) throws InterruptedException, DuplicateHashException {
        Objects.requireNonNull(hashes, "hashes");
        int[] slots = new int[tableCapacityFor(hashes.size())];
        var index = new PgPackedCatalogHashIndex(hashes, slots);
        byte[] packed = hashes.rawBytesForCache();
        for (int row = 0; row < hashes.size(); row++) {
            IMonitor.checkCancelled(monitor);
            int offset = hashes.offsetOf(row);
            int slot = digestHash(packed, offset) & (slots.length - 1);
            while (slots[slot] != 0) {
                int existingRow = slots[slot] - 1;
                if (hashes.equalsDigestAt(existingRow, packed, offset)) {
                    throw new DuplicateHashException(existingRow, row);
                }
                slot = (slot + 1) & (slots.length - 1);
            }
            slots[slot] = row + 1;
        }
        return index;
    }

    int find(PgPackedCatalogHashes source, int hashIndex) {
        Objects.requireNonNull(source, "hashes");
        return find(source.rawBytesForCache(), source.offsetOf(hashIndex));
    }

    int find(byte[] digest, int offset) {
        Objects.requireNonNull(digest, "digest");
        Objects.checkFromIndexSize(offset, PgPackedCatalogHashes.MD5_BYTES,
                digest.length);
        int slot = digestHash(digest, offset) & (slots.length - 1);
        while (slots[slot] != 0) {
            int row = slots[slot] - 1;
            if (hashes.equalsDigestAt(row, digest, offset)) {
                return row;
            }
            slot = (slot + 1) & (slots.length - 1);
        }
        return -1;
    }

    long retainedBytes() {
        return (long) slots.length * Integer.BYTES;
    }

    static int tableCapacityFor(int size) {
        if (size < 0) {
            throw new IllegalArgumentException(
                    "Catalog hash index size must not be negative");
        }
        if (size == 0) {
            return 1;
        }
        if (size > (MAX_TABLE_CAPACITY >>> 1)) {
            throw new IllegalArgumentException(
                    "Catalog hash index capacity exceeds the supported range");
        }

        int required = size << 1;
        if (required <= 2) {
            return 2;
        }
        return Integer.highestOneBit(required - 1) << 1;
    }

    static int digestHash(byte[] digest, int offset) {
        Objects.requireNonNull(digest, "digest");
        Objects.checkFromIndexSize(offset, PgPackedCatalogHashes.MD5_BYTES,
                digest.length);
        int result = 1;
        int end = offset + PgPackedCatalogHashes.MD5_BYTES;
        for (int i = offset; i < end; i++) {
            result = 31 * result + digest[i];
        }
        return result ^ result >>> 16;
    }

    static final class DuplicateHashException extends Exception {

        private static final long serialVersionUID = 1L;

        DuplicateHashException(int firstIndex, int duplicateIndex) {
            super("Duplicate catalog hash at indexes " + firstIndex + " and "
                    + duplicateIndex);
        }
    }
}
