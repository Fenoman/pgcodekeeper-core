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
package org.pgcodekeeper.core.database.base.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.settings.CoreSettings;

@Isolated("installs the package-local parser execution observer")
class AntlrExecutionPolicyTest {

    private enum TerminalAction {
        REQUEST_ABORT,
        ABORT,
        CLOSE
    }

    @Test
    void dedicatedPolicyCapsWorkersAndRejectsAfterClose() throws Exception {
        ParserExecutionPolicy policy = ParserExecutionPolicy.dedicated(2);
        Queue<AntlrTask<?>> queue = AntlrTaskManager.createTaskQueue(policy);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var finalized = new AtomicInteger();
        Set<String> workerNames = ConcurrentHashMap.newKeySet();
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);

        try {
            for (int i = 0; i < 8; i++) {
                int taskId = i;
                AntlrTaskManager.submit(queue, () -> {
                    workerNames.add(Thread.currentThread().getName());
                    int now = active.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    started.countDown();
                    try {
                        release.await();
                    } finally {
                        active.decrementAndGet();
                    }
                    return taskId;
                }, ignored -> finalized.incrementAndGet());
            }

            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(2, peak.get());
            assertTrue(workerNames.stream()
                    .allMatch(name -> name.startsWith("pgck-antlr-index-")));

            release.countDown();
            AntlrTaskManager.finish(queue);
            assertEquals(8, finalized.get());

            AntlrTaskManager.close(queue);
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> AntlrTaskManager.submit(queue, () -> 1, ignored -> { }));
            assertEquals("ANTLR task queue is closed", thrown.getMessage());
        } finally {
            release.countDown();
            AntlrTaskManager.close(queue);
        }
    }

    @Test
    void closeInterruptsDedicatedWorkerAndDrainsQueue() throws Exception {
        Queue<AntlrTask<?>> queue = AntlrTaskManager.createTaskQueue(
                ParserExecutionPolicy.dedicated(1));
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);

        try {
            AntlrTaskManager.submit(queue, () -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                    return 1;
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                    throw ex;
                }
            }, ignored -> { });

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AntlrTaskManager.close(queue);

            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(AntlrTaskManager.isDrained(queue));
            assertThrows(IllegalStateException.class,
                    () -> AntlrTaskManager.submit(queue, () -> 1, ignored -> { }));
        } finally {
            AntlrTaskManager.close(queue);
        }
    }

    @Test
    void siblingQueuesShareDedicatedWorkersAndRootOwnsLifecycle() throws Exception {
        Queue<AntlrTask<?>> root = AntlrTaskManager.createTaskQueue(
                ParserExecutionPolicy.dedicated(2));
        Queue<AntlrTask<?>> sibling = AntlrTaskManager.createSiblingTaskQueue(root);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);

        try {
            submitBlocking(root, active, peak, entered, release);
            submitBlocking(sibling, active, peak, entered, release);
            submitBlocking(root, active, peak, entered, release);
            submitBlocking(sibling, active, peak, entered, release);

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, peak.get(), "sibling queues created extra workers");

            release.countDown();
            AntlrTaskManager.finish(root);
            AntlrTaskManager.finish(sibling);

            AntlrTaskManager.close(sibling);
            AntlrTaskManager.submit(root, () -> 1, ignored -> { });
            AntlrTaskManager.finish(root);

            AntlrTaskManager.close(root);
            assertThrows(IllegalStateException.class,
                    () -> AntlrTaskManager.submit(root, () -> 1, ignored -> { }));
            assertThrows(IllegalStateException.class,
                    () -> AntlrTaskManager.submit(sibling, () -> 1, ignored -> { }));
        } finally {
            release.countDown();
            AntlrTaskManager.close(root);
        }
    }

    @Test
    void rootCloseInterruptsEverySiblingWorkerAndRemovesDedicatedThreads()
            throws Exception {
        Queue<AntlrTask<?>> root = AntlrTaskManager.createTaskQueue(
                ParserExecutionPolicy.dedicated(2));
        Queue<AntlrTask<?>> sibling = AntlrTaskManager.createSiblingTaskQueue(root);
        var entered = new CountDownLatch(2);
        var exited = new CountDownLatch(2);

        try {
            submitUntilInterrupted(root, entered, exited);
            submitUntilInterrupted(sibling, entered, exited);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            AntlrTaskManager.requestAbort(root);
            AntlrTaskManager.close(root);

            assertTrue(exited.await(5, TimeUnit.SECONDS));
        } finally {
            AntlrTaskManager.close(root);
        }
    }

    @Test
    void siblingQueuesShareOperationWidePendingCountAndCloseWakesWaiter()
            throws Exception {
        long taskBytes = 32L << 20;
        ParserExecutionPolicy policy = new ParserExecutionPolicy(
                2, 2, 64L << 20);
        var entered = new CountDownLatch(2);
        var interrupted = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        ExecutorService waiterOwner = Executors.newSingleThreadExecutor();

        try (var probe = ParserExecutionProbeHarness.install()) {
            AntlrPipeline root = assertInstanceOf(AntlrPipeline.class,
                    AntlrTaskManager.createTaskQueue(policy));
            List<AntlrPipeline> queues = List.of(root,
                    sibling(root), sibling(root), sibling(root));
            try {
                for (AntlrPipeline queue : queues) {
                    for (int task = 0; task < 2; task++) {
                        submitBlocking(queue, taskBytes, entered, interrupted,
                                release);
                    }
                }

                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(2, probe.onlySession().snapshot().submittedTasks());
                assertEquals(2, queues.stream()
                        .mapToInt(AntlrPipeline::runningCount).sum());
                assertEquals(64L << 20, queues.stream()
                        .mapToLong(AntlrPipeline::runningWeight).sum());
                assertEquals(6, queues.stream()
                        .mapToInt(AntlrPipeline::pendingCount).sum());

                var finishStarted = new CountDownLatch(1);
                Future<?> waitingFinish = waiterOwner.submit(() -> {
                    finishStarted.countDown();
                    AntlrTaskManager.finish(queues.get(3));
                    return null;
                });
                assertTrue(finishStarted.await(5, TimeUnit.SECONDS));
                assertTrue(probe.onlySession()
                        .awaitAdmissionWait(5, TimeUnit.SECONDS));

                AntlrTaskManager.close(root);

                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> waitingFinish.get(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof InterruptedException,
                        () -> String.valueOf(failure.getCause()));
                assertTrue(queues.stream().allMatch(AntlrPipeline::isDrained));
            } finally {
                release.countDown();
                AntlrTaskManager.close(root);
            }

            ParserExecutionProbeHarness.Snapshot snapshot =
                    probe.onlySession().snapshot();
            assertEquals(2, snapshot.submittedTasks());
            assertTrue(snapshot.terminated());
        } finally {
            release.countDown();
            waiterOwner.shutdownNow();
            assertTrue(waiterOwner.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rootCloseCoordinatesWithSiblingOwnerDuringCancellation()
            throws Exception {
        ExecutorService owners = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 500; iteration++) {
                assertCoordinatedClose(owners, iteration);
            }
        } finally {
            owners.shutdownNow();
            assertTrue(owners.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rootClosePreservesCancelFailureBeforeDirectOfferPublication()
            throws Exception {
        AntlrPipeline root = assertInstanceOf(AntlrPipeline.class,
                AntlrTaskManager.createTaskQueue(
                        ParserExecutionPolicy.dedicated(1)));
        RuntimeException expected = new IllegalStateException(
                "controlled offer cancellation failure");
        var future = new OfferRaceFuture(expected);
        var task = new RegistrationBlockingTask(future);
        var offerFailure = new AtomicReference<Throwable>();
        var closeFailure = new AtomicReference<Throwable>();
        var offering = new Thread(() -> {
            try {
                root.offer(task);
            } catch (Throwable ex) {
                offerFailure.set(ex);
            }
        });
        var closing = new Thread(() -> {
            try {
                root.close();
            } catch (Throwable ex) {
                closeFailure.set(ex);
            }
        });

        try {
            offering.start();
            assertTrue(task.registrationReached.await(5, TimeUnit.SECONDS));
            closing.start();
            assertTrue(future.cancelAttempted.await(5, TimeUnit.SECONDS));
            task.allowRegistrationReturn.countDown();
            offering.join(TimeUnit.SECONDS.toMillis(5));
            closing.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(offering.isAlive());
            assertFalse(closing.isAlive());
            assertInstanceOf(java.util.concurrent.CancellationException.class,
                    offerFailure.get());
            assertSame(expected, closeFailure.get());
            assertTrue(root.isDrained());
            assertEquals(0, root.operationAdmittedCount());
            assertEquals(0, root.operationAdmittedBytes());
        } finally {
            task.allowRegistrationReturn.countDown();
            offering.join(TimeUnit.SECONDS.toMillis(5));
            closing.join(TimeUnit.SECONDS.toMillis(5));
            root.close();
        }
    }

    @RepeatedTest(100)
    void requestAbortReportsQueueFailuresInRegistrationOrderAndCleansUp() {
        assertStableFailureOrder(TerminalAction.REQUEST_ABORT);
    }

    @RepeatedTest(100)
    void abortReportsQueueFailuresInRegistrationOrderAndCleansUp() {
        assertStableFailureOrder(TerminalAction.ABORT);
    }

    @RepeatedTest(100)
    void closeReportsQueueFailuresInRegistrationOrderAndCleansUp() {
        assertStableFailureOrder(TerminalAction.CLOSE);
    }

    @RepeatedTest(100)
    void terminalCleanupSuppressesEachFailureIdentityOnlyOnce() {
        for (TerminalAction action : TerminalAction.values()) {
            RuntimeException shared = new IllegalStateException("shared");
            FailureScenario scenario = failureScenario(shared, shared, shared);

            RuntimeException thrown = runTerminalAction(scenario.root(), action);

            assertSame(shared, thrown);
            assertEquals(0, thrown.getSuppressed().length);
            assertScenarioDrained(scenario);
        }
    }

    @RepeatedTest(100)
    void requestAbortReportsTaskFailuresInRegistrationOrderAndCleansRootQueue() {
        RuntimeException firstFailure = new IllegalStateException("first");
        RuntimeException secondFailure = new IllegalStateException("second");
        RuntimeException thirdFailure = new IllegalStateException("third");
        SingleQueueFailureScenario scenario = singleQueueFailureScenario(
                firstFailure, secondFailure, thirdFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                scenario.root()::requestAbort);
        scenario.root().abort();
        scenario.root().close();

        assertSame(firstFailure, thrown);
        assertEquals(2, thrown.getSuppressed().length);
        assertSame(secondFailure, thrown.getSuppressed()[0]);
        assertSame(thirdFailure, thrown.getSuppressed()[1]);
        assertSingleQueueDrained(scenario);
    }

    @RepeatedTest(100)
    void requestAbortSuppressesEachTaskFailureIdentityOnlyOnce() {
        RuntimeException firstFailure = new IllegalStateException("first");
        RuntimeException shared = new IllegalStateException("shared");
        SingleQueueFailureScenario scenario = singleQueueFailureScenario(
                firstFailure, shared, shared);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                scenario.root()::requestAbort);
        scenario.root().abort();
        scenario.root().close();

        assertSame(firstFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(shared, thrown.getSuppressed()[0]);
        assertSingleQueueDrained(scenario);
    }

    @RepeatedTest(100)
    void abortAndCloseReportIdentityCollidingTasksInRegistrationOrder() {
        for (TerminalAction action : List.of(
                TerminalAction.ABORT, TerminalAction.CLOSE)) {
            RuntimeException firstFailure = new IllegalStateException("first");
            RuntimeException secondFailure = new IllegalStateException("second");
            RuntimeException thirdFailure = new IllegalStateException("third");
            SingleQueueFailureScenario scenario = singleQueueFailureScenario(
                    firstFailure, secondFailure, thirdFailure);

            RuntimeException thrown = runTerminalAction(
                    scenario.root(), action);

            assertSame(firstFailure, thrown);
            assertEquals(2, thrown.getSuppressed().length);
            assertSame(secondFailure, thrown.getSuppressed()[0]);
            assertSame(thirdFailure, thrown.getSuppressed()[1]);
            assertSingleQueueDrained(scenario);
        }
    }

    @Test
    void siblingQueuesShareOperationWidePendingByteBudget() throws Exception {
        ParserExecutionPolicy policy = new ParserExecutionPolicy(2, 8, 64);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(0);
        var release = new CountDownLatch(1);

        try (var probe = ParserExecutionProbeHarness.install()) {
            AntlrPipeline root = assertInstanceOf(AntlrPipeline.class,
                    AntlrTaskManager.createTaskQueue(policy));
            List<AntlrPipeline> queues = List.of(root,
                    sibling(root), sibling(root), sibling(root));
            try {
                for (AntlrPipeline queue : queues) {
                    submitBlocking(queue, 40, entered, interrupted, release);
                }

                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, probe.onlySession().snapshot().submittedTasks());
                assertEquals(1, queues.stream()
                        .mapToInt(AntlrPipeline::runningCount).sum());
                assertEquals(40, queues.stream()
                        .mapToLong(AntlrPipeline::runningWeight).sum());
                assertEquals(3, queues.stream()
                        .mapToInt(AntlrPipeline::pendingCount).sum());

                release.countDown();
                for (AntlrPipeline queue : queues) {
                    AntlrTaskManager.finish(queue);
                }

                assertEquals(4, probe.onlySession().snapshot().submittedTasks());
                assertTrue(queues.stream().allMatch(AntlrPipeline::isDrained));
            } finally {
                release.countDown();
                AntlrTaskManager.close(root);
            }
        }
    }

    @Test
    void settingsDefaultToSharedAndCopyDedicatedPolicy() {
        var settings = new CoreSettings();

        assertEquals(ParserExecutionPolicy.SHARED,
                settings.getParserExecutionPolicy());

        ParserExecutionPolicy dedicated = ParserExecutionPolicy.dedicated(3);
        settings.setParserExecutionPolicy(dedicated);

        assertEquals(dedicated, settings.copy().getParserExecutionPolicy());
    }

    private static void submitBlocking(Queue<AntlrTask<?>> queue,
            AtomicInteger active, AtomicInteger peak, CountDownLatch entered,
            CountDownLatch release) {
        AntlrTaskManager.submit(queue, () -> {
            int now = active.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            entered.countDown();
            try {
                release.await();
            } finally {
                active.decrementAndGet();
            }
            return null;
        }, ignored -> { });
    }

    private static void submitBlocking(Queue<AntlrTask<?>> queue, long weight,
            CountDownLatch entered, CountDownLatch interrupted,
            CountDownLatch release) {
        AntlrTaskManager.submit(queue, weight, () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                throw ex;
            }
            return null;
        }, ignored -> { });
    }

    private static AntlrPipeline sibling(AntlrPipeline root) {
        return assertInstanceOf(AntlrPipeline.class,
                AntlrTaskManager.createSiblingTaskQueue(root));
    }

    private static void assertStableFailureOrder(TerminalAction action) {
        RuntimeException rootFailure = new IllegalStateException("root");
        RuntimeException firstFailure = new IllegalStateException("first");
        RuntimeException secondFailure = new IllegalStateException("second");
        FailureScenario scenario = failureScenario(
                rootFailure, firstFailure, secondFailure);

        RuntimeException thrown = runTerminalAction(scenario.root(), action);

        assertSame(rootFailure, thrown);
        assertEquals(2, thrown.getSuppressed().length);
        assertSame(firstFailure, thrown.getSuppressed()[0]);
        assertSame(secondFailure, thrown.getSuppressed()[1]);
        assertScenarioDrained(scenario);
    }

    private static FailureScenario failureScenario(
            RuntimeException rootFailure, RuntimeException firstFailure,
            RuntimeException secondFailure) {
        AntlrPipeline root = assertInstanceOf(AntlrPipeline.class,
                AntlrTaskManager.createTaskQueue(
                        ParserExecutionPolicy.dedicated(1)));
        AntlrPipeline first = sibling(root);
        AntlrPipeline second = sibling(root);
        var rootFuture = new CancelThenThrowFuture(rootFailure);
        var firstFuture = new CancelThenThrowFuture(firstFailure);
        var secondFuture = new CancelThenThrowFuture(secondFailure);
        root.add(new AntlrTask<>(rootFuture, ignored -> { }));
        first.add(new AntlrTask<>(firstFuture, ignored -> { }));
        second.add(new AntlrTask<>(secondFuture, ignored -> { }));
        return new FailureScenario(root, first, second,
                rootFuture, firstFuture, secondFuture);
    }

    private static RuntimeException runTerminalAction(AntlrPipeline root,
            TerminalAction action) {
        RuntimeException thrown;
        switch (action) {
        case REQUEST_ABORT:
            thrown = assertThrows(RuntimeException.class, root::requestAbort);
            root.abort();
            root.close();
            break;
        case ABORT:
            thrown = assertThrows(RuntimeException.class, root::abort);
            root.close();
            break;
        case CLOSE:
            thrown = assertThrows(RuntimeException.class, root::close);
            break;
        default:
            throw new AssertionError(action);
        }
        return thrown;
    }

    private static void assertScenarioDrained(FailureScenario scenario) {
        assertTrue(scenario.rootFuture().isCancelled());
        assertTrue(scenario.firstFuture().isCancelled());
        assertTrue(scenario.secondFuture().isCancelled());
        assertTrue(scenario.root().isDrained());
        assertTrue(scenario.first().isDrained());
        assertTrue(scenario.second().isDrained());
        assertEquals(0, scenario.root().operationAdmittedCount());
        assertEquals(0, scenario.root().operationAdmittedBytes());
    }

    private static SingleQueueFailureScenario singleQueueFailureScenario(
            RuntimeException firstFailure, RuntimeException secondFailure,
            RuntimeException thirdFailure) {
        AntlrPipeline root = assertInstanceOf(AntlrPipeline.class,
                AntlrTaskManager.createTaskQueue(
                        ParserExecutionPolicy.dedicated(1)));
        var firstFuture = new CancelThenThrowFuture(firstFailure);
        var secondFuture = new CancelThenThrowFuture(secondFailure);
        var thirdFuture = new CancelThenThrowFuture(thirdFailure);
        root.add(new IdentityCollidingAntlrTask(firstFuture));
        root.add(new IdentityCollidingAntlrTask(secondFuture));
        root.add(new IdentityCollidingAntlrTask(thirdFuture));
        return new SingleQueueFailureScenario(root,
                firstFuture, secondFuture, thirdFuture);
    }

    private static void assertSingleQueueDrained(
            SingleQueueFailureScenario scenario) {
        assertTrue(scenario.firstFuture().isCancelled());
        assertTrue(scenario.secondFuture().isCancelled());
        assertTrue(scenario.thirdFuture().isCancelled());
        assertTrue(scenario.root().isDrained());
        assertEquals(0, scenario.root().registeredTaskCount());
        assertEquals(0, scenario.root().operationAdmittedCount());
        assertEquals(0, scenario.root().operationAdmittedBytes());
    }

    private record FailureScenario(AntlrPipeline root, AntlrPipeline first,
            AntlrPipeline second, CancelThenThrowFuture rootFuture,
            CancelThenThrowFuture firstFuture,
            CancelThenThrowFuture secondFuture) {
    }

    private record SingleQueueFailureScenario(AntlrPipeline root,
            CancelThenThrowFuture firstFuture,
            CancelThenThrowFuture secondFuture,
            CancelThenThrowFuture thirdFuture) {
    }

    private static final class CancelThenThrowFuture
            extends CompletableFuture<Integer> {

        private final RuntimeException failure;
        private boolean firstCancel = true;

        private CancelThenThrowFuture(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (firstCancel) {
                firstCancel = false;
                throw failure;
            }
            return cancelled;
        }
    }

    private static final class IdentityCollidingAntlrTask
            extends AntlrTask<Integer> {

        private IdentityCollidingAntlrTask(Future<Integer> future) {
            super(future, ignored -> { });
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof IdentityCollidingAntlrTask;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    private static void assertCoordinatedClose(ExecutorService owners,
            int iteration) throws Exception {
        AntlrPipeline root = assertInstanceOf(AntlrPipeline.class,
                AntlrTaskManager.createTaskQueue(
                        new ParserExecutionPolicy(1, 2, 64)));
        AntlrPipeline sibling = sibling(root);
        var blockingFuture = new BlockingCancelFuture();
        var workerStarted = new CountDownLatch(1);
        var workerInterrupted = new CountDownLatch(1);
        try {
            AntlrTaskManager.submit(root, 40, () -> {
                workerStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException ex) {
                    workerInterrupted.countDown();
                    throw ex;
                }
                return 1;
            }, ignored -> { });
            assertTrue(sibling.offer(new AntlrTask<>(blockingFuture,
                    ignored -> { })));
            AntlrTaskManager.submit(sibling, 1, () -> 1, ignored -> { });
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            assertEquals(2, root.operationAdmittedCount());
            assertEquals(40, root.operationAdmittedBytes());

            Future<?> owner = owners.submit(() -> {
                AntlrTaskManager.finish(sibling);
                return null;
            });
            assertTrue(blockingFuture.getStarted.await(5, TimeUnit.SECONDS),
                    () -> "owner did not start at iteration " + iteration);

            Future<?> closing = owners.submit(() -> {
                AntlrTaskManager.close(root);
                return null;
            });
            assertTrue(blockingFuture.cancelStarted.await(5, TimeUnit.SECONDS),
                    () -> "close did not cancel at iteration " + iteration);

            ExecutionException ownerFailure = assertThrows(
                    ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS),
                    () -> "owner did not finish at iteration " + iteration);
            assertInstanceOf(InterruptedException.class,
                    ownerFailure.getCause());

            blockingFuture.allowCancelReturn.countDown();
            closing.get(5, TimeUnit.SECONDS);
            assertTrue(workerInterrupted.await(5, TimeUnit.SECONDS));
            assertTrue(root.isDrained());
            assertTrue(sibling.isDrained());
            assertEquals(0, root.operationAdmittedCount());
            assertEquals(0, root.operationAdmittedBytes());
        } finally {
            blockingFuture.allowCancelReturn.countDown();
            AntlrTaskManager.close(root);
        }
    }

    private static final class BlockingCancelFuture
            extends CompletableFuture<Integer> {

        private final CountDownLatch getStarted = new CountDownLatch(1);
        private final CountDownLatch cancelStarted = new CountDownLatch(1);
        private final CountDownLatch allowCancelReturn = new CountDownLatch(1);
        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public Integer get() throws InterruptedException, ExecutionException {
            getStarted.countDown();
            return super.get();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean first = cancelCalls.getAndIncrement() == 0;
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (first) {
                cancelStarted.countDown();
                awaitUninterruptibly(allowCancelReturn);
            }
            return cancelled;
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            boolean interrupted = false;
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class RegistrationBlockingTask
            extends AntlrTask<Integer> {

        private final CountDownLatch registrationReached =
                new CountDownLatch(1);
        private final CountDownLatch allowRegistrationReturn =
                new CountDownLatch(1);
        private boolean firstAdmissionCheck = true;

        private RegistrationBlockingTask(Future<Integer> future) {
            super(future, ignored -> { });
        }

        @Override
        boolean isAdmitted() {
            if (firstAdmissionCheck) {
                firstAdmissionCheck = false;
                registrationReached.countDown();
                BlockingCancelFuture.awaitUninterruptibly(
                        allowRegistrationReturn);
            }
            return super.isAdmitted();
        }
    }

    private static final class OfferRaceFuture
            extends CompletableFuture<Integer> {

        private final RuntimeException failure;
        private final CountDownLatch cancelAttempted = new CountDownLatch(1);
        private boolean firstCancel = true;

        private OfferRaceFuture(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            cancelAttempted.countDown();
            if (firstCancel) {
                firstCancel = false;
                throw failure;
            }
            return cancelled;
        }
    }

    private static void submitUntilInterrupted(Queue<AntlrTask<?>> queue,
            CountDownLatch entered, CountDownLatch exited) {
        AntlrTaskManager.submit(queue, () -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } finally {
                exited.countDown();
            }
            return null;
        }, ignored -> { });
    }
}
