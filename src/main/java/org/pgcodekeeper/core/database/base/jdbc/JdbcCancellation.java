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
package org.pgcodekeeper.core.database.base.jdbc;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Internal cancellation state shared by JDBC loader components.
 * <p>
 * Every resource kind is tracked as an identity set in registration order, so
 * one load operation may own several concurrently active statements and
 * connections (for example the lane-parallel catalog readers). Terminal
 * cancellation acts on every resource owned at the time of the request.
 * <p>
 * This public type connects implementation classes in sibling packages. It is
 * not a supported extension API.
 */
public final class JdbcCancellation {

    private static final String CANCELLED_MESSAGE = "JDBC operation was cancelled";
    private static final String STATEMENT_FAILURE = "Failed to cancel active JDBC statement";
    private static final String CONNECTION_FAILURE = "Failed to close active JDBC connection";
    private static final Object OPEN = new Object();
    private static final Object DRAINED = new Object();

    private final Object drainMonitor = new Object();
    private final ThreadLocal<Boolean> cancellationAction = new ThreadLocal<>();
    private final List<Future<?>> futures = new ArrayList<>();
    private final List<Statement> statements = new ArrayList<>();
    private final List<Connection> connections = new ArrayList<>();
    private volatile Object cancellationState = OPEN;

    /**
     * Registers the currently active query future.
     *
     * @param activeFuture future to cancel when cancellation is requested
     * @throws IOException          if post-registration cancellation fails
     * @throws InterruptedException if cancellation already won the race
     */
    public void registerFuture(Future<?> activeFuture) throws IOException, InterruptedException {
        register(futures, activeFuture, value -> value.cancel(true), CANCELLED_MESSAGE);
    }

    /**
     * Submits a query and publishes its future as one operation with respect to
     * cancellation. The executor must return promptly from {@code submit}; the
     * submitted JDBC work itself runs outside the registry monitor.
     */
    <T> Future<T> submitFuture(ExecutorService executor, Callable<T> task)
            throws IOException, InterruptedException {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(task, "task");

        Future<T> submitted;
        synchronized (drainMonitor) {
            if (cancellationState != OPEN) {
                throw new InterruptedException(CANCELLED_MESSAGE);
            }
            submitted = Objects.requireNonNull(executor.submit(task), "submitted future");
            if (cancellationState == OPEN) {
                addIdentity(futures, submitted);
                return submitted;
            }
        }

        // A same-thread executor callback may re-enter cancellation while the
        // monitor is held. Self-claim the returned future after leaving the
        // critical section so it cannot execute after that completed drain.
        registerFuture(submitted);
        throw new AssertionError("Cancelled future registration unexpectedly succeeded");
    }

    /**
     * Linearizes successful result publication with terminal cancellation.
     * The future, and optionally its statement, are cleared only while the
     * registry is still open.
     */
    boolean completeSuccess(Future<?> activeFuture, Statement activeStatement,
            boolean retainStatement, boolean externalCancellation) {
        Objects.requireNonNull(activeFuture, "activeFuture");
        Objects.requireNonNull(activeStatement, "activeStatement");

        synchronized (drainMonitor) {
            if (externalCancellation || cancellationState != OPEN) {
                return false;
            }
            removeIdentity(futures, activeFuture);
            if (!retainStatement) {
                removeIdentity(statements, activeStatement);
            }
            return true;
        }
    }

    /**
     * Clears a future only when it is still a registered instance.
     *
     * @param activeFuture expected registered future
     */
    public void clearFuture(Future<?> activeFuture) {
        clear(futures, Objects.requireNonNull(activeFuture, "activeFuture"));
    }

    /**
     * Registers the currently active JDBC statement.
     *
     * @param activeStatement statement to cancel when cancellation is requested
     * @throws IOException          if post-registration cancellation fails
     * @throws InterruptedException if cancellation already won the race
     */
    public void registerStatement(Statement activeStatement) throws IOException, InterruptedException {
        register(statements, activeStatement, Statement::cancel, STATEMENT_FAILURE);
    }

    /**
     * Clears a statement only when it is still a registered instance.
     *
     * @param activeStatement expected registered statement
     */
    public void clearStatement(Statement activeStatement) {
        clear(statements, Objects.requireNonNull(activeStatement, "activeStatement"));
    }

    /**
     * Registers a currently owned JDBC connection.
     *
     * @param activeConnection connection to close when cancellation is requested
     * @throws IOException          if post-registration cancellation fails
     * @throws InterruptedException if cancellation already won the race
     */
    public void registerConnection(Connection activeConnection) throws IOException, InterruptedException {
        register(connections, activeConnection, Connection::close, CONNECTION_FAILURE);
    }

    /**
     * Clears a connection only when it is still a registered instance.
     *
     * @param activeConnection expected registered connection
     */
    public void clearConnection(Connection activeConnection) {
        clear(connections, Objects.requireNonNull(activeConnection, "activeConnection"));
    }

    /**
     * Requests terminal cancellation and acts on all resources owned at the
     * time of the request. Repeated requests wait for the first drain to
     * finish without repeating its actions or failure.
     *
     * @throws IOException if a JDBC cancellation operation fails
     */
    public void cancelActive() throws IOException {
        Thread current = Thread.currentThread();
        boolean drain;
        synchronized (drainMonitor) {
            drain = cancellationState == OPEN;
            if (drain) {
                cancellationState = current;
            }
        }
        if (!drain) {
            awaitCancellationComplete();
            return;
        }

        try {
            List<Future<?>> activeFutures;
            List<Statement> activeStatements;
            List<Connection> activeConnections;
            synchronized (drainMonitor) {
                activeFutures = new ArrayList<>(futures);
                activeStatements = new ArrayList<>(statements);
                activeConnections = new ArrayList<>(connections);
                futures.clear();
                statements.clear();
                connections.clear();
            }

            var failures = new FailureCollector();
            for (Future<?> activeFuture : activeFutures) {
                runCancellationAction(failures, () -> activeFuture.cancel(true), CANCELLED_MESSAGE);
            }
            for (Statement activeStatement : activeStatements) {
                runCancellationAction(failures, activeStatement::cancel, STATEMENT_FAILURE);
            }
            for (Connection activeConnection : activeConnections) {
                runCancellationAction(failures, activeConnection::close, CONNECTION_FAILURE);
            }
            failures.throwIfAny();
        } finally {
            synchronized (drainMonitor) {
                cancellationState = DRAINED;
                drainMonitor.notifyAll();
            }
        }
    }

    /**
     * Forgets all currently observed resources without acting on them.
     */
    public void clearReferences() {
        synchronized (drainMonitor) {
            if (cancellationState == OPEN) {
                futures.clear();
                statements.clear();
                connections.clear();
            }
        }
        awaitCancellationComplete();
    }

    /**
     * Returns whether terminal cancellation has been requested.
     *
     * @return {@code true} after the first cancellation request
     */
    public boolean isCancellationRequested() {
        return cancellationState != OPEN;
    }

    private <T> void register(List<T> registry, T resource,
            CancellationAction<T> action, String checkedFailureMessage)
            throws IOException, InterruptedException {
        Objects.requireNonNull(resource, "resource");
        synchronized (drainMonitor) {
            if (cancellationState == OPEN) {
                addIdentity(registry, resource);
                return;
            }
        }

        FailureCollector failures = null;
        try {
            failures = new FailureCollector();
            runCancellationAction(failures, () -> action.run(resource), checkedFailureMessage);
        } finally {
            awaitCancellationComplete();
        }
        failures.throwIfAny();
        throw new InterruptedException(CANCELLED_MESSAGE);
    }

    private <T> void clear(List<T> registry, T resource) {
        synchronized (drainMonitor) {
            if (cancellationState == OPEN) {
                removeIdentity(registry, resource);
            }
        }
        awaitCancellationComplete();
    }

    /**
     * Adds one exact instance in registration order at most once, so a resource
     * published by two cooperating owners is acted on and cleared exactly once.
     */
    private static <T> void addIdentity(List<T> registry, T resource) {
        for (T registered : registry) {
            if (registered == resource) {
                return;
            }
        }
        registry.add(resource);
    }

    private static <T> void removeIdentity(List<T> registry, T resource) {
        for (int i = 0; i < registry.size(); i++) {
            if (registry.get(i) == resource) {
                registry.remove(i);
                return;
            }
        }
    }

    private void runCancellationAction(FailureCollector failures, JdbcAction action,
            String checkedFailureMessage) {
        Boolean previous = cancellationAction.get();
        cancellationAction.set(Boolean.TRUE);
        try {
            failures.run(action, checkedFailureMessage);
        } finally {
            if (previous == null) {
                cancellationAction.remove();
            } else {
                cancellationAction.set(previous);
            }
        }
    }

    private void awaitCancellationComplete() {
        Thread current = Thread.currentThread();
        boolean interrupted = false;
        synchronized (drainMonitor) {
            while (cancellationState != OPEN
                    && cancellationState != DRAINED
                    && cancellationState != current
                    && cancellationAction.get() == null) {
                try {
                    drainMonitor.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            current.interrupt();
        }
    }

    @FunctionalInterface
    private interface CancellationAction<T> {

        void run(T resource) throws SQLException;
    }

    @FunctionalInterface
    private interface JdbcAction {

        void run() throws SQLException;
    }

    private static final class FailureCollector {

        private final List<Throwable> sources = new ArrayList<>(3);
        private Throwable primary;

        private void run(JdbcAction action, String checkedFailureMessage) {
            try {
                action.run();
            } catch (SQLException e) {
                add(e, new IOException(checkedFailureMessage));
            } catch (RuntimeException | Error e) {
                add(e, e);
            }
        }

        private void add(Throwable source, Throwable exposed) {
            for (Throwable seen : sources) {
                if (seen == source) {
                    return;
                }
            }
            sources.add(source);
            if (primary == null) {
                primary = exposed;
            } else if (primary != exposed) {
                primary.addSuppressed(exposed);
            }
        }

        private void throwIfAny() throws IOException {
            if (primary instanceof IOException e) {
                throw e;
            }
            if (primary instanceof RuntimeException e) {
                throw e;
            }
            if (primary instanceof Error e) {
                throw e;
            }
        }
    }
}
