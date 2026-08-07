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
package org.pgcodekeeper.core.database.base.loader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

class FingerprintingInputStreamProviderTest {

    @Test
    void disabledCapturePreservesOriginalProviderIdentity()
            throws Exception {
        InputStreamProvider provider =
                () -> new ByteArrayInputStream(new byte[0]);

        try (var loader = new PgDumpLoader(
                provider, "input.sql", new CoreSettings())) {
            Assertions.assertSame(provider, loader.input);
        }
    }

    @Test
    void fingerprintHasValueSemanticsAndDefensiveDigestCopies() {
        Path path = Path.of("value.sql");
        byte[] digest = new byte[32];
        Arrays.fill(digest, (byte) 7);
        var first = new ProjectInputFingerprint(
                path, 10, digest);
        var second = new ProjectInputFingerprint(
                path, 10, digest.clone());

        digest[0] = 1;
        byte[] exposed = first.sha256();
        exposed[1] = 2;

        Assertions.assertEquals(first, second);
        Assertions.assertEquals(first.hashCode(),
                second.hashCode());
        Assertions.assertArrayEquals(
                second.sha256(), first.sha256());
    }

    @Test
    void publishesOnlyAfterExactInputReachesEof() throws Exception {
        byte[] bytes = "raw\r\nданные"
                .getBytes(StandardCharsets.UTF_8);
        var captured =
                new ArrayList<ProjectInputFingerprint>();
        var provider = new FingerprintingInputStreamProvider(
                () -> new ByteArrayInputStream(bytes));
        Path path = Path.of("input.sql").toAbsolutePath();
        provider.capture(path, captured::add);

        try (var input = provider.getStream()) {
            Assertions.assertArrayEquals(bytes,
                    input.readAllBytes());
            Assertions.assertEquals(-1, input.read());
            Assertions.assertEquals(-1, input.read());
        }

        Assertions.assertEquals(1, captured.size());
        var fingerprint = captured.get(0);
        Assertions.assertEquals(path.normalize(),
                fingerprint.path());
        Assertions.assertEquals(bytes.length,
                fingerprint.byteCount());
        Assertions.assertArrayEquals(
                MessageDigest.getInstance("SHA-256")
                        .digest(bytes),
                fingerprint.sha256());
    }

    @Test
    void closingPartialInputDoesNotPublishFingerprint()
            throws Exception {
        byte[] bytes = "partial".getBytes(StandardCharsets.UTF_8);
        var captured =
                new ArrayList<ProjectInputFingerprint>();
        var provider = new FingerprintingInputStreamProvider(
                () -> new ByteArrayInputStream(bytes));
        provider.capture(Path.of("partial.sql"),
                captured::add);

        try (var input = provider.getStream()) {
            Assertions.assertEquals(bytes[0] & 0xff,
                    input.read());
        }

        Assertions.assertTrue(captured.isEmpty());
    }

    @Test
    void failedInputDoesNotPublishFingerprint()
            throws Exception {
        var captured =
                new ArrayList<ProjectInputFingerprint>();
        var provider = new FingerprintingInputStreamProvider(
                () -> new java.io.InputStream() {
                    private boolean first = true;

                    @Override
                    public int read() throws java.io.IOException {
                        if (first) {
                            first = false;
                            return 1;
                        }
                        throw new java.io.IOException("read failed");
                    }
                });
        provider.capture(Path.of("failed.sql"),
                captured::add);

        try (var input = provider.getStream()) {
            Assertions.assertEquals(1, input.read());
            Assertions.assertThrows(java.io.IOException.class,
                    input::read);
        }

        Assertions.assertTrue(captured.isEmpty());
    }
}
