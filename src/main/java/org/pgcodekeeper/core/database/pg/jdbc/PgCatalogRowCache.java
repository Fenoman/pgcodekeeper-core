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

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.pgcodekeeper.core.database.base.jdbc.AbstractJdbcReader;
import org.pgcodekeeper.core.database.base.jdbc.ICatalogRowCache;
import org.pgcodekeeper.core.database.pg.jdbc.PgCatalogRowCodec.UnsupportedRowValueException;
import org.pgcodekeeper.core.database.pg.jdbc.PgPackedCatalogHashIndex.DuplicateHashException;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.exception.XmlReaderException;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.telemetry.ComparisonTelemetryPublisher;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.telemetry.PgCatalogCacheBypassReason;
import org.pgcodekeeper.core.telemetry.PgCatalogCacheMode;
import org.pgcodekeeper.core.telemetry.PgCatalogCacheRunTelemetry;
import org.pgcodekeeper.core.telemetry.PgCatalogReaderCacheTelemetry;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent PostgreSQL catalog row cache backed by immutable per-reader
 * packs. Labels are stored once, row hashes remain binary, and warm changed
 * reads fetch only missing rows while replaying exact server order.
 */
public final class PgCatalogRowCache implements ICatalogRowCache {

    private static final Logger LOG = LoggerFactory.getLogger(PgCatalogRowCache.class);

    private static final String ROW_ALIAS = "__pgck_r";
    private static final String HASH_COLUMN = "__pgck_h";
    private static final String HASH_COUNT_COLUMN = "__pgck_n";
    private static final String HASH_ORDINAL_COLUMN = "__pgck_o";
    private static final String HASH_CHUNK_COLUMN = "__pgck_c";
    private static final String HASH_TOTAL_COLUMN = "__pgck_t";
    private static final String HASH_ROWS_ALIAS = "__pgck_hashes";
    private static final String HASH_INPUT_ALIAS = "__pgck_input";
    private static final String WANTED_ALIAS = "__pgck_wanted";
    private static final String FINGERPRINT_ALIAS = "__pgck_fp";
    private static final String FINGERPRINT_COLUMN = "__pgck_f";
    /** Chunk ordinal of the leading summary row; every chunk ordinal is >= 0. */
    static final long SUMMARY_CHUNK_ORDINAL = -1L;
    static final int HASHES_PER_CHUNK = 4096;
    static final int HASH_CHUNK_BYTES =
            HASHES_PER_CHUNK * PgPackedCatalogHashes.MD5_BYTES;
    static final int MAX_HASH_ROWS =
            PgCatalogReaderPackFormat.MAX_RESIDENT_INDEX_ROWS;
    /** Caps one binary miss request at 160,000 bytes. */
    private static final int MAX_MISS_FETCH_ROWS = 10_000;
    private static final int ROW_MAX_BYTES = 32 << 20;

    private static final AtomicLong HIT_COUNT = new AtomicLong();
    private static final AtomicLong MISS_COUNT = new AtomicLong();
    private static final AtomicLong STORE_COUNT = new AtomicLong();
    private static final AtomicLong BYPASSED_READER_COUNT = new AtomicLong();

    private final PgJdbcLoader loader;
    private final PgCatalogReaderPackStore packStore;
    private final PgCatalogCacheNamespace namespace;
    private final long maxBytes;
    private final int maxRowPayloadBytes;
    private final boolean fingerprintProbeEnabled;
    private final byte[] snapshotDigest;
    private final IComparisonTelemetry telemetry;
    private final long runStartedNanos = System.nanoTime();
    private final AtomicBoolean runFinished = new AtomicBoolean();

    private final LongAdder runReaders = new LongAdder();
    private final LongAdder runRows = new LongAdder();
    private final LongAdder runHits = new LongAdder();
    private final LongAdder runMisses = new LongAdder();
    private final LongAdder runFetched = new LongAdder();
    private final LongAdder runStored = new LongAdder();
    private final LongAdder runBypassedReaders = new LongAdder();
    private final LongAdder runHashPayloadBytes = new LongAdder();
    private final LongAdder runEncodedRowBytes = new LongAdder();
    private final LongAdder runPackBytesRead = new LongAdder();
    private final LongAdder runPackBytesWritten = new LongAdder();

    public PgCatalogRowCache(PgJdbcLoader loader, Path directory, long maxBytes,
            PgCatalogCacheNamespace namespace) {
        this(loader, directory, maxBytes, ROW_MAX_BYTES, namespace, false,
                null, IComparisonTelemetry.NO_OP);
    }

    public PgCatalogRowCache(PgJdbcLoader loader, Path directory, long maxBytes,
            PgCatalogCacheNamespace namespace, boolean fingerprintProbeEnabled,
            IComparisonTelemetry telemetry) {
        this(loader, directory, maxBytes, ROW_MAX_BYTES, namespace,
                fingerprintProbeEnabled, null, telemetry);
    }

    public PgCatalogRowCache(PgJdbcLoader loader, Path directory, long maxBytes,
            PgCatalogCacheNamespace namespace, boolean fingerprintProbeEnabled,
            byte[] snapshotDigest, IComparisonTelemetry telemetry) {
        this(loader, directory, maxBytes, ROW_MAX_BYTES, namespace,
                fingerprintProbeEnabled, snapshotDigest, telemetry);
    }

    PgCatalogRowCache(PgJdbcLoader loader, Path directory, long maxBytes,
            int maxRowPayloadBytes, PgCatalogCacheNamespace namespace) {
        this(loader, directory, maxBytes, maxRowPayloadBytes, namespace, false,
                null, IComparisonTelemetry.NO_OP);
    }

    PgCatalogRowCache(PgJdbcLoader loader, Path directory, long maxBytes,
            int maxRowPayloadBytes, PgCatalogCacheNamespace namespace,
            boolean fingerprintProbeEnabled, byte[] snapshotDigest,
            IComparisonTelemetry telemetry) {
        this.loader = Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(directory, "directory");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException(
                    "PostgreSQL catalog cache size limit must be positive");
        }
        if (maxRowPayloadBytes <= 0
                || maxRowPayloadBytes > PgCatalogReaderPackFormat.MAX_VALUES_BYTES) {
            throw new IllegalArgumentException(
                    "PostgreSQL catalog row payload limit is invalid");
        }
        this.maxBytes = maxBytes;
        this.maxRowPayloadBytes = maxRowPayloadBytes;
        this.fingerprintProbeEnabled = fingerprintProbeEnabled;
        if (snapshotDigest != null
                && snapshotDigest.length
                        != PgCatalogReaderPackGeneration.SNAPSHOT_DIGEST_BYTES) {
            throw new IllegalArgumentException("Invalid catalog snapshot digest");
        }
        this.snapshotDigest = snapshotDigest == null
                ? null : snapshotDigest.clone();
        this.packStore = new PgCatalogReaderPackStore(directory);
    }

    @Override
    public boolean read(String readerName, String query,
            CatalogRowConsumer consumer, CatalogQueryParameterSetter paramSetter)
            throws SQLException, InterruptedException, XmlReaderException {
        return read(readerName, query, CatalogQueryOrder.UNSPECIFIED,
                consumer, paramSetter);
    }

    @Override
    public boolean read(String readerName, String query,
            CatalogQueryOrder queryOrder, CatalogRowConsumer consumer,
            CatalogQueryParameterSetter paramSetter)
            throws SQLException, InterruptedException, XmlReaderException {
        var run = new ReaderRun(readerName, query,
                Objects.requireNonNull(queryOrder, "queryOrder"), consumer,
                paramSetter);
        try {
            boolean handled = run.execute();
            run.releaseCacheSavepoint();
            return handled;
        } catch (InterruptedException | XmlReaderException | Error ex) {
            throw ex;
        } catch (SQLException | RuntimeException ex) {
            if (run.consumedRows > 0) {
                throw ex;
            }
            try {
                run.rollbackCacheSavepoint();
            } catch (SQLException | RuntimeException | Error recoveryFailure) {
                if (recoveryFailure != ex) {
                    ex.addSuppressed(recoveryFailure);
                }
                throw ex;
            }
            LOG.debug("Catalog row cache disengaged for {} before any row was consumed",
                    readerName, ex);
            run.bypassReason = PgCatalogCacheBypassReason.CACHE_ERROR;
            run.finish(false);
            return false;
        }
    }

    /** Emits one aggregate event and enforces the shared cache size cap. */
    public void finishRun() {
        if (!runFinished.compareAndSet(false, true)) {
            return;
        }
        long pruned = packStore.pruneToLimit(maxBytes);
        long elapsed = Math.max(0L, System.nanoTime() - runStartedNanos);
        LOG.debug("catalog_row_cache_summary readers={} hits={} misses={} stored={} "
                        + "bypassed_readers={} pack_read_bytes={} pack_written_bytes={} "
                        + "pruned_bytes={}",
                runReaders.sum(), runHits.sum(), runMisses.sum(), runStored.sum(),
                runBypassedReaders.sum(), runPackBytesRead.sum(),
                runPackBytesWritten.sum(), pruned);
        ComparisonTelemetryPublisher.publishCatalogRun(telemetry,
                new PgCatalogCacheRunTelemetry(runReaders.sum(),
                        runBypassedReaders.sum(), runRows.sum(), runHits.sum(),
                        runMisses.sum(), runFetched.sum(), runStored.sum(),
                        runHashPayloadBytes.sum(), runEncodedRowBytes.sum(),
                        runPackBytesRead.sum(), runPackBytesWritten.sum(),
                        pruned, elapsed));
    }

    /** Wraps a catalog query with a leading 16-byte server row hash. */
    static String wrapHashed(String query) {
        return "SELECT pg_catalog.decode(pg_catalog.md5(" + ROW_ALIAS
                + "::text), 'hex') AS " + HASH_COLUMN + ", " + ROW_ALIAS
                + ".*\nFROM (\n" + query + "\n) " + ROW_ALIAS;
    }

    /**
     * Builds the warm hash probe: one summary row carrying the ordered
     * fingerprint of the whole result, followed by the bounded packed hash
     * chunks in exact server row order - but only when that fingerprint
     * differs from the fingerprint of the cached pack.
     * <p>
     * The per-row digests and their order are exactly the ones a per-row hash
     * pass produces, and the summary aggregate is the MD5 over their
     * concatenated lowercase hex text, which is precisely the fingerprint
     * every pack stores in its manifest. An unchanged reader therefore proves
     * itself with one round trip and 16 bytes instead of 16 bytes per row,
     * while a changed reader still receives every hash in the same pass, so a
     * warm comparison never scans the source more than once before its miss
     * fetch.
     * <p>
     * Both common table expressions are referenced more than once and are
     * therefore never inlined, so the source is scanned exactly once.
     */
    static String wrapHashOnly(String query) {
        String chunkExpression = "((" + HASH_ORDINAL_COLUMN + " - 1) / "
                + HASHES_PER_CHUNK + ")::bigint";
        return "WITH " + HASH_ROWS_ALIAS + " AS (\n"
                + "SELECT pg_catalog.row_number() OVER () AS "
                + HASH_ORDINAL_COLUMN + ",\n"
                + "       pg_catalog.md5(" + ROW_ALIAS + "::text) AS "
                + HASH_COLUMN + "\n"
                + "FROM (\n"
                + "SELECT * FROM (\n" + query + "\n) " + HASH_INPUT_ALIAS + "\n"
                + "LIMIT " + ((long) MAX_HASH_ROWS + 1L) + "\n"
                + ") " + ROW_ALIAS + "\n"
                + "), " + FINGERPRINT_ALIAS + " AS (\n"
                + "SELECT pg_catalog.count(*)::bigint AS " + HASH_COUNT_COLUMN + ",\n"
                + "       pg_catalog.md5(COALESCE(pg_catalog.string_agg("
                + HASH_COLUMN + ", ''::text ORDER BY " + HASH_ORDINAL_COLUMN
                + "), ''::text)) AS " + FINGERPRINT_COLUMN + "\n"
                + "FROM " + HASH_ROWS_ALIAS + "\n"
                + ")\n"
                + "SELECT " + SUMMARY_CHUNK_ORDINAL + "::bigint AS "
                + HASH_CHUNK_COLUMN + ",\n"
                + "       " + HASH_COUNT_COLUMN + " AS " + HASH_COUNT_COLUMN + ",\n"
                + "       pg_catalog.decode(" + FINGERPRINT_COLUMN
                + ", 'hex') AS " + HASH_COLUMN + ",\n"
                + "       " + HASH_COUNT_COLUMN + " AS " + HASH_TOTAL_COLUMN + "\n"
                + "FROM " + FINGERPRINT_ALIAS + "\n"
                + "UNION ALL\n"
                + "SELECT " + chunkExpression + ",\n"
                + "       pg_catalog.count(*),\n"
                + "       pg_catalog.string_agg(pg_catalog.decode(" + HASH_COLUMN
                + ", 'hex'), ''::bytea ORDER BY " + HASH_ORDINAL_COLUMN + "),\n"
                + "       (SELECT " + HASH_COUNT_COLUMN + " FROM "
                + FINGERPRINT_ALIAS + ")\n"
                + "FROM " + HASH_ROWS_ALIAS + "\n"
                + "WHERE (SELECT " + FINGERPRINT_COLUMN + " FROM "
                + FINGERPRINT_ALIAS + ") IS DISTINCT FROM ?::text\n"
                + "GROUP BY " + chunkExpression + "\n"
                + "ORDER BY " + HASH_CHUNK_COLUMN;
    }

    /** Fetches requested misses in the exact order of the supplied hash array. */
    static String wrapMissFetch(String query) {
        return "SELECT " + WANTED_ALIAS + ".hash AS " + HASH_COLUMN + ", "
                + ROW_ALIAS + ".*\n"
                + "FROM (\n" + query + "\n) " + ROW_ALIAS + "\n"
                + "JOIN (\n"
                + "  SELECT pg_catalog.substr(payload.packed, "
                + "ordinal * 16 + 1, 16) AS hash, ordinal\n"
                + "  FROM (SELECT ?::bytea AS packed) payload\n"
                + "  CROSS JOIN LATERAL pg_catalog.generate_series(0, "
                + "pg_catalog.octet_length(payload.packed) / 16 - 1) ordinal\n"
                + ") " + WANTED_ALIAS + "\n"
                + "  ON pg_catalog.decode(pg_catalog.md5(" + ROW_ALIAS
                + "::text), 'hex') = "
                + WANTED_ALIAS + ".hash\n"
                + "ORDER BY " + WANTED_ALIAS + ".ordinal";
    }

    /** Counts JDBC placeholders outside quoted text and comments. */
    static int countJdbcParameters(String sql) {
        int count = 0;
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = sql.indexOf(c, i + 1);
                i = end < 0 ? length : end + 1;
            } else if (c == '-' && i + 1 < length
                    && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i + 2);
                i = end < 0 ? length : end + 1;
            } else if (c == '/' && i + 1 < length
                    && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else {
                if (c == '?') {
                    count++;
                }
                i++;
            }
        }
        return count;
    }

    public static long getHitCount() {
        return HIT_COUNT.get();
    }

    public static long getMissCount() {
        return MISS_COUNT.get();
    }

    public static long getStoreCount() {
        return STORE_COUNT.get();
    }

    public static long getBypassedReaderCount() {
        return BYPASSED_READER_COUNT.get();
    }

    /** Test-only reset of JVM-wide counters. */
    public static void resetCounters() {
        HIT_COUNT.set(0);
        MISS_COUNT.set(0);
        STORE_COUNT.set(0);
        BYPASSED_READER_COUNT.set(0);
    }

    private final class ReaderRun {

        private final String readerName;
        private final String query;
        private final CatalogQueryOrder queryOrder;
        private final CatalogRowConsumer consumer;
        private final CatalogQueryParameterSetter paramSetter;
        private final String qualifier;
        private final long startedNanos = System.nanoTime();

        private long consumedRows;
        private long readerHits;
        private long readerMisses;
        private long fetchedRows;
        private long publishedRows;
        private long hashPayloadBytes;
        private long encodedRowBytes;
        private long packBytesRead;
        private long packBytesWritten;
        private boolean fingerprintProbeUsed;
        private boolean fingerprintMatched;
        private Connection cacheConnection;
        private boolean cacheSavepointCreated;
        private PgCatalogCacheMode mode = PgCatalogCacheMode.COLD;
        private PgCatalogCacheBypassReason bypassReason =
                PgCatalogCacheBypassReason.NONE;
        private boolean finished;

        private ReaderRun(String readerName, String query,
                CatalogQueryOrder queryOrder, CatalogRowConsumer consumer,
                CatalogQueryParameterSetter paramSetter) {
            this.readerName = Objects.requireNonNull(readerName, "readerName");
            this.query = Objects.requireNonNull(query, "query");
            this.queryOrder = queryOrder;
            this.consumer = Objects.requireNonNull(consumer, "consumer");
            this.paramSetter = paramSetter;
            this.qualifier = namespace.readerQualifier(readerName, query,
                    (byte) PgCatalogRowValueCodec.FORMAT_VERSION);
        }

        private boolean execute()
                throws SQLException, InterruptedException, XmlReaderException {
            PgCatalogReaderPackGeneration generation =
                    packStore.readCurrent(qualifier, loader.getMonitor());
            byte[] snapshotDigest = probeSnapshotIfEligible();
            if (generation != null) {
                WarmOutcome outcome = tryWarm(generation, snapshotDigest);
                if (outcome == WarmOutcome.HANDLED) {
                    finish(true);
                    return true;
                }
                if (outcome == WarmOutcome.BYPASS) {
                    finish(false);
                    return false;
                }
            }
            runColdPath(snapshotDigest);
            finish(true);
            return true;
        }

        private WarmOutcome tryWarm(PgCatalogReaderPackGeneration generation,
                byte[] snapshotDigest)
                throws SQLException, InterruptedException, XmlReaderException {
            Path pack = packStore.packPath(qualifier, generation);
            try (PgCatalogReaderPackReader cached =
                    PgCatalogReaderPackReader.openForReplay(pack,
                            generation.packManifest(), loader.getMonitor())) {
                packBytesRead += cached.packBytes();
                // the only branch that serves a pack without asking the server
                // anything about its contents, so what the digest stands for is
                // the whole argument for it.
                //
                // It is SHA-256 of txid_current_snapshot(): a visibility marker,
                // not a digest of the catalog. Equality of it is still
                // sufficient. The token is read inside the same
                // repeatable-read read-only transaction the readers use
                // (PgJdbcLoader.buildSessionSetupScript; the parallel lanes
                // import that same snapshot), and two snapshots carrying the
                // same xmin, xmax and in-progress list admit exactly the same
                // set of committed transactions - so they see the same catalog
                // rows. What no catalog row can answer for is held apart by the
                // target namespace instead: the server version and the
                // rendering GUCs, quote_all_identifiers included, are part of
                // the directory digest rather than of this token.
                //
                // The price is precision, not safety. Any transaction anywhere
                // in the cluster that is assigned an xid moves the token, so a
                // busy but structurally unchanged database misses here and pays
                // for the hash pass below on a catalog nobody touched. That
                // exchange is not avoidable by choosing a better token: nothing
                // the server exposes counts catalog writes apart from writes,
                // and a token that tracked catalog contents would have to read
                // them, which is what the hash pass below already does.
                //
                // The one shape the argument does not cover is an xid space
                // that is not continuous. A cluster rewound by PITR keeps its
                // system identifier, so packs stamped on the abandoned timeline
                // stay in this namespace while their xids are handed out again.
                if (snapshotDigest != null
                        && Arrays.equals(snapshotDigest,
                                generation.snapshotDigest())) {
                    fingerprintMatched = true;
                    mode = PgCatalogCacheMode.WARM_EXACT;
                    replayExact(cached);
                    return WarmOutcome.HANDLED;
                }

                HashProbe probe = runHashPass(cached.rowCount(),
                        generation.packManifest().orderedFingerprint());
                if (probe == null) {
                    bypassReason = PgCatalogCacheBypassReason.MEMORY_LIMIT;
                    return WarmOutcome.COLD;
                }
                if (probe.isUnchanged()) {
                    refreshSnapshotAfterHashMatch(generation, snapshotDigest);
                    mode = PgCatalogCacheMode.WARM_EXACT;
                    replayExact(cached);
                    return WarmOutcome.HANDLED;
                }

                PgPackedCatalogHashes incoming = probe.hashes();
                if (cached.hashes().contentEquals(incoming)) {
                    refreshSnapshotAfterHashMatch(generation,
                            snapshotDigest);
                    mode = PgCatalogCacheMode.WARM_EXACT;
                    replayExact(cached);
                    return WarmOutcome.HANDLED;
                }
                if (!PgCatalogRowCacheMemoryBudget.withinBudget(
                        cached.rowCount(), incoming.size())) {
                    bypassReason = PgCatalogCacheBypassReason.MEMORY_LIMIT;
                    return WarmOutcome.COLD;
                }
                if (!validateUnique(incoming)) {
                    bypassReason = PgCatalogCacheBypassReason.DUPLICATE_HASHES;
                    mode = PgCatalogCacheMode.BYPASS;
                    return WarmOutcome.BYPASS;
                }

                cached.buildLookupIndex(loader.getMonitor());
                int[] cachedRows = mapIncomingRows(cached, incoming);
                int misses = countMisses(cachedRows);
                if (isPurePermutation(cached, incoming, misses)) {
                    // the probe reordered an unchanged reader; replaying its
                    // order would make a warm comparison differ from the cold
                    // one that wrote this pack
                    cached.releaseLookupIndex();
                    mode = PgCatalogCacheMode.WARM_EXACT;
                    replayExact(cached);
                    return WarmOutcome.HANDLED;
                }
                if (misses * 2L > incoming.size()) {
                    bypassReason = PgCatalogCacheBypassReason.MISS_RATIO;
                    return WarmOutcome.COLD;
                }
                if (misses > MAX_MISS_FETCH_ROWS) {
                    bypassReason = PgCatalogCacheBypassReason.MEMORY_LIMIT;
                    return WarmOutcome.COLD;
                }
                cached.releaseLookupIndex();
                mode = PgCatalogCacheMode.WARM_CHANGED;
                replayChanged(cached, incoming, cachedRows, misses,
                        snapshotDigest);
                return WarmOutcome.HANDLED;
            } catch (IOException ex) {
                if (consumedRows > 0) {
                    throw new IllegalStateException(
                            "Catalog pack failed after reader mutation", ex);
                }
                bypassReason = PgCatalogCacheBypassReason.INVALID_PACK;
                return WarmOutcome.COLD;
            }
        }

        private void refreshSnapshotAfterHashMatch(
                PgCatalogReaderPackGeneration generation,
                byte[] snapshotDigest) throws InterruptedException {
            if (snapshotDigest == null) {
                return;
            }
            try {
                packStore.refreshSnapshot(qualifier, generation,
                        snapshotDigest, loader.getMonitor());
            } catch (IOException | RuntimeException ex) {
                LOG.debug("Catalog snapshot metadata refresh failed for {}",
                        readerName, ex);
            }
        }

        private void runColdPath(byte[] snapshotDigest)
                throws SQLException, InterruptedException, XmlReaderException {
            mode = PgCatalogCacheMode.COLD;
            runPass(wrapHashed(query), this::setOriginalParameters, result -> {
                String[] labels = readLabels(result.getMetaData(), 2);
                PackBuild build = new PackBuild(labels, null, snapshotDigest);
                try {
                    IMonitor monitor = loader.getMonitor();
                    while (result.next()) {
                        IMonitor.checkCancelled(monitor);
                        byte[] digest = requireDigest(result.getBytes(1));
                        Object[] values = materializeValues(result, 2,
                                labels.length);
                        fetchedRows++;
                        emit(build, labels, digest, 0, values, false);
                    }
                    build.publish();
                } finally {
                    build.close();
                }
                return null;
            });
        }

        private void replayExact(PgCatalogReaderPackReader cached)
                throws SQLException, InterruptedException, XmlReaderException {
            String[] labels = cached.labels();
            PgPackedCatalogHashes hashes = cached.hashes();
            IMonitor monitor = loader.getMonitor();
            for (int row = 0; row < cached.rowCount(); row++) {
                IMonitor.checkCancelled(monitor);
                Object[] values;
                try {
                    values = cached.readValues(row, monitor);
                } catch (IOException ex) {
                    values = null;
                }
                boolean miss = values == null;
                if (miss) {
                    values = refetchSingle(hashes, row, labels);
                }
                emit(null, labels, hashes.rawBytesForCache(),
                        hashes.offsetOf(row), values, !miss);
            }
        }

        private void replayChanged(PgCatalogReaderPackReader cached,
                PgPackedCatalogHashes incoming, int[] cachedRows, int missCount,
                byte[] snapshotDigest)
                throws SQLException, InterruptedException, XmlReaderException {
            String[] labels = cached.labels();
            PackBuild build = new PackBuild(labels, incoming, snapshotDigest);
            try {
                replayChangedRows(cached, incoming, cachedRows, labels, build,
                        missCount);
                cached.close();
                build.publish();
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to close catalog pack", ex);
            } finally {
                build.close();
            }
        }

        private void replayChangedRows(PgCatalogReaderPackReader cached,
                PgPackedCatalogHashes incoming, int[] cachedRows,
                String[] labels, PackBuild build, int missCount)
                throws SQLException, InterruptedException, XmlReaderException {
            int[] missPositions = new int[missCount];
            int ordinal = 0;
            for (int row = 0; row < incoming.size(); row++) {
                if (cachedRows[row] < 0) {
                    missPositions[ordinal++] = row;
                }
            }
            if (ordinal != missCount) {
                throw new IllegalStateException(
                        "Catalog miss count changed during replay");
            }
            if (missCount == 0) {
                emitCachedRange(cached, incoming, cachedRows, labels, build,
                        0, incoming.size());
                return;
            }

            byte[] wanted = packMissHashes(incoming, missPositions,
                    missCount);
            runMissPass(wanted, result -> {
                String[] fetchedLabels = readLabels(result.getMetaData(), 2);
                if (!Arrays.equals(labels, fetchedLabels)) {
                    throw new IllegalStateException(
                            "Catalog miss labels do not match the pack");
                }
                int cursor = 0;
                int fetchedOrdinal = 0;
                while (result.next()) {
                    if (fetchedOrdinal >= missCount) {
                        throw new IllegalStateException(
                                "Server returned extra catalog misses");
                    }
                    int expected = missPositions[fetchedOrdinal++];
                    emitCachedRange(cached, incoming, cachedRows, labels,
                            build, cursor, expected);
                    byte[] actualHash = requireDigest(result.getBytes(1));
                    if (!incoming.equalsDigestAt(expected, actualHash, 0)) {
                        throw new IllegalStateException(
                                "Server returned catalog misses out of order");
                    }
                    Object[] values = materializeValues(result, 2,
                            labels.length);
                    fetchedRows++;
                    emit(build, labels, incoming.rawBytesForCache(),
                            incoming.offsetOf(expected), values, false);
                    cursor = expected + 1;
                }
                if (fetchedOrdinal != missCount) {
                    throw new IllegalStateException(
                            "Server did not return every catalog miss");
                }
                emitCachedRange(cached, incoming, cachedRows, labels, build,
                        cursor, incoming.size());
                return null;
            });
        }

        private void emitCachedRange(PgCatalogReaderPackReader cached,
                PgPackedCatalogHashes incoming, int[] cachedRows,
                String[] labels, PackBuild build, int from, int to)
                throws SQLException, InterruptedException, XmlReaderException {
            IMonitor monitor = loader.getMonitor();
            for (int row = from; row < to; row++) {
                IMonitor.checkCancelled(monitor);
                int cachedRow = cachedRows[row];
                if (cachedRow < 0) {
                    throw new IllegalStateException(
                            "Catalog miss was not supplied by the server");
                }
                Object[] values;
                try {
                    values = cached.readValues(cachedRow, monitor);
                } catch (IOException ex) {
                    values = null;
                }
                boolean hit = values != null;
                if (!hit) {
                    values = refetchSingle(incoming, row, labels);
                }
                emit(build, labels, incoming.rawBytesForCache(),
                        incoming.offsetOf(row), values, hit);
            }
        }

        private Object[] refetchSingle(PgPackedCatalogHashes hashes, int row,
                String[] labels)
                throws SQLException, InterruptedException, XmlReaderException {
            var value = new Object[1][];
            byte[] wanted = new byte[PgPackedCatalogHashes.MD5_BYTES];
            System.arraycopy(hashes.rawBytesForCache(), hashes.offsetOf(row),
                    wanted, 0, wanted.length);
            runMissPass(wanted, result -> {
                String[] fetchedLabels = readLabels(result.getMetaData(), 2);
                if (!Arrays.equals(labels, fetchedLabels) || !result.next()
                        || !hashes.equalsDigestAt(row,
                                requireDigest(result.getBytes(1)), 0)) {
                    throw new IllegalStateException(
                            "Catalog row disappeared inside one snapshot");
                }
                value[0] = materializeValues(result, 2, labels.length);
                if (result.next()) {
                    throw new IllegalStateException(
                            "Server returned duplicate catalog row");
                }
                return null;
            });
            fetchedRows++;
            return value[0];
        }

        /**
         * Runs the single warm probe pass. The server compares its own
         * ordered fingerprint with the fingerprint of the cached pack and
         * suppresses the packed hashes when they are equal, so an unchanged
         * reader costs one exchange and one digest.
         *
         * @param cachedRows          row count of the cached pack
         * @param expectedFingerprint ordered fingerprint of the cached pack
         * @return probe result, or {@code null} when the incoming result
         *         cannot be held within the warm memory budget
         */
        private HashProbe runHashPass(int cachedRows,
                byte[] expectedFingerprint)
                throws SQLException, InterruptedException, XmlReaderException {
            int fingerprintParameterIndex = countJdbcParameters(query) + 1;
            String expected = HexFormat.of().formatHex(expectedFingerprint);
            return runPass(wrapHashOnly(query), statement -> {
                // the summary row plus one chunk row keep the driver buffer
                // bounded while an unchanged reader stays in one exchange
                statement.setFetchSize(2);
                setOriginalParameters(statement);
                statement.setString(fingerprintParameterIndex, expected);
            }, result -> readHashProbe(result, cachedRows,
                    expectedFingerprint));
        }

        /**
         * Reads the summary row and, when the reader changed, its packed hash
         * chunks. A suppressed chunk list is accepted only for the exact
         * fingerprint of the cached pack and only with its row count, so a
         * server that answers inconsistently fails loudly instead of
         * replaying stale rows.
         */
        private HashProbe readHashProbe(ResultSet result, int cachedRows,
                byte[] expectedFingerprint)
                throws SQLException, InterruptedException {
            IMonitor monitor = loader.getMonitor();
            if (!result.next()) {
                throw new IllegalStateException(
                        "Server returned no catalog hash summary row");
            }
            long ordinal = requireProtocolLong(result, 1,
                    "hash summary ordinal");
            if (ordinal != SUMMARY_CHUNK_ORDINAL) {
                throw new IllegalStateException(
                        "Packed catalog hash summary row is missing");
            }
            long total = requireProtocolLong(result, 2, "total hash count");
            byte[] fingerprint = requireDigest(result.getBytes(3));
            hashPayloadBytes += fingerprint.length;
            if (Arrays.equals(fingerprint, expectedFingerprint)) {
                if (total != cachedRows) {
                    throw new IllegalStateException(
                            "Catalog fingerprint matched a different row count");
                }
                if (result.next()) {
                    throw new IllegalStateException(
                            "Server returned catalog hashes for an unchanged reader");
                }
                return HashProbe.unchangedPack();
            }
            PgPackedCatalogHashes hashes = readHashChunks(result, cachedRows,
                    total, monitor);
            return hashes == null ? null : new HashProbe(hashes);
        }

        private PgPackedCatalogHashes readHashChunks(ResultSet result,
                int cachedRows, long total, IMonitor monitor)
                throws SQLException, InterruptedException {
            if (total == 0) {
                if (result.next()) {
                    throw new IllegalStateException(
                            "Server returned catalog hashes for an empty reader");
                }
                return PgPackedCatalogHashes.takeOwnership(0, new byte[0],
                        monitor);
            }
            if (total < 0 || total > MAX_HASH_ROWS) {
                return null;
            }
            int incomingRows = Math.toIntExact(total);
            if (!PgCatalogRowCacheMemoryBudget.withinBudget(
                    cachedRows, incomingRows)) {
                return null;
            }
            if (!result.next()) {
                throw new IllegalStateException(
                        "Packed catalog hash chunks are truncated");
            }
            IMonitor.checkCancelled(monitor);
            byte[] packed = new byte[Math.multiplyExact(incomingRows,
                    PgPackedCatalogHashes.MD5_BYTES)];
            int copiedRows = 0;
            long expectedOrdinal = 0;
            while (true) {
                IMonitor.checkCancelled(monitor);
                if (requireProtocolLong(result, 4, "total hash count")
                        != total) {
                    throw new IllegalStateException(
                            "Packed catalog hash total changed between chunks");
                }
                long ordinal = requireProtocolLong(result, 1,
                        "hash chunk ordinal");
                if (ordinal != expectedOrdinal) {
                    throw new IllegalStateException(
                            "Packed catalog hash chunk order is invalid");
                }
                int expectedRows = Math.min(HASHES_PER_CHUNK,
                        incomingRows - copiedRows);
                long chunkRows = requireProtocolLong(result, 2,
                        "hash chunk row count");
                if (chunkRows != expectedRows) {
                    throw new IllegalStateException(
                            "Packed catalog hash chunk count is invalid");
                }
                byte[] chunk = result.getBytes(3);
                int expectedBytes = Math.multiplyExact(expectedRows,
                        PgPackedCatalogHashes.MD5_BYTES);
                if (chunk == null || chunk.length != expectedBytes
                        || chunk.length > HASH_CHUNK_BYTES) {
                    throw new IllegalStateException(
                            "Packed catalog hash chunk payload is invalid");
                }
                System.arraycopy(chunk, 0, packed,
                        copiedRows * PgPackedCatalogHashes.MD5_BYTES,
                        chunk.length);
                copiedRows += expectedRows;
                expectedOrdinal++;
                IMonitor.checkCancelled(monitor);

                boolean next = result.next();
                if (copiedRows == incomingRows) {
                    if (next) {
                        throw new IllegalStateException(
                                "Server returned extra packed catalog hash chunks");
                    }
                    break;
                }
                if (!next) {
                    throw new IllegalStateException(
                            "Packed catalog hash chunks are truncated");
                }
            }

            PgPackedCatalogHashes hashes =
                    PgPackedCatalogHashes.takeOwnership(total, packed, monitor);
            hashPayloadBytes += packed.length;
            return hashes;
        }

        private void runMissPass(byte[] wanted,
                ResultSetHandler<Void> handler)
                throws SQLException, InterruptedException, XmlReaderException {
            int hashParameterIndex = countJdbcParameters(query) + 1;
            runPass(wrapMissFetch(query), statement -> {
                setOriginalParameters(statement);
                statement.setBytes(hashParameterIndex, wanted);
            }, handler);
        }

        private byte[] probeSnapshotIfEligible() {
            if (!fingerprintProbeEnabled
                    || queryOrder != CatalogQueryOrder.EXPLICIT_ORDER_BY
                    || paramSetter != null
                    || countJdbcParameters(query) != 0
                    || snapshotDigest == null) {
                return null;
            }
            fingerprintProbeUsed = true;
            return snapshotDigest.clone();
        }

        private int[] mapIncomingRows(PgCatalogReaderPackReader cached,
                PgPackedCatalogHashes incoming) throws InterruptedException {
            int[] rows = new int[incoming.size()];
            IMonitor monitor = loader.getMonitor();
            for (int row = 0; row < rows.length; row++) {
                IMonitor.checkCancelled(monitor);
                rows[row] = cached.findRow(incoming.rawBytesForCache(),
                        incoming.offsetOf(row));
            }
            return rows;
        }

        private boolean validateUnique(PgPackedCatalogHashes hashes)
                throws InterruptedException {
            try {
                PgPackedCatalogHashIndex.build(hashes, loader.getMonitor());
                return true;
            } catch (DuplicateHashException ex) {
                return false;
            }
        }

        /**
         * Decides whether the probe returned the cached rows in a different
         * order rather than different rows.
         * <p>
         * The warm probe wraps the reader query in its own statement, so the
         * server may plan it differently and return the same rows in another
         * order. That order is not the one a plain read produces, so replaying
         * it would let a warm comparison build its model in a different order
         * than the cold comparison that wrote this pack - the one difference
         * a row cache must never introduce.
         * <p>
         * The test is exact, not heuristic. The incoming digests are already
         * known to be pairwise distinct, and every one of them was found in
         * the pack. With {@code n} distinct incoming digests contained in the
         * pack's {@code n} digests, the pack cannot hold a duplicate either,
         * so both sides carry the same digest set: the probe returned exactly
         * the cached rows, permuted. Anything genuinely added, removed or
         * edited changes a digest and therefore misses or changes the count.
         *
         * @param cached   pack of the previous read
         * @param incoming digests returned by this probe, in probe order
         * @param misses   incoming digests absent from the pack
         * @return true when the probe result is a permutation of the pack
         */
        private boolean isPurePermutation(PgCatalogReaderPackReader cached,
                PgPackedCatalogHashes incoming, int misses) {
            return misses == 0 && incoming.size() == cached.rowCount();
        }

        private int countMisses(int[] cachedRows) {
            int misses = 0;
            for (int cachedRow : cachedRows) {
                if (cachedRow < 0) {
                    misses++;
                }
            }
            return misses;
        }

        private byte[] packMissHashes(PgPackedCatalogHashes hashes,
                int[] positions, int count) throws InterruptedException {
            byte[] packed = new byte[Math.multiplyExact(count,
                    PgPackedCatalogHashes.MD5_BYTES)];
            byte[] source = hashes.rawBytesForCache();
            IMonitor monitor = loader.getMonitor();
            for (int i = 0; i < count; i++) {
                if ((i & 2047) == 0) {
                    IMonitor.checkCancelled(monitor);
                }
                System.arraycopy(source, hashes.offsetOf(positions[i]),
                        packed, i * PgPackedCatalogHashes.MD5_BYTES,
                        PgPackedCatalogHashes.MD5_BYTES);
            }
            return packed;
        }

        private void emit(PackBuild build, String[] labels, byte[] digest,
                int digestOffset, Object[] values, boolean hit)
                throws SQLException, InterruptedException, XmlReaderException {
            IMonitor.checkCancelled(loader.getMonitor());
            if (build != null) {
                build.append(digest, digestOffset, values);
            }
            consumedRows++;
            if (hit) {
                readerHits++;
                HIT_COUNT.incrementAndGet();
            } else {
                readerMisses++;
                MISS_COUNT.incrementAndGet();
            }
            consumer.accept(PgCachedRowResultSet.positioned(labels, values));
        }

        private void setOriginalParameters(PreparedStatement statement)
                throws SQLException {
            if (paramSetter != null) {
                paramSetter.setParameters(statement);
            }
        }

        /** Runs a derived statement with cancellation-safe JDBC cleanup. */
        private <R> R runPass(String sql, StatementPreparer preparer,
                ResultSetHandler<R> handler)
                throws SQLException, InterruptedException, XmlReaderException {
            PreparedStatement statement = null;
            ResultSet result = null;
            Throwable failure = null;
            R value = null;
            try {
                ensureCacheSavepoint();
                statement = loader.prepareCatalogStatement(sql);
                loader.registerCatalogStatement(statement);
                preparer.prepare(statement);
                result = loader.getRunner().runScript(statement);
                value = handler.handle(result);
            } catch (SQLException | InterruptedException | XmlReaderException
                    | RuntimeException | Error ex) {
                failure = ex;
            }
            AbstractJdbcReader.finishCatalogRead(loader, result, statement,
                    failure);
            return value;
        }

        /**
         * Binds this run to the rollback boundary of its catalog connection.
         * Exact pack replay remains fully local; only derived SQL needs a
         * boundary before the caller can safely run plain SQL. The boundary
         * belongs to the connection, not to the run: every reader of one
         * connection shares it, so a warm comparison opens one subtransaction
         * per connection instead of two round trips per reader.
         */
        private void ensureCacheSavepoint() throws SQLException {
            if (cacheSavepointCreated) {
                return;
            }
            Connection connection = loader.getCatalogConnection();
            loader.ensureCatalogProbeSavepoint(connection);
            cacheConnection = connection;
            cacheSavepointCreated = true;
        }

        /**
         * Drops this run's reference to the shared boundary. The boundary
         * itself stays open for the remaining readers of the connection and
         * ends with the comparison transaction.
         */
        private void releaseCacheSavepoint() {
            clearCacheSavepoint();
        }

        /**
         * Returns the connection to the shared boundary after a derived
         * statement failed before any row was consumed. Nothing between the
         * boundary and the failure can be lost: the comparison transaction is
         * read-only, so the earlier readers of this connection produced no
         * transactional state. PostgreSQL keeps the savepoint valid after the
         * rollback, so the boundary stays usable for the next reader.
         */
        private void rollbackCacheSavepoint() throws SQLException {
            if (!cacheSavepointCreated) {
                return;
            }
            loader.rollbackToCatalogProbeSavepoint(cacheConnection);
            clearCacheSavepoint();
        }

        private void clearCacheSavepoint() {
            cacheSavepointCreated = false;
            cacheConnection = null;
        }

        private void finish(boolean handled) {
            if (finished) {
                return;
            }
            finished = true;
            if (!handled) {
                mode = PgCatalogCacheMode.BYPASS;
                runBypassedReaders.increment();
                BYPASSED_READER_COUNT.incrementAndGet();
            }
            runReaders.increment();
            runRows.add(consumedRows);
            runHits.add(readerHits);
            runMisses.add(readerMisses);
            runFetched.add(fetchedRows);
            runStored.add(publishedRows);
            runHashPayloadBytes.add(hashPayloadBytes);
            runEncodedRowBytes.add(encodedRowBytes);
            runPackBytesRead.add(packBytesRead);
            runPackBytesWritten.add(packBytesWritten);
            long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
            LOG.debug("catalog_row_cache reader={} mode={} hits={} misses={} "
                            + "stored={} bypass={}", readerName, mode,
                    readerHits, readerMisses, publishedRows, bypassReason);
            ComparisonTelemetryPublisher.publishCatalogReader(telemetry,
                    new PgCatalogReaderCacheTelemetry(readerName, mode,
                            bypassReason, consumedRows, readerHits,
                            readerMisses, fetchedRows, publishedRows,
                            hashPayloadBytes, encodedRowBytes, packBytesRead,
                            packBytesWritten, fingerprintProbeUsed,
                            fingerprintMatched, elapsed));
        }

        private final class PackBuild implements AutoCloseable {

            private final String[] labels;
            private final UUID generation = UUID.randomUUID();
            private final byte[] snapshotDigest;
            private Path temporary;
            private PgCatalogReaderPackWriter writer;
            private long rows;

            private PackBuild(String[] labels,
                    PgPackedCatalogHashes borrowedHashes,
                    byte[] snapshotDigest) {
                this.labels = labels;
                this.snapshotDigest = snapshotDigest;
                try {
                    temporary = packStore.createTemporaryPack(qualifier,
                            generation);
                    writer = borrowedHashes == null
                            ? new PgCatalogReaderPackWriter(temporary, labels,
                                    maxRowPayloadBytes)
                            : new PgCatalogReaderPackWriter(temporary, labels,
                                    maxRowPayloadBytes, borrowedHashes, true);
                } catch (IOException | RuntimeException ex) {
                    LOG.debug("Catalog pack writer is unavailable for {}",
                            readerName, ex);
                    writer = null;
                }
            }

            private void append(byte[] digest, int digestOffset,
                    Object[] values) throws InterruptedException {
                if (writer == null) {
                    return;
                }
                long before = writer.bytesWritten();
                try {
                    writer.append(digest, digestOffset, values,
                            loader.getMonitor());
                    encodedRowBytes += Math.max(0L,
                            writer.bytesWritten() - before);
                    rows++;
                } catch (IOException | UnsupportedRowValueException
                        | RuntimeException ex) {
                    LOG.debug("Catalog pack publication was disabled for {}",
                            readerName, ex);
                    writer.close();
                    writer = null;
                }
            }

            private void publish() throws InterruptedException {
                if (writer == null) {
                    return;
                }
                try {
                    PgCatalogReaderPackManifest manifest = writer.finish(
                            generation, loader.getMonitor());
                    writer = null;
                    if (packStore.publish(qualifier, temporary, manifest,
                            snapshotDigest, loader.getMonitor())) {
                        publishedRows += rows;
                        STORE_COUNT.addAndGet(rows);
                        packBytesWritten += manifest.packSize();
                    }
                } catch (IOException | RuntimeException ex) {
                    LOG.debug("Catalog pack publication failed for {}",
                            readerName, ex);
                }
            }

            @Override
            public void close() {
                if (writer != null) {
                    writer.close();
                    writer = null;
                }
            }
        }
    }

    private enum WarmOutcome {
        HANDLED,
        COLD,
        BYPASS
    }

    /**
     * Result of one warm hash probe: either the server proved the cached pack
     * unchanged, or it returned every incoming row hash in server order.
     */
    private record HashProbe(PgPackedCatalogHashes hashes) {

        private static HashProbe unchangedPack() {
            return new HashProbe(null);
        }

        private boolean isUnchanged() {
            return hashes == null;
        }
    }

    private static long requireProtocolLong(ResultSet result, int column,
            String label) throws SQLException {
        long value = result.getLong(column);
        if (result.wasNull()) {
            throw new IllegalStateException(
                    "Server returned a null " + label);
        }
        return value;
    }

    private static byte[] requireDigest(byte[] digest) {
        if (digest == null
                || digest.length != PgPackedCatalogHashes.MD5_BYTES) {
            throw new IllegalStateException("Invalid binary catalog row hash");
        }
        return digest;
    }

    private static String[] readLabels(ResultSetMetaData metadata,
            int firstValueColumn) throws SQLException {
        int count = metadata.getColumnCount() - firstValueColumn + 1;
        if (count < 0) {
            throw new SQLException("Invalid catalog result metadata");
        }
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            labels[i] = metadata.getColumnLabel(firstValueColumn + i);
        }
        return labels;
    }

    private static Object[] materializeValues(ResultSet result,
            int firstValueColumn, int valueCount) throws SQLException {
        Object[] values = new Object[valueCount];
        for (int i = 0; i < valueCount; i++) {
            int column = firstValueColumn + i;
            Object value = result.getObject(column);
            if (value instanceof Array array) {
                value = captureArray(result, column, array);
            } else if (value instanceof PGobject pgObject) {
                value = pgObject.getValue();
            }
            values[i] = value;
        }
        return values;
    }

    /**
     * Captures an array column in both shapes its reader may ask for. A
     * freed {@link Array} keeps neither its elements nor its text, and
     * privilege columns are read as text while option columns are read as
     * elements, so both are taken from the live driver row. The text is the
     * one {@code getString} returns, never a re-rendered literal, so every
     * quoting, escaping and {@code NULL} detail matches the uncached read.
     */
    private static Object captureArray(ResultSet result, int column,
            Array array) throws SQLException {
        String text = result.getString(column);
        if (text == null) {
            // the driver renders a binary array through the array itself
            text = array.toString();
        }
        Object content;
        try {
            content = array.getArray();
        } finally {
            // PostgreSQL's PgArray retains its connection and encoded
            // representations until free() releases them
            array.free();
        }
        // a driver that offers no text at all leaves the decoded elements
        // alone instead of inventing a literal, exactly as before
        return text == null ? content
                : new PgCachedCatalogArray(normalizeElements(content), text);
    }

    /**
     * Reduces driver-specific array elements to their text. pgJDBC decodes
     * arrays of the types it knows into typed arrays ({@code String[]},
     * {@code Long[]}, ...) and everything else - {@code aclitem[]} above all -
     * into an untyped array of {@link PGobject}, which carries only text.
     * Reducing those here keeps a cold read and its warm replay identical and
     * lets the value codec store the row at all.
     */
    private static Object normalizeElements(Object content) {
        if (!(content instanceof Object[] elements)
                || elements.getClass().getComponentType() != Object.class) {
            return content;
        }
        var text = new String[elements.length];
        for (int i = 0; i < elements.length; i++) {
            Object element = elements[i];
            if (element == null) {
                // a null element stays null in either shape
                continue;
            }
            if (element instanceof PGobject pgObject) {
                text[i] = pgObject.getValue();
            } else if (element instanceof String string) {
                text[i] = string;
            } else {
                // an unknown element type keeps the exact objects the
                // uncached read returns; only the pack is skipped
                return elements;
            }
        }
        return text;
    }

    @FunctionalInterface
    private interface StatementPreparer {
        void prepare(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface ResultSetHandler<R> {
        R handle(ResultSet result)
                throws SQLException, InterruptedException, XmlReaderException;
    }
}
