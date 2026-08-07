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

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.pgcodekeeper.core.monitor.IMonitor;

/** Decodes the packed ordered MD5 list returned by a warm catalog query. */
final class PgCatalogHashListCodec {

    private static final int MD5_BYTES = 16;

    private PgCatalogHashListCodec() {
        // only statics
    }

    static List<String> decode(long count, byte[] packed, IMonitor monitor)
            throws InterruptedException {
        if (count < 0) {
            throw new IllegalArgumentException("Catalog hash count must not be negative");
        }
        if (packed == null) {
            throw new IllegalArgumentException("Packed catalog hashes must not be null");
        }

        long expectedLength;
        try {
            expectedLength = Math.multiplyExact(count, MD5_BYTES);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Catalog hash count overflows its payload", ex);
        }
        if (expectedLength != packed.length) {
            throw new IllegalArgumentException(
                    "Packed catalog hash length does not match its count");
        }

        int digestCount = Math.toIntExact(count);
        var hashes = new ArrayList<String>(digestCount);
        HexFormat hex = HexFormat.of();
        for (int offset = 0; offset < packed.length; offset += MD5_BYTES) {
            IMonitor.checkCancelled(monitor);
            hashes.add(hex.formatHex(packed, offset, offset + MD5_BYTES));
        }
        return hashes;
    }
}
