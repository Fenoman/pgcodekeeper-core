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

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Thread-confined FIFO queue backed by an operation-wide ANTLR admission
 * window. Tasks beyond the shared window retain only their callable and
 * finalizer until a slot becomes available. Tasks registered by a finalizer
 * are appended after every task already in this queue, which preserves
 * breadth-first generation order without independent per-generation windows.
 * <p>
 * Directly adding an {@link AntlrTask} remains supported for compatibility.
 * Such a task already owns an externally submitted future, so direct additions
 * may exceed the configured window. The hard submission bound is shared by
 * the root and every sibling queue and applies only to tasks registered through
 * {@link AntlrTaskManager#submit(java.util.Queue, Callable, Consumer)}.
 */
final class AntlrPipeline extends AbstractQueue<AntlrTask<?>> {

    private static final class SubmittedTaskRegistration {

        private final AntlrTask<?> task;
        private volatile boolean active = true;

        private SubmittedTaskRegistration(AntlrTask<?> task) {
            this.task = task;
        }
    }

    private final boolean allowEarlyFinish;
    private final ParserExecutionScope executionScope;
    private final boolean ownsExecutionScope;
    private final Deque<AntlrTask<?>> tasks = new ArrayDeque<>();
    /** Owner-written identity registry used for O(1) removal. */
    private final Map<AntlrTask<?>, SubmittedTaskRegistration> submittedTasks =
            new IdentityHashMap<>();
    /**
     * Concurrent registration log lets cancellation take a stable FIFO
     * snapshot without waiting for an owner that may be blocked in user code.
     * Inactive FIFO heads are pruned on ordinary owner removal, so retained
     * registrations stay bounded by active work and out-of-order tombstones.
     */
    private final ConcurrentLinkedQueue<SubmittedTaskRegistration>
            submittedTaskOrder = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    /**
     * Normal FIFO access remains thread-confined. The lock only coordinates a
     * cross-thread root abort with the current owner: cancellation is requested
     * through concurrent state first, then cleanup waits until that owner leaves
     * the deque before taking over it.
     */
    private final ReentrantLock ownerLock = new ReentrantLock();

    private volatile AntlrTask<?> currentTask;
    private volatile int submittedTaskCount;

    AntlrPipeline(int maxPending, ExecutorService executor) {
        this(maxPending, 0, executor, true, false);
    }

    AntlrPipeline(int maxPending, long maxPendingBytes, ExecutorService executor) {
        this(maxPending, maxPendingBytes, executor, true, false);
    }

    AntlrPipeline(int maxPending, long maxPendingBytes, ExecutorService executor,
                  boolean allowEarlyFinish) {
        this(maxPending, maxPendingBytes, executor, allowEarlyFinish, false);
    }

    AntlrPipeline(int maxPending, long maxPendingBytes, ExecutorService executor,
                  boolean allowEarlyFinish, boolean ownsExecutor) {
        this(allowEarlyFinish,
                new ParserExecutionScope(Objects.requireNonNull(executor), ownsExecutor,
                        maxPending, allowEarlyFinish ? maxPendingBytes : 0),
                true);
    }

    private AntlrPipeline(boolean allowEarlyFinish,
                          ParserExecutionScope executionScope,
                          boolean ownsExecutionScope) {
        this.allowEarlyFinish = allowEarlyFinish;
        this.executionScope = Objects.requireNonNull(executionScope);
        this.ownsExecutionScope = ownsExecutionScope;
        executionScope.register(this, ownsExecutionScope);
    }

    AntlrPipeline createSibling() {
        return new AntlrPipeline(allowEarlyFinish, executionScope, false);
    }

    <T> void submit(Callable<T> callable, Consumer<T> finalizer) {
        submit(0, callable, finalizer);
    }

    <T> void submit(long weight, Callable<T> callable, Consumer<T> finalizer) {
        ownerLock.lock();
        try {
            requireOpen();
            AntlrTask<T> task = new AntlrTask<>(normalizeSubmittedWeight(weight),
                    callable, finalizer);
            tasks.addLast(task);
            try {
                if (allowEarlyFinish) {
                    fillWindow();
                } else {
                    submitEager(task);
                }
                if (cancellationRequested.get() && task.isPending()
                        && tasks.removeLastOccurrence(task)) {
                    task.cancelAndClear();
                }
            } catch (RuntimeException | Error ex) {
                abortAfterSubmissionFailure(ex);
                throw ex;
            }
        } finally {
            ownerLock.unlock();
        }
    }

    void finish() throws ExecutionException {
        ownerLock.lock();
        try {
            while (finishNextOwned()) {
                // drain in logical FIFO order
            }
        } finally {
            ownerLock.unlock();
        }
    }

    boolean finishNext() throws ExecutionException {
        ownerLock.lock();
        try {
            return finishNextOwned();
        } finally {
            ownerLock.unlock();
        }
    }

    private boolean finishNextOwned() throws ExecutionException {
        AntlrTask<?> task = tasks.peekFirst();
        if (task == null) {
            return false;
        }
        if (!task.isSubmitted()) {
            fillWindow();
            if (!task.isSubmitted()) {
                awaitAndSubmit(task);
                fillWindow();
            }
        }

        currentTask = task;
        try {
            task.finish(cancellationRequested::get);
            tasks.removeFirst();
            removeSubmittedTask(task);
            releaseAdmission(task);
            fillWindow();
            return true;
        } finally {
            currentTask = null;
        }
    }

    boolean tryFinishNext() throws ExecutionException {
        ownerLock.lock();
        try {
            if (!allowEarlyFinish) {
                return false;
            }
            AntlrTask<?> head = tasks.peekFirst();
            if (head == null || !head.isSubmitted() || !head.isDone()) {
                return false;
            }
            return finishNextOwned();
        } finally {
            ownerLock.unlock();
        }
    }

    int pendingCount() {
        int count = 0;
        for (AntlrTask<?> task : tasks) {
            if (task.isPending()) {
                count++;
            }
        }
        return count;
    }

    int runningCount() {
        int count = 0;
        for (AntlrTask<?> task : tasks) {
            if (task.isAdmitted()) {
                count++;
            }
        }
        return count;
    }

    long runningWeight() {
        long weight = 0;
        for (AntlrTask<?> task : tasks) {
            if (task.isAdmitted()) {
                weight += accountingWeight(task);
            }
        }
        return weight;
    }

    int maxPending() {
        return executionScope.maxPending();
    }

    long maxPendingBytes() {
        return executionScope.maxPendingBytes();
    }

    boolean hasCurrentTask() {
        return currentTask != null;
    }

    boolean isDrained() {
        return tasks.isEmpty()
                && currentTask == null && submittedTaskCount == 0
                && submittedTaskOrder.isEmpty()
                && !cancellationRequested.get();
    }

    int registeredTaskCount() {
        return submittedTaskCount;
    }

    int operationAdmittedCount() {
        return executionScope.admittedCount();
    }

    long operationAdmittedBytes() {
        return executionScope.admittedBytes();
    }

    void requestAbort() {
        if (ownsExecutionScope) {
            executionScope.requestAbort();
        } else {
            requestAbortLocal();
        }
    }

    void requestAbortLocal() {
        cancellationRequested.set(true);
        Throwable cancellationFailure = null;
        try {
            List<AntlrTask<?>> snapshot = submittedTaskSnapshot();
            for (AntlrTask<?> task : snapshot) {
                cancellationFailure = requestCancel(task, cancellationFailure);
            }
            AntlrTask<?> latestCurrent = currentTask;
            if (latestCurrent != null && !containsIdentity(
                    snapshot, latestCurrent)) {
                cancellationFailure = requestCancel(
                        latestCurrent, cancellationFailure);
            }
        } finally {
            executionScope.signalAdmissionWaiters();
        }
        rethrowCancellationFailure(cancellationFailure);
    }

    void abort() {
        if (ownsExecutionScope) {
            executionScope.abort();
        } else {
            abortLocal();
        }
    }

    void abortLocal() {
        Throwable cancellationFailure = null;
        ownerLock.lock();
        try {
            cancellationRequested.set(true);
            executionScope.signalAdmissionWaiters();
            // Cancel in FIFO order before waiting for any worker. This path is
            // owned by the queue thread, so it may traverse the deque directly.
            for (AntlrTask<?> task : tasks) {
                cancellationFailure = requestCancel(task, cancellationFailure);
            }
            cancellationFailure = awaitCancellationOwned(cancellationFailure);
        } finally {
            ownerLock.unlock();
        }
        rethrowCancellationFailure(cancellationFailure);
    }

    /**
     * Requests cancellation without taking the owner lock and appends task
     * failures to the operation-wide FIFO aggregate.
     */
    Throwable requestAbortForScope(Throwable cancellationFailure) {
        cancellationRequested.set(true);
        try {
            List<AntlrTask<?>> snapshot = submittedTaskSnapshot();
            for (AntlrTask<?> task : snapshot) {
                cancellationFailure = requestCancel(task,
                        cancellationFailure);
            }
            AntlrTask<?> latestCurrent = currentTask;
            if (latestCurrent != null && !containsIdentity(
                    snapshot, latestCurrent)) {
                cancellationFailure = requestCancel(latestCurrent,
                        cancellationFailure);
            }
        } finally {
            executionScope.signalAdmissionWaiters();
        }
        return cancellationFailure;
    }

    Throwable awaitCancellationAfterRequest(Throwable cancellationFailure) {
        ownerLock.lock();
        try {
            return awaitCancellationOwned(cancellationFailure);
        } finally {
            ownerLock.unlock();
        }
    }

    private Throwable awaitCancellationOwned(Throwable cancellationFailure) {
        try {
            for (AntlrTask<?> task : tasks) {
                try {
                    task.awaitWorkerExitAndClear();
                } catch (RuntimeException | Error ex) {
                    cancellationFailure = addCancellationFailure(
                            cancellationFailure, ex);
                }
            }
        } finally {
            for (AntlrTask<?> task : tasks) {
                releaseAdmission(task);
            }
            tasks.clear();
            clearSubmittedTasks();
            currentTask = null;
            cancellationRequested.set(false);
        }
        return cancellationFailure;
    }

    void close() {
        if (ownsExecutionScope) {
            executionScope.close();
        } else {
            try {
                closeFromScope();
            } finally {
                executionScope.unregister(this);
            }
        }
    }

    void closeFromScope() {
        closed.set(true);
        abortLocal();
    }

    void markClosedFromScope() {
        closed.set(true);
    }

    @Override
    public boolean offer(AntlrTask<?> task) {
        ownerLock.lock();
        try {
            requireOpen();
            Objects.requireNonNull(task);
            if (!task.isSubmitted()) {
                throw new IllegalArgumentException(
                        "Directly added ANTLR task must own a Future");
            }
            long weight = accountingWeight(task);
            executionScope.acquireUnbounded(task, weight);

            // Register and close the cancellation race before transferring owner
            // queue/accounting state. A failed post-registration cancel therefore
            // rejects only this offered task and leaves the existing phase intact.
            try {
                registerSubmitted(task);
            } catch (RuntimeException | Error ex) {
                executionScope.release(task, weight);
                throw ex;
            }
            tasks.addLast(task);
            try {
                fillWindow();
                return true;
            } catch (RuntimeException | Error ex) {
                abortAfterSubmissionFailure(ex);
                throw ex;
            }
        } finally {
            ownerLock.unlock();
        }
    }

    @Override
    public AntlrTask<?> poll() {
        ownerLock.lock();
        try {
            AntlrTask<?> task = tasks.pollFirst();
            if (task != null) {
                if (task.isSubmitted()) {
                    removeSubmittedTask(task);
                    releaseAdmission(task);
                }
                fillWindow();
            }
            return task;
        } finally {
            ownerLock.unlock();
        }
    }

    @Override
    public AntlrTask<?> peek() {
        return tasks.peekFirst();
    }

    @Override
    public Iterator<AntlrTask<?>> iterator() {
        ownerLock.lock();
        Iterator<AntlrTask<?>> delegate;
        try {
            delegate = tasks.iterator();
        } finally {
            ownerLock.unlock();
        }
        return new Iterator<>() {

            private AntlrTask<?> current;

            @Override
            public boolean hasNext() {
                ownerLock.lock();
                try {
                    return delegate.hasNext();
                } finally {
                    ownerLock.unlock();
                }
            }

            @Override
            public AntlrTask<?> next() {
                ownerLock.lock();
                try {
                    current = delegate.next();
                    return current;
                } finally {
                    ownerLock.unlock();
                }
            }

            @Override
            public void remove() {
                ownerLock.lock();
                try {
                    AntlrTask<?> removed = current;
                    if (removed == null) {
                        delegate.remove();
                        return;
                    }

                    boolean submitted = removed.isSubmitted();
                    try {
                        removed.cancelAndClear();
                    } finally {
                        delegate.remove();
                        if (submitted) {
                            removeSubmittedTask(removed);
                            releaseAdmission(removed);
                        }
                        current = null;
                        fillWindow();
                    }
                } finally {
                    ownerLock.unlock();
                }
            }
        };
    }

    @Override
    public int size() {
        return tasks.size();
    }

    @Override
    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    @Override
    public void clear() {
        abort();
    }

    private void fillWindow() {
        if (!allowEarlyFinish) {
            return;
        }

        for (AntlrTask<?> task : tasks) {
            if (task.isPending()) {
                if (!executionScope.tryAcquire(task, accountingWeight(task))) {
                    // Preserve FIFO: a blocked pending head prevents submission
                    // of every later descriptor, even if a later one is smaller.
                    return;
                }
                submitAdmitted(task);
            }
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("ANTLR task queue is closed");
        }
    }

    private void awaitAndSubmit(AntlrTask<?> task) throws ExecutionException {
        try {
            if (!executionScope.awaitAcquire(task, accountingWeight(task),
                    cancellationRequested, closed)) {
                throw new java.util.concurrent.CancellationException();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ExecutionException(ex);
        }
        submitAdmitted(task);
    }

    private void submitAdmitted(AntlrTask<?> task) {
        try {
            if (cancellationRequested.get()) {
                task.cancelBeforeSubmit();
            } else {
                task.submit(executionScope.executor());
            }
            registerSubmitted(task);
        } catch (RuntimeException | Error ex) {
            try {
                task.requestCancel();
            } catch (RuntimeException | Error cancellationFailure) {
                if (cancellationFailure != ex) {
                    ex.addSuppressed(cancellationFailure);
                }
            }
            releaseAdmission(task);
            throw ex;
        }
    }

    private void submitEager(AntlrTask<?> task) {
        executionScope.acquireUnbounded(task, accountingWeight(task));
        submitAdmitted(task);
    }

    private void releaseAdmission(AntlrTask<?> task) {
        executionScope.release(task, accountingWeight(task));
    }

    private long normalizeSubmittedWeight(long weight) {
        if (weight < 0) {
            throw new IllegalArgumentException("ANTLR task weight must be nonnegative");
        }
        return allowEarlyFinish ? weight : 0;
    }

    private long accountingWeight(AntlrTask<?> task) {
        return allowEarlyFinish ? task.weight() : 0;
    }

    private void registerSubmitted(AntlrTask<?> task) {
        SubmittedTaskRegistration registration = submittedTasks.get(task);
        boolean registered = registration == null;
        if (registered) {
            registration = new SubmittedTaskRegistration(task);
            submittedTasks.put(task, registration);
            submittedTaskOrder.add(registration);
            submittedTaskCount++;
        }
        try {
            if (!task.isAdmitted() || executionScope.isClosed()) {
                task.cancelAndClear();
                throw new java.util.concurrent.CancellationException();
            }
            if (cancellationRequested.get()) {
                task.requestCancel();
            }
        } catch (RuntimeException | Error ex) {
            if (registered) {
                removeSubmittedTask(task);
            }
            throw ex;
        }
    }

    private void removeSubmittedTask(AntlrTask<?> task) {
        SubmittedTaskRegistration registration = submittedTasks.remove(task);
        if (registration == null) {
            return;
        }
        registration.active = false;
        submittedTaskCount--;
        pruneInactiveRegistrations();
    }

    private void pruneInactiveRegistrations() {
        SubmittedTaskRegistration head;
        while ((head = submittedTaskOrder.peek()) != null && !head.active) {
            submittedTaskOrder.poll();
        }
    }

    private List<AntlrTask<?>> submittedTaskSnapshot() {
        var snapshot = new ArrayList<AntlrTask<?>>(submittedTaskCount);
        for (SubmittedTaskRegistration registration : submittedTaskOrder) {
            if (registration.active) {
                snapshot.add(registration.task);
            }
        }
        return snapshot;
    }

    private static boolean containsIdentity(List<AntlrTask<?>> tasks,
            AntlrTask<?> candidate) {
        for (AntlrTask<?> task : tasks) {
            if (task == candidate) {
                return true;
            }
        }
        return false;
    }

    private void clearSubmittedTasks() {
        for (SubmittedTaskRegistration registration : submittedTasks.values()) {
            registration.active = false;
        }
        submittedTasks.clear();
        submittedTaskCount = 0;
        submittedTaskOrder.clear();
    }

    private void abortAfterSubmissionFailure(Throwable failure) {
        try {
            abort();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static Throwable requestCancel(AntlrTask<?> task, Throwable failure) {
        try {
            task.requestCancel();
        } catch (RuntimeException | Error ex) {
            return addCancellationFailure(failure, ex);
        }
        return failure;
    }

    private static Throwable addCancellationFailure(Throwable primary,
            Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (secondary != null && primary != secondary
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

    private static void rethrowCancellationFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
