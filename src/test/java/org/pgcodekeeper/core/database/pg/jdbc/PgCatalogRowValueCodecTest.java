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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.monitor.NullMonitor;

class PgCatalogRowValueCodecTest {

    private static final int INVALID_LENGTH = 1 << 30;
    private static final int TEST_PAYLOAD_LIMIT = 1 << 20;
    private static final int CHILD_UTF8_CHAR_COUNT = 6_000_000;
    private static final String CHILD_OK = "value-codec-miss";

    @TempDir
    private Path tempDir;

    @Test
    void valuesRoundTripPreservesEverySupportedType() throws Exception {
        Object[] original = sampleValues();

        Object[] decoded = PgCatalogRowValueCodec.deserialize(
                PgCatalogRowValueCodec.serialize(original, TEST_PAYLOAD_LIMIT),
                original.length);

        assertNotNull(decoded);
        assertEquals("text текст", decoded[0]);
        assertSame(Long.class, decoded[1].getClass());
        assertEquals(42L, decoded[1]);
        assertSame(Integer.class, decoded[2].getClass());
        assertSame(Short.class, decoded[3].getClass());
        assertSame(Boolean.class, decoded[4].getClass());
        assertSame(Double.class, decoded[5].getClass());
        assertSame(Float.class, decoded[6].getClass());
        assertArrayEquals(new byte[] {5, 6, 7}, (byte[]) decoded[7]);
        assertArrayEquals(new String[] {"я", null, "é"}, (String[]) decoded[8]);
        assertArrayEquals(new Long[] {1L, null, -3L}, (Long[]) decoded[9]);
        assertArrayEquals(new Integer[] {1, null, -3}, (Integer[]) decoded[10]);
        assertArrayEquals(new Short[] {(short) 1, null, (short) -3},
                (Short[]) decoded[11]);
        assertArrayEquals(new Boolean[] {Boolean.TRUE, null, Boolean.FALSE},
                (Boolean[]) decoded[12]);
        assertArrayEquals(new Double[] {1.5D, null, -3.5D}, (Double[]) decoded[13]);
        assertArrayEquals(new Float[] {1.5F, null, -3.5F}, (Float[]) decoded[14]);
        assertNull(decoded[15]);
    }

    @Test
    void emptyValuesAndUnicodeBeyondModifiedUtfLimitRoundTrip() throws Exception {
        assertArrayEquals(new Object[0], PgCatalogRowValueCodec.deserialize(
                PgCatalogRowValueCodec.serialize(new Object[0], TEST_PAYLOAD_LIMIT), 0));

        String unicode = "v".repeat(70_000) + "я😀";
        Object[] decoded = PgCatalogRowValueCodec.deserialize(
                PgCatalogRowValueCodec.serialize(new Object[] {unicode}, TEST_PAYLOAD_LIMIT), 1);
        assertNotNull(decoded);
        assertEquals(unicode, decoded[0]);
    }

    @Test
    void encodedSizeMatchesWireAndExactLimitIsAccepted() throws Exception {
        Object[] values = sampleValues();
        byte[] payload = PgCatalogRowValueCodec.serialize(values, TEST_PAYLOAD_LIMIT);

        assertEquals(payload.length,
                PgCatalogRowValueCodec.encodedSize(values, TEST_PAYLOAD_LIMIT));
        assertArrayEquals(payload,
                PgCatalogRowValueCodec.serialize(values, payload.length));
        assertNull(PgCatalogRowValueCodec.serialize(values, payload.length - 1));
    }

    @Test
    void streamHelpersUseTheSameCanonicalWire() throws Exception {
        Object[] values = sampleValues();
        byte[] expected = PgCatalogRowValueCodec.serialize(values, TEST_PAYLOAD_LIMIT);
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            PgCatalogRowValueCodec.writeValues(values, output);
        }

        assertArrayEquals(expected, bytes.toByteArray());
        try (var input = new DataInputStream(new ByteArrayInputStream(expected))) {
            assertDeepValuesEqual(values,
                    PgCatalogRowValueCodec.readValues(input, values.length, expected.length));
        }
    }

    /**
     * The PostgreSQL text of an array column is the one shape a privilege
     * reader asks for, so it must survive the codec byte for byte. The cases
     * below are exactly the ones a hand-written array-literal renderer gets
     * wrong: embedded quotes, backslashes, commas, braces, empty and
     * {@code NULL}-looking elements, NULL elements and an empty array.
     */
    @Test
    void capturedArrayTextRoundTripsByteForByte() throws Exception {
        var captured = new PgCachedCatalogArray(
                new String[] {"a\"b", "c,d", "e\\f", null, "", "NULL",
                        "{braced}", " padded ", "я😀"},
                "{\"a\\\"b\",\"c,d\",\"e\\\\f\",NULL,\"\",\"NULL\","
                        + "\"{braced}\",\" padded \",\"я😀\"}");
        var aclitem = new PgCachedCatalogArray(
                new String[] {"user=arwdDxt/owner", "=r/owner"},
                "{user=arwdDxt/owner,=r/owner}");
        var empty = new PgCachedCatalogArray(new String[0], "{}");
        var allNull = new PgCachedCatalogArray(new String[] {null, null},
                "{NULL,NULL}");
        var typed = new PgCachedCatalogArray(new Long[] {1L, null, -3L},
                "{1,NULL,-3}");
        Object[] values = {captured, aclitem, empty, allNull, typed, null};

        Object[] decoded = PgCatalogRowValueCodec.deserialize(
                PgCatalogRowValueCodec.serialize(values, TEST_PAYLOAD_LIMIT),
                values.length);

        assertNotNull(decoded);
        for (int i = 0; i < values.length - 1; i++) {
            var source = (PgCachedCatalogArray) values[i];
            var target = (PgCachedCatalogArray) decoded[i];
            assertEquals(source.text(), target.text());
            assertArrayEquals((Object[]) source.elements(),
                    (Object[]) target.elements());
            assertSame(source.elements().getClass(),
                    target.elements().getClass());
        }
        // a NULL array column stays a null value, never an empty array
        assertNull(decoded[5]);
        assertEquals("{}", ((PgCachedCatalogArray) decoded[2]).text());
    }

    /**
     * A captured array must not change how a plain decoded array is stored,
     * and its text must be accounted for by the size estimate that enforces
     * the per-row payload cap.
     */
    @Test
    void capturedArrayKeepsPlainArrayWireAndIsSizeAccounted()
            throws Exception {
        String[] elements = {"a\"b", null, "c"};
        String text = "{\"a\\\"b\",NULL,c}";
        byte[] plain = PgCatalogRowValueCodec.serialize(
                new Object[] {elements}, TEST_PAYLOAD_LIMIT);
        Object[] values = {new PgCachedCatalogArray(elements, text)};
        byte[] payload = PgCatalogRowValueCodec.serialize(values,
                TEST_PAYLOAD_LIMIT);

        // same layout apart from the leading tag and the appended text
        assertEquals(plain.length
                + PgCatalogRowValueCodec.encodedStringSize(text),
                payload.length);
        assertArrayEquals(Arrays.copyOfRange(plain, 1, plain.length),
                Arrays.copyOfRange(payload, 1, plain.length));
        assertNotEquals((int) plain[0], (int) payload[0]);
        assertEquals(payload.length,
                PgCatalogRowValueCodec.encodedSize(values, TEST_PAYLOAD_LIMIT));
        assertNull(PgCatalogRowValueCodec.serialize(values,
                payload.length - 1));
        assertArrayEquals(elements, (Object[]) ((PgCachedCatalogArray)
                PgCatalogRowValueCodec.deserialize(payload, 1)[0]).elements());
    }

    @Test
    void capturedArrayOfUnsupportedShapeIsRejectedWithoutPartialPayload() {
        var unsupported = new PgCachedCatalogArray(
                new BigDecimal[] {BigDecimal.ONE}, "{1}");
        var primitive = new PgCachedCatalogArray(new int[] {1}, "{1}");

        assertThrows(PgCatalogRowCodec.UnsupportedRowValueException.class,
                () -> PgCatalogRowValueCodec.serialize(
                        new Object[] {unsupported}, TEST_PAYLOAD_LIMIT));
        assertThrows(PgCatalogRowCodec.UnsupportedRowValueException.class,
                () -> PgCatalogRowValueCodec.serialize(
                        new Object[] {primitive}, TEST_PAYLOAD_LIMIT));
    }

    @Test
    void reusableOffsetOutputUsesTheSameCanonicalWire() throws Exception {
        Object[] values = sampleValues();
        byte[] expected = PgCatalogRowValueCodec.serialize(values,
                TEST_PAYLOAD_LIMIT);
        byte[] padded = new byte[expected.length + 11];
        Arrays.fill(padded, (byte) 0x5A);
        var output = new PgCatalogRowValueCodec.ArrayDataOutput();

        PgCatalogRowValueCodec.serializeInto(values, padded, 5,
                expected.length, output);

        assertArrayEquals(expected,
                Arrays.copyOfRange(padded, 5, 5 + expected.length));
        assertArrayEquals(new byte[] { 0x5A, 0x5A, 0x5A, 0x5A, 0x5A },
                Arrays.copyOfRange(padded, 0, 5));
        assertEquals(0x5A, padded[padded.length - 1]);
    }

    @Test
    void reusableArrayInputDecodesExactSliceWithCanonicalParity()
            throws Exception {
        Object[] values = sampleValues();
        byte[] payload = PgCatalogRowValueCodec.serialize(values,
                TEST_PAYLOAD_LIMIT);
        byte[] padded = new byte[payload.length + 7];
        System.arraycopy(payload, 0, padded, 3, payload.length);
        var input = new PgCatalogRowValueCodec.ReusableArrayDataInput();

        Object[] first = PgCatalogRowValueCodec.deserialize(padded, 3,
                payload.length, values.length, input);
        Object[] second = PgCatalogRowValueCodec.deserialize(payload, 0,
                payload.length, values.length, input);

        assertDeepValuesEqual(values, first);
        assertDeepValuesEqual(values, second);
        assertNull(PgCatalogRowValueCodec.deserialize(padded, 3,
                payload.length - 1, values.length, input));
    }

    @Test
    void reusableArrayInputDoesNotRetainDecodedPayload() throws Exception {
        byte[] payload = PgCatalogRowValueCodec.serialize(
                new Object[] { "large value".repeat(10_000) },
                TEST_PAYLOAD_LIMIT);
        var input = new PgCatalogRowValueCodec.ReusableArrayDataInput();

        assertNotNull(PgCatalogRowValueCodec.deserialize(payload, 0,
                payload.length, 1, input));

        var bytesField = input.getClass().getDeclaredField("bytes");
        bytesField.setAccessible(true);
        assertEquals(0, ((byte[]) bytesField.get(input)).length);
    }

    @Test
    void cancellationIsCheckedInsideLargeValueEncodingCopy()
            throws Exception {
        Object[] values = { new byte[4 * 1024 * 1024] };
        int length = Math.toIntExact(PgCatalogRowValueCodec.encodedSize(
                values, 8L * 1024L * 1024L));
        byte[] target = new byte[length];
        var monitor = new CancelAfterChecksMonitor(8);

        assertThrows(InterruptedException.class,
                () -> PgCatalogRowValueCodec.serializeInto(values, target,
                        0, target.length,
                        new PgCatalogRowValueCodec.ArrayDataOutput(),
                        monitor));
        assertTrue(monitor.checks >= 8);
    }

    @Test
    void cancellationIsCheckedInsideLargeValueDecodingCopy()
            throws Exception {
        Object[] values = { new byte[4 * 1024 * 1024] };
        byte[] payload = PgCatalogRowValueCodec.serialize(values,
                8 * 1024 * 1024);
        var monitor = new CancelAfterChecksMonitor(8);

        assertThrows(InterruptedException.class,
                () -> PgCatalogRowValueCodec.deserialize(payload, 0,
                        payload.length, 1,
                        new PgCatalogRowValueCodec.ReusableArrayDataInput(),
                        monitor));
        assertTrue(monitor.checks >= 8);
    }

    @Test
    void fixedArrayOutputBackpatchesUtf8LengthWithoutChangingWireBytes() throws Exception {
        String value = "text я 😀 malformed \uD800";
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[Integer.BYTES + utf8.length];
        try (var reference = new DataOutputStream(
                new java.io.OutputStream() {
                    private int position;

                    @Override
                    public void write(int next) {
                        expected[position++] = (byte) next;
                    }
                })) {
            reference.writeInt(utf8.length);
            reference.write(utf8);
        }

        byte[] actual = new byte[expected.length];
        var output = new InstrumentedArrayDataOutput(actual);
        PgCatalogRowValueCodec.writeString(value, output);

        assertEquals(1, output.reservations);
        assertEquals(1, output.backpatches);
        assertArrayEquals(expected, actual);
    }

    @Test
    void unsupportedValuesAreRejectedWithoutPartialPayload() {
        assertThrows(PgCatalogRowCodec.UnsupportedRowValueException.class,
                () -> PgCatalogRowValueCodec.serialize(
                        new Object[] {new BigDecimal("1.5")}, TEST_PAYLOAD_LIMIT));
        assertThrows(PgCatalogRowCodec.UnsupportedRowValueException.class,
                () -> PgCatalogRowValueCodec.serialize(
                        new Object[] {new BigDecimal[] {BigDecimal.ONE}}, TEST_PAYLOAD_LIMIT));
    }

    @Test
    void corruptTruncatedTrailingAndWrongColumnCountAreMisses() throws Exception {
        byte[] payload = PgCatalogRowValueCodec.serialize(
                new Object[] {"value", 42L}, TEST_PAYLOAD_LIMIT);
        byte[] corrupt = payload.clone();
        corrupt[0] = 127;

        assertNull(PgCatalogRowValueCodec.deserialize(corrupt, 2));
        assertNull(PgCatalogRowValueCodec.deserialize(
                Arrays.copyOf(payload, payload.length - 1), 2));
        assertNull(PgCatalogRowValueCodec.deserialize(
                Arrays.copyOf(payload, payload.length + 1), 2));
        assertNull(PgCatalogRowValueCodec.deserialize(payload, 1));
        assertNull(PgCatalogRowValueCodec.deserialize(payload, 3));
        assertNull(PgCatalogRowValueCodec.deserialize(null, 2));
    }

    @Test
    void maximumArrayLengthRoundTripsAndOneMoreIsRejected() throws Exception {
        Object[] maximum = {new String[Short.MAX_VALUE]};
        Object[] oversized = {new String[Short.MAX_VALUE + 1]};

        byte[] payload = PgCatalogRowValueCodec.serialize(maximum, TEST_PAYLOAD_LIMIT);
        assertNotNull(payload);
        assertArrayEquals(new String[Short.MAX_VALUE],
                (String[]) PgCatalogRowValueCodec.deserialize(payload, 1)[0]);
        assertNull(PgCatalogRowValueCodec.serialize(oversized, TEST_PAYLOAD_LIMIT));
    }

    @Test
    void invalidLimitsAndColumnCountsAreRejected() {
        for (int limit : new int[] {-1, 0, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PgCatalogRowValueCodec.serialize(new Object[] {null}, limit));
        }
        assertThrows(IllegalArgumentException.class,
                () -> PgCatalogRowValueCodec.deserialize(new byte[0], -1));
        assertThrows(IllegalArgumentException.class,
                () -> PgCatalogRowValueCodec.deserialize(
                        new byte[Short.MAX_VALUE + 1], Short.MAX_VALUE + 1));
    }

    @Test
    void oversizedMultibyteStringCannotExhaustChildJvm() throws Exception {
        assertChildCodecMiss("bounded-utf8");
    }

    @Test
    void hostileStringLengthCannotExhaustChildJvm() throws Exception {
        assertChildCodecMiss("string");
    }

    @Test
    void hostileByteLengthCannotExhaustChildJvm() throws Exception {
        assertChildCodecMiss("bytes");
    }

    @Test
    void hostileArrayLengthCannotExhaustChildJvm() throws Exception {
        assertChildCodecMiss("array");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one value codec test mode");
        }
        if ("bounded-utf8".equals(args[0])) {
            String value = "\u0800".repeat(CHILD_UTF8_CHAR_COUNT);
            if (PgCatalogRowValueCodec.serialize(
                    new Object[] {value}, TEST_PAYLOAD_LIMIT) != null) {
                throw new AssertionError("Oversized UTF-8 value was serialized");
            }
        } else if (PgCatalogRowValueCodec.deserialize(
                invalidLengthPayload(args[0]), 1) != null) {
            throw new AssertionError("Hostile " + args[0] + " length was accepted");
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
                "-cp", classPath, PgCatalogRowValueCodecTest.class.getName(), mode)
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

    private static byte[] invalidLengthPayload(String mode) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            switch (mode) {
                case "string" -> {
                    output.writeByte(1);
                    output.writeInt(INVALID_LENGTH);
                }
                case "bytes" -> {
                    output.writeByte(8);
                    output.writeInt(INVALID_LENGTH);
                }
                case "array" -> {
                    output.writeByte(9);
                    output.writeByte(1);
                    output.writeInt(INVALID_LENGTH);
                }
                default -> throw new IllegalArgumentException(
                        "Unknown forged-payload mode: " + mode);
            }
        }
        return bytes.toByteArray();
    }

    private static Object[] sampleValues() {
        return new Object[] {
                "text текст", 42L, 7, (short) 3, Boolean.TRUE,
                2.5D, 1.5F, new byte[] {5, 6, 7},
                new String[] {"я", null, "é"}, new Long[] {1L, null, -3L},
                new Integer[] {1, null, -3},
                new Short[] {(short) 1, null, (short) -3},
                new Boolean[] {Boolean.TRUE, null, Boolean.FALSE},
                new Double[] {1.5D, null, -3.5D},
                new Float[] {1.5F, null, -3.5F}, null
        };
    }

    private static void assertDeepValuesEqual(Object[] expected, Object[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            Object left = expected[i];
            Object right = actual[i];
            if (left instanceof byte[] bytes) {
                assertArrayEquals(bytes, (byte[]) right);
            } else if (left instanceof Object[] array) {
                assertArrayEquals(array, (Object[]) right);
            } else {
                assertEquals(left, right);
            }
        }
    }

    private static final class InstrumentedArrayDataOutput
            extends PgCatalogRowValueCodec.ArrayDataOutput {

        private int reservations;
        private int backpatches;

        private InstrumentedArrayDataOutput(byte[] payload) {
            super(payload);
        }

        @Override
        int reserveInt() {
            reservations++;
            return super.reserveInt();
        }

        @Override
        void writeIntAt(int offset, int value) {
            backpatches++;
            super.writeIntAt(offset, value);
        }
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
}
