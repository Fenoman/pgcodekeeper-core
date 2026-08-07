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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Streams raw routine text through UTF-8 and SHA-256 without allocating a
 * byte array proportional to the body size.
 */
public final class RoutineBodyFingerprinter {

    private static final int BUFFER_SIZE = 8 * 1024;
    private static final int SHA_256_BYTES = 32;
    private static final ThreadLocal<Workspace> WORKSPACE =
            ThreadLocal.withInitial(Workspace::new);

    private RoutineBodyFingerprinter() {
    }

    public static RoutineBodyMeasure measure(String raw) {
        return WORKSPACE.get().measure(Objects.requireNonNull(raw, "raw"));
    }

    /**
     * Measures the profile-normalized form of raw routine text. With
     * {@code keepNewLines} disabled the comparison canonicalizes bodies by
     * stripping carriage returns, so the exchange fingerprint must be blind
     * to them as well: bodies that differ only in CR characters canonicalize
     * identically and must produce identical fingerprints. With
     * {@code keepNewLines} enabled the measure stays byte-exact.
     *
     * @param raw          raw routine text
     * @param keepNewLines effective keep-newlines canonicalization setting
     * @return measure of the normalized text
     */
    public static RoutineBodyMeasure measure(String raw, boolean keepNewLines) {
        Objects.requireNonNull(raw, "raw");
        return measure(keepNewLines ? raw : raw.replace("\r", ""));
    }

    private static final class Workspace {

        private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        private final MessageDigest digest = newSha256();
        private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        private final byte[] digestBytes = new byte[SHA_256_BYTES];
        private final int replacementLength = encoder.replacement().length;

        private RoutineBodyMeasure measure(String raw) {
            encoder.reset();
            digest.reset();
            buffer.clear();

            CharBuffer input = CharBuffer.wrap(raw);
            long utf8Length = 0;
            boolean reusable = true;

            while (true) {
                CoderResult result = encoder.encode(input, buffer, true);
                utf8Length += drain(reusable);
                if (result.isUnderflow()) {
                    break;
                }
                if (result.isOverflow()) {
                    continue;
                }
                if (!result.isError()) {
                    throw new IllegalStateException("Unexpected UTF-8 encoder result: " + result);
                }

                reusable = false;
                utf8Length += replacementLength;
                input.position(input.position() + result.length());
            }

            while (true) {
                CoderResult result = encoder.flush(buffer);
                utf8Length += drain(reusable);
                if (result.isUnderflow()) {
                    break;
                }
                if (!result.isOverflow()) {
                    throw codingFailure(result);
                }
            }

            if (!reusable) {
                return new RoutineBodyMeasure.Unreusable(
                        utf8Length, RoutineBodyMeasure.Reason.MALFORMED_UTF16);
            }

            try {
                int written = digest.digest(digestBytes, 0, digestBytes.length);
                if (written != SHA_256_BYTES) {
                    throw new IllegalStateException("Unexpected SHA-256 digest size: " + written);
                }
            } catch (DigestException ex) {
                throw new IllegalStateException("Unable to materialize SHA-256 digest", ex);
            }
            return RoutineFingerprint.fromSha256(utf8Length, digestBytes);
        }

        private int drain(boolean reusable) {
            buffer.flip();
            int bytes = buffer.remaining();
            if (reusable) {
                digest.update(buffer);
            } else {
                buffer.position(buffer.limit());
            }
            buffer.clear();
            return bytes;
        }

        private static IllegalStateException codingFailure(CoderResult result) {
            try {
                result.throwException();
                throw new AssertionError("CoderResult did not throw");
            } catch (CharacterCodingException ex) {
                return new IllegalStateException("Unexpected UTF-8 flush failure", ex);
            }
        }

        private static MessageDigest newSha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new ExceptionInInitializerError(ex);
            }
        }
    }
}
