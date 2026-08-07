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

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Wrapper for asynchronous ANTLR parsing tasks that combines a Future with a completion handler.
 * Allows executing parsing tasks in background and processing results when complete.
 *
 * @param <T> type of the result produced by the parsing task
 */
public class AntlrTask<T> {

    private enum WorkerState {
        PENDING,
        RUNNING,
        EXITED,
        CANCELLED_BEFORE_RUN
    }

    /**
     * FutureTask completion is published before a cancelled callable has
     * necessarily left its executor thread. Manager-owned tasks therefore keep
     * a second completion signal for the actual {@link #run()} boundary.
     */
    private static final class PipelineFutureTask<T> extends FutureTask<T> {

        private WorkerState workerState = WorkerState.PENDING;

        private PipelineFutureTask(Callable<T> callable) {
            super(callable);
        }

        @Override
        public void run() {
            synchronized (this) {
                if (workerState != WorkerState.PENDING) {
                    return;
                }
                workerState = WorkerState.RUNNING;
            }

            try {
                super.run();
            } finally {
                synchronized (this) {
                    workerState = WorkerState.EXITED;
                    notifyAll();
                }
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                synchronized (this) {
                    if (workerState == WorkerState.PENDING) {
                        workerState = WorkerState.CANCELLED_BEFORE_RUN;
                        notifyAll();
                    }
                }
            }
            return cancelled;
        }

        private synchronized void awaitWorkerExitUninterruptibly() {
            boolean interrupted = false;
            while (workerState == WorkerState.PENDING || workerState == WorkerState.RUNNING) {
                try {
                    wait();
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private enum CancelledFuture implements Future<Object> {
        INSTANCE;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            throw new CancellationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            Objects.requireNonNull(unit);
            throw new CancellationException();
        }
    }

    private final long weight;
    private Callable<T> callable;
    private volatile Future<T> future;
    private Consumer<T> finalizer;
    private volatile boolean admitted;

    /**
     * Creates a new ANTLR task with a Future and completion handler.
     *
     * @param future    the Future representing the asynchronous parsing task
     * @param finalizer consumer that will process the parsing result when task completes
     */
    public AntlrTask(Future<T> future, Consumer<T> finalizer) {
        this.weight = 0;
        this.future = Objects.requireNonNull(future);
        this.finalizer = Objects.requireNonNull(finalizer);
    }

    AntlrTask(Callable<T> callable, Consumer<T> finalizer) {
        this(0, callable, finalizer);
    }

    AntlrTask(long weight, Callable<T> callable, Consumer<T> finalizer) {
        if (weight < 0) {
            throw new IllegalArgumentException("ANTLR task weight must be nonnegative");
        }
        this.weight = weight;
        this.callable = Objects.requireNonNull(callable);
        this.finalizer = Objects.requireNonNull(finalizer);
    }

    long weight() {
        return weight;
    }

    void submit(ExecutorService executor) {
        if (callable == null || future != null) {
            throw new IllegalStateException("ANTLR task is not pending");
        }

        PipelineFutureTask<T> pipelineFuture = new PipelineFutureTask<>(callable);
        future = pipelineFuture;
        try {
            executor.execute(pipelineFuture);
        } finally {
            // The FutureTask owns the callable even when execute rejects it;
            // pipeline failure cleanup will cancel and drain that descriptor.
            callable = null;
        }
    }

    void cancelBeforeSubmit() {
        if (callable == null || future != null) {
            throw new IllegalStateException("ANTLR task is not pending");
        }

        callable = null;
        future = cancelledFuture();
    }

    boolean isPending() {
        return callable != null;
    }

    boolean isSubmitted() {
        return future != null;
    }

    boolean isDone() {
        return future != null && future.isDone();
    }

    boolean isCleared() {
        return callable == null && future == null && finalizer == null
                && !admitted;
    }

    boolean isAdmitted() {
        return admitted;
    }

    void markAdmitted() {
        if (admitted) {
            throw new IllegalStateException("ANTLR task is already admitted");
        }
        admitted = true;
    }

    boolean clearAdmission() {
        if (!admitted) {
            return false;
        }
        admitted = false;
        return true;
    }

    /**
     * Waits for the task to complete and processes the result with the finalizer.
     * Propagates any exceptions that occurred during execution.
     *
     * @throws ExecutionException if the computation threw an exception
     */
    public void finish() throws ExecutionException {
        finish(() -> false);
    }

    void finish(BooleanSupplier cancelled) throws ExecutionException {
        Objects.requireNonNull(cancelled);
        Future<T> currentFuture = future;
        Consumer<T> currentFinalizer = finalizer;
        if (currentFuture == null || currentFinalizer == null) {
            throw new IllegalStateException("ANTLR task has not been submitted or was already finished");
        }

        boolean successful = false;
        try {
            T result = currentFuture.get();
            if (cancelled.getAsBoolean()) {
                throw new CancellationException();
            }
            currentFinalizer.accept(result);
            successful = true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ExecutionException(ex);
        } finally {
            if (successful) {
                clearReferences();
            }
        }
    }

    void requestCancel() {
        Future<T> currentFuture = future;
        if (currentFuture != null) {
            currentFuture.cancel(true);
        }
    }

    void clearReferences() {
        callable = null;
        future = null;
        finalizer = null;
    }

    void cancelAndClear() {
        try {
            requestCancel();
        } finally {
            awaitWorkerExitAndClear();
        }
    }

    void awaitWorkerExitAndClear() {
        Future<T> currentFuture = future;
        try {
            if (currentFuture instanceof PipelineFutureTask<?> pipelineFuture) {
                pipelineFuture.awaitWorkerExitUninterruptibly();
            }
        } finally {
            clearReferences();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Future<T> cancelledFuture() {
        return (Future<T>) CancelledFuture.INSTANCE;
    }
}
