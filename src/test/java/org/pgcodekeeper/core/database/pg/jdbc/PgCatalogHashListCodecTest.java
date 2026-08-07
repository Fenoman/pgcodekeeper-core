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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.monitor.NullMonitor;

class PgCatalogHashListCodecTest {

    private static final String HASH_A = "00112233445566778899aabbccddeeff";
    private static final String HASH_B = "ffeeddccbbaa99887766554433221100";

    @Test
    void decodesTwoHashesInExactPackedOrder() throws Exception {
        byte[] packed = HexFormat.of().parseHex(HASH_B + HASH_A);

        List<String> decoded = PgCatalogHashListCodec.decode(2L, packed, null);

        assertEquals(List.of(HASH_B, HASH_A), decoded);
    }

    @Test
    void decodesEmptyHashList() throws Exception {
        assertEquals(List.of(),
                PgCatalogHashListCodec.decode(0L, new byte[0], new NullMonitor()));
    }

    @Test
    void rejectsNullPackedPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> PgCatalogHashListCodec.decode(0L, null, null));
    }

    @Test
    void rejectsNegativeCount() {
        assertThrows(IllegalArgumentException.class,
                () -> PgCatalogHashListCodec.decode(-1L, new byte[0], null));
    }

    @Test
    void rejectsOverflowingCount() {
        assertThrows(IllegalArgumentException.class,
                () -> PgCatalogHashListCodec.decode(Long.MAX_VALUE, new byte[0], null));
    }

    @Test
    void rejectsCountLengthMismatchIncludingWholeDigestTruncation() {
        byte[] oneDigest = HexFormat.of().parseHex(HASH_A);
        byte[] twoDigests = HexFormat.of().parseHex(HASH_A + HASH_B);

        assertThrows(IllegalArgumentException.class,
                () -> PgCatalogHashListCodec.decode(2L, oneDigest, null));
        assertThrows(IllegalArgumentException.class,
                () -> PgCatalogHashListCodec.decode(1L, twoDigests, null));
    }

    @Test
    void checksCancellationBetweenDigestBlocks() {
        var checks = new AtomicInteger();
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return checks.incrementAndGet() == 2;
            }
        };

        assertThrows(InterruptedException.class, () -> PgCatalogHashListCodec.decode(
                2L, HexFormat.of().parseHex(HASH_A + HASH_B), monitor));
        assertEquals(2, checks.get());
    }
}
