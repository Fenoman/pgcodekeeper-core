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
package org.pgcodekeeper.core.database.pg.loader;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.base.loader.JdbcCatalogLane;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;
import org.pgcodekeeper.core.database.base.parser.ParserExecutionPolicy;
import org.pgcodekeeper.core.database.base.parser.ParserExecutionProbeHarness;
import org.pgcodekeeper.core.database.pg.jdbc.PgCatalogCacheNamespace;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.telemetry.PgConnectionLifecycleTelemetry;
import org.pgcodekeeper.core.telemetry.PgConnectionRole;
import org.pgcodekeeper.core.telemetry.PgParallelCatalogFallbackReason;

/**
 * Determinism, canonical launcher ordering, stage-barrier and fallback tests
 * of the lane-parallel catalog readers against a scripted catalog fixture.
 */
@Isolated("installs the package-local parser execution observer")
class PgParallelCatalogReadersTest {

    private static final int PARALLEL_WORKERS = 3;
    private static final String COLD_CACHE_QUERY_PREFIX =
            "SELECT pg_catalog.decode(pg_catalog.md5(__pgck_r::text), "
                    + "'hex') AS __pgck_h, __pgck_r.*";
    private static final String WARM_CACHE_QUERY_PREFIX =
            "WITH __pgck_hashes AS (";

    @Test
    void parallelLoadIsByteIdenticalToSerialLoadAcrossRepeatedRuns() throws Exception {
        LoadResult serial = load(fixture(), 0);
        ScriptedPgCatalog parallelCatalog = fixture();
        LoadResult parallelFirst = load(parallelCatalog, PARALLEL_WORKERS);
        LoadResult parallelSecond = load(fixture(), PARALLEL_WORKERS);

        assertAll(
                () -> assertEquals(serial.dump(), parallelFirst.dump(),
                        "parallel model must be byte-identical to serial"),
                () -> assertEquals(parallelFirst.dump(), parallelSecond.dump(),
                        "repeated parallel loads must be byte-identical"),
                () -> assertEquals(serial.launcherSequence(), parallelFirst.launcherSequence(),
                        "parallel launcher order must equal the serial reader order"),
                () -> assertEquals(parallelFirst.launcherSequence(),
                        parallelSecond.launcherSequence()),
                () -> assertEquals(serial.columnDependencies(),
                        parallelFirst.columnDependencies()),
                () -> assertEquals(parallelFirst.columnDependencies(),
                        parallelSecond.columnDependencies()),
                () -> assertTrue(serial.dump().contains(
                        "c2 text COLLATE app.\"quoted Name\""), serial.dump()),
                () -> assertTrue(serial.dump().contains(
                        "ALTER TABLE app.t1 ALTER COLUMN c2 SET STORAGE MAIN;"), serial.dump()),
                () -> assertTrue(serial.columnDependencies().contains(
                        "app.quoted Name (COLLATION)"), serial.columnDependencies().toString()),
                () -> assertTrue(serial.columnDependencies().contains(
                        "app (SCHEMA)"), serial.columnDependencies().toString()),
                () -> assertTrue(serial.launcherSequence().size() >= 4,
                        "fixture must exercise several launcher-producing readers: "
                                + serial.launcherSequence()),
                // preLoad + primary + three workers
                () -> assertEquals(5, parallelCatalog.getConnectionCount()));
    }

    @Test
    void connectionTelemetryIdentifiesEveryParallelCatalogLane()
            throws Exception {
        ScriptedPgCatalog catalog = fixture();
        var events = new ArrayList<PgConnectionLifecycleTelemetry>();
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgParallelCatalogReaders(PARALLEL_WORKERS);
        settings.setComparisonTelemetry(new IComparisonTelemetry() {
            @Override
            public void pgConnectionLifecycle(
                    PgConnectionLifecycleTelemetry event) {
                events.add(event);
            }
        });

        try (PgJdbcLoader loader =
                new PgJdbcLoader(catalog, "UTC", settings)) {
            loader.load();
        }

        assertEquals(List.of(
                "OPENED:PREFLIGHT:0",
                "CLOSE_REQUESTED:PREFLIGHT:0",
                "OPENED:PRIMARY:0",
                "OPENED:CATALOG_LANE:1",
                "OPENED:CATALOG_LANE:2",
                "OPENED:CATALOG_LANE:3",
                "CLOSE_REQUESTED:CATALOG_LANE:1",
                "CLOSE_REQUESTED:CATALOG_LANE:2",
                "CLOSE_REQUESTED:CATALOG_LANE:3",
                "CLOSE_REQUESTED:PRIMARY:0"),
                events.stream()
                        .map(event -> event.lifecycle() + ":"
                                + event.role() + ":" + event.lane())
                        .toList());
        assertTrue(events.stream().allMatch(
                event -> event.side()
                        == PgConnectionLifecycleTelemetry.LogicalSide.UNBOUND));
        assertTrue(events.stream().allMatch(
                event -> event.backendPid() == 0));
    }

    @Test
    void dedicatedParserExecutionCoversProductionCatalogLanes() throws Exception {
        ScriptedPgCatalog catalog = fixture();
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgParallelCatalogReaders(PARALLEL_WORKERS);
        settings.setParserExecutionPolicy(ParserExecutionPolicy.dedicated(2));
        try (var probe = ParserExecutionProbeHarness.install()) {
            var loader = new ObservedPgJdbcLoader(catalog, settings);
            Queue<AntlrTask<?>> rootTasks = loader.parserTasks();

            try {
                loader.loadAndAnalyze();
                assertDedicatedLaneSnapshot(
                        probe.onlySession().snapshot(), false);
                assertEquals(5, catalog.getConnectionCount());
            } finally {
                loader.close();
            }

            assertClosedParserScope(
                    probe.onlySession().snapshot(), rootTasks);
        }
    }

    @Test
    void cancellationInterruptsActiveProductionCatalogLanes()
            throws Exception {
        ScriptedPgCatalog catalog = fixture();
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgParallelCatalogReaders(PARALLEL_WORKERS);
        settings.setParserExecutionPolicy(ParserExecutionPolicy.dedicated(2));

        var laneBlocker = new LaneBlocker(PARALLEL_WORKERS, 2);
        try (var probe = ParserExecutionProbeHarness.install();
                var laneObserver =
                        PgParallelCatalogReadersObserver.Installation.install(
                                laneBlocker)) {
            var loader = new ObservedPgJdbcLoader(catalog, settings);
            Queue<AntlrTask<?>> rootTasks = loader.parserTasks();
            ExecutorService owner = Executors.newSingleThreadExecutor();
            Future<PgDatabase> load = owner.submit(loader::load);

            try {
                assertTrue(laneBlocker.awaitTasks(10, TimeUnit.SECONDS));
                assertTrue(laneBlocker.awaitDrains(10, TimeUnit.SECONDS));
                assertDedicatedLaneSnapshot(
                        probe.onlySession().snapshot(), true);

                loader.cancel();
                assertTrue(laneBlocker.awaitInterrupts(
                        10, TimeUnit.SECONDS));
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> load.get(10, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof InterruptedException,
                        () -> String.valueOf(failure.getCause()));
            } finally {
                laneBlocker.release();
                loader.close();
                owner.shutdownNow();
                assertTrue(owner.awaitTermination(10, TimeUnit.SECONDS));
            }

            assertClosedParserScope(
                    probe.onlySession().snapshot(), rootTasks);
            assertEquals(PARALLEL_WORKERS,
                    laneBlocker.laneQueues.size());
            for (Queue<AntlrTask<?>> laneTasks
                    : laneBlocker.laneQueues) {
                assertTrue(AntlrTaskManager.isDrained(laneTasks));
                assertThrows(IllegalStateException.class,
                        () -> AntlrTaskManager.submit(laneTasks,
                                () -> null, ignored -> { }));
            }
            assertEquals(5, catalog.getConnectionCount());
        }
    }

    @Test
    void cacheOffDoesNotReadTargetIdentity() throws Exception {
        ScriptedPgCatalog catalog = fixture();
        CoreSettings settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgCatalogCacheRows(true);

        try (PgJdbcLoader loader = new PgJdbcLoader(catalog, "UTC", settings)) {
            loader.load();
        }

        assertEquals(0, catalog.getCacheIdentityQueryCount());
    }

    @Test
    void configuredDirectoryWithoutAUsableCacheDoesNotReadTargetIdentity(
            @TempDir Path cacheDir) throws Exception {
        ScriptedPgCatalog catalog = fixture();
        CoreSettings settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgCatalogCacheDir(cacheDir.toString());

        try (PgJdbcLoader loader = new PgJdbcLoader(catalog, "UTC", settings)) {
            loader.load();
        }

        assertEquals(0, catalog.getCacheIdentityQueryCount());
    }

    @Test
    void cacheIdentitySavepointIsReleasedBeforeSnapshotExportAndModelReaders(
            @TempDir Path cacheDir) throws Exception {
        ScriptedPgCatalog catalog = fixture();

        load(catalog, PARALLEL_WORKERS, cacheDir);

        List<ScriptedPgCatalog.JdbcEvent> events = catalog.getJdbcEvents();
        int setup = eventIndex(events, event -> event.kind()
                == ScriptedPgCatalog.JdbcEventKind.SCRIPT
                && PgJdbcLoader.buildSessionSetupScript("UTC").equals(event.sql()));
        // the primary connection is the one that ran the read-only setup
        int primary = events.get(setup).connectionIdentity();
        int savepoint = eventIndex(events, event -> event.kind()
                == ScriptedPgCatalog.JdbcEventKind.SAVEPOINT_OPEN
                && event.connectionIdentity() == primary);
        int identity = eventIndex(events, event -> event.kind()
                == ScriptedPgCatalog.JdbcEventKind.QUERY
                && PgCatalogCacheNamespace.IDENTITY_QUERY.equals(event.sql()));
        int release = eventIndex(events, event -> event.kind()
                == ScriptedPgCatalog.JdbcEventKind.SAVEPOINT_RELEASE
                && event.connectionIdentity() == primary);
        int snapshotExport = eventIndex(events, event -> event.sql() != null
                && event.sql().contains("pg_export_snapshot")
                && event.connectionIdentity() == primary);

        assertAll(
                () -> assertEquals(1, catalog.getCacheIdentityQueryCount()),
                () -> assertTrue(setup >= 0, "read-only setup was not observed"),
                () -> assertTrue(savepoint > setup,
                        "probe boundary must follow read-only setup: " + events),
                () -> assertTrue(identity > savepoint,
                        "target identity must run inside its boundary: " + events),
                () -> assertTrue(release > identity,
                        "the primary boundary must close before the export: " + events),
                () -> assertTrue(snapshotExport > release,
                        "snapshot export must not run in the probe subtransaction: "
                                + events));
    }

    /**
     * The probe boundary is what a failing derived catalog statement returns
     * to, so it must exist once per catalog connection - not once per reader,
     * which on a high-latency link costs two extra round trips for every
     * reader.
     */
    @Test
    void probeBoundaryIsOpenedOncePerCatalogConnection(@TempDir Path cacheDir)
            throws Exception {
        ScriptedPgCatalog catalog = fixture();

        load(catalog, PARALLEL_WORKERS, cacheDir);

        assertAll(
                () -> assertEquals(0, catalog.getSavepointRollbackCount(),
                        "a clean load must not roll back to the probe boundary"),
                () -> assertTrue(
                        catalog.getSavepointOpenCount() <= catalog.getConnectionCount(),
                        "probe boundaries opened: " + catalog.getSavepointOpenCount()
                                + ", connections: " + catalog.getConnectionCount()),
                () -> assertEquals(1, catalog.getSavepointReleaseCount(),
                        "only the primary boundary is closed early, before the export"));
    }

    @Test
    void parallelStageBarrierAttachesContainerChildren() throws Exception {
        LoadResult parallel = load(fixture(), PARALLEL_WORKERS);

        assertAll(
                () -> assertTrue(parallel.dump().contains("CREATE TABLE app.t1"), parallel.dump()),
                () -> assertTrue(parallel.dump().contains("CREATE VIEW app.v1"), parallel.dump()),
                () -> assertTrue(parallel.dump().contains("CREATE TRIGGER trg1"),
                        "stage-2 trigger must find its table container: " + parallel.dump()));
    }

    @Test
    void rowCacheDoesNotDisableSharedSnapshotCatalogLanes(@TempDir Path cacheDir)
            throws Exception {
        ScriptedPgCatalog catalog = fixture();

        load(catalog, PARALLEL_WORKERS, cacheDir);

        // preLoad + primary + three worker lanes proves the combined selector
        // did not silently route this cache-enabled load through serial readers
        assertEquals(5, catalog.getConnectionCount());
    }

    @Test
    void parallelRowCacheColdAndWarmPreserveModelSqlAndCanonicalOrder(
            @TempDir Path cacheDir) throws Exception {
        LoadResult serial = load(fixture(), 0);
        ScriptedPgCatalog serialColdCatalog = fixture();
        LoadResult serialCold = load(serialColdCatalog, 0,
                cacheDir.resolve("serial"));
        ScriptedPgCatalog coldCatalog = fixture();
        Path parallelCache = cacheDir.resolve("parallel");
        LoadResult cold = load(coldCatalog, PARALLEL_WORKERS, parallelCache);
        ScriptedPgCatalog warmCatalog = fixture();
        LoadResult warm = load(warmCatalog, PARALLEL_WORKERS, parallelCache);
        ScriptedPgCatalog repeatedWarmCatalog = fixture();
        LoadResult repeatedWarm = load(repeatedWarmCatalog, PARALLEL_WORKERS,
                parallelCache);

        assertAll(
                () -> assertEquals(serial.database(), serialCold.database()),
                () -> assertEquals(serial.database(), cold.database()),
                () -> assertEquals(serial.database(), warm.database()),
                () -> assertEquals(warm.database(), repeatedWarm.database()),
                () -> assertArrayEquals(dumpBytes(serial), dumpBytes(serialCold)),
                () -> assertArrayEquals(dumpBytes(serial), dumpBytes(cold)),
                () -> assertArrayEquals(dumpBytes(serial), dumpBytes(warm)),
                () -> assertArrayEquals(dumpBytes(warm), dumpBytes(repeatedWarm)),
                () -> assertEquals(serial.launcherSequence(), cold.launcherSequence()),
                () -> assertEquals(serial.launcherSequence(), warm.launcherSequence()),
                () -> assertEquals(warm.launcherSequence(), repeatedWarm.launcherSequence()),
                () -> assertEquals(serial.columnDependencies(), cold.columnDependencies()),
                () -> assertEquals(serial.columnDependencies(), warm.columnDependencies()),
                () -> assertEquals(warm.columnDependencies(),
                        repeatedWarm.columnDependencies()),
                () -> assertTrue(countCacheQueries(serialColdCatalog, true) > 0,
                        "serial cold load must execute full hashed cache queries"),
                () -> assertTrue(countCacheQueries(coldCatalog, true) > 0,
                        "cold load must execute full hashed cache queries"),
                () -> assertTrue(countCacheQueries(warmCatalog, false) > 0,
                        "warm load must execute hash-only cache queries"),
                () -> assertEquals(0, countCacheQueries(warmCatalog, true),
                        "unchanged warm load must not transfer full catalog rows"),
                () -> assertEquals(5, coldCatalog.getConnectionCount()),
                () -> assertEquals(5, warmCatalog.getConnectionCount()),
                () -> assertEquals(5, repeatedWarmCatalog.getConnectionCount()));

        assertSnapshotImportedBeforeLaneCacheQueries(coldCatalog);
        assertSnapshotImportedBeforeLaneCacheQueries(warmCatalog);
        assertSnapshotImportedBeforeLaneCacheQueries(repeatedWarmCatalog);
    }

    @Test
    void changedTargetIdentityAlwaysStartsWithAColdRowCache(
            @TempDir Path cacheDir) throws Exception {
        String[][] changes = {
                {"database_name", "other_database"},
                {"database_oid", "24576"},
                {"system_identifier", "7504815387372040999"},
                {"session_user_name", "other_session_user"},
                {"current_role_name", "other_role"},
                {"server_address", "198.51.100.20"},
                {"server_port", "6432"},
                {"server_version_num", "170006"},
                {"timezone", "Europe/Moscow"},
                {"date_style", "SQL, DMY"},
                {"interval_style", "sql_standard"},
                {"extra_float_digits", "0"},
                {"bytea_output", "escape"}
        };

        for (String[] change : changes) {
            ScriptedPgCatalog catalog = fixture();
            catalog.setCacheIdentity(change[0], change[1]);

            load(catalog, 0, cacheDir);

            assertAll(change[0],
                    () -> assertEquals(1, catalog.getCacheIdentityQueryCount()),
                    () -> assertTrue(countCacheQueries(catalog, true) > 0,
                            "changed target must be cold"),
                    () -> assertEquals(0, countCacheQueries(catalog, false),
                            "changed target must not reuse another namespace"));
        }
    }

    @Test
    void passwordOnlyChangeKeepsTheSameWarmNamespace(@TempDir Path cacheDir)
            throws Exception {
        ScriptedPgCatalog coldCatalog = fixture("first-secret");
        load(coldCatalog, 0, cacheDir);
        ScriptedPgCatalog warmCatalog = fixture("second-secret");

        load(warmCatalog, 0, cacheDir);

        assertAll(
                () -> assertEquals(1, coldCatalog.getCacheIdentityQueryCount()),
                () -> assertEquals(1, warmCatalog.getCacheIdentityQueryCount()),
                () -> assertTrue(countCacheQueries(warmCatalog, false) > 0,
                        "password is not part of the target identity"),
                () -> assertEquals(0, countCacheQueries(warmCatalog, true),
                        "password-only changes must retain warm row entries"));
    }

    @Test
    void parallelRowCacheCancellationAfterConsumptionDoesNotFallBack(
            @TempDir Path cacheDir) throws Exception {
        ScriptedPgCatalog catalog = fixture();
        var monitor = new NullMonitor();
        catalog.cancelAfterFirstColdCacheRow(
                "FROM pg_catalog.pg_proc res", () -> monitor.setCancelled(true));
        CoreSettings settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgParallelCatalogReaders(PARALLEL_WORKERS);
        settings.setPgCatalogCacheDir(cacheDir.toString());
        settings.setPgCatalogCacheRows(true);
        settings.setMonitor(monitor);

        try (PgJdbcLoader loader = new PgJdbcLoader(catalog, "UTC", settings)) {
            assertThrows(InterruptedException.class, loader::load);
        }

        long coldFunctionQueries = catalog.getJdbcEvents().stream()
                .filter(event -> event.kind() == ScriptedPgCatalog.JdbcEventKind.QUERY)
                .map(ScriptedPgCatalog.JdbcEvent::sql)
                .filter(sql -> sql != null && sql.contains("FROM pg_catalog.pg_proc res"))
                .filter(sql -> sql.startsWith(COLD_CACHE_QUERY_PREFIX))
                .count();
        long plainFunctionQueries = catalog.getJdbcEvents().stream()
                .filter(event -> event.kind() == ScriptedPgCatalog.JdbcEventKind.QUERY)
                .map(ScriptedPgCatalog.JdbcEvent::sql)
                .filter(sql -> sql != null && sql.contains("FROM pg_catalog.pg_proc res"))
                .filter(sql -> !isCacheQuery(sql))
                .count();
        assertAll(
                () -> assertEquals(1, coldFunctionQueries),
                () -> assertEquals(0, plainFunctionQueries,
                        "a consumed cache reader must never restart on the plain path"));
    }

    @Test
    void snapshotExportFailureFallsBackToSerialFlow() throws Exception {
        LoadResult serial = load(fixture(), 0);
        ScriptedPgCatalog catalog = fixture();
        catalog.failSnapshotExport();

        LoadResult fallback = load(catalog, PARALLEL_WORKERS);

        assertAll(
                () -> assertEquals(serial.dump(), fallback.dump()),
                () -> assertEquals(serial.launcherSequence(), fallback.launcherSequence()),
                // preLoad + primary + the failed probe connection only
                () -> assertEquals(3, catalog.getConnectionCount()),
                // the failed probe must keep the primary transaction untouched
                () -> assertTrue(catalog.getExecutedScripts().stream()
                                .noneMatch(script -> script.contains("SET TRANSACTION SNAPSHOT")),
                        "no lane may synchronize after a failed probe: "
                                + catalog.getExecutedScripts()));
    }

    @Test
    void primarySnapshotExportFailureClosesTheProbedLane() throws Exception {
        ScriptedPgCatalog catalog = fixture();
        catalog.failPrimarySnapshotExport();
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgParallelCatalogReaders(PARALLEL_WORKERS);

        try (PgJdbcLoader loader =
                new PgJdbcLoader(catalog, "UTC", settings)) {
            assertThrows(java.io.IOException.class, loader::load);
        }

        assertEquals(3, catalog.getConnectionCount());
        assertEquals(catalog.getConnectionCount(),
                catalog.getConnectionCloseCount(),
                "preflight, primary and post-probe failure connections "
                        + "must all be closed");
    }

    @Test
    void cancellationDuringWorkerRegistrationPairsTelemetryAndClose()
            throws Exception {
        ScriptedPgCatalog catalog = fixture();
        var events = new ArrayList<PgConnectionLifecycleTelemetry>();
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgParallelCatalogReaders(PARALLEL_WORKERS);
        settings.setComparisonTelemetry(new IComparisonTelemetry() {
            @Override
            public void pgConnectionLifecycle(
                    PgConnectionLifecycleTelemetry event) {
                events.add(event);
            }
        });

        try (PgJdbcLoader loader =
                new CancelOnWorkerRegistrationLoader(catalog, settings)) {
            assertThrows(InterruptedException.class, loader::load);
        }

        assertEquals(catalog.getConnectionCount(),
                catalog.getConnectionCloseCount());
        assertEquals(List.of(
                "OPENED:CATALOG_LANE:1",
                "CLOSE_REQUESTED:CATALOG_LANE:1"),
                events.stream()
                        .filter(event -> event.role()
                                == PgConnectionRole.CATALOG_LANE)
                        .map(event -> event.lifecycle() + ":"
                                + event.role() + ":" + event.lane())
                        .toList());
    }

    @Test
    void snapshotProbeFailureFallsBackToSerialFlowWithRowCache(
            @TempDir Path cacheDir) throws Exception {
        LoadResult serial = load(fixture(), 0);
        ScriptedPgCatalog catalog = fixture();
        catalog.failSnapshotExport();

        LoadResult fallback = load(catalog, PARALLEL_WORKERS, cacheDir);

        assertAll(
                () -> assertEquals(serial.database(), fallback.database()),
                () -> assertEquals(serial.dump(), fallback.dump()),
                () -> assertEquals(serial.launcherSequence(), fallback.launcherSequence()),
                () -> assertTrue(countCacheQueries(catalog, true) > 0,
                        "serial fallback must keep the row cache active"),
                // preLoad + primary + the failed probe connection only
                () -> assertEquals(3, catalog.getConnectionCount()));
    }

    /**
     * The sequential fallback is a key remote-load signal, so both of its
     * causes must reach typed telemetry as a closed enum, never as text.
     */
    @Test
    void sequentialFallbackPublishesItsTypedReason() throws Exception {
        ScriptedPgCatalog probeFailure = fixture();
        probeFailure.failSnapshotExport();
        assertEquals(List.of(
                PgParallelCatalogFallbackReason.SNAPSHOT_PROBE_FAILED),
                loadCollectingFallbacks(probeFailure));

        ScriptedPgCatalog laneFailure = fixture();
        laneFailure.limitConnections(3);
        assertEquals(List.of(
                PgParallelCatalogFallbackReason.LANE_SETUP_FAILED),
                loadCollectingFallbacks(laneFailure));

        assertEquals(List.of(), loadCollectingFallbacks(fixture()));
    }

    private static List<PgParallelCatalogFallbackReason> loadCollectingFallbacks(
            ScriptedPgCatalog catalog) throws Exception {
        var reasons = new ArrayList<PgParallelCatalogFallbackReason>();
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgParallelCatalogReaders(PARALLEL_WORKERS);
        settings.setComparisonTelemetry(new IComparisonTelemetry() {
            @Override
            public void pgParallelCatalogFallback(
                    PgParallelCatalogFallbackReason reason) {
                reasons.add(reason);
            }
        });

        try (PgJdbcLoader loader = new PgJdbcLoader(catalog, "UTC", settings)) {
            loader.load();
        }
        return reasons;
    }

    @Test
    void workerConnectionFailureFallsBackToSerialFlow() throws Exception {
        LoadResult serial = load(fixture(), 0);

        // preLoad and the primary connection succeed, the probe worker fails
        ScriptedPgCatalog probeFailure = fixture();
        probeFailure.limitConnections(2);
        LoadResult probeFallback = load(probeFailure, PARALLEL_WORKERS);

        // the probe worker succeeds, a later worker connection fails
        ScriptedPgCatalog laneFailure = fixture();
        laneFailure.limitConnections(3);
        LoadResult laneFallback = load(laneFailure, PARALLEL_WORKERS);

        assertAll(
                () -> assertEquals(serial.dump(), probeFallback.dump()),
                () -> assertEquals(serial.launcherSequence(), probeFallback.launcherSequence()),
                () -> assertEquals(serial.dump(), laneFallback.dump()),
                () -> assertEquals(serial.launcherSequence(), laneFallback.launcherSequence()));
    }

    @Test
    void catalogFedViewAnalysisCompletesWithoutErrors() throws Exception {
        CoreSettings settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgParallelCatalogReaders(PARALLEL_WORKERS);

        try (PgJdbcLoader loader = new PgJdbcLoader(fixture(), "UTC", settings)) {
            PgDatabase db = loader.loadAndAnalyze();

            assertAll(
                    () -> assertTrue(db.getAnalysisLaunchers().isEmpty()),
                    () -> assertEquals(List.of(), settings.getErrors()));
        }
    }

    private record LoadResult(PgDatabase database, String dump, List<String> launcherSequence,
                              List<String> columnDependencies) {
    }

    private static LoadResult load(ScriptedPgCatalog catalog, int parallelReaders)
            throws Exception {
        return load(catalog, parallelReaders, null);
    }

    private static LoadResult load(ScriptedPgCatalog catalog, int parallelReaders, Path cacheDir)
            throws Exception {
        CoreSettings settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setPgParallelCatalogReaders(parallelReaders);
        if (cacheDir != null) {
            settings.setPgCatalogCacheDir(cacheDir.toString());
            settings.setPgCatalogCacheRows(true);
        }

        try (PgJdbcLoader loader = new PgJdbcLoader(catalog, "UTC", settings)) {
            PgDatabase db = loader.load();
            List<String> launcherSequence = db.getAnalysisLaunchers().stream()
                    .map(PgParallelCatalogReadersTest::describeLauncher)
                    .toList();
            List<String> columnDependencies = db.getSchema("app").getTable("t1")
                    .getColumn("c2").getDependencies().stream()
                    .map(Object::toString)
                    .sorted()
                    .toList();
            return new LoadResult(db, dump(db, settings), launcherSequence, columnDependencies);
        }
    }

    private static long countCacheQueries(ScriptedPgCatalog catalog, boolean fullRows) {
        String prefix = fullRows ? COLD_CACHE_QUERY_PREFIX : WARM_CACHE_QUERY_PREFIX;
        return catalog.getJdbcEvents().stream()
                .filter(event -> event.kind() == ScriptedPgCatalog.JdbcEventKind.QUERY)
                .map(ScriptedPgCatalog.JdbcEvent::sql)
                .filter(sql -> sql != null && sql.startsWith(prefix))
                .count();
    }

    private static byte[] dumpBytes(LoadResult result) {
        return result.dump().getBytes(StandardCharsets.UTF_8);
    }

    private static int eventIndex(List<ScriptedPgCatalog.JdbcEvent> events,
            java.util.function.Predicate<ScriptedPgCatalog.JdbcEvent> predicate) {
        for (int i = 0; i < events.size(); i++) {
            if (predicate.test(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isCacheQuery(String sql) {
        return sql.startsWith(COLD_CACHE_QUERY_PREFIX)
                || sql.startsWith(WARM_CACHE_QUERY_PREFIX);
    }

    private static void assertSnapshotImportedBeforeLaneCacheQueries(
            ScriptedPgCatalog catalog) {
        List<ScriptedPgCatalog.JdbcEvent> events = catalog.getJdbcEvents();
        List<ScriptedPgCatalog.JdbcEvent> imports = events.stream()
                .filter(event -> event.kind() == ScriptedPgCatalog.JdbcEventKind.SCRIPT)
                .filter(event -> event.sql() != null
                        && event.sql().contains("SET TRANSACTION SNAPSHOT"))
                .toList();
        Set<Integer> laneConnections = imports.stream()
                .map(ScriptedPgCatalog.JdbcEvent::connectionIdentity)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> snapshotScripts = imports.stream()
                .map(ScriptedPgCatalog.JdbcEvent::sql)
                .collect(java.util.stream.Collectors.toSet());

        assertAll(
                () -> assertEquals(PARALLEL_WORKERS, imports.size()),
                () -> assertEquals(PARALLEL_WORKERS, laneConnections.size()),
                () -> assertEquals(1, snapshotScripts.size()));

        int lastImport = -1;
        int firstLaneCacheQuery = Integer.MAX_VALUE;
        int laneCacheQueries = 0;
        for (int i = 0; i < events.size(); i++) {
            ScriptedPgCatalog.JdbcEvent event = events.get(i);
            if (imports.contains(event)) {
                lastImport = i;
            }
            if (event.kind() == ScriptedPgCatalog.JdbcEventKind.QUERY
                    && laneConnections.contains(event.connectionIdentity())
                    && event.sql() != null && isCacheQuery(event.sql())) {
                laneCacheQueries++;
                firstLaneCacheQuery = Math.min(firstLaneCacheQuery, i);
                assertTrue(event.snapshotImported(),
                        "lane cache query ran before importing its snapshot: " + event);
            }
        }
        assertTrue(laneCacheQueries > 0, "fixture must execute cache queries on worker lanes");
        assertTrue(lastImport < firstLaneCacheQuery,
                "all lane snapshots must be imported before cache readers are dispatched");
    }

    private static String describeLauncher(IAnalysisLauncher launcher) {
        return launcher.getClass().getSimpleName() + ':'
                + launcher.getStmt().getQualifiedName();
    }

    private static void assertDedicatedLaneSnapshot(
            ParserExecutionProbeHarness.Snapshot snapshot,
            boolean allWorkersBusy) {
        assertAll(
                () -> assertEquals(1, snapshot.scopes()),
                () -> assertEquals(1 + PARALLEL_WORKERS,
                        snapshot.queues()),
                () -> assertEquals(1, snapshot.lazyExecutors()),
                () -> assertTrue(snapshot.peakWorkers() >=
                        (allWorkersBusy ? 2 : 1)),
                () -> assertTrue(snapshot.peakWorkers() <= 2),
                () -> assertTrue(snapshot.workerNames().stream()
                        .allMatch(name -> name.startsWith(
                                "pgck-antlr-index-"))));
    }

    private static void assertClosedParserScope(
            ParserExecutionProbeHarness.Snapshot snapshot,
            Queue<AntlrTask<?>> rootTasks) {
        assertAll(
                () -> assertEquals(0, snapshot.liveWorkers()),
                () -> assertTrue(snapshot.shutdown()),
                () -> assertTrue(snapshot.terminated()),
                () -> assertThrows(IllegalStateException.class,
                        () -> AntlrTaskManager.submit(rootTasks,
                                () -> null, ignored -> { })));
    }

    private static String dump(PgDatabase db, CoreSettings settings) {
        SQLScript script = new SQLScript(settings, db.getSeparator());
        db.getDescendants().forEach(st -> st.getCreationSQL(script));
        return script.getFullScript();
    }

    private static final class ObservedPgJdbcLoader extends PgJdbcLoader {

        private ObservedPgJdbcLoader(ScriptedPgCatalog catalog,
                CoreSettings settings) {
            super(catalog, "UTC", settings);
        }

        private Queue<AntlrTask<?>> parserTasks() {
            return antlrTasks;
        }
    }

    private static final class CancelOnWorkerRegistrationLoader
            extends PgJdbcLoader {

        private boolean firstWorker = true;

        private CancelOnWorkerRegistrationLoader(
                ScriptedPgCatalog catalog, CoreSettings settings) {
            super(catalog, "UTC", settings);
        }

        @Override
        void registerWorkerConnection(Connection workerConnection)
                throws IOException, InterruptedException {
            if (firstWorker) {
                firstWorker = false;
                cancel();
            }
            super.registerWorkerConnection(workerConnection);
        }
    }

    private static final class LaneBlocker
            implements PgParallelCatalogReadersObserver {

        private final CountDownLatch tasksEntered;
        private final CountDownLatch drainsStarted;
        private final CountDownLatch tasksInterrupted;
        private final CountDownLatch release = new CountDownLatch(1);
        private final Queue<Queue<AntlrTask<?>>> laneQueues =
                new ConcurrentLinkedQueue<>();

        private LaneBlocker(int lanes, int expectedRunningTasks) {
            tasksEntered = new CountDownLatch(expectedRunningTasks);
            drainsStarted = new CountDownLatch(lanes);
            tasksInterrupted = new CountDownLatch(expectedRunningTasks);
        }

        @Override
        public void laneStarted(JdbcCatalogLane lane) {
            Queue<AntlrTask<?>> laneTasks = lane.getAntlrTasks();
            laneQueues.add(laneTasks);
            AntlrTaskManager.submit(laneTasks, () -> {
                tasksEntered.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    tasksInterrupted.countDown();
                    throw ex;
                }
                return null;
            }, ignored -> { });
        }

        @Override
        public void laneDrainStarted(JdbcCatalogLane lane) {
            drainsStarted.countDown();
        }

        private boolean awaitTasks(long timeout, TimeUnit unit)
                throws InterruptedException {
            return tasksEntered.await(timeout, unit);
        }

        private boolean awaitDrains(long timeout, TimeUnit unit)
                throws InterruptedException {
            return drainsStarted.await(timeout, unit);
        }

        private boolean awaitInterrupts(long timeout, TimeUnit unit)
                throws InterruptedException {
            return tasksInterrupted.await(timeout, unit);
        }

        private void release() {
            release.countDown();
        }
    }

    /**
     * A minimal but representative catalog: two functions (routine-body
     * launchers), a view (view launcher), a table with a column default
     * (vex launcher), and a trigger with a WHEN clause on that table
     * (stage-2 container dependency plus trigger launcher).
     */
    private static ScriptedPgCatalog fixture() {
        return fixture("fixture-password");
    }

    private static ScriptedPgCatalog fixture(String password) {
        ScriptedPgCatalog catalog = new ScriptedPgCatalog(password);

        catalog.on("version() AS version_string", List.of(row(
                "version_string", "PostgreSQL 16.4 (scripted)",
                "version_num", 160400)));
        catalog.on("pg_dbo_timestamp", List.of());
        catalog.on("has_table_privilege", List.of(row("result", true)));
        catalog.on("pg_export_snapshot", List.of(row("snapshot", "00000004-00000001-1")));
        catalog.on("has_schema_privilege", List.of());
        catalog.on("has_sequence_privilege", List.of());

        // must precede the type-cache rule: the types reader query also
        // matches the shorter pg_type fragment
        catalog.on("FROM pg_catalog.pg_type res", List.of());
        catalog.on("FROM pg_catalog.pg_type t", List.of(
                typeRow(16L, "bool", 0L, 1000L, "p", 0L),
                typeRow(1000L, "_bool", 16L, 0L, "x", 0L),
                typeRow(23L, "int4", 0L, 1007L, "p", 0L),
                typeRow(1007L, "_int4", 23L, 0L, "x", 0L),
                typeRow(25L, "text", 0L, 1009L, "x", 100L),
                typeRow(1009L, "_text", 25L, 0L, "x", 0L),
                typeRow(2278L, "void", 0L, 0L, "p", 0L),
                typeRow(2279L, "trigger", 0L, 0L, "p", 0L)));
        catalog.on("FROM pg_catalog.pg_collation c", List.of(
                row("oid", 100L, "collname", "default", "nspname", "pg_catalog"),
                row("oid", 90000L, "collname", "quoted Name", "nspname", "app")));

        catalog.on("FROM pg_catalog.pg_namespace res", List.of(row(
                "description", null, "oid", 100L, "nspname", "app")));

        // must precede the functions rule: both select FROM pg_catalog.pg_proc
        catalog.on("JOIN pg_catalog.pg_aggregate", List.of());
        catalog.on("FROM pg_catalog.pg_proc res", List.of(
                functionRow("fn_b", "begin perform 2; end"),
                functionRow("fn_a", "begin perform 1; end")));

        catalog.on("pg_get_viewdef", List.of(row(
                "description", null,
                "column_names", new String[] { "c1" },
                "column_comments", new String[] { null },
                "column_defaults", new String[] { null },
                "column_types", new String[] { "integer" },
                "relname", "v1",
                "kind", "v",
                "table_space", null,
                "definition", "SELECT t1.c1 FROM app.t1;",
                "reloptions", null,
                "access_method", null,
                "relispopulated", true,
                "relnamespace", 100L)));

        catalog.on("LEFT JOIN pg_catalog.pg_foreign_table ftbl", List.of(tableRow()));

        catalog.on("FROM pg_catalog.pg_trigger res", List.of(row(
                "description", null,
                "relname", "t1",
                "proname", "fn_a",
                "nspname", "app",
                "tgname", "trg1",
                "tgtype", 7,
                "tgenabled", "O",
                "tgargs", new byte[0],
                "tgconstraint", 0L,
                "cols", null,
                "has_when", true,
                "definition", "CREATE TRIGGER trg1 BEFORE INSERT ON app.t1 FOR EACH ROW "
                        + "WHEN (NEW.c1 > 0) EXECUTE FUNCTION app.fn_a();",
                "tgoldtable", null,
                "tgnewtable", null,
                "tgparentid", "0",
                "relnamespace", 100L)));

        catalog.on("FROM pg_catalog.pg_rewrite res", List.of());
        catalog.on("FROM pg_catalog.pg_policy res", List.of());
        catalog.on("pg_catalog.pg_index", List.of());
        catalog.on("FROM pg_catalog.pg_constraint res", List.of());
        catalog.on("FROM pg_catalog.pg_statistic_ext res", List.of());
        catalog.on("res.relkind = 'S'", List.of());
        catalog.on("FROM pg_catalog.pg_ts_parser res", List.of());
        catalog.on("FROM pg_catalog.pg_ts_template res", List.of());
        catalog.on("FROM pg_catalog.pg_ts_dict res", List.of());
        catalog.on("FROM pg_catalog.pg_ts_config res", List.of());
        catalog.on("FROM pg_catalog.pg_operator res", List.of());
        catalog.on("FROM pg_catalog.pg_extension res", List.of());
        catalog.on("FROM pg_catalog.pg_event_trigger res", List.of());
        catalog.on("FROM pg_catalog.pg_cast res", List.of());
        catalog.on("FROM pg_catalog.pg_foreign_data_wrapper res", List.of());
        catalog.on("FROM pg_catalog.pg_foreign_server res", List.of());
        catalog.on("FROM pg_catalog.pg_user_mapping res", List.of());
        catalog.on("FROM pg_catalog.pg_collation res", List.of(row(
                "description", null,
                "collname", "quoted Name",
                "collcollate", "en_US.UTF-8",
                "collctype", "en_US.UTF-8",
                "collprovider", "c",
                "collisdeterministic", true,
                "colliculocale", null,
                "collicurules", null,
                "collnamespace", 100L)));
        return catalog;
    }

    private static Map<String, Object> typeRow(long oid, String typname,
            long typelem, long typarray, String typstorage, long typcollation) {
        return row("oid", oid, "typname", typname, "typelem", typelem,
                "typarray", typarray, "typstorage", typstorage,
                "typcollation", typcollation, "nspname", "pg_catalog");
    }

    private static Map<String, Object> functionRow(String name, String body) {
        return row(
                "description", null,
                "proname", name,
                "proisagg", false,
                "proisproc", false,
                "proiswindow", false,
                "lang_name", "plpgsql",
                "prosrc", body,
                "probin", null,
                "prosqlbody", null,
                "provolatile", "v",
                "proleakproof", false,
                "proisstrict", false,
                "prosecdef", false,
                "procost", 100f,
                "prorows", 0f,
                "proconfig", null,
                "protrftypes", null,
                "proparallel", "u",
                "support_func", "-",
                "proallargtypes", null,
                "proargmodes", null,
                "proargnames", null,
                "argtypes", new Long[0],
                "prorettype", 2278L,
                "proretset", false,
                "default_values_as_string", null,
                "pronargs", 0,
                "pronamespace", 100L);
    }

    private static Map<String, Object> tableRow() {
        return row(
                "description", null,
                "col_names", new String[] { "c1", "c2" },
                "col_options", new String[] { null, null },
                "col_foptions", new String[] { null, null },
                "col_storages", new String[] { "p", "m" },
                "col_has_default", new Boolean[] { false, true },
                "col_defaults", new String[] { null, "'fixture'::text" },
                "col_comments", new String[] { null, null },
                "col_type_ids", new Long[] { 23L, 25L },
                "col_type_name", new String[] { "integer", "text" },
                "col_statistics", new Short[] { (short) -1, (short) -1 },
                "col_local", new Boolean[] { true, true },
                "col_collation", new Long[] { 0L, 90000L },
                "col_generated", new String[] { "", "" },
                "col_compression", new String[] { null, null },
                "col_notnull", new Boolean[] { true, false },
                "inhrelnames", null,
                "inhnspnames", null,
                "relname", "t1",
                "relkind", "r",
                "persistence", "p",
                "reloptions", null,
                "toast_reloptions", null,
                "table_space", null,
                "access_method", null,
                "ftoptions", null,
                "server_name", null,
                "of_type", 0L,
                "row_security", false,
                "force_security", false,
                "relispartition", false,
                "partition_by", null,
                "partition_bound", null,
                "relnamespace", 100L);
    }

    private static Map<String, Object> row(Object... keysAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return row;
    }
}
