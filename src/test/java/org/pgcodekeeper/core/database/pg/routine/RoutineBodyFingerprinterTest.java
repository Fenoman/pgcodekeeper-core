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
package org.pgcodekeeper.core.database.pg.routine;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RoutineBodyFingerprinterTest {

    @ParameterizedTest
    @MethodSource("wellFormedBodies")
    void fingerprintMatchesJdkSha256AndUtf8Length(String raw) throws Exception {
        RoutineBodyMeasure measure = RoutineBodyFingerprinter.measure(raw);

        assertEquals(jdkFingerprint(raw), assertInstanceOf(RoutineFingerprint.class, measure));
    }

    @ParameterizedTest
    @MethodSource("malformedBodies")
    void malformedUtf16HasExactJdkLengthButNoReusableFingerprint(String raw) {
        RoutineBodyMeasure measure = RoutineBodyFingerprinter.measure(raw);

        var unreusable = assertInstanceOf(RoutineBodyMeasure.Unreusable.class, measure);
        assertAll(
                () -> assertEquals(raw.getBytes(StandardCharsets.UTF_8).length,
                        unreusable.utf8Length()),
                () -> assertEquals(RoutineBodyMeasure.Reason.MALFORMED_UTF16,
                        unreusable.reason()));
    }

    @Test
    void normalizedMeasureIsBlindToCarriageReturnsOnlyWhenNewlinesAreNotKept() {
        String crlfRaw = "BEGIN\r\n RETURN;\r\nEND";
        String lfRaw = "BEGIN\n RETURN;\nEND";

        assertAll(
                () -> assertEquals(
                        RoutineBodyFingerprinter.measure(lfRaw),
                        RoutineBodyFingerprinter.measure(crlfRaw, false),
                        "with keep-newlines off the measure must strip CR"),
                () -> assertEquals(
                        RoutineBodyFingerprinter.measure(crlfRaw),
                        RoutineBodyFingerprinter.measure(crlfRaw, true),
                        "with keep-newlines on the measure stays byte-exact"),
                () -> assertNotEquals(
                        RoutineBodyFingerprinter.measure(crlfRaw, true),
                        RoutineBodyFingerprinter.measure(lfRaw, true)));
    }

    @Test
    void largeBodyCrossesFixedWorkspaceBufferWithoutChangingFingerprint() throws Exception {
        String raw = "SELECT 'Привет 😀 $function$';\r\n".repeat(32_768);

        assertEquals(jdkFingerprint(raw), RoutineBodyFingerprinter.measure(raw));
    }

    @Test
    void serverDigestFactoryDoesNotRetainMutableDigestArray() throws Exception {
        String raw = "SELECT 'immutable digest';";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
        RoutineFingerprint fingerprint = RoutineFingerprint.fromSha256(
                raw.getBytes(StandardCharsets.UTF_8).length, digest);

        Arrays.fill(digest, (byte) 0);

        assertEquals(jdkFingerprint(raw), fingerprint);
    }

    @Test
    void reusableWorkspaceHasNoFieldThatCanRetainInputCharacters() {
        Class<?> workspace = Arrays.stream(RoutineBodyFingerprinter.class.getDeclaredClasses())
                .filter(type -> "Workspace".equals(type.getSimpleName()))
                .findFirst()
                .orElseThrow();

        List<Class<?>> fieldTypes = Arrays.stream(workspace.getDeclaredFields())
                .map(Field::getType)
                .toList();
        assertAll(
                () -> assertTrue(fieldTypes.contains(ByteBuffer.class)),
                () -> assertTrue(fieldTypes.contains(CharsetEncoder.class)),
                () -> assertTrue(fieldTypes.contains(MessageDigest.class)),
                () -> assertTrue(fieldTypes.stream()
                        .noneMatch(type -> CharSequence.class.isAssignableFrom(type)
                                || CharBuffer.class.isAssignableFrom(type))));
    }

    @Test
    void workspaceBufferIsFixedAndSmall() throws Exception {
        Field workspaceField = RoutineBodyFingerprinter.class.getDeclaredField("WORKSPACE");
        workspaceField.setAccessible(true);
        Object workspace = ((ThreadLocal<?>) workspaceField.get(null)).get();
        Field bufferField = workspace.getClass().getDeclaredField("buffer");
        bufferField.setAccessible(true);
        ByteBuffer buffer = (ByteBuffer) bufferField.get(workspace);

        assertTrue(buffer.capacity() > 0 && buffer.capacity() <= 16 * 1024,
                () -> "unexpected fingerprint workspace capacity: " + buffer.capacity());
    }

    @Test
    void productionRoutinePackageNeverMaterializesBodiesWithStringGetBytes() throws Exception {
        Path packageDir = Path.of("src/main/java/org/pgcodekeeper/core/database/pg/routine");
        try (var files = Files.list(packageDir)) {
            List<Path> offenders = files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains(".getBytes(");
                        } catch (java.io.IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    })
                    .toList();
            assertTrue(offenders.isEmpty(), () -> "body-sized byte[] regression: " + offenders);
        }
    }

    @Test
    void publicFactoriesRejectNullNegativeLengthAndWrongDigestSize() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> RoutineBodyFingerprinter.measure(null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RoutineFingerprint.fromSha256(-1, new byte[32])),
                () -> assertThrows(NullPointerException.class,
                        () -> RoutineFingerprint.fromSha256(0, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RoutineFingerprint.fromSha256(0, new byte[31])),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RoutineFingerprint.fromSha256(0, new byte[33])));
    }

    private static RoutineFingerprint jdkFingerprint(String raw) throws Exception {
        byte[] utf8 = raw.getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(utf8);
        return RoutineFingerprint.fromSha256(utf8.length, digest);
    }

    private static Stream<String> wellFormedBodies() {
        return Stream.of(
                "",
                "SELECT 'ascii';",
                "SELECT 'Привет';",
                "SELECT '😀';",
                "SELECT '$', '$_$', '$_X$', '$function$';",
                "line one\nline two",
                "line one\rline two",
                "line one\r\nline two");
    }

    private static Stream<String> malformedBodies() {
        return Stream.of(
                "\uD800",
                "\uDC00",
                "before\uD800after",
                "\uD800\uD800\uDC00",
                "\uDC00\uD800\uDC00",
                "\uD800\uDC00\uD800");
    }
}
