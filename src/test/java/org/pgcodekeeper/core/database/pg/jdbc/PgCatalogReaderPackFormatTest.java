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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.monitor.NullMonitor;

class PgCatalogReaderPackFormatTest {

    private static final int MAX_ROW_BYTES = 1024 * 1024;
    private static final HexFormat HEX = HexFormat.of();

    @TempDir
    Path tempDir;

    @Test
    void roundTripStoresLabelsOnceAndRowsInExactHashOrder() throws Exception {
        Path pack = tempDir.resolve("catalog.tmp");
        String[] labels = { "nspname", "relname", "oid" };
        byte[][] hashes = {
                HEX.parseHex("000102030405060708090a0b0c0d0e0f"),
                HEX.parseHex("101112131415161718191a1b1c1d1e1f"),
                HEX.parseHex("202122232425262728292a2b2c2d2e2f")
        };
        Object[][] rows = {
                { "public", "one", 11L },
                { "app", "two", 22L },
                { "rep", "three", 33L }
        };

        PgCatalogReaderPackManifest manifest;
        long bytesWritten;
        try (var writer = new PgCatalogReaderPackWriter(pack, labels,
                MAX_ROW_BYTES)) {
            for (int i = 0; i < rows.length; i++) {
                writer.append(hashes[i], 0, rows[i]);
            }
            manifest = writer.finish(UUID.randomUUID());
            bytesWritten = writer.bytesWritten();
        }

        assertEquals(Files.size(pack), bytesWritten);
        assertEquals(bytesWritten, manifest.packSize());
        assertEquals(rows.length, manifest.rowCount());
        byte[] file = Files.readAllBytes(pack);
        for (String label : labels) {
            assertEquals(1, occurrences(file,
                    label.getBytes(StandardCharsets.UTF_8)));
        }

        try (var reader = PgCatalogReaderPackReader.open(pack, manifest)) {
            assertArrayEquals(labels, reader.labels());
            assertEquals(rows.length, reader.rowCount());
            assertEquals(bytesWritten, reader.packBytes());
            assertArrayEquals(manifest.orderedFingerprint(),
                    reader.orderedFingerprint());
            for (int i = 0; i < rows.length; i++) {
                assertTrue(reader.hashes().equalsDigestAt(i, hashes[i], 0));
                PgCachedCatalogRow row = reader.readRow(i);
                assertNotNull(row);
                assertArrayEquals(labels, row.labels());
                assertArrayEquals(rows[i], row.values());
            }
        }
        assertFalse(Files.exists(tempDir.resolve("catalog.tmp.tmp")));
    }

    /**
     * A privilege column survives the full serialize - file - deserialize -
     * replay path with the exact text pgJDBC returned, including the quoting
     * a hand-written array-literal renderer would get wrong.
     */
    @Test
    void capturedArrayTextSurvivesTheWholePackRoundTrip() throws Exception {
        Path pack = tempDir.resolve("acl.tmp");
        String[] labels = {"nspname", "nspacl", "reloptions"};
        String aclText = "{\"we\\\"ird=UC/postgres\",=U/postgres,"
                + "\"a,b=U/postgres\",\"back\\\\slash=U/postgres\"}";
        Object[][] rows = {
                {"public", new PgCachedCatalogArray(
                        new String[] {"we\"ird=UC/postgres", "=U/postgres",
                                "a,b=U/postgres", "back\\slash=U/postgres"},
                        aclText),
                        new PgCachedCatalogArray(new String[0], "{}")},
                {"empty", new PgCachedCatalogArray(
                        new String[] {null, "NULL"}, "{NULL,\"NULL\"}"),
                        new PgCachedCatalogArray(
                                new String[] {"fillfactor=70"},
                                "{fillfactor=70}")}
        };
        byte[][] hashes = {
                HEX.parseHex("0f0e0d0c0b0a09080706050403020100"),
                HEX.parseHex("1f1e1d1c1b1a19181716151413121110")
        };

        PgCatalogReaderPackManifest manifest;
        try (var writer = new PgCatalogReaderPackWriter(pack, labels,
                MAX_ROW_BYTES)) {
            for (int i = 0; i < rows.length; i++) {
                writer.append(hashes[i], 0, rows[i]);
            }
            manifest = writer.finish(UUID.randomUUID());
        }

        try (var reader = PgCatalogReaderPackReader.open(pack, manifest)) {
            for (int i = 0; i < rows.length; i++) {
                PgCachedCatalogRow row = reader.readRow(i);
                assertNotNull(row);
                var replay = PgCachedRowResultSet.positioned(row);
                for (int column = 1; column < labels.length; column++) {
                    var expected = (PgCachedCatalogArray) rows[i][column];
                    assertEquals(expected.text(),
                            replay.getString(labels[column]));
                    assertArrayEquals((Object[]) expected.elements(),
                            (Object[]) replay.getArray(labels[column])
                                    .getArray());
                }
                assertEquals(rows[i][0], replay.getString("nspname"));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(StructuralCorruption.class)
    void rejectsStructuralCorruptionBeforeReadingRows(
            StructuralCorruption corruption) throws Exception {
        Fixture fixture = fixture("structural-" + corruption.name());
        byte[] bytes = Files.readAllBytes(fixture.pack());
        corruption.apply(bytes);
        Files.write(fixture.pack(), bytes);

        assertThrows(InvalidPackException.class,
                () -> PgCatalogReaderPackReader.open(
                        fixture.pack(), fixture.manifest()));
    }

    @ParameterizedTest
    @EnumSource(SemanticCorruption.class)
    void rejectsHostileMetadataEvenWhenAffectedChecksumsAreRepaired(
            SemanticCorruption corruption) throws Exception {
        Fixture fixture = fixture("semantic-" + corruption.name());
        byte[] bytes = Files.readAllBytes(fixture.pack());
        corruption.apply(bytes, fixture.manifest().rowCount());
        Files.write(fixture.pack(), bytes);

        assertThrows(InvalidPackException.class,
                () -> PgCatalogReaderPackReader.open(
                        fixture.pack(), fixture.manifest()));
    }

    @Test
    void rejectsManifestMismatchesBeforeReadingRows() throws Exception {
        Fixture fixture = fixture("manifest");
        PgCatalogReaderPackManifest valid = fixture.manifest();
        byte[] otherFingerprint = valid.orderedFingerprint();
        otherFingerprint[0] ^= 1;

        assertThrows(InvalidPackException.class,
                () -> PgCatalogReaderPackReader.open(fixture.pack(),
                        new PgCatalogReaderPackManifest(valid.generationId(),
                                valid.packSize() + 1, valid.rowCount(),
                                valid.orderedFingerprint())));
        assertThrows(InvalidPackException.class,
                () -> PgCatalogReaderPackReader.open(fixture.pack(),
                        new PgCatalogReaderPackManifest(valid.generationId(),
                                valid.packSize(), valid.rowCount() + 1,
                                valid.orderedFingerprint())));
        assertThrows(InvalidPackException.class,
                () -> PgCatalogReaderPackReader.open(fixture.pack(),
                        new PgCatalogReaderPackManifest(valid.generationId(),
                                valid.packSize(), valid.rowCount(),
                                otherFingerprint)));
    }

    @Test
    void rejectsStrictUtf8ViolationEvenWithValidLabelsChecksum()
            throws Exception {
        Fixture fixture = fixture("utf8");
        byte[] bytes = Files.readAllBytes(fixture.pack());
        int labelsLength = intAt(bytes, 20);
        bytes[PgCatalogReaderPackFormat.HEADER_FIXED_BYTES + Integer.BYTES] =
                (byte) 0xC0;
        putInt(bytes, PgCatalogReaderPackFormat.HEADER_FIXED_BYTES + labelsLength,
                PgCatalogReaderPackFormat.crc32(bytes,
                        PgCatalogReaderPackFormat.HEADER_FIXED_BYTES,
                        labelsLength));
        Files.write(fixture.pack(), bytes);

        assertThrows(InvalidPackException.class,
                () -> PgCatalogReaderPackReader.open(
                        fixture.pack(), fixture.manifest()));
    }

    @Test
    void corruptRowReturnsNullWithoutHidingOtherRows() throws Exception {
        Fixture fixture = fixture("row-crc");
        byte[] bytes = Files.readAllBytes(fixture.pack());
        int indexOffset = Math.toIntExact(indexOffset(bytes));
        int firstRowOffset = Math.toIntExact(longAt(bytes,
                indexOffset + PgPackedCatalogHashes.MD5_BYTES));
        bytes[firstRowOffset + Integer.BYTES] ^= 1;
        Files.write(fixture.pack(), bytes);

        try (var reader = PgCatalogReaderPackReader.open(
                fixture.pack(), fixture.manifest())) {
            assertNull(reader.readRow(0));
            assertNotNull(reader.readRow(1));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { -1, PgCatalogReaderPackFormat.MAX_VALUES_BYTES + 1 })
    void invalidValuesLengthWithValidRowChecksumOnlyInvalidatesThatRow(
            int valuesLength) throws Exception {
        Fixture fixture = fixture("values-length-" + valuesLength);
        byte[] bytes = Files.readAllBytes(fixture.pack());
        int indexOffset = Math.toIntExact(indexOffset(bytes));
        int rowOffset = Math.toIntExact(longAt(bytes,
                indexOffset + PgPackedCatalogHashes.MD5_BYTES));
        int recordLength = intAt(bytes,
                indexOffset + PgPackedCatalogHashes.MD5_BYTES + Long.BYTES);
        putInt(bytes, rowOffset, valuesLength);
        putInt(bytes, rowOffset + recordLength - Integer.BYTES,
                PgCatalogReaderPackFormat.crc32(bytes, rowOffset,
                        recordLength - Integer.BYTES));
        Files.write(fixture.pack(), bytes);

        try (var reader = PgCatalogReaderPackReader.open(
                fixture.pack(), fixture.manifest())) {
            assertNull(reader.readRow(0));
            assertNotNull(reader.readRow(1));
        }
    }

    @Test
    void rejectsDuplicateDigestWithConsistentChecksumsAndFingerprint()
            throws Exception {
        Fixture fixture = fixture("duplicate");
        byte[] bytes = Files.readAllBytes(fixture.pack());
        int indexOffset = Math.toIntExact(indexOffset(bytes));
        System.arraycopy(bytes, indexOffset, bytes,
                indexOffset + PgCatalogReaderPackFormat.INDEX_ENTRY_BYTES,
                PgPackedCatalogHashes.MD5_BYTES);
        updateIndexCrc(bytes, indexOffset, fixture.manifest().rowCount());

        byte[] packed = new byte[fixture.manifest().rowCount()
                * PgPackedCatalogHashes.MD5_BYTES];
        for (int row = 0; row < fixture.manifest().rowCount(); row++) {
            System.arraycopy(bytes,
                    indexOffset + row * PgCatalogReaderPackFormat.INDEX_ENTRY_BYTES,
                    packed, row * PgPackedCatalogHashes.MD5_BYTES,
                    PgPackedCatalogHashes.MD5_BYTES);
        }
        byte[] fingerprint = PgPackedCatalogHashes
                .takeOwnership(fixture.manifest().rowCount(), packed, null)
                .orderedFingerprint();
        int footerOffset = bytes.length - PgCatalogReaderPackFormat.FOOTER_BYTES;
        System.arraycopy(fingerprint, 0, bytes, footerOffset + 24,
                fingerprint.length);
        updateFooterCrc(bytes);
        Files.write(fixture.pack(), bytes);
        var manifest = new PgCatalogReaderPackManifest(
                fixture.manifest().generationId(), bytes.length,
                fixture.manifest().rowCount(), fingerprint);

        assertThrows(InvalidPackException.class,
                () -> PgCatalogReaderPackReader.open(fixture.pack(), manifest));
    }

    @Test
    void rejectsNonContiguousRowsWithValidIndexChecksum() throws Exception {
        Fixture fixture = fixture("overlap");
        byte[] bytes = Files.readAllBytes(fixture.pack());
        int indexOffset = Math.toIntExact(indexOffset(bytes));
        long firstOffset = longAt(bytes,
                indexOffset + PgPackedCatalogHashes.MD5_BYTES);
        putLong(bytes,
                indexOffset + PgCatalogReaderPackFormat.INDEX_ENTRY_BYTES
                        + PgPackedCatalogHashes.MD5_BYTES,
                firstOffset);
        updateIndexCrc(bytes, indexOffset, fixture.manifest().rowCount());
        Files.write(fixture.pack(), bytes);

        assertThrows(InvalidPackException.class,
                () -> PgCatalogReaderPackReader.open(
                        fixture.pack(), fixture.manifest()));
    }

    @Test
    void rejectsTruncationAtEverySectionBoundary() throws Exception {
        Fixture fixture = fixture("truncate");
        byte[] bytes = Files.readAllBytes(fixture.pack());
        int labelsLength = intAt(bytes, 20);
        int rowsOffset = PgCatalogReaderPackFormat.HEADER_FIXED_BYTES
                + labelsLength + Integer.BYTES;
        int indexOffset = Math.toIntExact(indexOffset(bytes));
        int footerOffset = bytes.length - PgCatalogReaderPackFormat.FOOTER_BYTES;
        int[] sizes = { 1, PgCatalogReaderPackFormat.HEADER_FIXED_BYTES - 1,
                rowsOffset - 1, indexOffset - 1, footerOffset + 1,
                bytes.length - 1 };
        for (int i = 0; i < sizes.length; i++) {
            Path truncated = tempDir.resolve("truncated-" + i + ".bin");
            Files.write(truncated, Arrays.copyOf(bytes, sizes[i]));
            var manifest = new PgCatalogReaderPackManifest(
                    fixture.manifest().generationId(), sizes[i],
                    fixture.manifest().rowCount(),
                    fixture.manifest().orderedFingerprint());
            assertThrows(InvalidPackException.class,
                    () -> PgCatalogReaderPackReader.open(truncated, manifest));
        }
    }

    @Test
    void rejectsResidentIndexOver64MiBBeforeAllocatingArrays() throws Exception {
        int rowCount = 1_800_000;
        long residentBytes = (long) rowCount
                * (PgPackedCatalogHashes.MD5_BYTES + Long.BYTES + Integer.BYTES)
                + (long) PgPackedCatalogHashIndex.tableCapacityFor(rowCount)
                        * Integer.BYTES;
        assertTrue(residentBytes
                > PgCatalogReaderPackFormat.RESIDENT_INDEX_BUDGET_BYTES);

        Path pack = tempDir.resolve("oversized-index.bin");
        long indexOffset = PgCatalogReaderPackFormat.HEADER_FIXED_BYTES
                + Integer.BYTES;
        long entriesBytes = (long) rowCount
                * PgCatalogReaderPackFormat.INDEX_ENTRY_BYTES;
        long footerOffset = indexOffset + entriesBytes + Integer.BYTES;
        try (FileChannel channel = FileChannel.open(pack,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer header = ByteBuffer.allocate((int) indexOffset)
                    .put(PgCatalogReaderPackFormat.HEADER_MAGIC)
                    .putInt(PgCatalogReaderPackFormat.PACK_FORMAT_VERSION)
                    .putInt(PgCatalogRowValueCodec.FORMAT_VERSION)
                    .putInt(0).putInt(0).putInt(0).flip();
            while (header.hasRemaining()) {
                channel.write(header);
            }
            var crc = new CRC32();
            byte[] zeroes = new byte[8192];
            long remaining = entriesBytes;
            while (remaining > 0) {
                int amount = (int) Math.min(remaining, zeroes.length);
                crc.update(zeroes, 0, amount);
                remaining -= amount;
            }
            channel.position(indexOffset + entriesBytes);
            channel.write(ByteBuffer.allocate(Integer.BYTES)
                    .putInt((int) crc.getValue()).flip());
            byte[] fingerprint = MessageDigest.getInstance("MD5").digest();
            ByteBuffer footer = footer(indexOffset, rowCount, fingerprint);
            channel.position(footerOffset);
            while (footer.hasRemaining()) {
                channel.write(footer);
            }
        }
        byte[] fingerprint = MessageDigest.getInstance("MD5").digest();
        var manifest = new PgCatalogReaderPackManifest(UUID.randomUUID(),
                Files.size(pack), rowCount, fingerprint);

        InvalidPackException ex = assertThrows(InvalidPackException.class,
                () -> PgCatalogReaderPackReader.open(pack, manifest));
        assertTrue(ex.getMessage().contains("64 MiB"));
    }

    @Test
    void exactResidentIndexBoundaryIsSharedByWriterAndReader()
            throws Exception {
        int maximum = PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS;
        assertTrue(PgCatalogReaderPackFormat.residentIndexBytes(maximum)
                <= PgCatalogReaderPackFormat.RESIDENT_INDEX_BUDGET_BYTES);
        assertTrue(PgCatalogReaderPackFormat.residentIndexBytes(maximum + 1)
                > PgCatalogReaderPackFormat.RESIDENT_INDEX_BUDGET_BYTES);
        assertTrue(PgCatalogReaderPackFormat.writerResidentIndexBytes(
                maximum, maximum)
                <= PgCatalogReaderPackFormat.RESIDENT_INDEX_BUDGET_BYTES);
        assertTrue(PgCatalogReaderPackFormat.writerResidentIndexBytes(
                maximum, maximum + 1)
                > PgCatalogReaderPackFormat.RESIDENT_INDEX_BUDGET_BYTES);

        Path temporary = tempDir.resolve("writer-limit.tmp");
        var writer = new PgCatalogReaderPackWriter(temporary,
                new String[] { "value" }, MAX_ROW_BYTES);
        var rowCount = PgCatalogReaderPackWriter.class
                .getDeclaredField("rowCount");
        rowCount.setAccessible(true);
        rowCount.setInt(writer, maximum);
        assertThrows(IOException.class,
                () -> writer.append(new byte[16], 0,
                        new Object[] { "over limit" }));
        assertFalse(Files.exists(temporary));
        assertThrows(IllegalStateException.class,
                () -> writer.finish(UUID.randomUUID()));

        Path finishTemporary = tempDir.resolve("writer-finish-limit.tmp");
        var finishWriter = new PgCatalogReaderPackWriter(finishTemporary,
                new String[] { "value" }, MAX_ROW_BYTES);
        rowCount.setInt(finishWriter, maximum + 1);
        assertThrows(IOException.class,
                () -> finishWriter.finish(UUID.randomUUID()));
        assertFalse(Files.exists(finishTemporary));
    }

    @Test
    void borrowedHashesAvoidASecondWriterHashArray() throws Exception {
        byte[] packed = HexFormat.of().parseHex("11".repeat(16) + "22".repeat(16));
        PgPackedCatalogHashes hashes = PgPackedCatalogHashes.takeOwnership(
                2, packed, null);
        Path temporary = tempDir.resolve("borrowed.tmp");
        var writer = new PgCatalogReaderPackWriter(temporary,
                new String[] { "value" }, MAX_ROW_BYTES, hashes);

        writer.append(packed, 0, new Object[] { "one" });
        writer.append(packed, PgPackedCatalogHashes.MD5_BYTES,
                new Object[] { "two" });

        assertEquals(4096L * (Long.BYTES + Integer.BYTES),
                writer.retainedIndexBytesForTest());
        PgCatalogReaderPackManifest manifest = writer.finish(UUID.randomUUID());
        try (var reader = PgCatalogReaderPackReader.open(temporary, manifest)) {
            assertEquals("one", reader.readValues(0)[0]);
            assertEquals("two", reader.readValues(1)[0]);
        }
    }

    @Test
    void readerCanReleaseHashLookupBeforeOrderedReplay() throws Exception {
        Fixture fixture = fixture("release-lookup.bin");
        try (var reader = PgCatalogReaderPackReader.open(
                fixture.pack(), fixture.manifest())) {
            long before = reader.retainedIndexBytesForTest();
            byte[] firstHash = reader.hashes().rawBytesForCache().clone();

            reader.releaseLookupIndex();

            assertTrue(reader.retainedIndexBytesForTest() < before);
            assertEquals("public", reader.readValues(0)[0]);
            assertArrayEquals(firstHash,
                    reader.hashes().rawBytesForCache());
            assertThrows(IllegalStateException.class,
                    () -> reader.findRow(new byte[16], 0));
        }
    }

    @Test
    void exactReplayOpensWithoutAllocatingHashLookupSlots() throws Exception {
        Fixture fixture = fixture("replay-no-lookup.bin");
        try (var reader = PgCatalogReaderPackReader.openForReplay(
                fixture.pack(), fixture.manifest(), null)) {
            long expected = (long) fixture.manifest().rowCount()
                    * (PgPackedCatalogHashes.MD5_BYTES
                            + Long.BYTES + Integer.BYTES);

            assertEquals(expected, reader.retainedIndexBytesForTest());
            assertEquals("public", reader.readValues(0)[0]);
            assertNotNull(reader.hashes());
            assertThrows(IllegalStateException.class,
                    () -> reader.findRow(new byte[16], 0));
        }
    }

    @Test
    void combinedWarmChangedHardPeakHasAnExact128MiBBoundary() {
        int low = 0;
        int high = PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (PgCatalogRowCacheMemoryBudget.withinBudget(middle, middle)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }

        assertTrue(low > 0);
        assertTrue(PgCatalogRowCacheMemoryBudget.estimatedPeakBytes(low, low)
                <= PgCatalogRowCacheMemoryBudget.MAX_WARM_CHANGED_BYTES);
        assertFalse(PgCatalogRowCacheMemoryBudget.withinBudget(
                low + 1, low + 1));
        assertTrue(PgCatalogRowCacheMemoryBudget.estimatedPeakBytes(
                low + 1, low + 1)
                > PgCatalogRowCacheMemoryBudget.MAX_WARM_CHANGED_BYTES);
        assertFalse(PgCatalogRowCacheMemoryBudget.withinBudget(
                PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS,
                PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS));
    }

    @Test
    void warmChangedBudgetRetainsAtMostOneBoundedDriverChunk() {
        assertEquals(0L,
                PgCatalogRowCacheMemoryBudget.driverPayloadBytes(0));
        assertEquals(PgPackedCatalogHashes.MD5_BYTES,
                PgCatalogRowCacheMemoryBudget.driverPayloadBytes(1));
        assertEquals(PgCatalogRowCache.HASH_CHUNK_BYTES,
                PgCatalogRowCacheMemoryBudget.driverPayloadBytes(
                        PgCatalogRowCache.HASHES_PER_CHUNK));
        assertEquals(PgCatalogRowCache.HASH_CHUNK_BYTES,
                PgCatalogRowCacheMemoryBudget.driverPayloadBytes(
                        PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS));
    }

    @Test
    void rejectsIndexLengthBeyondSignedFormatLimitWithoutAllocation() {
        int firstInvalidCount = Math.toIntExact(
                (Integer.MAX_VALUE - 8L - Integer.BYTES)
                        / PgCatalogReaderPackFormat.INDEX_ENTRY_BYTES + 1L);
        assertDoesNotThrow(() -> PgCatalogReaderPackFormat.indexBytes(
                firstInvalidCount - 1));
        assertThrows(InvalidPackException.class,
                () -> PgCatalogReaderPackFormat.indexBytes(firstInvalidCount));
    }

    @Test
    void rejectsOverflowingIndexRange() throws Exception {
        Fixture fixture = fixture("overflow");
        byte[] bytes = Files.readAllBytes(fixture.pack());
        int footerOffset = bytes.length - PgCatalogReaderPackFormat.FOOTER_BYTES;
        putLong(bytes, footerOffset + 12, Long.MAX_VALUE);
        updateFooterCrc(bytes);
        Files.write(fixture.pack(), bytes);

        assertThrows(InvalidPackException.class,
                () -> PgCatalogReaderPackReader.open(
                        fixture.pack(), fixture.manifest()));
    }

    @Test
    void vmErrorDuringOpenClosesChannelAndIsRethrownUnchanged()
            throws Exception {
        Fixture fixture = fixture("open-error");
        var openedChannel = new AtomicReference<FileChannel>();
        var expected = new OutOfMemoryError("injected allocation failure");

        OutOfMemoryError actual = assertThrows(OutOfMemoryError.class,
                () -> PgCatalogReaderPackReader.open(fixture.pack(),
                        fixture.manifest(), channel -> {
                            openedChannel.set(channel);
                            throw expected;
                        }));
        assertSame(expected, actual);
        assertNotNull(openedChannel.get());
        assertFalse(openedChannel.get().isOpen());
    }

    @Test
    void vmErrorDuringHashIndexBuildClosesChannelAndIsRethrownUnchanged()
            throws Exception {
        Fixture fixture = fixture("build-error");
        var openedChannel = new AtomicReference<FileChannel>();
        var expected = new OutOfMemoryError("injected index build failure");
        var hook = new PgCatalogReaderPackReader.OpenValidationHook() {
            @Override
            public void beforeIndexAllocation(FileChannel channel) {
                openedChannel.set(channel);
            }

            @Override
            public void beforeHashIndexBuild(FileChannel channel) {
                throw expected;
            }
        };

        OutOfMemoryError actual = assertThrows(OutOfMemoryError.class,
                () -> PgCatalogReaderPackReader.open(fixture.pack(),
                        fixture.manifest(), hook));
        assertSame(expected, actual);
        assertNotNull(openedChannel.get());
        assertFalse(openedChannel.get().isOpen());
    }

    @Test
    void abortOnCancellationDeletesTempAndPreservesExistingPack()
            throws Exception {
        Path existing = tempDir.resolve("published.bin");
        Files.writeString(existing, "last-good");
        Path temporary = tempDir.resolve("next.tmp");
        try (var writer = new PgCatalogReaderPackWriter(temporary,
                new String[] { "value" }, MAX_ROW_BYTES)) {
            writer.append(HEX.parseHex(
                    "000102030405060708090a0b0c0d0e0f"), 0,
                    new Object[] { "new" });
            writer.abort();
        }
        assertFalse(Files.exists(temporary));
        assertEquals("last-good", Files.readString(existing));
    }

    @Test
    void closeWithoutFinishDeletesTemp() throws Exception {
        Path temporary = tempDir.resolve("unfinished.tmp");
        try (var writer = new PgCatalogReaderPackWriter(temporary,
                new String[] { "value" }, MAX_ROW_BYTES)) {
            writer.append(HEX.parseHex(
                    "000102030405060708090a0b0c0d0e0f"), 0,
                    new Object[] { "new" });
        }
        assertFalse(Files.exists(temporary));
    }

    @Test
    void duplicateHashAbortsWriterAndDeletesTemp() throws Exception {
        Path temporary = tempDir.resolve("duplicate.tmp");
        byte[] digest = HEX.parseHex("000102030405060708090a0b0c0d0e0f");
        var writer = new PgCatalogReaderPackWriter(temporary,
                new String[] { "value" }, MAX_ROW_BYTES);
        writer.append(digest, 0, new Object[] { "one" });
        writer.append(digest, 0, new Object[] { "two" });
        assertThrows(IOException.class,
                () -> writer.finish(UUID.randomUUID()));
        assertFalse(Files.exists(temporary));
    }

    @Test
    void manifestAndReaderDefensivelyCopyFingerprintsAndLabels()
            throws Exception {
        Fixture fixture = fixture("ownership");
        byte[] fingerprint = fixture.manifest().orderedFingerprint();
        fingerprint[0] ^= 1;
        assertFalse(Arrays.equals(fingerprint,
                fixture.manifest().orderedFingerprint()));
        try (var reader = PgCatalogReaderPackReader.open(
                fixture.pack(), fixture.manifest())) {
            String[] labels = reader.labels();
            labels[0] = "changed";
            assertEquals("nspname", reader.labels()[0]);
            byte[] readerFingerprint = reader.orderedFingerprint();
            readerFingerprint[0] ^= 1;
            assertFalse(Arrays.equals(readerFingerprint,
                    reader.orderedFingerprint()));
            PgCachedCatalogRow row = reader.readRow(0);
            assertNotNull(row);
            row.labels()[0] = "mutated";
            assertEquals("nspname", reader.labels()[0]);
            assertArrayEquals(new Object[] { "public", "one", 11L },
                    reader.readValues(0));
        }
    }

    @Test
    void batchedIndexRoundTripCrossesMultipleBufferBoundaries()
            throws Exception {
        int rowCount = 4097;
        Path pack = tempDir.resolve("batched.bin");
        PgCatalogReaderPackManifest manifest;
        try (var writer = new PgCatalogReaderPackWriter(pack,
                new String[] { "value" }, MAX_ROW_BYTES)) {
            byte[] digest = new byte[PgPackedCatalogHashes.MD5_BYTES];
            for (int row = 0; row < rowCount; row++) {
                ByteBuffer.wrap(digest).putInt(12, row);
                writer.append(digest, 0, new Object[] { row });
            }
            manifest = writer.finish(UUID.randomUUID());
        }
        try (var reader = PgCatalogReaderPackReader.open(pack, manifest)) {
            assertEquals(rowCount, reader.rowCount());
            assertArrayEquals(new Object[] { 0 }, reader.readRow(0).values());
            assertArrayEquals(new Object[] { 2048 },
                    reader.readRow(2048).values());
            assertArrayEquals(new Object[] { 4096 },
                    reader.readRow(4096).values());
        }
    }

    @Test
    void emptyPackHasValidEmptyFingerprintAndIndex() throws Exception {
        Path pack = tempDir.resolve("empty.bin");
        PgCatalogReaderPackManifest manifest;
        try (var writer = new PgCatalogReaderPackWriter(pack,
                new String[0], MAX_ROW_BYTES)) {
            manifest = writer.finish(UUID.randomUUID());
        }
        assertArrayEquals(MessageDigest.getInstance("MD5").digest(),
                manifest.orderedFingerprint());
        try (var reader = PgCatalogReaderPackReader.open(pack, manifest)) {
            assertEquals(0, reader.rowCount());
            assertArrayEquals(new String[0], reader.labels());
        }
    }

    @Test
    void enforcesWriterColumnLabelAndRowPayloadBounds() throws Exception {
        Path invalidLimit = tempDir.resolve("invalid-limit.tmp");
        assertThrows(IllegalArgumentException.class,
                () -> new PgCatalogReaderPackWriter(invalidLimit,
                        new String[] { "value" }, 0));
        assertFalse(Files.exists(invalidLimit));

        String[] tooManyLabels = new String[Short.MAX_VALUE + 1];
        Arrays.fill(tooManyLabels, "x");
        Path tooMany = tempDir.resolve("too-many.tmp");
        assertThrows(IllegalArgumentException.class,
                () -> new PgCatalogReaderPackWriter(tooMany,
                        tooManyLabels, MAX_ROW_BYTES));
        assertFalse(Files.exists(tooMany));

        Path oversized = tempDir.resolve("oversized.tmp");
        try (var writer = new PgCatalogReaderPackWriter(oversized,
                new String[] { "value" }, 16)) {
            assertThrows(IOException.class,
                    () -> writer.append(new byte[16], 0,
                            new Object[] { "this value is too large" }));
        }
        assertFalse(Files.exists(oversized));

        Path unsupported = tempDir.resolve("unsupported.tmp");
        try (var writer = new PgCatalogReaderPackWriter(unsupported,
                new String[] { "value" }, MAX_ROW_BYTES)) {
            assertThrows(PgCatalogRowCodec.UnsupportedRowValueException.class,
                    () -> writer.append(new byte[16], 0,
                            new Object[] { new Object() }));
        }
        assertFalse(Files.exists(unsupported));
    }

    @Test
    void writerValidatesChunksWithoutCopyAndReleasesRetainedIndex()
            throws Exception {
        var observedSlots = new AtomicInteger();
        var retainedAtValidation = new AtomicReference<Long>();
        Path pack = tempDir.resolve("no-copy.bin");
        var writer = new PgCatalogReaderPackWriter(pack,
                new String[] { "value" }, MAX_ROW_BYTES,
                (retainedBytes, slots) -> {
                    retainedAtValidation.set(retainedBytes);
                    observedSlots.set(slots.length);
                    assertTrue(assertDoesNotThrow(() ->
                            PgCatalogReaderPackFormat.writerResidentIndexBytes(
                                    100, Math.toIntExact(retainedBytes / 28L)))
                            <= PgCatalogReaderPackFormat
                                    .RESIDENT_INDEX_BUDGET_BYTES);
                });
        byte[] digest = new byte[PgPackedCatalogHashes.MD5_BYTES];
        for (int row = 0; row < 100; row++) {
            ByteBuffer.wrap(digest).putInt(12, row);
            writer.append(digest, 0, new Object[] { row });
        }
        writer.finish(UUID.randomUUID());

        assertNotNull(retainedAtValidation.get());
        assertTrue(observedSlots.get() >= 200);
        assertEquals(0, retainedIndexBytes(writer));
    }

    @Test
    void chunkGrowthNeverDuplicatesRetainedPrimitiveStorage() throws Exception {
        var peakBytes = new AtomicReference<Long>(0L);
        var growthEvents = new AtomicInteger();
        var hook = new PgCatalogReaderPackWriter.IndexGrowthHook() {
            @Override
            public void indexChunkAllocated(long retainedBefore,
                    long allocatedBytes, long retainedAfter) {
                growthEvents.incrementAndGet();
                assertEquals(retainedBefore + allocatedBytes, retainedAfter);
                peakBytes.updateAndGet(previous ->
                        Math.max(previous, retainedAfter));
            }
        };
        Path pack = tempDir.resolve("chunk-growth.bin");
        var writer = new PgCatalogReaderPackWriter(pack,
                new String[] { "value" }, MAX_ROW_BYTES,
                (retainedBytes, slots) -> assertTrue(retainedBytes
                        + (long) slots.length * Integer.BYTES
                        <= PgCatalogReaderPackFormat
                                .RESIDENT_INDEX_BUDGET_BYTES),
                hook, new PgCatalogRowValueCodec.ArrayDataOutput());
        byte[] digest = new byte[PgPackedCatalogHashes.MD5_BYTES];
        for (int row = 0; row < 8200; row++) {
            ByteBuffer.wrap(digest).putInt(12, row);
            writer.append(digest, 0, new Object[] { row });
        }
        writer.finish(UUID.randomUUID());

        assertEquals(3, growthEvents.get());
        assertEquals(0, retainedIndexBytes(writer));
        assertEquals(3L * 4096L * 28L, peakBytes.get());
    }

    @Test
    void prospectiveChunkBudgetRejectsBeforeTheAllocationHook()
            throws Exception {
        int chunkRows = 4096;
        long exactBudget = PgCatalogReaderPackFormat
                .writerResidentIndexBytes(chunkRows, chunkRows);
        var growthEvents = new AtomicInteger();
        Path pack = tempDir.resolve("prospective-budget.bin");
        var writer = new PgCatalogReaderPackWriter(pack,
                new String[] { "value" }, MAX_ROW_BYTES,
                (retainedBytes, slots) -> { },
                (retainedBefore, allocatedBytes, retainedAfter) ->
                        growthEvents.incrementAndGet(),
                new PgCatalogRowValueCodec.ArrayDataOutput(), exactBudget);
        byte[] digest = new byte[PgPackedCatalogHashes.MD5_BYTES];
        for (int row = 0; row < chunkRows; row++) {
            ByteBuffer.wrap(digest).putInt(12, row);
            writer.append(digest, 0, new Object[] { row });
        }

        assertEquals(1, growthEvents.get());
        ByteBuffer.wrap(digest).putInt(12, chunkRows);
        assertThrows(IOException.class,
                () -> writer.append(digest, 0, new Object[] { chunkRows }));
        assertEquals(1, growthEvents.get(),
                "the rejected second chunk must never be allocated");
        assertFalse(Files.exists(pack));
    }

    @Test
    void abortAndUnfinishedCloseReleaseRetainedIndexArrays() throws Exception {
        Path aborted = tempDir.resolve("release-abort.tmp");
        var abortedWriter = new PgCatalogReaderPackWriter(aborted,
                new String[] { "value" }, MAX_ROW_BYTES);
        abortedWriter.append(new byte[16], 0, new Object[] { "value" });
        abortedWriter.abort();
        assertEquals(0, retainedIndexBytes(abortedWriter));
        assertFalse(Files.exists(aborted));

        Path closed = tempDir.resolve("release-close.tmp");
        var closedWriter = new PgCatalogReaderPackWriter(closed,
                new String[] { "value" }, MAX_ROW_BYTES);
        closedWriter.append(new byte[16], 0, new Object[] { "value" });
        closedWriter.close();
        assertEquals(0, retainedIndexBytes(closedWriter));
        assertFalse(Files.exists(closed));
    }

    @Test
    void smallRowsUseBatchAndLargeRowUsesBoundedBypass() throws Exception {
        Path pack = tempDir.resolve("row-batches.bin");
        String large = "я".repeat(70_000);
        var output = new TrackingArrayDataOutput();
        PgCatalogReaderPackManifest manifest;
        try (var writer = new PgCatalogReaderPackWriter(pack,
                new String[] { "value" }, MAX_ROW_BYTES,
                (retainedBytes, slots) -> { }, null, output)) {
            byte[] digest = new byte[PgPackedCatalogHashes.MD5_BYTES];
            for (int row = 0; row < 3000; row++) {
                ByteBuffer.wrap(digest).putInt(12, row);
                writer.append(digest, 0, new Object[] { row });
            }
            ByteBuffer.wrap(digest).putInt(12, 3000);
            writer.append(digest, 0, new Object[] { large });
            manifest = writer.finish(UUID.randomUUID());
        }
        try (var reader = PgCatalogReaderPackReader.open(pack, manifest)) {
            assertArrayEquals(new Object[] { 0 }, reader.readValues(0));
            assertArrayEquals(new Object[] { 2999 }, reader.readValues(2999));
            assertArrayEquals(new Object[] { large }, reader.readValues(3000));
        }
        assertEquals(3000, output.batchTargets.get());
        assertEquals(1, output.standaloneTargets.get());
    }

    @Test
    void cancelledAppendAbortsBeforeIndexGrowthOrSerialization()
            throws Exception {
        Path pack = tempDir.resolve("cancel-append.tmp");
        var output = new TrackingArrayDataOutput();
        var writer = new PgCatalogReaderPackWriter(pack,
                new String[] { "value" }, MAX_ROW_BYTES,
                (retainedBytes, slots) -> { }, null, output);
        var monitor = new NullMonitor();
        monitor.setCancelled(true);

        assertThrows(InterruptedException.class,
                () -> writer.append(new byte[16], 0,
                        new Object[] { new Object() }, monitor));
        assertEquals(0, output.batchTargets.get());
        assertEquals(0, output.standaloneTargets.get());
        assertEquals(0, retainedIndexBytes(writer));
        assertFalse(Files.exists(pack));
        assertThrows(IllegalStateException.class,
                () -> writer.finish(UUID.randomUUID()));
    }

    @Test
    void cancellationInsideLargeRowEncodingDeletesTemporaryPack()
            throws Exception {
        Path pack = tempDir.resolve("cancel-large-encode.tmp");
        var writer = new PgCatalogReaderPackWriter(pack,
                new String[] { "value" }, MAX_ROW_BYTES);
        var monitor = new CancelAfterChecksMonitor(6);

        assertThrows(InterruptedException.class,
                () -> writer.append(new byte[16], 0,
                        new Object[] { new byte[512 * 1024] }, monitor));
        assertTrue(monitor.checks >= 6);
        assertFalse(Files.exists(pack));
        assertEquals(0, retainedIndexBytes(writer));
    }

    @Test
    void cancellationInsideLargeRowReadStopsDecode() throws Exception {
        Path pack = tempDir.resolve("cancel-large-decode.bin");
        PgCatalogReaderPackManifest manifest;
        try (var writer = new PgCatalogReaderPackWriter(pack,
                new String[] { "value" }, MAX_ROW_BYTES)) {
            writer.append(new byte[16], 0,
                    new Object[] { new byte[512 * 1024] });
            manifest = writer.finish(UUID.randomUUID());
        }
        var monitor = new CancelAfterChecksMonitor(6);

        try (var reader = PgCatalogReaderPackReader.open(pack, manifest)) {
            assertThrows(InterruptedException.class,
                    () -> reader.readValues(0, monitor));
        }
        assertTrue(monitor.checks >= 6);
    }

    @Test
    void cancellationDuringFinishDeletesTemporaryPackAndReleasesArrays()
            throws Exception {
        Path pack = tempDir.resolve("cancel-finish.tmp");
        var writer = new PgCatalogReaderPackWriter(pack,
                new String[] { "value" }, MAX_ROW_BYTES);
        writer.append(new byte[16], 0, new Object[] { "value" });
        var monitor = new NullMonitor();
        monitor.setCancelled(true);

        assertThrows(InterruptedException.class,
                () -> writer.finish(UUID.randomUUID(), monitor));
        assertFalse(Files.exists(pack));
        assertEquals(0, retainedIndexBytes(writer));
    }

    @Test
    void cancellationBeforeValidationAvoidsHashTableAllocation()
            throws Exception {
        Path pack = tempDir.resolve("cancel-before-validation.tmp");
        var validationStarted = new AtomicBoolean();
        var writer = new PgCatalogReaderPackWriter(pack, new String[0],
                MAX_ROW_BYTES,
                (retainedBytes, slots) ->
                        validationStarted.set(true));
        var monitor = new NullMonitor();
        monitor.setCancelled(true);

        assertThrows(InterruptedException.class,
                () -> writer.finish(UUID.randomUUID(), monitor));
        assertFalse(validationStarted.get());
        assertFalse(Files.exists(pack));
    }

    @Test
    void cancellationImmediatelyBeforeForceStillDeletesCompletedTemp()
            throws Exception {
        Path pack = tempDir.resolve("cancel-force.tmp");
        var writer = new PgCatalogReaderPackWriter(pack,
                new String[] { "value" }, MAX_ROW_BYTES);
        writer.append(new byte[16], 0, new Object[] { "value" });

        assertThrows(InterruptedException.class,
                () -> writer.finish(UUID.randomUUID(),
                        new CancelAfterChecksMonitor(5)));
        assertFalse(Files.exists(pack));
        assertEquals(0, retainedIndexBytes(writer));
    }

    @Test
    void cancellationDuringOpenClosesChannel() throws Exception {
        Fixture fixture = fixture("cancel-open");
        var openedChannel = new AtomicReference<FileChannel>();
        var monitor = new CancelAfterChecksMonitor(3);
        var hook = new PgCatalogReaderPackReader.OpenValidationHook() {
            @Override
            public void beforeIndexAllocation(FileChannel channel) {
                openedChannel.set(channel);
            }
        };

        assertThrows(InterruptedException.class,
                () -> PgCatalogReaderPackReader.open(fixture.pack(),
                        fixture.manifest(), monitor, hook));
        assertNotNull(openedChannel.get());
        assertFalse(openedChannel.get().isOpen());
    }

    private Fixture fixture(String name) throws Exception {
        Path pack = tempDir.resolve(name + ".bin");
        String[] labels = { "nspname", "relname", "oid" };
        byte[][] hashes = {
                HEX.parseHex("000102030405060708090a0b0c0d0e0f"),
                HEX.parseHex("101112131415161718191a1b1c1d1e1f")
        };
        try (var writer = new PgCatalogReaderPackWriter(pack, labels,
                MAX_ROW_BYTES)) {
            writer.append(hashes[0], 0,
                    new Object[] { "public", "one", 11L });
            writer.append(hashes[1], 0,
                    new Object[] { "app", "two", 22L });
            return new Fixture(pack, writer.finish(UUID.randomUUID()));
        }
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static long retainedIndexBytes(PgCatalogReaderPackWriter writer)
            throws Exception {
        Object index = field(writer, "index");
        var method = index.getClass().getDeclaredMethod("retainedBytes");
        method.setAccessible(true);
        return (long) method.invoke(index);
    }

    private static final class TrackingArrayDataOutput
            extends PgCatalogRowValueCodec.ArrayDataOutput {

        private final AtomicInteger batchTargets = new AtomicInteger();
        private final AtomicInteger standaloneTargets = new AtomicInteger();

        @Override
        void reset(byte[] payload, int offset, int length) {
            if (payload.length == 64 * 1024) {
                batchTargets.incrementAndGet();
            } else {
                standaloneTargets.incrementAndGet();
            }
            super.reset(payload, offset, length);
        }
    }

    private static long indexOffset(byte[] bytes) {
        return longAt(bytes, bytes.length
                - PgCatalogReaderPackFormat.FOOTER_BYTES + 12);
    }

    private static int intAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Integer.BYTES).getInt();
    }

    private static long longAt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Long.BYTES).getLong();
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes, offset, Integer.BYTES).putInt(value);
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes, offset, Long.BYTES).putLong(value);
    }

    private static void updateIndexCrc(byte[] bytes, int indexOffset,
            int rowCount) {
        int entriesBytes = rowCount
                * PgCatalogReaderPackFormat.INDEX_ENTRY_BYTES;
        putInt(bytes, indexOffset + entriesBytes,
                PgCatalogReaderPackFormat.crc32(bytes, indexOffset,
                        entriesBytes));
    }

    private static void updateFooterCrc(byte[] bytes) {
        int footerOffset = bytes.length - PgCatalogReaderPackFormat.FOOTER_BYTES;
        putInt(bytes, bytes.length - Integer.BYTES,
                PgCatalogReaderPackFormat.crc32(bytes, footerOffset,
                        PgCatalogReaderPackFormat.FOOTER_BYTES - Integer.BYTES));
    }

    private static ByteBuffer footer(long indexOffset, int rowCount,
            byte[] fingerprint) {
        ByteBuffer footer = ByteBuffer.allocate(
                PgCatalogReaderPackFormat.FOOTER_BYTES);
        footer.put(PgCatalogReaderPackFormat.FOOTER_MAGIC)
                .putInt(PgCatalogReaderPackFormat.PACK_FORMAT_VERSION)
                .putLong(indexOffset).putInt(rowCount).put(fingerprint);
        footer.putInt(PgCatalogReaderPackFormat.crc32(
                footer.array(), 0, footer.position())).flip();
        return footer;
    }

    private enum StructuralCorruption {
        HEADER_MAGIC {
            @Override void apply(byte[] bytes) {
                bytes[0] ^= 1;
            }
        },
        HEADER_VERSION {
            @Override void apply(byte[] bytes) {
                putInt(bytes, 8, 2);
            }
        },
        LABELS {
            @Override void apply(byte[] bytes) {
                bytes[PgCatalogReaderPackFormat.HEADER_FIXED_BYTES
                        + Integer.BYTES] ^= 1;
            }
        },
        INDEX_HASH {
            @Override void apply(byte[] bytes) {
                bytes[Math.toIntExact(indexOffset(bytes))] ^= 1;
            }
        },
        INDEX_OFFSET {
            @Override void apply(byte[] bytes) {
                int index = Math.toIntExact(indexOffset(bytes));
                putLong(bytes, index + PgPackedCatalogHashes.MD5_BYTES,
                        Long.MAX_VALUE);
            }
        },
        FOOTER {
            @Override void apply(byte[] bytes) {
                bytes[bytes.length
                        - PgCatalogReaderPackFormat.FOOTER_BYTES] ^= 1;
            }
        };

        abstract void apply(byte[] bytes);
    }

    private enum SemanticCorruption {
        NEGATIVE_COLUMN_COUNT {
            @Override void apply(byte[] bytes, int rowCount) {
                putInt(bytes, 16, -1);
            }
        },
        OVERSIZED_COLUMN_COUNT {
            @Override void apply(byte[] bytes, int rowCount) {
                putInt(bytes, 16, Short.MAX_VALUE + 1);
            }
        },
        NEGATIVE_LABELS_LENGTH {
            @Override void apply(byte[] bytes, int rowCount) {
                putInt(bytes, 20, -1);
            }
        },
        OVERSIZED_LABELS_LENGTH {
            @Override void apply(byte[] bytes, int rowCount) {
                putInt(bytes, 20,
                        PgCatalogReaderPackFormat.MAX_LABELS_BYTES + 1);
            }
        },
        VALUE_CODEC_VERSION {
            @Override void apply(byte[] bytes, int rowCount) {
                putInt(bytes, 12, PgCatalogRowValueCodec.FORMAT_VERSION + 1);
            }
        },
        FOOTER_VERSION {
            @Override void apply(byte[] bytes, int rowCount) {
                int footer = bytes.length
                        - PgCatalogReaderPackFormat.FOOTER_BYTES;
                putInt(bytes, footer + 8,
                        PgCatalogReaderPackFormat.PACK_FORMAT_VERSION + 1);
                updateFooterCrc(bytes);
            }
        },
        NEGATIVE_FOOTER_ROW_COUNT {
            @Override void apply(byte[] bytes, int rowCount) {
                int footer = bytes.length
                        - PgCatalogReaderPackFormat.FOOTER_BYTES;
                putInt(bytes, footer + 20, -1);
                updateFooterCrc(bytes);
            }
        },
        NEGATIVE_ROW_RECORD_LENGTH {
            @Override void apply(byte[] bytes, int rowCount) {
                int index = Math.toIntExact(indexOffset(bytes));
                putInt(bytes, index + PgPackedCatalogHashes.MD5_BYTES
                        + Long.BYTES, -1);
                updateIndexCrc(bytes, index, rowCount);
            }
        },
        OVERSIZED_ROW_RECORD_LENGTH {
            @Override void apply(byte[] bytes, int rowCount) {
                int index = Math.toIntExact(indexOffset(bytes));
                putInt(bytes, index + PgPackedCatalogHashes.MD5_BYTES
                        + Long.BYTES,
                        PgCatalogReaderPackFormat.MAX_VALUES_BYTES
                                + Integer.BYTES * 2 + 1);
                updateIndexCrc(bytes, index, rowCount);
            }
        },
        OVERFLOWING_ROW_OFFSET {
            @Override void apply(byte[] bytes, int rowCount) {
                int index = Math.toIntExact(indexOffset(bytes));
                putLong(bytes, index + PgPackedCatalogHashes.MD5_BYTES,
                        Long.MAX_VALUE);
                updateIndexCrc(bytes, index, rowCount);
            }
        };

        abstract void apply(byte[] bytes, int rowCount);
    }

    private record Fixture(Path pack, PgCatalogReaderPackManifest manifest) {
    }

    private static final class CancelAfterChecksMonitor extends NullMonitor {

        private final int cancelAt;
        private int checks;

        private CancelAfterChecksMonitor(int cancelAt) {
            this.cancelAt = cancelAt;
        }

        @Override
        public boolean isCancelled() {
            return ++checks >= cancelAt;
        }
    }

    private static int occurrences(byte[] haystack, byte[] needle) {
        int count = 0;
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                count++;
            }
        }
        return count;
    }
}
