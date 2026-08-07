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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.callable.ResultSetCallable;
import org.pgcodekeeper.core.callable.StatementCallable;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;

class JdbcRunnerCancellationTest {

    private static final String STATEMENT_FAILURE = "Failed to cancel active JDBC statement";

    @Test
    void statementCallableExposesExactStatement() {
        Statement statement = statement(() -> null, () -> { });

        assertSame(statement, new ResultSetCallable(statement, "select 1").getStatement());
    }

    @Test
    void publicStatementAccessorRemainsOverridable() {
        Statement owned = statement(() -> null, () -> { });
        Statement exposed = statement(() -> null, () -> { });

        assertSame(exposed, new OverridingStatementCallable(owned, exposed).getStatement());
    }

    @Test
    void ownedConnectionIsRegisteredBeforeCreateStatement() throws Exception {
        var cancellation = new JdbcCancellation();
        var closed = new AtomicBoolean();
        Statement statement = statement(() -> false, () -> { });
        Connection connection = connection(() -> {
            cancel(cancellation);
            assertTrue(closed.get());
            return statement;
        }, () -> closed.set(true));
        var runner = new JdbcRunner(new NullMonitor(), cancellation,
                new FixedFutureExecutor(future(() -> "unused"), () -> { }));

        assertThrows(InterruptedException.class,
                () -> runner.run(connector(connection), "select 1"));

        assertTrue(closed.get());
    }

    @Test
    void failedCancellerConnectionCloseIsRetriedByOwner() throws Exception {
        var cancellation = new JdbcCancellation();
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var connectionCloses = new AtomicInteger();
        Statement statement = statement(() -> {
            executeEntered.countDown();
            awaitUninterruptibly(releaseExecute);
            return false;
        }, () -> { });
        Connection connection = connection(() -> statement, () -> {
            if (connectionCloses.incrementAndGet() == 1) {
                releaseExecute.countDown();
                throw new SQLException("controlled canceller close failure");
            }
        });
        ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        var runner = new JdbcRunner(new NullMonitor(), cancellation, queryExecutor);
        Future<?> cancelTask = canceller.submit(() -> {
            await(executeEntered);
            cancellation.cancelActive();
            return null;
        });

        try {
            assertThrows(InterruptedException.class,
                    () -> runner.run(connector(connection), "select 1"));
            ExecutionException cancelFailure = assertThrows(ExecutionException.class,
                    () -> get(cancelTask));
            assertTrue(cancelFailure.getCause() instanceof IOException);
        } finally {
            releaseExecute.countDown();
            shutdown(canceller);
            shutdown(queryExecutor);
        }

        assertEquals(2, connectionCloses.get());
    }

    @Test
    void ordinaryFailurePreservesIdentityAndCloseSuppressionOrder() throws Exception {
        RuntimeException primary = new RejectedExecutionException("primary");
        SQLException statementClose = new SQLException("statement close");
        SQLException connectionClose = new SQLException("connection close");
        Statement statement = statement(() -> false, () -> { }, () -> {
            throw statementClose;
        });
        Connection connection = connection(() -> statement, () -> {
            throw connectionClose;
        });
        var runner = new JdbcRunner(new NullMonitor(), new JdbcCancellation(),
                new RejectingExecutor(() -> { }, (RejectedExecutionException) primary));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> runner.run(connector(connection), "select 1"));

        assertSame(primary, thrown);
        assertEquals(List.of(statementClose, connectionClose), List.of(thrown.getSuppressed()));
    }

    @Test
    void cancellationReturnsNeutralInterruptionWithSingleRawAggregate() throws Exception {
        var cancellation = new JdbcCancellation();
        var monitor = new TestMonitor();
        RuntimeException primary = new IllegalStateException("primary");
        RuntimeException drainFailure = new IllegalArgumentException("drain");
        SQLException statementClose = new SQLException("statement close");
        SQLException connectionClose = new SQLException("connection close");
        cancellation.registerFuture(future(() -> "stale", () -> {
            throw drainFailure;
        }));
        Statement statement = statement(() -> false, () -> { }, () -> {
            throw statementClose;
        });
        Connection connection = connection(() -> statement, () -> {
            throw connectionClose;
        });
        IJdbcConnector connector = connector(() -> connection, () -> {
            monitor.setCancelled(true);
            throw primary;
        });
        var runner = new JdbcRunner(monitor, cancellation,
                new FixedFutureExecutor(future(() -> "unused"), () -> { }));

        InterruptedException thrown = assertThrows(InterruptedException.class,
                () -> runner.runBatches(connector, List.of(), null));

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(primary, thrown.getSuppressed()[0]);
        assertEquals(List.of(drainFailure, statementClose, connectionClose),
                List.of(primary.getSuppressed()));
    }

    @Test
    void cancellationObservedDuringCloseRunsPostCloseDrainLast() throws Exception {
        var cancellation = new JdbcCancellation();
        var monitor = new TestMonitor();
        RuntimeException primary = new RejectedExecutionException("primary");
        SQLException statementClose = new SQLException("statement close");
        SQLException connectionClose = new SQLException("connection close");
        RuntimeException postDrain = new IllegalStateException("post-close drain");
        cancellation.registerFuture(future(() -> "stale", () -> {
            throw postDrain;
        }));
        Statement statement = statement(() -> false, () -> { }, () -> {
            throw statementClose;
        });
        Connection connection = connection(() -> statement, () -> {
            monitor.setCancelled(true);
            throw connectionClose;
        });
        var runner = new JdbcRunner(monitor, cancellation,
                new RejectingExecutor(() -> { }, (RejectedExecutionException) primary));

        InterruptedException thrown = assertThrows(InterruptedException.class,
                () -> runner.run(connector(connection), "select 1"));

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(primary, thrown.getSuppressed()[0]);
        assertEquals(List.of(statementClose, connectionClose, postDrain),
                List.of(primary.getSuppressed()));
    }

    @Test
    void monitorFailureAfterOwnedActionStillClosesBothResources() throws Exception {
        RuntimeException monitorFailure = new IllegalStateException("monitor failure");
        var monitor = new ActionOnCheckMonitor(8, () -> {
            throw monitorFailure;
        });
        var cancellation = new JdbcCancellation();
        var statementCancels = new AtomicInteger();
        var statementCloses = new AtomicInteger();
        var connectionCloses = new AtomicInteger();
        Statement statement = statement(() -> false, statementCancels::incrementAndGet,
                statementCloses::incrementAndGet);
        Connection connection = connection(() -> statement, connectionCloses::incrementAndGet);
        var runner = new JdbcRunner(monitor, cancellation,
                new FixedFutureExecutor(future(() -> "success"), () -> { }));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> runner.run(connector(connection), "select 1"));

        assertSame(monitorFailure, thrown);
        assertEquals(1, statementCloses.get());
        assertEquals(1, connectionCloses.get());
        cancellation.cancelActive();
        assertEquals(0, statementCancels.get());
        assertEquals(1, connectionCloses.get());
    }

    @Test
    void activeWorkerIsDrainedAfterMonitorRuntimeFailure() throws Exception {
        assertActiveWorkerIsDrainedAfterMonitorFailure(
                new IllegalStateException("monitor runtime failure"),
                new IllegalArgumentException("statement cancellation failure"));
    }

    @Test
    void activeWorkerIsDrainedAfterMonitorError() throws Exception {
        assertActiveWorkerIsDrainedAfterMonitorFailure(
                new AssertionError("monitor error"),
                new LinkageError("statement cancellation error"));
    }

    @Test
    void activeWorkerIsDrainedAfterMonitorCancellationException() throws Exception {
        assertActiveWorkerIsDrainedAfterMonitorFailure(
                new CancellationException("monitor cancellation failure"),
                new IllegalStateException("statement cancellation failure"));
    }

    @Test
    void duplicateCleanupThrowableIsNeverSelfSuppressed() throws Exception {
        RejectedExecutionException shared = new RejectedExecutionException("shared");
        Statement statement = statement(() -> false, () -> { }, () -> {
            throw shared;
        });
        Connection connection = connection(() -> statement, () -> {
            throw shared;
        });
        var runner = new JdbcRunner(new NullMonitor(), new JdbcCancellation(),
                new RejectingExecutor(() -> { }, shared));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> runner.run(connector(connection), "select 1"));

        assertSame(shared, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void runUsesRegisteredConnectionAsCancellationFallback() throws Exception {
        assertOwnedConnectionFallback(false);
    }

    @Test
    void runBatchesUsesRegisteredConnectionAsCancellationFallback() throws Exception {
        assertOwnedConnectionFallback(true);
    }

    @Test
    void ownerWaitsForDrainBeforeEitherPhysicalClose() throws Exception {
        var cancellation = new JdbcCancellation();
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var cancellerCloseEntered = new CountDownLatch(1);
        var releaseCancellerClose = new CountDownLatch(1);
        var ownerFinished = new CountDownLatch(1);
        var ownerFailure = new AtomicReference<Throwable>();
        var cancellerThread = new AtomicReference<Thread>();
        var statementCloses = new AtomicInteger();
        Statement statement = statement(() -> {
            executeEntered.countDown();
            awaitUninterruptibly(releaseExecute);
            return false;
        }, () -> { }, statementCloses::incrementAndGet);
        Connection connection = connection(() -> statement, () -> {
            if (Thread.currentThread() == cancellerThread.get()) {
                cancellerCloseEntered.countDown();
                awaitUninterruptibly(releaseCancellerClose);
                releaseExecute.countDown();
            }
        });
        ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        var runner = new JdbcRunner(new NullMonitor(), cancellation, queryExecutor);
        Thread owner = new Thread(() -> {
            try {
                runner.run(connector(connection), "select 1");
            } catch (Throwable e) {
                ownerFailure.set(e);
            } finally {
                ownerFinished.countDown();
            }
        });
        owner.start();
        Future<?> cancelTask = canceller.submit(() -> {
            await(executeEntered);
            cancellerThread.set(Thread.currentThread());
            cancellation.cancelActive();
            return null;
        });

        try {
            assertTrue(cancellerCloseEntered.await(5, TimeUnit.SECONDS));
            assertEquals(0, statementCloses.get());
            assertFalse(ownerFinished.await(100, TimeUnit.MILLISECONDS));
            releaseCancellerClose.countDown();
            assertTrue(ownerFinished.await(5, TimeUnit.SECONDS));
            get(cancelTask);
        } finally {
            releaseCancellerClose.countDown();
            releaseExecute.countDown();
            owner.interrupt();
            shutdown(canceller);
            shutdown(queryExecutor);
        }

        assertTrue(ownerFailure.get() instanceof InterruptedException);
    }

    @Test
    void successfulOwnedRunClearsCancellationSlotsBeforePhysicalClose() throws Exception {
        var cancellation = new JdbcCancellation();
        var statementCancels = new AtomicInteger();
        var statementCloses = new AtomicInteger();
        var connectionCloses = new AtomicInteger();
        Statement statement = statement(() -> false, statementCancels::incrementAndGet,
                statementCloses::incrementAndGet);
        Connection connection = connection(() -> statement, connectionCloses::incrementAndGet);
        var runner = new JdbcRunner(new NullMonitor(), cancellation,
                new FixedFutureExecutor(future(() -> "success"), () -> { }));

        runner.run(connector(connection), "select 1");
        cancellation.cancelActive();

        assertEquals(0, statementCancels.get());
        assertEquals(1, statementCloses.get());
        assertEquals(1, connectionCloses.get());
    }

    private static void assertOwnedConnectionFallback(boolean batches) throws Exception {
        var cancellation = new JdbcCancellation();
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var connectionCloses = new AtomicInteger();
        var closesSeenByCanceller = new AtomicInteger();
        Statement statement = statement(() -> {
            executeEntered.countDown();
            awaitUninterruptibly(releaseExecute);
            return false;
        }, () -> { });
        Connection connection = connection(() -> statement, () -> {
            connectionCloses.incrementAndGet();
            releaseExecute.countDown();
        });
        ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        var runner = new JdbcRunner(new NullMonitor(), cancellation, queryExecutor);
        Future<?> cancelTask = canceller.submit(() -> {
            await(executeEntered);
            cancellation.cancelActive();
            closesSeenByCanceller.set(connectionCloses.get());
            return null;
        });

        try {
            if (batches) {
                ObjectLocation query = new ObjectLocation.Builder().setSql("select 1").build();
                assertThrows(InterruptedException.class,
                        () -> runner.runBatches(connector(connection), List.of(query), null));
            } else {
                assertThrows(InterruptedException.class,
                        () -> runner.run(connector(connection), "select 1"));
            }
            get(cancelTask);
        } finally {
            releaseExecute.countDown();
            shutdown(canceller);
            shutdown(queryExecutor);
        }

        assertEquals(1, closesSeenByCanceller.get());
    }

    private static void assertActiveWorkerIsDrainedAfterMonitorFailure(
            Throwable monitorFailure, Throwable cancellationFailure) throws Exception {
        var cancellation = new JdbcCancellation();
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var executeExited = new CountDownLatch(1);
        var sequence = new AtomicInteger();
        var cancelOrder = new AtomicInteger();
        var statementCloseOrder = new AtomicInteger();
        var connectionFirstCloseOrder = new AtomicInteger();
        var connectionSecondCloseOrder = new AtomicInteger();
        var connectionCloses = new AtomicInteger();
        Statement statement = statement(() -> {
            executeEntered.countDown();
            try {
                awaitUninterruptibly(releaseExecute);
                return false;
            } finally {
                executeExited.countDown();
            }
        }, () -> {
            cancelOrder.set(sequence.incrementAndGet());
            releaseExecute.countDown();
            await(executeExited);
            throwUnchecked(cancellationFailure);
        }, () -> {
            statementCloseOrder.set(sequence.incrementAndGet());
            releaseExecute.countDown();
            await(executeExited);
        });
        Connection connection = connection(() -> statement, () -> {
            int order = sequence.incrementAndGet();
            if (connectionCloses.incrementAndGet() == 1) {
                connectionFirstCloseOrder.set(order);
            } else {
                connectionSecondCloseOrder.set(order);
            }
        });
        IMonitor monitor = new FailureAfterLatchMonitor(executeEntered, monitorFailure);
        ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
        var runner = new JdbcRunner(monitor, cancellation, queryExecutor);

        Throwable thrown;
        try {
            thrown = assertThrows(monitorFailure.getClass(),
                    () -> runner.run(connector(connection), "select 1"));
        } finally {
            releaseExecute.countDown();
            shutdown(queryExecutor);
        }

        assertSame(monitorFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cancellationFailure, thrown.getSuppressed()[0]);
        assertTrue(cancelOrder.get() > 0);
        assertTrue(connectionFirstCloseOrder.get() > cancelOrder.get());
        assertTrue(statementCloseOrder.get() > connectionFirstCloseOrder.get());
        assertTrue(connectionSecondCloseOrder.get() > statementCloseOrder.get());
        assertEquals(2, connectionCloses.get());
    }

    @Test
    void statementIsRegisteredBeforeSubmitAndRejectionRemainsPrimary() throws Exception {
        var cancellation = new JdbcCancellation();
        var cancelCalls = new AtomicInteger();
        Statement statement = statement(() -> null, cancelCalls::incrementAndGet);
        RejectedExecutionException rejection = new RejectedExecutionException("controlled rejection");
        ExecutorService executor = new RejectingExecutor(() -> cancel(cancellation), rejection);
        var runner = new JdbcRunner(new NullMonitor(), cancellation, executor);

        RejectedExecutionException thrown = assertThrows(RejectedExecutionException.class,
                () -> runner.runScript(statement, "select 1"));

        assertSame(rejection, thrown);
        assertEquals(1, cancelCalls.get());
    }

    @Test
    void rejectedSubmissionClearsExactStatementWithoutCancellingIt() throws Exception {
        var cancellation = new JdbcCancellation();
        var cancelCalls = new AtomicInteger();
        Statement statement = statement(() -> null, cancelCalls::incrementAndGet);
        RejectedExecutionException rejection = new RejectedExecutionException("controlled rejection");
        ExecutorService executor = new RejectingExecutor(() -> { }, rejection);
        var runner = new JdbcRunner(new NullMonitor(), cancellation, executor);

        RejectedExecutionException thrown = assertThrows(RejectedExecutionException.class,
                () -> runner.runScript(statement, "select 1"));
        cancellation.cancelActive();

        assertSame(rejection, thrown);
        assertEquals(0, cancelCalls.get());
    }

    @Test
    void cancellationBetweenSubmitAndFutureRegistrationCancelsBothResources() throws Exception {
        var cancellation = new JdbcCancellation();
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        ControlledFuture<ResultSet> queryFuture = future(() -> null);
        ExecutorService executor = new FixedFutureExecutor(queryFuture, () -> cancel(cancellation));
        var runner = new JdbcRunner(new NullMonitor(), cancellation, executor);

        InterruptedException thrown = assertThrows(InterruptedException.class,
                () -> runner.runScript(statement, "select 1"));

        assertEquals("JDBC operation was cancelled", thrown.getMessage());
        assertEquals(1, statementCancels.get());
        assertEquals(1, queryFuture.cancelCalls());
    }

    @Test
    void completeDrainBeforeFuturePublicationCannotBeFollowedByExecution() throws Exception {
        var cancellation = new JdbcCancellation();
        var submitEntered = new CountDownLatch(1);
        var allowSubmitReturn = new CountDownLatch(1);
        var cancellationStarted = new CountDownLatch(1);
        var cancellationFinished = new CountDownLatch(1);
        var taskFinished = new CountDownLatch(1);
        var ownerFinished = new CountDownLatch(1);
        var ownerFailure = new AtomicReference<Throwable>();
        var executeCalls = new AtomicInteger();
        Statement statement = statement(() -> {
            executeCalls.incrementAndGet();
            return false;
        }, () -> { });
        ExecutorService queryExecutor = new PublicationRaceExecutor(
                submitEntered, allowSubmitReturn, cancellationFinished, taskFinished);
        var runner = new JdbcRunner(new NullMonitor(), cancellation, queryExecutor);
        Thread owner = new Thread(() -> {
            try {
                runner.run(statement, "select 1");
            } catch (Throwable e) {
                ownerFailure.set(e);
            } finally {
                ownerFinished.countDown();
            }
        });
        Thread canceller = new Thread(() -> {
            cancellationStarted.countDown();
            try {
                cancellation.cancelActive();
            } catch (Throwable e) {
                throw new AssertionError("Unexpected cancellation failure", e);
            } finally {
                cancellationFinished.countDown();
            }
        });

        owner.start();
        try {
            assertTrue(submitEntered.await(5, TimeUnit.SECONDS));
            canceller.start();
            assertTrue(cancellationStarted.await(5, TimeUnit.SECONDS));

            boolean drainedBeforePublication = cancellationFinished.await(100, TimeUnit.MILLISECONDS);
            if (drainedBeforePublication) {
                assertTrue(taskFinished.await(5, TimeUnit.SECONDS));
            }
            allowSubmitReturn.countDown();

            assertTrue(cancellationFinished.await(5, TimeUnit.SECONDS));
            assertTrue(taskFinished.await(5, TimeUnit.SECONDS));
            assertTrue(ownerFinished.await(5, TimeUnit.SECONDS));
        } finally {
            allowSubmitReturn.countDown();
            owner.interrupt();
            canceller.interrupt();
            shutdown(queryExecutor);
        }

        assertTrue(ownerFailure.get() instanceof InterruptedException);
        assertEquals(0, executeCalls.get());
    }

    @Test
    void preCancelledRegistryFailsBeforeStatementRegistrationAndSubmit() throws Exception {
        var cancellation = new JdbcCancellation();
        cancellation.cancelActive();
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        ControlledFuture<ResultSet> queryFuture = future(() -> resultSet(() -> false));
        var executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(new NullMonitor(), cancellation, executor);

        InterruptedException thrown = assertThrows(InterruptedException.class,
                () -> runner.runScript(statement, "select 1"));

        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(0, executor.submitCalls());
        assertEquals(0, queryFuture.cancelCalls());
        assertEquals(0, statementCancels.get());
    }

    @Test
    void ownerAwaitsCompleteCancellationDrainAfterCancelledFutureWake() throws Exception {
        var cancellation = new JdbcCancellation();
        var futureGetEntered = new CountDownLatch(1);
        var futureCancelEntered = new CountDownLatch(1);
        var releaseFutureCancel = new CountDownLatch(1);
        var ownerFinished = new CountDownLatch(1);
        var ownerFailure = new AtomicReference<Throwable>();
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        CancellationException cancelled = new CancellationException("cancelled by registry");
        ControlledFuture<ResultSet> queryFuture = future(() -> {
            futureGetEntered.countDown();
            await(futureCancelEntered);
            throw cancelled;
        }, () -> {
            futureCancelEntered.countDown();
            awaitUninterruptibly(releaseFutureCancel);
        });
        ExecutorService queryExecutor = new FixedFutureExecutor(queryFuture, () -> { });
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        var runner = new JdbcRunner(new NullMonitor(), cancellation, queryExecutor);
        Thread owner = new Thread(() -> {
            try {
                runner.runScript(statement, "select 1");
            } catch (Throwable e) {
                ownerFailure.set(e);
            } finally {
                ownerFinished.countDown();
            }
        });
        owner.start();
        Future<?> cancelTask = canceller.submit(() -> {
            await(futureGetEntered);
            cancellation.cancelActive();
            return null;
        });
        try {
            assertTrue(futureCancelEntered.await(5, TimeUnit.SECONDS));
            assertFalse(ownerFinished.await(100, TimeUnit.MILLISECONDS));
            releaseFutureCancel.countDown();
            assertTrue(ownerFinished.await(5, TimeUnit.SECONDS));
            get(cancelTask);
        } finally {
            releaseFutureCancel.countDown();
            owner.interrupt();
            shutdown(canceller);
        }

        assertTrue(ownerFailure.get() instanceof InterruptedException);
        assertEquals(1, statementCancels.get());
    }

    @Test
    void externalFutureRuntimeFailureDrainsBeforeOwnerInterruption() throws Exception {
        RuntimeException queryCancelFailure = new IllegalStateException("query future cancel failed");

        assertExternalFutureFailureDrainsBeforeOwnerInterruption(queryCancelFailure);
    }

    @Test
    void externalFutureErrorDrainsBeforeOwnerInterruption() throws Exception {
        Error queryCancelFailure = new AssertionError("query future cancel failed");

        assertExternalFutureFailureDrainsBeforeOwnerInterruption(queryCancelFailure);
    }

    @Test
    void staleFutureFinallyCannotClearReplacement() throws Exception {
        var cancellation = new JdbcCancellation();
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> false, statementCancels::incrementAndGet);
        var getEntered = new CountDownLatch(1);
        var releaseGet = new CountDownLatch(1);
        ControlledFuture<String> original = future(() -> {
            getEntered.countDown();
            await(releaseGet);
            return "success";
        });
        ControlledFuture<String> replacement = future(() -> "replacement");
        ExecutorService queryExecutor = new FixedFutureExecutor(original, () -> { });
        ExecutorService ownerExecutor = Executors.newSingleThreadExecutor();
        var runner = new JdbcRunner(new NullMonitor(), cancellation, queryExecutor);

        Future<?> owner = ownerExecutor.submit(() -> {
            runner.run(statement, "select 1");
            return null;
        });
        try {
            assertTrue(getEntered.await(5, TimeUnit.SECONDS));
            cancellation.registerFuture(replacement);
            releaseGet.countDown();
            get(owner);
            cancellation.cancelActive();
        } finally {
            releaseGet.countDown();
            shutdown(ownerExecutor);
        }

        assertEquals(0, original.cancelCalls());
        assertEquals(1, replacement.cancelCalls());
        assertEquals(0, statementCancels.get());
    }

    @Test
    void successfulNonResultQueryClearsExactStatementRegistration() throws Exception {
        var cancellation = new JdbcCancellation();
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> false, statementCancels::incrementAndGet);
        ControlledFuture<String> queryFuture = future(() -> "success");
        var executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(new NullMonitor(), cancellation, executor);

        runner.run(statement, "select 1");
        cancellation.cancelActive();

        assertEquals(1, executor.submitCalls());
        assertEquals(0, queryFuture.cancelCalls());
        assertEquals(0, statementCancels.get());
    }

    @Test
    void activeStatementRemainsRegisteredWhileResultSetNextIsBlocked() throws Exception {
        var cancellation = new JdbcCancellation();
        var nextEntered = new CountDownLatch(1);
        var releaseNext = new CountDownLatch(1);
        var nextFinished = new CountDownLatch(1);
        var statementCancels = new AtomicInteger();
        ResultSet resultSet = resultSet(() -> {
            nextEntered.countDown();
            awaitUninterruptibly(releaseNext);
            return false;
        });
        Statement statement = statement(() -> resultSet, () -> {
            statementCancels.incrementAndGet();
            releaseNext.countDown();
        });
        ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
        var runner = new JdbcRunner(new NullMonitor(), cancellation, queryExecutor);

        ResultSet returned = runner.runScript(statement, "select 1");
        var failure = new AtomicReference<Throwable>();
        Thread iterator = new Thread(() -> {
            try {
                returned.next();
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                nextFinished.countDown();
            }
        });
        iterator.start();
        try {
            assertTrue(nextEntered.await(5, TimeUnit.SECONDS));
            assertFalse(queryExecutor.isShutdown());
            cancellation.cancelActive();
            assertTrue(nextFinished.await(5, TimeUnit.SECONDS));
        } finally {
            releaseNext.countDown();
            iterator.interrupt();
            shutdown(queryExecutor);
        }

        assertNull(failure.get());
        assertEquals(1, statementCancels.get());
    }

    @Test
    void cancelActiveUnblocksExecuteQueryAndReturnsInterruption() throws Exception {
        var cancellation = new JdbcCancellation();
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> {
            executeEntered.countDown();
            awaitUninterruptibly(releaseExecute);
            return resultSet(() -> false);
        }, () -> {
            statementCancels.incrementAndGet();
            releaseExecute.countDown();
        });
        ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        var runner = new JdbcRunner(new NullMonitor(), cancellation, queryExecutor);
        Future<?> cancelTask = canceller.submit(() -> {
            await(executeEntered);
            cancellation.cancelActive();
            return null;
        });

        try {
            assertThrows(InterruptedException.class,
                    () -> runner.runScript(statement, "select 1"));
            get(cancelTask);
        } finally {
            releaseExecute.countDown();
            shutdown(canceller);
            shutdown(queryExecutor);
        }

        assertEquals(1, statementCancels.get());
    }

    @Test
    void connectionCloseUnblocksDriverIgnoringStatementCancelAndInterrupt() throws Exception {
        var cancellation = new JdbcCancellation();
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var statementCancels = new AtomicInteger();
        var connectionCloses = new AtomicInteger();
        Statement statement = statement(() -> {
            executeEntered.countDown();
            awaitUninterruptibly(releaseExecute);
            return resultSet(() -> false);
        }, statementCancels::incrementAndGet);
        Connection connection = connection(() -> {
            connectionCloses.incrementAndGet();
            releaseExecute.countDown();
        });
        cancellation.registerConnection(connection);
        ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        var runner = new JdbcRunner(new NullMonitor(), cancellation, queryExecutor);
        Future<?> cancelTask = canceller.submit(() -> {
            await(executeEntered);
            cancellation.cancelActive();
            return null;
        });

        try {
            assertThrows(InterruptedException.class,
                    () -> runner.runScript(statement, "select 1"));
            get(cancelTask);
        } finally {
            releaseExecute.countDown();
            shutdown(canceller);
            shutdown(queryExecutor);
        }

        assertEquals(1, statementCancels.get());
        assertEquals(1, connectionCloses.get());
    }

    @Test
    void waiterInterruptionStaysPrimaryWithNeutralCleanupFailureSuppressed() throws Exception {
        var cancellation = new JdbcCancellation();
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var ownerFinished = new CountDownLatch(1);
        var ownerFailure = new AtomicReference<Throwable>();
        Statement statement = statement(() -> {
            executeEntered.countDown();
            awaitUninterruptibly(releaseExecute);
            return resultSet(() -> false);
        }, () -> {
            releaseExecute.countDown();
            throw new SQLException("jdbc:sensitive password=sensitive");
        });
        ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
        var runner = new JdbcRunner(new NullMonitor(), cancellation, queryExecutor);
        Thread owner = new Thread(() -> {
            try {
                runner.runScript(statement, "select 1");
            } catch (Throwable e) {
                ownerFailure.set(e);
            } finally {
                ownerFinished.countDown();
            }
        });
        owner.start();
        try {
            assertTrue(executeEntered.await(5, TimeUnit.SECONDS));
            owner.interrupt();
            assertTrue(ownerFinished.await(5, TimeUnit.SECONDS));
        } finally {
            releaseExecute.countDown();
            owner.interrupt();
            shutdown(queryExecutor);
        }

        InterruptedException thrown = (InterruptedException) ownerFailure.get();
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals(STATEMENT_FAILURE, thrown.getSuppressed()[0].getMessage());
        assertNull(thrown.getSuppressed()[0].getCause());
    }

    @Test
    void monitorCancellationCreatesInterruptionWithRuntimeCleanupFailureSuppressed() throws Exception {
        var cancellation = new JdbcCancellation();
        var monitor = new TestMonitor();
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        RuntimeException cleanupFailure = new IllegalStateException("controlled cleanup failure");
        Statement statement = statement(() -> {
            executeEntered.countDown();
            awaitUninterruptibly(releaseExecute);
            return resultSet(() -> false);
        }, () -> {
            releaseExecute.countDown();
            throw cleanupFailure;
        });
        ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
        ExecutorService controller = Executors.newSingleThreadExecutor();
        var runner = new JdbcRunner(monitor, cancellation, queryExecutor);
        Future<?> controlTask = controller.submit(() -> {
            await(executeEntered);
            monitor.setCancelled(true);
            return null;
        });

        InterruptedException thrown;
        try {
            thrown = assertThrows(InterruptedException.class,
                    () -> runner.runScript(statement, "select 1"));
            get(controlTask);
        } finally {
            releaseExecute.countDown();
            shutdown(controller);
            shutdown(queryExecutor);
        }

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void preInterruptedOwnerFailsBeforeExecutorSubmit() throws Exception {
        var cancellation = new JdbcCancellation();
        var ownerFinished = new CountDownLatch(1);
        var ownerFailure = new AtomicReference<Throwable>();
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        ControlledFuture<ResultSet> queryFuture = future(() -> resultSet(() -> false));
        var executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(new NullMonitor(), cancellation, executor);
        Thread owner = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                runner.runScript(statement, "select 1");
            } catch (Throwable e) {
                ownerFailure.set(e);
            } finally {
                ownerFinished.countDown();
            }
        });

        owner.start();
        assertTrue(ownerFinished.await(5, TimeUnit.SECONDS));

        assertTrue(ownerFailure.get() instanceof InterruptedException);
        assertEquals(0, executor.submitCalls());
        assertEquals(0, queryFuture.cancelCalls());
        assertEquals(0, statementCancels.get());
    }

    @Test
    void preCancelledMonitorFailsBeforeExecutorSubmit() throws Exception {
        var cancellation = new JdbcCancellation();
        var monitor = new TestMonitor();
        monitor.setCancelled(true);
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        ControlledFuture<ResultSet> queryFuture = future(() -> resultSet(() -> false));
        var executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(monitor, cancellation, executor);

        assertThrows(InterruptedException.class,
                () -> runner.runScript(statement, "select 1"));

        assertEquals(0, executor.submitCalls());
        assertEquals(0, queryFuture.cancelCalls());
        assertEquals(0, statementCancels.get());
        assertTrue(cancellation.isCancellationRequested());
    }

    @Test
    void cancellationAfterInitialGateCancelsStatementBeforeExecutorSubmit() throws Exception {
        var cancellation = new JdbcCancellation();
        var monitor = new CancelOnSecondCheckMonitor();
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        ControlledFuture<ResultSet> queryFuture = future(() -> resultSet(() -> false));
        var executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(monitor, cancellation, executor);

        assertThrows(InterruptedException.class,
                () -> runner.runScript(statement, "select 1"));

        assertEquals(2, monitor.checks());
        assertEquals(0, executor.submitCalls());
        assertEquals(0, queryFuture.cancelCalls());
        assertEquals(1, statementCancels.get());
    }

    @Test
    void cancellationStateAfterGetPreventsSuccessfulPublication() throws Exception {
        var cancellation = new JdbcCancellation();
        var monitor = new TestMonitor();
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        ControlledFuture<ResultSet> queryFuture = future(() -> {
            monitor.setCancelled(true);
            return resultSet(() -> false);
        });
        ExecutorService executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(monitor, cancellation, executor);

        assertThrows(InterruptedException.class,
                () -> runner.runScript(statement, "select 1"));

        assertEquals(1, queryFuture.cancelCalls());
        assertEquals(1, statementCancels.get());
    }

    @Test
    void cancellationAfterLastWaitCheckPreventsSuccessfulPublication() throws Exception {
        var cancellation = new JdbcCancellation();
        var monitor = new ActionOnCheckMonitor(4, () -> cancel(cancellation));
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        ControlledFuture<ResultSet> queryFuture = future(() -> resultSet(() -> false));
        ExecutorService executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(monitor, cancellation, executor);

        assertThrows(InterruptedException.class,
                () -> runner.runScript(statement, "select 1"));

        assertEquals(4, monitor.checks());
        assertEquals(1, queryFuture.cancelCalls());
        assertEquals(1, statementCancels.get());
    }

    @Test
    void monitorCancellationExceptionAfterSuccessfulGetKeepsIdentityAndDrainFailure() throws Exception {
        var cancellation = new JdbcCancellation();
        CancellationException monitorFailure = new CancellationException("monitor failure after get");
        IllegalStateException cancellationFailure = new IllegalStateException("statement cancellation failure");
        var monitor = new ActionOnCheckMonitor(4, () -> {
            throw monitorFailure;
        });
        Statement statement = statement(() -> null, () -> {
            throw cancellationFailure;
        });
        ControlledFuture<ResultSet> queryFuture = future(() -> resultSet(() -> false));
        ExecutorService executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(monitor, cancellation, executor);

        CancellationException thrown = assertThrows(CancellationException.class,
                () -> runner.runScript(statement, "select 1"));

        assertSame(monitorFailure, thrown);
        assertEquals(4, monitor.checks());
        assertEquals(1, queryFuture.cancelCalls());
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cancellationFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void cancellationExceptionWithoutCancellationStateKeepsIdentityAndClearsReferences() throws Exception {
        var cancellation = new JdbcCancellation();
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        CancellationException expected = new CancellationException("independent future cancellation");
        ControlledFuture<ResultSet> queryFuture = future(() -> {
            throw expected;
        });
        ExecutorService executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(new NullMonitor(), cancellation, executor);

        CancellationException thrown = assertThrows(CancellationException.class,
                () -> runner.runScript(statement, "select 1"));
        cancellation.cancelActive();

        assertSame(expected, thrown);
        assertEquals(0, queryFuture.cancelCalls());
        assertEquals(0, statementCancels.get());
    }

    @Test
    void cancelledExecutionFailureIsNeutralAndDoesNotInspectDriverMessage() throws Exception {
        var cancellation = new JdbcCancellation();
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        SQLException sensitive = new SensitiveSQLException();
        ControlledFuture<ResultSet> queryFuture = future(() -> {
            cancel(cancellation);
            throw new NeutralExecutionException(sensitive);
        });
        ExecutorService executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(new NullMonitor(), cancellation, executor);

        InterruptedException thrown = assertThrows(InterruptedException.class,
                () -> runner.runScript(statement, "select 1"));

        assertNull(thrown.getCause());
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(sensitive, thrown.getSuppressed()[0]);
        assertEquals(1, queryFuture.cancelCalls());
        assertEquals(1, statementCancels.get());
    }

    @Test
    void sameWorkerAndCancellationCleanupFailureIsSuppressedOnlyOnce() throws Exception {
        var cancellation = new JdbcCancellation();
        var monitor = new TestMonitor();
        RuntimeException shared = new IllegalStateException("shared worker and cleanup failure");
        Statement statement = statement(() -> null, () -> {
            throw shared;
        });
        ControlledFuture<ResultSet> queryFuture = future(() -> {
            monitor.setCancelled(true);
            throw new ExecutionException(shared);
        });
        ExecutorService executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(monitor, cancellation, executor);

        InterruptedException thrown = assertThrows(InterruptedException.class,
                () -> runner.runScript(statement, "select 1"));

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(shared, thrown.getSuppressed()[0]);
    }

    @Test
    void ordinaryExecutionFailureKeepsSqlExceptionBehaviorAndClearsReferences() throws Exception {
        var cancellation = new JdbcCancellation();
        var statementCancels = new AtomicInteger();
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        SQLException cause = new SQLException("controlled driver failure");
        ExecutionException executionFailure = new ExecutionException(cause);
        ControlledFuture<ResultSet> queryFuture = future(() -> {
            throw executionFailure;
        });
        ExecutorService executor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(new NullMonitor(), cancellation, executor);

        SQLException thrown = assertThrows(SQLException.class,
                () -> runner.runScript(statement, "select 1"));
        cancellation.cancelActive();

        assertEquals("controlled driver failure", thrown.getMessage());
        assertSame(executionFailure, thrown.getCause());
        assertEquals(0, queryFuture.cancelCalls());
        assertEquals(0, statementCancels.get());
    }

    private static void assertExternalFutureFailureDrainsBeforeOwnerInterruption(Throwable queryCancelFailure)
            throws Exception {
        var cancellation = new JdbcCancellation();
        var queryGetEntered = new CountDownLatch(1);
        var queryCancelEntered = new CountDownLatch(1);
        var ownerFinished = new CountDownLatch(1);
        var ownerFailure = new AtomicReference<Throwable>();
        var statementCancels = new AtomicInteger();
        ControlledFuture<ResultSet> queryFuture = future(() -> {
            queryGetEntered.countDown();
            await(queryCancelEntered);
            throw new CancellationException("cancelled by external registry drain");
        }, () -> {
            queryCancelEntered.countDown();
            throwUnchecked(queryCancelFailure);
        });
        Statement statement = statement(() -> null, statementCancels::incrementAndGet);
        ExecutorService canceller = Executors.newSingleThreadExecutor();
        ExecutorService queryExecutor = new FixedFutureExecutor(queryFuture, () -> { });
        var runner = new JdbcRunner(new NullMonitor(), cancellation, queryExecutor);
        Thread owner = new Thread(() -> {
            try {
                runner.runScript(statement, "select 1");
            } catch (Throwable e) {
                ownerFailure.set(e);
            } finally {
                ownerFinished.countDown();
            }
        });

        owner.start();
        try {
            assertTrue(queryGetEntered.await(5, TimeUnit.SECONDS));
            Future<?> cancelTask = canceller.submit(() -> {
                cancellation.cancelActive();
                return null;
            });
            assertTrue(queryCancelEntered.await(5, TimeUnit.SECONDS));
            assertTrue(ownerFinished.await(5, TimeUnit.SECONDS));
            ExecutionException cancellationFailure = assertThrows(ExecutionException.class,
                    () -> get(cancelTask));
            assertSame(queryCancelFailure, cancellationFailure.getCause());
        } finally {
            owner.interrupt();
            shutdown(canceller);
        }

        assertTrue(ownerFailure.get() instanceof InterruptedException);
        assertEquals(1, queryFuture.cancelCalls());
        assertEquals(1, statementCancels.get());
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
        throw new AssertionError("Test failure must be unchecked", failure);
    }

    private static <T> ControlledFuture<T> future(FutureGet<T> action) {
        return future(action, () -> { });
    }

    private static <T> ControlledFuture<T> future(FutureGet<T> action, Runnable cancel) {
        return new ControlledFuture<>(action, cancel);
    }

    private static Statement statement(SqlGet execute, SqlRun cancel) {
        return statement(execute, cancel, () -> { });
    }

    private static Statement statement(SqlGet execute, SqlRun cancel, SqlRun close) {
        return proxy(Statement.class, (proxy, method, args) -> switch (method.getName()) {
        case "executeQuery" -> execute.get();
        case "execute" -> execute.get();
        case "cancel" -> {
            cancel.run();
            yield null;
        }
        case "close" -> {
            close.run();
            yield null;
        }
        default -> objectMethod(proxy, method.getName(), args, "Statement");
        });
    }

    private static ResultSet resultSet(SqlGet next) {
        return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
        case "next" -> next.get();
        case "close" -> null;
        default -> objectMethod(proxy, method.getName(), args, "ResultSet");
        });
    }

    private static Connection connection(SqlRun close) {
        return connection(() -> {
            throw new AssertionError("Unexpected createStatement");
        }, close);
    }

    private static Connection connection(SqlGet createStatement, SqlRun close) {
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
        case "createStatement" -> createStatement.get();
        case "close" -> {
            close.run();
            yield null;
        }
        default -> objectMethod(proxy, method.getName(), args, "Connection");
        });
    }

    private static IJdbcConnector connector(Connection connection) {
        return connector(() -> connection, () -> null);
    }

    private static IJdbcConnector connector(ConnectionGet connection, StringGet delimiter) {
        return new IJdbcConnector() {
            @Override
            public Connection getConnection() throws IOException {
                return connection.get();
            }

            @Override
            public String getBatchDelimiter() {
                return delimiter.get();
            }

            @Override
            public String getUrl() {
                throw new AssertionError("Unexpected connector URL access");
            }

            @Override
            public String getDbName() {
                throw new AssertionError("Unexpected connector database access");
            }
        };
    }

    private static Object objectMethod(Object proxy, String method, Object[] args, String label) {
        return switch (method) {
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        case "toString" -> label;
        default -> throw new AssertionError("Unexpected method: " + method);
        };
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }

    private static void cancel(JdbcCancellation cancellation) {
        try {
            cancellation.cancelActive();
        } catch (IOException e) {
            throw new AssertionError("Unexpected cancellation failure", e);
        }
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

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting for test latch");
                    }
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void get(Future<?> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        future.get(5, TimeUnit.SECONDS);
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @FunctionalInterface
    private interface SqlGet {

        Object get() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlRun {

        void run() throws SQLException;
    }

    @FunctionalInterface
    private interface ConnectionGet {

        Connection get() throws IOException;
    }

    @FunctionalInterface
    private interface StringGet {

        String get();
    }

    @FunctionalInterface
    private interface FutureGet<T> {

        T get() throws InterruptedException, ExecutionException, TimeoutException;
    }

    private static final class ControlledFuture<T> implements Future<T> {

        private final FutureGet<T> get;
        private final Runnable cancel;
        private final AtomicInteger cancelCalls = new AtomicInteger();

        private ControlledFuture(FutureGet<T> get, Runnable cancel) {
            this.get = get;
            this.cancel = cancel;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            assertTrue(mayInterruptIfRunning);
            cancelCalls.incrementAndGet();
            cancel.run();
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelCalls.get() != 0;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            try {
                return get.get();
            } catch (TimeoutException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return get.get();
        }

        private int cancelCalls() {
            return cancelCalls.get();
        }
    }

    private static final class FixedFutureExecutor extends AbstractExecutorService {

        private final Future<?> future;
        private final Runnable beforeReturn;
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private final AtomicInteger submitCalls = new AtomicInteger();

        private FixedFutureExecutor(Future<?> future, Runnable beforeReturn) {
            this.future = future;
            this.beforeReturn = beforeReturn;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> Future<T> submit(Callable<T> task) {
            submitCalls.incrementAndGet();
            beforeReturn.run();
            return (Future<T>) future;
        }

        private int submitCalls() {
            return submitCalls.get();
        }

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown.set(true);
            return java.util.List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdown.get();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown.get();
        }

        @Override
        public void execute(Runnable command) {
            throw new AssertionError("submit(Callable) must be used");
        }
    }

    private static final class PublicationRaceExecutor extends AbstractExecutorService {

        private final CountDownLatch submitEntered;
        private final CountDownLatch allowSubmitReturn;
        private final CountDownLatch cancellationFinished;
        private final CountDownLatch taskFinished;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();

        private PublicationRaceExecutor(CountDownLatch submitEntered,
                CountDownLatch allowSubmitReturn, CountDownLatch cancellationFinished,
                CountDownLatch taskFinished) {
            this.submitEntered = submitEntered;
            this.allowSubmitReturn = allowSubmitReturn;
            this.cancellationFinished = cancellationFinished;
            this.taskFinished = taskFinished;
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            var future = new FutureTask<>(task);
            worker.execute(() -> {
                try {
                    awaitUninterruptibly(cancellationFinished);
                    future.run();
                } finally {
                    taskFinished.countDown();
                }
            });
            submitEntered.countDown();
            awaitUninterruptibly(allowSubmitReturn);
            return future;
        }

        @Override
        public void shutdown() {
            worker.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return worker.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return worker.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return worker.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return worker.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            throw new AssertionError("submit(Callable) must be used");
        }
    }

    private static final class RejectingExecutor extends AbstractExecutorService {

        private final Runnable beforeReject;
        private final RejectedExecutionException rejection;

        private RejectingExecutor(Runnable beforeReject, RejectedExecutionException rejection) {
            this.beforeReject = beforeReject;
            this.rejection = rejection;
        }

        @Override
        public void execute(Runnable command) {
            beforeReject.run();
            throw rejection;
        }

        @Override
        public void shutdown() {
            // test executor owns no resources
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return java.util.List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }
    }

    private static final class TestMonitor implements IMonitor {

        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled.set(cancelled);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void worked(int i) {
            // not needed for cancellation tests
        }

        @Override
        public IMonitor createSubMonitor() {
            return this;
        }

        @Override
        public void setWorkRemaining(int size) {
            // not needed for cancellation tests
        }

        @Override
        public void setTaskName(String name) {
            // not needed for cancellation tests
        }
    }

    private static final class FailureAfterLatchMonitor implements IMonitor {

        private final CountDownLatch ready;
        private final Throwable failure;
        private final AtomicBoolean thrown = new AtomicBoolean();

        private FailureAfterLatchMonitor(CountDownLatch ready, Throwable failure) {
            this.ready = ready;
            this.failure = failure;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            // this monitor fails instead of reporting cancellation
        }

        @Override
        public boolean isCancelled() {
            if (ready.getCount() == 0 && thrown.compareAndSet(false, true)) {
                throwUnchecked(failure);
            }
            return false;
        }

        @Override
        public void worked(int i) {
            // not needed for cancellation tests
        }

        @Override
        public IMonitor createSubMonitor() {
            return this;
        }

        @Override
        public void setWorkRemaining(int size) {
            // not needed for cancellation tests
        }

        @Override
        public void setTaskName(String name) {
            // not needed for cancellation tests
        }
    }

    private static final class CancelOnSecondCheckMonitor implements IMonitor {

        private final AtomicInteger checks = new AtomicInteger();

        @Override
        public void setCancelled(boolean cancelled) {
            // cancellation state is derived from check order in this test monitor
        }

        @Override
        public boolean isCancelled() {
            return checks.incrementAndGet() > 1;
        }

        @Override
        public void worked(int i) {
            // not needed for cancellation tests
        }

        @Override
        public IMonitor createSubMonitor() {
            return this;
        }

        @Override
        public void setWorkRemaining(int size) {
            // not needed for cancellation tests
        }

        @Override
        public void setTaskName(String name) {
            // not needed for cancellation tests
        }

        private int checks() {
            return checks.get();
        }
    }

    private static final class ActionOnCheckMonitor implements IMonitor {

        private final int actionCheck;
        private final Runnable action;
        private final AtomicInteger checks = new AtomicInteger();

        private ActionOnCheckMonitor(int actionCheck, Runnable action) {
            this.actionCheck = actionCheck;
            this.action = action;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            // cancellation is driven by the deterministic check hook
        }

        @Override
        public boolean isCancelled() {
            if (checks.incrementAndGet() == actionCheck) {
                action.run();
            }
            return false;
        }

        @Override
        public void worked(int i) {
            // not needed for cancellation tests
        }

        @Override
        public IMonitor createSubMonitor() {
            return this;
        }

        @Override
        public void setWorkRemaining(int size) {
            // not needed for cancellation tests
        }

        @Override
        public void setTaskName(String name) {
            // not needed for cancellation tests
        }

        private int checks() {
            return checks.get();
        }
    }

    private static final class OverridingStatementCallable extends StatementCallable<Void> {

        private final Statement exposed;

        private OverridingStatementCallable(Statement owned, Statement exposed) {
            super(owned, null);
            this.exposed = exposed;
        }

        @Override
        public Statement getStatement() {
            return exposed;
        }

        @Override
        public Void call() {
            return null;
        }
    }

    private static final class SensitiveSQLException extends SQLException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new AssertionError("Sensitive SQLException message must not be inspected");
        }

        @Override
        public String getLocalizedMessage() {
            throw new AssertionError("Sensitive SQLException localized message must not be inspected");
        }

        @Override
        public String toString() {
            throw new AssertionError("Sensitive SQLException toString must not be inspected");
        }
    }

    private static final class NeutralExecutionException extends ExecutionException {

        private static final long serialVersionUID = 1L;

        private NeutralExecutionException(Throwable cause) {
            super(null, cause);
        }
    }
}
