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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Parser executor and queues owned by one root loading operation. Parallel
 * JDBC lanes use separate thread-confined queues, but submit through this same
 * count-and-byte admission window and executor, and are cancelled and closed
 * with the root queue.
 */
final class ParserExecutionScope {

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final int maxPending;
    private final long maxPendingBytes;
    private final ParserExecutionObserver observer;
    /**
     * Queue creation order is part of terminal failure ordering. This registry
     * is touched only while queues are registered, unregistered or snapshotted;
     * parser task submission and completion never acquire its lock.
     */
    private final Object queueRegistryLock = new Object();
    private final List<AntlrPipeline> queues = new ArrayList<>();
    private final Set<AntlrPipeline> queueIdentities =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicBoolean closed = new AtomicBoolean();

    private int admittedCount;
    private long admittedBytes;

    ParserExecutionScope(ExecutorService executor, boolean ownsExecutor,
            int maxPending, long maxPendingBytes) {
        if (maxPending <= 0) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        if (maxPendingBytes < 0) {
            throw new IllegalArgumentException(
                    "maxPendingBytes must be nonnegative");
        }
        ExecutorService checkedExecutor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
        this.maxPending = maxPending;
        this.maxPendingBytes = maxPendingBytes;
        this.observer = ParserExecutionObservers.create();
        if (observer == null) {
            this.executor = checkedExecutor;
        } else {
            observer.scopeCreated(checkedExecutor);
            this.executor = Objects.requireNonNull(
                    observer.observeExecutor(checkedExecutor), "observed executor");
        }
    }

    ExecutorService executor() {
        return executor;
    }

    int maxPending() {
        return maxPending;
    }

    long maxPendingBytes() {
        return maxPendingBytes;
    }

    synchronized boolean tryAcquire(AntlrTask<?> task, long weight) {
        requireOpen();
        if (!hasCapacity(weight)) {
            return false;
        }
        acquire(task, weight);
        return true;
    }

    synchronized boolean awaitAcquire(AntlrTask<?> task, long weight,
            AtomicBoolean queueCancelled, AtomicBoolean queueClosed)
            throws InterruptedException {
        while (!closed.get() && !queueCancelled.get() && !queueClosed.get()) {
            if (hasCapacity(weight)) {
                acquire(task, weight);
                return true;
            }
            if (observer != null) {
                observer.admissionWaitStarted();
            }
            wait();
        }
        return false;
    }

    synchronized void acquireUnbounded(AntlrTask<?> task, long weight) {
        requireOpen();
        if (admittedCount == Integer.MAX_VALUE
                || weight > Long.MAX_VALUE - admittedBytes) {
            throw new IllegalStateException(
                    "ANTLR running task weight overflow");
        }
        acquire(task, weight);
    }

    synchronized void release(AntlrTask<?> task, long weight) {
        if (!task.isAdmitted()) {
            return;
        }
        if (admittedCount <= 0 || admittedBytes < weight) {
            throw new IllegalStateException(
                    "ANTLR admission accounting underflow");
        }
        task.clearAdmission();
        admittedCount--;
        admittedBytes -= weight;
        notifyAll();
    }

    synchronized void signalAdmissionWaiters() {
        notifyAll();
    }

    boolean isClosed() {
        return closed.get();
    }

    void register(AntlrPipeline queue, boolean root) {
        synchronized (queueRegistryLock) {
            if (closed.get()) {
                throw new IllegalStateException("ANTLR task queue is closed");
            }
            if (!queueIdentities.add(queue)) {
                throw new IllegalStateException(
                        "ANTLR task queue is already registered");
            }
            queues.add(queue);
        }
        if (observer != null) {
            observer.queueCreated(root);
        }
    }

    void unregister(AntlrPipeline queue) {
        synchronized (queueRegistryLock) {
            if (!queueIdentities.remove(queue)) {
                return;
            }
            queues.removeIf(registered -> registered == queue);
        }
    }

    void requestAbort() {
        Throwable failure = null;
        for (AntlrPipeline queue : queueSnapshot()) {
            try {
                queue.requestAbortLocal();
            } catch (RuntimeException | Error ex) {
                failure = addFailure(failure, ex);
            }
        }
        rethrow(failure);
    }

    void abort() {
        abort(queueSnapshot());
    }

    private void abort(List<AntlrPipeline> ownedQueues) {
        Throwable failure = null;
        // Interrupt all queues before waiting for any one queue to drain. A
        // parser in one lane must not delay cancellation of sibling lanes.
        for (AntlrPipeline queue : ownedQueues) {
            failure = queue.requestAbortForScope(failure);
        }
        for (AntlrPipeline queue : ownedQueues) {
            failure = queue.awaitCancellationAfterRequest(failure);
        }
        rethrow(failure);
    }

    void close() {
        List<AntlrPipeline> ownedQueues = closeAndSnapshot();
        if (ownedQueues == null) {
            return;
        }
        for (AntlrPipeline queue : ownedQueues) {
            queue.markClosedFromScope();
        }
        signalAdmissionWaiters();

        Throwable failure = null;
        try {
            abort(ownedQueues);
        } catch (RuntimeException | Error ex) {
            failure = ex;
        }
        synchronized (queueRegistryLock) {
            queues.clear();
            queueIdentities.clear();
        }

        if (ownsExecutor) {
            try {
                executor.shutdownNow();
                try {
                    executor.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            } catch (RuntimeException | Error ex) {
                failure = addFailure(failure, ex);
            }
        }
        rethrow(failure);
    }

    private boolean hasCapacity(long weight) {
        boolean accountingFits = admittedCount < Integer.MAX_VALUE
                && weight <= Long.MAX_VALUE - admittedBytes;
        boolean countFits = admittedCount < maxPending;
        boolean bytesFit = maxPendingBytes == 0
                || admittedBytes <= maxPendingBytes
                && weight <= maxPendingBytes - admittedBytes;
        return admittedCount == 0 || accountingFits && countFits && bytesFit;
    }

    private void acquire(AntlrTask<?> task, long weight) {
        task.markAdmitted();
        admittedCount++;
        admittedBytes += weight;
    }

    synchronized int admittedCount() {
        return admittedCount;
    }

    synchronized long admittedBytes() {
        return admittedBytes;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("ANTLR task queue is closed");
        }
    }

    private List<AntlrPipeline> queueSnapshot() {
        synchronized (queueRegistryLock) {
            return List.copyOf(queues);
        }
    }

    private List<AntlrPipeline> closeAndSnapshot() {
        synchronized (queueRegistryLock) {
            if (!closed.compareAndSet(false, true)) {
                return null;
            }
            return List.copyOf(queues);
        }
    }

    private static Throwable addFailure(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary != secondary
                && !containsSuppressedIdentity(primary, secondary)) {
            primary.addSuppressed(secondary);
        }
        return primary;
    }

    private static boolean containsSuppressedIdentity(Throwable primary,
            Throwable candidate) {
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == candidate) {
                return true;
            }
        }
        return false;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
