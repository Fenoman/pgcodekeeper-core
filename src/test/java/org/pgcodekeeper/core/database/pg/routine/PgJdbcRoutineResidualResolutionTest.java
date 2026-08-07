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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pgcodekeeper.core.database.base.parser.launcher.DeferredAnalysisStateException;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.BodyType;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.utils.PgDiffUtils;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.utils.Utils;

class PgJdbcRoutineResidualResolutionTest {

    private static final RoutineBodyProfile PROFILE = RoutineBodyProfile.current(false);
    private static final RoutineBodyRepresentation REPRESENTATION =
            RoutineBodyRepresentation.PLPGSQL_TEXT;

    @Test
    @Timeout(2)
    void emptyFingerprintResolutionDeclinesProjectCatalogWithoutWaiting()
            throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        var transport = new RecordingTransport(Map.of());
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(1, 1), channel);

        resolution.resolveAll(new NullMonitor());

        assertFalse(channel.publishIfOpen(new PgDatabase()));
        assertTrue(transport.batches.isEmpty());
        assertTrue(transport.closed);
        assertThrows(InterruptedException.class,
                () -> channel.take(new NullMonitor()));
    }

    @Test
    void boundedResidualsStreamInMetadataOrderAndRehashBeforePublication() throws Exception {
        String firstRaw = new String("BEGIN\r\n  RETURN;\r\nEND");
        String secondRaw = new String("BEGIN RETURN; END");
        String thirdRaw = new String("BEGIN RAISE NOTICE '$pgck$'; END");
        var bodies = new LinkedHashMap<Long, String>();
        bodies.put(11L, firstRaw);
        bodies.put(12L, secondRaw);
        bodies.put(13L, thirdRaw);
        var transport = new RecordingTransport(bodies);
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(2, Long.MAX_VALUE));
        PgFunction first = attachedFunction("first");
        PgFunction second = attachedFunction("second");
        PgFunction third = attachedFunction("third");

        RoutineBodySource firstSource = register(
                resolution, first, 11L, 101L, firstRaw);
        RoutineBodySource secondSource = register(
                resolution, second, 12L, 102L, secondRaw);
        RoutineBodySource thirdSource = register(
                resolution, third, 13L, 103L, thirdRaw);

        resolution.resolveAll(new NullMonitor());

        RoutineBody firstBody = firstSource.take();
        RoutineBody secondBody = secondSource.take();
        RoutineBody thirdBody = thirdSource.take();
        assertEquals(List.of(List.of(11L, 12L), List.of(13L)), transport.batches);
        assertSame(firstRaw, firstBody.raw());
        assertSame(secondRaw, secondBody.raw());
        assertSame(thirdRaw, thirdBody.raw());
        assertTrue(first.hasBodyReference(firstBody.canonical()));
        assertTrue(second.hasBodyReference(secondBody.canonical()));
        assertTrue(third.hasBodyReference(thirdBody.canonical()));
        assertEquals(Utils.checkNewLines(PgDiffUtils.quoteStringDollar(firstRaw), false),
                firstBody.canonical());
        assertTrue(transport.closed);
        assertEquals(1, transport.closeCalls);

        resolution.close();
        assertEquals(1, transport.closeCalls);
    }

    @Test
    void exactProjectCandidateIsSharedAndOnlyMismatchBecomesResidual() throws Exception {
        String sharedRaw = new String("BEGIN RETURN; END");
        String sharedCanonical = canonical(sharedRaw);
        String staleProjectRaw = "BEGIN RETURN 1; END";
        String staleProjectCanonical = canonical(staleProjectRaw);
        String serverResidualRaw = new String("BEGIN RETURN 2; END");

        var projectDb = new PgDatabase();
        var projectSchema = new PgSchema("public");
        projectDb.addChild(projectSchema);
        PgFunction projectShared = attachedFunction(projectSchema, "shared");
        PgFunction projectChanged = attachedFunction(projectSchema, "changed");
        projectShared.setBody(sharedCanonical);
        projectChanged.setBody(staleProjectCanonical);
        OwnedRoutineBodySource projectSharedSource = addProjectCandidate(
                projectDb, projectShared, sharedRaw, sharedCanonical);
        addProjectCandidate(
                projectDb, projectChanged, staleProjectRaw, staleProjectCanonical);

        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        channel.publish(ProjectRoutineBodyCatalog.build(projectDb));
        var transport = new RecordingTransport(Map.of(2L, serverResidualRaw));
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(16, 1024), channel);
        PgFunction jdbcShared = attachedFunction("shared");
        PgFunction jdbcChanged = attachedFunction("changed");
        RoutineBodySource sharedSource = register(
                resolution, jdbcShared, 1L, 1L, sharedRaw);
        RoutineBodySource residualSource = register(
                resolution, jdbcChanged, 2L, 2L, serverResidualRaw);

        resolution.resolveAll(new NullMonitor());

        RoutineBody shared = sharedSource.take();
        assertSame(shared, projectSharedSource.take());
        assertSame(sharedRaw, shared.raw());
        assertSame(sharedCanonical, shared.canonical());
        assertTrue(jdbcShared.hasBodyReference(sharedCanonical));
        RoutineBody residual = residualSource.take();
        assertSame(serverResidualRaw, residual.raw());
        assertEquals(canonical(serverResidualRaw), residual.canonical());
        assertTrue(jdbcChanged.hasBodyReference(residual.canonical()));
        assertEquals(List.of(List.of(2L)), transport.batches);
        assertTrue(transport.closed);
    }

    @Test
    void crlfOnlyDriftMatchesInsteadOfFetchingOrDiverging() throws Exception {
        // project file saved with CRLF, server body stored with LF: the
        // canonical forms are identical, so the normalized fingerprints must
        // match and the pair must resolve as unchanged without any fetch
        String projectRaw = "BEGIN\r\n RETURN;\r\nEND";
        String serverRaw = new String("BEGIN\n RETURN;\nEND");
        String projectCanonical = canonical(projectRaw);

        var projectDb = new PgDatabase();
        var projectSchema = new PgSchema("public");
        projectDb.addChild(projectSchema);
        PgFunction projectFunction = attachedFunction(projectSchema, "crlf_same");
        projectFunction.setBody(projectCanonical);
        OwnedRoutineBodySource projectSource = addProjectCandidate(
                projectDb, projectFunction, projectRaw, projectCanonical);

        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        channel.publish(ProjectRoutineBodyCatalog.build(projectDb));
        var transport = new RecordingTransport(Map.of());
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(16, 1024), channel, null,
                new PgRoutineBodyDivergencePolicy(true, true));
        PgFunction jdbcFunction = attachedFunction("crlf_same");
        RoutineBodySource source = register(
                resolution, jdbcFunction, 9L, 9L, serverRaw);

        resolution.resolveAll(new NullMonitor());

        assertTrue(transport.batches.isEmpty(),
                "a CR-only difference is canonical equality and must not fetch");
        RoutineBody shared = source.take();
        assertSame(shared, projectSource.take(),
                "the pair must share the exact project payload");
        assertEquals(projectCanonical, bodyOf(jdbcFunction));
        assertFalse(((DeferredRoutineBodySource) source).isDivergent());
    }

    @Test
    void divergencePolicySkipsUnmatchedEligibleFetchesEntirely() throws Exception {
        String sharedRaw = new String("BEGIN RETURN; END");
        String sharedCanonical = canonical(sharedRaw);
        String staleProjectRaw = "BEGIN RETURN 1; END";
        String serverChangedRaw = new String("BEGIN RETURN 2; END");
        String serverOnlyRaw = new String("BEGIN RETURN 3; END");

        var projectDb = new PgDatabase();
        var projectSchema = new PgSchema("public");
        projectDb.addChild(projectSchema);
        PgFunction projectShared = attachedFunction(projectSchema, "shared");
        PgFunction projectChanged = attachedFunction(projectSchema, "changed");
        projectShared.setBody(sharedCanonical);
        projectChanged.setBody(canonical(staleProjectRaw));
        addProjectCandidate(projectDb, projectShared, sharedRaw, sharedCanonical);
        addProjectCandidate(projectDb, projectChanged,
                staleProjectRaw, canonical(staleProjectRaw));

        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        channel.publish(ProjectRoutineBodyCatalog.build(projectDb));
        var transport = new RecordingTransport(Map.of());
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(16, 1024), channel, null,
                new PgRoutineBodyDivergencePolicy(true, false));
        PgFunction jdbcShared = attachedFunction("shared");
        PgFunction jdbcChanged = attachedFunction("changed");
        PgFunction jdbcServerOnly = attachedFunction("server_only");
        RoutineBodySource sharedSource = register(
                resolution, jdbcShared, 1L, 1L, sharedRaw);
        RoutineBodySource changedSource = register(
                resolution, jdbcChanged, 2L, 2L, serverChangedRaw);
        RoutineBodySource serverOnlySource = register(
                resolution, jdbcServerOnly, 3L, 3L, serverOnlyRaw);

        resolution.resolveAll(new NullMonitor());

        String changedSentinel = PgRoutineBodyDivergencePolicy.divergentSentinel(
                fingerprintOf(serverChangedRaw));
        String serverOnlySentinel = PgRoutineBodyDivergencePolicy.divergentSentinel(
                fingerprintOf(serverOnlyRaw));
        assertTrue(transport.batches.isEmpty(),
                () -> "no residual fetch may happen when every unmatched slot diverges: "
                        + transport.batches);
        assertSame(sharedRaw, sharedSource.take().raw());
        assertEquals(changedSentinel, bodyOf(jdbcChanged),
                "unmatched slot must resolve to its divergent sentinel");
        assertEquals(serverOnlySentinel, bodyOf(jdbcServerOnly));
        assertNotEquals(bodyOf(jdbcChanged), bodyOf(jdbcServerOnly),
                "different bodies must produce different sentinels");
        assertThrows(DeferredAnalysisStateException.class, changedSource::take);
        assertThrows(DeferredAnalysisStateException.class, serverOnlySource::take);
        assertTrue(((DeferredRoutineBodySource) changedSource).isDivergent());
    }

    @Test
    void divergencePolicyKeepsFetchingIneligibleRepresentations() throws Exception {
        String sqlRaw = new String("SELECT 1");
        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        channel.publish(ProjectRoutineBodyCatalog.build(new PgDatabase()));
        var transport = new RecordingTransport(Map.of(5L, sqlRaw));
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(16, 1024), channel, null,
                new PgRoutineBodyDivergencePolicy(true, false));
        PgFunction sqlFunction = attachedFunction("sql_kept");
        String canonical = canonical(sqlRaw);
        RoutineFingerprint fingerprint = (RoutineFingerprint) RoutineBody
                .create(sqlRaw, canonical).measure();
        RoutineBodySource sqlSource = resolution.registerFingerprint(
                sqlFunction, 5L, 5L, fingerprint, PROFILE,
                RoutineBodyRepresentation.SQL_TEXT);

        resolution.resolveAll(new NullMonitor());

        assertEquals(List.of(List.of(5L)), transport.batches,
                "sql bodies stay fetched while quoted sql is not late-bound");
        assertSame(sqlRaw, sqlSource.take().raw());
    }

    @Test
    void ambiguousProjectIdentityBlocksDivergenceAndStillFetches() throws Exception {
        String raw = new String("BEGIN RETURN; END");
        String canonical = canonical(raw);

        var projectDb = new PgDatabase();
        var projectSchema = new PgSchema("public");
        projectDb.addChild(projectSchema);
        PgFunction duplicated = attachedFunction(projectSchema, "duplicated");
        duplicated.setBody(canonical);
        // two candidates for one identity: the catalog keeps only ambiguity
        addProjectCandidate(projectDb, duplicated, raw, canonical);
        addProjectCandidate(projectDb, duplicated, raw, canonical);

        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        channel.publish(ProjectRoutineBodyCatalog.build(projectDb));
        var transport = new RecordingTransport(Map.of(7L, raw));
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(16, 1024), channel, null,
                new PgRoutineBodyDivergencePolicy(true, true));
        PgFunction jdbcDuplicated = attachedFunction("duplicated");
        RoutineBodySource source = register(resolution, jdbcDuplicated, 7L, 7L, raw);

        resolution.resolveAll(new NullMonitor());

        assertEquals(List.of(List.of(7L)), transport.batches,
                "an ambiguous identity has no fingerprint verdict and must fetch");
        assertSame(raw, source.take().raw());
        assertEquals(canonical, bodyOf(jdbcDuplicated));
    }

    @Test
    void oversizedResidualRunsAlone() throws Exception {
        String small = "x";
        String oversized = "0123456789";
        var bodies = new LinkedHashMap<Long, String>();
        bodies.put(1L, small);
        bodies.put(2L, oversized);
        bodies.put(3L, small);
        var transport = new RecordingTransport(bodies);
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(10, 4));
        register(resolution, attachedFunction("small_before"), 1L, 1L, small);
        register(resolution, attachedFunction("oversized"), 2L, 2L, oversized);
        register(resolution, attachedFunction("small_after"), 3L, 3L, small);

        resolution.resolveAll(new NullMonitor());

        assertEquals(List.of(List.of(1L), List.of(2L), List.of(3L)), transport.batches);
    }

    @Test
    void protocolViolationsAndHashMismatchFailClosed() {
        assertProtocolFailure((oids, rows, monitor) -> {
            // missing row
        });
        assertProtocolFailure((oids, rows, monitor) ->
                rows.accept(2L, oids[0], "SELECT 1"));
        assertProtocolFailure((oids, rows, monitor) ->
                rows.accept(1L, oids[0] + 1L, "SELECT 1"));
        assertProtocolFailure((oids, rows, monitor) -> {
            rows.accept(1L, oids[0], "SELECT 1");
            rows.accept(2L, oids[0], "SELECT 1");
        });
        assertProtocolFailure((oids, rows, monitor) ->
                rows.accept(1L, oids[0], null));
        assertProtocolFailure((oids, rows, monitor) ->
                rows.accept(1L, oids[0], "SELECT 2"));
    }

    @Test
    void closeBeforeResolutionClosesTransportAndPendingSource() throws Exception {
        var transport = new RecordingTransport(Map.of(1L, "SELECT 1"));
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(1, 1));
        RoutineBodySource source = register(
                resolution, attachedFunction("pending"), 1L, 1L, "SELECT 1");

        resolution.close();
        resolution.close();

        assertTrue(transport.closed);
        assertEquals(1, transport.closeCalls);
        assertThrows(DeferredAnalysisStateException.class, source::take);
    }

    @Test
    void transportFailureRemainsPrimaryAndCloseFailureIsSuppressed() {
        IOException primary = new IOException("controlled fetch failure");
        IOException closeFailure = new IOException("controlled close failure");
        PgRoutineBodyResidualTransport transport = new PgRoutineBodyResidualTransport() {
            @Override
            public void fetch(Long[] orderedOids, RowConsumer rows, IMonitor monitor)
                    throws IOException {
                throw primary;
            }

            @Override
            public void close() throws IOException {
                throw closeFailure;
            }
        };
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(1, 1024));
        register(resolution, attachedFunction("failure"), 1L, 1L, "SELECT 1");

        IOException thrown = assertThrows(IOException.class,
                () -> resolution.resolveAll(new NullMonitor()));

        assertSame(primary, thrown);
        assertEquals(List.of(closeFailure), Arrays.asList(thrown.getSuppressed()));
    }

    @Test
    void closeFailureAfterSuccessfulFetchInvalidatesResolvedSource() {
        String raw = "SELECT 1";
        IOException closeFailure = new IOException("controlled terminal close failure");
        PgRoutineBodyResidualTransport transport = new PgRoutineBodyResidualTransport() {
            @Override
            public void fetch(Long[] orderedOids, RowConsumer rows, IMonitor monitor)
                    throws IOException, InterruptedException {
                rows.accept(1L, orderedOids[0], raw);
            }

            @Override
            public void close() throws IOException {
                throw closeFailure;
            }
        };
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(1, 1024));
        RoutineBodySource source = register(
                resolution, attachedFunction("close_failure"), 1L, 1L, raw);

        IOException thrown = assertThrows(IOException.class,
                () -> resolution.resolveAll(new NullMonitor()));

        assertSame(closeFailure, thrown);
        assertThrows(DeferredAnalysisStateException.class, source::take);
    }

    @Test
    void identicalOperationAndCloseFailureIsNeverSelfSuppressed() {
        IOException shared = new IOException("controlled shared failure");
        PgRoutineBodyResidualTransport transport = new PgRoutineBodyResidualTransport() {
            @Override
            public void fetch(Long[] orderedOids, RowConsumer rows, IMonitor monitor)
                    throws IOException {
                throw shared;
            }

            @Override
            public void close() throws IOException {
                throw shared;
            }
        };
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(1, 1024));
        register(resolution, attachedFunction("shared_failure"), 1L, 1L, "SELECT 1");

        IOException thrown = assertThrows(IOException.class,
                () -> resolution.resolveAll(new NullMonitor()));

        assertSame(shared, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void interruptDuringResidualStreamFailsClosedAndClosesTransportOnce() {
        Thread.interrupted();
        String firstRaw = "SELECT 1";
        String secondRaw = "SELECT 2";
        int[] closeCalls = { 0 };
        PgRoutineBodyResidualTransport transport = new PgRoutineBodyResidualTransport() {
            @Override
            public void fetch(Long[] orderedOids, RowConsumer rows, IMonitor monitor)
                    throws IOException, InterruptedException {
                rows.accept(1L, orderedOids[0], firstRaw);
                Thread.currentThread().interrupt();
                rows.accept(2L, orderedOids[1], secondRaw);
            }

            @Override
            public void close() {
                closeCalls[0]++;
            }
        };
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(2, 1024));
        RoutineBodySource firstSource = register(
                resolution, attachedFunction("interrupt_first"), 1L, 1L, firstRaw);
        RoutineBodySource secondSource = register(
                resolution, attachedFunction("interrupt_second"), 2L, 2L, secondRaw);

        try {
            assertThrows(InterruptedException.class,
                    () -> resolution.resolveAll(new NullMonitor()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertThrows(DeferredAnalysisStateException.class, firstSource::take);
            assertThrows(DeferredAnalysisStateException.class, secondSource::take);
            assertEquals(1, closeCalls[0]);
            resolution.close();
            assertEquals(1, closeCalls[0]);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationDuringTransportCloseFailsClosedBeforeSourceRelease() {
        Thread.interrupted();
        String raw = "SELECT 1";
        var monitor = new NullMonitor();
        int[] closeCalls = { 0 };
        PgRoutineBodyResidualTransport transport = new PgRoutineBodyResidualTransport() {
            @Override
            public void fetch(Long[] orderedOids, RowConsumer rows, IMonitor ignored)
                    throws IOException, InterruptedException {
                rows.accept(1L, orderedOids[0], raw);
            }

            @Override
            public void close() {
                closeCalls[0]++;
                monitor.setCancelled(true);
            }
        };
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(1, 1024));
        RoutineBodySource source = register(
                resolution, attachedFunction("cancel_during_close"), 1L, 1L, raw);

        try {
            assertThrows(InterruptedException.class,
                    () -> resolution.resolveAll(monitor));
            assertTrue(Thread.currentThread().isInterrupted());
            assertThrows(DeferredAnalysisStateException.class, source::take);
            assertEquals(1, closeCalls[0]);
        } finally {
            Thread.interrupted();
        }
    }

    private static void assertProtocolFailure(FetchBehavior behavior) {
        PgRoutineBodyResidualTransport transport = new PgRoutineBodyResidualTransport() {
            @Override
            public void fetch(Long[] orderedOids, RowConsumer rows, IMonitor monitor)
                    throws IOException, InterruptedException {
                behavior.fetch(orderedOids, rows, monitor);
            }

            @Override
            public void close() {
                // no-op
            }
        };
        var resolution = PgJdbcRoutineBodyResolution.fingerprint(
                transport, new PgRoutineBodyBatchLimits(1, 1024));
        RoutineBodySource source = register(
                resolution, attachedFunction("protocol"), 7L, 77L, "SELECT 1");

        assertThrows(IOException.class, () -> resolution.resolveAll(new NullMonitor()));
        assertThrows(DeferredAnalysisStateException.class, source::take);
    }

    private static RoutineBodySource register(
            PgJdbcRoutineBodyResolution resolution, PgFunction function,
            long oid, long metadataOrdinal, String raw) {
        String canonical = Utils.checkNewLines(PgDiffUtils.quoteStringDollar(raw), false);
        // the catalog computes the profile-normalized fingerprint server-side
        RoutineFingerprint fingerprint = (RoutineFingerprint) RoutineBody
                .create(raw, canonical, PROFILE.keepNewLines()).measure();
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

    private static OwnedRoutineBodySource addProjectCandidate(
            PgDatabase database, PgFunction function, String raw, String canonical) {
        var source = OwnedRoutineBodySource.exchangeCandidate(
                raw, canonical, PROFILE, REPRESENTATION);
        database.addAnalysisLauncher(new PgFuncProcAnalysisLauncher(
                function, source, BodyType.PLPGSQL,
                "routine body", "test.sql", List.of(), true));
        return source;
    }

    private static String canonical(String raw) {
        return Utils.checkNewLines(PgDiffUtils.quoteStringDollar(raw), false);
    }

    private static RoutineFingerprint fingerprintOf(String raw) {
        return (RoutineFingerprint) RoutineBody
                .create(raw, canonical(raw), PROFILE.keepNewLines()).measure();
    }

    /** Reads the model body of a routine; the schema model exposes no getter. */
    private static String bodyOf(PgFunction function) {
        try {
            var field = org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction.class
                    .getDeclaredField("body");
            field.setAccessible(true);
            return (String) field.get(function);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    @FunctionalInterface
    private interface FetchBehavior {
        void fetch(Long[] oids, PgRoutineBodyResidualTransport.RowConsumer rows,
                   IMonitor monitor) throws IOException, InterruptedException;
    }

    private static final class RecordingTransport implements PgRoutineBodyResidualTransport {
        private final Map<Long, String> bodies;
        private final List<List<Long>> batches = new ArrayList<>();
        private boolean closed;
        private int closeCalls;

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
            closeCalls++;
            closed = true;
        }
    }
}
