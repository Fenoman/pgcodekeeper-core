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
package org.pgcodekeeper.core.utils;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UtilsTest {

    @Test
    void resolveVersionPrefersImplementationVersionWithoutReadingFallback() {
        AtomicBoolean fallbackRead = new AtomicBoolean();
        Supplier<InputStream> fallback = () -> {
            fallbackRead.set(true);
            return propertiesStream("version=15.0.0-fallback");
        };

        String version = Utils.resolveVersion("15.0.0-implementation", fallback);

        assertAll(
                () -> assertEquals("15.0.0-implementation", version),
                () -> assertFalse(fallbackRead.get(), "fallback must remain unread"));
    }

    @Test
    void resolveVersionReadsMavenVersionWhenImplementationVersionIsMissing() {
        assertEquals("15.0.0-pom",
                Utils.resolveVersion(null, properties("version=15.0.0-pom")));
    }

    @Test
    void resolveVersionReadsMavenVersionWhenImplementationVersionIsBlank() {
        assertEquals("15.0.0-pom",
                Utils.resolveVersion(" \t", properties("version=15.0.0-pom")));
    }

    @Test
    void resolveVersionReturnsUnknownForUnavailableMavenVersion() {
        assertAll(
                () -> assertEquals("unknown", Utils.resolveVersion(null, () -> null), "missing resource"),
                () -> assertEquals("unknown", Utils.resolveVersion(null, properties("version= \t")), "blank version"),
                () -> assertEquals("unknown", Utils.resolveVersion(null, properties("version=\\u00G0")),
                        "malformed properties"),
                () -> assertEquals("unknown", Utils.resolveVersion(null, () -> new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("unreadable");
                    }
                }), "unreadable properties"));
    }

    @Test
    void getFullLocalizedMessageTest() {
        Exception cause = new Exception("Bad cause");
        Exception main = new Exception("Main exception", cause);
        String message = Utils.getFullLocalizedMessage(main);
        Assertions.assertEquals("Main exception -> Bad cause", message);

        String emptyMessage = Utils.getFullLocalizedMessage(null);
        Assertions.assertEquals("", emptyMessage);
    }

    private static Supplier<InputStream> properties(String content) {
        return () -> propertiesStream(content);
    }

    private static InputStream propertiesStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1));
    }
}
