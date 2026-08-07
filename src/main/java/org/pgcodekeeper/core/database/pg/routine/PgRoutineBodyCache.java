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

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.pgcodekeeper.core.telemetry.ComparisonTelemetryPublisher;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.telemetry.PgRoutineBodyCacheTelemetry;
import org.pgcodekeeper.core.utils.ContentAddressedFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent local cache of raw PostgreSQL routine bodies for the hash-first
 * residual path. Its directory is already scoped to one PostgreSQL target
 * and session rendering identity. Entries are addressed by the exact tuple
 * the residual resolver already uses for body equality: the SHA-256 digest
 * and UTF-8 length of the raw body ({@link RoutineFingerprint}) plus the
 * parser {@link RoutineBodyRepresentation}. The stored payload is the raw
 * UTF-8 bytes; canonicalization is re-applied per load with the consuming
 * slot's own profile, so entries stay valid across profile settings.
 * <p>
 * Every cache hit passes the same fail-closed rehash-and-validate resolution
 * as a body fetched from the server, so the resulting model is identical
 * regardless of cache state. Corrupt or undecodable entries are deleted and
 * degrade to ordinary residual fetches.
 * <p>
 * Concurrency: multiple loaders in one JVM and multiple processes for the
 * same target may share one directory. Publication is an atomic rename and
 * reads verify content hashes. Size maintenance uses a non-blocking prune
 * lock and simply skips a contended pass.
 */
public final class PgRoutineBodyCache {

    private static final Logger LOG = LoggerFactory.getLogger(PgRoutineBodyCache.class);

    private static final String BODIES_CATEGORY = "bodies";

    private static final AtomicLong HIT_COUNT = new AtomicLong();
    private static final AtomicLong MISS_COUNT = new AtomicLong();
    private static final AtomicLong STORE_COUNT = new AtomicLong();
    private static final AtomicLong SAVED_BYTES = new AtomicLong();

    private final ContentAddressedFileStore store;
    private final long maxBytes;
    private final IComparisonTelemetry telemetry;
    private final long runStartedNanos;
    private final AtomicBoolean finished = new AtomicBoolean();

    private long runHits;
    private long runMisses;
    private long runStored;
    private long runSavedBytes;

    public PgRoutineBodyCache(Path directory, long maxBytes) {
        this(directory, maxBytes, IComparisonTelemetry.NO_OP);
    }

    public PgRoutineBodyCache(Path directory, long maxBytes,
            IComparisonTelemetry telemetry) {
        this.store = new ContentAddressedFileStore(
                Objects.requireNonNull(directory, "directory"));
        if (maxBytes <= 0) {
            throw new IllegalArgumentException(
                    "PostgreSQL catalog cache size limit must be positive");
        }
        this.maxBytes = maxBytes;
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.runStartedNanos = System.nanoTime();
    }

    /**
     * Attempts to resolve one residual slot from the cache. A hit runs the
     * exact residual resolution path including the fail-closed rehash, so a
     * resolved slot is indistinguishable from a server-fetched one. Any
     * invalid entry is deleted and counted as a miss.
     *
     * @return true when the slot was resolved from the cache
     */
    boolean resolve(PgJdbcRoutineBodySlot slot) {
        RoutineBodyAuthorization authorization = slot.authorization();
        RoutineFingerprint fingerprint = authorization.fingerprint();
        String sha256Hex = sha256Hex(fingerprint);
        String qualifier = qualifier(authorization);

        String raw = store.readUtf8(BODIES_CATEGORY, sha256Hex, qualifier,
                fingerprint.utf8Length());
        if (raw == null) {
            countMiss();
            return false;
        }

        try {
            slot.resolveResidual(raw);
        } catch (IllegalArgumentException ex) {
            LOG.debug("Dropping invalid routine body cache entry {}-{}",
                    sha256Hex, qualifier, ex);
            store.delete(BODIES_CATEGORY, sha256Hex, qualifier);
            countMiss();
            return false;
        }
        runHits++;
        runSavedBytes += fingerprint.utf8Length();
        HIT_COUNT.incrementAndGet();
        SAVED_BYTES.addAndGet(fingerprint.utf8Length());
        return true;
    }

    /**
     * Stores one server-fetched raw body under its authorization address.
     * The store layer streams the text through the digest and re-verifies the
     * payload hash before publication, so a payload that does not match its
     * address is silently discarded.
     */
    void store(RoutineBodyAuthorization authorization, String raw) {
        // The address is the profile-normalized fingerprint, so the payload
        // must be normalized the same way or the content-addressed store
        // would discard it on its own hash re-verification. The normalized
        // payload canonicalizes identically to the fetched raw.
        String payload = authorization.profile().keepNewLines()
                ? raw
                : raw.replace("\r", "");
        if (store.writeUtf8(BODIES_CATEGORY, sha256Hex(authorization.fingerprint()),
                qualifier(authorization), payload)) {
            runStored++;
            STORE_COUNT.incrementAndGet();
        }
    }

    /**
     * Emits the per-run summary counters and prunes the store to its size
     * cap after the residual phase completes. Repeated calls are ignored.
     */
    void finishRun() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        LOG.debug("routine_body_cache hits={} misses={} stored={} bytes_saved={}",
                runHits, runMisses, runStored, runSavedBytes);
        long prunedBytes = store.pruneToLimit(maxBytes);
        long elapsedNanos = Math.max(0L, System.nanoTime() - runStartedNanos);
        ComparisonTelemetryPublisher.publishRoutineBody(telemetry,
                new PgRoutineBodyCacheTelemetry(runHits, runMisses,
                        runStored, runSavedBytes, prunedBytes, elapsedNanos));
    }

    private void countMiss() {
        runMisses++;
        MISS_COUNT.incrementAndGet();
    }

    private static String qualifier(RoutineBodyAuthorization authorization) {
        return authorization.fingerprint().utf8Length()
                + "-" + authorization.representation().name();
    }

    private static String sha256Hex(RoutineFingerprint fingerprint) {
        HexFormat hex = HexFormat.of();
        return hex.toHexDigits(fingerprint.hash0())
                + hex.toHexDigits(fingerprint.hash1())
                + hex.toHexDigits(fingerprint.hash2())
                + hex.toHexDigits(fingerprint.hash3());
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

    public static long getSavedBytes() {
        return SAVED_BYTES.get();
    }

    /** Test-only reset of the JVM-wide cache counters. */
    public static void resetCounters() {
        HIT_COUNT.set(0);
        MISS_COUNT.set(0);
        STORE_COUNT.set(0);
        SAVED_BYTES.set(0);
    }
}
