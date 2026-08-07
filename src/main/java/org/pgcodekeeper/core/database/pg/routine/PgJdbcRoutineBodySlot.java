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

import java.util.Objects;

import org.pgcodekeeper.core.database.base.parser.launcher.DeferredAnalysisStateException;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;

final class PgJdbcRoutineBodySlot implements AutoCloseable {

    private enum State {
        UNRESOLVED,
        RESOLVED,
        RELEASED,
        CLOSED
    }

    private RoutineIdentity identity;
    private PgAbstractFunction routine;
    private DeferredRoutineBodySource source;
    private RoutineBody fullBody;
    private long bodyOid;
    private long metadataOrdinal;
    private boolean divergenceBlocked;
    private State state = State.UNRESOLVED;

    PgJdbcRoutineBodySlot(RoutineIdentity identity, PgAbstractFunction routine,
                          DeferredRoutineBodySource source, RoutineBody fullBody) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.routine = Objects.requireNonNull(routine, "routine");
        this.source = Objects.requireNonNull(source, "source");
        this.fullBody = Objects.requireNonNull(fullBody, "fullBody");
    }

    PgJdbcRoutineBodySlot(RoutineIdentity identity, PgAbstractFunction routine,
                          DeferredRoutineBodySource source, long bodyOid,
                          long metadataOrdinal) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.routine = Objects.requireNonNull(routine, "routine");
        this.source = Objects.requireNonNull(source, "source");
        if (bodyOid <= 0L) {
            throw new IllegalArgumentException("Routine body OID must be positive");
        }
        if (metadataOrdinal <= 0L) {
            throw new IllegalArgumentException("Routine metadata ordinal must be positive");
        }
        this.bodyOid = bodyOid;
        this.metadataOrdinal = metadataOrdinal;
    }

    RoutineIdentity identity() {
        if (identity == null) {
            throw new IllegalStateException("JDBC routine-body slot was released");
        }
        return identity;
    }

    void resolveFullBody() {
        if (state != State.UNRESOLVED) {
            throw new IllegalStateException(
                    "JDBC routine-body slot cannot resolve from state " + state);
        }

        RoutineBody body = fullBody;
        DeferredRoutineBodySource deferred = source;
        PgAbstractFunction function = routine;
        deferred.resolveSame(body, deferred.authorization());
        function.setBody(body.canonical());
        fullBody = null;
        state = State.RESOLVED;
    }

    long bodyOid() {
        requireFingerprintSlot();
        return bodyOid;
    }

    long metadataOrdinal() {
        requireFingerprintSlot();
        return metadataOrdinal;
    }

    long predictedUtf8Bytes() {
        requireFingerprintSlot();
        return source.authorization().fingerprint().utf8Length();
    }

    RoutineBodyAuthorization authorization() {
        requireFingerprintSlot();
        return source.authorization();
    }

    boolean requiresResidual() {
        requireFingerprintSlot();
        return state == State.UNRESOLVED;
    }

    boolean resolveProjectCandidate(ProjectRoutineBodyCandidate candidate) {
        requireFingerprintSlot();
        if (state != State.UNRESOLVED) {
            throw new IllegalStateException(
                    "JDBC routine-body slot cannot resolve from state " + state);
        }
        DeferredRoutineBodySource deferred = source;
        if (!deferred.authorization().matches(candidate.authorization())) {
            return false;
        }
        RoutineBody body = candidate.shareTo(deferred);
        routine.setBody(body.canonical());
        state = State.RESOLVED;
        return true;
    }

    /**
     * Excludes this slot from divergence. Used for ambiguous project
     * identities where the exchange never produced a fingerprint verdict, so
     * only a real fetch can decide equality.
     */
    void blockDivergence() {
        divergenceBlocked = true;
    }

    boolean isDivergenceBlocked() {
        return divergenceBlocked;
    }

    /**
     * Resolves an unmatched old-side slot without fetching its body text. The
     * routine receives a fingerprint-derived sentinel that can never compare
     * equal to a real body, and the deferred lease becomes divergent so any
     * later parse attempt fails closed.
     */
    void resolveDivergent() {
        requireFingerprintSlot();
        if (state != State.UNRESOLVED) {
            throw new IllegalStateException(
                    "JDBC routine-body slot cannot resolve from state " + state);
        }
        if (divergenceBlocked) {
            throw new IllegalStateException(
                    "JDBC routine-body slot is excluded from divergence");
        }
        DeferredRoutineBodySource deferred = source;
        routine.setBody(PgRoutineBodyDivergencePolicy.divergentSentinel(
                deferred.authorization().fingerprint()));
        deferred.markDivergent();
        state = State.RESOLVED;
    }

    void resolveResidual(String raw) {
        requireFingerprintSlot();
        if (state != State.UNRESOLVED) {
            throw new IllegalStateException(
                    "JDBC routine-body slot cannot resolve from state " + state);
        }
        DeferredRoutineBodySource deferred = source;
        RoutineBodyAuthorization authorization = deferred.authorization();
        String canonical = PgRoutineBodyCanonicalizer.canonicalizePlainText(
                raw, authorization.profile().keepNewLines());
        RoutineBody body = deferred.resolve(raw, canonical,
                authorization.profile(), authorization.representation());
        routine.setBody(body.canonical());
        state = State.RESOLVED;
    }

    void requireResolved() {
        if (state != State.RESOLVED) {
            throw new DeferredAnalysisStateException(
                    "JDBC routine body was not resolved for " + identity());
        }
    }

    void releaseResolved() {
        requireResolved();
        identity = null;
        routine = null;
        source = null;
        fullBody = null;
        bodyOid = 0L;
        metadataOrdinal = 0L;
        state = State.RELEASED;
    }

    @Override
    public void close() {
        if (state == State.CLOSED || state == State.RELEASED) {
            return;
        }
        DeferredRoutineBodySource deferred = source;
        identity = null;
        routine = null;
        source = null;
        fullBody = null;
        bodyOid = 0L;
        metadataOrdinal = 0L;
        state = State.CLOSED;
        if (deferred != null) {
            deferred.close();
        }
    }

    private void requireFingerprintSlot() {
        if (bodyOid <= 0L || metadataOrdinal <= 0L || fullBody != null) {
            throw new IllegalStateException("JDBC routine-body slot has no fingerprint metadata");
        }
    }
}
