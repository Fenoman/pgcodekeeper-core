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

import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.utils.PhaseTimer;

/**
 * Cancellation-aware, one-shot handoff for the final project routine catalog.
 * The channel never stores the catalog after a successful take.
 */
public final class ProjectRoutineBodyCatalogChannel implements AutoCloseable {

    private static final long CANCELLATION_POLL_MILLIS = 25;

    private enum State {
        DORMANT,
        OPEN,
        PUBLISHING,
        PUBLISHED,
        TAKEN,
        FAILED,
        CANCELLED,
        CLOSED
    }

    private ProjectRoutineBodyCatalog catalog;
    private Throwable failure;
    private State state = State.DORMANT;

    /**
     * Enables collection before either comparison loader starts.
     */
    public synchronized void open() {
        if (state != State.DORMANT) {
            throw new IllegalStateException("Project routine catalog channel cannot open from " + state);
        }
        state = State.OPEN;
    }

    /**
     * Builds and publishes only when a paired consumer opened the channel.
     * Construction failure is retained and wakes a direct consumer.
     *
     * @return true when a catalog was published
     */
    public boolean publishIfOpen(PgDatabase database) {
        return publish(database, true);
    }

    /**
     * Coordinator-only publication variant. The comparison coordinator owns
     * the original side failure, so construction failure is rethrown without
     * waking the peer until its binding publishes induced cancellation.
     */
    boolean publishForComparison(PgDatabase database) {
        return publish(database, false);
    }

    private boolean publish(PgDatabase database, boolean retainBuildFailure) {
        Objects.requireNonNull(database, "database");
        synchronized (this) {
            if (state == State.DORMANT) {
                return false;
            }
            if (state != State.OPEN) {
                return false;
            }
            state = State.PUBLISHING;
        }

        ProjectRoutineBodyCatalog built;
        try {
            long start = PhaseTimer.start();
            built = ProjectRoutineBodyCatalog.build(database);
            PhaseTimer.end("routine_fingerprints", start, "project_catalog");
        } catch (RuntimeException | Error ex) {
            if (retainBuildFailure) {
                failPublication(ex);
            } else {
                abortPublication();
            }
            throw ex;
        }

        synchronized (this) {
            if (state != State.PUBLISHING) {
                built.close();
                return false;
            }
            catalog = built;
            state = State.PUBLISHED;
            notifyAll();
            return true;
        }
    }

    /**
     * Publishes an already built catalog and transfers ownership to this
     * channel. Intended for binding tests and narrowly scoped integrations.
     */
    public synchronized void publish(ProjectRoutineBodyCatalog value) {
        Objects.requireNonNull(value, "catalog");
        if (state != State.OPEN) {
            throw new IllegalStateException("Project routine catalog cannot publish from " + state);
        }
        catalog = value;
        state = State.PUBLISHED;
        notifyAll();
    }

    /**
     * Publishes a prebuilt catalog only while a consumer is still waiting.
     * Ownership always transfers to this method.
     */
    public synchronized boolean publishIfOpen(ProjectRoutineBodyCatalog value) {
        Objects.requireNonNull(value, "catalog");
        if (state != State.OPEN) {
            value.close();
            return false;
        }
        catalog = value;
        state = State.PUBLISHED;
        notifyAll();
        return true;
    }

    /**
     * Waits for and transfers the catalog. The internal reference is cleared
     * before this method returns.
     */
    public synchronized ProjectRoutineBodyCatalog take(IMonitor monitor)
            throws InterruptedException {
        if (state == State.DORMANT) {
            throw new IllegalStateException("Project routine catalog channel is not open");
        }
        while (state == State.OPEN || state == State.PUBLISHING) {
            checkCancelled(monitor);
            try {
                wait(CANCELLATION_POLL_MILLIS);
            } catch (InterruptedException ex) {
                if (state == State.FAILED) {
                    // Preserve the producer's exact failure while restoring
                    // the caller interrupt consumed by Object.wait().
                    Thread.currentThread().interrupt();
                    break;
                }
                transitionToCancelled();
                throw ex;
            }
        }

        if (state == State.FAILED) {
            Throwable currentFailure = failure;
            failure = null;
            state = State.CLOSED;
            throw new ProjectRoutineBodyCatalogException(
                    "Project routine body catalog producer failed", currentFailure);
        }
        if (state == State.CANCELLED) {
            throw new InterruptedException();
        }
        if (state == State.PUBLISHED) {
            // Publication wins only after this cancellation linearization point.
            checkCancelled(monitor);
            ProjectRoutineBodyCatalog current = catalog;
            catalog = null;
            state = State.TAKEN;
            return current;
        }
        throw new IllegalStateException("Project routine catalog is unavailable: " + state);
    }

    /**
     * Publishes the producer's exact failure and wakes a waiting consumer.
     */
    public synchronized void fail(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        if (isTerminal(state)) {
            return;
        }
        clearCatalog();
        if (failure == null) {
            failure = cause;
        }
        state = State.FAILED;
        notifyAll();
    }

    /**
     * Cancels publication/waiting and clears any untaken catalog.
     */
    public synchronized void cancel() {
        transitionToCancelled();
    }

    private void checkCancelled(IMonitor monitor) throws InterruptedException {
        if (Thread.interrupted()) {
            transitionToCancelled();
            throw new InterruptedException();
        }
        try {
            IMonitor.checkCancelled(monitor);
        } catch (InterruptedException ex) {
            transitionToCancelled();
            throw ex;
        }
    }

    private void transitionToCancelled() {
        if (isTerminal(state)) {
            return;
        }
        clearCatalog();
        failure = null;
        state = State.CANCELLED;
        notifyAll();
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        clearCatalog();
        failure = null;
        state = State.CLOSED;
        notifyAll();
    }

    private synchronized void abortPublication() {
        if (state == State.PUBLISHING) {
            // The comparison binding will publish the original loader failure
            // through sideFailed. Keep the waiter non-terminal until then so a
            // derived wrapper cannot win the coordinator's fail-first race.
            state = State.OPEN;
        }
    }

    private synchronized void failPublication(Throwable cause) {
        if (state == State.PUBLISHING) {
            failure = cause;
            state = State.FAILED;
            notifyAll();
        }
    }

    private void clearCatalog() {
        ProjectRoutineBodyCatalog current = catalog;
        catalog = null;
        if (current != null) {
            current.close();
        }
    }

    private static boolean isTerminal(State current) {
        return current == State.TAKEN
                || current == State.FAILED
                || current == State.CANCELLED
                || current == State.CLOSED;
    }
}
