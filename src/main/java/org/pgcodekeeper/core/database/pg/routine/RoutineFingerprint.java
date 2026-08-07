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

import java.util.Objects;

/**
 * Exact UTF-8 length and SHA-256 digest of raw routine parser input.
 * The digest is stored as primitives so no mutable byte array is retained.
 */
public record RoutineFingerprint(long utf8Length, long hash0, long hash1,
                                 long hash2, long hash3) implements RoutineBodyMeasure {

    private static final int SHA_256_BYTES = 32;

    public RoutineFingerprint {
        if (utf8Length < 0) {
            throw new IllegalArgumentException("UTF-8 length must be nonnegative");
        }
    }

    public static RoutineFingerprint fromSha256(long utf8Length, byte[] digest) {
        if (utf8Length < 0) {
            throw new IllegalArgumentException("UTF-8 length must be nonnegative");
        }
        Objects.requireNonNull(digest, "digest");
        if (digest.length != SHA_256_BYTES) {
            throw new IllegalArgumentException("SHA-256 digest must contain exactly 32 bytes");
        }

        return new RoutineFingerprint(utf8Length, readLong(digest, 0),
                readLong(digest, 8), readLong(digest, 16), readLong(digest, 24));
    }

    private static long readLong(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xFF) << 56
                | ((long) bytes[offset + 1] & 0xFF) << 48
                | ((long) bytes[offset + 2] & 0xFF) << 40
                | ((long) bytes[offset + 3] & 0xFF) << 32
                | ((long) bytes[offset + 4] & 0xFF) << 24
                | ((long) bytes[offset + 5] & 0xFF) << 16
                | ((long) bytes[offset + 6] & 0xFF) << 8
                | ((long) bytes[offset + 7] & 0xFF);
    }
}
