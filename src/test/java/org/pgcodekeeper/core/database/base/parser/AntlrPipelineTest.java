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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;

@Isolated("mutates parser pending-limit system properties")
class AntlrPipelineTest {

    private String originalMaxPending;
    private String originalMaxPendingBytes;

    @BeforeEach
    void rememberMaxPendingProperty() {
        originalMaxPending = System.getProperty(Consts.MAX_PENDING_TASKS);
        originalMaxPendingBytes = System.getProperty(Consts.MAX_PENDING_BYTES);
    }

    @AfterEach
    void restoreMaxPendingProperty() {
        if (originalMaxPending == null) {
            System.clearProperty(Consts.MAX_PENDING_TASKS);
        } else {
            System.setProperty(Consts.MAX_PENDING_TASKS, originalMaxPending);
        }
        if (originalMaxPendingBytes == null) {
            System.clearProperty(Consts.MAX_PENDING_BYTES);
        } else {
            System.setProperty(Consts.MAX_PENDING_BYTES, originalMaxPendingBytes);
        }
    }

    @Test
    void tryFinishNextNeverWaitsOrSkipsTheHead() throws Exception {
        var executor = new ManualExecutor();
        var finalized = new ArrayList<String>();
        var pipeline = new AntlrPipeline(2, 2, executor);
        pipeline.submit(1, () -> "first", finalized::add);
        pipeline.submit(1, () -> "second", finalized::add);
        executor.run(1);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertFalse(pipeline.tryFinishNext()));
        assertAll(
                () -> assertTrue(finalized.isEmpty()),
                () -> assertEquals(2, pipeline.size()),
                () -> assertEquals(2, pipeline.runningCount()),
                () -> assertEquals(2, pipeline.runningWeight()));

        executor.run(0);
        assertTrue(pipeline.tryFinishNext());
        assertAll(
                () -> assertIterableEquals(List.of("first"), finalized),
                () -> assertEquals(1, pipeline.size()),
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(1, pipeline.runningWeight()));

        assertTrue(pipeline.tryFinishNext());
        assertIterableEquals(List.of("first", "second"), finalized);
        assertDrained(pipeline);
    }

    @Test
    void managerTryFinishNextMapsHeadIOExceptionAndAbortsLaterTasks() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, 10, executor);
        var expected = new IOException("completed head failed");
        AntlrTaskManager.submit(pipeline, 4, () -> {
            throw expected;
        }, ignored -> { });
        AntlrTaskManager.submit(pipeline, 5, () -> "running", ignored -> { });
        AntlrTaskManager.submit(pipeline, 6, () -> "pending", ignored -> { });
        executor.run(0);

        IOException actual = assertThrows(IOException.class,
                () -> AntlrTaskManager.tryFinishNext(pipeline));

        assertSame(expected, actual);
        assertAll(
                () -> assertEquals(2, executor.submittedCount()),
                () -> assertTrue(executor.future(1).isCancelled()));
        assertDrained(pipeline);
    }

    @Test
    void managerTryFinishNextIsExactNoOpForUnboundedQueue() throws Exception {
        var finalized = new ArrayList<String>();
        var firstFuture = new FutureTask<>(() -> "first");
        var secondFuture = new FutureTask<>(() -> "second");
        var first = new AntlrTask<>(firstFuture, finalized::add);
        var second = new AntlrTask<>(secondFuture, finalized::add);
        var queue = new ArrayDeque<AntlrTask<?>>();
        queue.add(first);
        queue.add(second);
        firstFuture.run();

        assertFalse(AntlrTaskManager.tryFinishNext(queue));

        assertAll(
                () -> assertEquals(2, queue.size()),
                () -> assertSame(first, queue.peek()),
                () -> assertTrue(finalized.isEmpty()),
                () -> assertFalse(firstFuture.isCancelled()),
                () -> assertFalse(secondFuture.isCancelled()));
    }

    @Test
    void requestAbortCancelsSubmittedFutureWithoutMutatingOwnerQueue() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(1, executor);
        AntlrTaskManager.submit(pipeline, () -> "parsed", ignored -> { });
        AntlrTask<?> task = pipeline.peek();

        var requester = new Thread(pipeline::requestAbort);
        requester.start();
        requester.join(TimeUnit.SECONDS.toMillis(5));

        assertAll(
                () -> assertFalse(requester.isAlive()),
                () -> assertSame(task, pipeline.peek()),
                () -> assertEquals(1, pipeline.size()),
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(1, pipeline.registeredTaskCount()),
                () -> assertTrue(executor.future(0).isCancelled()));

        pipeline.abort();
        assertDrained(pipeline);
    }

    @Test
    void localRequestAbortDoesNotCancelSnapshotTaskAgainAfterItBecomesCurrent()
            throws Exception {
        assertNoDuplicateCancelAcrossCurrentTransition(false);
    }

    @Test
    void scopeRequestAbortDoesNotCancelSnapshotTaskAgainAfterItBecomesCurrent()
            throws Exception {
        assertNoDuplicateCancelAcrossCurrentTransition(true);
    }

    @Test
    void ownerAbortWaitsForManagerWorkerExitAndRestoresInterruptFlag() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var pipeline = new AntlrPipeline(1, executor);
        var workerEntered = new CountDownLatch(1);
        var cancellationObserved = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        var workerExited = new AtomicBoolean();
        var ownerStarted = new CountDownLatch(1);
        var ownerDone = new CountDownLatch(1);
        var ownerInterrupted = new AtomicBoolean();
        var ownerFailure = new AtomicReference<Throwable>();
        AntlrTaskManager.submit(pipeline,
                () -> awaitReleaseIgnoringInterrupt(workerEntered, cancellationObserved,
                        releaseWorker, workerExited), ignored -> { });

        var owner = new Thread(() -> {
            ownerStarted.countDown();
            try {
                pipeline.abort();
            } catch (Throwable ex) {
                ownerFailure.set(ex);
            } finally {
                ownerInterrupted.set(Thread.currentThread().isInterrupted());
                ownerDone.countDown();
            }
        });

        try {
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));

            var requester = new Thread(pipeline::requestAbort);
            requester.start();
            requester.join(TimeUnit.SECONDS.toMillis(5));

            assertAll(
                    () -> assertFalse(requester.isAlive()),
                    () -> assertTrue(cancellationObserved.await(5, TimeUnit.SECONDS)),
                    () -> assertFalse(workerExited.get()),
                    () -> assertEquals(1, pipeline.size()),
                    () -> assertEquals(1, pipeline.runningCount()),
                    () -> assertEquals(1, pipeline.registeredTaskCount()));

            owner.start();
            assertTrue(ownerStarted.await(5, TimeUnit.SECONDS));
            assertFalse(ownerDone.await(100, TimeUnit.MILLISECONDS));
            owner.interrupt();
            assertFalse(ownerDone.await(100, TimeUnit.MILLISECONDS));
            assertAll(
                    () -> assertFalse(workerExited.get()),
                    () -> assertEquals(1, pipeline.size()),
                    () -> assertEquals(1, pipeline.runningCount()),
                    () -> assertEquals(1, pipeline.registeredTaskCount()));

            releaseWorker.countDown();
            assertTrue(ownerDone.await(5, TimeUnit.SECONDS));
            owner.join(TimeUnit.SECONDS.toMillis(5));

            assertAll(
                    () -> assertFalse(owner.isAlive()),
                    () -> assertNull(ownerFailure.get()),
                    () -> assertTrue(workerExited.get()),
                    () -> assertTrue(ownerInterrupted.get()));
            assertDrained(pipeline);
        } finally {
            releaseWorker.countDown();
            owner.interrupt();
            owner.join(TimeUnit.SECONDS.toMillis(5));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void ownerAbortCancelsEveryWorkerBeforeWaitingForTheirExit() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var pipeline = new AntlrPipeline(2, executor);
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var firstCancellationObserved = new CountDownLatch(1);
        var secondCancellationObserved = new CountDownLatch(1);
        var releaseWorkers = new CountDownLatch(1);
        var firstExited = new AtomicBoolean();
        var secondExited = new AtomicBoolean();
        var ownerDone = new CountDownLatch(1);
        AntlrTaskManager.submit(pipeline,
                () -> awaitReleaseIgnoringInterrupt(firstEntered, firstCancellationObserved,
                        releaseWorkers, firstExited), ignored -> { });
        AntlrTaskManager.submit(pipeline,
                () -> awaitReleaseIgnoringInterrupt(secondEntered, secondCancellationObserved,
                        releaseWorkers, secondExited), ignored -> { });

        var owner = new Thread(() -> {
            try {
                pipeline.abort();
            } finally {
                ownerDone.countDown();
            }
        });

        try {
            assertAll(
                    () -> assertTrue(firstEntered.await(5, TimeUnit.SECONDS)),
                    () -> assertTrue(secondEntered.await(5, TimeUnit.SECONDS)));
            owner.start();

            assertAll(
                    () -> assertTrue(firstCancellationObserved.await(5, TimeUnit.SECONDS)),
                    () -> assertTrue(secondCancellationObserved.await(5, TimeUnit.SECONDS)));
            assertFalse(ownerDone.await(100, TimeUnit.MILLISECONDS));
            assertAll(
                    () -> assertFalse(firstExited.get()),
                    () -> assertFalse(secondExited.get()),
                    () -> assertEquals(2, pipeline.size()),
                    () -> assertEquals(2, pipeline.runningCount()),
                    () -> assertEquals(2, pipeline.registeredTaskCount()));

            releaseWorkers.countDown();
            assertTrue(ownerDone.await(5, TimeUnit.SECONDS));
            owner.join(TimeUnit.SECONDS.toMillis(5));

            assertAll(
                    () -> assertFalse(owner.isAlive()),
                    () -> assertTrue(firstExited.get()),
                    () -> assertTrue(secondExited.get()));
            assertDrained(pipeline);
        } finally {
            releaseWorkers.countDown();
            owner.interrupt();
            owner.join(TimeUnit.SECONDS.toMillis(5));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failureCleanupWaitsForEveryManagerWorkerExit() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var pipeline = new AntlrPipeline(2, executor);
        var laterWorkerEntered = new CountDownLatch(1);
        var laterCancellationObserved = new CountDownLatch(1);
        var releaseLaterWorker = new CountDownLatch(1);
        var laterWorkerExited = new AtomicBoolean();
        var expected = new IOException("controlled head failure");
        var ownerDone = new CountDownLatch(1);
        var ownerFailure = new AtomicReference<Throwable>();
        AntlrTaskManager.submit(pipeline, () -> {
            if (!laterWorkerEntered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for later parser worker");
            }
            throw expected;
        }, ignored -> { });
        AntlrTaskManager.submit(pipeline,
                () -> awaitReleaseIgnoringInterrupt(laterWorkerEntered,
                        laterCancellationObserved, releaseLaterWorker, laterWorkerExited),
                ignored -> { });

        var owner = new Thread(() -> {
            try {
                AntlrTaskManager.finish(pipeline);
            } catch (Throwable ex) {
                ownerFailure.set(ex);
            } finally {
                ownerDone.countDown();
            }
        });

        try {
            owner.start();
            assertTrue(laterCancellationObserved.await(5, TimeUnit.SECONDS));
            assertFalse(ownerDone.await(100, TimeUnit.MILLISECONDS));
            assertAll(
                    () -> assertFalse(laterWorkerExited.get()),
                    () -> assertEquals(2, pipeline.size()),
                    () -> assertEquals(2, pipeline.runningCount()),
                    () -> assertEquals(2, pipeline.registeredTaskCount()));

            releaseLaterWorker.countDown();
            assertTrue(ownerDone.await(5, TimeUnit.SECONDS));
            owner.join(TimeUnit.SECONDS.toMillis(5));

            assertAll(
                    () -> assertFalse(owner.isAlive()),
                    () -> assertSame(expected, ownerFailure.get()),
                    () -> assertTrue(laterWorkerExited.get()));
            assertDrained(pipeline);
        } finally {
            releaseLaterWorker.countDown();
            owner.interrupt();
            owner.join(TimeUnit.SECONDS.toMillis(5));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void managerTaskCancelledBeforeExecutorRunDoesNotWaitOrInvokeCallable() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(1, executor);
        var callableRuns = new AtomicInteger();
        AntlrTaskManager.submit(pipeline, () -> {
            callableRuns.incrementAndGet();
            return "unexpected";
        }, ignored -> { });

        assertTimeoutPreemptively(Duration.ofSeconds(1), pipeline::abort);
        assertDrained(pipeline);

        executor.run(0);
        assertEquals(0, callableRuns.get());
    }

    @Test
    void taskSubmittedDuringRequestAbortIsCancelledByPostRegistrationCheck() {
        var pipelineRef = new AtomicReference<AntlrPipeline>();
        var executor = new CallbackExecutor(() -> pipelineRef.get().requestAbort());
        var pipeline = new AntlrPipeline(1, executor);
        pipelineRef.set(pipeline);

        AntlrTaskManager.submit(pipeline, () -> "parsed", ignored -> { });

        assertAll(
                () -> assertEquals(1, pipeline.size()),
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(1, pipeline.registeredTaskCount()),
                () -> assertTrue(executor.future().isCancelled()));

        pipeline.abort();
        assertDrained(pipeline);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void cancelledPipelinePreCancelsNewManagerTaskWithoutExecutorSubmission(
            boolean allowEarlyFinish) {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(
                allowEarlyFinish ? 1 : Integer.MAX_VALUE,
                allowEarlyFinish ? 8 : 0, executor, allowEarlyFinish);
        var callableRuns = new AtomicInteger();
        var finalizerRuns = new AtomicInteger();
        pipeline.requestAbort();

        AntlrTaskManager.submit(pipeline, 7, () -> {
            callableRuns.incrementAndGet();
            return "parsed";
        }, ignored -> finalizerRuns.incrementAndGet());
        AntlrTask<?> task = pipeline.peek();

        assertAll(
                () -> assertEquals(0, executor.submittedCount()),
                () -> assertEquals(1, pipeline.size()),
                () -> assertEquals(0, pipeline.pendingCount()),
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(allowEarlyFinish ? 7 : 0, pipeline.runningWeight()),
                () -> assertEquals(1, pipeline.registeredTaskCount()),
                () -> assertTrue(task.isSubmitted()),
                () -> assertTrue(task.isDone()),
                () -> assertEquals(0, callableRuns.get()),
                () -> assertEquals(0, finalizerRuns.get()));

        assertThrows(InterruptedException.class, () -> AntlrTaskManager.finish(pipeline));

        assertAll(
                () -> assertEquals(0, executor.submittedCount()),
                () -> assertEquals(0, callableRuns.get()),
                () -> assertEquals(0, finalizerRuns.get()),
                () -> assertTrue(task.isCleared()));
        assertDrained(pipeline);
    }

    @Test
    void completedCancellationDropsManagerTaskAddedBehindFullWindow() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(1, executor);
        var callableRuns = new AtomicInteger();
        var finalizerRuns = new AtomicInteger();
        AntlrTaskManager.submit(pipeline, () -> "existing", ignored -> { });
        AntlrTask<?> existingTask = pipeline.peek();
        pipeline.requestAbort();

        AntlrTaskManager.submit(pipeline, () -> {
            callableRuns.incrementAndGet();
            return "discarded";
        }, ignored -> finalizerRuns.incrementAndGet());

        assertAll(
                () -> assertEquals(1, executor.submittedCount()),
                () -> assertEquals(1, pipeline.size()),
                () -> assertSame(existingTask, pipeline.peek()),
                () -> assertIterableEquals(List.of(existingTask), pipeline),
                () -> assertEquals(0, pipeline.pendingCount()),
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(1, pipeline.registeredTaskCount()),
                () -> assertEquals(0, callableRuns.get()),
                () -> assertEquals(0, finalizerRuns.get()));

        assertThrows(InterruptedException.class, () -> AntlrTaskManager.finish(pipeline));

        assertAll(
                () -> assertEquals(1, executor.submittedCount()),
                () -> assertEquals(0, callableRuns.get()),
                () -> assertEquals(0, finalizerRuns.get()));
        assertDrained(pipeline);
    }

    @Test
    void cancellationDuringCurrentFinalizerNeverSubmitsNextPendingTask() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(1, executor);
        var finalizerEntered = new CountDownLatch(1);
        var releaseFinalizer = new CountDownLatch(1);
        var nextCallableRuns = new AtomicInteger();
        var nextFinalizerRuns = new AtomicInteger();
        AntlrTaskManager.submit(pipeline, () -> "first", ignored -> {
            finalizerEntered.countDown();
            try {
                if (!releaseFinalizer.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release first finalizer");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        });
        AntlrTaskManager.submit(pipeline, () -> {
            nextCallableRuns.incrementAndGet();
            return "second";
        }, ignored -> nextFinalizerRuns.incrementAndGet());
        assertAll(
                () -> assertEquals(1, executor.submittedCount()),
                () -> assertEquals(1, pipeline.pendingCount()));
        executor.run(0);

        var thrown = new AtomicReference<Throwable>();
        var owner = new Thread(() -> {
            try {
                AntlrTaskManager.finish(pipeline);
            } catch (Throwable ex) {
                thrown.set(ex);
            }
        });

        try {
            owner.start();
            assertTrue(finalizerEntered.await(5, TimeUnit.SECONDS));
            assertTrue(pipeline.hasCurrentTask());

            pipeline.requestAbort();
            releaseFinalizer.countDown();
            owner.join(TimeUnit.SECONDS.toMillis(5));

            assertAll(
                    () -> assertFalse(owner.isAlive()),
                    () -> assertInstanceOf(InterruptedException.class, thrown.get()),
                    () -> assertEquals(1, executor.submittedCount()),
                    () -> assertEquals(0, nextCallableRuns.get()),
                    () -> assertEquals(0, nextFinalizerRuns.get()));
            assertDrained(pipeline);
        } finally {
            releaseFinalizer.countDown();
            owner.interrupt();
            owner.join(TimeUnit.SECONDS.toMillis(5));
            if (!owner.isAlive()) {
                pipeline.abort();
            }
        }
    }

    @Test
    void requestAfterFutureCompletionBeforeOwnerFinalizerSkipsFinalization() throws Exception {
        var pipeline = new AntlrPipeline(1, new ManualExecutor());
        var future = new BlockingFuture<>("parsed");
        var finalized = new ArrayList<String>();
        pipeline.add(new AntlrTask<>(future, finalized::add));
        var thrown = new AtomicReference<Throwable>();
        var owner = new Thread(() -> {
            try {
                AntlrTaskManager.finish(pipeline);
            } catch (Throwable ex) {
                thrown.set(ex);
            }
        });

        try {
            owner.start();
            assertTrue(future.awaitGet());
            pipeline.requestAbort();
            owner.join(TimeUnit.SECONDS.toMillis(5));

            assertAll(
                    () -> assertFalse(owner.isAlive()),
                    () -> assertInstanceOf(InterruptedException.class, thrown.get()),
                    () -> assertTrue(finalized.isEmpty()));
            assertDrained(pipeline);
        } finally {
            pipeline.abort();
            owner.interrupt();
            owner.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void directOfferRegistersAndPostChecksCancellation() {
        var pipeline = new AntlrPipeline(1, new ManualExecutor());
        var future = new FutureTask<>(() -> "parsed");
        pipeline.requestAbort();

        assertTrue(pipeline.offer(new AntlrTask<>(future, ignored -> { })));

        assertAll(
                () -> assertEquals(1, pipeline.size()),
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(1, pipeline.registeredTaskCount()),
                () -> assertTrue(future.isCancelled()));

        pipeline.abort();
        assertDrained(pipeline);
    }

    @Test
    void failedDirectOfferDoesNotMutateOrAbortExistingPipelineState() {
        var pipeline = new AntlrPipeline(2, new ManualExecutor());
        var existingFuture = new RecordingCancelFuture<>(() -> "existing");
        var existingTask = new AntlrTask<>(existingFuture, ignored -> { });
        var expected = new IllegalStateException("controlled cancel failure");
        var rejectedFuture = new FirstCancelThrowsFuture<>(() -> "rejected", expected);
        var rejectedTask = new AntlrTask<>(rejectedFuture, ignored -> { });
        pipeline.add(existingTask);
        pipeline.requestAbort();

        try {
            IllegalStateException actual = assertThrows(IllegalStateException.class,
                    () -> pipeline.offer(rejectedTask));

            assertSame(expected, actual);
            assertAll(
                    () -> assertEquals(1, pipeline.size()),
                    () -> assertSame(existingTask, pipeline.peek()),
                    () -> assertEquals(1, pipeline.runningCount()),
                    () -> assertEquals(0, pipeline.runningWeight()),
                    () -> assertEquals(1, pipeline.registeredTaskCount()),
                    () -> assertEquals(1, existingFuture.cancelCalls()),
                    () -> assertFalse(existingFuture.isCancelled()),
                    () -> assertTrue(rejectedTask.isSubmitted()));
        } finally {
            rejectedTask.cancelAndClear();
            pipeline.abort();
        }

        assertDrained(pipeline);
    }

    @Test
    void repeatedRequestAbortAndOwnerAbortAreIdempotent() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, executor);
        AntlrTaskManager.submit(pipeline, () -> 1, ignored -> { });
        AntlrTaskManager.submit(pipeline, () -> 2, ignored -> { });

        pipeline.requestAbort();
        pipeline.requestAbort();
        pipeline.abort();
        pipeline.abort();

        assertAll(
                () -> assertTrue(executor.future(0).isCancelled()),
                () -> assertTrue(executor.future(1).isCancelled()));
        assertDrained(pipeline);
    }

    @Test
    void eagerCompatibilityQueueCanBeCancelledCrossThread() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(Integer.MAX_VALUE, -1, executor, false);
        AntlrTaskManager.submit(pipeline, Long.MAX_VALUE, () -> 1, ignored -> { });
        AntlrTaskManager.submit(pipeline, 1, () -> 2, ignored -> { });

        var requester = new Thread(pipeline::requestAbort);
        requester.start();
        requester.join(TimeUnit.SECONDS.toMillis(5));

        assertAll(
                () -> assertFalse(requester.isAlive()),
                () -> assertEquals(2, pipeline.size()),
                () -> assertEquals(2, pipeline.runningCount()),
                () -> assertEquals(0, pipeline.runningWeight()),
                () -> assertEquals(2, pipeline.registeredTaskCount()),
                () -> assertTrue(executor.future(0).isCancelled()),
                () -> assertTrue(executor.future(1).isCancelled()));

        pipeline.abort();
        assertDrained(pipeline);
    }

    @Test
    void eagerSubmissionDoesNotRescanPreviouslySubmittedTasks() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(Integer.MAX_VALUE, 0, executor, false);
        var probe = new PendingProbeTask(new FutureTask<>(() -> "probe"));
        pipeline.add(probe);
        probe.resetPendingChecks();
        int taskCount = 1_000;

        for (int i = 0; i < taskCount; i++) {
            AntlrTaskManager.submit(pipeline, i, () -> "parsed", ignored -> { });
        }

        assertAll(
                () -> assertEquals(taskCount, executor.submittedCount()),
                () -> assertEquals(0, probe.pendingChecks()),
                () -> assertEquals(taskCount + 1, pipeline.runningCount()),
                () -> assertEquals(taskCount + 1, pipeline.registeredTaskCount()),
                () -> assertEquals(0, pipeline.runningWeight()));
        pipeline.abort();
        assertDrained(pipeline);
    }

    @Test
    void eagerCompatibilityTryFinishNextRemainsNoOp() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(Integer.MAX_VALUE, 0, executor, false);
        var finalized = new ArrayList<String>();
        AntlrTaskManager.submit(pipeline, 42, () -> "parsed", finalized::add);
        executor.run(0);

        assertFalse(AntlrTaskManager.tryFinishNext(pipeline));
        assertTrue(finalized.isEmpty());

        AntlrTaskManager.finish(pipeline);
        assertEquals(List.of("parsed"), finalized);
        assertDrained(pipeline);
    }

    @Test
    void explicitArrayDequeRequestAbortRemainsNoOp() {
        var future = new FutureTask<>(() -> "parsed");
        var queue = new ArrayDeque<AntlrTask<?>>();
        var task = new AntlrTask<>(future, ignored -> { });
        queue.add(task);

        AntlrTaskManager.requestAbort(queue);

        assertAll(
                () -> assertSame(task, queue.peek()),
                () -> assertFalse(future.isCancelled()));
        AntlrTaskManager.abort(queue);
    }

    @Test
    void completedTasksDoNotAccumulateInCancellationRegistry() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(7, executor);
        int taskCount = 1_000;
        for (int i = 0; i < taskCount; i++) {
            AntlrTaskManager.submit(pipeline, () -> "parsed", ignored -> { });
        }

        drain(pipeline, executor);

        assertEquals(0, pipeline.registeredTaskCount());
        assertDrained(pipeline);
    }

    @Test
    void executorRejectionLeavesNoQueueRegistryOrAccountingState() {
        var pipeline = new AntlrPipeline(1, new RejectingExecutor());

        assertThrows(RejectedExecutionException.class,
                () -> AntlrTaskManager.submit(pipeline, 7, () -> "parsed", ignored -> { }));

        assertDrained(pipeline);
    }

    @Test
    void eagerExecutorRejectionLeavesNoQueueRegistryOrAccountingState() {
        var pipeline = new AntlrPipeline(Integer.MAX_VALUE, 0, new RejectingExecutor(), false);

        assertThrows(RejectedExecutionException.class,
                () -> AntlrTaskManager.submit(pipeline, Long.MAX_VALUE,
                        () -> "parsed", ignored -> { }));

        assertDrained(pipeline);
    }

    @Test
    void countAndByteBudgetsPreserveFifoAndAllowOneOversizedTask() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(3, 10, executor);
        var finalized = new ArrayList<String>();

        AntlrTaskManager.submit(pipeline, 6, () -> "first", finalized::add);
        AntlrTaskManager.submit(pipeline, 6, () -> "second", finalized::add);
        AntlrTaskManager.submit(pipeline, 20, () -> "oversized", finalized::add);

        assertAll(
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(6, pipeline.runningWeight()),
                () -> assertEquals(2, pipeline.pendingCount()));

        executor.run(0);
        assertTrue(pipeline.finishNext());
        assertAll(
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(6, pipeline.runningWeight()));

        executor.run(1);
        assertTrue(pipeline.finishNext());
        assertAll(
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(20, pipeline.runningWeight()));

        executor.run(2);
        assertTrue(pipeline.finishNext());
        assertIterableEquals(List.of("first", "second", "oversized"), finalized);
        assertDrained(pipeline);
    }

    @Test
    void blockedWeightedHeadIsNeverSkipped() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(3, 10, executor);

        AntlrTaskManager.submit(pipeline, 6, () -> 1, ignored -> { });
        AntlrTaskManager.submit(pipeline, 6, () -> 2, ignored -> { });
        AntlrTaskManager.submit(pipeline, 4, () -> 3, ignored -> { });

        assertAll(
                () -> assertEquals(1, executor.submittedCount()),
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(6, pipeline.runningWeight()),
                () -> assertEquals(2, pipeline.pendingCount()));

        executor.run(0);
        assertTrue(pipeline.finishNext());
        assertAll(
                () -> assertEquals(3, executor.submittedCount()),
                () -> assertEquals(2, pipeline.runningCount()),
                () -> assertEquals(10, pipeline.runningWeight()));

        executor.run(1);
        assertTrue(pipeline.finishNext());
        executor.run(2);
        assertTrue(pipeline.finishNext());
        assertDrained(pipeline);
    }

    @Test
    void weightedNestedSubmissionSharesParentBudget() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, 10, executor);

        AntlrTaskManager.submit(pipeline, 6, () -> "parent", ignored -> {
            AntlrTaskManager.submit(pipeline, 6, () -> "child", child -> { });
            assertAll(
                    () -> assertEquals(1, executor.submittedCount()),
                    () -> assertEquals(1, pipeline.runningCount()),
                    () -> assertEquals(6, pipeline.runningWeight()),
                    () -> assertEquals(1, pipeline.pendingCount()));
        });

        executor.run(0);
        assertTrue(pipeline.finishNext());
        assertAll(
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(6, pipeline.runningWeight()),
                () -> assertEquals(2, executor.submittedCount()));

        executor.run(1);
        assertTrue(pipeline.finishNext());
        assertDrained(pipeline);
    }

    @Test
    void oversizedHeadRunsAloneBeforeFollowingTask() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, 10, executor);

        AntlrTaskManager.submit(pipeline, 20, () -> "oversized", ignored -> { });
        AntlrTaskManager.submit(pipeline, 1, () -> "following", ignored -> { });

        assertAll(
                () -> assertEquals(1, executor.submittedCount()),
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(20, pipeline.runningWeight()),
                () -> assertEquals(1, pipeline.pendingCount()));

        executor.run(0);
        assertTrue(pipeline.finishNext());
        assertAll(
                () -> assertEquals(2, executor.submittedCount()),
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(1, pipeline.runningWeight()));

        executor.run(1);
        assertTrue(pipeline.finishNext());
        assertDrained(pipeline);
    }

    @Test
    void zeroByteBudgetIsUnlimited() {
        var pipeline = new AntlrPipeline(2, 0, new ManualExecutor());

        AntlrTaskManager.submit(pipeline, 100, () -> 1, ignored -> { });
        AntlrTaskManager.submit(pipeline, 200, () -> 2, ignored -> { });

        assertAll(
                () -> assertEquals(2, pipeline.runningCount()),
                () -> assertEquals(300, pipeline.runningWeight()));
    }

    @Test
    void byteAdmissionDoesNotOverflowLong() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, Long.MAX_VALUE, executor);

        AntlrTaskManager.submit(pipeline, Long.MAX_VALUE, () -> 1, ignored -> { });
        AntlrTaskManager.submit(pipeline, 1, () -> 2, ignored -> { });

        assertAll(
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(Long.MAX_VALUE, pipeline.runningWeight()),
                () -> assertEquals(1, pipeline.pendingCount()));

        executor.run(0);
        assertTrue(pipeline.finishNext());
        assertEquals(1, pipeline.runningWeight());
        executor.run(1);
        assertTrue(pipeline.finishNext());
        assertDrained(pipeline);
    }

    @Test
    void negativeTaskWeightsAreRejected() {
        var pipeline = new AntlrPipeline(1, 10, new ManualExecutor());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> pipeline.submit(-1, () -> 1, ignored -> { })),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AntlrTaskManager.submit(pipeline, -1, () -> 1, ignored -> { })),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AntlrTaskManager.submit(new ArrayDeque<>(), -1,
                                () -> 1, ignored -> { })));
        assertDrained(pipeline);
    }

    @Test
    void negativeByteBudgetIsRejected() {
        var executor = new ManualExecutor();

        assertThrows(IllegalArgumentException.class,
                () -> new AntlrPipeline(1, -1, executor));
    }

    @Test
    void abortResetsRunningWeight() {
        var pipeline = new AntlrPipeline(2, 10, new ManualExecutor());
        AntlrTaskManager.submit(pipeline, 4, () -> 1, ignored -> { });
        AntlrTaskManager.submit(pipeline, 5, () -> 2, ignored -> { });

        AntlrTaskManager.abort(pipeline);

        assertDrained(pipeline);
    }

    @Test
    void pollAndIteratorRemovalReleaseWeight() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, 10, executor);
        AntlrTaskManager.submit(pipeline, 4, () -> 1, ignored -> { });
        AntlrTaskManager.submit(pipeline, 5, () -> 2, ignored -> { });

        pipeline.poll();
        assertAll(
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(5, pipeline.runningWeight()),
                () -> assertFalse(executor.future(0).isCancelled()));

        var iterator = pipeline.iterator();
        iterator.next();
        iterator.remove();
        assertTrue(executor.future(1).isCancelled());
        assertDrained(pipeline);
    }

    @Test
    void offeringPolledWeightedTaskRestoresRunningWeight() throws Exception {
        var executor = new ManualExecutor();
        var source = new AntlrPipeline(1, 10, executor);
        var target = new AntlrPipeline(1, 10, executor);
        AntlrTaskManager.submit(source, 7, () -> "parsed", ignored -> { });

        AntlrTask<?> transferred = source.poll();
        assertDrained(source);

        assertTrue(target.offer(transferred));
        assertAll(
                () -> assertEquals(1, target.runningCount()),
                () -> assertEquals(7, target.runningWeight()));

        executor.run(0);
        assertTrue(target.finishNext());
        assertDrained(target);
    }

    @Test
    void offeringWeightedTaskRejectsRunningWeightOverflowBeforeTransfer() {
        var executor = new ManualExecutor();
        var target = new AntlrPipeline(2, 0, executor);
        var source = new AntlrPipeline(1, 0, executor);
        AntlrTaskManager.submit(target, 1, () -> "existing", ignored -> { });
        AntlrTask<?> existing = target.peek();
        AntlrTaskManager.submit(source, Long.MAX_VALUE, () -> "transferred", ignored -> { });
        AntlrTask<?> transferred = source.poll();

        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> target.offer(transferred));

            assertAll(
                    () -> assertEquals("ANTLR running task weight overflow", failure.getMessage()),
                    () -> assertEquals(1, target.size()),
                    () -> assertSame(existing, target.peek()),
                    () -> assertEquals(1, target.runningCount()),
                    () -> assertEquals(1, target.runningWeight()),
                    () -> assertEquals(1, target.registeredTaskCount()),
                    () -> assertEquals(0, source.registeredTaskCount()),
                    () -> assertTrue(transferred.isSubmitted()),
                    () -> assertFalse(executor.future(1).isCancelled()));
        } finally {
            transferred.cancelAndClear();
            target.abort();
        }

        assertAll(
                () -> assertTrue(executor.future(0).isCancelled()),
                () -> assertTrue(executor.future(1).isCancelled()),
                () -> assertDrained(target),
                () -> assertDrained(source));
    }

    @Test
    void clearReleasesRunningWeight() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, 10, executor);
        AntlrTaskManager.submit(pipeline, 4, () -> 1, ignored -> { });
        AntlrTaskManager.submit(pipeline, 5, () -> 2, ignored -> { });

        pipeline.clear();

        assertAll(
                () -> assertTrue(executor.future(0).isCancelled()),
                () -> assertTrue(executor.future(1).isCancelled()));
        assertDrained(pipeline);
    }

    @Test
    void failureReleasesRunningAndPendingWeight() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, 10, executor);
        AntlrTaskManager.submit(pipeline, 4, () -> {
            throw new IOException("weighted failure");
        }, ignored -> { });
        AntlrTaskManager.submit(pipeline, 5, () -> 2, ignored -> { });
        AntlrTaskManager.submit(pipeline, 6, () -> 3, ignored -> { });
        assertAll(
                () -> assertEquals(2, executor.submittedCount()),
                () -> assertEquals(1, pipeline.pendingCount()),
                () -> assertEquals(9, pipeline.runningWeight()));
        executor.run(0);

        assertThrows(IOException.class, () -> AntlrTaskManager.finish(pipeline));

        assertDrained(pipeline);
    }

    @Test
    void compatibilityConstructorAndSubmissionApisUseZeroWeight() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, executor);
        AntlrTaskManager.submit(pipeline, () -> 1, ignored -> { });
        pipeline.submit(() -> 2, ignored -> { });

        assertAll(
                () -> assertEquals(2, pipeline.runningCount()),
                () -> assertEquals(0, pipeline.runningWeight()));
    }

    @Test
    void directlyAddedFutureUsesZeroWeight() {
        var pipeline = new AntlrPipeline(1, 1, new ManualExecutor());

        pipeline.add(new AntlrTask<>(new FutureTask<>(() -> 1), ignored -> { }));

        assertAll(
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(0, pipeline.runningWeight()));
    }

    @Test
    void weightedManagerSubmissionToUnboundedQueueIgnoresWeight() throws Exception {
        var queue = new ArrayDeque<AntlrTask<?>>();
        var finalized = new ArrayList<Integer>();

        AntlrTaskManager.submit(queue, 42, () -> 1, finalized::add);

        assertEquals(0, queue.peek().weight());
        AntlrTaskManager.finish(queue);
        assertEquals(List.of(1), finalized);
    }

    @Test
    void submitsOnlyWindowBeforeDrain() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, executor);

        for (int i = 0; i < 5; i++) {
            int result = i;
            AntlrTaskManager.submit(pipeline, () -> result, ignored -> { });
        }

        assertAll(
                () -> assertEquals(2, executor.submittedCount()),
                () -> assertEquals(2, pipeline.runningCount()),
                () -> assertEquals(3, pipeline.pendingCount()),
                () -> assertEquals(5, pipeline.size()));

        drain(pipeline, executor);
    }

    @Test
    void finalizesInSubmissionOrderWhenCallablesCompleteInReverseOrder() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(3, executor);
        var finalized = new ArrayList<String>();

        for (int i = 1; i <= 3; i++) {
            String result = "R" + i;
            AntlrTaskManager.submit(pipeline, () -> result, finalized::add);
        }

        executor.run(2);
        executor.run(1);
        executor.run(0);
        AntlrTaskManager.finish(pipeline);

        assertIterableEquals(List.of("R1", "R2", "R3"), finalized);
        assertDrained(pipeline);
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 4 })
    void neverExceedsConfiguredRunningWindow(int window) throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(window, executor);
        int taskCount = window * 3 + 2;

        for (int i = 0; i < taskCount; i++) {
            int result = i;
            AntlrTaskManager.submit(pipeline, () -> result, ignored -> { });
            assertTrue(pipeline.runningCount() <= window);
        }

        int nextFuture = 0;
        while (!pipeline.isEmpty()) {
            assertTrue(pipeline.runningCount() <= window);
            executor.run(nextFuture++);
            assertTrue(pipeline.finishNext());
            assertTrue(pipeline.runningCount() <= window);
        }

        assertEquals(taskCount, executor.submittedCount());
        assertDrained(pipeline);
    }

    @Test
    void directAddOccupiesWindowUntilItIsFinalized() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(1, executor);
        var finalized = new ArrayList<String>();
        var externalFuture = new FutureTask<>(() -> "external");
        var externalTask = new AntlrTask<>(externalFuture, finalized::add);

        pipeline.add(externalTask);
        AntlrTaskManager.submit(pipeline, () -> "internal", finalized::add);

        assertAll(
                () -> assertEquals(0, executor.submittedCount()),
                () -> assertEquals(1, pipeline.runningCount()),
                () -> assertEquals(1, pipeline.pendingCount()),
                () -> assertEquals(2, pipeline.size()));

        externalFuture.run();
        assertTrue(pipeline.finishNext());
        assertEquals(1, executor.submittedCount());

        executor.run(0);
        assertTrue(pipeline.finishNext());

        assertIterableEquals(List.of("external", "internal"), finalized);
        assertTrue(externalTask.isCleared());
        assertDrained(pipeline);
    }

    @Test
    void queueViewExposesLogicalFifoState() {
        var pipeline = new AntlrPipeline(1, new ManualExecutor());
        var first = new AntlrTask<>(new FutureTask<>(() -> "first"), ignored -> { });
        var second = new AntlrTask<>(new FutureTask<>(() -> "second"), ignored -> { });

        assertTrue(pipeline.add(first));
        assertTrue(pipeline.offer(second));

        assertAll(
                () -> assertEquals(2, pipeline.size()),
                () -> assertEquals(2, pipeline.runningCount()),
                () -> assertFalse(pipeline.isEmpty()),
                () -> assertSame(first, pipeline.peek()),
                () -> assertIterableEquals(List.of(first, second), pipeline));

        assertSame(first, pipeline.poll());
        assertSame(second, pipeline.peek());
        assertSame(second, pipeline.poll());
        assertNull(pipeline.peek());
        assertNull(pipeline.poll());
        assertDrained(pipeline);
    }

    @Test
    void successfulFinishReleasesPhaseState() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, executor);

        for (int i = 0; i < 7; i++) {
            int result = i;
            AntlrTaskManager.submit(pipeline, () -> result, ignored -> { });
        }

        drain(pipeline, executor);

        assertDrained(pipeline);
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 4 })
    void finalizesNestedTasksBreadthFirst(int window) throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(window, executor);
        var finalized = new ArrayList<String>();

        AntlrTaskManager.submit(pipeline, () -> "R1", result -> {
            finalized.add(result);
            AntlrTaskManager.submit(pipeline, () -> "C11", child -> {
                finalized.add(child);
                AntlrTaskManager.submit(pipeline, () -> "G111", finalized::add);
            });
            AntlrTaskManager.submit(pipeline, () -> "C12", finalized::add);
        });
        AntlrTaskManager.submit(pipeline, () -> "R2", result -> {
            finalized.add(result);
            AntlrTaskManager.submit(pipeline, () -> "C21", finalized::add);
        });
        AntlrTaskManager.submit(pipeline, () -> "R3", finalized::add);

        drain(pipeline, executor);

        assertIterableEquals(List.of("R1", "R2", "R3", "C11", "C12", "C21", "G111"), finalized);
    }

    @Test
    void canBeReusedAfterSuccessfulFinish() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(1, executor);
        var finalized = new ArrayList<String>();

        AntlrTaskManager.submit(pipeline, () -> "A", finalized::add);
        executor.run(0);
        AntlrTaskManager.finish(pipeline);
        assertDrained(pipeline);

        AntlrTaskManager.submit(pipeline, () -> "B", finalized::add);
        executor.run(1);
        AntlrTaskManager.finish(pipeline);

        assertIterableEquals(List.of("A", "B"), finalized);
        assertDrained(pipeline);
    }

    @Test
    void nestedTasksShareTheSinglePipelineWindow() throws Exception {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, executor);

        AntlrTaskManager.submit(pipeline, () -> "R1", ignored -> {
            for (int i = 0; i < 6; i++) {
                int result = i;
                AntlrTaskManager.submit(pipeline, () -> result, child -> { });
                assertTrue(pipeline.runningCount() <= 2);
            }
        });
        AntlrTaskManager.submit(pipeline, () -> "R2", ignored -> { });

        int nextFuture = 0;
        while (!pipeline.isEmpty()) {
            assertTrue(pipeline.runningCount() <= 2);
            executor.run(nextFuture++);
            assertTrue(pipeline.finishNext());
            assertTrue(pipeline.runningCount() <= 2);
        }

        assertEquals(8, executor.submittedCount());
        assertDrained(pipeline);
    }

    @Test
    void mapsCallableIOExceptionAndReleasesPipeline() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(1, executor);
        var expected = new IOException("parse failed");
        AntlrTaskManager.submit(pipeline, () -> {
            throw expected;
        }, ignored -> { });
        executor.run(0);

        IOException actual = assertThrows(IOException.class, () -> AntlrTaskManager.finish(pipeline));

        assertSame(expected, actual);
        assertDrained(pipeline);
    }

    @Test
    void mapsCallableInterruptionWithoutInterruptingCaller() {
        Thread.interrupted();
        try {
            var executor = new ManualExecutor();
            var pipeline = new AntlrPipeline(1, executor);
            var expected = new InterruptedException("worker interrupted");
            AntlrTaskManager.submit(pipeline, () -> {
                throw expected;
            }, ignored -> { });
            executor.run(0);

            InterruptedException actual = assertThrows(InterruptedException.class,
                    () -> AntlrTaskManager.finish(pipeline));

            assertSame(expected, actual);
            assertFalse(Thread.currentThread().isInterrupted());
            assertDrained(pipeline);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wrapsArbitraryCallableFailureAndReleasesPipeline() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(1, executor);
        var expected = new IllegalArgumentException("bad parser state");
        AntlrTaskManager.submit(pipeline, () -> {
            throw expected;
        }, ignored -> { });
        executor.run(0);

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> AntlrTaskManager.finish(pipeline));

        assertSame(expected, actual.getCause().getCause());
        assertDrained(pipeline);
    }

    @Test
    void preservesUnclassifiedFinalizerCancellationAndReleasesPipeline() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(1, executor);
        var expected = new MonitorCancelledRuntimeException();
        AntlrTaskManager.submit(pipeline, () -> "parsed", ignored -> {
            throw expected;
        });
        executor.run(0);

        MonitorCancelledRuntimeException actual = assertThrows(
                MonitorCancelledRuntimeException.class,
                () -> AntlrTaskManager.finish(pipeline));

        assertSame(expected, actual);
        assertFalse(Thread.currentThread().isInterrupted());
        assertDrained(pipeline);
    }

    @Test
    void taskCancellationPreservesIdentityAndAbortFailureAsSuppressed() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, executor);
        var cancellation = new MonitorCancelledRuntimeException();
        var cleanupFailure = new IllegalStateException("controlled abort failure");
        AntlrTaskManager.submit(pipeline, () -> {
            throw cancellation;
        }, ignored -> { });
        pipeline.add(new AntlrTask<>(new FirstCancelThrowsFuture<>(
                () -> "pending", cleanupFailure), ignored -> { }));
        executor.run(0);

        MonitorCancelledRuntimeException thrown = assertThrows(
                MonitorCancelledRuntimeException.class,
                () -> AntlrTaskManager.finish(pipeline));

        assertAll(
                () -> assertSame(cancellation, thrown),
                () -> assertArrayEquals(
                        new Throwable[] { cleanupFailure }, thrown.getSuppressed()));
        assertDrained(pipeline);
    }

    @Test
    void taskInterruptionPreservesIdentityAndAbortFailureAsSuppressed() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, executor);
        var interruption = new InterruptedException("controlled interruption");
        var cleanupFailure = new IllegalStateException("controlled abort failure");
        AntlrTaskManager.submit(pipeline, () -> {
            throw interruption;
        }, ignored -> { });
        pipeline.add(new AntlrTask<>(new FirstCancelThrowsFuture<>(
                () -> "pending", cleanupFailure), ignored -> { }));
        executor.run(0);

        InterruptedException thrown = assertThrows(InterruptedException.class,
                () -> AntlrTaskManager.finish(pipeline));

        assertAll(
                () -> assertSame(interruption, thrown),
                () -> assertArrayEquals(
                        new Throwable[] { cleanupFailure }, thrown.getSuppressed()));
        assertDrained(pipeline);
    }

    @Test
    void taskIoFailurePreservesIdentityAndAbortFailureAsSuppressed() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, executor);
        var ioFailure = new IOException("controlled I/O failure");
        var cleanupFailure = new IllegalStateException("controlled abort failure");
        AntlrTaskManager.submit(pipeline, () -> {
            throw ioFailure;
        }, ignored -> { });
        pipeline.add(new AntlrTask<>(new FirstCancelThrowsFuture<>(
                () -> "pending", cleanupFailure), ignored -> { }));
        executor.run(0);

        IOException thrown = assertThrows(IOException.class,
                () -> AntlrTaskManager.finish(pipeline));

        assertAll(
                () -> assertSame(ioFailure, thrown),
                () -> assertArrayEquals(
                        new Throwable[] { cleanupFailure }, thrown.getSuppressed()));
        assertDrained(pipeline);
    }

    @Test
    void fatalHeadCancelsRunningTasksAndSkipsLaterFinalizers() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(3, executor);
        var finalized = new ArrayList<String>();
        AntlrTaskManager.submit(pipeline, () -> {
            throw new IOException("fatal");
        }, ignored -> finalized.add("failed"));
        AntlrTaskManager.submit(pipeline, () -> "R2", finalized::add);
        AntlrTaskManager.submit(pipeline, () -> "R3", finalized::add);
        executor.run(0);

        assertThrows(IOException.class, () -> AntlrTaskManager.finish(pipeline));

        assertTrue(finalized.isEmpty());
        assertTrue(executor.future(1).isCancelled());
        assertTrue(executor.future(2).isCancelled());
        assertDrained(pipeline);
    }

    @Test
    void fatalHeadSkipsAlreadyCompletedLaterFinalizers() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(3, executor);
        var finalized = new ArrayList<String>();
        AntlrTaskManager.submit(pipeline, () -> {
            throw new IOException("fatal");
        }, ignored -> finalized.add("failed"));
        AntlrTaskManager.submit(pipeline, () -> "R2", finalized::add);
        AntlrTaskManager.submit(pipeline, () -> "R3", finalized::add);
        executor.run(2);
        executor.run(1);
        executor.run(0);

        assertThrows(IOException.class, () -> AntlrTaskManager.finish(pipeline));

        assertTrue(finalized.isEmpty());
        assertDrained(pipeline);
    }

    @Test
    void fatalTaskReleasesDescriptorsThatWereNeverSubmitted() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, executor);
        AntlrTaskManager.submit(pipeline, () -> {
            throw new IOException("fatal");
        }, ignored -> { });
        for (int i = 0; i < 5; i++) {
            int result = i;
            AntlrTaskManager.submit(pipeline, () -> result, ignored -> { });
        }
        executor.run(0);

        assertThrows(IOException.class, () -> AntlrTaskManager.finish(pipeline));

        assertEquals(2, executor.submittedCount());
        assertTrue(executor.future(1).isCancelled());
        assertDrained(pipeline);
    }

    @Test
    void abortBeforeFinishCancelsAndReleasesOwnedTasks() {
        var executor = new ManualExecutor();
        var pipeline = new AntlrPipeline(2, executor);
        for (int i = 0; i < 5; i++) {
            int result = i;
            AntlrTaskManager.submit(pipeline, () -> result, ignored -> { });
        }

        AntlrTaskManager.abort(pipeline);
        AntlrTaskManager.abort(pipeline);

        assertEquals(2, executor.submittedCount());
        assertTrue(executor.future(0).isCancelled());
        assertTrue(executor.future(1).isCancelled());
        assertDrained(pipeline);
    }

    @Test
    void abortCancelsTasksInUnboundedPlainQueue() {
        var first = new FutureTask<>(() -> "first");
        var second = new FutureTask<>(() -> "second");
        var queue = new ArrayDeque<AntlrTask<?>>();
        queue.add(new AntlrTask<>(first, ignored -> { }));
        queue.add(new AntlrTask<>(second, ignored -> { }));

        AntlrTaskManager.abort(queue);

        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
        assertTrue(queue.isEmpty());
    }

    @Test
    void pipelineAbortDoesNotSelfSuppressRepeatedCancellationFailure() {
        RuntimeException failure = new IllegalStateException("controlled cancellation failure");
        var pipeline = new AntlrPipeline(2, new ManualExecutor());
        pipeline.add(new AntlrTask<>(new FirstCancelThrowsFuture<>(() -> "first", failure), ignored -> { }));
        pipeline.add(new AntlrTask<>(new FirstCancelThrowsFuture<>(() -> "second", failure), ignored -> { }));

        RuntimeException thrown = assertThrows(RuntimeException.class, pipeline::abort);

        assertSame(failure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertDrained(pipeline);
    }

    @Test
    void unboundedQueueAbortDoesNotSelfSuppressRepeatedCancellationFailure() {
        RuntimeException failure = new IllegalStateException("controlled cancellation failure");
        var queue = new ArrayDeque<AntlrTask<?>>();
        queue.add(new AntlrTask<>(new FirstCancelThrowsFuture<>(() -> "first", failure), ignored -> { }));
        queue.add(new AntlrTask<>(new FirstCancelThrowsFuture<>(() -> "second", failure), ignored -> { }));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> AntlrTaskManager.abort(queue));

        assertSame(failure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertTrue(queue.isEmpty());
    }

    @Test
    void pipelineAbortPreservesFirstErrorAndStillCancelsLaterTasks() {
        AssertionError primary = new AssertionError("controlled primary cancellation failure");
        RuntimeException secondary = new IllegalStateException("controlled secondary cancellation failure");
        var first = new FirstCancelThrowsFuture<>(() -> "first", primary);
        var second = new FirstCancelThrowsFuture<>(() -> "second", secondary);
        var third = new RecordingCancelFuture<>(() -> "third");
        var pipeline = new AntlrPipeline(3, new ManualExecutor());
        pipeline.add(new AntlrTask<>(first, ignored -> { }));
        pipeline.add(new AntlrTask<>(second, ignored -> { }));
        pipeline.add(new AntlrTask<>(third, ignored -> { }));

        AssertionError thrown = assertThrows(AssertionError.class, pipeline::abort);

        assertSame(primary, thrown);
        assertAll(
                () -> assertEquals(1, thrown.getSuppressed().length),
                () -> assertSame(secondary, thrown.getSuppressed()[0]),
                () -> assertEquals(1, first.cancelCalls()),
                () -> assertEquals(1, second.cancelCalls()),
                () -> assertEquals(1, third.cancelCalls()));
        assertDrained(pipeline);
    }

    @Test
    void unboundedQueueAbortPreservesFirstErrorAndStillCancelsLaterTasks() {
        AssertionError primary = new AssertionError("controlled primary cancellation failure");
        RuntimeException secondary = new IllegalStateException("controlled secondary cancellation failure");
        var first = new FirstCancelThrowsFuture<>(() -> "first", primary);
        var second = new FirstCancelThrowsFuture<>(() -> "second", secondary);
        var third = new RecordingCancelFuture<>(() -> "third");
        var queue = new ArrayDeque<AntlrTask<?>>();
        queue.add(new AntlrTask<>(first, ignored -> { }));
        queue.add(new AntlrTask<>(second, ignored -> { }));
        queue.add(new AntlrTask<>(third, ignored -> { }));

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> AntlrTaskManager.abort(queue));

        assertSame(primary, thrown);
        assertAll(
                () -> assertEquals(1, thrown.getSuppressed().length),
                () -> assertSame(secondary, thrown.getSuppressed()[0]),
                () -> assertEquals(1, first.cancelCalls()),
                () -> assertEquals(1, second.cancelCalls()),
                () -> assertEquals(1, third.cancelCalls()),
                () -> assertTrue(queue.isEmpty()));
    }

    @Test
    void abortAfterFailureSkipsSelfSuppressionAndStillCancelsRemainingTasks() {
        RuntimeException failure = new IllegalStateException("controlled shared failure");
        var current = new FirstCancelThrowsFuture<>(() -> "current", failure);
        var sameFailure = new FirstCancelThrowsFuture<>(() -> "same", failure);
        var remaining = new RecordingCancelFuture<>(() -> "remaining");
        current.run();
        var queue = new ArrayDeque<AntlrTask<?>>();
        queue.add(new AntlrTask<>(current, ignored -> {
            throw failure;
        }));
        queue.add(new AntlrTask<>(sameFailure, ignored -> { }));
        queue.add(new AntlrTask<>(remaining, ignored -> { }));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> AntlrTaskManager.finish(queue));

        assertSame(failure, thrown);
        assertAll(
                () -> assertEquals(0, thrown.getSuppressed().length),
                () -> assertEquals(1, current.cancelCalls()),
                () -> assertEquals(1, sameFailure.cancelCalls()),
                () -> assertEquals(1, remaining.cancelCalls()),
                () -> assertTrue(queue.isEmpty()));
    }

    @Test
    void unboundedQueueFailureCancelsCurrentAndRemainingTasks() {
        var expected = new IOException("queue failure");
        var first = new FutureTask<String>(() -> {
            throw expected;
        });
        var second = new FutureTask<>(() -> "second");
        var queue = new ArrayDeque<AntlrTask<?>>();
        queue.add(new AntlrTask<>(first, ignored -> { }));
        queue.add(new AntlrTask<>(second, ignored -> { }));
        first.run();

        IOException actual = assertThrows(IOException.class, () -> AntlrTaskManager.finish(queue));

        assertSame(expected, actual);
        assertTrue(second.isCancelled());
        assertTrue(queue.isEmpty());
    }

    @Test
    void abortingOnePipelineDoesNotAffectAnother() throws Exception {
        var executor = new ManualExecutor();
        var first = new AntlrPipeline(1, executor);
        var second = new AntlrPipeline(1, executor);
        var finalized = new ArrayList<String>();
        AntlrTaskManager.submit(first, () -> "first", finalized::add);
        AntlrTaskManager.submit(second, () -> "second", finalized::add);

        AntlrTaskManager.abort(first);
        executor.run(1);
        AntlrTaskManager.finish(second);

        assertTrue(executor.future(0).isCancelled());
        assertFalse(executor.future(1).isCancelled());
        assertIterableEquals(List.of("second"), finalized);
        assertDrained(first);
        assertDrained(second);
    }

    @Test
    void interruptingCallerWaitingOnFuturePreservesInterruptFlag() throws Exception {
        var pipeline = new AntlrPipeline(1, new ManualExecutor());
        var future = new BlockingFuture<>("never returned");
        pipeline.add(new AntlrTask<>(future, ignored -> { }));
        var thrown = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        var waiter = new Thread(() -> {
            try {
                AntlrTaskManager.finish(pipeline);
            } catch (Throwable ex) {
                thrown.set(ex);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        try {
            waiter.start();
            assertTrue(future.awaitGet());
            waiter.interrupt();
            waiter.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(waiter.isAlive());
            assertInstanceOf(InterruptedException.class, thrown.get());
            assertTrue(interrupted.get());
            assertDrained(pipeline);
        } finally {
            waiter.interrupt();
            waiter.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void absentPropertyCreatesDefaultBoundedPipeline() {
        System.clearProperty(Consts.MAX_PENDING_TASKS);
        System.clearProperty(Consts.MAX_PENDING_BYTES);

        var pipeline = assertInstanceOf(AntlrPipeline.class, AntlrTaskManager.createTaskQueue());
        int expected = (int) Math.min(Integer.MAX_VALUE, (long) AntlrTaskManager.getPoolSize() * 2);

        assertAll(
                () -> assertEquals(expected, pipeline.maxPending()),
                () -> assertEquals(0, pipeline.maxPendingBytes()));
    }

    @Test
    void positivePropertyCreatesPipelineWithRequestedWindow() {
        System.setProperty(Consts.MAX_PENDING_TASKS, "1");
        System.setProperty(Consts.MAX_PENDING_BYTES, "123");

        var pipeline = assertInstanceOf(AntlrPipeline.class, AntlrTaskManager.createTaskQueue());

        assertAll(
                () -> assertEquals(1, pipeline.maxPending()),
                () -> assertEquals(123, pipeline.maxPendingBytes()));
    }

    @Test
    void negativeByteBudgetPropertyIsRejectedForBoundedPipeline() {
        System.setProperty(Consts.MAX_PENDING_TASKS, "1");
        System.setProperty(Consts.MAX_PENDING_BYTES, "-1");

        assertThrows(IllegalArgumentException.class, AntlrTaskManager::createTaskQueue);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1 })
    void nonPositivePropertyCreatesEagerCompatibilityPipeline(int configuredWindow) {
        System.setProperty(Consts.MAX_PENDING_TASKS, Integer.toString(configuredWindow));
        System.setProperty(Consts.MAX_PENDING_BYTES, "-1");

        var queue = AntlrTaskManager.createTaskQueue();

        var pipeline = assertInstanceOf(AntlrPipeline.class, queue);
        assertAll(
                () -> assertEquals(Integer.MAX_VALUE, pipeline.maxPending()),
                () -> assertEquals(0, pipeline.maxPendingBytes()),
                () -> assertEquals(0, pipeline.runningWeight()),
                () -> assertEquals(0, pipeline.registeredTaskCount()));
    }

    private static void drain(AntlrPipeline pipeline, ManualExecutor executor) throws Exception {
        int nextFuture = 0;
        while (!pipeline.isEmpty()) {
            executor.run(nextFuture++);
            assertTrue(pipeline.finishNext());
        }
        assertEquals(nextFuture, executor.submittedCount());
        assertDrained(pipeline);
    }

    private static String awaitReleaseIgnoringInterrupt(CountDownLatch entered,
                                                         CountDownLatch cancellationObserved,
                                                         CountDownLatch release,
                                                         AtomicBoolean exited) {
        entered.countDown();
        try {
            while (true) {
                try {
                    if (release.await(5, TimeUnit.SECONDS)) {
                        return "parsed";
                    }
                    throw new AssertionError("Timed out waiting to release parser worker");
                } catch (InterruptedException ex) {
                    cancellationObserved.countDown();
                    // Model parser code that does not leave the callable on interrupt.
                }
            }
        } finally {
            exited.set(true);
        }
    }

    private static void assertDrained(AntlrPipeline pipeline) {
        assertAll(
                () -> assertTrue(pipeline.isDrained()),
                () -> assertTrue(pipeline.isEmpty()),
                () -> assertEquals(0, pipeline.pendingCount()),
                () -> assertEquals(0, pipeline.runningCount()),
                () -> assertEquals(0, pipeline.runningWeight()),
                () -> assertEquals(0, pipeline.registeredTaskCount()),
                () -> assertFalse(pipeline.hasCurrentTask()));
    }

    private static final class ManualExecutor extends AbstractExecutorService {

        private final List<RunnableFuture<?>> submitted = new ArrayList<>();
        private boolean shutdown;

        int submittedCount() {
            return submitted.size();
        }

        void run(int index) {
            submitted.get(index).run();
        }

        Future<?> future(int index) {
            return submitted.get(index);
        }

        @Override
        public void execute(Runnable command) {
            if (!(command instanceof RunnableFuture<?> future)) {
                throw new IllegalArgumentException("Expected a RunnableFuture");
            }
            submitted.add(future);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }

    private static final class CallbackExecutor extends AbstractExecutorService {

        private final Runnable callback;
        private Future<?> future;

        private CallbackExecutor(Runnable callback) {
            this.callback = callback;
        }

        Future<?> future() {
            return future;
        }

        @Override
        public void execute(Runnable command) {
            future = (Future<?>) command;
            callback.run();
        }

        @Override
        public void shutdown() {
            // no-op
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
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

    private static final class RejectingExecutor extends AbstractExecutorService {

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("controlled rejection");
        }

        @Override
        public void shutdown() {
            // no-op
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
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

    private static final class PendingProbeTask extends AntlrTask<String> {

        private int pendingChecks;

        private PendingProbeTask(Future<String> future) {
            super(future, ignored -> { });
        }

        @Override
        boolean isPending() {
            pendingChecks++;
            return super.isPending();
        }

        int pendingChecks() {
            return pendingChecks;
        }

        void resetPendingChecks() {
            pendingChecks = 0;
        }
    }

    private static void assertNoDuplicateCancelAcrossCurrentTransition(
            boolean scopeRequest) throws Exception {
        var pipeline = new AntlrPipeline(1, new ManualExecutor());
        var future = new CurrentTransitionFuture("parsed");
        pipeline.add(new AntlrTask<>(future, ignored -> { }));
        var requestFailure = new AtomicReference<Throwable>();
        var ownerFailure = new AtomicReference<Throwable>();
        var requester = new Thread(() -> {
            try {
                if (scopeRequest) {
                    pipeline.requestAbortForScope(null);
                } else {
                    pipeline.requestAbortLocal();
                }
            } catch (Throwable ex) {
                requestFailure.set(ex);
            }
        });
        var owner = new Thread(() -> {
            try {
                pipeline.finish();
            } catch (Throwable ex) {
                ownerFailure.set(ex);
            }
        });

        try {
            requester.start();
            assertTrue(future.cancelEntered.await(5, TimeUnit.SECONDS));
            owner.start();
            assertTrue(future.getEntered.await(5, TimeUnit.SECONDS));

            future.allowCancelReturn.countDown();
            requester.join(TimeUnit.SECONDS.toMillis(5));

            assertAll(
                    () -> assertFalse(requester.isAlive()),
                    () -> assertNull(requestFailure.get()),
                    () -> assertTrue(pipeline.hasCurrentTask()),
                    () -> assertEquals(1, future.cancelCalls.get()));
        } finally {
            future.allowCancelReturn.countDown();
            future.allowGetReturn.countDown();
            requester.join(TimeUnit.SECONDS.toMillis(5));
            owner.join(TimeUnit.SECONDS.toMillis(5));
            pipeline.abort();
            pipeline.close();
        }

        assertAll(
                () -> assertFalse(owner.isAlive()),
                () -> assertInstanceOf(
                        java.util.concurrent.CancellationException.class,
                        ownerFailure.get()));
        assertDrained(pipeline);
    }

    private static final class RecordingCancelFuture<T> extends FutureTask<T> {

        private int cancelCalls;

        private RecordingCancelFuture(java.util.concurrent.Callable<T> callable) {
            super(callable);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls++;
            return false;
        }

        int cancelCalls() {
            return cancelCalls;
        }
    }

    private static final class CurrentTransitionFuture implements Future<String> {

        private final String result;
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final CountDownLatch cancelEntered = new CountDownLatch(1);
        private final CountDownLatch allowCancelReturn = new CountDownLatch(1);
        private final CountDownLatch getEntered = new CountDownLatch(1);
        private final CountDownLatch allowGetReturn = new CountDownLatch(1);

        private CurrentTransitionFuture(String result) {
            this.result = result;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (cancelCalls.incrementAndGet() == 1) {
                cancelEntered.countDown();
                awaitUninterruptibly(allowCancelReturn);
            }
            return false;
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
        public String get() throws InterruptedException {
            getEntered.countDown();
            allowGetReturn.await();
            return result;
        }

        @Override
        public String get(long timeout, TimeUnit unit)
                throws InterruptedException, TimeoutException {
            getEntered.countDown();
            if (!allowGetReturn.await(timeout, unit)) {
                throw new TimeoutException("Timed out waiting for release");
            }
            return result;
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

    private static final class FirstCancelThrowsFuture<T> extends FutureTask<T> {

        private final Throwable failure;
        private int cancelCalls;

        private FirstCancelThrowsFuture(java.util.concurrent.Callable<T> callable,
                                        Throwable failure) {
            super(callable);
            this.failure = failure;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (++cancelCalls == 1) {
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw (Error) failure;
            }
            return super.cancel(mayInterruptIfRunning);
        }

        int cancelCalls() {
            return cancelCalls;
        }
    }

    private static final class BlockingFuture<T> implements Future<T> {

        private final T result;
        private final CountDownLatch getEntered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean cancelled;

        private BlockingFuture(T result) {
            this.result = result;
        }

        boolean awaitGet() throws InterruptedException {
            return getEntered.await(5, TimeUnit.SECONDS);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            released.countDown();
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return released.getCount() == 0;
        }

        @Override
        public T get() throws InterruptedException {
            getEntered.countDown();
            released.await();
            return result;
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
            getEntered.countDown();
            if (!released.await(timeout, unit)) {
                throw new TimeoutException("Timed out waiting for release");
            }
            return result;
        }
    }
}
