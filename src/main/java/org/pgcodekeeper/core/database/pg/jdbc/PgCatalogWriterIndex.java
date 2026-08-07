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

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Segmented writer-side index. Growing it retains old chunks and adds one new
 * chunk, so no complete primitive index copy exists at any point.
 */
final class PgCatalogWriterIndex {

    private static final int CHUNK_ROWS = 4096;
    private static final int OWNED_ROW_PRIMITIVE_BYTES =
            PgPackedCatalogHashes.MD5_BYTES + Long.BYTES + Integer.BYTES;
    private static final int BORROWED_ROW_PRIMITIVE_BYTES =
            Long.BYTES + Integer.BYTES;
    private static final IndexChunk[] EMPTY_CHUNKS = new IndexChunk[0];

    private final PgPackedCatalogHashes borrowedHashes;

    private IndexChunk[] chunks = new IndexChunk[4];
    private int chunkCount;
    private int capacity;

    PgCatalogWriterIndex() {
        this(null);
    }

    PgCatalogWriterIndex(PgPackedCatalogHashes borrowedHashes) {
        this.borrowedHashes = borrowedHashes;
    }

    void ensureCapacity(int needed,
            PgCatalogReaderPackWriter.IndexGrowthHook hook) {
        if (needed <= capacity) {
            return;
        }
        if (borrowedHashes != null && needed > borrowedHashes.size()) {
            throw new IllegalArgumentException(
                    "Catalog pack row exceeds the borrowed hash list");
        }
        int nextCapacity = capacityAfterGrowth(needed);
        int chunkRows = nextCapacity - capacity;
        if (chunkCount == chunks.length) {
            chunks = Arrays.copyOf(chunks, chunks.length << 1);
        }
        long retainedBefore = retainedBytes();
        var chunk = new IndexChunk(capacity, chunkRows,
                borrowedHashes == null);
        chunks[chunkCount++] = chunk;
        capacity += chunkRows;
        if (hook != null) {
            hook.indexChunkAllocated(retainedBefore, chunk.retainedBytes(),
                    retainedBytes());
        }
    }

    int capacityAfterGrowth(int needed) {
        if (needed <= capacity) {
            return capacity;
        }
        if (borrowedHashes != null && needed > borrowedHashes.size()) {
            throw new IllegalArgumentException(
                    "Catalog pack row exceeds the borrowed hash list");
        }
        int chunkRows = Math.min(CHUNK_ROWS,
                PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS - capacity);
        if (chunkRows <= 0 || needed > capacity + chunkRows) {
            throw new IllegalArgumentException(
                    "Catalog pack writer index is full");
        }
        return capacity + chunkRows;
    }

    int capacity() {
        return capacity;
    }

    long retainedBytes() {
        return (long) capacity * (borrowedHashes == null
                ? OWNED_ROW_PRIMITIVE_BYTES : BORROWED_ROW_PRIMITIVE_BYTES);
    }

    static long borrowedRetainedBytesForRows(int rows) {
        if (rows < 0
                || rows > PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS) {
            throw new IllegalArgumentException(
                    "Invalid borrowed catalog writer row count");
        }
        if (rows == 0) {
            return 0L;
        }
        long chunks = ((long) rows + CHUNK_ROWS - 1L) / CHUNK_ROWS;
        long capacity = Math.min(Math.multiplyExact(chunks, CHUNK_ROWS),
                PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS);
        return Math.multiplyExact(capacity, BORROWED_ROW_PRIMITIVE_BYTES);
    }

    void set(int row, byte[] digest, int digestOffset, long offset,
            int length) {
        IndexChunk chunk = chunk(row);
        int local = row - chunk.firstRow;
        if (borrowedHashes == null) {
            System.arraycopy(digest, digestOffset, chunk.hashes,
                    local * PgPackedCatalogHashes.MD5_BYTES,
                    PgPackedCatalogHashes.MD5_BYTES);
        } else if (!borrowedHashes.equalsDigestAt(row, digest,
                digestOffset)) {
            throw new IllegalArgumentException(
                    "Catalog pack append order does not match borrowed hashes");
        }
        chunk.offsets[local] = offset;
        chunk.lengths[local] = length;
    }

    void putEntry(int row, ByteBuffer target) {
        IndexChunk chunk = chunk(row);
        int local = row - chunk.firstRow;
        if (borrowedHashes == null) {
            target.put(chunk.hashes,
                    local * PgPackedCatalogHashes.MD5_BYTES,
                    PgPackedCatalogHashes.MD5_BYTES);
        } else {
            target.put(borrowedHashes.rawBytesForCache(),
                    borrowedHashes.offsetOf(row),
                    PgPackedCatalogHashes.MD5_BYTES);
        }
        target.putLong(chunk.offsets[local])
                .putInt(chunk.lengths[local]);
    }

    int digestHash(int row) {
        if (borrowedHashes != null) {
            return PgPackedCatalogHashIndex.digestHash(
                    borrowedHashes.rawBytesForCache(),
                    borrowedHashes.offsetOf(row));
        }
        IndexChunk chunk = chunk(row);
        return PgPackedCatalogHashIndex.digestHash(chunk.hashes,
                (row - chunk.firstRow) * PgPackedCatalogHashes.MD5_BYTES);
    }

    boolean digestEquals(int leftRow, int rightRow) {
        if (borrowedHashes != null) {
            return borrowedHashes.equalsDigestAt(leftRow,
                    borrowedHashes.rawBytesForCache(),
                    borrowedHashes.offsetOf(rightRow));
        }
        IndexChunk left = chunk(leftRow);
        IndexChunk right = chunk(rightRow);
        int leftOffset = (leftRow - left.firstRow)
                * PgPackedCatalogHashes.MD5_BYTES;
        int rightOffset = (rightRow - right.firstRow)
                * PgPackedCatalogHashes.MD5_BYTES;
        return Arrays.equals(left.hashes, leftOffset,
                leftOffset + PgPackedCatalogHashes.MD5_BYTES,
                right.hashes, rightOffset,
                rightOffset + PgPackedCatalogHashes.MD5_BYTES);
    }

    void release() {
        Arrays.fill(chunks, null);
        chunks = EMPTY_CHUNKS;
        chunkCount = 0;
        capacity = 0;
    }

    private IndexChunk chunk(int row) {
        if (row < 0 || row >= capacity) {
            throw new IndexOutOfBoundsException("Catalog pack row is outside the writer index");
        }
        int ordinal = row / CHUNK_ROWS;
        if (ordinal >= chunkCount) {
            throw new IndexOutOfBoundsException("Catalog pack row has no writer index chunk");
        }
        return chunks[ordinal];
    }

    private static final class IndexChunk {

        private final int firstRow;
        private final byte[] hashes;
        private final long[] offsets;
        private final int[] lengths;

        private IndexChunk(int firstRow, int rows, boolean retainHashes) {
            this.firstRow = firstRow;
            hashes = retainHashes ? new byte[Math.multiplyExact(rows,
                    PgPackedCatalogHashes.MD5_BYTES)] : null;
            offsets = new long[rows];
            lengths = new int[rows];
        }

        private long retainedBytes() {
            return (hashes == null ? 0L : hashes.length)
                    + (long) offsets.length * Long.BYTES
                    + (long) lengths.length * Integer.BYTES;
        }
    }
}
