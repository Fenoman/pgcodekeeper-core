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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.monitor.IMonitor;

/**
 * One-load owner of ordered PostgreSQL JDBC routine-body slots. The full-body
 * resolver provides the catalog payload while preserving the
 * same barrier and ownership contract needed by hash-first resolution.
 */
public final class PgJdbcRoutineBodyResolution implements AutoCloseable {

    private enum State {
        OPEN,
        RESOLVING,
        RESOLVED,
        CLOSED
    }

    private PgJdbcRoutineBodyResolver resolver;
    private List<PgJdbcRoutineBodySlot> slots = new ArrayList<>();
    private State state = State.OPEN;
    private boolean resolverClosed;

    public static PgJdbcRoutineBodyResolution fullBody() {
        return new PgJdbcRoutineBodyResolution((slots, monitor) -> {
            for (PgJdbcRoutineBodySlot slot : slots) {
                checkCancelled(monitor);
                slot.resolveFullBody();
            }
        });
    }

    public static PgJdbcRoutineBodyResolution fingerprint(
            PgRoutineBodyResidualTransport transport,
            PgRoutineBodyBatchLimits limits) {
        return new PgJdbcRoutineBodyResolution(
                new PgJdbcRoutineResidualResolver(transport, limits));
    }

    public static PgJdbcRoutineBodyResolution fingerprint(
            PgRoutineBodyResidualTransport transport,
            PgRoutineBodyBatchLimits limits,
            ProjectRoutineBodyCatalogChannel projectCatalogChannel) {
        return new PgJdbcRoutineBodyResolution(
                new PgJdbcRoutineResidualResolver(
                        transport, limits,
                        Objects.requireNonNull(projectCatalogChannel,
                                "projectCatalogChannel")));
    }

    /**
     * Creates a fingerprint resolution with an optional persistent routine
     * body cache consulted between project matching and residual fetches.
     *
     * @param projectCatalogChannel nullable project catalog handover
     * @param bodyCache             nullable persistent routine body cache
     */
    public static PgJdbcRoutineBodyResolution fingerprint(
            PgRoutineBodyResidualTransport transport,
            PgRoutineBodyBatchLimits limits,
            ProjectRoutineBodyCatalogChannel projectCatalogChannel,
            PgRoutineBodyCache bodyCache) {
        return fingerprint(transport, limits, projectCatalogChannel, bodyCache, null);
    }

    /**
     * Creates a fingerprint resolution with an optional persistent body cache
     * and an optional old-side divergence policy. When the policy is present,
     * eligible unmatched slots resolve to a divergent sentinel instead of
     * fetching their body text.
     *
     * @param projectCatalogChannel nullable project catalog handover
     * @param bodyCache             nullable persistent routine body cache
     * @param divergencePolicy      nullable old-side fetch-skip policy
     */
    public static PgJdbcRoutineBodyResolution fingerprint(
            PgRoutineBodyResidualTransport transport,
            PgRoutineBodyBatchLimits limits,
            ProjectRoutineBodyCatalogChannel projectCatalogChannel,
            PgRoutineBodyCache bodyCache,
            PgRoutineBodyDivergencePolicy divergencePolicy) {
        return new PgJdbcRoutineBodyResolution(
                new PgJdbcRoutineResidualResolver(
                        transport, limits, projectCatalogChannel, bodyCache,
                        divergencePolicy));
    }

    PgJdbcRoutineBodyResolution(PgJdbcRoutineBodyResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * Registers one exact final-model routine. Valid UTF-16 receives a
     * deferred lease; malformed UTF-16 stays analyzable through a local-only
     * source and is excluded from cross-loader exchange.
     */
    public synchronized RoutineBodySource registerFullBody(
            PgAbstractFunction routine, String raw, String canonical,
            RoutineBodyProfile profile, RoutineBodyRepresentation representation) {
        requireOpen();
        Objects.requireNonNull(routine, "routine");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(representation, "representation");
        if (!representation.isExchangeEligible()) {
            throw new IllegalArgumentException(
                    "Routine representation is not eligible for body exchange: " + representation);
        }

        RoutineIdentity identity = requireExactAttachedIdentity(routine);
        RoutineBody body = RoutineBody.create(raw, canonical, profile.keepNewLines());
        if (!(body.measure() instanceof RoutineFingerprint fingerprint)) {
            routine.setBody(canonical);
            return OwnedRoutineBodySource.analysisOnly(body);
        }

        var authorization = new RoutineBodyAuthorization(profile, representation, fingerprint);
        var source = new DeferredRoutineBodySource(authorization);
        slots.add(new PgJdbcRoutineBodySlot(identity, routine, source, body));
        return source;
    }

    /**
     * Registers one exact final-model routine using only bounded catalog
     * metadata. Its raw body must be resolved and rehashed before analysis.
     */
    public synchronized RoutineBodySource registerFingerprint(
            PgAbstractFunction routine, long bodyOid, long metadataOrdinal,
            RoutineFingerprint fingerprint, RoutineBodyProfile profile,
            RoutineBodyRepresentation representation) {
        requireOpen();
        Objects.requireNonNull(routine, "routine");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(representation, "representation");
        if (!representation.isExchangeEligible()) {
            throw new IllegalArgumentException(
                    "Routine representation is not eligible for body exchange: " + representation);
        }

        RoutineIdentity identity = requireExactAttachedIdentity(routine);
        var authorization = new RoutineBodyAuthorization(profile, representation, fingerprint);
        var source = new DeferredRoutineBodySource(authorization);
        slots.add(new PgJdbcRoutineBodySlot(
                identity, routine, source, bodyOid, metadataOrdinal));
        return source;
    }

    /**
     * Resolves every registered slot exactly once and clears all loader-owned
     * corpus references before returning.
     */
    public void resolveAll(IMonitor monitor) throws IOException, InterruptedException {
        List<PgJdbcRoutineBodySlot> batch;
        synchronized (this) {
            requireOpen();
            state = State.RESOLVING;
            batch = slots;
            slots = null;
        }
        List<PgJdbcRoutineBodySlot> view = Collections.unmodifiableList(batch);

        Throwable failure = null;
        try {
            checkCancelled(monitor);
            resolver.resolve(view, monitor);
            checkCancelled(monitor);
            for (PgJdbcRoutineBodySlot slot : batch) {
                slot.requireResolved();
            }
            closeResolver();
            checkCancelled(monitor);
            for (PgJdbcRoutineBodySlot slot : batch) {
                slot.releaseResolved();
            }
            batch.clear();
            synchronized (this) {
                if (state != State.RESOLVING) {
                    throw new IllegalStateException(
                            "JDBC routine-body resolution lost active ownership: " + state);
                }
                state = State.RESOLVED;
            }
            return;
        } catch (IOException | InterruptedException | RuntimeException | Error ex) {
            failure = ex;
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            closeResolver();
        } catch (IOException | RuntimeException | Error ex) {
            failure = addFailure(failure, ex);
        }
        failure = closeSlots(batch, failure);
        synchronized (this) {
            state = State.CLOSED;
        }
        rethrowResolutionFailure(failure);
    }

    synchronized int pendingCount() {
        return slots == null ? 0 : slots.size();
    }

    /**
     * Closes registrations only while no resolver owns the detached batch.
     * The loading owner must finish or fail {@link #resolveAll(IMonitor)}
     * before terminal cleanup.
     */
    @Override
    public void close() {
        List<PgJdbcRoutineBodySlot> batch;
        synchronized (this) {
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.RESOLVING) {
                throw new IllegalStateException(
                        "Cannot close JDBC routine bodies while resolution is active");
            }
            batch = slots;
            slots = null;
            state = State.CLOSED;
        }
        Throwable failure = null;
        try {
            closeResolver();
        } catch (IOException | RuntimeException | Error ex) {
            failure = ex;
        }
        failure = closeSlots(batch, failure);
        if (failure instanceof IOException io) {
            throw new IllegalStateException("Failed to close JDBC routine-body resolver", io);
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private synchronized void closeResolver() throws IOException {
        if (resolverClosed) {
            return;
        }
        resolverClosed = true;
        PgJdbcRoutineBodyResolver ownedResolver = resolver;
        resolver = null;
        ownedResolver.close();
    }

    private static Throwable addFailure(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary == secondary) {
            return primary;
        }
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == secondary) {
                return primary;
            }
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    private synchronized void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException(
                    "JDBC routine-body resolution is not open: " + state);
        }
    }

    private static Throwable closeSlots(List<PgJdbcRoutineBodySlot> batch,
                                        Throwable primary) {
        Throwable failure = primary;
        if (batch != null) {
            for (PgJdbcRoutineBodySlot slot : batch) {
                try {
                    slot.close();
                } catch (RuntimeException | Error ex) {
                    failure = addFailure(failure, ex);
                }
            }
            batch.clear();
        }
        return failure;
    }

    private static RoutineIdentity requireExactAttachedIdentity(PgAbstractFunction routine) {
        RoutineIdentity identity;
        try {
            identity = RoutineIdentity.from(routine);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "JDBC routine must be attached to its final schema", ex);
        }
        if (!(routine.getParent() instanceof PgSchema schema)
                || schema.getFunction(identity.signature()) != routine) {
            throw new IllegalArgumentException(
                    "JDBC routine must be the exact final schema child: " + identity);
        }
        return identity;
    }

    private static void checkCancelled(IMonitor monitor) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        IMonitor.checkCancelled(monitor);
    }

    private static void rethrowResolutionFailure(Throwable failure)
            throws IOException, InterruptedException {
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unknown JDBC routine-body resolution failure", failure);
    }
}
