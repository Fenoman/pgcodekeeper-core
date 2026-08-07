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

import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;

/**
 * Project-owned routine-body lease. A successful exact match may create a
 * second independent deferred lease without duplicating either String.
 */
public final class OwnedRoutineBodySource implements RoutineBodySource {

    private enum State {
        AVAILABLE,
        SHARED,
        TAKEN,
        CLOSED
    }

    private final RoutineBodyMeasure measure;
    private final RoutineBodyAuthorization authorization;

    private RoutineBody body;
    private State state = State.AVAILABLE;

    private OwnedRoutineBodySource(RoutineBody body,
                                   RoutineBodyAuthorization authorization) {
        this.body = Objects.requireNonNull(body, "body");
        this.measure = body.measure();
        this.authorization = authorization;
    }

    /**
     * Creates a local-only source that can be analyzed but can never authorize
     * cross-loader body reuse.
     */
    public static OwnedRoutineBodySource analysisOnly(String raw, String canonical) {
        return analysisOnly(RoutineBody.create(raw, canonical));
    }

    static OwnedRoutineBodySource analysisOnly(RoutineBody body) {
        return new OwnedRoutineBodySource(body, null);
    }

    static OwnedRoutineBodySource exchangeCandidate(
            RoutineBody body, RoutineBodyAuthorization authorization) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(authorization, "authorization");
        if (!body.measure().equals(authorization.fingerprint())) {
            throw new IllegalArgumentException(
                    "Routine body and authorization fingerprints differ");
        }
        return new OwnedRoutineBodySource(body, authorization);
    }

    /**
     * Creates an exchange candidate. Malformed UTF-16 remains analyzable but
     * deliberately has no reusable authorization.
     */
    public static OwnedRoutineBodySource exchangeCandidate(
            String raw, String canonical, RoutineBodyProfile profile,
            RoutineBodyRepresentation representation) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(representation, "representation");
        if (!representation.isExchangeEligible()) {
            throw new IllegalArgumentException(
                    "Routine representation is not eligible for body exchange: " + representation);
        }

        RoutineBody body = RoutineBody.create(raw, canonical, profile.keepNewLines());
        RoutineBodyAuthorization authorization = body.measure() instanceof RoutineFingerprint fingerprint
                ? new RoutineBodyAuthorization(profile, representation, fingerprint)
                : null;
        return new OwnedRoutineBodySource(body, authorization);
    }

    public RoutineBodyMeasure measure() {
        return measure;
    }

    public RoutineBodyAuthorization requireAuthorization() {
        if (authorization == null) {
            throw new IllegalStateException("Routine body cannot authorize cross-loader reuse");
        }
        return authorization;
    }

    /**
     * Returns whether this source is a cheap upper-bound candidate for the
     * project catalog, without allocating an identity or wrapper.
     */
    public synchronized boolean isProjectCandidateAvailable() {
        return state == State.AVAILABLE && authorization != null && body != null;
    }

    synchronized RoutineBody snapshotBody() {
        return isProjectCandidateAvailable() ? body : null;
    }

    /**
     * Creates a catalog entry only while this source still owns the exact
     * canonical String stored by the final project-model routine.
     */
    public synchronized ProjectRoutineBodyCandidate projectCandidate(
            RoutineIdentity identity, PgAbstractFunction finalRoutine) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(finalRoutine, "finalRoutine");
        if (!isProjectCandidateAvailable()
                || !finalRoutine.hasBodyReference(body.canonical())) {
            return null;
        }
        return new ProjectRoutineBodyCandidate(identity, this);
    }

    /**
     * Resolves a compatible deferred lease with this exact payload. The project
     * lease remains independently consumable.
     */
    public synchronized RoutineBody shareTo(DeferredRoutineBodySource target) {
        Objects.requireNonNull(target, "target");
        if (state != State.AVAILABLE) {
            throw new IllegalStateException("Owned routine body is not available for sharing: " + state);
        }
        RoutineBodyAuthorization currentAuthorization = requireAuthorization();
        RoutineBody currentBody = body;
        target.resolveProjectShared(currentBody, currentAuthorization);
        state = State.SHARED;
        return currentBody;
    }

    /**
     * Returns whether the exact payload of this source was shared with a
     * matching JDBC consumer, proving the body byte-identical between the
     * project and the compared database. Absent a comparison or a match the
     * source stays unshared, keeping the analysis-skip decision fail-open.
     *
     * @return true only after a successful hash-first share
     */
    public synchronized boolean isSharedWithMatchedConsumer() {
        return state == State.SHARED;
    }

    @Override
    public synchronized RoutineBody take() {
        if (state != State.AVAILABLE && state != State.SHARED) {
            throw new IllegalStateException("Owned routine body is not available: " + state);
        }
        RoutineBody current = body;
        body = null;
        state = State.TAKEN;
        return current;
    }

    @Override
    public long estimatedUtf8Bytes() {
        return measure.utf8Length();
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        body = null;
        state = State.CLOSED;
    }
}
