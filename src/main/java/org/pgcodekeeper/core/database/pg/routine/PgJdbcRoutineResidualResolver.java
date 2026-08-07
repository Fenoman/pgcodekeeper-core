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

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.utils.PhaseTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves fingerprint slots through ordered, count-and-byte bounded residual
 * requests. Every row is rehashed and published before the transport advances.
 * With an optional persistent body cache, slots unmatched by the project
 * catalog are first resolved from verified local entries and only true
 * misses travel over JDBC; fetched bodies are stored back into the cache.
 * With an optional old-side divergence policy, eligible unmatched slots skip
 * both the cache and the residual fetch entirely and resolve to a divergent
 * sentinel: their text is write-only for the comparison and never analyzed.
 */
final class PgJdbcRoutineResidualResolver implements PgJdbcRoutineBodyResolver {

    private static final Logger LOG = LoggerFactory.getLogger(PgJdbcRoutineResidualResolver.class);

    private final PgRoutineBodyResidualTransport transport;
    private final PgRoutineBodyBatchLimits limits;
    private final ProjectRoutineBodyCatalogChannel projectCatalogChannel;
    private final PgRoutineBodyCache bodyCache;
    private final PgRoutineBodyDivergencePolicy divergencePolicy;

    PgJdbcRoutineResidualResolver(PgRoutineBodyResidualTransport transport,
                                  PgRoutineBodyBatchLimits limits) {
        this(transport, limits, null, null, null);
    }

    PgJdbcRoutineResidualResolver(PgRoutineBodyResidualTransport transport,
                                  PgRoutineBodyBatchLimits limits,
                                  ProjectRoutineBodyCatalogChannel projectCatalogChannel) {
        this(transport, limits, projectCatalogChannel, null, null);
    }

    PgJdbcRoutineResidualResolver(PgRoutineBodyResidualTransport transport,
                                  PgRoutineBodyBatchLimits limits,
                                  ProjectRoutineBodyCatalogChannel projectCatalogChannel,
                                  PgRoutineBodyCache bodyCache) {
        this(transport, limits, projectCatalogChannel, bodyCache, null);
    }

    PgJdbcRoutineResidualResolver(PgRoutineBodyResidualTransport transport,
                                  PgRoutineBodyBatchLimits limits,
                                  ProjectRoutineBodyCatalogChannel projectCatalogChannel,
                                  PgRoutineBodyCache bodyCache,
                                  PgRoutineBodyDivergencePolicy divergencePolicy) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.projectCatalogChannel = projectCatalogChannel;
        this.bodyCache = bodyCache;
        this.divergencePolicy = divergencePolicy;
    }

    @Override
    public void resolve(List<PgJdbcRoutineBodySlot> slots, IMonitor monitor)
            throws IOException, InterruptedException {
        // Includes the wait for the project side to hand the catalog over.
        long matchStart = PhaseTimer.start();
        resolveProjectMatches(slots, monitor);
        PhaseTimer.end("jdbc_reader", matchStart, "routine_match");

        if (divergencePolicy != null) {
            long divergentStart = PhaseTimer.start();
            resolveDivergentSlots(slots, monitor);
            PhaseTimer.end("jdbc_reader", divergentStart, "routine_divergent");
        }

        if (bodyCache != null) {
            long cacheStart = PhaseTimer.start();
            resolveCacheHits(slots, monitor);
            PhaseTimer.end("jdbc_reader", cacheStart, "routine_cache");
        }

        long residualStart = PhaseTimer.start();
        int start = 0;
        while (start < slots.size()) {
            checkCancelled(monitor);
            int end = PgRoutineBodyResidualBatchPlanner.nextEnd(
                    slots.size(), start, limits,
                    index -> slots.get(index).requiresResidual(),
                    index -> slots.get(index).predictedUtf8Bytes());
            int batchSize = countResiduals(slots, start, end);
            if (batchSize == 0) {
                start = end;
                continue;
            }
            Long[] oids = new Long[batchSize];
            int oidIndex = 0;
            for (int i = start; i < end; i++) {
                PgJdbcRoutineBodySlot slot = slots.get(i);
                if (slot.requiresResidual()) {
                    oids[oidIndex++] = slot.bodyOid();
                }
            }

            int batchStart = start;
            int batchEnd = end;
            PgJdbcRoutineBodySlot firstResidual = slots.get(
                    nextResidualIndex(slots, batchStart, batchEnd));
            int[] consumed = { 0 };
            int[] cursor = { batchStart };
            transport.fetch(oids, (batchOrdinal, bodyOid, rawBody) -> {
                checkCancelled(monitor);
                int index = consumed[0];
                if (index >= batchSize) {
                    throw protocolFailure("extra row", firstResidual, bodyOid);
                }
                int slotIndex = nextResidualIndex(slots, cursor[0], batchEnd);
                PgJdbcRoutineBodySlot slot = slots.get(slotIndex);
                cursor[0] = slotIndex + 1;
                long expectedOrdinal = index + 1L;
                if (batchOrdinal != expectedOrdinal) {
                    throw protocolFailure("ordinal " + batchOrdinal
                            + " instead of " + expectedOrdinal, slot, bodyOid);
                }
                if (bodyOid != slot.bodyOid()) {
                    throw protocolFailure("OID " + bodyOid
                            + " instead of " + slot.bodyOid(), slot, bodyOid);
                }
                if (rawBody == null) {
                    throw protocolFailure("NULL body", slot, bodyOid);
                }
                try {
                    slot.resolveResidual(rawBody);
                } catch (IllegalArgumentException ex) {
                    throw new IOException("Residual PostgreSQL routine body failed fingerprint "
                            + "validation at metadata ordinal " + slot.metadataOrdinal()
                            + ", OID " + bodyOid, ex);
                }
                if (bodyCache != null) {
                    bodyCache.store(slot.authorization(), rawBody);
                }
                consumed[0] = index + 1;
            }, monitor);
            if (consumed[0] != batchSize) {
                PgJdbcRoutineBodySlot missing = slots.get(
                        nextResidualIndex(slots, cursor[0], batchEnd));
                throw protocolFailure("missing row", missing, missing.bodyOid());
            }
            checkCancelled(monitor);
            start = end;
        }
        PhaseTimer.end("jdbc_reader", residualStart, "routine_residuals");
        if (bodyCache != null) {
            bodyCache.finishRun();
        }
    }

    /**
     * Resolves eligible unmatched slots to the divergent sentinel before the
     * cache and residual stages run, so neither stage sees them as pending.
     */
    private void resolveDivergentSlots(
            List<PgJdbcRoutineBodySlot> slots, IMonitor monitor)
            throws InterruptedException {
        long divergentSlots = 0;
        long divergentBytes = 0;
        for (PgJdbcRoutineBodySlot slot : slots) {
            checkCancelled(monitor);
            if (slot.requiresResidual() && !slot.isDivergenceBlocked()
                    && divergencePolicy.isDivergenceEligible(
                            slot.authorization().representation())) {
                long predictedBytes = slot.predictedUtf8Bytes();
                slot.resolveDivergent();
                PgRoutineBodyAnalysisStats.recordDivergentUnfetched(predictedBytes);
                divergentSlots++;
                divergentBytes += predictedBytes;
            }
        }
        if (divergentSlots != 0) {
            LOG.debug("routine_body_residuals divergent={} divergent_bytes={}",
                    divergentSlots, divergentBytes);
        }
    }

    private void resolveCacheHits(
            List<PgJdbcRoutineBodySlot> slots, IMonitor monitor)
            throws InterruptedException {
        for (PgJdbcRoutineBodySlot slot : slots) {
            checkCancelled(monitor);
            if (slot.requiresResidual()) {
                bodyCache.resolve(slot);
            }
        }
    }

    private void resolveProjectMatches(
            List<PgJdbcRoutineBodySlot> slots, IMonitor monitor)
            throws InterruptedException {
        if (projectCatalogChannel == null) {
            return;
        }
        if (slots.isEmpty()) {
            // No consumer rows exist, so decline before the project side builds
            // a catalog that cannot resolve anything.
            projectCatalogChannel.cancel();
            return;
        }

        ProjectRoutineBodyCatalog catalog = projectCatalogChannel.take(monitor);
        try {
            for (PgJdbcRoutineBodySlot slot : slots) {
                checkCancelled(monitor);
                RoutineIdentity identity = slot.identity();
                boolean ambiguous = catalog.removeAmbiguous(identity);
                ProjectRoutineBodyCandidate candidate = catalog.removeCandidate(identity);
                if (ambiguous) {
                    // no fingerprint verdict exists for a duplicated project
                    // identity, so equality may only be decided by a real fetch
                    slot.blockDivergence();
                } else if (candidate != null) {
                    slot.resolveProjectCandidate(candidate);
                }
            }
            checkCancelled(monitor);
        } finally {
            catalog.close();
        }
    }

    private static int countResiduals(
            List<PgJdbcRoutineBodySlot> slots, int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) {
            if (slots.get(i).requiresResidual()) {
                count++;
            }
        }
        return count;
    }

    private static int nextResidualIndex(
            List<PgJdbcRoutineBodySlot> slots, int start, int end) {
        for (int i = start; i < end; i++) {
            if (slots.get(i).requiresResidual()) {
                return i;
            }
        }
        throw new IllegalStateException("Residual PostgreSQL routine slot is missing");
    }

    @Override
    public void close() throws IOException {
        transport.close();
    }

    private static IOException protocolFailure(
            String detail, PgJdbcRoutineBodySlot slot, long observedOid) {
        return new IOException("Invalid residual PostgreSQL routine body response ("
                + detail + ") at metadata ordinal " + slot.metadataOrdinal()
                + ", expected OID " + slot.bodyOid() + ", observed OID " + observedOid);
    }

    private static void checkCancelled(IMonitor monitor) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        IMonitor.checkCancelled(monitor);
    }

}
