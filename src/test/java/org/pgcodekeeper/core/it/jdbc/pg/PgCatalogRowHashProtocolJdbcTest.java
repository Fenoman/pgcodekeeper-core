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
package org.pgcodekeeper.core.it.jdbc.pg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgcodekeeper.core.database.pg.jdbc.PgCatalogCacheNamespace;
import org.pgcodekeeper.core.database.pg.jdbc.PgCatalogRowCache;
import org.pgcodekeeper.core.database.pg.jdbc.PgJdbcConnector;
import org.pgcodekeeper.core.utils.testcontainer.TestContainerType;

class PgCatalogRowHashProtocolJdbcTest {

    private static final int HASHES_PER_CHUNK = 4096;
    private static final int HASH_CHUNK_BYTES = HASHES_PER_CHUNK * 16;

    private static final String ASCENDING_QUERY = """
            SELECT v.id, v.payload
            FROM (VALUES
                (1::integer, 'alpha'::text),
                (2::integer, 'beta'::text),
                (3::integer, NULL::text)
            ) v(id, payload)
            ORDER BY v.id ASC""";

    private static final String DESCENDING_QUERY = """
            SELECT v.id, v.payload
            FROM (VALUES
                (1::integer, 'alpha'::text),
                (2::integer, 'beta'::text),
                (3::integer, NULL::text)
            ) v(id, payload)
            ORDER BY v.id DESC""";

    private static final String EMPTY_QUERY = """
            SELECT NULL::integer AS id, NULL::text AS payload
            WHERE false""";

    private static final String LARGE_QUERY = """
            SELECT value AS id, ('value-' || value)::text AS payload
            FROM pg_catalog.generate_series(1, 4097) value
            ORDER BY value""";

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = TestContainerType.class, names = {"PG_16", "PG_18"})
    void packedHashesMatchIndividualHashesInExactOrder(TestContainerType type)
            throws Exception {
        var connector = new PgJdbcConnector(type.getUrl());
        try (Connection connection = connector.getConnection()) {
            List<String> ascending = assertPackedMatchesOracle(connection, ASCENDING_QUERY);
            List<String> descending = assertPackedMatchesOracle(connection, DESCENDING_QUERY);

            var reversed = new ArrayList<>(ascending);
            Collections.reverse(reversed);
            assertEquals(reversed, descending);
            assertEquals(4097,
                    assertPackedMatchesOracle(connection, LARGE_QUERY).size());

            PackedHashes empty = readPacked(connection, EMPTY_QUERY);
            assertEquals(0L, empty.count());
            assertNotNull(empty.payload());
            assertArrayEquals(new byte[0], empty.payload());
            assertEquals(List.of(), unpack(empty));
        }
    }

    @ParameterizedTest(name = "guarded identity {0}")
    @EnumSource(value = TestContainerType.class, names = {"PG_16", "PG_18"})
    void catalogIdentityFallsBackAfterOptionalFunctionsAreDenied(
            TestContainerType type) throws Exception {
        var connector = new PgJdbcConnector(type.getUrl());
        try (Connection connection = connector.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE pgck_no_control_system NOLOGIN");
                statement.execute("REVOKE EXECUTE ON FUNCTION "
                        + "pg_catalog.pg_control_system() FROM PUBLIC");
                statement.execute("REVOKE EXECUTE ON FUNCTION "
                        + "pg_catalog.txid_current_snapshot() FROM PUBLIC");
                statement.execute("SET LOCAL ROLE pgck_no_control_system");
                statement.execute("SAVEPOINT pgck_catalog_snapshot_probe");
                assertThrows(SQLException.class, () -> {
                    try (var ignored = statement.executeQuery(
                            PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT)) {
                        // the direct optional-function probe must fail
                    }
                });
                statement.execute(
                        "ROLLBACK TO SAVEPOINT pgck_catalog_snapshot_probe");
                try (var result = statement.executeQuery(
                        PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK)) {
                    assertTrue(result.next());
                    assertNull(result.getString("system_identifier"));
                    assertNull(result.getString("snapshot_token"));
                    assertFalse(result.next());
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static List<String> assertPackedMatchesOracle(
            Connection connection, String query) throws Exception {
        List<String> oracle = readOracle(connection, query);
        PackedHashes packed = readPacked(connection, query);

        assertEquals(oracle.size(), packed.count());
        assertNotNull(packed.payload());
        assertEquals(Math.multiplyExact(packed.count(), 16L), packed.payload().length);
        assertEquals(oracle, unpack(packed));
        return oracle;
    }

    private static List<String> readOracle(Connection connection, String query)
            throws Exception {
        String sql = "SELECT pg_catalog.md5(__pgck_r::text) AS __pgck_h\n"
                + "FROM (\n" + query + "\n) __pgck_r";
        var hashes = new ArrayList<String>();
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            while (result.next()) {
                hashes.add(result.getString(1));
            }
        }
        return hashes;
    }

    private static PackedHashes readPacked(Connection connection, String query)
            throws Exception {
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement(wrapHashOnly(query))) {
            statement.setFetchSize(1);
            // no cached pack can carry this fingerprint, so the server must
            // return every packed hash chunk after its summary row
            statement.setString(1, "0".repeat(32));
            try (var result = statement.executeQuery()) {
                assertEquals(4, result.getMetaData().getColumnCount());
                assertEquals("bytea", result.getMetaData().getColumnTypeName(3));
                assertTrue(result.next(), "the summary row is always present");
                assertEquals(-1L, result.getLong(1));
                assertEquals(16, result.getBytes(3).length,
                        "the summary row carries one ordered fingerprint");
                long summaryTotal = result.getLong(2);
                assertEquals(summaryTotal, result.getLong(4));
                long total = -1;
                long expectedOrdinal = 0;
                long copiedRows = 0;
                var payload = new ByteArrayOutputStream();
                while (result.next()) {
                    long ordinal = result.getLong(1);
                    assertFalse(result.wasNull());
                    assertEquals(expectedOrdinal++, ordinal);
                    long chunkRows = result.getLong(2);
                    assertFalse(result.wasNull());
                    assertTrue(chunkRows > 0 && chunkRows <= HASHES_PER_CHUNK);
                    byte[] chunk = result.getBytes(3);
                    assertNotNull(chunk);
                    assertEquals(chunkRows * 16L, chunk.length);
                    assertTrue(chunk.length <= HASH_CHUNK_BYTES);
                    long currentTotal = result.getLong(4);
                    assertFalse(result.wasNull());
                    if (total < 0) {
                        total = currentTotal;
                    } else {
                        assertEquals(total, currentTotal);
                    }
                    payload.write(chunk);
                    copiedRows += chunkRows;
                }
                if (total < 0) {
                    total = 0;
                }
                assertEquals(total, copiedRows);
                assertEquals(summaryTotal, total,
                        "the summary row must carry the total row count");
                return new PackedHashes(total, payload.toByteArray());
            }
        }
    }

    private static List<String> unpack(PackedHashes packed) {
        var hashes = new ArrayList<String>(Math.toIntExact(packed.count()));
        HexFormat hex = HexFormat.of();
        for (int offset = 0; offset < packed.payload().length; offset += 16) {
            hashes.add(hex.formatHex(packed.payload(), offset, offset + 16));
        }
        return hashes;
    }

    private static String wrapHashOnly(String query) throws Exception {
        Method method = PgCatalogRowCache.class.getDeclaredMethod("wrapHashOnly", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, query);
    }

    private record PackedHashes(long count, byte[] payload) {
    }
}
