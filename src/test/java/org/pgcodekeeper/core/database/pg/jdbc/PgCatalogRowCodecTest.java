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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trip and integrity tests of the catalog row payload codec. The row
 * store key is a server MD5 that cannot be re-derived client-side, so the
 * codec's internal CRC and format version are the only integrity layer.
 */
class PgCatalogRowCodecTest {

    private static final int INVALID_LENGTH = 1 << 30;
    private static final int TEST_PAYLOAD_LIMIT = 1 << 20;
    private static final int CHILD_UTF8_CHAR_COUNT = 6_000_000;
    private static final String CHILD_OK = "codec-miss";

    @TempDir
    private Path tempDir;

    private static PgCachedCatalogRow sampleRow() {
        return new PgCachedCatalogRow(
                new String[] {"s", "big", "i", "sm", "b", "d", "f", "by", "sa", "la", "n"},
                new Object[] {
                        "text текст", 42L, 7, (short) 3, Boolean.TRUE,
                        2.5D, 1.5F, new byte[] {5, 6, 7},
                        new String[] {"a", null, "é"}, new Long[] {1L, null, 3L}, null
                });
    }

    @Test
    void roundTripPreservesEveryTypeExactly() throws Exception {
        PgCachedCatalogRow original = sampleRow();

        PgCachedCatalogRow decoded = PgCatalogRowCodec.deserialize(
                PgCatalogRowCodec.serialize(original, TEST_PAYLOAD_LIMIT));

        assertNotNull(decoded);
        assertArrayEquals(original.labels(), decoded.labels());
        Object[] values = decoded.values();
        assertEquals("text текст", values[0]);
        assertSame(Long.class, values[1].getClass());
        assertEquals(42L, values[1]);
        assertSame(Integer.class, values[2].getClass());
        assertSame(Short.class, values[3].getClass());
        assertSame(Boolean.class, values[4].getClass());
        assertSame(Double.class, values[5].getClass());
        assertSame(Float.class, values[6].getClass());
        assertArrayEquals(new byte[] {5, 6, 7}, (byte[]) values[7]);
        assertSame(String[].class, values[8].getClass());
        assertArrayEquals(new String[] {"a", null, "é"}, (String[]) values[8]);
        assertSame(Long[].class, values[9].getClass());
        assertArrayEquals(new Long[] {1L, null, 3L}, (Long[]) values[9]);
        assertNull(values[10]);
    }

    @Test
    void emptyRowAndEmptyArrayRoundTrip() throws Exception {
        var original = new PgCachedCatalogRow(
                new String[] {"empty"}, new Object[] {new String[0]});

        PgCachedCatalogRow decoded = PgCatalogRowCodec.deserialize(
                PgCatalogRowCodec.serialize(original, TEST_PAYLOAD_LIMIT));

        assertNotNull(decoded);
        assertArrayEquals(new String[0], (String[]) decoded.values()[0]);
    }

    @Test
    void formatVersionMismatchIsAMiss() throws Exception {
        byte[] payload = PgCatalogRowCodec.serialize(sampleRow(), TEST_PAYLOAD_LIMIT);
        payload[0] = (byte) (PgCatalogRowCodec.FORMAT_VERSION + 1);
        // keep the CRC valid for the altered version byte so only the
        // version check can reject the payload
        byte[] recrc = recrc(payload);

        assertNull(PgCatalogRowCodec.deserialize(recrc));
    }

    @Test
    void corruptChecksumIsAMiss() throws Exception {
        byte[] payload = PgCatalogRowCodec.serialize(sampleRow(), TEST_PAYLOAD_LIMIT);
        payload[payload.length / 2] ^= 0x40;

        assertNull(PgCatalogRowCodec.deserialize(payload));
    }

    @Test
    void truncatedAndOversizedPayloadsAreMisses() throws Exception {
        byte[] payload = PgCatalogRowCodec.serialize(sampleRow(), TEST_PAYLOAD_LIMIT);

        assertNull(PgCatalogRowCodec.deserialize(Arrays.copyOf(payload, payload.length - 1)));
        assertNull(PgCatalogRowCodec.deserialize(new byte[0]));
        assertNull(PgCatalogRowCodec.deserialize(null));
        byte[] extended = Arrays.copyOf(payload, payload.length + 1);
        assertNull(PgCatalogRowCodec.deserialize(extended));
    }

    @Test
    void trailingGarbageWithValidCrcIsAMiss() throws Exception {
        byte[] payload = PgCatalogRowCodec.serialize(sampleRow(), TEST_PAYLOAD_LIMIT);
        int bodyLength = payload.length - Integer.BYTES;
        // body + three garbage bytes + a CRC recomputed to be valid, so only
        // the exact-consumption check can reject the payload
        byte[] withGarbage = new byte[bodyLength + 3 + Integer.BYTES];
        System.arraycopy(payload, 0, withGarbage, 0, bodyLength);
        withGarbage[bodyLength] = 9;

        assertNull(PgCatalogRowCodec.deserialize(recrc(withGarbage)));
    }

    @Test
    void unsupportedValueTypesAreReported() {
        var decimalRow = new PgCachedCatalogRow(
                new String[] {"x"}, new Object[] {new BigDecimal("1.5")});
        var decimalArrayRow = new PgCachedCatalogRow(
                new String[] {"x"}, new Object[] {new BigDecimal[] {BigDecimal.ONE}});

        assertThrows(PgCatalogRowCodec.UnsupportedRowValueException.class,
                () -> PgCatalogRowCodec.serialize(decimalRow, TEST_PAYLOAD_LIMIT));
        assertThrows(PgCatalogRowCodec.UnsupportedRowValueException.class,
                () -> PgCatalogRowCodec.serialize(decimalArrayRow, TEST_PAYLOAD_LIMIT));
    }

    @Test
    void largeStringsBeyondModifiedUtfLimitRoundTrip() throws Exception {
        String big = "v".repeat(70_000) + "я";
        var original = new PgCachedCatalogRow(new String[] {"def"}, new Object[] {big});

        PgCachedCatalogRow decoded = PgCatalogRowCodec.deserialize(
                PgCatalogRowCodec.serialize(original, TEST_PAYLOAD_LIMIT));

        assertNotNull(decoded);
        assertEquals(big, decoded.values()[0]);
    }

    @Test
    void boundedSerializationMatchesReferenceWireBytesForEveryTypeAndUnicode()
            throws Exception {
        PgCachedCatalogRow row = wireParityRow();

        assertArrayEquals(referenceSerialize(row),
                PgCatalogRowCodec.serialize(row, TEST_PAYLOAD_LIMIT));
    }

    @Test
    void boundedSerializationAcceptsExactLimitAndRejectsOneByteLess() throws Exception {
        PgCachedCatalogRow row = wireParityRow();
        byte[] expected = referenceSerialize(row);

        assertArrayEquals(expected, PgCatalogRowCodec.serialize(row, expected.length));
        assertNull(PgCatalogRowCodec.serialize(row, expected.length - 1));
    }

    @Test
    void serializationAcceptsMaximumArrayLengthAndRejectsOneMore() throws Exception {
        var maximumRow = new PgCachedCatalogRow(
                new String[] {"values"}, new Object[] {new String[Short.MAX_VALUE]});
        var oversizedRow = new PgCachedCatalogRow(
                new String[] {"values"}, new Object[] {new String[Short.MAX_VALUE + 1]});

        assertNotNull(PgCatalogRowCodec.serialize(maximumRow, TEST_PAYLOAD_LIMIT));
        assertNull(PgCatalogRowCodec.serialize(oversizedRow, TEST_PAYLOAD_LIMIT));
    }

    @Test
    void deserializationAcceptsMaximumArrayLengthAndRejectsOneMore() throws Exception {
        PgCachedCatalogRow maximum = PgCatalogRowCodec.deserialize(
                nullStringArrayPayload(Short.MAX_VALUE));

        assertNotNull(maximum);
        assertArrayEquals(new String[Short.MAX_VALUE], (String[]) maximum.values()[0]);
        assertNull(PgCatalogRowCodec.deserialize(
                nullStringArrayPayload(Short.MAX_VALUE + 1)));
    }

    @Test
    void boundedSerializationRejectsInvalidLimits() {
        for (int invalidLimit : new int[] {-1, 0, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PgCatalogRowCodec.serialize(sampleRow(), invalidLimit));
        }
    }

    @Test
    void oversizedMultibyteStringCannotExhaustChildJvm() throws Exception {
        assertChildCodecMiss("bounded-utf8");
    }

    @Test
    void invalidStringLengthCannotExhaustChildJvm() throws Exception {
        assertChildCodecMiss("string");
    }

    @Test
    void invalidByteLengthCannotExhaustChildJvm() throws Exception {
        assertChildCodecMiss("bytes");
    }

    @Test
    void invalidArrayLengthCannotExhaustChildJvm() throws Exception {
        assertChildCodecMiss("array");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one codec test mode");
        }
        if ("bounded-utf8".equals(args[0])) {
            String value = "\u0800".repeat(CHILD_UTF8_CHAR_COUNT);
            var row = new PgCachedCatalogRow(
                    new String[] {"value"}, new Object[] {value});
            if (PgCatalogRowCodec.serialize(row, TEST_PAYLOAD_LIMIT) != null) {
                throw new AssertionError("Oversized UTF-8 value was serialized");
            }
        } else if (PgCatalogRowCodec.deserialize(invalidLengthPayload(args[0])) != null) {
            throw new AssertionError("Invalid " + args[0] + " length was accepted");
        }
        System.out.println(CHILD_OK);
    }

    private void assertChildCodecMiss(String mode) throws Exception {
        String classPath = System.getProperty("surefire.test.class.path");
        if (classPath == null || classPath.isBlank()) {
            classPath = System.getProperty("java.class.path");
        }
        String executable = System.getProperty("os.name").startsWith("Windows")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path outputFile = tempDir.resolve(mode + ".out");
        var builder = new ProcessBuilder(
                java.toString(), "-Xmx32m", "-XX:-HeapDumpOnOutOfMemoryError",
                "-cp", classPath,
                PgCatalogRowCodecTest.class.getName(), mode)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");

        Process process = builder.start();
        boolean finished = false;
        try {
            finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        assertTrue(finished, () -> "Child timed out:\n" + output);
        assertEquals(0, process.exitValue(), () -> "Child failed:\n" + output);
        assertTrue(output.contains(CHILD_OK),
                () -> "Child did not confirm a codec miss:\n" + output);
    }

    private static byte[] invalidLengthPayload(String mode) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(bytes)) {
            data.writeByte(PgCatalogRowCodec.FORMAT_VERSION);
            data.writeInt(1);
            data.writeInt(0);
            switch (mode) {
                case "string" -> {
                    data.writeByte(1);
                    data.writeInt(INVALID_LENGTH);
                }
                case "bytes" -> {
                    data.writeByte(8);
                    data.writeInt(INVALID_LENGTH);
                }
                case "array" -> {
                    data.writeByte(9);
                    data.writeByte(1);
                    data.writeInt(INVALID_LENGTH);
                }
                default -> throw new IllegalArgumentException(
                        "Unknown forged-payload mode: " + mode);
            }
        }
        return recrc(Arrays.copyOf(bytes.toByteArray(), bytes.size() + Integer.BYTES));
    }

    private static byte[] nullStringArrayPayload(int length) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(bytes)) {
            data.writeByte(PgCatalogRowCodec.FORMAT_VERSION);
            data.writeInt(1);
            writeReferenceString(data, "values");
            data.writeByte(9);
            data.writeByte(1);
            data.writeInt(length);
            for (int i = 0; i < length; i++) {
                data.writeByte(0);
            }
        }
        return recrc(Arrays.copyOf(bytes.toByteArray(), bytes.size() + Integer.BYTES));
    }

    private static PgCachedCatalogRow wireParityRow() {
        return new PgCachedCatalogRow(
                new String[] {
                        "строка", "long", "integer", "short", "boolean", "double",
                        "float", "bytes", "strings", "longs", "integers", "shorts",
                        "booleans", "doubles", "floats", "null"
                },
                new Object[] {
                        "text é \uD83D\uDE00 malformed \uD800", 42L, 7, (short) 3,
                        Boolean.TRUE, Double.longBitsToDouble(0x7ff0000000000001L),
                        Float.intBitsToFloat(0x7f800001), new byte[] {0, 5, -1},
                        new String[] {"я", null, "\uD800"},
                        new Long[] {1L, null, -3L},
                        new Integer[] {1, null, -3},
                        new Short[] {(short) 1, null, (short) -3},
                        new Boolean[] {Boolean.TRUE, null, Boolean.FALSE},
                        new Double[] {1.5D, null,
                                Double.longBitsToDouble(0x7ff0000000000001L)},
                        new Float[] {1.5F, null, Float.intBitsToFloat(0x7f800001)},
                        null
                });
    }

    private static byte[] referenceSerialize(PgCachedCatalogRow row) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(bytes)) {
            data.writeByte(PgCatalogRowCodec.FORMAT_VERSION);
            data.writeInt(row.labels().length);
            for (int i = 0; i < row.labels().length; i++) {
                writeReferenceString(data, row.labels()[i]);
                writeReferenceValue(data, row.values()[i]);
            }
        }
        return recrc(Arrays.copyOf(bytes.toByteArray(), bytes.size() + Integer.BYTES));
    }

    private static void writeReferenceValue(DataOutputStream data, Object value)
            throws IOException {
        if (value == null) {
            data.writeByte(0);
        } else if (value instanceof String string) {
            data.writeByte(1);
            writeReferenceString(data, string);
        } else if (value instanceof Long longValue) {
            data.writeByte(2);
            data.writeLong(longValue);
        } else if (value instanceof Integer intValue) {
            data.writeByte(3);
            data.writeInt(intValue);
        } else if (value instanceof Short shortValue) {
            data.writeByte(4);
            data.writeShort(shortValue);
        } else if (value instanceof Boolean booleanValue) {
            data.writeByte(5);
            data.writeBoolean(booleanValue);
        } else if (value instanceof Double doubleValue) {
            data.writeByte(6);
            data.writeDouble(doubleValue);
        } else if (value instanceof Float floatValue) {
            data.writeByte(7);
            data.writeFloat(floatValue);
        } else if (value instanceof byte[] byteArray) {
            data.writeByte(8);
            data.writeInt(byteArray.length);
            data.write(byteArray);
        } else if (value instanceof Object[] array) {
            writeReferenceArray(data, array);
        } else {
            throw new IllegalArgumentException("Unexpected test value: " + value);
        }
    }

    private static void writeReferenceArray(DataOutputStream data, Object[] array)
            throws IOException {
        byte componentTag = referenceComponentTag(array.getClass().getComponentType());
        data.writeByte(9);
        data.writeByte(componentTag);
        data.writeInt(array.length);
        for (Object element : array) {
            if (element == null) {
                data.writeByte(0);
                continue;
            }
            data.writeByte(componentTag);
            switch (componentTag) {
                case 1 -> writeReferenceString(data, (String) element);
                case 2 -> data.writeLong((Long) element);
                case 3 -> data.writeInt((Integer) element);
                case 4 -> data.writeShort((Short) element);
                case 5 -> data.writeBoolean((Boolean) element);
                case 6 -> data.writeDouble((Double) element);
                case 7 -> data.writeFloat((Float) element);
                default -> throw new AssertionError("Unexpected component tag");
            }
        }
    }

    private static byte referenceComponentTag(Class<?> componentType) {
        if (componentType == String.class) {
            return 1;
        }
        if (componentType == Long.class) {
            return 2;
        }
        if (componentType == Integer.class) {
            return 3;
        }
        if (componentType == Short.class) {
            return 4;
        }
        if (componentType == Boolean.class) {
            return 5;
        }
        if (componentType == Double.class) {
            return 6;
        }
        if (componentType == Float.class) {
            return 7;
        }
        throw new IllegalArgumentException(
                "Unexpected test array component: " + componentType.getName());
    }

    private static void writeReferenceString(DataOutputStream data, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static byte[] recrc(byte[] payload) {
        var crc = new java.util.zip.CRC32();
        crc.update(payload, 0, payload.length - Integer.BYTES);
        int checksum = (int) crc.getValue();
        byte[] result = payload.clone();
        result[payload.length - 4] = (byte) (checksum >>> 24);
        result[payload.length - 3] = (byte) (checksum >>> 16);
        result[payload.length - 2] = (byte) (checksum >>> 8);
        result[payload.length - 1] = (byte) checksum;
        return result;
    }
}
