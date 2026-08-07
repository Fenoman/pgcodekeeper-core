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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;

import org.pgcodekeeper.core.monitor.IMonitor;

/** Constants and checked primitives for immutable catalog reader packs. */
final class PgCatalogReaderPackFormat {

    static final byte[] HEADER_MAGIC = "PGCKRP01".getBytes(StandardCharsets.US_ASCII);
    static final byte[] FOOTER_MAGIC = "PGCKRF01".getBytes(StandardCharsets.US_ASCII);
    static final int PACK_FORMAT_VERSION = 1;
    static final int HEADER_FIXED_BYTES = 8 + Integer.BYTES * 4;
    static final int FOOTER_BYTES = 8 + Integer.BYTES + Long.BYTES
            + Integer.BYTES + PgPackedCatalogHashes.MD5_BYTES + Integer.BYTES;
    static final int INDEX_ENTRY_BYTES = PgPackedCatalogHashes.MD5_BYTES
            + Long.BYTES + Integer.BYTES;
    static final int MAX_COLUMN_COUNT = Short.MAX_VALUE;
    static final int MAX_LABELS_BYTES = 1024 * 1024;
    static final int MAX_VALUES_BYTES = 32 * 1024 * 1024;
    static final long RESIDENT_INDEX_BUDGET_BYTES = 64L * 1024L * 1024L;
    static final int MAX_RESIDENT_INDEX_ROWS = findMaxResidentIndexRows();

    private PgCatalogReaderPackFormat() {
        // only statics
    }

    static byte[] encodeLabels(String[] labels) throws IOException {
        Objects.requireNonNull(labels, "labels");
        if (labels.length > MAX_COLUMN_COUNT) {
            throw new IllegalArgumentException("Too many catalog pack labels: " + labels.length);
        }

        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            for (String label : labels) {
                Objects.requireNonNull(label, "label");
                ByteBuffer encoded;
                try {
                    encoded = StandardCharsets.UTF_8.newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(label));
                } catch (CharacterCodingException ex) {
                    throw new IOException("Catalog label is not valid Unicode", ex);
                }
                int length = encoded.remaining();
                long next = (long) bytes.size() + Integer.BYTES + length;
                if (next > MAX_LABELS_BYTES) {
                    throw new IOException("Catalog pack labels exceed the size limit");
                }
                output.writeInt(length);
                byte[] labelBytes = new byte[length];
                encoded.get(labelBytes);
                output.write(labelBytes);
            }
        }
        return bytes.toByteArray();
    }

    static String[] decodeLabels(byte[] bytes, int columnCount)
            throws InvalidPackException {
        if (columnCount < 0 || columnCount > MAX_COLUMN_COUNT
                || bytes.length > MAX_LABELS_BYTES) {
            throw invalid("Invalid catalog pack labels metadata");
        }
        var labels = new String[columnCount];
        ByteBuffer input = ByteBuffer.wrap(bytes);
        for (int i = 0; i < columnCount; i++) {
            if (input.remaining() < Integer.BYTES) {
                throw invalid("Catalog pack labels are truncated");
            }
            int length = input.getInt();
            if (length < 0 || length > input.remaining()) {
                throw invalid("Invalid catalog pack label length");
            }
            ByteBuffer encoded = input.slice(input.position(), length);
            try {
                labels[i] = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(encoded).toString();
            } catch (CharacterCodingException ex) {
                throw new InvalidPackException("Catalog pack label is not strict UTF-8", ex);
            }
            input.position(input.position() + length);
        }
        if (input.hasRemaining()) {
            throw invalid("Catalog pack labels contain trailing bytes");
        }
        return labels;
    }

    static int crc32(byte[] bytes, int offset, int length) {
        var crc = new CRC32();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    static void readFully(FileChannel channel, ByteBuffer buffer, long position)
            throws IOException {
        try {
            readFully(channel, buffer, position, null);
        } catch (InterruptedException ex) {
            throw new IllegalStateException(
                    "Unexpected catalog pack cancellation without a monitor",
                    ex);
        }
    }

    static void readFully(FileChannel channel, ByteBuffer buffer, long position,
            IMonitor monitor) throws IOException, InterruptedException {
        long current = position;
        while (buffer.hasRemaining()) {
            IMonitor.checkCancelled(monitor);
            int read = channel.read(buffer, current);
            if (read < 0) {
                throw invalid("Catalog pack is truncated");
            }
            if (read == 0) {
                continue;
            }
            current = Math.addExact(current, read);
        }
        IMonitor.checkCancelled(monitor);
        buffer.flip();
    }

    static long addExact(long left, long right, String role)
            throws InvalidPackException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            throw new InvalidPackException("Catalog pack " + role + " overflows", ex);
        }
    }

    static long multiplyExact(long left, long right, String role)
            throws InvalidPackException {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException ex) {
            throw new InvalidPackException("Catalog pack " + role + " overflows", ex);
        }
    }

    static InvalidPackException invalid(String message) {
        return new InvalidPackException(message);
    }

    static long indexBytes(int rowCount) throws InvalidPackException {
        if (rowCount < 0) {
            throw invalid("Catalog pack row count is negative");
        }
        long entriesBytes = multiplyExact(rowCount, INDEX_ENTRY_BYTES,
                "index length");
        long total = addExact(entriesBytes, Integer.BYTES, "index length");
        if (total > Integer.MAX_VALUE - 8L) {
            throw invalid("Catalog pack index exceeds the format limit");
        }
        return total;
    }

    static long residentIndexBytes(int rowCount) throws InvalidPackException {
        if (rowCount < 0) {
            throw invalid("Catalog pack row count is negative");
        }
        long hashesBytes = multiplyExact(rowCount,
                PgPackedCatalogHashes.MD5_BYTES, "hash array size");
        long offsetsBytes = multiplyExact(rowCount, Long.BYTES,
                "offset array size");
        long lengthsBytes = multiplyExact(rowCount, Integer.BYTES,
                "length array size");
        long slotsBytes = hashIndexSlotsBytes(rowCount);
        long total = addExact(hashesBytes, offsetsBytes,
                "resident index size");
        total = addExact(total, lengthsBytes, "resident index size");
        return addExact(total, slotsBytes, "resident index size");
    }

    static void requireResidentIndexBudget(int rowCount)
            throws InvalidPackException {
        if (residentIndexBytes(rowCount) > RESIDENT_INDEX_BUDGET_BYTES) {
            throw invalid("Catalog pack resident index exceeds the 64 MiB limit");
        }
    }

    static long writerResidentIndexBytes(int rowCount, int retainedCapacity)
            throws InvalidPackException {
        if (retainedCapacity < rowCount) {
            throw invalid("Catalog pack writer capacity is smaller than its row count");
        }
        long retainedArrays = multiplyExact(retainedCapacity,
                PgPackedCatalogHashes.MD5_BYTES + Long.BYTES + Integer.BYTES,
                "writer retained index size");
        return addExact(retainedArrays, hashIndexSlotsBytes(rowCount),
                "writer resident index size");
    }

    private static long hashIndexSlotsBytes(int rowCount)
            throws InvalidPackException {
        final int slots;
        try {
            slots = PgPackedCatalogHashIndex.tableCapacityFor(rowCount);
        } catch (IllegalArgumentException ex) {
            throw new InvalidPackException(
                    "Catalog pack hash index capacity is invalid", ex);
        }
        return multiplyExact(slots, Integer.BYTES,
                "hash index slots size");
    }

    private static int findMaxResidentIndexRows() {
        int low = 0;
        int high = 1;
        while (fitsResidentIndexBudget(high)) {
            low = high;
            if (high > Integer.MAX_VALUE / 2) {
                return high;
            }
            high *= 2;
        }
        while (low + 1 < high) {
            int middle = low + (high - low) / 2;
            if (fitsResidentIndexBudget(middle)) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static boolean fitsResidentIndexBudget(int rowCount) {
        try {
            return residentIndexBytes(rowCount) <= RESIDENT_INDEX_BUDGET_BYTES;
        } catch (InvalidPackException ex) {
            return false;
        }
    }
}

/** Signals a structurally invalid or incompatible catalog reader pack. */
final class InvalidPackException extends IOException {

    private static final long serialVersionUID = 1L;

    InvalidPackException(String message) {
        super(message);
    }

    InvalidPackException(String message, Throwable cause) {
        super(message, cause);
    }
}
