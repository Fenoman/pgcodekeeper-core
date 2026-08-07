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

/**
 * JDBC-side routine-body lease resolved either by exact project reuse or by a
 * verified residual read from the same database snapshot.
 */
public final class DeferredRoutineBodySource implements RoutineBodySource {

    private enum State {
        UNRESOLVED,
        RESOLVED,
        DIVERGENT,
        FAILED,
        TAKEN,
        CLOSED
    }

    private final RoutineBodyAuthorization authorization;

    private RoutineBody body;
    private Throwable failure;
    private boolean resolvedByProjectMatch;
    private State state = State.UNRESOLVED;

    public DeferredRoutineBodySource(RoutineBodyAuthorization authorization) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    public RoutineBodyAuthorization authorization() {
        return authorization;
    }

    /**
     * Resolves a residual body after rehashing its raw input and validating the
     * complete profile, representation and fingerprint.
     */
    public RoutineBody resolve(String raw, String canonical,
                               RoutineBodyProfile profile,
                               RoutineBodyRepresentation representation) {
        RoutineBody candidate = RoutineBody.create(raw, canonical, profile.keepNewLines());
        if (!(candidate.measure() instanceof RoutineFingerprint fingerprint)) {
            throw new IllegalArgumentException("Malformed UTF-16 routine body cannot satisfy authorization");
        }
        RoutineBodyAuthorization actual =
                new RoutineBodyAuthorization(profile, representation, fingerprint);
        return resolveSame(candidate, actual);
    }

    /**
     * Resolves this lease with an exact project-owned payload and remembers
     * the match, so a byte-identical late-bound body can later skip its
     * deferred parse and dependency analysis.
     */
    synchronized RoutineBody resolveProjectShared(RoutineBody candidate,
                                                  RoutineBodyAuthorization actual) {
        RoutineBody resolved = resolveSame(candidate, actual);
        resolvedByProjectMatch = true;
        return resolved;
    }

    /**
     * Returns whether this lease currently holds a body proven byte-identical
     * to the final project model by the hash-first exchange. Consuming or
     * releasing the body clears the signal, keeping every other path fail-open.
     *
     * @return true only while a project-matched body is resolved and untaken
     */
    public synchronized boolean isResolvedByProjectMatch() {
        return resolvedByProjectMatch && state == State.RESOLVED;
    }

    synchronized RoutineBody resolveSame(RoutineBody candidate,
                                         RoutineBodyAuthorization actual) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(actual, "actual");
        if (!authorization.matches(actual)) {
            throw new IllegalArgumentException("Routine body authorization mismatch");
        }
        if (!actual.fingerprint().equals(candidate.measure())) {
            throw new IllegalArgumentException("Routine body payload does not match its authorization");
        }
        if (state != State.UNRESOLVED) {
            throw new IllegalStateException("Deferred routine body cannot be resolved from state " + state);
        }
        body = candidate;
        state = State.RESOLVED;
        return candidate;
    }

    /**
     * Marks this lease divergent: the hash-first exchange proved the body
     * different from the project peer and the old-side skip decided never to
     * fetch its text. A divergent lease deliberately has no payload, so any
     * attempt to parse it fails closed with a precise diagnostic.
     */
    synchronized void markDivergent() {
        if (state != State.UNRESOLVED) {
            throw new IllegalStateException(
                    "Deferred routine body cannot become divergent from state " + state);
        }
        state = State.DIVERGENT;
    }

    /**
     * Returns whether this lease was marked divergent and its body text was
     * intentionally never fetched from the server.
     */
    public synchronized boolean isDivergent() {
        return state == State.DIVERGENT;
    }

    /**
     * Records the first producer-side failure and clears any resolved body.
     */
    public synchronized void fail(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        if (state == State.CLOSED || state == State.TAKEN || state == State.DIVERGENT) {
            return;
        }
        body = null;
        if (failure == null) {
            failure = cause;
        }
        state = State.FAILED;
    }

    @Override
    public synchronized RoutineBody take() {
        if (state == State.RESOLVED) {
            RoutineBody current = body;
            body = null;
            failure = null;
            state = State.TAKEN;
            return current;
        }
        if (state == State.DIVERGENT) {
            // divergence is sticky: the lease keeps answering the same
            // fail-closed diagnostic without transitioning to TAKEN
            throw new DeferredAnalysisStateException(
                    "Divergent routine body was deliberately never fetched"
                            + " and cannot be analyzed");
        }

        Throwable currentFailure = state == State.FAILED ? failure : null;
        body = null;
        failure = null;
        State previous = state;
        if (state != State.CLOSED) {
            state = State.TAKEN;
        }
        String message = previous == State.UNRESOLVED
                ? "Deferred routine body was not resolved before analysis"
                : "Deferred routine body is unavailable: " + previous;
        if (currentFailure == null) {
            throw new DeferredAnalysisStateException(message);
        }
        throw new DeferredAnalysisStateException(message, currentFailure);
    }

    @Override
    public long estimatedUtf8Bytes() {
        return authorization.fingerprint().utf8Length();
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        body = null;
        failure = null;
        state = State.CLOSED;
    }
}
