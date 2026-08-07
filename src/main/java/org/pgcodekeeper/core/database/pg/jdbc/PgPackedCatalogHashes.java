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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import org.pgcodekeeper.core.monitor.IMonitor;

/** Ordered catalog row MD5 values stored without per-row objects. */
final class PgPackedCatalogHashes {

    static final int MD5_BYTES = 16;

    private static final byte[] LOWER_HEX =
            "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final byte[] packed;
    private final int size;
    private final byte[] orderedFingerprint;

    private PgPackedCatalogHashes(byte[] packed, int size,
            byte[] orderedFingerprint) {
        this.packed = packed;
        this.size = size;
        this.orderedFingerprint = orderedFingerprint;
    }

    static PgPackedCatalogHashes takeOwnership(long count, byte[] packed,
            IMonitor monitor) throws InterruptedException {
        if (count < 0) {
            throw new IllegalArgumentException(
                    "Catalog hash count must not be negative");
        }
        if (packed == null) {
            throw new IllegalArgumentException(
                    "Packed catalog hashes must not be null");
        }

        long expectedLength;
        try {
            expectedLength = Math.multiplyExact(count, MD5_BYTES);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "Catalog hash count overflows its payload", ex);
        }
        if (expectedLength != packed.length) {
            throw new IllegalArgumentException(
                    "Packed catalog hash length does not match its count");
        }

        int digestCount;
        try {
            digestCount = Math.toIntExact(count);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "Catalog hash count exceeds the supported range", ex);
        }

        MessageDigest fingerprint = newMd5();
        byte[] hexDigest = new byte[MD5_BYTES * 2];
        for (int offset = 0; offset < packed.length; offset += MD5_BYTES) {
            IMonitor.checkCancelled(monitor);
            encodeLowerHex(packed, offset, hexDigest);
            fingerprint.update(hexDigest);
        }
        return new PgPackedCatalogHashes(packed, digestCount,
                fingerprint.digest());
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    boolean contentEquals(PgPackedCatalogHashes other) {
        return other != null && Arrays.equals(packed, other.packed);
    }

    boolean equalsDigestAt(int index, byte[] digest, int digestOffset) {
        Objects.requireNonNull(digest, "digest");
        int packedOffset = offsetOf(index);
        Objects.checkFromIndexSize(digestOffset, MD5_BYTES, digest.length);
        return Arrays.equals(packed, packedOffset, packedOffset + MD5_BYTES,
                digest, digestOffset, digestOffset + MD5_BYTES);
    }

    String hexAt(int index) {
        int offset = offsetOf(index);
        return HexFormat.of().formatHex(packed, offset, offset + MD5_BYTES);
    }

    byte[] orderedFingerprint() {
        return orderedFingerprint.clone();
    }

    byte[] rawBytesForCache() {
        return packed;
    }

    int offsetOf(int index) {
        Objects.checkIndex(index, size);
        return index * MD5_BYTES;
    }

    private static void encodeLowerHex(byte[] bytes, int offset,
            byte[] target) {
        int end = offset + MD5_BYTES;
        for (int i = offset, targetOffset = 0; i < end; i++) {
            int value = Byte.toUnsignedInt(bytes[i]);
            target[targetOffset++] = LOWER_HEX[value >>> 4];
            target[targetOffset++] = LOWER_HEX[value & 0x0F];
        }
    }

    private static MessageDigest newMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 digest is not available", ex);
        }
    }
}
