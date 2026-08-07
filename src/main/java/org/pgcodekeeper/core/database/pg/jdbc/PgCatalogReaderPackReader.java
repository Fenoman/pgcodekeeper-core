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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

import org.pgcodekeeper.core.database.pg.jdbc.PgPackedCatalogHashIndex.DuplicateHashException;
import org.pgcodekeeper.core.monitor.IMonitor;

/**
 * Validates and lazily replays one immutable catalog reader pack.
 * <p>
 * One instance belongs to one catalog reader lane. Value reads are synchronized
 * only to protect the bounded reusable decode state against accidental reentry;
 * parallel lanes use separate reader instances.
 */
final class PgCatalogReaderPackReader implements AutoCloseable {

    private static final int INDEX_BATCH_ROWS = 2048;
    private static final int REUSABLE_ROW_BUFFER_BYTES = 64 * 1024;
    private static final OpenValidationHook NO_OP_OPEN_HOOK = channel -> { };

    private final FileChannel channel;
    private final String[] labels;
    private final PgPackedCatalogHashes hashes;
    private PgPackedCatalogHashIndex hashIndex;
    private final long[] rowOffsets;
    private final int[] rowRecordLengths;
    private final byte[] orderedFingerprint;
    private final long packBytes;
    private final ByteBuffer rowBuffer =
            ByteBuffer.allocate(REUSABLE_ROW_BUFFER_BYTES);
    private final CRC32 rowCrc = new CRC32();
    private final PgCatalogRowValueCodec.ReusableArrayDataInput valuesInput =
            new PgCatalogRowValueCodec.ReusableArrayDataInput();

    private PgCatalogReaderPackReader(FileChannel channel, String[] labels,
            PgPackedCatalogHashes hashes, PgPackedCatalogHashIndex hashIndex,
            long[] rowOffsets, int[] rowRecordLengths,
            byte[] orderedFingerprint, long packBytes) {
        this.channel = channel;
        this.labels = labels;
        this.hashes = hashes;
        this.hashIndex = hashIndex;
        this.rowOffsets = rowOffsets;
        this.rowRecordLengths = rowRecordLengths;
        this.orderedFingerprint = orderedFingerprint;
        this.packBytes = packBytes;
    }

    static PgCatalogReaderPackReader open(Path pack,
            PgCatalogReaderPackManifest manifest)
            throws IOException, InvalidPackException {
        return openWithoutMonitor(pack, manifest, NO_OP_OPEN_HOOK);
    }

    static PgCatalogReaderPackReader open(Path pack,
            PgCatalogReaderPackManifest manifest, OpenValidationHook hook)
            throws IOException, InvalidPackException {
        return openWithoutMonitor(pack, manifest, hook);
    }

    static PgCatalogReaderPackReader open(Path pack,
            PgCatalogReaderPackManifest manifest, IMonitor monitor)
            throws IOException, InvalidPackException, InterruptedException {
        return open(pack, manifest, monitor, NO_OP_OPEN_HOOK, true);
    }

    static PgCatalogReaderPackReader openForReplay(Path pack,
            PgCatalogReaderPackManifest manifest, IMonitor monitor)
            throws IOException, InvalidPackException, InterruptedException {
        return open(pack, manifest, monitor, NO_OP_OPEN_HOOK, false);
    }

    private static PgCatalogReaderPackReader openWithoutMonitor(Path pack,
            PgCatalogReaderPackManifest manifest, OpenValidationHook hook)
            throws IOException, InvalidPackException {
        try {
            return open(pack, manifest, null, hook);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Catalog pack validation was interrupted", ex);
        }
    }

    static PgCatalogReaderPackReader open(Path pack,
            PgCatalogReaderPackManifest manifest, IMonitor monitor,
            OpenValidationHook hook)
            throws IOException, InvalidPackException, InterruptedException {
        return open(pack, manifest, monitor, hook, true);
    }

    private static PgCatalogReaderPackReader open(Path pack,
            PgCatalogReaderPackManifest manifest, IMonitor monitor,
            OpenValidationHook hook, boolean buildLookup)
            throws IOException, InvalidPackException, InterruptedException {
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(hook, "hook");
        FileChannel channel = FileChannel.open(pack, StandardOpenOption.READ);
        try {
            IMonitor.checkCancelled(monitor);
            long size = channel.size();
            if (size != manifest.packSize()) {
                throw PgCatalogReaderPackFormat.invalid(
                        "Catalog pack size does not match its manifest");
            }
            long minimum = PgCatalogReaderPackFormat.HEADER_FIXED_BYTES
                    + Integer.BYTES + Integer.BYTES
                    + PgCatalogReaderPackFormat.FOOTER_BYTES;
            if (size < minimum) {
                throw PgCatalogReaderPackFormat.invalid("Catalog pack is too small");
            }

            Footer footer = readFooter(channel, size);
            validateIndexRange(size, footer);
            if (footer.rowCount() != manifest.rowCount()
                    || !Arrays.equals(footer.fingerprint(),
                            manifest.orderedFingerprint())) {
                throw PgCatalogReaderPackFormat.invalid(
                        "Catalog pack footer does not match its manifest");
            }
            PgCatalogReaderPackFormat.requireResidentIndexBudget(
                    footer.rowCount());

            Header header = readHeader(channel, footer.indexOffset());
            IMonitor.checkCancelled(monitor);
            hook.beforeIndexAllocation(channel);

            int rowCount = footer.rowCount();
            byte[] packedHashes;
            long[] rowOffsets;
            int[] rowRecordLengths;
            try {
                packedHashes = new byte[Math.multiplyExact(rowCount,
                        PgPackedCatalogHashes.MD5_BYTES)];
                rowOffsets = new long[rowCount];
                rowRecordLengths = new int[rowCount];
            } catch (ArithmeticException ex) {
                throw new InvalidPackException(
                        "Catalog pack index cannot be allocated", ex);
            }

            readAndValidateIndex(channel, footer.indexOffset(),
                    header.rowsOffset(), packedHashes, rowOffsets,
                    rowRecordLengths, monitor);
            PgPackedCatalogHashes hashes;
            PgPackedCatalogHashIndex hashIndex = null;
            try {
                hashes = PgPackedCatalogHashes.takeOwnership(rowCount,
                        packedHashes, monitor);
                if (!Arrays.equals(hashes.orderedFingerprint(),
                        footer.fingerprint())) {
                    throw PgCatalogReaderPackFormat.invalid(
                            "Catalog pack ordered fingerprint is invalid");
                }
                if (buildLookup) {
                    hook.beforeHashIndexBuild(channel);
                    hashIndex = PgPackedCatalogHashIndex.build(hashes,
                            monitor);
                }
            } catch (DuplicateHashException ex) {
                throw new InvalidPackException(
                        "Catalog pack index contains duplicate hashes", ex);
            }

            IMonitor.checkCancelled(monitor);
            return new PgCatalogReaderPackReader(channel, header.labels(),
                    hashes, hashIndex, rowOffsets, rowRecordLengths,
                    footer.fingerprint(), size);
        } catch (IOException | InterruptedException | RuntimeException ex) {
            try {
                channel.close();
            } catch (IOException closeEx) {
                ex.addSuppressed(closeEx);
            }
            throw ex;
        } catch (Error ex) {
            try {
                channel.close();
            } catch (IOException closeEx) {
                // preserve the original VM error without allocating suppression state
            }
            throw ex;
        }
    }

    String[] labels() {
        return labels.clone();
    }

    PgPackedCatalogHashes hashes() {
        return hashes;
    }

    int rowCount() {
        return rowOffsets.length;
    }

    byte[] orderedFingerprint() {
        return orderedFingerprint.clone();
    }

    PgCachedCatalogRow readRow(int rowIndex) throws IOException {
        Object[] values = readValues(rowIndex);
        return values == null ? null
                : new PgCachedCatalogRow(labels.clone(), values);
    }

    Object[] readValues(int rowIndex) throws IOException {
        try {
            return readValues(rowIndex, null);
        } catch (InterruptedException ex) {
            throw new IllegalStateException(
                    "Unexpected catalog pack cancellation without a monitor",
                    ex);
        }
    }

    synchronized Object[] readValues(int rowIndex, IMonitor monitor)
            throws IOException, InterruptedException {
        IMonitor.checkCancelled(monitor);
        Objects.checkIndex(rowIndex, rowOffsets.length);
        int recordLength = rowRecordLengths[rowIndex];
        ByteBuffer record;
        if (recordLength <= rowBuffer.capacity()) {
            record = rowBuffer.clear().limit(recordLength);
        } else {
            record = ByteBuffer.allocate(recordLength);
        }
        try {
            PgCatalogReaderPackFormat.readFully(channel, record,
                    rowOffsets[rowIndex], monitor);
        } catch (InvalidPackException ex) {
            return null;
        }

        byte[] bytes = record.array();
        int valuesLength = record.getInt();
        if (valuesLength < 0 || valuesLength > PgCatalogReaderPackFormat.MAX_VALUES_BYTES
                || valuesLength != recordLength - Integer.BYTES * 2) {
            return null;
        }
        int expectedCrc = record.getInt(recordLength - Integer.BYTES);
        rowCrc.reset();
        updateCrc(bytes, 0, recordLength - Integer.BYTES, monitor);
        int actualCrc = (int) rowCrc.getValue();
        if (actualCrc != expectedCrc) {
            return null;
        }
        return PgCatalogRowValueCodec.deserialize(bytes, Integer.BYTES,
                valuesLength, labels.length, valuesInput, monitor);
    }

    private void updateCrc(byte[] bytes, int offset, int length,
            IMonitor monitor) throws InterruptedException {
        int end = offset + length;
        while (offset < end) {
            IMonitor.checkCancelled(monitor);
            int chunk = Math.min(REUSABLE_ROW_BUFFER_BYTES, end - offset);
            rowCrc.update(bytes, offset, chunk);
            offset += chunk;
        }
    }

    long packBytes() {
        return packBytes;
    }

    int findRow(byte[] digest, int offset) {
        if (hashIndex == null) {
            throw new IllegalStateException("Catalog pack lookup index was released");
        }
        return hashIndex.find(digest, offset);
    }

    void releaseLookupIndex() {
        hashIndex = null;
    }

    void buildLookupIndex(IMonitor monitor)
            throws InterruptedException, InvalidPackException {
        if (hashIndex != null) {
            return;
        }
        try {
            hashIndex = PgPackedCatalogHashIndex.build(hashes, monitor);
        } catch (DuplicateHashException ex) {
            throw new InvalidPackException(
                    "Catalog pack index contains duplicate hashes", ex);
        }
    }

    long retainedIndexBytesForTest() {
        long replayBytes = (long) rowOffsets.length * Long.BYTES
                + (long) rowRecordLengths.length * Integer.BYTES;
        long retained = replayBytes + hashes.rawBytesForCache().length;
        return hashIndex == null ? retained
                : retained + hashIndex.retainedBytes();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private static Header readHeader(FileChannel channel, long indexOffset)
            throws IOException {
        ByteBuffer fixed = ByteBuffer.allocate(
                PgCatalogReaderPackFormat.HEADER_FIXED_BYTES);
        PgCatalogReaderPackFormat.readFully(channel, fixed, 0L);
        byte[] magic = new byte[PgCatalogReaderPackFormat.HEADER_MAGIC.length];
        fixed.get(magic);
        if (!Arrays.equals(magic, PgCatalogReaderPackFormat.HEADER_MAGIC)
                || fixed.getInt() != PgCatalogReaderPackFormat.PACK_FORMAT_VERSION
                || fixed.getInt() != PgCatalogRowValueCodec.FORMAT_VERSION) {
            throw PgCatalogReaderPackFormat.invalid(
                    "Invalid catalog pack header");
        }
        int columnCount = fixed.getInt();
        int labelsLength = fixed.getInt();
        if (columnCount < 0
                || columnCount > PgCatalogReaderPackFormat.MAX_COLUMN_COUNT
                || labelsLength < 0
                || labelsLength > PgCatalogReaderPackFormat.MAX_LABELS_BYTES) {
            throw PgCatalogReaderPackFormat.invalid(
                    "Invalid catalog pack labels bounds");
        }
        long rowsOffset = PgCatalogReaderPackFormat.addExact(
                PgCatalogReaderPackFormat.HEADER_FIXED_BYTES,
                PgCatalogReaderPackFormat.addExact(labelsLength,
                        Integer.BYTES, "labels range"), "header range");
        if (rowsOffset > indexOffset) {
            throw PgCatalogReaderPackFormat.invalid(
                    "Catalog pack labels overlap its index");
        }

        ByteBuffer labelsAndCrc = ByteBuffer.allocate(labelsLength + Integer.BYTES);
        PgCatalogReaderPackFormat.readFully(channel, labelsAndCrc,
                PgCatalogReaderPackFormat.HEADER_FIXED_BYTES);
        byte[] labelsPayload = new byte[labelsLength];
        labelsAndCrc.get(labelsPayload);
        int expectedCrc = labelsAndCrc.getInt();
        if (expectedCrc != PgCatalogReaderPackFormat.crc32(
                labelsPayload, 0, labelsPayload.length)) {
            throw PgCatalogReaderPackFormat.invalid(
                    "Catalog pack labels checksum is invalid");
        }
        return new Header(PgCatalogReaderPackFormat.decodeLabels(
                labelsPayload, columnCount), rowsOffset);
    }

    private static Footer readFooter(FileChannel channel, long size)
            throws IOException {
        long offset = size - PgCatalogReaderPackFormat.FOOTER_BYTES;
        ByteBuffer footer = ByteBuffer.allocate(
                PgCatalogReaderPackFormat.FOOTER_BYTES);
        PgCatalogReaderPackFormat.readFully(channel, footer, offset);
        byte[] bytes = footer.array();
        int expectedCrc = footer.getInt(
                PgCatalogReaderPackFormat.FOOTER_BYTES - Integer.BYTES);
        if (expectedCrc != PgCatalogReaderPackFormat.crc32(bytes, 0,
                PgCatalogReaderPackFormat.FOOTER_BYTES - Integer.BYTES)) {
            throw PgCatalogReaderPackFormat.invalid(
                    "Catalog pack footer checksum is invalid");
        }
        byte[] magic = new byte[PgCatalogReaderPackFormat.FOOTER_MAGIC.length];
        footer.get(magic);
        int version = footer.getInt();
        if (!Arrays.equals(magic, PgCatalogReaderPackFormat.FOOTER_MAGIC)
                || version != PgCatalogReaderPackFormat.PACK_FORMAT_VERSION) {
            throw PgCatalogReaderPackFormat.invalid(
                    "Invalid catalog pack footer");
        }
        long indexOffset = footer.getLong();
        int rowCount = footer.getInt();
        byte[] fingerprint = new byte[PgPackedCatalogHashes.MD5_BYTES];
        footer.get(fingerprint);
        if (indexOffset < 0 || rowCount < 0) {
            throw PgCatalogReaderPackFormat.invalid(
                    "Invalid catalog pack footer bounds");
        }
        return new Footer(indexOffset, rowCount, fingerprint);
    }

    private static void validateIndexRange(long size, Footer footer)
            throws InvalidPackException {
        long totalIndexBytes = PgCatalogReaderPackFormat.indexBytes(
                footer.rowCount());
        long indexEnd = PgCatalogReaderPackFormat.addExact(
                footer.indexOffset(), totalIndexBytes, "index range");
        long footerOffset = size - PgCatalogReaderPackFormat.FOOTER_BYTES;
        if (indexEnd != footerOffset) {
            throw PgCatalogReaderPackFormat.invalid(
                    "Catalog pack index range is invalid");
        }
    }

    private static void readAndValidateIndex(FileChannel channel,
            long indexOffset, long rowsOffset, byte[] hashes, long[] offsets,
            int[] lengths, IMonitor monitor)
            throws IOException, InterruptedException {
        var crc = new CRC32();
        ByteBuffer entries = ByteBuffer.allocate(
                PgCatalogReaderPackFormat.INDEX_ENTRY_BYTES * INDEX_BATCH_ROWS);
        long current = indexOffset;
        long expectedRowOffset = rowsOffset;
        for (int firstRow = 0; firstRow < offsets.length;
                firstRow += INDEX_BATCH_ROWS) {
            IMonitor.checkCancelled(monitor);
            int batchRows = Math.min(INDEX_BATCH_ROWS,
                    offsets.length - firstRow);
            int batchBytes = batchRows
                    * PgCatalogReaderPackFormat.INDEX_ENTRY_BYTES;
            entries.clear().limit(batchBytes);
            PgCatalogReaderPackFormat.readFully(channel, entries, current);
            crc.update(entries.array(), 0, batchBytes);
            for (int batchRow = 0; batchRow < batchRows; batchRow++) {
                int row = firstRow + batchRow;
                entries.get(hashes,
                        row * PgPackedCatalogHashes.MD5_BYTES,
                        PgPackedCatalogHashes.MD5_BYTES);
                long offset = entries.getLong();
                int recordLength = entries.getInt();
                if (offset != expectedRowOffset
                        || recordLength < Integer.BYTES * 2
                        || recordLength > PgCatalogReaderPackFormat.MAX_VALUES_BYTES
                                + Integer.BYTES * 2) {
                    throw PgCatalogReaderPackFormat.invalid(
                            "Catalog pack row range is invalid");
                }
                long rowEnd = PgCatalogReaderPackFormat.addExact(offset,
                        recordLength, "row range");
                if (rowEnd > indexOffset) {
                    throw PgCatalogReaderPackFormat.invalid(
                            "Catalog pack row overlaps its index");
                }
                offsets[row] = offset;
                lengths[row] = recordLength;
                expectedRowOffset = rowEnd;
            }
            current = PgCatalogReaderPackFormat.addExact(current,
                    batchBytes,
                    "index position");
        }
        if (expectedRowOffset != indexOffset) {
            throw PgCatalogReaderPackFormat.invalid(
                    "Catalog pack rows do not fill their section");
        }
        ByteBuffer checksum = ByteBuffer.allocate(Integer.BYTES);
        PgCatalogReaderPackFormat.readFully(channel, checksum, current);
        if (checksum.getInt() != (int) crc.getValue()) {
            throw PgCatalogReaderPackFormat.invalid(
                    "Catalog pack index checksum is invalid");
        }
    }

    private record Header(String[] labels, long rowsOffset) {
    }

    private record Footer(long indexOffset, int rowCount, byte[] fingerprint) {
    }

    @FunctionalInterface
    interface OpenValidationHook {
        void beforeIndexAllocation(FileChannel channel);

        default void beforeHashIndexBuild(FileChannel channel) {
            // optional validation hook
        }
    }
}
