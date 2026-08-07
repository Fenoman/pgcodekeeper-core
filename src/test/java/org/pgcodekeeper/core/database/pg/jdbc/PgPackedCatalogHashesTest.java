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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.monitor.NullMonitor;

class PgPackedCatalogHashesTest {

    private static final String HASH_A = "00112233445566778899aabbccddeeff";
    private static final String HASH_B = "ffeeddccbbaa99887766554433221100";
    private static final String HASH_C = "0123456789abcdeffedcba9876543210";

    @Test
    void keepsSixteenByteDigestsInExactOrderWithoutStrings() throws Exception {
        byte[] bytes = HexFormat.of().parseHex(HASH_B + HASH_A);

        var hashes = PgPackedCatalogHashes.takeOwnership(2L, bytes, null);

        assertEquals(2, hashes.size());
        assertFalse(hashes.isEmpty());
        assertSame(bytes, hashes.rawBytesForCache());
        assertEquals(HASH_B, hashes.hexAt(0));
        assertEquals(HASH_A, hashes.hexAt(1));
    }

    @Test
    void orderedFingerprintMatchesMd5OfLowerHexStream() throws Exception {
        byte[] bytes = HexFormat.of().parseHex(HASH_B + HASH_A);
        var hashes = PgPackedCatalogHashes.takeOwnership(2L, bytes, null);
        byte[] expected = MessageDigest.getInstance("MD5")
                .digest((HASH_B + HASH_A).getBytes(StandardCharsets.US_ASCII));

        assertArrayEquals(expected, hashes.orderedFingerprint());
    }

    @Test
    void emptyFingerprintMatchesMd5OfEmptyHexStream() throws Exception {
        var hashes = PgPackedCatalogHashes.takeOwnership(0L, new byte[0], null);

        assertTrue(hashes.isEmpty());
        assertArrayEquals(MessageDigest.getInstance("MD5").digest(),
                hashes.orderedFingerprint());
    }

    @Test
    void validatesCountAndPackedPayloadBeforeTakingOwnership() {
        assertThrows(IllegalArgumentException.class,
                () -> PgPackedCatalogHashes.takeOwnership(-1L, new byte[0], null));
        assertThrows(IllegalArgumentException.class,
                () -> PgPackedCatalogHashes.takeOwnership(0L, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> PgPackedCatalogHashes.takeOwnership(Long.MAX_VALUE,
                        new byte[0], null));
        assertThrows(IllegalArgumentException.class,
                () -> PgPackedCatalogHashes.takeOwnership(2L,
                        HexFormat.of().parseHex(HASH_A), null));
        assertThrows(IllegalArgumentException.class,
                () -> PgPackedCatalogHashes.takeOwnership(1L,
                        HexFormat.of().parseHex(HASH_A + HASH_B), null));
    }

    @Test
    void checksCancellationBetweenDigestBlocks() {
        var checks = new AtomicInteger();
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return checks.incrementAndGet() == 2;
            }
        };

        assertThrows(InterruptedException.class,
                () -> PgPackedCatalogHashes.takeOwnership(2L,
                        HexFormat.of().parseHex(HASH_A + HASH_B), monitor));
        assertEquals(2, checks.get());
    }

    @Test
    void contentAndDigestComparisonsUseAllSixteenBytes() throws Exception {
        var first = PgPackedCatalogHashes.takeOwnership(1L,
                HexFormat.of().parseHex(HASH_A), null);
        var equal = PgPackedCatalogHashes.takeOwnership(1L,
                HexFormat.of().parseHex(HASH_A), null);
        var different = PgPackedCatalogHashes.takeOwnership(1L,
                HexFormat.of().parseHex(HASH_B), null);
        byte[] pair = HexFormat.of().parseHex(HASH_B + HASH_A);

        assertTrue(first.contentEquals(equal));
        assertFalse(first.contentEquals(different));
        assertTrue(first.equalsDigestAt(0, pair,
                PgPackedCatalogHashes.MD5_BYTES));
        assertFalse(first.equalsDigestAt(0, pair, 0));
    }

    @Test
    void rejectsInvalidDigestIndexesAndRanges() throws Exception {
        var hashes = PgPackedCatalogHashes.takeOwnership(1L,
                HexFormat.of().parseHex(HASH_A), null);

        assertThrows(IndexOutOfBoundsException.class, () -> hashes.hexAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> hashes.hexAt(1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> hashes.equalsDigestAt(0, new byte[15], 0));
        assertThrows(NullPointerException.class,
                () -> hashes.equalsDigestAt(0, null, 0));
    }

    @Test
    void binaryIndexFindsOldRowsAndMarksMissesWithoutHashObjects() throws Exception {
        var oldHashes = PgPackedCatalogHashes.takeOwnership(2L,
                HexFormat.of().parseHex(HASH_B + HASH_A), null);
        var currentHashes = PgPackedCatalogHashes.takeOwnership(3L,
                HexFormat.of().parseHex(HASH_A + HASH_C + HASH_B), null);
        var index = PgPackedCatalogHashIndex.build(oldHashes, null);

        assertEquals(1, index.find(currentHashes, 0));
        assertEquals(-1, index.find(currentHashes, 1));
        assertEquals(0, index.find(currentHashes, 2));
        assertEquals(1, index.find(HexFormat.of().parseHex(HASH_A), 0));
    }

    @Test
    void binaryIndexRejectsDuplicateFullDigests() throws Exception {
        var hashes = PgPackedCatalogHashes.takeOwnership(3L,
                HexFormat.of().parseHex(HASH_A + HASH_B + HASH_A), null);

        var failure = assertThrows(
                PgPackedCatalogHashIndex.DuplicateHashException.class,
                () -> PgPackedCatalogHashIndex.build(hashes, null));

        assertEquals("Duplicate catalog hash at indexes 0 and 2",
                failure.getMessage());
    }

    @Test
    void binaryIndexComparesAllSixteenBytesAfterIntHashCollision() throws Exception {
        byte[] first = new byte[PgPackedCatalogHashes.MD5_BYTES];
        byte[] second = new byte[PgPackedCatalogHashes.MD5_BYTES];
        first[15] = 31;
        second[14] = 1;
        assertEquals(PgPackedCatalogHashIndex.digestHash(first, 0),
                PgPackedCatalogHashIndex.digestHash(second, 0));
        byte[] packed = new byte[first.length + second.length];
        System.arraycopy(first, 0, packed, 0, first.length);
        System.arraycopy(second, 0, packed, first.length, second.length);
        var hashes = PgPackedCatalogHashes.takeOwnership(2L, packed, null);

        var index = PgPackedCatalogHashIndex.build(hashes, null);

        assertEquals(0, index.find(first, 0));
        assertEquals(1, index.find(second, 0));
    }

    @Test
    void binaryIndexChecksCancellationBetweenInsertions() throws Exception {
        var hashes = PgPackedCatalogHashes.takeOwnership(2L,
                HexFormat.of().parseHex(HASH_A + HASH_B), null);
        var checks = new AtomicInteger();
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return checks.incrementAndGet() == 2;
            }
        };

        assertThrows(InterruptedException.class,
                () -> PgPackedCatalogHashIndex.build(hashes, monitor));
        assertEquals(2, checks.get());
    }

    @Test
    void binaryIndexRejectsCapacityOverflowBeforeAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> PgPackedCatalogHashIndex.tableCapacityFor(Integer.MAX_VALUE));
    }
}
