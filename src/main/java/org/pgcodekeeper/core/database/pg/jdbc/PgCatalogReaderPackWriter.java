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
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

import org.pgcodekeeper.core.database.pg.jdbc.PgCatalogRowCodec.UnsupportedRowValueException;
import org.pgcodekeeper.core.monitor.IMonitor;

/** Streams rows into an unpublished immutable catalog reader pack. */
final class PgCatalogReaderPackWriter implements AutoCloseable {

    private static final int INDEX_BUFFER_BYTES =
            PgCatalogReaderPackFormat.INDEX_ENTRY_BYTES * 2048;
    private static final int ROW_BATCH_BYTES = 64 * 1024;
    private static final ValidationHook NO_OP_VALIDATION_HOOK =
            (retainedBytes, slots) -> { };
    private static final byte[] LOWER_HEX =
            "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final Set<OpenOption> CREATE_PRIVATE_OPTIONS =
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    /** Catalog packs hold full catalog rows, including routine sources. */
    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path tempFile;
    private final Path ownershipFile;
    private final String[] labels;
    private final int maxRowPayloadBytes;
    private final FileChannel channel;
    private final ValidationHook validationHook;
    private final MessageDigest fingerprint = newMd5();
    private final byte[] fingerprintHex =
            new byte[PgPackedCatalogHashes.MD5_BYTES * 2];
    private final CRC32 rowCrc = new CRC32();
    private final byte[] lengthScratch = new byte[Integer.BYTES];
    private final byte[] checksumScratch = new byte[Integer.BYTES];
    private final ByteBuffer lengthBuffer = ByteBuffer.wrap(lengthScratch);
    private final ByteBuffer checksumBuffer = ByteBuffer.wrap(checksumScratch);
    private final ByteBuffer[] largeRowBuffers =
            { lengthBuffer, null, checksumBuffer };
    private final ByteBuffer rowBatch = ByteBuffer.allocate(ROW_BATCH_BYTES);
    private final PgCatalogRowValueCodec.ArrayDataOutput rowOutput;
    private final PgCatalogWriterIndex index;
    private final IndexGrowthHook indexGrowthHook;
    private final boolean borrowedHashesUniquenessVerified;
    private final long residentIndexBudgetBytes;

    private FileChannel ownershipChannel;
    private FileLock ownershipLock;
    private int rowCount;
    private long bytesWritten;
    private boolean finished;
    private boolean closed;

    PgCatalogReaderPackWriter(Path tempFile, String[] labels,
            int maxRowPayloadBytes) throws IOException {
        this(tempFile, labels, maxRowPayloadBytes, NO_OP_VALIDATION_HOOK,
                null, new PgCatalogRowValueCodec.ArrayDataOutput(), null,
                false, PgCatalogReaderPackFormat.RESIDENT_INDEX_BUDGET_BYTES);
    }

    PgCatalogReaderPackWriter(Path tempFile, String[] labels,
            int maxRowPayloadBytes, ValidationHook validationHook)
            throws IOException {
        this(tempFile, labels, maxRowPayloadBytes, validationHook, null,
                new PgCatalogRowValueCodec.ArrayDataOutput(), null, false,
                PgCatalogReaderPackFormat.RESIDENT_INDEX_BUDGET_BYTES);
    }

    PgCatalogReaderPackWriter(Path tempFile, String[] labels,
            int maxRowPayloadBytes, PgPackedCatalogHashes borrowedHashes)
            throws IOException {
        this(tempFile, labels, maxRowPayloadBytes, NO_OP_VALIDATION_HOOK,
                null, new PgCatalogRowValueCodec.ArrayDataOutput(),
                Objects.requireNonNull(borrowedHashes, "borrowedHashes"),
                false, PgCatalogReaderPackFormat.RESIDENT_INDEX_BUDGET_BYTES);
    }

    PgCatalogReaderPackWriter(Path tempFile, String[] labels,
            int maxRowPayloadBytes, PgPackedCatalogHashes borrowedHashes,
            boolean uniquenessVerified) throws IOException {
        this(tempFile, labels, maxRowPayloadBytes, NO_OP_VALIDATION_HOOK,
                null, new PgCatalogRowValueCodec.ArrayDataOutput(),
                Objects.requireNonNull(borrowedHashes, "borrowedHashes"),
                uniquenessVerified,
                PgCatalogReaderPackFormat.RESIDENT_INDEX_BUDGET_BYTES);
    }

    PgCatalogReaderPackWriter(Path tempFile, String[] labels,
            int maxRowPayloadBytes, ValidationHook validationHook,
            IndexGrowthHook indexGrowthHook,
            PgCatalogRowValueCodec.ArrayDataOutput rowOutput)
            throws IOException {
        this(tempFile, labels, maxRowPayloadBytes, validationHook,
                indexGrowthHook, rowOutput, null, false,
                PgCatalogReaderPackFormat.RESIDENT_INDEX_BUDGET_BYTES);
    }

    PgCatalogReaderPackWriter(Path tempFile, String[] labels,
            int maxRowPayloadBytes, ValidationHook validationHook,
            IndexGrowthHook indexGrowthHook,
            PgCatalogRowValueCodec.ArrayDataOutput rowOutput,
            long residentIndexBudgetBytes) throws IOException {
        this(tempFile, labels, maxRowPayloadBytes, validationHook,
                indexGrowthHook, rowOutput, null, false,
                residentIndexBudgetBytes);
    }

    private PgCatalogReaderPackWriter(Path tempFile, String[] labels,
            int maxRowPayloadBytes, ValidationHook validationHook,
            IndexGrowthHook indexGrowthHook,
            PgCatalogRowValueCodec.ArrayDataOutput rowOutput,
            PgPackedCatalogHashes borrowedHashes,
            boolean borrowedHashesUniquenessVerified,
            long residentIndexBudgetBytes)
            throws IOException {
        this.tempFile = Objects.requireNonNull(tempFile, "tempFile");
        this.validationHook = Objects.requireNonNull(validationHook,
                "validationHook");
        this.indexGrowthHook = indexGrowthHook;
        this.rowOutput = Objects.requireNonNull(rowOutput, "rowOutput");
        this.index = new PgCatalogWriterIndex(borrowedHashes);
        this.borrowedHashesUniquenessVerified =
                borrowedHashesUniquenessVerified;
        if (residentIndexBudgetBytes <= 0
                || residentIndexBudgetBytes
                        > PgCatalogReaderPackFormat.RESIDENT_INDEX_BUDGET_BYTES) {
            throw new IllegalArgumentException(
                    "Invalid catalog writer resident index budget");
        }
        this.residentIndexBudgetBytes = residentIndexBudgetBytes;
        Objects.requireNonNull(labels, "labels");
        if (maxRowPayloadBytes <= 0
                || maxRowPayloadBytes > PgCatalogReaderPackFormat.MAX_VALUES_BYTES) {
            throw new IllegalArgumentException("Invalid catalog row payload limit");
        }
        this.labels = labels.clone();
        this.maxRowPayloadBytes = maxRowPayloadBytes;
        this.ownershipFile = PgCatalogReaderPackStore.temporaryLockPath(
                tempFile);

        byte[] labelsPayload = PgCatalogReaderPackFormat.encodeLabels(this.labels);
        acquireWriterOwnership();
        try {
            channel = createPrivateFile(tempFile);
        } catch (IOException | RuntimeException | Error ex) {
            releaseWriterOwnership();
            throw ex;
        }
        boolean initialized = false;
        try {
            ByteBuffer header = ByteBuffer.allocate(
                    PgCatalogReaderPackFormat.HEADER_FIXED_BYTES
                            + labelsPayload.length + Integer.BYTES);
            header.put(PgCatalogReaderPackFormat.HEADER_MAGIC)
                    .putInt(PgCatalogReaderPackFormat.PACK_FORMAT_VERSION)
                    .putInt(PgCatalogRowValueCodec.FORMAT_VERSION)
                    .putInt(this.labels.length)
                    .putInt(labelsPayload.length)
                    .put(labelsPayload)
                    .putInt(PgCatalogReaderPackFormat.crc32(
                            labelsPayload, 0, labelsPayload.length))
                    .flip();
            PgCatalogReaderPackFormat.writeFully(channel, header);
            bytesWritten = channel.position();
            initialized = true;
        } finally {
            if (!initialized) {
                close();
            }
        }
    }

    void append(byte[] digest, int digestOffset, Object[] values)
            throws IOException, UnsupportedRowValueException {
        try {
            append(digest, digestOffset, values, null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Catalog pack row write was interrupted", ex);
        }
    }

    void append(byte[] digest, int digestOffset, Object[] values,
            IMonitor monitor) throws IOException, UnsupportedRowValueException,
            InterruptedException {
        requireOpen();
        try {
            IMonitor.checkCancelled(monitor);
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(values, "values");
            Objects.checkFromIndexSize(digestOffset,
                    PgPackedCatalogHashes.MD5_BYTES, digest.length);
            if (values.length != labels.length) {
                throw new IllegalArgumentException(
                        "Catalog pack values do not match the label count");
            }
            int nextRowCount;
            try {
                nextRowCount = Math.addExact(rowCount, 1);
            } catch (ArithmeticException ex) {
                throw new IOException("Catalog pack row count overflows", ex);
            }
            requireResidentIndexBudget(nextRowCount);
            long payloadSize = PgCatalogRowValueCodec.encodedSize(values,
                    maxRowPayloadBytes, monitor);
            if (payloadSize > maxRowPayloadBytes) {
                throw new IOException(
                        "Catalog row exceeds the pack payload limit");
            }
            int recordLength;
            try {
                recordLength = Math.addExact(Math.toIntExact(payloadSize),
                        Integer.BYTES * 2);
            } catch (ArithmeticException ex) {
                throw new IOException("Catalog row record length overflows", ex);
            }
            ensureCapacity(nextRowCount);

            long offset = bytesWritten;
            writeRow(values, (int) payloadSize, recordLength, monitor);

            index.set(rowCount, digest, digestOffset, offset, recordLength);
            updateFingerprint(digest, digestOffset);
            rowCount = nextRowCount;
            bytesWritten = Math.addExact(bytesWritten, recordLength);
        } catch (IOException | UnsupportedRowValueException
                | InterruptedException | RuntimeException ex) {
            abort();
            throw ex;
        } catch (Error ex) {
            abort();
            throw ex;
        }
    }

    PgCatalogReaderPackManifest finish(UUID generationId) throws IOException {
        try {
            return finish(generationId, null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Catalog pack finish was interrupted", ex);
        }
    }

    PgCatalogReaderPackManifest finish(UUID generationId, IMonitor monitor)
            throws IOException, InterruptedException {
        requireOpen();
        try {
            Objects.requireNonNull(generationId, "generationId");
            requireResidentIndexBudget(rowCount);
            requireWriterResidentIndexBudget(rowCount, index.capacity());
            flushRowBatch(monitor);
            if (!borrowedHashesUniquenessVerified) {
                validateUniqueHashes(monitor);
            }
            long indexOffset = channel.position();
            var indexCrc = new CRC32();
            ByteBuffer entries = ByteBuffer.allocate(INDEX_BUFFER_BYTES);
            for (int row = 0; row < rowCount; row++) {
                if (entries.remaining()
                        < PgCatalogReaderPackFormat.INDEX_ENTRY_BYTES) {
                    flushIndexEntries(entries, indexCrc, monitor);
                }
                index.putEntry(row, entries);
            }
            flushIndexEntries(entries, indexCrc, monitor);
            ByteBuffer indexChecksum = ByteBuffer.allocate(Integer.BYTES)
                    .putInt((int) indexCrc.getValue()).flip();
            PgCatalogReaderPackFormat.writeFully(channel, indexChecksum);

            byte[] orderedFingerprint = fingerprint.digest();
            ByteBuffer footer = ByteBuffer.allocate(
                    PgCatalogReaderPackFormat.FOOTER_BYTES);
            footer.put(PgCatalogReaderPackFormat.FOOTER_MAGIC)
                    .putInt(PgCatalogReaderPackFormat.PACK_FORMAT_VERSION)
                    .putLong(indexOffset)
                    .putInt(rowCount)
                    .put(orderedFingerprint);
            footer.putInt(PgCatalogReaderPackFormat.crc32(
                    footer.array(), 0, footer.position())).flip();
            PgCatalogReaderPackFormat.writeFully(channel, footer);
            IMonitor.checkCancelled(monitor);
            channel.force(true);
            bytesWritten = channel.size();
            finished = true;
            channel.close();
            closed = true;
            releaseWriterOwnership();
            releaseIndex();
            return new PgCatalogReaderPackManifest(generationId, bytesWritten,
                    rowCount, orderedFingerprint);
        } catch (IOException | InterruptedException | RuntimeException ex) {
            abort();
            throw ex;
        } catch (Error ex) {
            abort();
            throw ex;
        }
    }

    long bytesWritten() {
        return bytesWritten;
    }

    long retainedIndexBytesForTest() {
        return index.retainedBytes();
    }

    void abort() {
        try {
            if (!closed) {
                channel.close();
                closed = true;
            }
        } catch (IOException ex) {
            // best-effort cleanup continues below
        } finally {
            releaseIndex();
        }
        finished = false;
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ex) {
            // a later cache cleanup can remove the abandoned temporary file
        } finally {
            releaseWriterOwnership();
        }
    }

    @Override
    public void close() {
        if (closed) {
            releaseIndex();
            return;
        }
        try {
            channel.close();
        } catch (IOException ex) {
            // best-effort close; unfinished files are deleted below
        } finally {
            closed = true;
            releaseIndex();
        }
        if (!finished) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ex) {
                // a later cache cleanup can remove the abandoned temporary file
            }
        }
        releaseWriterOwnership();
    }

    /**
     * Creates a new cache file readable only by its owner. The permissions are
     * requested through the POSIX view when the file system supports it; on
     * file systems without POSIX permissions (Windows) the file is created
     * with the platform default, exactly as before.
     */
    private static FileChannel createPrivateFile(Path file) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return FileChannel.open(file, CREATE_PRIVATE_OPTIONS,
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS));
        }
        return FileChannel.open(file, CREATE_PRIVATE_OPTIONS);
    }

    private void acquireWriterOwnership() throws IOException {
        ownershipChannel = createPrivateFile(ownershipFile);
        try {
            ownershipLock = ownershipChannel.lock();
        } catch (IOException | RuntimeException | Error ex) {
            releaseWriterOwnership();
            throw ex;
        }
    }

    private void releaseWriterOwnership() {
        if (ownershipLock != null) {
            try {
                ownershipLock.release();
            } catch (IOException | RuntimeException ex) {
                // closing the channel releases the operating-system lock
            } finally {
                ownershipLock = null;
            }
        }
        if (ownershipChannel != null) {
            try {
                ownershipChannel.close();
            } catch (IOException | RuntimeException ex) {
                // best-effort release continues with sidecar cleanup
            } finally {
                ownershipChannel = null;
            }
        }
        try {
            Files.deleteIfExists(ownershipFile);
        } catch (IOException | RuntimeException ex) {
            // an empty sidecar is harmless and can be reused by cleanup
        }
    }

    private void validateUniqueHashes(IMonitor monitor)
            throws IOException, InterruptedException {
        IMonitor.checkCancelled(monitor);
        int[] slots = new int[PgPackedCatalogHashIndex
                .tableCapacityFor(rowCount)];
        validationHook.indexAllocated(index.retainedBytes(), slots);
        for (int row = 0; row < rowCount; row++) {
            if ((row & 2047) == 0) {
                IMonitor.checkCancelled(monitor);
            }
            int slot = index.digestHash(row) & (slots.length - 1);
            int probes = 0;
            while (slots[slot] != 0) {
                if ((++probes & 2047) == 0) {
                    IMonitor.checkCancelled(monitor);
                }
                int existing = slots[slot] - 1;
                if (index.digestEquals(existing, row)) {
                    throw new IOException("Catalog pack contains duplicate hashes");
                }
                slot = (slot + 1) & (slots.length - 1);
            }
            slots[slot] = row + 1;
        }
    }

    private void ensureCapacity(int needed) throws IOException {
        try {
            int capacity = index.capacityAfterGrowth(needed);
            requireWriterResidentIndexBudget(needed, capacity);
            index.ensureCapacity(needed, indexGrowthHook);
        } catch (ArithmeticException ex) {
            throw new IOException("Catalog pack index exceeds the supported size", ex);
        }
    }

    private static void requireResidentIndexBudget(int rows)
            throws IOException {
        try {
            PgCatalogReaderPackFormat.requireResidentIndexBudget(rows);
        } catch (InvalidPackException ex) {
            throw new IOException(
                    "Catalog pack resident index exceeds the 64 MiB limit", ex);
        }
    }

    private void requireWriterResidentIndexBudget(int rows,
            int capacity) throws IOException {
        try {
            long bytes = PgCatalogReaderPackFormat.writerResidentIndexBytes(
                    rows, capacity);
            if (bytes > residentIndexBudgetBytes) {
                throw new InvalidPackException(
                        "Catalog pack writer index exceeds the 64 MiB limit");
            }
        } catch (InvalidPackException ex) {
            throw new IOException(
                    "Catalog pack writer index exceeds the 64 MiB limit", ex);
        }
    }

    private void updateFingerprint(byte[] digest, int offset) {
        int end = offset + PgPackedCatalogHashes.MD5_BYTES;
        for (int i = offset, target = 0; i < end; i++) {
            int value = Byte.toUnsignedInt(digest[i]);
            fingerprintHex[target++] = LOWER_HEX[value >>> 4];
            fingerprintHex[target++] = LOWER_HEX[value & 0x0F];
        }
        fingerprint.update(fingerprintHex);
    }

    private void flushIndexEntries(ByteBuffer entries, CRC32 crc,
            IMonitor monitor) throws IOException, InterruptedException {
        if (entries.position() == 0) {
            return;
        }
        IMonitor.checkCancelled(monitor);
        int length = entries.position();
        crc.update(entries.array(), 0, length);
        entries.flip();
        PgCatalogReaderPackFormat.writeFully(channel, entries);
        entries.clear();
    }

    private void writeRow(Object[] values, int payloadLength,
            int recordLength, IMonitor monitor)
            throws IOException, UnsupportedRowValueException,
            InterruptedException {
        if (recordLength <= rowBatch.capacity()) {
            if (recordLength > rowBatch.remaining()) {
                flushRowBatch(monitor);
            }
            int recordOffset = rowBatch.position();
            rowBatch.putInt(payloadLength);
            PgCatalogRowValueCodec.serializeInto(values, rowBatch.array(),
                    rowBatch.arrayOffset() + rowBatch.position(),
                    payloadLength, rowOutput, monitor);
            rowBatch.position(rowBatch.position() + payloadLength);
            rowCrc.reset();
            updateCrc(rowCrc, rowBatch.array(),
                    rowBatch.arrayOffset() + recordOffset,
                    Integer.BYTES + payloadLength, monitor);
            rowBatch.putInt((int) rowCrc.getValue());
            return;
        }
        flushRowBatch(monitor);
        IMonitor.checkCancelled(monitor);
        byte[] payload = new byte[payloadLength];
        PgCatalogRowValueCodec.serializeInto(values, payload, 0,
                payloadLength, rowOutput, monitor);
        writeInt(lengthScratch, payloadLength);
        rowCrc.reset();
        rowCrc.update(lengthScratch, 0, Integer.BYTES);
        updateCrc(rowCrc, payload, 0, payload.length, monitor);
        writeInt(checksumScratch, (int) rowCrc.getValue());
        lengthBuffer.clear();
        checksumBuffer.clear();
        largeRowBuffers[1] = ByteBuffer.wrap(payload);
        try {
            while (lengthBuffer.hasRemaining()
                    || largeRowBuffers[1].hasRemaining()
                    || checksumBuffer.hasRemaining()) {
                IMonitor.checkCancelled(monitor);
                channel.write(largeRowBuffers);
            }
        } finally {
            largeRowBuffers[1] = null;
        }
    }

    private void flushRowBatch(IMonitor monitor)
            throws IOException, InterruptedException {
        if (rowBatch.position() == 0) {
            return;
        }
        IMonitor.checkCancelled(monitor);
        rowBatch.flip();
        PgCatalogReaderPackFormat.writeFully(channel, rowBatch);
        rowBatch.clear();
    }

    private void releaseIndex() {
        index.release();
        largeRowBuffers[1] = null;
        rowOutput.clear();
    }

    private static void writeInt(byte[] target, int value) {
        target[0] = (byte) (value >>> 24);
        target[1] = (byte) (value >>> 16);
        target[2] = (byte) (value >>> 8);
        target[3] = (byte) value;
    }

    private static void updateCrc(CRC32 crc, byte[] bytes, int offset,
            int length, IMonitor monitor) throws InterruptedException {
        int end = offset + length;
        while (offset < end) {
            IMonitor.checkCancelled(monitor);
            int chunk = Math.min(ROW_BATCH_BYTES, end - offset);
            crc.update(bytes, offset, chunk);
            offset += chunk;
        }
    }

    private void requireOpen() {
        if (closed || finished) {
            throw new IllegalStateException("Catalog pack writer is closed");
        }
    }

    private static MessageDigest newMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 digest is not available", ex);
        }
    }

    @FunctionalInterface
    interface ValidationHook {
        void indexAllocated(long retainedBytes, int[] slots);
    }

    @FunctionalInterface
    interface IndexGrowthHook {
        void indexChunkAllocated(long retainedBefore,
                long allocatedBytes, long retainedAfter);
    }
}
