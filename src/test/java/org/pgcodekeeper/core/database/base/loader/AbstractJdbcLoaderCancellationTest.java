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
package org.pgcodekeeper.core.database.base.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;

class AbstractJdbcLoaderCancellationTest {

    @Test
    void runnerSharesLoaderCancellationRegistry() throws Exception {
        var loader = new TestJdbcLoader();
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> {
            executeEntered.countDown();
            awaitUninterruptibly(releaseExecute);
        }, () -> {
            statementCancels.incrementAndGet();
            releaseExecute.countDown();
        }, () -> { });
        ExecutorService ownerExecutor = Executors.newSingleThreadExecutor();
        Future<?> owner = ownerExecutor.submit(() -> {
            loader.run(statement);
            return null;
        });

        try {
            assertTrue(executeEntered.await(5, TimeUnit.SECONDS));
            loader.cancel();
            assertEquals(1, statementCancels.get());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
        } finally {
            releaseExecute.countDown();
            owner.cancel(true);
            shutdown(ownerExecutor);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cancelAggregatesAntlrAndJdbcFailuresWithoutDuplicates(boolean sameFailure) throws Exception {
        var loader = new TestJdbcLoader();
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var statementCancels = new AtomicInteger();
        RuntimeException antlrFailure = new IllegalStateException("antlr failure");
        RuntimeException jdbcFailure = sameFailure
                ? antlrFailure
                : new IllegalArgumentException("jdbc failure");
        loader.addAntlrCancellationFailure(antlrFailure);
        Statement statement = statement(() -> {
            executeEntered.countDown();
            awaitUninterruptibly(releaseExecute);
        }, () -> {
            statementCancels.incrementAndGet();
            throw jdbcFailure;
        }, () -> { });
        ExecutorService ownerExecutor = Executors.newSingleThreadExecutor();
        Future<?> owner = ownerExecutor.submit(() -> {
            loader.run(statement);
            return null;
        });

        try {
            assertTrue(executeEntered.await(5, TimeUnit.SECONDS));
            RuntimeException thrown = assertThrows(RuntimeException.class, loader::cancel);

            assertSame(antlrFailure, thrown);
            assertEquals(1, statementCancels.get());
            if (sameFailure) {
                assertEquals(0, thrown.getSuppressed().length);
            } else {
                assertEquals(List.of(jdbcFailure), List.of(thrown.getSuppressed()));
            }
        } finally {
            releaseExecute.countDown();
            owner.cancel(true);
            shutdown(ownerExecutor);
        }
    }

    @Test
    void repeatedCancelWaitsForExistingJdbcDrain() throws Exception {
        var loader = new TestJdbcLoader();
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var cancelEntered = new CountDownLatch(1);
        var releaseCancel = new CountDownLatch(1);
        Statement statement = statement(() -> {
            executeEntered.countDown();
            awaitUninterruptibly(releaseExecute);
        }, () -> {
            cancelEntered.countDown();
            awaitUninterruptibly(releaseCancel);
            releaseExecute.countDown();
        }, () -> { });
        ExecutorService ownerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService cancelExecutor = Executors.newFixedThreadPool(2);
        Future<?> owner = ownerExecutor.submit(() -> {
            loader.run(statement);
            return null;
        });
        Future<?> firstCancel = null;
        Future<?> secondCancel = null;

        try {
            assertTrue(executeEntered.await(5, TimeUnit.SECONDS));
            firstCancel = cancelExecutor.submit(() -> {
                loader.cancel();
                return null;
            });
            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS));
            secondCancel = cancelExecutor.submit(() -> {
                loader.cancel();
                return null;
            });

            assertFalse(secondCancel.isDone());
            Future<?> waitingCancel = secondCancel;
            assertThrows(TimeoutException.class,
                    () -> waitingCancel.get(100, TimeUnit.MILLISECONDS));
        } finally {
            releaseCancel.countDown();
            releaseExecute.countDown();
            if (firstCancel != null) {
                firstCancel.get(5, TimeUnit.SECONDS);
            }
            if (secondCancel != null) {
                secondCancel.get(5, TimeUnit.SECONDS);
            }
            owner.cancel(true);
            shutdown(cancelExecutor);
            shutdown(ownerExecutor);
        }
    }

    @Test
    void clearActiveStatementWaitsBeforeOwnerClose() throws Exception {
        var loader = new TestJdbcLoader();
        var cancelEntered = new CountDownLatch(1);
        var releaseCancel = new CountDownLatch(1);
        var closeCalls = new AtomicInteger();
        Statement statement = statement(() -> { }, () -> {
            cancelEntered.countDown();
            awaitUninterruptibly(releaseCancel);
        }, closeCalls::incrementAndGet);
        loader.registerStatement(statement);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> cancellation = executor.submit(() -> {
            loader.cancel();
            return null;
        });
        Future<?> ownerClose = null;

        try {
            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS));
            ownerClose = executor.submit(() -> {
                loader.clearStatement(statement);
                statement.close();
                return null;
            });
            assertFalse(ownerClose.isDone());
            assertEquals(0, closeCalls.get());
        } finally {
            releaseCancel.countDown();
            cancellation.get(5, TimeUnit.SECONDS);
            if (ownerClose != null) {
                ownerClose.get(5, TimeUnit.SECONDS);
            }
            shutdown(executor);
        }

        assertEquals(1, closeCalls.get());
    }

    @Test
    void lateRegistrationDuringCancellationWaitsForDrainBeforeOwnerClose() throws Exception {
        var loader = new TestJdbcLoader();
        var connectionCloseEntered = new CountDownLatch(1);
        var releaseConnectionClose = new CountDownLatch(1);
        var statementCancelEntered = new CountDownLatch(1);
        var statementCloses = new AtomicInteger();
        loader.registerConnection(connection(() -> {
            connectionCloseEntered.countDown();
            awaitUninterruptibly(releaseConnectionClose);
        }));
        Statement lateStatement = statement(() -> { }, statementCancelEntered::countDown,
                statementCloses::incrementAndGet);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> cancellation = executor.submit(() -> {
            loader.cancel();
            return null;
        });
        Future<?> owner = null;

        try {
            assertTrue(connectionCloseEntered.await(5, TimeUnit.SECONDS));
            owner = executor.submit(() -> {
                assertThrows(InterruptedException.class,
                        () -> loader.registerStatement(lateStatement));
                lateStatement.close();
                return null;
            });

            assertTrue(statementCancelEntered.await(5, TimeUnit.SECONDS));
            assertFalse(owner.isDone());
            assertEquals(0, statementCloses.get());
        } finally {
            releaseConnectionClose.countDown();
            cancellation.get(5, TimeUnit.SECONDS);
            if (owner != null) {
                owner.get(5, TimeUnit.SECONDS);
            }
            shutdown(executor);
        }

        assertEquals(1, statementCloses.get());
    }

    @Test
    void lateRegistrationDrainsJdbcWhileLifecycleCancellationIsStillInAntlr() throws Exception {
        var loader = new TestJdbcLoader();
        var antlrCancelEntered = new CountDownLatch(1);
        var releaseAntlrCancel = new CountDownLatch(1);
        var connectionCloseEntered = new CountDownLatch(1);
        var releaseConnectionClose = new CountDownLatch(1);
        var statementCancels = new AtomicInteger();
        var statementCloses = new AtomicInteger();
        loader.addBlockingAntlrCancellation(antlrCancelEntered, releaseAntlrCancel);
        loader.registerConnection(connection(() -> {
            connectionCloseEntered.countDown();
            awaitUninterruptibly(releaseConnectionClose);
        }));
        Statement lateStatement = statement(() -> { }, statementCancels::incrementAndGet,
                statementCloses::incrementAndGet);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> cancellation = executor.submit(() -> {
            loader.cancel();
            return null;
        });
        Future<?> owner = null;

        try {
            assertTrue(antlrCancelEntered.await(5, TimeUnit.SECONDS));
            owner = executor.submit(() -> {
                assertThrows(InterruptedException.class,
                        () -> loader.registerStatement(lateStatement));
                lateStatement.close();
                return null;
            });

            assertTrue(connectionCloseEntered.await(5, TimeUnit.SECONDS));
            assertFalse(owner.isDone());
            assertEquals(1, statementCancels.get());
            assertEquals(0, statementCloses.get());
        } finally {
            releaseConnectionClose.countDown();
            releaseAntlrCancel.countDown();
            if (owner != null) {
                owner.get(5, TimeUnit.SECONDS);
            }
            cancellation.get(5, TimeUnit.SECONDS);
            shutdown(executor);
        }

        assertEquals(1, statementCloses.get());
    }

    @Test
    void registrationAfterCloseIsRejectedAndCancelDoesNotTouchJdbc() throws Exception {
        var loader = new TestJdbcLoader();
        var connectionCloses = new AtomicInteger();
        Connection connection = connection(connectionCloses::incrementAndGet);
        loader.registerConnection(connection);
        loader.close();

        assertThrows(IllegalStateException.class,
                () -> loader.registerConnection(connection(() -> { })));

        loader.cancel();

        assertEquals(0, connectionCloses.get());
    }

    @Test
    void releaseClearsJdbcRegistryBeforeRetainedState() throws Exception {
        var loader = new TestJdbcLoader();
        var statementCancels = new AtomicInteger();
        var connectionCloses = new AtomicInteger();
        Statement statement = statement(() -> { }, statementCancels::incrementAndGet, () -> { });
        Connection connection = connection(connectionCloses::incrementAndGet);
        loader.registerConnection(connection);
        loader.registerStatement(statement);
        loader.retain(connection, statement);

        loader.releaseNow();
        loader.cancel();

        assertEquals(0, statementCancels.get());
        assertEquals(0, connectionCloses.get());
        assertNull(loader.getConnection());
        assertNull(loader.getStatement());
        assertEquals(0, loader.schemaIds.size());
    }

    @Test
    void abstractLoaderExposesCheckedCancelAndProtectedFinalGate() throws Exception {
        Method cancel = AbstractLoader.class.getDeclaredMethod("cancel");
        Method gate = AbstractLoader.class.getDeclaredMethod("requireOpenForLoad");

        assertEquals(List.of(IOException.class), List.of(cancel.getExceptionTypes()));
        assertTrue(Modifier.isProtected(gate.getModifiers()));
        assertTrue(Modifier.isFinal(gate.getModifiers()));
    }

    @Test
    void jdbcCancellationObservationAndGateUseSharedState() throws Exception {
        var loader = new TestJdbcLoader();

        assertFalse(loader.jdbcCancellationRequested());
        loader.cancel();
        assertTrue(loader.jdbcCancellationRequested());
        assertThrows(InterruptedException.class, loader::checkJdbcCancellation);
    }

    @Test
    void jdbcCancellationClassificationDoesNotInspectDriverFailure() throws Exception {
        var loader = new TestJdbcLoader();
        SQLException raw = new SensitiveSQLException();

        assertThrows(IllegalStateException.class, () -> loader.classifyJdbcCancellation(raw));
        loader.cancel();
        InterruptedException interrupted = loader.classifyJdbcCancellation(raw);

        assertNull(interrupted.getCause());
        assertEquals(1, interrupted.getSuppressed().length);
        assertSame(raw, interrupted.getSuppressed()[0]);
    }

    @Test
    void checkedJdbcFailureRemainsPrimaryAndCollectsLaterRuntime() throws Exception {
        var loader = new TestJdbcLoader();
        var statementCancels = new AtomicInteger();
        var connectionCloses = new AtomicInteger();
        SQLException checkedFailure = new SQLException("checked failure");
        RuntimeException runtimeFailure = new IllegalStateException("runtime failure");
        Statement statement = statement(() -> { }, () -> {
            statementCancels.incrementAndGet();
            throw checkedFailure;
        }, () -> { });
        Connection connection = connection(() -> {
            connectionCloses.incrementAndGet();
            throw runtimeFailure;
        });
        loader.registerStatement(statement);
        loader.registerConnection(connection);

        IOException thrown = assertThrows(IOException.class, loader::cancel);

        assertEquals(1, statementCancels.get());
        assertEquals(1, connectionCloses.get());
        assertNull(thrown.getCause());
        assertEquals(List.of(runtimeFailure), List.of(thrown.getSuppressed()));
    }

    @Test
    void antlrErrorStaysPrimaryAndJdbcCancellationIsStillAttempted() throws Exception {
        var loader = new TestJdbcLoader();
        Error antlrFailure = new AssertionError("antlr error");
        RuntimeException jdbcFailure = new IllegalStateException("jdbc failure");
        var connectionCloses = new AtomicInteger();
        loader.addAntlrCancellationFailure(antlrFailure);
        loader.registerConnection(connection(() -> {
            connectionCloses.incrementAndGet();
            throw jdbcFailure;
        }));

        Error thrown = assertThrows(Error.class, loader::cancel);

        assertSame(antlrFailure, thrown);
        assertEquals(1, connectionCloses.get());
        assertEquals(List.of(jdbcFailure), List.of(thrown.getSuppressed()));
    }

    @Test
    void sharedErrorFromAntlrAndJdbcIsNotSelfSuppressed() throws Exception {
        var loader = new TestJdbcLoader();
        Error shared = new AssertionError("shared");
        var connectionCloses = new AtomicInteger();
        loader.addAntlrCancellationFailure(shared);
        loader.registerConnection(connection(() -> {
            connectionCloses.incrementAndGet();
            throw shared;
        }));

        Error thrown = assertThrows(Error.class, loader::cancel);

        assertSame(shared, thrown);
        assertEquals(1, connectionCloses.get());
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void ownedResourcesArePhysicallyClosedOnlyAfterCancellationDrain() throws Exception {
        var loader = new TestJdbcLoader();
        var cancelEntered = new CountDownLatch(1);
        var releaseCancel = new CountDownLatch(1);
        var statementCloses = new AtomicInteger();
        Statement statement = statement(() -> { }, () -> {
            cancelEntered.countDown();
            awaitUninterruptibly(releaseCancel);
        }, statementCloses::incrementAndGet);
        Connection connection = connection(() -> { });
        loader.registerConnection(connection);
        loader.registerStatement(statement);
        loader.retain(connection, statement);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> cancellation = executor.submit(() -> {
            loader.cancel();
            return null;
        });
        Future<Throwable> cleanup = null;

        try {
            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS));
            cleanup = executor.submit(() -> loader.finishResources(connection, statement, null));

            Future<Throwable> waitingCleanup = cleanup;
            assertThrows(TimeoutException.class, () -> waitingCleanup.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, statementCloses.get());
        } finally {
            releaseCancel.countDown();
            cancellation.get(5, TimeUnit.SECONDS);
            if (cleanup != null) {
                assertInstanceOf(InterruptedException.class, cleanup.get(5, TimeUnit.SECONDS));
            }
            shutdown(executor);
        }

        assertEquals(1, statementCloses.get());
        assertNull(loader.getStatement());
        assertNull(loader.getConnection());
    }

    @Test
    void ownedResourceCleanupAttemptsEveryPhysicalCloseInOrder() throws Exception {
        var loader = new TestJdbcLoader();
        var order = new java.util.ArrayList<String>();
        SQLException primary = new SQLException("primary");
        SQLException statementFailure = new SQLException("statement close");
        RuntimeException connectionFailure = new IllegalStateException("connection close");
        Statement statement = statement(() -> { }, () -> { }, () -> {
            order.add("statement.close");
            throw statementFailure;
        });
        Connection connection = connection(() -> {
            order.add("connection.close");
            throw connectionFailure;
        });
        loader.registerConnection(connection);
        loader.registerStatement(statement);
        loader.retain(connection, statement);

        Throwable thrown = loader.finishResources(connection, statement, primary);

        assertSame(primary, thrown);
        assertEquals(List.of(statementFailure, connectionFailure), List.of(thrown.getSuppressed()));
        assertEquals(List.of("statement.close", "connection.close"), order);
        assertNull(loader.getStatement());
        assertNull(loader.getConnection());
    }

    @Test
    void ownedResourceCleanupDoesNotSelfSuppressSameFailure() throws Exception {
        var loader = new TestJdbcLoader();
        SQLException shared = new SQLException("shared");
        Statement statement = statement(() -> { }, () -> { }, () -> {
            throw shared;
        });
        Connection connection = connection(() -> {
            throw shared;
        });
        loader.registerConnection(connection);
        loader.registerStatement(statement);
        loader.retain(connection, statement);

        Throwable thrown = loader.finishResources(connection, statement, shared);

        assertSame(shared, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void ownedResourceCleanupOnlyClearsMatchingRetainedIdentities() throws Exception {
        var loader = new TestJdbcLoader();
        Statement ownedStatement = statement(() -> { }, () -> { }, () -> { });
        Connection ownedConnection = connection(() -> { });
        Statement newerStatement = statement(() -> { }, () -> { }, () -> { });
        Connection newerConnection = connection(() -> { });
        loader.registerConnection(ownedConnection);
        loader.registerStatement(ownedStatement);
        loader.retain(newerConnection, newerStatement);

        Throwable thrown = loader.finishResources(ownedConnection, ownedStatement, null);

        assertNull(thrown);
        assertSame(newerStatement, loader.getStatement());
        assertSame(newerConnection, loader.getConnection());
    }

    private static Statement statement(SqlRun execute, SqlRun cancel, SqlRun close) {
        return proxy(Statement.class, (proxy, method, args) -> switch (method.getName()) {
        case "execute" -> {
            execute.run();
            yield false;
        }
        case "cancel" -> {
            cancel.run();
            yield null;
        }
        case "close" -> {
            close.run();
            yield null;
        }
        default -> objectMethod(proxy, method.getName(), args, method.getReturnType(), "Statement");
        });
    }

    private static Connection connection(SqlRun close) {
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
        case "close" -> {
            close.run();
            yield null;
        }
        default -> objectMethod(proxy, method.getName(), args, method.getReturnType(), "Connection");
        });
    }

    private static Object objectMethod(Object proxy, String method, Object[] args,
                                       Class<?> returnType, String label) {
        return switch (method) {
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        case "toString" -> label;
        default -> returnType == boolean.class ? false : returnType == int.class ? 0 : null;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @FunctionalInterface
    private interface SqlRun {

        void run() throws SQLException;
    }

    private static final class TestJdbcLoader extends AbstractJdbcLoader<PgDatabase> {

        private TestJdbcLoader() {
            super(connector(), new CoreSettings());
        }

        private static IJdbcConnector connector() {
            return new IJdbcConnector() {
                @Override
                public Connection getConnection() {
                    throw new AssertionError("Unexpected connection acquisition");
                }

                @Override
                public String getBatchDelimiter() {
                    return null;
                }

                @Override
                public String getUrl() {
                    return "jdbc:test";
                }

                @Override
                public String getDbName() {
                    return "test";
                }
            };
        }

        private void run(Statement statement) throws SQLException, InterruptedException {
            runner.run(statement, "select 1");
        }

        private void addAntlrCancellationFailure(Throwable failure) {
            FutureTask<String> future = new FutureTask<>(() -> "pending") {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    if (failure instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    if (failure instanceof Error error) {
                        throw error;
                    }
                    throw new AssertionError("Cancellation failure must be unchecked", failure);
                }
            };
            antlrTasks.add(new AntlrTask<>(future, ignored -> { }));
        }

        private void addBlockingAntlrCancellation(CountDownLatch entered, CountDownLatch release) {
            FutureTask<String> future = new FutureTask<>(() -> "pending") {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    entered.countDown();
                    awaitUninterruptibly(release);
                    return super.cancel(mayInterruptIfRunning);
                }
            };
            antlrTasks.add(new AntlrTask<>(future, ignored -> { }));
        }

        private void registerConnection(Connection connection) throws IOException, InterruptedException {
            registerActiveConnection(connection);
        }

        private void registerStatement(Statement statement) throws IOException, InterruptedException {
            registerActiveStatement(statement);
        }

        private void clearStatement(Statement statement) {
            clearActiveStatement(statement);
        }

        private boolean jdbcCancellationRequested() {
            return isJdbcCancellationRequested();
        }

        private void checkJdbcCancellation() throws InterruptedException {
            checkJdbcCancellationRequested();
        }

        private InterruptedException classifyJdbcCancellation(Throwable failure) {
            return interruptedByJdbcCancellation(failure);
        }

        private void retain(Connection connection, Statement statement) {
            this.connection = connection;
            this.statement = statement;
            schemaIds.put(1L, null);
        }

        private Throwable finishResources(Connection connection, Statement statement, Throwable failure) {
            return finishOwnedJdbcResources(connection, statement, failure);
        }

        private void releaseNow() {
            super.releaseLoadResources();
        }

        @Override
        protected PgDatabase loadInternal() {
            return createDatabase();
        }

        @Override
        protected PgDatabase createDatabase() {
            return new PgDatabase();
        }
    }

    private static final class SensitiveSQLException extends SQLException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new AssertionError("JDBC message must not be inspected");
        }

        @Override
        public String getLocalizedMessage() {
            throw new AssertionError("JDBC localized message must not be inspected");
        }

        @Override
        public String toString() {
            throw new AssertionError("JDBC failure must not be stringified");
        }
    }
}
