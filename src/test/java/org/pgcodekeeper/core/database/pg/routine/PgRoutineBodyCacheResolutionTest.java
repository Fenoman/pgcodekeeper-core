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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.pg.jdbc.PgCatalogCacheNamespace;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.BodyType;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.utils.PgDiffUtils;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.telemetry.PgRoutineBodyCacheTelemetry;
import org.pgcodekeeper.core.utils.LogCapture;
import org.pgcodekeeper.core.utils.Utils;

/**
 * Persistent routine body cache pipeline tests: a cold run fetches from the
 * transport and stores entries, a warm run resolves every body locally with
 * zero residual JDBC batches, and the resulting bodies are byte-identical
 * across cache-off, cold and warm runs. Counter assertions live only in this
 * class, so the JVM-wide cache counters are never mutated concurrently.
 */
class PgRoutineBodyCacheResolutionTest {

    private static final RoutineBodyProfile PROFILE = RoutineBodyProfile.current(false);
    private static final RoutineBodyRepresentation REPRESENTATION =
            RoutineBodyRepresentation.PLPGSQL_TEXT;
    private static final long UNLIMITED_CACHE = 512L << 20;

    @TempDir
    private Path cacheDir;

    @BeforeEach
    void resetCounters() {
        PgRoutineBodyCache.resetCounters();
    }

    @Test
    void coldRunStoresBodiesAndWarmRunSkipsResidualFetchesEntirely() throws Exception {
        Map<Long, String> bodies = orderedBodies();
        // saved-bytes accounting follows the profile-normalized fingerprint
        long expectedBytes = bodies.values().stream()
                .mapToLong(raw -> raw.replace("\r", "")
                        .getBytes(StandardCharsets.UTF_8).length)
                .sum();

        var coldTransport = new RecordingTransport(bodies);
        List<RunResult> cold = resolveRun(coldTransport, bodies,
                new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE));

        assertEquals(List.of(List.of(11L, 12L), List.of(13L)), coldTransport.batches);
        assertEquals(0L, PgRoutineBodyCache.getHitCount());
        assertEquals(3L, PgRoutineBodyCache.getMissCount());
        assertEquals(3L, PgRoutineBodyCache.getStoreCount());
        assertEquals(0L, PgRoutineBodyCache.getSavedBytes());
        assertEquals(3L, cacheEntryCount());

        var warmTransport = new FailingTransport();
        List<RunResult> warm;
        try (LogCapture capture = LogCapture.start()) {
            warm = resolveRun(warmTransport, bodies,
                    new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE));

            assertLinePresent(capture,
                    "phase=jdbc_reader elapsed_ms=\\d+ detail=routine_cache");
            assertTrue(capture.messages().contains("routine_body_cache hits=3 "
                    + "misses=0 stored=0 bytes_saved=" + expectedBytes),
                    () -> "expected warm summary line, captured: " + capture.messages());
        }

        assertTrue(warmTransport.closed);
        assertEquals(3L, PgRoutineBodyCache.getHitCount());
        assertEquals(3L, PgRoutineBodyCache.getMissCount());
        assertEquals(3L, PgRoutineBodyCache.getStoreCount());
        assertEquals(expectedBytes, PgRoutineBodyCache.getSavedBytes());
        assertModelIdentical(cold, warm);
    }

    @Test
    void resolvedModelIsIdenticalAcrossCacheOffColdAndWarmRuns() throws Exception {
        Map<Long, String> bodies = orderedBodies();

        List<RunResult> cacheOff = resolveRun(
                new RecordingTransport(bodies), bodies, null);
        List<RunResult> cold = resolveRun(new RecordingTransport(bodies), bodies,
                new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE));
        List<RunResult> warm = resolveRun(new FailingTransport(), bodies,
                new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE));

        assertModelIdentical(cacheOff, cold);
        assertModelIdentical(cacheOff, warm);
    }

    @Test
    void differentTargetsNeverShareRoutineBodies() throws Exception {
        Map<Long, String> bodies = orderedBodies();
        Path targetA = targetDirectory(cacheDir, "target_a");
        Path targetB = targetDirectory(cacheDir, "target_b");

        var coldA = new RecordingTransport(bodies);
        List<RunResult> first = resolveRun(coldA, bodies,
                new PgRoutineBodyCache(targetA, UNLIMITED_CACHE));
        var coldB = new RecordingTransport(bodies);
        List<RunResult> second = resolveRun(coldB, bodies,
                new PgRoutineBodyCache(targetB, UNLIMITED_CACHE));
        List<RunResult> warmA = resolveRun(new FailingTransport(), bodies,
                new PgRoutineBodyCache(targetA, UNLIMITED_CACHE));

        assertAllTargetIsolation(coldA, coldB, first, second, warmA);
    }

    @Test
    void corruptCacheEntryFallsBackToTransportAndIsRepublished() throws Exception {
        Map<Long, String> bodies = orderedBodies();
        resolveRun(new RecordingTransport(bodies), bodies,
                new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE));
        Path victim = anyCacheEntry();
        Files.write(victim, "corrupted beyond repair".getBytes(StandardCharsets.UTF_8));
        PgRoutineBodyCache.resetCounters();

        var repairTransport = new RecordingTransport(bodies);
        List<RunResult> repaired = resolveRun(repairTransport, bodies,
                new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE));

        assertEquals(1, repairTransport.batches.size());
        assertEquals(1, repairTransport.batches.get(0).size());
        assertEquals(2L, PgRoutineBodyCache.getHitCount());
        assertEquals(1L, PgRoutineBodyCache.getMissCount());
        assertEquals(1L, PgRoutineBodyCache.getStoreCount());
        assertEquals(3L, cacheEntryCount());
        assertModelIdentical(resolveRun(new FailingTransport(), bodies,
                new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE)), repaired);
    }

    @Test
    void mixedCacheHitsPreserveResidualBatchOrderForTrueMisses() throws Exception {
        Map<Long, String> bodies = orderedBodies();
        String middleRaw = bodies.get(12L);
        var seedCache = new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE);
        seedCache.store(authorization(middleRaw), middleRaw);

        var transport = new RecordingTransport(bodies);
        List<RunResult> results = resolveRun(transport, bodies,
                new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE));

        // the cached middle slot leaves the surrounding residuals in
        // metadata order inside one batch
        assertEquals(List.of(List.of(11L, 13L)), transport.batches);
        assertEquals(1L, PgRoutineBodyCache.getHitCount());
        assertEquals(2L, PgRoutineBodyCache.getMissCount());
        assertEquals(bodies.values().stream().toList(),
                results.stream().map(RunResult::raw).toList());
    }

    @Test
    void projectMatchesAreNeverConsultedAgainstTheCache() throws Exception {
        String sharedRaw = "BEGIN RETURN; END";
        String residualRaw = "BEGIN RETURN 2; END";

        String sharedCanonical = canonical(sharedRaw);
        var projectDb = new PgDatabase();
        var projectSchema = new PgSchema("public");
        projectDb.addChild(projectSchema);
        PgFunction projectShared = attachedFunction(projectSchema, "shared");
        // hasBodyReference() compares String identity, so the routine body and
        // the exchange candidate must share one canonical instance
        projectShared.setBody(sharedCanonical);
        var projectSource = OwnedRoutineBodySource.exchangeCandidate(
                sharedRaw, sharedCanonical, PROFILE, REPRESENTATION);
        projectDb.addAnalysisLauncher(new PgFuncProcAnalysisLauncher(
                projectShared, projectSource, BodyType.PLPGSQL,
                "routine body", "test.sql", List.of(), true));

        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        channel.publish(ProjectRoutineBodyCatalog.build(projectDb));

        var transport = new RecordingTransport(Map.of(2L, residualRaw));
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(16, 1024L), channel,
                new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE));
        RoutineBodySource sharedSource = register(
                resolution, attachedFunction("shared"), 1L, 1L, sharedRaw);
        RoutineBodySource residualSource = register(
                resolution, attachedFunction("changed"), 2L, 2L, residualRaw);

        resolution.resolveAll(new NullMonitor());

        assertEquals(sharedRaw, sharedSource.take().raw());
        assertEquals(residualRaw, residualSource.take().raw());
        assertEquals(List.of(List.of(2L)), transport.batches);
        // the project-matched slot is neither a cache hit nor a miss
        assertEquals(0L, PgRoutineBodyCache.getHitCount());
        assertEquals(1L, PgRoutineBodyCache.getMissCount());
        assertEquals(1L, PgRoutineBodyCache.getStoreCount());
    }

    @Test
    void finishRunPrunesTheStoreDownToTheConfiguredCap() throws Exception {
        Map<Long, String> bodies = orderedBodies();

        resolveRun(new RecordingTransport(bodies), bodies,
                new PgRoutineBodyCache(cacheDir, 1L));

        assertEquals(0L, cacheEntryCount());

        PgRoutineBodyCache.resetCounters();
        var coldAgainTransport = new RecordingTransport(bodies);
        resolveRun(coldAgainTransport, bodies,
                new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE));
        assertEquals(0L, PgRoutineBodyCache.getHitCount());
        assertEquals(3L, PgRoutineBodyCache.getMissCount());
        assertFalse(coldAgainTransport.batches.isEmpty());
    }

    @Test
    void finishRunPublishesOneCompleteTelemetryEventAfterPruning() throws Exception {
        Map<Long, String> bodies = orderedBodies();
        String cachedRaw = bodies.get(12L);
        var seedCache = new PgRoutineBodyCache(cacheDir, UNLIMITED_CACHE);
        seedCache.store(authorization(cachedRaw), cachedRaw);

        var events = new ArrayList<PgRoutineBodyCacheTelemetry>();
        IComparisonTelemetry telemetry = new IComparisonTelemetry() {
            @Override
            public void pgRoutineBodyCacheFinished(PgRoutineBodyCacheTelemetry event) {
                events.add(event);
            }
        };
        PgRoutineBodyCache cache = telemetryCache(cacheDir, 1L, telemetry);

        resolveRun(new RecordingTransport(bodies), bodies, cache);
        cache.finishRun();

        assertEquals(1, events.size(), "finishRun must publish at most once");
        PgRoutineBodyCacheTelemetry event = events.get(0);
        assertEquals(1L, event.hits());
        assertEquals(2L, event.misses());
        assertEquals(2L, event.stored());
        assertEquals(cachedRaw.getBytes(StandardCharsets.UTF_8).length,
                event.savedUtf8Bytes());
        assertEquals(bodies.values().stream()
                        .map(raw -> raw.replace("\r", ""))
                        .mapToLong(raw -> raw.getBytes(StandardCharsets.UTF_8).length)
                        .sum(),
                event.prunedBytes());
        assertTrue(event.elapsedNanos() >= 0L);
    }

    @Test
    void throwingTelemetryCannotBreakRoutineBodyCacheCompletion() throws Exception {
        IComparisonTelemetry telemetry = new IComparisonTelemetry() {
            @Override
            public void pgRoutineBodyCacheFinished(PgRoutineBodyCacheTelemetry event) {
                throw new IllegalStateException("controlled telemetry failure");
            }
        };
        PgRoutineBodyCache cache = telemetryCache(
                cacheDir, UNLIMITED_CACHE, telemetry);

        assertDoesNotThrow(cache::finishRun);
        assertDoesNotThrow(cache::finishRun);
    }

    private static PgRoutineBodyCache telemetryCache(
            Path directory, long maxBytes, IComparisonTelemetry telemetry) {
        return new PgRoutineBodyCache(directory, maxBytes, telemetry);
    }

    private static Map<Long, String> orderedBodies() {
        var bodies = new LinkedHashMap<Long, String>();
        bodies.put(11L, "BEGIN\r\n  RETURN;\r\nEND");
        bodies.put(12L, "BEGIN RETURN; END");
        bodies.put(13L, "BEGIN RAISE NOTICE 'кэш каталога'; END");
        return bodies;
    }

    private List<RunResult> resolveRun(PgRoutineBodyResidualTransport transport,
                                       Map<Long, String> bodies,
                                       PgRoutineBodyCache cache) throws Exception {
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(2, Long.MAX_VALUE),
                null, cache);
        var functions = new ArrayList<PgFunction>();
        var sources = new ArrayList<RoutineBodySource>();
        long ordinal = 0;
        for (Map.Entry<Long, String> body : bodies.entrySet()) {
            PgFunction function = attachedFunction("routine_" + body.getKey());
            functions.add(function);
            sources.add(register(resolution, function, body.getKey(),
                    ++ordinal, body.getValue()));
        }

        resolution.resolveAll(new NullMonitor());

        var results = new ArrayList<RunResult>();
        for (int i = 0; i < sources.size(); i++) {
            RoutineBody body = sources.get(i).take();
            assertTrue(functions.get(i).hasBodyReference(body.canonical()));
            results.add(new RunResult(body.raw(), body.canonical()));
        }
        return results;
    }

    private static void assertModelIdentical(List<RunResult> expected,
                                             List<RunResult> actual) {
        // the cache persists the profile-normalized payload, so raw parse
        // inputs are equal modulo carriage returns while the canonical model
        // text must match exactly
        assertEquals(expected.stream().map(RunResult::canonical).toList(),
                actual.stream().map(RunResult::canonical).toList());
        assertEquals(
                expected.stream().map(result -> result.raw().replace("\r", "")).toList(),
                actual.stream().map(result -> result.raw().replace("\r", "")).toList());
        assertNotEquals(List.of(), expected);
    }

    private static RoutineBodyAuthorization authorization(String raw) {
        RoutineFingerprint fingerprint = (RoutineFingerprint) RoutineBody
                .create(raw, canonical(raw), PROFILE.keepNewLines()).measure();
        return new RoutineBodyAuthorization(PROFILE, REPRESENTATION, fingerprint);
    }

    private static Path targetDirectory(Path base, String databaseName) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getString(anyString())).thenAnswer(invocation -> switch (
                invocation.<String>getArgument(0)) {
            case "server_address" -> "192.0.2.10";
            case "server_port" -> "5432";
            case "database_name" -> databaseName;
            case "database_oid" -> "16384";
            case "system_identifier" -> "7504815387372040237";
            case "session_user_name" -> "session_user";
            case "current_role_name" -> "current_role";
            case "server_version_num" -> "170006";
            case "timezone" -> "UTC";
            case "date_style" -> "ISO, MDY";
            case "interval_style" -> "postgres";
            case "extra_float_digits" -> "1";
            case "bytea_output" -> "hex";
            case "quote_all_identifiers" -> "off";
            case "snapshot_token" -> "100:200:";
            default -> throw new AssertionError("Unexpected identity column");
        });
        return PgCatalogCacheNamespace.fromResultSet(result).resolveUnder(base);
    }

    private static void assertAllTargetIsolation(
            RecordingTransport coldA, RecordingTransport coldB,
            List<RunResult> first, List<RunResult> second, List<RunResult> warmA) {
        assertEquals(List.of(List.of(11L, 12L), List.of(13L)), coldA.batches);
        assertEquals(List.of(List.of(11L, 12L), List.of(13L)), coldB.batches,
                "target B must fetch instead of reusing target A bodies");
        assertModelIdentical(first, second);
        assertModelIdentical(first, warmA);
    }

    private long cacheEntryCount() throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(cacheDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .count();
        }
    }

    private Path anyCacheEntry() throws IOException {
        try (Stream<Path> walk = Files.walk(cacheDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .findFirst().orElseThrow();
        }
    }

    private static void assertLinePresent(LogCapture capture, String pattern) {
        assertFalse(capture.messages().stream()
                        .noneMatch(message -> message.matches(pattern)),
                () -> "expected a line matching <" + pattern
                        + ">, captured: " + capture.messages());
    }

    private static RoutineBodySource register(
            PgJdbcRoutineBodyResolution resolution, PgFunction function,
            long oid, long metadataOrdinal, String raw) {
        // the catalog computes the profile-normalized fingerprint server-side
        RoutineFingerprint fingerprint = (RoutineFingerprint) RoutineBody
                .create(raw, canonical(raw), PROFILE.keepNewLines()).measure();
        return resolution.registerFingerprint(function, oid, metadataOrdinal,
                fingerprint, PROFILE, REPRESENTATION);
    }

    private static PgFunction attachedFunction(String name) {
        var schema = new PgSchema("public");
        return attachedFunction(schema, name);
    }

    private static PgFunction attachedFunction(PgSchema schema, String name) {
        var function = new PgFunction(name);
        schema.addChild(function);
        return function;
    }

    private static String canonical(String raw) {
        return Utils.checkNewLines(PgDiffUtils.quoteStringDollar(raw), false);
    }

    private record RunResult(String raw, String canonical) {
    }

    private static final class RecordingTransport implements PgRoutineBodyResidualTransport {
        private final Map<Long, String> bodies;
        private final List<List<Long>> batches = new ArrayList<>();

        private RecordingTransport(Map<Long, String> bodies) {
            this.bodies = bodies;
        }

        @Override
        public void fetch(Long[] orderedOids, RowConsumer rows, IMonitor monitor)
                throws IOException, InterruptedException {
            batches.add(List.copyOf(Arrays.asList(orderedOids)));
            for (int i = 0; i < orderedOids.length; i++) {
                long oid = orderedOids[i];
                rows.accept(i + 1L, oid, bodies.get(oid));
            }
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    private static final class FailingTransport implements PgRoutineBodyResidualTransport {
        private boolean closed;

        @Override
        public void fetch(Long[] orderedOids, RowConsumer rows, IMonitor monitor)
                throws IOException {
            throw new IOException("Residual fetch must not run on a warm cache: "
                    + Arrays.toString(orderedOids));
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
