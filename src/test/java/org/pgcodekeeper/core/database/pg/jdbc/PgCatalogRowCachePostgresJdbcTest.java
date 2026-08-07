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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.base.jdbc.ICatalogRowCache;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.telemetry.PgCatalogCacheBypassReason;
import org.pgcodekeeper.core.telemetry.PgCatalogCacheMode;
import org.pgcodekeeper.core.telemetry.PgCatalogReaderCacheTelemetry;

/**
 * Read-only checks against explicitly supplied PostgreSQL instances. Run with
 * {@code -Dpgcodekeeper.test.pg.url=jdbc:postgresql://host:port/database}.
 */
class PgCatalogRowCachePostgresJdbcTest {

    private static final long CACHE_BYTES = 64L << 20;

    @TempDir
    private Path cacheDirectory;

    @Test
    void parallelRowCacheLoadExportsSnapshotAfterIdentityProbe()
            throws Exception {
        var connector = new PgJdbcConnector(configuredUrl());
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgCatalogCacheDir(cacheDirectory.toString());
        settings.setPgCatalogCacheRows(true);
        settings.setPgParallelCatalogReaders(3);

        try (var loader = new PgJdbcLoader(connector, "UTC", settings)) {
            assertNotNull(loader.load());
        }
    }

    @Test
    void cacheSqlFailureLeavesTransactionUsableForPlainFallback()
            throws Exception {
        String url = configuredUrl();
        var connector = new PgJdbcConnector(url);
        try (Connection connection = connector.getConnection()) {
            beginReadOnly(connection);
            try {
                var loader = new TestLoader(connector, connection);
                var cache = new PgCatalogRowCache(loader, cacheDirectory,
                        CACHE_BYTES, namespace());
                String query = """
                        SELECT 1 / CASE
                            WHEN pg_catalog.strpos(pg_catalog.current_query(),
                                    '__pgck' || '_r') > 0
                            THEN 0
                            ELSE 1
                        END AS value""";

                assertFalse(cache.read("FailOpenReader", query,
                        result -> { }, null));
                try {
                    try (var statement = connection.prepareStatement(query);
                            var result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(1, result.getInt(1));
                        assertFalse(result.next());
                    }
                } catch (SQLException ex) {
                    assertNotEquals("25P02", ex.getSQLState(),
                            "cache fail-open left the PostgreSQL transaction aborted");
                    throw ex;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void parameterizedWarmChangeFetchesOnlyTheNewRow() throws Exception {
        String url = configuredUrl();
        var connector = new PgJdbcConnector(url);
        try (Connection connection = connector.getConnection()) {
            beginReadOnly(connection);
            try {
                var telemetry = new RecordingTelemetry();

                assertEquals(List.of(1, 2),
                        readSeries(connector, connection, 2, telemetry));
                assertEquals(List.of(1, 2, 3),
                        readSeries(connector, connection, 3, telemetry));
                assertEquals(List.of(1, 2, 3),
                        readSeries(connector, connection, 3, telemetry));

                assertEquals(3, telemetry.events.size());
                PgCatalogReaderCacheTelemetry changed =
                        telemetry.events.get(1);
                PgCatalogReaderCacheTelemetry exact =
                        telemetry.events.get(2);
                assertEquals(PgCatalogCacheMode.WARM_CHANGED, changed.mode());
                assertEquals(PgCatalogCacheBypassReason.NONE,
                        changed.bypassReason());
                assertEquals(2L, changed.hits());
                assertEquals(1L, changed.misses());
                assertEquals(1L, changed.fetchedRows());
                assertEquals(PgCatalogCacheMode.WARM_EXACT, exact.mode());
                assertEquals(PgCatalogCacheBypassReason.NONE,
                        exact.bypassReason());
                assertEquals(3L, exact.hits());
                assertEquals(0L, exact.misses());
                assertEquals(0L, exact.fetchedRows());
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * Proves on a live server that the summary fingerprint of the warm probe
     * decides exactly what the packed hash list decides: it is the MD5 over
     * the concatenated lowercase hex of every per-row digest the same pass
     * returns, and the server suppresses those digests for that fingerprint
     * alone. Covers empty, single-row, chunk-boundary, reordered and full
     * catalog results.
     */
    @Test
    void orderedFingerprintEqualsPackedRowHashes() throws Exception {
        var connector = new PgJdbcConnector(configuredUrl());
        try (Connection connection = connector.getConnection()) {
            beginReadOnly(connection);
            try {
                for (String query : List.of(
                        "SELECT NULL::integer AS id WHERE false",
                        "SELECT 1::integer AS id",
                        """
                        SELECT value AS id, ('v-' || value)::text AS payload
                        FROM pg_catalog.generate_series(1, 4096) value
                        ORDER BY value""",
                        """
                        SELECT value AS id, ('v-' || value)::text AS payload
                        FROM pg_catalog.generate_series(1, 4097) value
                        ORDER BY value""",
                        """
                        SELECT value AS id FROM pg_catalog.generate_series(1, 100) value
                        ORDER BY value DESC""",
                        """
                        SELECT t.oid, t.typname, t.typelem, t.typarray, t.typstorage,
                               t.typcollation::bigint, n.nspname
                        FROM pg_catalog.pg_type t
                        LEFT JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid""",
                        """
                        SELECT c.oid, c.relname, c.relkind, n.nspname
                        FROM pg_catalog.pg_class c
                        JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid""")) {
                    Probe changed = readProbe(connection, query, "0".repeat(32));
                    var fingerprint = MessageDigest.getInstance("MD5");
                    for (byte[] digest : changed.digests()) {
                        fingerprint.update(HexFormat.of().formatHex(digest)
                                .getBytes(StandardCharsets.US_ASCII));
                    }
                    String expected = HexFormat.of().formatHex(
                            fingerprint.digest());
                    assertEquals(expected, changed.fingerprint(),
                            "the summary must be the MD5 over the packed row hashes");
                    assertEquals((long) changed.digests().size(),
                            changed.rows(),
                            "the summary row count must match the hash list");

                    Probe unchanged = readProbe(connection, query, expected);
                    assertEquals(expected, unchanged.fingerprint());
                    assertEquals(changed.rows(), unchanged.rows());
                    assertEquals(List.of(), unchanged.digests(),
                            "an unchanged reader must transfer no row hash");
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * Same warm decisions as {@link #parameterizedWarmChangeFetchesOnlyTheNewRow()}
     * for a multi-chunk pack, where the summary fingerprint carries the whole
     * unchanged answer: a changed result must still receive every hash in the
     * same pass and fetch only the new row.
     */
    @Test
    void fingerprintProbedWarmChangeFetchesOnlyTheNewRow() throws Exception {
        int rows = PgCatalogRowCache.HASHES_PER_CHUNK + 100;
        var connector = new PgJdbcConnector(configuredUrl());
        try (Connection connection = connector.getConnection()) {
            beginReadOnly(connection);
            try {
                var telemetry = new RecordingTelemetry();

                assertEquals(series(rows),
                        readSeries(connector, connection, rows, telemetry));
                assertEquals(series(rows + 1),
                        readSeries(connector, connection, rows + 1, telemetry));
                assertEquals(series(rows + 1),
                        readSeries(connector, connection, rows + 1, telemetry));

                assertEquals(3, telemetry.events.size());
                PgCatalogReaderCacheTelemetry cold = telemetry.events.get(0);
                PgCatalogReaderCacheTelemetry changed = telemetry.events.get(1);
                PgCatalogReaderCacheTelemetry exact = telemetry.events.get(2);
                assertEquals(PgCatalogCacheMode.COLD, cold.mode());
                assertEquals(PgCatalogCacheMode.WARM_CHANGED, changed.mode());
                assertEquals(PgCatalogCacheBypassReason.NONE,
                        changed.bypassReason());
                assertEquals(rows, changed.hits());
                assertEquals(1L, changed.misses());
                assertEquals(1L, changed.fetchedRows());
                assertEquals(PgCatalogCacheMode.WARM_EXACT, exact.mode());
                assertEquals(PgCatalogCacheBypassReason.NONE,
                        exact.bypassReason());
                assertEquals(rows + 1L, exact.hits());
                assertEquals(0L, exact.misses());
                assertEquals(0L, exact.fetchedRows());
                // the exact pass carries one aggregate digest instead of one
                // hash per row
                assertEquals(PgPackedCatalogHashes.MD5_BYTES,
                        exact.hashPayloadBytes());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void quoteAllIdentifiersSeparatesSameSnapshotCatalogBytes()
            throws Exception {
        String url = configuredUrl();
        var connector = new PgJdbcConnector(url);
        try (Connection connection = connector.getConnection()) {
            beginReadOnly(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL quote_all_identifiers = off");
                String plainView = viewDefinition(statement);
                PgCatalogCacheNamespace.ResolvedIdentity plain =
                        identity(statement);

                statement.execute("SET LOCAL quote_all_identifiers = on");
                String quotedView = viewDefinition(statement);
                PgCatalogCacheNamespace.ResolvedIdentity quoted =
                        identity(statement);

                assertNotEquals(plainView, quotedView,
                        "pg_get_viewdef bytes must demonstrate the cache risk");
                assertArrayEquals(plain.snapshotDigest(),
                        quoted.snapshotDigest(),
                        "both identities must come from one database snapshot");
                assertNotEquals(
                        plain.namespace().resolveUnder(cacheDirectory),
                        quoted.namespace().resolveUnder(cacheDirectory),
                        "different catalog bytes must not share WARM_EXACT");
            } finally {
                connection.rollback();
            }
        }
    }

    private static void beginReadOnly(Connection connection) throws Exception {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute(
                    "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
            try (var result = statement.executeQuery(
                    "SHOW transaction_read_only")) {
                assertTrue(result.next());
                assertEquals("on", result.getString(1));
                assertFalse(result.next());
            }
        }
    }

    private List<Integer> readSeries(PgJdbcConnector connector,
            Connection connection, int upperBound,
            IComparisonTelemetry telemetry) throws Exception {
        var loader = new TestLoader(connector, connection);
        var cache = new PgCatalogRowCache(loader, cacheDirectory,
                CACHE_BYTES, namespace(), false, null, telemetry);
        List<Integer> values = new ArrayList<>();
        boolean handled = cache.read("GenerateSeriesReader", """
                SELECT value
                FROM pg_catalog.generate_series(1, ?) AS value
                ORDER BY value
                """, ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                result -> values.add(result.getInt(1)),
                statement -> statement.setInt(1, upperBound));
        assertTrue(handled, "row cache unexpectedly requested plain fallback");
        return values;
    }

    private static List<Integer> series(int upperBound) {
        return IntStream.rangeClosed(1, upperBound).boxed().toList();
    }

    /** One warm probe answer: summary row plus any per-row digests. */
    private record Probe(long rows, String fingerprint, List<byte[]> digests) {
    }

    /** Runs the warm probe with the supplied expected pack fingerprint. */
    private static Probe readProbe(Connection connection, String query,
            String expectedFingerprint) throws Exception {
        List<byte[]> digests = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                PgCatalogRowCache.wrapHashOnly(query))) {
            statement.setString(1, expectedFingerprint);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next(), "the summary row is always present");
                assertEquals(PgCatalogRowCache.SUMMARY_CHUNK_ORDINAL,
                        result.getLong(1));
                long rows = result.getLong(2);
                byte[] fingerprint = result.getBytes(3);
                assertNotNull(fingerprint);
                assertEquals(PgPackedCatalogHashes.MD5_BYTES,
                        fingerprint.length);
                while (result.next()) {
                    byte[] chunk = result.getBytes(3);
                    assertNotNull(chunk);
                    for (int offset = 0; offset < chunk.length;
                            offset += PgPackedCatalogHashes.MD5_BYTES) {
                        byte[] digest = new byte[PgPackedCatalogHashes.MD5_BYTES];
                        System.arraycopy(chunk, offset, digest, 0, digest.length);
                        digests.add(digest);
                    }
                }
                return new Probe(rows, HexFormat.of().formatHex(fingerprint),
                        digests);
            }
        }
    }

    private static String viewDefinition(java.sql.Statement statement)
            throws Exception {
        try (var result = statement.executeQuery(
                "SELECT pg_catalog.pg_get_viewdef("
                + "'pg_catalog.pg_views'::pg_catalog.regclass, true)")) {
            assertTrue(result.next());
            String definition = result.getString(1);
            assertFalse(result.next());
            return definition;
        }
    }

    private static PgCatalogCacheNamespace.ResolvedIdentity identity(
            java.sql.Statement statement) throws Exception {
        try (var result = statement.executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT)) {
            assertTrue(result.next());
            PgCatalogCacheNamespace.ResolvedIdentity identity =
                    PgCatalogCacheNamespace.resolveIdentity(result, true);
            assertFalse(result.next());
            return identity;
        }
    }

    private static String configuredUrl() {
        String url = System.getProperty("pgcodekeeper.test.pg.url", "");
        assumeFalse(url.isBlank(),
                "set -Dpgcodekeeper.test.pg.url for real PostgreSQL tests");
        return url;
    }

    private static PgCatalogCacheNamespace namespace() {
        return PgCatalogCacheNamespace.fromValues(
                PgCatalogCacheNamespace.CACHE_ABI,
                "127.0.0.1", "5432", "postgres", "session_user",
                "current_role", "170000", "UTC", "ISO, MDY", "postgres",
                "1", "hex", "off");
    }

    private static final class TestLoader extends PgJdbcLoader {

        private TestLoader(PgJdbcConnector connector, Connection connection) {
            super(connector, "UTC", new CoreSettings());
            this.connection = connection;
            setVersion(170000);
        }
    }

    private static final class RecordingTelemetry
            implements IComparisonTelemetry {

        private final List<PgCatalogReaderCacheTelemetry> events =
                new ArrayList<>();

        @Override
        public void pgCatalogReaderFinished(
                PgCatalogReaderCacheTelemetry event) {
            events.add(event);
        }
    }
}
