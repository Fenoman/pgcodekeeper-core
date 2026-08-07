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
package org.pgcodekeeper.core.database.base.project;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.Consts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Guards the idempotence of the project version marker.
 * <p>
 * The marker is a configuration input of the Eclipse project index. Rewriting it with identical content still bumps its
 * resource timestamp, which downgrades the next build to a full project reindex. Every export must therefore leave an
 * already-current marker completely untouched, while producing byte-identical content whenever it does write.
 */
class ProjectVersionMarkerTest {

    private static final byte[] EXPECTED =
            (Consts.VERSION_PROP_NAME + " = " + Consts.EXPORT_CURRENT_VERSION + '\n')
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    void testCreatesMarkerWithExpectedBytes(@TempDir Path tempDir) throws IOException {
        Path marker = tempDir.resolve(Consts.FILENAME_WORKING_DIR_MARKER);

        AbstractModelExporter.writeProjVersion(marker);

        Assertions.assertArrayEquals(EXPECTED, Files.readAllBytes(marker),
                "marker bytes must not change");
    }

    @Test
    void testCurrentMarkerIsNotTouched(@TempDir Path tempDir) throws IOException {
        Path marker = tempDir.resolve(Consts.FILENAME_WORKING_DIR_MARKER);
        AbstractModelExporter.writeProjVersion(marker);
        FileTime stamp = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(marker, stamp);

        AbstractModelExporter.writeProjVersion(marker);

        Assertions.assertEquals(stamp, Files.getLastModifiedTime(marker),
                "an up-to-date marker must not be rewritten");
        Assertions.assertArrayEquals(EXPECTED, Files.readAllBytes(marker));
    }

    @Test
    void testRepeatedWritesAreStable(@TempDir Path tempDir) throws IOException {
        Path marker = tempDir.resolve(Consts.FILENAME_WORKING_DIR_MARKER);

        AbstractModelExporter.writeProjVersion(marker);
        byte[] first = Files.readAllBytes(marker);
        for (int i = 0; i < 5; i++) {
            AbstractModelExporter.writeProjVersion(marker);
        }

        Assertions.assertArrayEquals(first, Files.readAllBytes(marker));
    }

    @Test
    void testStaleMarkerIsUpgraded(@TempDir Path tempDir) throws IOException {
        Path marker = tempDir.resolve(Consts.FILENAME_WORKING_DIR_MARKER);
        Files.writeString(marker, Consts.VERSION_PROP_NAME + " = 0.0.1\n");
        FileTime stamp = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(marker, stamp);

        AbstractModelExporter.writeProjVersion(marker);

        Assertions.assertArrayEquals(EXPECTED, Files.readAllBytes(marker),
                "a stale marker must be upgraded to the current version");
        Assertions.assertNotEquals(stamp, Files.getLastModifiedTime(marker),
                "upgrading the marker must actually rewrite it");
    }

    @Test
    void testTruncatedMarkerIsRewritten(@TempDir Path tempDir) throws IOException {
        Path marker = tempDir.resolve(Consts.FILENAME_WORKING_DIR_MARKER);
        Files.write(marker, new byte[0]);

        AbstractModelExporter.writeProjVersion(marker);

        Assertions.assertArrayEquals(EXPECTED, Files.readAllBytes(marker));
    }

    @Test
    void testMarkerWithSameLengthButDifferentBytesIsRewritten(@TempDir Path tempDir) throws IOException {
        Path marker = tempDir.resolve(Consts.FILENAME_WORKING_DIR_MARKER);
        byte[] sameLength = new byte[EXPECTED.length];
        java.util.Arrays.fill(sameLength, (byte) 'x');
        Files.write(marker, sameLength);

        AbstractModelExporter.writeProjVersion(marker);

        Assertions.assertArrayEquals(EXPECTED, Files.readAllBytes(marker));
    }

    @Test
    void testDirectoryInPlaceOfMarkerStillFails(@TempDir Path tempDir) throws IOException {
        Path marker = tempDir.resolve(Consts.FILENAME_WORKING_DIR_MARKER);
        Files.createDirectory(marker);

        Assertions.assertThrows(IOException.class,
                () -> AbstractModelExporter.writeProjVersion(marker),
                "an unwritable marker must still surface an IOException");
    }
}
