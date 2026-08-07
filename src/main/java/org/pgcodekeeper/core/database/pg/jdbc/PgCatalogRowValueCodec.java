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

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

import org.pgcodekeeper.core.database.pg.jdbc.PgCatalogRowCodec.UnsupportedRowValueException;
import org.pgcodekeeper.core.monitor.IMonitor;

/** Binary codec for catalog row values whose column labels are stored separately. */
final class PgCatalogRowValueCodec {

    /**
     * Bump on any change of the value wire format or capture semantics. The
     * version is written into every pack header and hashed into the reader
     * qualifier, so a bump both renames the pack and makes an older file that
     * still reaches this reader fail its header check instead of being
     * misparsed.
     * <p>
     * Version 2 added {@link #TAG_ARRAY_TEXT}: array columns now carry the
     * exact {@code getString} text next to their decoded elements.
     */
    static final int FORMAT_VERSION = 2;

    private static final byte TAG_NULL = 0;
    private static final byte TAG_STRING = 1;
    private static final byte TAG_LONG = 2;
    private static final byte TAG_INTEGER = 3;
    private static final byte TAG_SHORT = 4;
    private static final byte TAG_BOOLEAN = 5;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_FLOAT = 7;
    private static final byte TAG_BYTES = 8;
    private static final byte TAG_ARRAY = 9;
    /** Array elements followed by the exact text of the same column. */
    private static final byte TAG_ARRAY_TEXT = 10;

    private static final int MAX_ARRAY_ELEMENTS = Short.MAX_VALUE;
    private static final int MAX_COLUMN_COUNT = Short.MAX_VALUE;
    private static final int COPY_CHUNK_BYTES = 64 * 1024;
    private static final int LOOP_CHECK_INTERVAL = 1024;

    private PgCatalogRowValueCodec() {
        // only statics
    }

    static byte[] serialize(Object[] values, int maxPayloadBytes)
            throws UnsupportedRowValueException {
        Objects.requireNonNull(values, "values");
        requirePayloadLimit(maxPayloadBytes);
        long payloadSize = encodedSize(values, maxPayloadBytes);
        if (payloadSize > maxPayloadBytes) {
            return null;
        }

        byte[] payload = new byte[(int) payloadSize];
        var output = new ArrayDataOutput(payload);
        try {
            writeValues(values, output);
        } catch (IOException ex) {
            throw new IllegalStateException("Unexpected in-memory catalog row write failure", ex);
        }
        if (output.position() != payload.length) {
            throw new IllegalStateException("Catalog row size calculation mismatch");
        }
        return payload;
    }

    static void serializeInto(Object[] values, byte[] target, int offset,
            int length, ArrayDataOutput output)
            throws UnsupportedRowValueException {
        try {
            serializeInto(values, target, offset, length, output, null);
        } catch (InterruptedException ex) {
            throw impossibleWithoutMonitor(ex);
        }
    }

    static void serializeInto(Object[] values, byte[] target, int offset,
            int length, ArrayDataOutput output, IMonitor monitor)
            throws UnsupportedRowValueException, InterruptedException {
        IMonitor.checkCancelled(monitor);
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(output, "output");
        Objects.checkFromIndexSize(offset, length, target.length);
        output.reset(target, offset, length);
        try {
            writeValues(values, output, monitor);
            if (output.position() != length) {
                throw new IllegalStateException(
                        "Catalog row size calculation mismatch");
            }
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unexpected in-memory catalog row write failure", ex);
        } finally {
            output.clear();
        }
    }

    static Object[] deserialize(byte[] payload, int expectedColumnCount) {
        requireColumnCount(expectedColumnCount);
        if (payload == null || expectedColumnCount > payload.length) {
            return null;
        }
        try (var data = new DataInputStream(new ByteArrayInputStream(payload))) {
            return readValues(data, expectedColumnCount, payload.length);
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    static Object[] deserialize(byte[] payload, int offset, int length,
            int expectedColumnCount, ReusableArrayDataInput input) {
        try {
            return deserialize(payload, offset, length, expectedColumnCount,
                    input, null);
        } catch (InterruptedException ex) {
            throw impossibleWithoutMonitor(ex);
        }
    }

    static Object[] deserialize(byte[] payload, int offset, int length,
            int expectedColumnCount, ReusableArrayDataInput input,
            IMonitor monitor) throws InterruptedException {
        IMonitor.checkCancelled(monitor);
        requireColumnCount(expectedColumnCount);
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(input, "input");
        Objects.checkFromIndexSize(offset, length, payload.length);
        input.reset(payload, offset, length);
        try {
            return readValues(input, expectedColumnCount, length, monitor);
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        } finally {
            input.clear();
        }
    }

    static long encodedSize(Object[] values, long maxPayloadBytes)
            throws UnsupportedRowValueException {
        try {
            return encodedSize(values, maxPayloadBytes, null);
        } catch (InterruptedException ex) {
            throw impossibleWithoutMonitor(ex);
        }
    }

    static long encodedSize(Object[] values, long maxPayloadBytes,
            IMonitor monitor)
            throws UnsupportedRowValueException, InterruptedException {
        Objects.requireNonNull(values, "values");
        requirePayloadLimit(maxPayloadBytes);
        if (values.length > MAX_COLUMN_COUNT) {
            return maxPayloadBytes + 1L;
        }

        long size = 0L;
        for (int i = 0; i < values.length; i++) {
            checkLoopCancelled(monitor, i);
            size = addBounded(size,
                    encodedValueSize(values[i], maxPayloadBytes - size,
                            monitor),
                    maxPayloadBytes);
            if (size > maxPayloadBytes) {
                return size;
            }
        }
        return size;
    }

    static void writeValues(Object[] values, DataOutput output)
            throws IOException, UnsupportedRowValueException {
        try {
            writeValues(values, output, null);
        } catch (InterruptedException ex) {
            throw impossibleWithoutMonitor(ex);
        }
    }

    static void writeValues(Object[] values, DataOutput output,
            IMonitor monitor)
            throws IOException, UnsupportedRowValueException,
            InterruptedException {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(output, "output");
        requireColumnCount(values.length);
        for (int i = 0; i < values.length; i++) {
            checkLoopCancelled(monitor, i);
            writeValue(values[i], output, monitor);
        }
    }

    static Object[] readValues(DataInput input, int columnCount, int availableBytes)
            throws IOException {
        try {
            return readValues(input, columnCount, availableBytes, null);
        } catch (InterruptedException ex) {
            throw impossibleWithoutMonitor(ex);
        }
    }

    static Object[] readValues(DataInput input, int columnCount,
            int availableBytes, IMonitor monitor)
            throws IOException, InterruptedException {
        Objects.requireNonNull(input, "input");
        requireColumnCount(columnCount);
        if (availableBytes < 0) {
            throw new IllegalArgumentException("Available payload bytes must not be negative");
        }

        RemainingDataInput bounded = input instanceof RemainingDataInput existing
                && existing.remaining() == availableBytes
                        ? existing
                        : new BoundedDataInput(input, availableBytes);
        if (columnCount > bounded.remaining()) {
            throw new IOException("Catalog row has too few bytes for its column count");
        }

        var values = new Object[columnCount];
        for (int i = 0; i < columnCount; i++) {
            checkLoopCancelled(monitor, i);
            values[i] = readValue(bounded, monitor);
        }
        if (bounded.remaining() != 0) {
            throw new IOException("Catalog row contains trailing value bytes");
        }
        return values;
    }

    static long encodedStringSize(String value) {
        try {
            return encodedStringSize(value, null);
        } catch (InterruptedException ex) {
            throw impossibleWithoutMonitor(ex);
        }
    }

    private static long encodedStringSize(String value, IMonitor monitor)
            throws InterruptedException {
        Objects.requireNonNull(value, "value");
        return Integer.BYTES + utf8Length(value, monitor);
    }

    static long encodedValueSize(Object value, long remaining)
            throws UnsupportedRowValueException {
        try {
            return encodedValueSize(value, remaining, null);
        } catch (InterruptedException ex) {
            throw impossibleWithoutMonitor(ex);
        }
    }

    private static long encodedValueSize(Object value, long remaining,
            IMonitor monitor)
            throws UnsupportedRowValueException, InterruptedException {
        if (value == null) {
            return Byte.BYTES;
        }
        if (value instanceof String string) {
            return addBounded(Byte.BYTES,
                    encodedStringSize(string, monitor), remaining);
        }
        if (value instanceof Long || value instanceof Double) {
            return Byte.BYTES + Long.BYTES;
        }
        if (value instanceof Integer || value instanceof Float) {
            return Byte.BYTES + Integer.BYTES;
        }
        if (value instanceof Short) {
            return Byte.BYTES + Short.BYTES;
        }
        if (value instanceof Boolean) {
            return Byte.BYTES + Byte.BYTES;
        }
        if (value instanceof byte[] bytes) {
            return addBounded(Byte.BYTES + Integer.BYTES, bytes.length, remaining);
        }
        if (value instanceof PgCachedCatalogArray captured) {
            long size = encodedArraySize(requireElements(captured), remaining,
                    monitor);
            if (size > remaining) {
                return size;
            }
            return addBounded(size,
                    encodedStringSize(captured.text(), monitor), remaining);
        }
        if (value instanceof Object[] array) {
            return encodedArraySize(array, remaining, monitor);
        }
        throw new UnsupportedRowValueException(
                "Unsupported catalog row value type: " + value.getClass().getName());
    }

    static void writeString(String value, DataOutput output) throws IOException {
        try {
            writeString(value, output, null);
        } catch (InterruptedException ex) {
            throw impossibleWithoutMonitor(ex);
        }
    }

    private static void writeString(String value, DataOutput output,
            IMonitor monitor) throws IOException, InterruptedException {
        if (output instanceof ArrayDataOutput arrayOutput) {
            int lengthOffset = arrayOutput.reserveInt();
            int contentOffset = arrayOutput.position();
            writeUtf8(value, output, monitor);
            arrayOutput.writeIntAt(lengthOffset,
                    arrayOutput.position() - contentOffset);
            return;
        }

        long length = utf8Length(value, monitor);
        if (length > Integer.MAX_VALUE) {
            throw new IOException("Catalog row string is too large");
        }
        output.writeInt((int) length);
        writeUtf8(value, output, monitor);
    }

    static String readString(RemainingDataInput input) throws IOException {
        try {
            return readString(input, null);
        } catch (InterruptedException ex) {
            throw impossibleWithoutMonitor(ex);
        }
    }

    private static String readString(RemainingDataInput input,
            IMonitor monitor) throws IOException, InterruptedException {
        int length = readBoundedLength(input, "string");
        IMonitor.checkCancelled(monitor);
        byte[] bytes = new byte[length];
        readFully(input, bytes, monitor);
        String value = new String(bytes,
                java.nio.charset.StandardCharsets.UTF_8);
        IMonitor.checkCancelled(monitor);
        return value;
    }

    static void writeValue(Object value, DataOutput output)
            throws IOException, UnsupportedRowValueException {
        try {
            writeValue(value, output, null);
        } catch (InterruptedException ex) {
            throw impossibleWithoutMonitor(ex);
        }
    }

    private static void writeValue(Object value, DataOutput output,
            IMonitor monitor)
            throws IOException, UnsupportedRowValueException,
            InterruptedException {
        if (value == null) {
            output.writeByte(TAG_NULL);
        } else if (value instanceof String string) {
            output.writeByte(TAG_STRING);
            writeString(string, output, monitor);
        } else if (value instanceof Long longValue) {
            output.writeByte(TAG_LONG);
            output.writeLong(longValue);
        } else if (value instanceof Integer intValue) {
            output.writeByte(TAG_INTEGER);
            output.writeInt(intValue);
        } else if (value instanceof Short shortValue) {
            output.writeByte(TAG_SHORT);
            output.writeShort(shortValue);
        } else if (value instanceof Boolean booleanValue) {
            output.writeByte(TAG_BOOLEAN);
            output.writeBoolean(booleanValue);
        } else if (value instanceof Double doubleValue) {
            output.writeByte(TAG_DOUBLE);
            output.writeDouble(doubleValue);
        } else if (value instanceof Float floatValue) {
            output.writeByte(TAG_FLOAT);
            output.writeFloat(floatValue);
        } else if (value instanceof byte[] bytes) {
            output.writeByte(TAG_BYTES);
            output.writeInt(bytes.length);
            writeBytes(bytes, output, monitor);
        } else if (value instanceof PgCachedCatalogArray captured) {
            writeArray(requireElements(captured), TAG_ARRAY_TEXT, output,
                    monitor);
            writeString(captured.text(), output, monitor);
        } else if (value instanceof Object[] array) {
            writeArray(array, TAG_ARRAY, output, monitor);
        } else {
            throw new UnsupportedRowValueException(
                    "Unsupported catalog row value type: " + value.getClass().getName());
        }
    }

    static Object readValue(RemainingDataInput input) throws IOException {
        try {
            return readValue(input, null);
        } catch (InterruptedException ex) {
            throw impossibleWithoutMonitor(ex);
        }
    }

    private static Object readValue(RemainingDataInput input,
            IMonitor monitor) throws IOException, InterruptedException {
        byte tag = input.readByte();
        return switch (tag) {
            case TAG_NULL -> null;
            case TAG_STRING -> readString(input, monitor);
            case TAG_LONG -> input.readLong();
            case TAG_INTEGER -> input.readInt();
            case TAG_SHORT -> input.readShort();
            case TAG_BOOLEAN -> input.readBoolean();
            case TAG_DOUBLE -> input.readDouble();
            case TAG_FLOAT -> input.readFloat();
            case TAG_BYTES -> readBytes(input, monitor);
            case TAG_ARRAY -> readArray(input, monitor);
            case TAG_ARRAY_TEXT -> readCapturedArray(input, monitor);
            default -> throw new IllegalArgumentException("Unknown value tag: " + tag);
        };
    }

    private static PgCachedCatalogArray readCapturedArray(
            RemainingDataInput input, IMonitor monitor)
            throws IOException, InterruptedException {
        Object[] elements = readArray(input, monitor);
        return new PgCachedCatalogArray(elements, readString(input, monitor));
    }

    /**
     * Returns the element array of a captured column, or fails the row when
     * the driver handed out a shape this codec has no stable encoding for.
     * The replay path keeps working either way; only the pack is skipped.
     */
    private static Object[] requireElements(PgCachedCatalogArray captured)
            throws UnsupportedRowValueException {
        if (captured.elements() instanceof Object[] elements) {
            return elements;
        }
        throw new UnsupportedRowValueException(
                "Unsupported catalog row array type: "
                        + captured.elements().getClass().getName());
    }

    private static long encodedArraySize(Object[] array, long remaining,
            IMonitor monitor)
            throws UnsupportedRowValueException, InterruptedException {
        byte componentTag = componentTag(array.getClass().getComponentType());
        if (array.length > MAX_ARRAY_ELEMENTS) {
            return remaining + 1L;
        }
        long size = Byte.BYTES + Byte.BYTES + Integer.BYTES;
        for (int i = 0; i < array.length; i++) {
            checkLoopCancelled(monitor, i);
            Object element = array[i];
            size = addBounded(size, Byte.BYTES, remaining);
            if (size > remaining) {
                return size;
            }
            if (element == null) {
                continue;
            }
            long elementSize = switch (componentTag) {
                case TAG_STRING -> encodedStringSize((String) element,
                        monitor);
                case TAG_LONG, TAG_DOUBLE -> Long.BYTES;
                case TAG_INTEGER, TAG_FLOAT -> Integer.BYTES;
                case TAG_SHORT -> Short.BYTES;
                case TAG_BOOLEAN -> Byte.BYTES;
                default -> throw new IllegalStateException("Unexpected component tag");
            };
            size = addBounded(size, elementSize, remaining);
            if (size > remaining) {
                return size;
            }
        }
        return size;
    }

    private static void writeArray(Object[] array, byte arrayTag,
            DataOutput output, IMonitor monitor)
            throws IOException, UnsupportedRowValueException,
            InterruptedException {
        byte componentTag = componentTag(array.getClass().getComponentType());
        if (array.length > MAX_ARRAY_ELEMENTS) {
            throw new IllegalArgumentException(
                    "Array length exceeds maximum: " + array.length);
        }
        output.writeByte(arrayTag);
        output.writeByte(componentTag);
        output.writeInt(array.length);
        for (int i = 0; i < array.length; i++) {
            checkLoopCancelled(monitor, i);
            Object element = array[i];
            if (element == null) {
                output.writeByte(TAG_NULL);
                continue;
            }
            output.writeByte(componentTag);
            switch (componentTag) {
                case TAG_STRING -> writeString((String) element, output,
                        monitor);
                case TAG_LONG -> output.writeLong((Long) element);
                case TAG_INTEGER -> output.writeInt((Integer) element);
                case TAG_SHORT -> output.writeShort((Short) element);
                case TAG_BOOLEAN -> output.writeBoolean((Boolean) element);
                case TAG_DOUBLE -> output.writeDouble((Double) element);
                case TAG_FLOAT -> output.writeFloat((Float) element);
                default -> throw new IllegalStateException("Unexpected component tag");
            }
        }
    }

    private static Object[] readArray(RemainingDataInput input, IMonitor monitor)
            throws IOException, InterruptedException {
        byte componentTag = input.readByte();
        int length = readBoundedLength(input, "array");
        if (length > MAX_ARRAY_ELEMENTS) {
            throw new IllegalArgumentException("Array length exceeds maximum: " + length);
        }
        IMonitor.checkCancelled(monitor);
        Object[] array = switch (componentTag) {
            case TAG_STRING -> new String[length];
            case TAG_LONG -> new Long[length];
            case TAG_INTEGER -> new Integer[length];
            case TAG_SHORT -> new Short[length];
            case TAG_BOOLEAN -> new Boolean[length];
            case TAG_DOUBLE -> new Double[length];
            case TAG_FLOAT -> new Float[length];
            default -> throw new IllegalArgumentException(
                    "Unknown array component tag: " + componentTag);
        };
        for (int i = 0; i < length; i++) {
            checkLoopCancelled(monitor, i);
            byte tag = input.readByte();
            if (tag == TAG_NULL) {
                continue;
            }
            if (tag != componentTag) {
                throw new IllegalArgumentException("Array element tag mismatch");
            }
            array[i] = switch (componentTag) {
                case TAG_STRING -> readString(input, monitor);
                case TAG_LONG -> input.readLong();
                case TAG_INTEGER -> input.readInt();
                case TAG_SHORT -> input.readShort();
                case TAG_BOOLEAN -> input.readBoolean();
                case TAG_DOUBLE -> input.readDouble();
                case TAG_FLOAT -> input.readFloat();
                default -> throw new IllegalStateException("Unexpected component tag");
            };
        }
        return array;
    }

    private static byte[] readBytes(RemainingDataInput input,
            IMonitor monitor) throws IOException, InterruptedException {
        int length = readBoundedLength(input, "byte");
        IMonitor.checkCancelled(monitor);
        byte[] bytes = new byte[length];
        readFully(input, bytes, monitor);
        return bytes;
    }

    private static int readBoundedLength(RemainingDataInput input, String role)
            throws IOException {
        int length = input.readInt();
        int remaining = input.remaining();
        if (length < 0 || length > remaining) {
            throw new IllegalArgumentException(
                    "Invalid " + role + " length " + length
                            + " for " + remaining + " remaining bytes");
        }
        return length;
    }

    private static byte componentTag(Class<?> componentType)
            throws UnsupportedRowValueException {
        if (componentType == String.class) {
            return TAG_STRING;
        }
        if (componentType == Long.class) {
            return TAG_LONG;
        }
        if (componentType == Integer.class) {
            return TAG_INTEGER;
        }
        if (componentType == Short.class) {
            return TAG_SHORT;
        }
        if (componentType == Boolean.class) {
            return TAG_BOOLEAN;
        }
        if (componentType == Double.class) {
            return TAG_DOUBLE;
        }
        if (componentType == Float.class) {
            return TAG_FLOAT;
        }
        throw new UnsupportedRowValueException(
                "Unsupported catalog row array component type: " + componentType.getName());
    }

    private static long utf8Length(String value, IMonitor monitor)
            throws InterruptedException {
        long length = 0L;
        for (int i = 0; i < value.length(); i++) {
            checkLoopCancelled(monitor, i);
            char c = value.charAt(i);
            if (c < 0x80) {
                length++;
            } else if (c < 0x800) {
                length += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                length += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                length++;
            } else {
                length += 3;
            }
        }
        return length;
    }

    private static void writeUtf8(String value, DataOutput output,
            IMonitor monitor) throws IOException, InterruptedException {
        for (int i = 0; i < value.length(); i++) {
            checkLoopCancelled(monitor, i);
            char c = value.charAt(i);
            if (c < 0x80) {
                output.writeByte(c);
            } else if (c < 0x800) {
                output.writeByte(0xC0 | c >>> 6);
                output.writeByte(0x80 | c & 0x3F);
            } else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                int codePoint = Character.toCodePoint(c, value.charAt(++i));
                output.writeByte(0xF0 | codePoint >>> 18);
                output.writeByte(0x80 | codePoint >>> 12 & 0x3F);
                output.writeByte(0x80 | codePoint >>> 6 & 0x3F);
                output.writeByte(0x80 | codePoint & 0x3F);
            } else if (Character.isSurrogate(c)) {
                output.writeByte('?');
            } else {
                output.writeByte(0xE0 | c >>> 12);
                output.writeByte(0x80 | c >>> 6 & 0x3F);
                output.writeByte(0x80 | c & 0x3F);
            }
        }
    }

    private static void writeBytes(byte[] bytes, DataOutput output,
            IMonitor monitor) throws IOException, InterruptedException {
        int offset = 0;
        while (offset < bytes.length) {
            IMonitor.checkCancelled(monitor);
            int length = Math.min(COPY_CHUNK_BYTES, bytes.length - offset);
            output.write(bytes, offset, length);
            offset += length;
        }
    }

    private static void readFully(RemainingDataInput input, byte[] bytes,
            IMonitor monitor) throws IOException, InterruptedException {
        int offset = 0;
        while (offset < bytes.length) {
            IMonitor.checkCancelled(monitor);
            int length = Math.min(COPY_CHUNK_BYTES, bytes.length - offset);
            input.readFully(bytes, offset, length);
            offset += length;
        }
    }

    private static void checkLoopCancelled(IMonitor monitor, int index)
            throws InterruptedException {
        if ((index & (LOOP_CHECK_INTERVAL - 1)) == 0) {
            IMonitor.checkCancelled(monitor);
        }
    }

    private static IllegalStateException impossibleWithoutMonitor(
            InterruptedException ex) {
        return new IllegalStateException(
                "Unexpected catalog row cancellation without a monitor", ex);
    }

    private static long addBounded(long current, long addition, long limit) {
        if (addition < 0 || current > limit || addition > limit - current) {
            return limit + 1L;
        }
        return current + addition;
    }

    private static void requirePayloadLimit(long maxPayloadBytes) {
        if (maxPayloadBytes <= 0 || maxPayloadBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Row payload cap must be positive and smaller than Integer.MAX_VALUE");
        }
    }

    private static void requireColumnCount(int columnCount) {
        if (columnCount < 0 || columnCount > MAX_COLUMN_COUNT) {
            throw new IllegalArgumentException("Invalid catalog row column count: " + columnCount);
        }
    }

    interface RemainingDataInput extends DataInput {
        int remaining();
    }

    static final class BoundedDataInput implements RemainingDataInput {

        private final DataInput delegate;
        private int remaining;

        BoundedDataInput(DataInput delegate, int availableBytes) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            if (availableBytes < 0) {
                throw new IllegalArgumentException("Available payload bytes must not be negative");
            }
            remaining = availableBytes;
        }

        @Override
        public int remaining() {
            return remaining;
        }

        private void require(int count) throws EOFException {
            if (count < 0 || count > remaining) {
                throw new EOFException("Catalog row payload is truncated");
            }
            remaining -= count;
        }

        @Override
        public void readFully(byte[] bytes) throws IOException {
            readFully(bytes, 0, bytes.length);
        }

        @Override
        public void readFully(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            require(length);
            delegate.readFully(bytes, offset, length);
        }

        @Override
        public int skipBytes(int count) throws IOException {
            int allowed = Math.min(Math.max(count, 0), remaining);
            int skipped = delegate.skipBytes(allowed);
            remaining -= skipped;
            return skipped;
        }

        @Override
        public boolean readBoolean() throws IOException {
            require(Byte.BYTES);
            return delegate.readBoolean();
        }

        @Override
        public byte readByte() throws IOException {
            require(Byte.BYTES);
            return delegate.readByte();
        }

        @Override
        public int readUnsignedByte() throws IOException {
            require(Byte.BYTES);
            return delegate.readUnsignedByte();
        }

        @Override
        public short readShort() throws IOException {
            require(Short.BYTES);
            return delegate.readShort();
        }

        @Override
        public int readUnsignedShort() throws IOException {
            require(Short.BYTES);
            return delegate.readUnsignedShort();
        }

        @Override
        public char readChar() throws IOException {
            require(Character.BYTES);
            return delegate.readChar();
        }

        @Override
        public int readInt() throws IOException {
            require(Integer.BYTES);
            return delegate.readInt();
        }

        @Override
        public long readLong() throws IOException {
            require(Long.BYTES);
            return delegate.readLong();
        }

        @Override
        public float readFloat() throws IOException {
            require(Float.BYTES);
            return delegate.readFloat();
        }

        @Override
        public double readDouble() throws IOException {
            require(Double.BYTES);
            return delegate.readDouble();
        }

        @Override
        public String readLine() {
            throw new UnsupportedOperationException("readLine is not used by the catalog row codec");
        }

        @Override
        public String readUTF() {
            throw new UnsupportedOperationException("readUTF is not used by the catalog row codec");
        }
    }

    static final class ReusableArrayDataInput implements RemainingDataInput {

        private static final byte[] EMPTY_BYTES = new byte[0];

        private byte[] bytes = EMPTY_BYTES;
        private int position;
        private int end;

        void reset(byte[] bytes, int offset, int length) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            position = offset;
            end = offset + length;
        }

        private void clear() {
            bytes = EMPTY_BYTES;
            position = 0;
            end = 0;
        }

        @Override
        public int remaining() {
            return end - position;
        }

        private void require(int count) throws EOFException {
            if (count < 0 || count > remaining()) {
                throw new EOFException("Catalog row payload is truncated");
            }
        }

        @Override
        public void readFully(byte[] target) throws IOException {
            readFully(target, 0, target.length);
        }

        @Override
        public void readFully(byte[] target, int offset, int length)
                throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            require(length);
            System.arraycopy(bytes, position, target, offset, length);
            position += length;
        }

        @Override
        public int skipBytes(int count) {
            int skipped = Math.min(Math.max(count, 0), remaining());
            position += skipped;
            return skipped;
        }

        @Override
        public boolean readBoolean() throws IOException {
            return readUnsignedByte() != 0;
        }

        @Override
        public byte readByte() throws IOException {
            require(Byte.BYTES);
            return bytes[position++];
        }

        @Override
        public int readUnsignedByte() throws IOException {
            return Byte.toUnsignedInt(readByte());
        }

        @Override
        public short readShort() throws IOException {
            return (short) readUnsignedShort();
        }

        @Override
        public int readUnsignedShort() throws IOException {
            require(Short.BYTES);
            int value = Byte.toUnsignedInt(bytes[position]) << 8
                    | Byte.toUnsignedInt(bytes[position + 1]);
            position += Short.BYTES;
            return value;
        }

        @Override
        public char readChar() throws IOException {
            return (char) readUnsignedShort();
        }

        @Override
        public int readInt() throws IOException {
            require(Integer.BYTES);
            int value = Byte.toUnsignedInt(bytes[position]) << 24
                    | Byte.toUnsignedInt(bytes[position + 1]) << 16
                    | Byte.toUnsignedInt(bytes[position + 2]) << 8
                    | Byte.toUnsignedInt(bytes[position + 3]);
            position += Integer.BYTES;
            return value;
        }

        @Override
        public long readLong() throws IOException {
            return (long) readInt() << 32
                    | Integer.toUnsignedLong(readInt());
        }

        @Override
        public float readFloat() throws IOException {
            return Float.intBitsToFloat(readInt());
        }

        @Override
        public double readDouble() throws IOException {
            return Double.longBitsToDouble(readLong());
        }

        @Override
        public String readLine() {
            throw new UnsupportedOperationException(
                    "readLine is not used by the catalog row codec");
        }

        @Override
        public String readUTF() {
            throw new UnsupportedOperationException(
                    "readUTF is not used by the catalog row codec");
        }
    }

    static class ArrayDataOutput extends DataOutputStream {

        private final FixedByteArrayOutputStream bytes;

        ArrayDataOutput(byte[] payload) {
            this(new FixedByteArrayOutputStream());
            reset(payload, 0, payload.length);
        }

        ArrayDataOutput() {
            this(new FixedByteArrayOutputStream());
        }

        private ArrayDataOutput(FixedByteArrayOutputStream bytes) {
            super(bytes);
            this.bytes = bytes;
        }

        int position() {
            return bytes.position();
        }

        void reset(byte[] payload, int offset, int length) {
            bytes.reset(payload, offset, length);
            written = 0;
        }

        void clear() {
            bytes.clear();
            written = 0;
        }

        int reserveInt() {
            return bytes.reserve(Integer.BYTES);
        }

        void writeIntAt(int offset, int value) {
            bytes.writeIntAt(offset, value);
        }
    }

    private static final class FixedByteArrayOutputStream extends OutputStream {

        private static final byte[] EMPTY = new byte[0];

        private byte[] payload = EMPTY;
        private int start;
        private int position;
        private int limit;

        private FixedByteArrayOutputStream() {
            // reset before use
        }

        private int position() {
            return position - start;
        }

        private void reset(byte[] payload, int offset, int length) {
            Objects.requireNonNull(payload, "payload");
            Objects.checkFromIndexSize(offset, length, payload.length);
            this.payload = payload;
            start = offset;
            position = offset;
            limit = offset + length;
        }

        private void clear() {
            payload = EMPTY;
            start = 0;
            position = 0;
            limit = 0;
        }

        private int reserve(int length) {
            if (length < 0 || length > limit - position) {
                throw new IndexOutOfBoundsException("Catalog row output exceeds payload size");
            }
            int offset = position;
            position += length;
            return offset;
        }

        private void writeIntAt(int offset, int value) {
            if (offset < start || offset > position - Integer.BYTES) {
                throw new IndexOutOfBoundsException(
                        "Catalog row backpatch is outside the written payload");
            }
            payload[offset] = (byte) (value >>> 24);
            payload[offset + 1] = (byte) (value >>> 16);
            payload[offset + 2] = (byte) (value >>> 8);
            payload[offset + 3] = (byte) value;
        }

        @Override
        public void write(int value) {
            payload[reserve(Byte.BYTES)] = (byte) value;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            int target = reserve(length);
            System.arraycopy(bytes, offset, payload, target, length);
        }
    }
}
