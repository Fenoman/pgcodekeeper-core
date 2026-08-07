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
package org.pgcodekeeper.core.database.api.loader;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * SHA-256 of the exact raw bytes consumed for one project SQL input.
 */
public record ProjectInputFingerprint(
        Path path,
        long byteCount,
        byte[] sha256) {

    public ProjectInputFingerprint {
        path = Objects.requireNonNull(path, "path")
                .toAbsolutePath().normalize();
        if (byteCount < 0) {
            throw new IllegalArgumentException(
                    "Input byte count must not be negative");
        }
        sha256 = Objects.requireNonNull(sha256, "sha256").clone();
        if (sha256.length != 32) {
            throw new IllegalArgumentException(
                    "Input fingerprint must contain one SHA-256 digest");
        }
    }

    @Override
    public byte[] sha256() {
        return sha256.clone();
    }

    @Override
    public boolean equals(Object object) {
        return this == object
                || object instanceof ProjectInputFingerprint other
                && byteCount == other.byteCount
                && path.equals(other.path)
                && Arrays.equals(sha256, other.sha256);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(path, byteCount);
        return 31 * result + Arrays.hashCode(sha256);
    }
}
