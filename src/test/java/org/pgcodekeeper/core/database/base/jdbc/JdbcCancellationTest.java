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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class JdbcCancellationTest {

    private static final String STATEMENT_FAILURE = "Failed to cancel active JDBC statement";
    private static final String CONNECTION_FAILURE = "Failed to close active JDBC connection";

    @Test
    void cancelActiveUsesRequiredOrderAndActsOnlyOnce() throws Exception {
        var order = new ArrayList<String>();
        var cancellation = new JdbcCancellation();
        cancellation.registerConnection(connection(() -> order.add("connection"), false));
        cancellation.registerStatement(statement(() -> order.add("statement"), false));
        cancellation.registerFuture(future(() -> order.add("future")));

        cancellation.cancelActive();
        cancellation.cancelActive();

        assertEquals(List.of("future", "statement", "connection"), order);
        assertTrue(cancellation.isCancellationRequested());
    }

    @Test
    void staleClearCannotEraseReplacementForAnyResource() throws Exception {
        var order = new ArrayList<String>();
        var cancellation = new JdbcCancellation();
        Future<?> oldFuture = future(() -> order.add("old future"));
        Future<?> newFuture = future(() -> order.add("new future"));
        Statement oldStatement = statement(() -> order.add("old statement"), false);
        Statement newStatement = statement(() -> order.add("new statement"), false);
        Connection oldConnection = connection(() -> order.add("old connection"), false);
        Connection newConnection = connection(() -> order.add("new connection"), false);

        cancellation.registerFuture(oldFuture);
        cancellation.registerFuture(newFuture);
        cancellation.registerStatement(oldStatement);
        cancellation.registerStatement(newStatement);
        cancellation.registerConnection(oldConnection);
        cancellation.registerConnection(newConnection);
        cancellation.clearFuture(oldFuture);
        cancellation.clearStatement(oldStatement);
        cancellation.clearConnection(oldConnection);
        cancellation.cancelActive();

        assertEquals(List.of("new future", "new statement", "new connection"), order);
    }

    @Test
    void registerAfterCancellationSelfClaimsAndSignalsInterruption() throws Exception {
        var order = new ArrayList<String>();
        var cancellation = new JdbcCancellation();
        cancellation.cancelActive();

        assertThrows(InterruptedException.class,
                () -> cancellation.registerFuture(future(() -> order.add("future"))));
        assertThrows(InterruptedException.class,
                () -> cancellation.registerStatement(statement(() -> order.add("statement"), false)));
        assertThrows(InterruptedException.class,
                () -> cancellation.registerConnection(connection(() -> order.add("connection"), false)));

        assertEquals(List.of("future", "statement", "connection"), order);
    }

    @Test
    void registerAfterCancellationReportsCheckedFailureWithoutDriverDetails() throws Exception {
        var cancellation = new JdbcCancellation();
        cancellation.cancelActive();
        Statement active = statement(() -> {
            throw new SQLException("jdbc:secret password=secret");
        }, true);

        IOException thrown = assertThrows(IOException.class,
                () -> cancellation.registerStatement(active));

        assertEquals(STATEMENT_FAILURE, thrown.getMessage());
        assertNull(thrown.getCause());
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void blockedCancellationRaceDoesNotCancelNewStatementTwice() throws Exception {
        var cancelEntered = new CountDownLatch(1);
        var releaseCancel = new CountDownLatch(1);
        var replacementCancelled = new CountDownLatch(1);
        var registrationFinished = new CountDownLatch(1);
        var statementCalls = new AtomicInteger();
        var cancellation = new JdbcCancellation();
        cancellation.registerFuture(future(() -> {
            cancelEntered.countDown();
            await(releaseCancel);
        }));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> cancellationTask = executor.submit(() -> {
            cancellation.cancelActive();
            return null;
        });
        try {
            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS));
            Future<?> registrationTask = executor.submit(() -> {
                try {
                    cancellation.registerStatement(statement(() -> {
                        statementCalls.incrementAndGet();
                        replacementCancelled.countDown();
                    }, false));
                    return null;
                } finally {
                    registrationFinished.countDown();
                }
            });

            assertTrue(replacementCancelled.await(5, TimeUnit.SECONDS));
            assertFalse(registrationFinished.await(100, TimeUnit.MILLISECONDS));
            releaseCancel.countDown();
            get(cancellationTask);
            ExecutionException registrationFailure = assertThrows(ExecutionException.class,
                    () -> get(registrationTask));
            assertTrue(registrationFailure.getCause() instanceof InterruptedException);
        } finally {
            releaseCancel.countDown();
            executor.shutdownNow();
        }

        cancellation.cancelActive();
        assertEquals(1, statementCalls.get());
    }

    @Test
    void cancelActiveSuppressesOnlyDistinctFailuresAndPreservesRuntimeIdentity() throws Exception {
        RuntimeException primary = new IllegalStateException("primary");
        RuntimeException later = new IllegalArgumentException("later");
        var cancellation = new JdbcCancellation();
        cancellation.registerFuture(future(() -> {
            throw primary;
        }));
        cancellation.registerStatement(statement(() -> {
            throw primary;
        }, false));
        cancellation.registerConnection(connection(() -> {
            throw later;
        }, false));

        RuntimeException thrown = assertThrows(RuntimeException.class, cancellation::cancelActive);

        assertSame(primary, thrown);
        assertArrayEquals(new Throwable[] { later }, thrown.getSuppressed());
    }

    @Test
    void sameCheckedFailureSourceIsReportedOnlyOnce() throws Exception {
        SQLException shared = new SQLException("shared controlled failure");
        var statementCalls = new AtomicInteger();
        var connectionCalls = new AtomicInteger();
        var cancellation = new JdbcCancellation();
        cancellation.registerStatement(statement(() -> {
            statementCalls.incrementAndGet();
            throw shared;
        }, false));
        cancellation.registerConnection(connection(() -> {
            connectionCalls.incrementAndGet();
            throw shared;
        }, false));

        IOException thrown = assertThrows(IOException.class, cancellation::cancelActive);

        assertEquals(STATEMENT_FAILURE, thrown.getMessage());
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(1, statementCalls.get());
        assertEquals(1, connectionCalls.get());
    }

    @Test
    void lateSelfClaimActionCanReenterCancellationWithoutWaitingForWinner() throws Exception {
        var firstActionEntered = new CountDownLatch(1);
        var releaseFirstAction = new CountDownLatch(1);
        var reentrantRequestReturned = new CountDownLatch(1);
        var registrationFinished = new CountDownLatch(1);
        var statementCalls = new AtomicInteger();
        var cancellation = new JdbcCancellation();
        cancellation.registerFuture(future(() -> {
            firstActionEntered.countDown();
            await(releaseFirstAction);
        }));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> cancellationTask = executor.submit(() -> {
            cancellation.cancelActive();
            return null;
        });

        try {
            assertTrue(firstActionEntered.await(5, TimeUnit.SECONDS));
            Future<?> registrationTask = executor.submit(() -> {
                try {
                    cancellation.registerStatement(statement(() -> {
                        statementCalls.incrementAndGet();
                        try {
                            cancellation.cancelActive();
                        } catch (IOException e) {
                            throw new AssertionError("Unexpected reentrant cancellation failure", e);
                        }
                        reentrantRequestReturned.countDown();
                    }, false));
                    return null;
                } finally {
                    registrationFinished.countDown();
                }
            });

            assertTrue(reentrantRequestReturned.await(5, TimeUnit.SECONDS));
            assertFalse(registrationFinished.await(100, TimeUnit.MILLISECONDS));
            releaseFirstAction.countDown();
            get(cancellationTask);
            ExecutionException registrationFailure = assertThrows(ExecutionException.class,
                    () -> get(registrationTask));
            assertTrue(registrationFailure.getCause() instanceof InterruptedException);
        } finally {
            releaseFirstAction.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, statementCalls.get());
    }

    @Test
    void clearCannotStealSlotAfterCancellationPublicationAndAwaitsDrain() throws Exception {
        var firstActionEntered = new CountDownLatch(1);
        var releaseFirstAction = new CountDownLatch(1);
        var clearFinished = new CountDownLatch(1);
        var statementCalls = new AtomicInteger();
        var cancellation = new JdbcCancellation();
        Statement activeStatement = statement(statementCalls::incrementAndGet, false);
        cancellation.registerFuture(future(() -> {
            firstActionEntered.countDown();
            await(releaseFirstAction);
        }));
        cancellation.registerStatement(activeStatement);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> cancellationTask = executor.submit(() -> {
            cancellation.cancelActive();
            return null;
        });

        try {
            assertTrue(firstActionEntered.await(5, TimeUnit.SECONDS));
            Future<?> clearTask = executor.submit(() -> {
                try {
                    cancellation.clearStatement(activeStatement);
                } finally {
                    clearFinished.countDown();
                }
            });

            assertFalse(clearFinished.await(100, TimeUnit.MILLISECONDS));
            releaseFirstAction.countDown();
            get(cancellationTask);
            get(clearTask);
        } finally {
            releaseFirstAction.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, statementCalls.get());
    }

    @Test
    void failedFirstCancellationRequestMakesSecondRequestNoOp() throws Exception {
        var statementCalls = new AtomicInteger();
        var connectionCalls = new AtomicInteger();
        var cancellation = new JdbcCancellation();
        cancellation.registerStatement(statement(() -> {
            statementCalls.incrementAndGet();
            throw new SQLException("controlled failure");
        }, false));
        cancellation.registerConnection(connection(connectionCalls::incrementAndGet, false));

        assertThrows(IOException.class, cancellation::cancelActive);
        cancellation.cancelActive();

        assertEquals(1, statementCalls.get());
        assertEquals(1, connectionCalls.get());
    }

    @Test
    void concurrentRequestsAwaitCompleteDrainUninterruptiblyWithoutRepeatingFailure() throws Exception {
        var futureEntered = new CountDownLatch(1);
        var releaseFuture = new CountDownLatch(1);
        var firstFinished = new CountDownLatch(1);
        var preInterruptedStarted = new CountDownLatch(1);
        var preInterruptedFinished = new CountDownLatch(1);
        var duringInterruptedStarted = new CountDownLatch(1);
        var duringInterruptedFinished = new CountDownLatch(1);
        var futureCalls = new AtomicInteger();
        var statementCalls = new AtomicInteger();
        var connectionCalls = new AtomicInteger();
        var firstFailure = new AtomicReference<Throwable>();
        var preInterruptedFailure = new AtomicReference<Throwable>();
        var duringInterruptedFailure = new AtomicReference<Throwable>();
        var preInterruptedFlag = new AtomicBoolean();
        var duringInterruptedFlag = new AtomicBoolean();
        var cancellation = new JdbcCancellation();
        cancellation.registerFuture(future(() -> {
            futureCalls.incrementAndGet();
            futureEntered.countDown();
            await(releaseFuture);
        }));
        cancellation.registerStatement(statement(() -> {
            statementCalls.incrementAndGet();
            throw new SQLException("controlled first-caller failure");
        }, false));
        cancellation.registerConnection(connection(connectionCalls::incrementAndGet, false));

        Thread first = cancellationThread(cancellation, firstFailure, firstFinished, false, null, null);
        Thread preInterrupted = cancellationThread(cancellation, preInterruptedFailure,
                preInterruptedFinished, true, preInterruptedStarted, preInterruptedFlag);
        Thread duringInterrupted = cancellationThread(cancellation, duringInterruptedFailure,
                duringInterruptedFinished, false, duringInterruptedStarted, duringInterruptedFlag);

        first.start();
        try {
            assertTrue(futureEntered.await(5, TimeUnit.SECONDS));
            preInterrupted.start();
            duringInterrupted.start();
            assertTrue(preInterruptedStarted.await(5, TimeUnit.SECONDS));
            assertTrue(duringInterruptedStarted.await(5, TimeUnit.SECONDS));
            assertFalse(preInterruptedFinished.await(100, TimeUnit.MILLISECONDS));
            assertFalse(duringInterruptedFinished.await(100, TimeUnit.MILLISECONDS));
            duringInterrupted.interrupt();
            assertFalse(duringInterruptedFinished.await(100, TimeUnit.MILLISECONDS));
        } finally {
            releaseFuture.countDown();
        }

        assertTrue(firstFinished.await(5, TimeUnit.SECONDS));
        assertTrue(preInterruptedFinished.await(5, TimeUnit.SECONDS));
        assertTrue(duringInterruptedFinished.await(5, TimeUnit.SECONDS));
        assertTrue(firstFailure.get() instanceof IOException);
        assertNull(preInterruptedFailure.get());
        assertNull(duringInterruptedFailure.get());
        assertTrue(preInterruptedFlag.get());
        assertTrue(duringInterruptedFlag.get());
        assertEquals(1, futureCalls.get());
        assertEquals(1, statementCalls.get());
        assertEquals(1, connectionCalls.get());
    }

    @Test
    void sameThreadReentrantCancellationReturnsWithoutDeadlock() throws Exception {
        var cancellation = new JdbcCancellation();
        var statementCalls = new AtomicInteger();
        var ownerFailure = new AtomicReference<Throwable>();
        var ownerFinished = new CountDownLatch(1);
        cancellation.registerStatement(statement(() -> {
            statementCalls.incrementAndGet();
            try {
                cancellation.cancelActive();
            } catch (IOException e) {
                throw new AssertionError("Unexpected reentrant cancellation failure", e);
            }
        }, false));

        Thread owner = cancellationThread(cancellation, ownerFailure, ownerFinished, false, null, null);
        owner.start();

        assertTrue(ownerFinished.await(5, TimeUnit.SECONDS));
        assertNull(ownerFailure.get());
        assertEquals(1, statementCalls.get());
    }

    @Test
    void replacementSelfClaimsWhileCancellationOfClaimedOldStatementIsBlocked() throws Exception {
        var oldEntered = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        var replacementCancelled = new CountDownLatch(1);
        var registrationFinished = new CountDownLatch(1);
        var oldCalls = new AtomicInteger();
        var newCalls = new AtomicInteger();
        var cancellation = new JdbcCancellation();
        cancellation.registerStatement(statement(() -> {
            oldCalls.incrementAndGet();
            oldEntered.countDown();
            await(releaseOld);
        }, false));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> cancellationTask = executor.submit(() -> {
            cancellation.cancelActive();
            return null;
        });
        try {
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS));
            Future<?> registrationTask = executor.submit(() -> {
                try {
                    cancellation.registerStatement(statement(() -> {
                        newCalls.incrementAndGet();
                        replacementCancelled.countDown();
                    }, false));
                    return null;
                } finally {
                    registrationFinished.countDown();
                }
            });

            assertTrue(replacementCancelled.await(5, TimeUnit.SECONDS));
            assertFalse(registrationFinished.await(100, TimeUnit.MILLISECONDS));
            releaseOld.countDown();
            get(cancellationTask);
            ExecutionException registrationFailure = assertThrows(ExecutionException.class,
                    () -> get(registrationTask));
            assertTrue(registrationFailure.getCause() instanceof InterruptedException);
        } finally {
            releaseOld.countDown();
            executor.shutdownNow();
        }

        cancellation.cancelActive();
        assertEquals(1, oldCalls.get());
        assertEquals(1, newCalls.get());
    }

    @Test
    void cancelActivePreservesErrorIdentityAndAttemptsLaterResources() throws Exception {
        Error primary = new AssertionError("primary");
        RuntimeException connectionFailure = new IllegalStateException("connection");
        var statementCalls = new AtomicInteger();
        var cancellation = new JdbcCancellation();
        cancellation.registerFuture(future(() -> {
            throw primary;
        }));
        cancellation.registerStatement(statement(() -> {
            statementCalls.incrementAndGet();
            throw new SQLException("secret statement details");
        }, false));
        cancellation.registerConnection(connection(() -> {
            throw connectionFailure;
        }, false));

        Error thrown = assertThrows(Error.class, cancellation::cancelActive);

        assertSame(primary, thrown);
        assertEquals(1, statementCalls.get());
        assertEquals(2, thrown.getSuppressed().length);
        assertEquals(STATEMENT_FAILURE, thrown.getSuppressed()[0].getMessage());
        assertNull(thrown.getSuppressed()[0].getCause());
        assertSame(connectionFailure, thrown.getSuppressed()[1]);
    }

    @Test
    void checkedJdbcFailuresAreNeutralAndNeverInspectResources() throws Exception {
        var cancellation = new JdbcCancellation();
        cancellation.registerStatement(statement(() -> {
            throw new SQLException("statement password=secret");
        }, true));
        cancellation.registerConnection(connection(() -> {
            throw new SQLException("jdbc:secret user=secret");
        }, true));
        cancellation.registerFuture(future(() -> { }));

        IOException thrown = assertThrows(IOException.class, cancellation::cancelActive);

        assertEquals(STATEMENT_FAILURE, thrown.getMessage());
        assertNull(thrown.getCause());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals(CONNECTION_FAILURE, thrown.getSuppressed()[0].getMessage());
        assertNull(thrown.getSuppressed()[0].getCause());
    }

    @Test
    void clearReferencesForgetsWithoutActingOnResources() throws Exception {
        var calls = new AtomicInteger();
        var cancellation = new JdbcCancellation();
        cancellation.registerFuture(future(calls::incrementAndGet));
        cancellation.registerStatement(statement(calls::incrementAndGet, false));
        cancellation.registerConnection(connection(calls::incrementAndGet, false));

        cancellation.clearReferences();
        cancellation.cancelActive();

        assertEquals(0, calls.get());
    }

    @Test
    void duplicateRegistrationBeforeCancellationActsOnce() throws Exception {
        var futureCalls = new AtomicInteger();
        var statementCalls = new AtomicInteger();
        var connectionCalls = new AtomicInteger();
        var cancellation = new JdbcCancellation();
        Future<?> activeFuture = future(futureCalls::incrementAndGet);
        Statement activeStatement = statement(statementCalls::incrementAndGet, false);
        Connection activeConnection = connection(connectionCalls::incrementAndGet, false);

        cancellation.registerFuture(activeFuture);
        cancellation.registerFuture(activeFuture);
        cancellation.registerStatement(activeStatement);
        cancellation.registerStatement(activeStatement);
        cancellation.registerConnection(activeConnection);
        cancellation.registerConnection(activeConnection);
        cancellation.cancelActive();

        assertEquals(1, futureCalls.get());
        assertEquals(1, statementCalls.get());
        assertEquals(1, connectionCalls.get());
    }

    @Test
    void cancelActiveActsOnEveryRegisteredResourceInRegistrationOrder() throws Exception {
        var order = new ArrayList<String>();
        var cancellation = new JdbcCancellation();
        cancellation.registerFuture(future(() -> order.add("future 1")));
        cancellation.registerFuture(future(() -> order.add("future 2")));
        cancellation.registerStatement(statement(() -> order.add("statement 1"), false));
        cancellation.registerStatement(statement(() -> order.add("statement 2"), false));
        cancellation.registerConnection(connection(() -> order.add("connection 1"), false));
        cancellation.registerConnection(connection(() -> order.add("connection 2"), false));

        cancellation.cancelActive();
        cancellation.cancelActive();

        assertEquals(List.of("future 1", "future 2", "statement 1", "statement 2",
                "connection 1", "connection 2"), order);
    }

    @Test
    void clearRemovesOnlyTheGivenIdentityAmongPeers() throws Exception {
        var order = new ArrayList<String>();
        var cancellation = new JdbcCancellation();
        Statement laneOne = statement(() -> order.add("lane 1"), false);
        Statement laneTwo = statement(() -> order.add("lane 2"), false);
        Statement laneThree = statement(() -> order.add("lane 3"), false);
        cancellation.registerStatement(laneOne);
        cancellation.registerStatement(laneTwo);
        cancellation.registerStatement(laneThree);

        cancellation.clearStatement(laneTwo);
        cancellation.cancelActive();

        assertEquals(List.of("lane 1", "lane 3"), order);
    }

    @Test
    void duplicateIdentityRegistrationActsAndClearsExactlyOnce() throws Exception {
        var statementCalls = new AtomicInteger();
        var cancellation = new JdbcCancellation();
        Statement shared = statement(statementCalls::incrementAndGet, false);

        cancellation.registerStatement(shared);
        cancellation.registerStatement(shared);
        cancellation.clearStatement(shared);
        cancellation.cancelActive();
        assertEquals(0, statementCalls.get());

        var secondRegistry = new JdbcCancellation();
        secondRegistry.registerStatement(shared);
        secondRegistry.registerStatement(shared);
        secondRegistry.cancelActive();
        assertEquals(1, statementCalls.get());
    }

    @Test
    void completeSuccessRemovesOnlyItsOwnFutureAndStatement() throws Exception {
        var order = new ArrayList<String>();
        var cancellation = new JdbcCancellation();
        Future<?> laneOneFuture = future(() -> order.add("future 1"));
        Future<?> laneTwoFuture = future(() -> order.add("future 2"));
        Statement laneOneStatement = statement(() -> order.add("statement 1"), false);
        Statement laneTwoStatement = statement(() -> order.add("statement 2"), false);
        cancellation.registerFuture(laneOneFuture);
        cancellation.registerFuture(laneTwoFuture);
        cancellation.registerStatement(laneOneStatement);
        cancellation.registerStatement(laneTwoStatement);

        assertTrue(cancellation.completeSuccess(laneOneFuture, laneOneStatement, false, false));
        cancellation.cancelActive();

        assertEquals(List.of("future 2", "statement 2"), order);
    }

    @Test
    void concurrentLaneRegistrationsAllDrainOnCancellation() throws Exception {
        int lanes = 4;
        var cancellation = new JdbcCancellation();
        var cancelled = new AtomicInteger();
        var registered = new CountDownLatch(lanes);
        ExecutorService executor = Executors.newFixedThreadPool(lanes);
        try {
            for (int i = 0; i < lanes; i++) {
                executor.submit(() -> {
                    cancellation.registerStatement(
                            statement(cancelled::incrementAndGet, false));
                    registered.countDown();
                    return null;
                });
            }
            await(registered);

            cancellation.cancelActive();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(lanes, cancelled.get());
    }

    @Test
    void nullResourcesAreRejectedWithoutChangingState() {
        var cancellation = new JdbcCancellation();

        assertThrows(NullPointerException.class, () -> cancellation.registerFuture(null));
        assertThrows(NullPointerException.class, () -> cancellation.registerStatement(null));
        assertThrows(NullPointerException.class, () -> cancellation.registerConnection(null));
        assertThrows(NullPointerException.class, () -> cancellation.clearFuture(null));
        assertThrows(NullPointerException.class, () -> cancellation.clearStatement(null));
        assertThrows(NullPointerException.class, () -> cancellation.clearConnection(null));
        assertFalse(cancellation.isCancellationRequested());
    }

    private static Future<?> future(Action action) {
        return new Future<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                assertTrue(mayInterruptIfRunning);
                action.run();
                return true;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public Object get() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object get(long timeout, TimeUnit unit) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Statement statement(JdbcAction action, boolean rejectToString) {
        return proxy(Statement.class, "cancel", action, rejectToString);
    }

    private static Connection connection(JdbcAction action, boolean rejectToString) {
        return proxy(Connection.class, "close", action, rejectToString);
    }

    private static <T> T proxy(Class<T> type, String actionMethod, JdbcAction action,
            boolean rejectToString) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> {
                    if (method.getName().equals(actionMethod)) {
                        action.run();
                        return null;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> {
                            if (rejectToString) {
                                throw new AssertionError("resource toString must not be called");
                            }
                            yield type.getSimpleName();
                        }
                        default -> throw new AssertionError(method.getName());
                        };
                    }
                    throw new AssertionError("Unexpected JDBC call: " + method.getName());
                }));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test helper interrupted", e);
        }
    }

    private static void get(Future<?> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        future.get(5, TimeUnit.SECONDS);
    }

    private static Thread cancellationThread(JdbcCancellation cancellation,
            AtomicReference<Throwable> failure, CountDownLatch finished, boolean preInterrupt,
            CountDownLatch started, AtomicBoolean interruptedFlag) {
        return new Thread(() -> {
            if (preInterrupt) {
                Thread.currentThread().interrupt();
            }
            if (started != null) {
                started.countDown();
            }
            try {
                cancellation.cancelActive();
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                if (interruptedFlag != null) {
                    interruptedFlag.set(Thread.currentThread().isInterrupted());
                }
                finished.countDown();
            }
        });
    }

    @FunctionalInterface
    private interface Action {

        void run();
    }

    @FunctionalInterface
    private interface JdbcAction {

        void run() throws SQLException;
    }
}
