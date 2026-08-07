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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.base.jdbc.ICatalogRowCache;
import org.pgcodekeeper.core.database.base.loader.JdbcCatalogLane;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.telemetry.PgCatalogCacheRunTelemetry;
import org.pgcodekeeper.core.telemetry.PgCatalogReaderCacheTelemetry;
import org.pgcodekeeper.core.utils.LogCapture;
import org.postgresql.util.PGobject;

/**
 * Reader-level integration tests of the row-level catalog cache: the same
 * fixture rows are consumed with the cache off, on a cold store and on a warm
 * store, and the loaded models must be equal in all modes. Also covers the
 * fail-open guards: duplicate hashes, excessive miss ratio, corrupt row files
 * and pre-feed SQL failures.
 */
@Isolated("asserts on global catalog row cache counters")
class PgCatalogRowCacheReaderTest {

    private static final long UNLIMITED_CACHE = 512L << 20;
    private static final String HASH_A = "aa".repeat(16);
    private static final String HASH_B = "bb".repeat(16);
    private static final String HASH_C = "cc".repeat(16);
    private static final String HASH_D = "dd".repeat(16);

    /** Leading text of the warm hash probe statement. */
    private static final String HASH_ONLY_PREFIX = "WITH __pgck_hashes AS (";

    private static final String[] VIEW_LABELS = {
            "relnamespace", "relname", "kind", "table_space", "access_method",
            "relispopulated", "definition", "column_names", "column_comments",
            "column_defaults", "column_types", "reloptions", "description"
    };

    private static final String[] CAST_LABELS = {
            "source", "target", "castcontext", "castmethod", "func", "description"
    };

    private static final String[] CONSTRAINT_LABELS = {
            "relnamespace", "relname", "conname", "contype", "conparentid",
            "isclustered", "reloptions", "definition", "spcname", "col_name", "description"
    };

    @TempDir
    private Path cacheDir;

    @BeforeEach
    void resetCounters() {
        PgCatalogRowCache.resetCounters();
    }

    private static List<Object[]> viewRows() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {10L, "v1", "v", null, null, Boolean.FALSE, "SELECT 1;",
                new String[] {"c1", "c2"}, new String[] {"col comment", null},
                new String[] {null, null}, new String[] {"integer", "text"}, null, "view one"});
        rows.add(new Object[] {10L, "mv1", "m", "ts1", "heap", Boolean.TRUE, "SELECT 2;",
                null, null, null, null, new String[] {"fillfactor=70"}, null});
        return rows;
    }

    private static List<Object[]> castRows() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {"int4", "bool", "e", "f", "myschema.fn(integer)", "cast one"});
        rows.add(new Object[] {"text", "varchar", "i", "b", null, null});
        return rows;
    }

    private static List<Object[]> constraintRows() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {10L, "t1", "t1_pk", "p", 0, Boolean.TRUE, null,
                "PRIMARY KEY (a)", null, null, "primary key"});
        rows.add(new Object[] {10L, "t1", "t1_check", "c", 0, Boolean.FALSE, null,
                "CHECK ((a > 0))", null, null, null});
        return rows;
    }

    @Test
    void viewsReaderModelIsEqualOffColdAndWarm() throws Exception {
        var fixture = new Fixture(VIEW_LABELS, viewRows(), List.of(HASH_A, HASH_B));

        PgDatabase off = fixture.runViews(Mode.OFF);
        assertEquals(1, fixture.server.plainRuns.get());

        PgDatabase cold = fixture.runViews(Mode.CACHED);
        assertEquals(1, fixture.server.hashedRuns.get());
        assertEquals(0, fixture.server.hashOnlyRuns.get());
        assertEquals(0L, PgCatalogRowCache.getHitCount());
        assertEquals(2L, PgCatalogRowCache.getMissCount());
        assertEquals(2L, PgCatalogRowCache.getStoreCount());

        PgDatabase warm = fixture.runViews(Mode.CACHED);
        assertEquals(1, fixture.server.hashedRuns.get());
        assertEquals(1, fixture.server.hashOnlyRuns.get());
        assertEquals(0, fixture.server.missRuns.get());
        assertEquals(2L, PgCatalogRowCache.getHitCount());

        assertEquals(off, cold);
        assertEquals(off, warm);
        assertTrue(off.getSchema("app").getRelation("v1") != null);
        assertTrue(off.getSchema("app").getRelation("mv1") != null);
    }

    @Test
    void coldRunPublishesOnePackedGenerationAndRetiresLegacyRows()
            throws Exception {
        Path legacyRows = cacheDir.resolve("rows").resolve("aa");
        Path legacyManifests = cacheDir.resolve("row-manifests").resolve("bb");
        Files.createDirectories(legacyRows);
        Files.createDirectories(legacyManifests);
        Files.write(legacyRows.resolve("legacy.bin"), new byte[] { 1 });
        Files.write(legacyManifests.resolve("legacy.bin"), new byte[] { 2 });
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));

        fixture.runCasts(Mode.CACHED);

        assertFalse(Files.exists(cacheDir.resolve("rows")));
        assertFalse(Files.exists(cacheDir.resolve("row-manifests")));
        try (Stream<Path> files = Files.walk(cacheDir.resolve("reader-packs"))) {
            assertEquals(2L, files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .count(), "one manifest plus one immutable pack");
        }
    }

    @Test
    void sameSqlUnderDifferentReaderNamesDoesNotShareRows() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.OFF);
        PgCatalogCacheNamespace namespace = namespace("same_target");
        Path targetDirectory = namespace.resolveUnder(cacheDir);

        assertTrue(fixture.readThroughCache(targetDirectory, namespace,
                "FirstReader", result -> { }));
        assertTrue(fixture.readThroughCache(targetDirectory, namespace,
                "SecondReader", result -> { }));
        assertTrue(fixture.readThroughCache(targetDirectory, namespace,
                "FirstReader", result -> { }));

        assertAll(
                () -> assertEquals(2, fixture.server.hashedRuns.get(),
                        "each reader identity needs its own cold publication"),
                () -> assertEquals(1, fixture.server.hashOnlyRuns.get(),
                        "only the repeated first reader may be warm"));
    }

    @Test
    void differentTargetsNeverShareRowEntries() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.OFF);
        PgCatalogCacheNamespace targetA = namespace("target_a");
        PgCatalogCacheNamespace targetB = namespace("target_b");

        assertTrue(fixture.readThroughCache(targetA.resolveUnder(cacheDir), targetA,
                "SharedReader", result -> { }));
        assertTrue(fixture.readThroughCache(targetB.resolveUnder(cacheDir), targetB,
                "SharedReader", result -> { }));
        assertTrue(fixture.readThroughCache(targetA.resolveUnder(cacheDir), targetA,
                "SharedReader", result -> { }));

        assertAll(
                () -> assertEquals(2, fixture.server.hashedRuns.get(),
                        "target B must be cold after target A"),
                () -> assertEquals(1, fixture.server.hashOnlyRuns.get(),
                        "returning to target A must reuse only target A"));
    }

    @Test
    void warmHashQueryReturnsBoundedCountedOrderedByteaChunks() {
        String original = "SELECT id, payload FROM app.rows ORDER BY id DESC";

        String packed = PgCatalogRowCache.wrapHashOnly(original);

        assertAll(
                () -> assertTrue(packed.startsWith(
                        "WITH __pgck_hashes AS (\n"
                        + "SELECT pg_catalog.row_number() OVER () AS __pgck_o"), packed),
                () -> assertTrue(packed.contains(
                        "pg_catalog.md5(__pgck_r::text) AS __pgck_h"), packed),
                // the summary row carries the ordered fingerprint of the whole
                // result, so an unchanged reader transfers no per-row hash
                () -> assertTrue(packed.contains(
                        "pg_catalog.md5(COALESCE(pg_catalog.string_agg(__pgck_h,"
                        + " ''::text ORDER BY __pgck_o), ''::text)) AS __pgck_f"),
                        packed),
                () -> assertTrue(packed.contains(
                        "SELECT -1::bigint AS __pgck_c"), packed),
                () -> assertTrue(packed.contains(
                        "pg_catalog.decode(__pgck_f, 'hex') AS __pgck_h"), packed),
                () -> assertTrue(packed.contains("UNION ALL"), packed),
                () -> assertTrue(packed.contains("((__pgck_o - 1) / 4096)::bigint"),
                        packed),
                () -> assertTrue(packed.contains(
                        "pg_catalog.string_agg(pg_catalog.decode(__pgck_h, 'hex'),"
                        + " ''::bytea ORDER BY __pgck_o)"), packed),
                () -> assertTrue(packed.contains(
                        "WHERE (SELECT __pgck_f FROM __pgck_fp) IS DISTINCT FROM ?::text"),
                        packed),
                () -> assertTrue(packed.contains(
                        "FROM (\n" + original + "\n) __pgck_input"),
                        packed),
                () -> assertTrue(packed.contains("LIMIT "
                        + ((long) PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS + 1)), packed),
                () -> assertTrue(packed.contains(
                        "GROUP BY ((__pgck_o - 1) / 4096)::bigint"), packed),
                () -> assertTrue(packed.endsWith("ORDER BY __pgck_c"), packed),
                () -> assertEquals(packed.indexOf(original), packed.lastIndexOf(original),
                        "the expensive source query must appear exactly once"),
                () -> assertEquals(1, PgCatalogRowCache.countJdbcParameters(packed),
                        "only the expected fingerprint is bound"));
    }

    @Test
    void emptyWarmResultUsesOneEmptyPackedRow() throws Exception {
        var fixture = new Fixture(CAST_LABELS, List.of(), List.of());

        PgDatabase off = fixture.runCasts(Mode.OFF);
        PgDatabase cold = fixture.runCasts(Mode.CACHED);
        PgDatabase warm = fixture.runCasts(Mode.CACHED);

        assertAll(
                () -> assertEquals(off, cold),
                () -> assertEquals(off, warm),
                () -> assertEquals(1, fixture.server.hashOnlyRuns.get()),
                () -> assertEquals(0L, fixture.server.lastPackedCount),
                () -> assertArrayEquals(new byte[0], fixture.server.lastPackedPayload));
    }

    /**
     * The rollback boundary a failing derived statement returns to belongs to
     * the connection, not to the reader. Opening one per reader costs two
     * extra round trips per reader, which on a high-latency link is the
     * largest fixed cost of a warm comparison.
     */
    @Test
    void everyReaderOfOneConnectionSharesOneProbeBoundary() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        Connection connection = fixture.server.connection("primary");
        var loader = new TestLoader(connection, new CoreSettings());
        loader.enableRowCache(cacheDir);

        for (int read = 0; read < 3; read++) {
            assertTrue(loader.rowCache.read(
                    PgCastsReader.class.getSimpleName(), fixture.lastQuery,
                    result -> { }, null));
        }

        assertAll(
                () -> verify(connection, times(1)).setSavepoint(),
                () -> verify(connection, never()).releaseSavepoint(any()),
                () -> verify(connection, never()).rollback(any(Savepoint.class)));
    }

    /**
     * A derived statement that fails before any row was consumed still has to
     * return the connection to a usable state, and the shared boundary must
     * survive that rollback so the next reader can reuse it.
     */
    @Test
    void derivedFailureRollsBackToTheSharedBoundaryAndKeepsIt()
            throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        Connection connection = fixture.server.connection("primary");
        var loader = new TestLoader(connection, new CoreSettings());
        loader.enableRowCache(cacheDir);
        fixture.server.failHashOnlyPass = true;

        assertFalse(loader.rowCache.read(PgCastsReader.class.getSimpleName(),
                fixture.lastQuery, result -> { }, null));
        fixture.server.failHashOnlyPass = false;
        assertTrue(loader.rowCache.read(PgCastsReader.class.getSimpleName(),
                fixture.lastQuery, result -> { }, null));

        assertAll(
                () -> verify(connection, times(1)).setSavepoint(),
                () -> verify(connection, times(1)).rollback(any(Savepoint.class)),
                () -> verify(connection, never()).releaseSavepoint(any()));
    }

    /**
     * The warm probe wraps the reader query in its own statement, so the
     * server may return the same rows in another order. That order is not the
     * one a plain read produces, so replaying it would make a warm comparison
     * build its model differently from the cold comparison that wrote the
     * pack. A reordered but otherwise unchanged reader must therefore replay
     * the packed order and fetch nothing.
     */
    @Test
    void reorderedWarmProbeReplaysThePackedOrderWithoutFetching()
            throws Exception {
        List<Object[]> original = castRows();
        var fixture = new Fixture(CAST_LABELS, original, List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        fixture.replaceRows(List.of(original.get(1), original.get(0)),
                List.of(HASH_B, HASH_A));
        List<String> observed = new ArrayList<>();

        boolean cached = fixture.readThroughCache(
                result -> observed.add(result.getString("source")));

        assertAll(
                () -> assertTrue(cached),
                () -> assertEquals(List.of("int4", "text"), observed),
                () -> assertEquals(0, fixture.server.missRuns.get(),
                        "a permuted probe must not fetch any row"),
                () -> assertEquals(1, fixture.server.hashedRuns.get(),
                        "the cold pass must not run again"));
    }

    /**
     * The permutation guard must not swallow a real change: one edited row
     * still replays the probe order and fetches exactly the changed row.
     */
    @Test
    void changedWarmProbeReplaysTheServerOrderAndFetchesTheChangedRow()
            throws Exception {
        List<Object[]> original = castRows();
        var fixture = new Fixture(CAST_LABELS, original, List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        List<Object[]> changed = new ArrayList<>();
        changed.add(new Object[] {"json", "jsonb", "e", "f", null, null});
        changed.add(original.get(0));
        fixture.replaceRows(changed, List.of(HASH_C, HASH_A));
        List<String> observed = new ArrayList<>();

        boolean cached = fixture.readThroughCache(
                result -> observed.add(result.getString("source")));

        assertAll(
                () -> assertTrue(cached),
                () -> assertEquals(List.of("json", "int4"), observed),
                () -> assertEquals(1, fixture.server.missRuns.get(),
                        "the changed row must be fetched"),
                () -> assertEquals(List.of(HASH_C), fixture.server.lastMissRequest));
    }

    @Test
    void trustworthySnapshotAndExplicitOrderReplayWithoutAnotherRtt()
            throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        byte[] snapshot = new byte[32];
        snapshot[0] = 42;
        String query = "SELECT * FROM scripted ORDER BY id";
        List<String> observed = new ArrayList<>();

        assertTrue(fixture.readThroughFingerprint(query,
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                snapshot, result -> { }));
        assertTrue(fixture.readThroughFingerprint(query,
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                snapshot, result -> observed.add(result.getString("source"))));

        assertAll(
                () -> assertEquals(List.of("int4", "text"), observed),
                () -> assertEquals(1, fixture.server.hashedRuns.get()),
                () -> assertEquals(0, fixture.server.hashOnlyRuns.get()),
                () -> assertEquals(0, fixture.server.missRuns.get()),
                () -> verify(fixture.server.lastPrimaryConnection, never())
                        .setSavepoint());
    }

    @Test
    void packedHashPassUsesBoundedCellsAndCursorFetchSize()
            throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));

        fixture.runCasts(Mode.CACHED);
        fixture.runCasts(Mode.CACHED);

        assertAll(
                // the unchanged reader reads one summary fingerprint cell and
                // no packed chunk at all
                () -> assertEquals(1,
                        fixture.server.packedGetBytesReads.get()),
                () -> assertEquals(0,
                        fixture.server.packedBinaryStreamReads.get()),
                () -> assertEquals(2,
                        fixture.server.packedFetchSize.get(),
                        "the probe keeps a bounded cursor fetch"),
                () -> assertTrue(fixture.server.maxPackedPayloadBytes
                        <= PgCatalogRowCache.HASH_CHUNK_BYTES));
    }

    @Test
    void oversizedPackedHashCountIsRejectedBeforePayloadAccess()
            throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        PgDatabase cold = fixture.runCasts(Mode.CACHED);
        fixture.server.overridePackedHashes(Long.MAX_VALUE, new byte[0]);

        PgDatabase fallback = fixture.runCasts(Mode.CACHED);

        assertAll(
                () -> assertEquals(cold, fallback),
                // only the 16-byte summary fingerprint is read; the oversized
                // chunk payload cell is never touched
                () -> assertEquals(1,
                        fixture.server.packedGetBytesReads.get()),
                () -> assertEquals(0,
                        fixture.server.packedBinaryStreamReads.get()),
                () -> assertEquals(2, fixture.server.hashedRuns.get()));
    }

    @Test
    void snapshotReplayRequiresExplicitOrderAndStableParameterIdentity()
            throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        byte[] snapshot = new byte[32];
        snapshot[0] = 7;

        assertTrue(fixture.readThroughFingerprint(
                "SELECT * FROM scripted WHERE simplify = false ORDER BY id",
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                snapshot, result -> { }));
        assertTrue(fixture.readThroughFingerprint(
                "SELECT * FROM scripted WHERE simplify = true ORDER BY id",
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                snapshot, result -> { }));
        assertTrue(fixture.readThroughFingerprint(
                "SELECT * FROM unordered",
                ICatalogRowCache.CatalogQueryOrder.UNSPECIFIED,
                snapshot, result -> { }));
        assertTrue(fixture.readThroughFingerprint(
                "SELECT * FROM unordered",
                ICatalogRowCache.CatalogQueryOrder.UNSPECIFIED,
                snapshot, result -> { }));

        assertAll(
                () -> assertEquals(3, fixture.server.hashedRuns.get(),
                        "changed literals and first unordered run are cold"),
                () -> assertEquals(1, fixture.server.hashOnlyRuns.get(),
                        "unordered warm run must verify packed hashes"));
    }

    @Test
    void sameSqlAndSnapshotWithTwoBoundValuesNeverDirectReplays()
            throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        byte[] snapshot = new byte[32];
        snapshot[0] = 11;
        String query = "SELECT * FROM scripted WHERE left_id = ? "
                + "AND right_id = ? ORDER BY id";
        var binds = new AtomicInteger();
        ICatalogRowCache.CatalogQueryParameterSetter setter = statement -> {
            int value = binds.incrementAndGet();
            statement.setInt(1, value);
            statement.setInt(2, value + 100);
        };

        assertTrue(fixture.readThroughFingerprint(query,
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                snapshot, result -> { }, setter));
        List<Object[]> reversed = new ArrayList<>(castRows());
        java.util.Collections.reverse(reversed);
        fixture.replaceRows(reversed, List.of(HASH_B, HASH_A));
        List<String> observed = new ArrayList<>();
        assertTrue(fixture.readThroughFingerprint(query,
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                snapshot,
                result -> observed.add(result.getString("source")), setter));

        assertAll(
                // the reversed probe result is a permutation of the pack, so
                // the warm read replays the packed order and rebinds anyway
                () -> assertEquals(List.of("int4", "text"), observed),
                () -> assertEquals(2, binds.get()),
                () -> assertEquals(1, fixture.server.hashOnlyRuns.get()));
    }

    @Test
    void setterAndPlaceholderEachIndependentlyDisableDirectReplay()
            throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        byte[] snapshot = new byte[32];
        snapshot[0] = 12;

        assertTrue(fixture.readThroughFingerprint(
                "SELECT * FROM scripted ORDER BY id",
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                snapshot, result -> { }, statement -> { }));
        assertTrue(fixture.readThroughFingerprint(
                "SELECT * FROM scripted ORDER BY id",
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                snapshot, result -> { }, statement -> { }));
        assertTrue(fixture.readThroughFingerprint(
                "SELECT * FROM scripted WHERE id = ? ORDER BY id",
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                snapshot, result -> { }, null));
        assertTrue(fixture.readThroughFingerprint(
                "SELECT * FROM scripted WHERE id = ? ORDER BY id",
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                snapshot, result -> { }, null));

        assertEquals(2, fixture.server.hashOnlyRuns.get());
    }

    @Test
    void equalHashPassRefreshesSnapshotForTheNextDirectReplay()
            throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        String query = "SELECT * FROM scripted ORDER BY id";
        byte[] firstSnapshot = new byte[32];
        firstSnapshot[0] = 31;
        byte[] secondSnapshot = new byte[32];
        secondSnapshot[0] = 32;

        assertTrue(fixture.readThroughFingerprint(query,
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                firstSnapshot, result -> { }));
        assertTrue(fixture.readThroughFingerprint(query,
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                secondSnapshot, result -> { }));
        assertTrue(fixture.readThroughFingerprint(query,
                ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY,
                secondSnapshot, result -> { }));

        assertAll(
                () -> assertEquals(1, fixture.server.hashedRuns.get()),
                () -> assertEquals(1, fixture.server.hashOnlyRuns.get()),
                () -> assertEquals(0, fixture.server.missRuns.get()));
    }

    @Test
    void missRequestUsesOnePackedByteaAfterOriginalParameters() {
        String query = "SELECT * FROM app.rows WHERE a = ? AND b = ? ORDER BY id";
        String wrapped = PgCatalogRowCache.wrapMissFetch(query);

        assertAll(
                () -> assertEquals(3,
                        PgCatalogRowCache.countJdbcParameters(wrapped)),
                () -> assertTrue(wrapped.contains("SELECT ?::bytea AS packed")),
                () -> assertTrue(wrapped.contains(
                        "pg_catalog.substr(payload.packed, ordinal * 16 + 1, 16)")),
                () -> assertFalse(wrapped.contains(
                        "pg_catalog.substring(payload.packed FROM")),
                () -> assertFalse(wrapped.contains("unnest(?::text[])")),
                () -> assertFalse(wrapped.contains("ANY(?)")));
    }

    @Test
    void wholeDigestTruncationFailsOpenBeforeConsumerMutation() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        fixture.server.overridePackedHashes(2L, HexFormat.of().parseHex(HASH_A));
        var consumed = new AtomicInteger();

        boolean cached = fixture.readThroughCache(result -> consumed.incrementAndGet());

        assertFalse(cached);
        assertEquals(0, consumed.get());
    }

    @Test
    void nullPackedPayloadFailsOpenBeforeConsumerMutation() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        fixture.server.overridePackedHashes(2L, null);
        var consumed = new AtomicInteger();

        boolean cached = fixture.readThroughCache(result -> consumed.incrementAndGet());

        assertFalse(cached);
        assertEquals(0, consumed.get());
    }

    @Test
    void malformedPackedChunkSequencesFailBeforeConsumerMutation() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        byte[] oneHash = HexFormat.of().parseHex(HASH_A);
        byte[] twoHashes = HexFormat.of().parseHex(HASH_A + HASH_B);
        List<List<HashChunk>> malformed = List.of(
                List.of(new HashChunk(1, 2, twoHashes, 2)),
                List.of(new HashChunk(0, 1, oneHash, 2)),
                List.of(new HashChunk(0, 2, oneHash, 2)),
                List.of(new HashChunk(0, 2, twoHashes, 2),
                        new HashChunk(1, 1, oneHash, 2)),
                List.of(new HashChunk(0, 1, oneHash, 2),
                        new HashChunk(0, 1, oneHash, 2)));

        for (List<HashChunk> chunks : malformed) {
            fixture.server.overridePackedChunks(chunks);
            var consumed = new AtomicInteger();

            boolean cached = fixture.readThroughCache(
                    result -> consumed.incrementAndGet());

            assertFalse(cached, "malformed chunks must fail open: " + chunks);
            assertEquals(0, consumed.get());
        }
    }

    @Test
    void warmRunFetchesOnlyChangedRowsInServerOrder() throws Exception {
        var fixture = new Fixture(VIEW_LABELS, viewRows(), List.of(HASH_A, HASH_B));
        fixture.runViews(Mode.CACHED);
        PgCatalogRowCache.resetCounters();

        // the materialized view definition changes on the server
        List<Object[]> changed = viewRows();
        changed.get(1)[6] = "SELECT 3;";
        fixture.replaceRows(changed, List.of(HASH_A, HASH_C));

        PgDatabase warm = fixture.runViews(Mode.CACHED);
        assertEquals(1, fixture.server.missRuns.get());
        assertEquals(List.of(HASH_C), fixture.server.lastMissRequest);
        assertEquals(1L, PgCatalogRowCache.getHitCount());
        assertEquals(1L, PgCatalogRowCache.getMissCount());
        assertEquals(2L, PgCatalogRowCache.getStoreCount(),
                "replacement generation contains both ordered rows");

        PgDatabase off = fixture.runViews(Mode.OFF);
        assertEquals(off, warm);
    }

    @Test
    void sparseMissesAboveOnePayloadUseAtMostTwoCatalogScans()
            throws Exception {
        int rowCount = 40_003;
        int changedCount = 20_001;
        List<Object[]> rows = new ArrayList<>(rowCount);
        List<String> coldHashes = new ArrayList<>(rowCount);
        List<String> warmHashes = new ArrayList<>(rowCount);
        for (int row = 0; row < rowCount; row++) {
            rows.add(new Object[] {"int4", "text", "i", "b", null, null});
            coldHashes.add("%032x".formatted(row));
            warmHashes.add(row < changedCount
                    ? "%032x".formatted((long) rowCount + row)
                    : coldHashes.get(row));
        }
        var fixture = new Fixture(CAST_LABELS, rows, coldHashes);
        fixture.lastQuery = "SELECT catalog_fixture";
        assertTrue(fixture.readThroughCache(result -> { }));

        int hashedBeforeWarm = fixture.server.hashedRuns.get();
        fixture.replaceRows(rows, warmHashes);
        assertTrue(fixture.readThroughCache(result -> { }));

        int coldScans = fixture.server.hashedRuns.get() - hashedBeforeWarm;
        int hashScans = fixture.server.hashOnlyRuns.get();
        int missScans = fixture.server.missRuns.get();
        assertAll(
                () -> assertTrue(missScans <= 1,
                        "all sparse misses must use one bounded pass or cold fallback"),
                () -> assertTrue(hashScans + missScans + coldScans <= 2,
                        "warm comparison must scan the source at most twice"),
                () -> assertTrue(coldScans == 1 || missScans == 1,
                        "the second scan must be either one miss pass or one cold pass"));
    }

    @Test
    void missArrayUsesTheSameLaneConnectionAsItsPreparedStatement() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);

        List<Object[]> changed = castRows();
        changed.get(1)[5] = "changed comment";
        fixture.replaceRows(changed, List.of(HASH_A, HASH_C));

        fixture.runCastsOnLane();

        assertAll(
                () -> assertSame(fixture.server.lastMissStatementConnection,
                        fixture.server.lastArrayConnection),
                () -> assertSame(fixture.server.lastMissStatementConnection,
                        fixture.server.lastSavepointConnection),
                () -> assertNotSame(fixture.server.lastPrimaryConnection,
                        fixture.server.lastArrayConnection),
                () -> assertEquals(0, fixture.server.primaryArrayCreations.get(),
                        "the primary connection must never create a lane miss array"));
    }

    @Test
    void actualReaderParametersArePartOfTheExactCachedQueryIdentity()
            throws Exception {
        var views = new Fixture(VIEW_LABELS, viewRows(),
                List.of(HASH_A, HASH_B));
        views.runViews(Mode.CACHED, false);
        String regularViewsQuery = views.lastQuery;
        views.runViews(Mode.CACHED, true);
        String simplifiedViewsQuery = views.lastQuery;

        var casts = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        casts.runCasts(Mode.CACHED);

        assertAll(
                () -> assertTrue(regularViewsQuery.contains(
                        "pg_get_viewdef(res.oid, false)")),
                () -> assertTrue(simplifiedViewsQuery.contains(
                        "pg_get_viewdef(res.oid, true)")),
                () -> assertNotEquals(regularViewsQuery,
                        simplifiedViewsQuery),
                () -> assertFalse(regularViewsQuery.contains("?")),
                () -> assertFalse(simplifiedViewsQuery.contains("?")),
                () -> assertTrue(casts.lastQuery.contains("res.oid > 0")),
                () -> assertFalse(casts.lastQuery.contains("?")));
    }

    @Test
    void typedTelemetryPublishesOneSecretFreeReaderAndRunAggregate()
            throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        var telemetry = new RecordingTelemetry();
        var loader = new TestLoader(fixture.server.connection("primary"),
                new CoreSettings());
        loader.enableRowCache(cacheDir, namespace("fixture"), telemetry);

        String readerName = PgCastsReader.class.getSimpleName();
        assertTrue(loader.rowCache.read(readerName, fixture.lastQuery,
                result -> { }, null));
        loader.rowCache.finishRun();
        loader.rowCache.finishRun();

        assertEquals(1, telemetry.readers.size());
        assertEquals(1, telemetry.runs.size());
        PgCatalogReaderCacheTelemetry reader = telemetry.readers.get(0);
        PgCatalogCacheRunTelemetry run = telemetry.runs.get(0);
        assertAll(
                () -> assertEquals(readerName, reader.readerName()),
                () -> assertEquals(2L, reader.rows()),
                () -> assertEquals(2L, reader.hits()),
                () -> assertEquals(0L, reader.misses()),
                () -> assertEquals(1L, run.readers()),
                () -> assertEquals(reader.rows(), run.rows()),
                () -> assertEquals(reader.hits(), run.hits()),
                () -> assertFalse(reader.toString().contains("fixture-password")));
    }

    @Test
    void concurrentReadersKeepExactPerRunSummary() throws Exception {
        int readerCount = 32;
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        var loader = new TestLoader(fixture.server.connection("primary"), new CoreSettings());
        loader.enableRowCache(cacheDir);

        try (LogCapture capture = LogCapture.start()) {
            runConcurrentCacheReaders(fixture, loader, readerCount, "reader_");
            runConcurrentCacheReaders(fixture, loader, readerCount, "reader_");
            runConcurrentCacheReaders(fixture, loader, readerCount, "duplicate_reader_");
            fixture.replaceRows(castRows(), List.of(HASH_A, HASH_A));
            runConcurrentDuplicateBypasses(fixture, loader, readerCount);
            loader.rowCache.finishRun();

            String exactSummary = "catalog_row_cache_summary readers="
                    + readerCount * 4 + " hits=" + readerCount * 2
                    + " misses="
                    + readerCount * 4 + " stored=" + readerCount * 4
                    + " bypassed_readers=" + readerCount;
            assertAll(
                    () -> assertEquals(1, capture.messagesContaining(exactSummary).size(),
                            exactSummary),
                    () -> assertEquals(readerCount * 2L,
                            PgCatalogRowCache.getHitCount()),
                    () -> assertEquals(readerCount * 4L,
                            PgCatalogRowCache.getMissCount()),
                    () -> assertEquals(readerCount * 4L,
                            PgCatalogRowCache.getStoreCount()),
                    () -> assertEquals(readerCount,
                            PgCatalogRowCache.getBypassedReaderCount()));
        }
    }

    private static void runConcurrentCacheReaders(Fixture fixture, TestLoader loader,
            int readerCount, String readerPrefix) throws Exception {
        var ready = new CountDownLatch(readerCount);
        var release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(readerCount);
        List<Future<Boolean>> reads = new ArrayList<>();
        try {
            for (int i = 0; i < readerCount; i++) {
                int reader = i;
                reads.add(executor.submit(() -> {
                    Connection laneConnection = fixture.server.connection("lane");
                    loader.bindCatalogLane(new JdbcCatalogLane(laneConnection));
                    var consumed = new AtomicInteger();
                    try {
                        return loader.rowCache.read(readerPrefix + reader,
                                "SELECT * FROM scripted_" + readerPrefix + reader, result -> {
                                    if (consumed.incrementAndGet() == 2) {
                                        ready.countDown();
                                        assertTrue(release.await(10, TimeUnit.SECONDS));
                                    }
                                }, null);
                    } finally {
                        loader.unbindCatalogLane();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            release.countDown();
            for (Future<Boolean> read : reads) {
                assertTrue(read.get(10, TimeUnit.SECONDS));
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void runConcurrentDuplicateBypasses(Fixture fixture, TestLoader loader,
            int readerCount) throws Exception {
        var ready = new CountDownLatch(readerCount);
        var release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(readerCount);
        List<Future<Boolean>> reads = new ArrayList<>();
        try {
            for (int i = 0; i < readerCount; i++) {
                int reader = i;
                reads.add(executor.submit(() -> {
                    Connection laneConnection = fixture.server.connection("lane");
                    loader.bindCatalogLane(new JdbcCatalogLane(laneConnection));
                    try {
                        ready.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                        return loader.rowCache.read("duplicate_reader_" + reader,
                                "SELECT * FROM scripted_duplicate_reader_" + reader,
                                result -> { }, null);
                    } finally {
                        loader.unbindCatalogLane();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            release.countDown();
            for (Future<Boolean> read : reads) {
                assertFalse(read.get(10, TimeUnit.SECONDS));
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void castsReaderModelIsEqualOffColdAndWarmWithShiftedArrayParameter() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));

        PgDatabase off = fixture.runCasts(Mode.OFF);
        PgDatabase cold = fixture.runCasts(Mode.CACHED);

        List<Object[]> changed = castRows();
        changed.get(1)[5] = "changed comment";
        fixture.replaceRows(changed, List.of(HASH_A, HASH_D));
        PgDatabase warm = fixture.runCasts(Mode.CACHED);
        PgDatabase offChanged = fixture.runCasts(Mode.OFF);

        assertEquals(off, cold);
        assertEquals(offChanged, warm);
        assertNotEquals(off, warm);
        int innerParameters = PgCatalogRowCache.countJdbcParameters(fixture.lastQuery);
        assertEquals(innerParameters + 1, fixture.server.lastArrayParameterIndex.get());
    }

    @Test
    void constraintsReaderModelIsEqualOffColdAndWarm() throws Exception {
        var fixture = new Fixture(CONSTRAINT_LABELS, constraintRows(), List.of(HASH_A, HASH_B));

        PgDatabase off = fixture.runConstraints(Mode.OFF);
        PgDatabase cold = fixture.runConstraints(Mode.CACHED);
        PgDatabase warm = fixture.runConstraints(Mode.CACHED);

        assertEquals(off, cold);
        assertEquals(off, warm);
        assertEquals(1, fixture.server.hashOnlyRuns.get());
    }

    @Test
    void duplicateHashesBypassTheCacheAndRunThePlainQuery() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));

        PgDatabase off = fixture.runCasts(Mode.OFF);
        PgDatabase cold = fixture.runCasts(Mode.CACHED);
        fixture.replaceRows(castRows(), List.of(HASH_A, HASH_A));

        try (LogCapture capture = LogCapture.start()) {
            PgDatabase warm = fixture.runCasts(Mode.CACHED);
            assertEquals(off, warm);
            assertEquals(off, cold);
            assertFalse(capture.messagesContaining("bypass=DUPLICATE_HASHES").isEmpty());
        }
        // off baseline plus duplicate-bypass fallback ran the plain query
        assertEquals(2, fixture.server.plainRuns.get());
        assertEquals(1L, PgCatalogRowCache.getBypassedReaderCount());
    }

    @Test
    void excessiveMissRatioRerunsTheColdPathInsteadOfReplay() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        assertEquals(1, fixture.server.hashedRuns.get());

        List<Object[]> changed = castRows();
        changed.get(0)[5] = "one";
        changed.get(1)[5] = "two";
        fixture.replaceRows(changed, List.of(HASH_C, HASH_D));

        try (LogCapture capture = LogCapture.start()) {
            PgDatabase warm = fixture.runCasts(Mode.CACHED);
            PgDatabase off = fixture.runCasts(Mode.OFF);
            assertEquals(off, warm);
            assertFalse(capture.messagesContaining("bypass=MISS_RATIO").isEmpty());
        }
        assertEquals(2, fixture.server.hashedRuns.get());
        assertEquals(0, fixture.server.missRuns.get());
    }

    @Test
    void corruptPackedRowIsRefetchedAloneDuringReplay() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        PgCatalogRowCache.resetCounters();
        corruptPackedValue("varchar");

        PgDatabase warm = fixture.runCasts(Mode.CACHED);
        PgDatabase off = fixture.runCasts(Mode.OFF);

        assertEquals(off, warm);
        assertEquals(1, fixture.server.missRuns.get(),
                "hashed=" + fixture.server.hashedRuns.get()
                        + " hashOnly=" + fixture.server.hashOnlyRuns.get()
                        + " plain=" + fixture.server.plainRuns.get()
                        + " hits=" + PgCatalogRowCache.getHitCount()
                        + " misses=" + PgCatalogRowCache.getMissCount()
                        + " stores=" + PgCatalogRowCache.getStoreCount());
        assertEquals(List.of(HASH_B), fixture.server.lastMissRequest);
        assertEquals(1L, PgCatalogRowCache.getHitCount());
        assertEquals(1L, PgCatalogRowCache.getMissCount());
        assertEquals(0L, PgCatalogRowCache.getStoreCount());
    }

    @Test
    void durableGenerationManifestSelectsWarmPath() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        PgDatabase cold = fixture.runCasts(Mode.CACHED);

        PgDatabase warm = fixture.runCasts(Mode.CACHED);

        assertAll(
                () -> assertEquals(cold, warm),
                () -> assertEquals(1, fixture.server.hashedRuns.get()),
                () -> assertEquals(1, fixture.server.hashOnlyRuns.get()));
    }

    @Test
    void invalidManifestsAreDeletedAndSelectColdPath() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        PgDatabase expected = fixture.runCasts(Mode.CACHED);
        Path manifest = onlyPackManifest();
        List<byte[]> invalidPayloads = List.of(
                new byte[0],
                new byte[] {PgCatalogRowCodec.FORMAT_VERSION, 0},
                new byte[] {(byte) (PgCatalogRowCodec.FORMAT_VERSION + 1)});

        int expectedHashedRuns = 1;
        for (byte[] payload : invalidPayloads) {
            Files.write(manifest, payload);
            int hashOnlyRuns = fixture.server.hashOnlyRuns.get();

            PgDatabase actual = fixture.runCasts(Mode.CACHED);

            assertAll(
                    () -> assertEquals(expected, actual),
                    () -> assertEquals(hashOnlyRuns,
                            fixture.server.hashOnlyRuns.get()),
                    () -> assertEquals(PgCatalogReaderPackStore.MAX_MANIFEST_BYTES,
                            Files.size(manifest)));
            assertEquals(++expectedHashedRuns, fixture.server.hashedRuns.get());
        }
    }

    @Test
    void oversizedNewRowsAreConsumedButNotPublished() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.OFF);
        PgCatalogRowCache.resetCounters();
        List<String> consumedSources = new ArrayList<>();

        boolean cached = fixture.readThroughCache(8,
                result -> consumedSources.add(result.getString("source")));

        assertAll(
                () -> assertTrue(cached),
                () -> assertEquals(List.of("int4", "text"), consumedSources),
                () -> assertEquals(2L, PgCatalogRowCache.getMissCount()),
                () -> assertEquals(0L, PgCatalogRowCache.getStoreCount()),
                () -> assertTrue(storedPackFiles().isEmpty()));
    }

    @Test
    void preFeedSqlFailureFallsOpenToThePlainQuery() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));
        PgDatabase cold = fixture.runCasts(Mode.CACHED);
        fixture.server.failHashOnlyPass = true;

        try (LogCapture capture = LogCapture.start()) {
            PgDatabase warm = fixture.runCasts(Mode.CACHED);
            assertEquals(cold, warm);
            assertFalse(capture.messagesContaining("bypass=CACHE_ERROR").isEmpty());
        }
        assertEquals(1, fixture.server.plainRuns.get());
        assertEquals(1L, PgCatalogRowCache.getBypassedReaderCount());
    }

    @Test
    void rollbackFailureKeepsTheCacheSqlFailurePrimary() throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        SQLException cacheFailure =
                new SQLException("controlled cache SQL failure");
        SQLException rollbackFailure =
                new SQLException("controlled savepoint rollback failure");
        fixture.server.hashOnlyFailure = cacheFailure;
        fixture.server.rollbackFailure = rollbackFailure;

        SQLException thrown = assertThrows(SQLException.class,
                () -> fixture.runCasts(Mode.CACHED));

        assertAll(
                () -> assertEquals(cacheFailure.getMessage(),
                        thrown.getMessage()),
                () -> assertSame(cacheFailure,
                        thrown.getCause().getCause()),
                () -> assertEquals(List.of(rollbackFailure),
                        List.of(thrown.getSuppressed())),
                () -> assertEquals(0, fixture.server.plainRuns.get(),
                        "unsafe fallback must not run after rollback failure"));
    }

    @Test
    void consumerFailureAfterFirstMutationIsNeverRestartedOnPlainQuery()
            throws Exception {
        var fixture = new Fixture(CAST_LABELS, castRows(),
                List.of(HASH_A, HASH_B));
        fixture.runCasts(Mode.CACHED);
        SQLException controlled = new SQLException(
                "controlled consumer mutation failure");

        SQLException thrown = assertThrows(SQLException.class,
                () -> fixture.readThroughCache(result -> {
                    throw controlled;
                }));

        assertSame(controlled, thrown);
        assertEquals(0, fixture.server.plainRuns.get(),
                "a mutated model must never be fed again from a fallback query");
    }

    @Test
    void pgObjectColumnsMaterializeAsTheirWireText() throws Exception {
        var regproc = new PGobject();
        regproc.setType("regproc");
        regproc.setValue("myschema.fn(integer)");
        List<Object[]> serverRows = castRows();
        serverRows.get(0)[4] = regproc;
        var cachedFixture = new Fixture(CAST_LABELS, serverRows, List.of(HASH_A, HASH_B));
        var plainFixture = new Fixture(CAST_LABELS, castRows(), List.of(HASH_A, HASH_B));

        PgDatabase off = plainFixture.runCasts(Mode.OFF);
        PgDatabase cold = cachedFixture.runCasts(Mode.CACHED);
        PgDatabase warm = cachedFixture.runCasts(Mode.CACHED);

        assertEquals(off, cold);
        assertEquals(off, warm);
        assertEquals(2L, PgCatalogRowCache.getStoreCount());
    }

    @Test
    void parameterPlaceholdersAreCountedOutsideLiteralsAndComments() {
        assertEquals(0, PgCatalogRowCache.countJdbcParameters("SELECT 1"));
        assertEquals(2, PgCatalogRowCache.countJdbcParameters("SELECT ?, ?"));
        assertEquals(1, PgCatalogRowCache.countJdbcParameters(
                "SELECT '?', \"a?b\", ? FROM t"));
        assertEquals(1, PgCatalogRowCache.countJdbcParameters(
                "SELECT 'it''s a ?', ? FROM t"));
        assertEquals(1, PgCatalogRowCache.countJdbcParameters(
                "SELECT ? -- trailing ? comment\nFROM t"));
        assertEquals(1, PgCatalogRowCache.countJdbcParameters(
                "SELECT /* block ? */ ? FROM t"));
        assertEquals(0, PgCatalogRowCache.countJdbcParameters("SELECT '? unterminated"));
    }

    private void corruptPackedValue(String text) throws IOException {
        List<Path> packs = storedPackFiles();
        assertEquals(1, packs.size());
        Path pack = packs.get(0);
        byte[] bytes = Files.readAllBytes(pack);
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        int found = -1;
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                found = i;
                break;
            }
        }
        assertTrue(found >= 0, "fixture value must be present in the pack");
        bytes[found] ^= 1;
        Files.write(pack, bytes);
    }

    private Path onlyPackManifest() throws IOException {
        List<Path> files = storedFiles("reader-packs").stream()
                .filter(path -> path.getFileName().toString()
                        .equals("current.bin"))
                .toList();
        assertEquals(1, files.size());
        return files.get(0);
    }

    private List<Path> storedPackFiles() throws IOException {
        return storedFiles("reader-packs").stream()
                .filter(path -> path.getFileName().toString()
                        .startsWith("generation-"))
                .filter(path -> path.getFileName().toString()
                        .endsWith(".bin"))
                .toList();
    }

    private List<Path> storedFiles(String category) throws IOException {
        Path categoryDirectory = cacheDir.resolve(category);
        if (!Files.isDirectory(categoryDirectory)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(categoryDirectory)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private static PgCatalogCacheNamespace namespace(String databaseName) {
        return PgCatalogCacheNamespace.fromValues(
                PgCatalogCacheNamespace.CACHE_ABI,
                "192.0.2.10", "5432", databaseName, "session_user", "current_role",
                "170006", "UTC", "ISO, MDY", "postgres", "1", "hex", "off");
    }

    private enum Mode {
        OFF,
        CACHED
    }

    private record HashChunk(long ordinal, long count, byte[] payload, long total) {
    }

    /** One fixture database served through the fake JDBC layer. */
    private final class Fixture {

        private final FakeCatalogServer server;
        private String lastQuery;

        private Fixture(String[] labels, List<Object[]> rows, List<String> hashes) {
            this.server = new FakeCatalogServer(labels, rows, hashes);
        }

        private void replaceRows(List<Object[]> rows, List<String> hashes) {
            server.replaceRows(rows, hashes);
        }

        private boolean readThroughCache(ICatalogRowCache.CatalogRowConsumer consumer)
                throws Exception {
            var loader = new TestLoader(server.connection("primary"), new CoreSettings());
            loader.enableRowCache(cacheDir);
            return loader.rowCache.read(PgCastsReader.class.getSimpleName(),
                    lastQuery, consumer, null);
        }

        private boolean readThroughCache(Path directory,
                PgCatalogCacheNamespace namespace, String readerName,
                ICatalogRowCache.CatalogRowConsumer consumer) throws Exception {
            var loader = new TestLoader(server.connection("primary"), new CoreSettings());
            loader.enableRowCache(directory, namespace);
            return loader.rowCache.read(readerName, lastQuery, consumer, null);
        }

        private boolean readThroughCache(int maxRowPayloadBytes,
                ICatalogRowCache.CatalogRowConsumer consumer) throws Exception {
            var loader = new TestLoader(server.connection("primary"), new CoreSettings());
            loader.enableRowCache(cacheDir, maxRowPayloadBytes);
            return loader.rowCache.read(PgCastsReader.class.getSimpleName(),
                    lastQuery, consumer, null);
        }

        private boolean readThroughFingerprint(String query,
                ICatalogRowCache.CatalogQueryOrder order, byte[] snapshot,
                ICatalogRowCache.CatalogRowConsumer consumer) throws Exception {
            return readThroughFingerprint(query, order, snapshot, consumer,
                    null);
        }

        private boolean readThroughFingerprint(String query,
                ICatalogRowCache.CatalogQueryOrder order, byte[] snapshot,
                ICatalogRowCache.CatalogRowConsumer consumer,
                ICatalogRowCache.CatalogQueryParameterSetter setter)
                throws Exception {
            var loader = new TestLoader(server.connection("primary"),
                    new CoreSettings());
            loader.enableFingerprintRowCache(cacheDir, snapshot);
            return loader.rowCache.read("FingerprintReader", query, order,
                    consumer, setter);
        }

        private PgDatabase runViews(Mode mode) throws Exception {
            return run(mode, (loader, database) -> new PgViewsReader(loader).read());
        }

        private PgDatabase runViews(Mode mode, boolean simplifyView)
                throws Exception {
            return run(mode, false,
                    settings -> settings.setSimplifyView(simplifyView),
                    (loader, database) -> new PgViewsReader(loader).read());
        }

        private PgDatabase runCasts(Mode mode) throws Exception {
            return run(mode, (loader, database) -> new PgCastsReader(loader, database).read());
        }

        private PgDatabase runCastsOnLane() throws Exception {
            return run(Mode.CACHED, true,
                    (loader, database) -> new PgCastsReader(loader, database).read());
        }

        private PgDatabase runConstraints(Mode mode) throws Exception {
            return run(mode, (loader, database) -> new PgConstraintsReader(loader).read());
        }

        private PgDatabase run(Mode mode, ReaderInvocation invocation) throws Exception {
            return run(mode, false, invocation);
        }

        private PgDatabase run(Mode mode, boolean useLane, ReaderInvocation invocation)
                throws Exception {
            return run(mode, useLane, settings -> { }, invocation);
        }

        private PgDatabase run(Mode mode, boolean useLane,
                Consumer<CoreSettings> configure,
                ReaderInvocation invocation) throws Exception {
            var settings = new CoreSettings();
            settings.setIgnorePrivileges(true);
            configure.accept(settings);
            var loader = new TestLoader(server.connection("primary"), settings);
            if (mode == Mode.CACHED) {
                loader.enableRowCache(cacheDir);
            }
            var database = new PgDatabase();
            var schema = new PgSchema("app");
            var table = new PgSimpleTable("t1");
            table.addColumn(new PgColumn("a"));
            schema.addChild(table);
            database.addChild(schema);
            loader.putSchema(10L, schema);
            lastQuery = null;

            if (useLane) {
                loader.bindCatalogLane(new JdbcCatalogLane(server.connection("lane")));
            }
            try {
                invocation.read(loader, database);
                loader.drainAntlrTasks();
            } finally {
                if (useLane) {
                    loader.unbindCatalogLane();
                }
            }
            lastQuery = server.lastBaseQuery;
            return database;
        }
    }

    @FunctionalInterface
    private interface ReaderInvocation {

        void read(PgJdbcLoader loader, PgDatabase database) throws Exception;
    }

    /**
     * Fake server: answers the plain query, the hashed cold query, the
     * hash-only pass and the chunked miss fetch over one mutable set of
     * fixture rows, always through the same ResultSet view the cache itself
     * uses for replay.
     */
    private static final class FakeCatalogServer {

        private final String[] labels;
        private List<Object[]> rows;
        private List<String> hashes;

        private final AtomicInteger plainRuns = new AtomicInteger();
        private final AtomicInteger hashedRuns = new AtomicInteger();
        private final AtomicInteger hashOnlyRuns = new AtomicInteger();
        private final AtomicInteger missRuns = new AtomicInteger();
        private final AtomicInteger packedGetBytesReads = new AtomicInteger();
        private final AtomicInteger packedBinaryStreamReads =
                new AtomicInteger();
        private final AtomicInteger packedFetchSize = new AtomicInteger(-1);
        private final AtomicInteger lastArrayParameterIndex = new AtomicInteger(-1);
        private final AtomicInteger connectionSequence = new AtomicInteger();
        private final AtomicInteger primaryArrayCreations = new AtomicInteger();
        private volatile List<String> lastMissRequest = List.of();
        private volatile String lastBaseQuery;
        private volatile boolean failHashOnlyPass;
        private volatile SQLException hashOnlyFailure;
        private volatile SQLException rollbackFailure;
        private volatile boolean packedHashesOverridden;
        private volatile List<HashChunk> packedChunksOverride;
        private volatile long lastPackedCount = -1L;
        private volatile byte[] lastPackedPayload;
        private volatile int maxPackedPayloadBytes;
        private volatile Connection lastArrayConnection;
        private volatile Connection lastMissStatementConnection;
        private volatile Connection lastSavepointConnection;
        private volatile Connection lastPrimaryConnection;
        private volatile Runnable missFetchExhausted;

        private FakeCatalogServer(String[] labels, List<Object[]> rows, List<String> hashes) {
            this.labels = labels;
            replaceRows(rows, hashes);
        }

        private synchronized void replaceRows(List<Object[]> rows, List<String> hashes) {
            assertEquals(rows.size(), hashes.size());
            // a live pgJDBC row serves an array column as elements and as
            // text, so the fake server carries both shapes as well
            this.rows = rows.stream().map(PgFakeCatalogArrays::capture)
                    .toList();
            this.hashes = List.copyOf(hashes);
        }

        private void overridePackedHashes(long count, byte[] payload) {
            packedHashesOverridden = true;
            packedChunksOverride = List.of(new HashChunk(0, count, payload, count));
        }

        private void overridePackedChunks(List<HashChunk> chunks) {
            packedHashesOverridden = true;
            packedChunksOverride = List.copyOf(chunks);
        }

        private void afterMissFetchExhausted(Runnable callback) {
            missFetchExhausted = callback;
        }

        private Connection connection(String role) throws SQLException {
            String identity = role + '-' + connectionSequence.incrementAndGet();
            Connection connection = mock(Connection.class);
            if (role.equals("primary")) {
                lastPrimaryConnection = connection;
            }
            when(connection.prepareStatement(anyString()))
                    .thenAnswer(invocation -> prepare(invocation.getArgument(0), connection,
                            identity));
            Savepoint savepoint = mock(Savepoint.class);
            when(connection.setSavepoint()).thenAnswer(invocation -> {
                lastSavepointConnection = connection;
                return savepoint;
            });
            doAnswer(invocation -> {
                if (rollbackFailure != null) {
                    throw rollbackFailure;
                }
                return null;
            }).when(connection).rollback(savepoint);
            when(connection.createArrayOf(eq("text"), any())).thenAnswer(invocation -> {
                lastArrayConnection = connection;
                if (identity.startsWith("primary-")) {
                    primaryArrayCreations.incrementAndGet();
                }
                Object[] elements = invocation.getArgument(1);
                Array array = mock(Array.class);
                when(array.getArray()).thenReturn(elements);
                return array;
            });
            return connection;
        }

        private PreparedStatement prepare(String sql, Connection connection, String identity)
                throws SQLException {
            PreparedStatement statement = mock(PreparedStatement.class);
            when(statement.getConnection()).thenReturn(connection);
            if (sql.contains("__pgck_wanted")) {
                lastMissStatementConnection = connection;
            }
            var packedParameter = new AtomicReference<byte[]>();
            var expectedFingerprint = new AtomicReference<String>();
            doAnswer(invocation -> {
                lastArrayParameterIndex.set(invocation.getArgument(0));
                packedParameter.set(invocation.getArgument(1));
                lastArrayConnection = connection;
                if (identity.startsWith("primary-")) {
                    primaryArrayCreations.incrementAndGet();
                }
                return null;
            }).when(statement).setBytes(anyInt(), any());
            doAnswer(invocation -> {
                expectedFingerprint.set(invocation.getArgument(1));
                return null;
            }).when(statement).setString(anyInt(), anyString());
            if (sql.startsWith(HASH_ONLY_PREFIX)) {
                doAnswer(invocation -> {
                    packedFetchSize.set(invocation.getArgument(0));
                    return null;
                }).when(statement).setFetchSize(anyInt());
            }
            when(statement.executeQuery())
                    .thenAnswer(invocation -> execute(sql, packedParameter.get(),
                            expectedFingerprint.get()));
            return statement;
        }

        private synchronized PgCachedRowResultSet execute(String sql, byte[] wantedHashes,
                String expectedFingerprint) throws SQLException {
            if (sql.contains("__pgck_wanted")) {
                missRuns.incrementAndGet();
                List<String> wanted = new ArrayList<>();
                for (int offset = 0; offset < wantedHashes.length;
                        offset += PgPackedCatalogHashes.MD5_BYTES) {
                    wanted.add(HexFormat.of().formatHex(wantedHashes, offset,
                            offset + PgPackedCatalogHashes.MD5_BYTES));
                }
                lastMissRequest = wanted;
                Set<String> filter = new HashSet<>(wanted);
                List<PgCachedCatalogRow> result = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) {
                    if (filter.contains(hashes.get(i))) {
                        result.add(hashedRow(i));
                    }
                }
                PgCachedRowResultSet cursor = PgCachedRowResultSet.cursor(
                        hashedLabels(), result);
                if (missFetchExhausted == null) {
                    return cursor;
                }
                PgCachedRowResultSet observed = spy(cursor);
                doAnswer(invocation -> {
                    boolean hasNext = (boolean) invocation.callRealMethod();
                    if (!hasNext) {
                        Runnable callback = missFetchExhausted;
                        missFetchExhausted = null;
                        if (callback != null) {
                            callback.run();
                        }
                    }
                    return hasNext;
                }).when(observed).next();
                return observed;
            }
            if (sql.startsWith("SELECT pg_catalog.decode(pg_catalog.md5(__pgck_r::text), "
                    + "'hex') AS __pgck_h, __pgck_r.*")) {
                hashedRuns.incrementAndGet();
                rememberBaseQuery(sql, true);
                List<PgCachedCatalogRow> result = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) {
                    result.add(hashedRow(i));
                }
                return PgCachedRowResultSet.cursor(hashedLabels(), result);
            }
            if (sql.startsWith(HASH_ONLY_PREFIX)) {
                if (hashOnlyFailure != null) {
                    throw hashOnlyFailure;
                }
                if (failHashOnlyPass) {
                    throw new SQLException("simulated hash pass failure");
                }
                hashOnlyRuns.incrementAndGet();
                byte[] allHashes = HexFormat.of().parseHex(String.join("", hashes));
                List<HashChunk> chunks = packedHashesOverridden
                        ? packedChunksOverride : splitHashChunks(allHashes, hashes.size());
                lastPackedCount = packedHashesOverridden
                        ? chunks.isEmpty() ? 0 : chunks.get(0).total()
                        : hashes.size();
                lastPackedPayload = allHashes;
                String[] packedLabels = {
                        "__pgck_c", "__pgck_n", "__pgck_h", "__pgck_t"
                };
                // the server answers with the ordered fingerprint first and
                // keeps every hash to itself while that fingerprint matches
                byte[] fingerprint = packedHashesOverridden
                        ? new byte[PgPackedCatalogHashes.MD5_BYTES]
                        : orderedFingerprint(hashes);
                List<PgCachedCatalogRow> packedRows = new ArrayList<>();
                packedRows.add(new PgCachedCatalogRow(packedLabels,
                        new Object[] {PgCatalogRowCache.SUMMARY_CHUNK_ORDINAL,
                                lastPackedCount, fingerprint, lastPackedCount}));
                boolean unchanged = HexFormat.of().formatHex(fingerprint)
                        .equals(expectedFingerprint);
                for (HashChunk chunk : unchanged ? List.<HashChunk>of() : chunks) {
                    if (chunk.payload() != null) {
                        maxPackedPayloadBytes = Math.max(maxPackedPayloadBytes,
                                chunk.payload().length);
                    }
                    packedRows.add(new PgCachedCatalogRow(packedLabels,
                            new Object[] {chunk.ordinal(), chunk.count(),
                                    chunk.payload(), chunk.total()}));
                }
                PgCachedRowResultSet cursor = spy(PgCachedRowResultSet.cursor(
                        packedLabels, packedRows));
                doAnswer(invocation -> {
                    packedGetBytesReads.incrementAndGet();
                    return invocation.callRealMethod();
                }).when(cursor).getBytes(3);
                doAnswer(invocation -> {
                    packedBinaryStreamReads.incrementAndGet();
                    return invocation.callRealMethod();
                }).when(cursor).getBinaryStream(3);
                return cursor;
            }
            if (sql.startsWith("SELECT pg_catalog.md5(__pgck_r::text) AS __pgck_h")) {
                throw new SQLException("text-row hash protocol is not accepted");
            }
            plainRuns.incrementAndGet();
            lastBaseQuery = sql;
            List<PgCachedCatalogRow> result = new ArrayList<>();
            for (Object[] values : rows) {
                result.add(new PgCachedCatalogRow(labels, values.clone()));
            }
            return PgCachedRowResultSet.cursor(labels, result);
        }

        /** MD5 over the concatenated lowercase hex of every row hash. */
        private static byte[] orderedFingerprint(List<String> hashes) {
            try {
                var digest = java.security.MessageDigest.getInstance("MD5");
                for (String hash : hashes) {
                    digest.update(hash.getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII));
                }
                return digest.digest();
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException("MD5 is not available", ex);
            }
        }

        private static List<HashChunk> splitHashChunks(byte[] packed, int count) {
            if (count == 0) {
                return List.of();
            }
            List<HashChunk> chunks = new ArrayList<>();
            int copied = 0;
            long ordinal = 0;
            while (copied < count) {
                int chunkCount = Math.min(PgCatalogRowCache.HASHES_PER_CHUNK,
                        count - copied);
                int from = copied * PgPackedCatalogHashes.MD5_BYTES;
                int to = from + chunkCount * PgPackedCatalogHashes.MD5_BYTES;
                chunks.add(new HashChunk(ordinal++, chunkCount,
                        java.util.Arrays.copyOfRange(packed, from, to), count));
                copied += chunkCount;
            }
            return chunks;
        }

        private void rememberBaseQuery(String wrappedSql, boolean hashed) {
            int from = wrappedSql.indexOf("\nFROM (\n");
            int tail = wrappedSql.lastIndexOf("\n) __pgck_r");
            if (from >= 0 && tail > from) {
                lastBaseQuery = wrappedSql.substring(from + "\nFROM (\n".length(), tail);
            }
        }

        private String[] hashedLabels() {
            var hashedLabels = new String[labels.length + 1];
            hashedLabels[0] = "__pgck_h";
            System.arraycopy(labels, 0, hashedLabels, 1, labels.length);
            return hashedLabels;
        }

        private PgCachedCatalogRow hashedRow(int index) {
            var values = new Object[labels.length + 1];
            values[0] = HexFormat.of().parseHex(hashes.get(index));
            System.arraycopy(rows.get(index), 0, values, 1, labels.length);
            return new PgCachedCatalogRow(hashedLabels(), values);
        }
    }

    private static final class TestLoader extends PgJdbcLoader {

        private PgCatalogRowCache rowCache;

        private TestLoader(Connection connection, CoreSettings settings) {
            super(mockConnector(), "UTC", settings);
            this.connection = connection;
            setVersion(170000);
        }

        private static IJdbcConnector mockConnector() {
            IJdbcConnector connector = mock(IJdbcConnector.class);
            when(connector.getDbName()).thenReturn("test");
            return connector;
        }

        private void enableRowCache(Path directory) {
            enableRowCache(directory, namespace("fixture"));
        }

        private void enableRowCache(Path directory, PgCatalogCacheNamespace namespace) {
            rowCache = new PgCatalogRowCache(this, directory, UNLIMITED_CACHE, namespace);
        }

        private void enableRowCache(Path directory,
                PgCatalogCacheNamespace namespace,
                IComparisonTelemetry telemetry) {
            rowCache = new PgCatalogRowCache(this, directory,
                    UNLIMITED_CACHE,
                    PgCatalogReaderPackFormat.MAX_VALUES_BYTES, namespace,
                    false, null, telemetry);
        }

        private void enableRowCache(Path directory, int maxRowPayloadBytes) {
            rowCache = new PgCatalogRowCache(this, directory, UNLIMITED_CACHE,
                    maxRowPayloadBytes, namespace("fixture"));
        }

        private void enableFingerprintRowCache(Path directory,
                byte[] snapshot) {
            rowCache = new PgCatalogRowCache(this, directory,
                    UNLIMITED_CACHE, namespace("fixture"), true, snapshot,
                    org.pgcodekeeper.core.telemetry.IComparisonTelemetry.NO_OP);
        }

        @Override
        public ICatalogRowCache getCatalogRowCache() {
            return rowCache;
        }

        private void drainAntlrTasks() throws Exception {
            finishLoaders();
        }
    }

    private static final class RecordingTelemetry
            implements IComparisonTelemetry {

        private final List<PgCatalogReaderCacheTelemetry> readers =
                new ArrayList<>();
        private final List<PgCatalogCacheRunTelemetry> runs =
                new ArrayList<>();

        @Override
        public void pgCatalogReaderFinished(
                PgCatalogReaderCacheTelemetry event) {
            readers.add(event);
        }

        @Override
        public void pgCatalogCacheFinished(PgCatalogCacheRunTelemetry event) {
            runs.add(event);
        }
    }
}
