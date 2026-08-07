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

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.utils.DaemonThreadFactory;

/**
 * Manages execution and completion of asynchronous ANTLR parsing tasks.
 * Uses a fixed thread pool for parallel parsing operations.
 */
public final class AntlrTaskManager {

    private static final int POOL_SIZE = Integer.max(1,
            Integer.getInteger(Consts.POOL_SIZE, Runtime.getRuntime().availableProcessors() - 1));

    private static final ExecutorService ANTLR_POOL =
            Executors.newFixedThreadPool(POOL_SIZE, new DaemonThreadFactory());

    /**
     * Gets the size of the thread pool used for ANTLR parsing tasks.
     *
     * @return number of threads in the pool
     */
    public static int getPoolSize() {
        return POOL_SIZE;
    }

    /**
     * Creates a queue for one ANTLR loading phase. A positive configured window
     * enables bounded lazy submission; a non-positive explicit value restores
     * eager submission without interleaved finalization.
     *
     * @return a newly configured task queue
     */
    public static Queue<AntlrTask<?>> createTaskQueue() {
        return createTaskQueue(ParserExecutionPolicy.SHARED);
    }

    /**
     * Creates a parser queue using either the existing process-wide pool or a
     * bounded executor owned by this queue.
     *
     * @param policy parser execution policy
     * @return newly configured task queue
     */
    public static Queue<AntlrTask<?>> createTaskQueue(ParserExecutionPolicy policy) {
        if (!java.util.Objects.requireNonNull(policy, "policy").shared()) {
            var threadNumber = new AtomicInteger();
            var daemonFactory = new DaemonThreadFactory();
            ExecutorService executor = new LazyExecutorService(() ->
                    Executors.newFixedThreadPool(policy.workers(), runnable -> {
                        Thread thread = daemonFactory.newThread(runnable);
                        thread.setName("pgck-antlr-index-"
                                + threadNumber.incrementAndGet());
                        return thread;
                    }));
            return new AntlrPipeline(policy.maxPending(),
                    policy.maxPendingBytes(), executor, true, true);
        }

        int defaultMaxPending = POOL_SIZE > Integer.MAX_VALUE / 2
                ? Integer.MAX_VALUE
                : POOL_SIZE * 2;
        int maxPending = Integer.getInteger(Consts.MAX_PENDING_TASKS, defaultMaxPending);
        if (maxPending <= 0) {
            return new AntlrPipeline(Integer.MAX_VALUE, 0, ANTLR_POOL, false);
        }

        long maxPendingBytes = Long.getLong(Consts.MAX_PENDING_BYTES, 0L);
        return new AntlrPipeline(maxPending, maxPendingBytes, ANTLR_POOL);
    }

    /**
     * Creates a thread-confined queue that submits to the same operation-owned
     * executor as its root queue. Closing this sibling never closes the root.
     *
     * @param rootQueue root parser queue of the loading operation
     * @return sibling queue sharing the root execution scope
     */
    public static Queue<AntlrTask<?>> createSiblingTaskQueue(
            Queue<AntlrTask<?>> rootQueue) {
        if (rootQueue instanceof AntlrPipeline pipeline) {
            return pipeline.createSibling();
        }
        throw new IllegalArgumentException(
                "Sibling ANTLR queues require a manager-created root queue");
    }

    /**
     * Submits a parsing task for asynchronous execution.
     *
     * @param <T>  type of the parsing result
     * @param task the parsing task to execute
     * @return Future representing the pending result
     */
    public static <T> Future<T> submit(Callable<T> task) {
        return ANTLR_POOL.submit(task);
    }

    /**
     * Submits a parsing task with completion handler.
     *
     * @param <T>        type of the parsing result
     * @param antlrTasks queue to store the created task
     * @param task       the parsing task to execute
     * @param finalizer  consumer to process the result when complete
     */
    public static <T> void submit(Queue<AntlrTask<?>> antlrTasks, Callable<T> task, Consumer<T> finalizer) {
        submit(antlrTasks, 0, task, finalizer);
    }

    /**
     * Submits a parsing task with its retained parser-input estimate. The
     * estimate participates in byte admission for a bounded pipeline and is
     * ignored by an explicit unbounded eager queue.
     *
     * @param <T>        type of the parsing result
     * @param antlrTasks queue to store the created task
     * @param weight     nonnegative retained parser-input bytes
     * @param task       parsing task to execute
     * @param finalizer  consumer to process the result when complete
     */
    public static <T> void submit(Queue<AntlrTask<?>> antlrTasks, long weight,
                                  Callable<T> task, Consumer<T> finalizer) {
        if (weight < 0) {
            throw new IllegalArgumentException("ANTLR task weight must be nonnegative");
        }
        if (antlrTasks instanceof AntlrPipeline pipeline) {
            pipeline.submit(weight, task, finalizer);
            return;
        }

        Future<T> future = submit(task);
        antlrTasks.add(new AntlrTask<>(future, finalizer));
    }

    /**
     * Processes all tasks in the queue until completion or failure.
     *
     * @param antlrTasks queue of tasks to process
     * @throws InterruptedException if task processing was interrupted
     * @throws IOException          if an I/O error occurred during parsing
     */
    public static void finish(Queue<AntlrTask<?>> antlrTasks) throws InterruptedException, IOException {
        AntlrTask<?> currentTask = null;
        try {
            if (antlrTasks instanceof AntlrPipeline pipeline) {
                pipeline.finish();
            } else {
                while ((currentTask = antlrTasks.poll()) != null) {
                    currentTask.finish();
                    currentTask = null;
                }
            }
        } catch (ExecutionException ex) {
            abortAfterFailure(antlrTasks, currentTask, ex);
            handleAntlrTaskException(ex);
        } catch (CancellationException ex) {
            abortAfterFailure(antlrTasks, currentTask, ex);
            InterruptedException interrupted = new InterruptedException();
            mergeSuppressed(interrupted, ex);
            throw interrupted;
        } catch (MonitorCancelledRuntimeException ex) {
            abortAfterFailure(antlrTasks, currentTask, ex);
            throw ex;
        } catch (RuntimeException | Error ex) {
            abortAfterFailure(antlrTasks, currentTask, ex);
            throw ex;
        }
    }

    /**
     * Processes the completed FIFO head of a bounded parser pipeline without
     * waiting. Explicit unbounded queues are left untouched.
     *
     * @param antlrTasks queue whose completed head may be processed
     * @return {@code true} if one task was finalized, otherwise {@code false}
     * @throws InterruptedException if task processing was interrupted
     * @throws IOException          if an I/O error occurred during parsing
     */
    public static boolean tryFinishNext(Queue<AntlrTask<?>> antlrTasks)
            throws InterruptedException, IOException {
        if (!(antlrTasks instanceof AntlrPipeline pipeline)) {
            return false;
        }

        try {
            return pipeline.tryFinishNext();
        } catch (ExecutionException ex) {
            abortAfterFailure(antlrTasks, null, ex);
            handleAntlrTaskException(ex);
            return false;
        } catch (CancellationException ex) {
            abortAfterFailure(antlrTasks, null, ex);
            InterruptedException interrupted = new InterruptedException();
            mergeSuppressed(interrupted, ex);
            throw interrupted;
        } catch (MonitorCancelledRuntimeException ex) {
            abortAfterFailure(antlrTasks, null, ex);
            throw ex;
        } catch (RuntimeException | Error ex) {
            abortAfterFailure(antlrTasks, null, ex);
            throw ex;
        }
    }

    /**
     * Cancels all submitted tasks owned by the queue and releases every pending
     * callable and finalizer. The operation is safe to repeat.
     *
     * @param antlrTasks queue whose current phase must be discarded
     */
    public static void abort(Queue<AntlrTask<?>> antlrTasks) {
        if (antlrTasks instanceof AntlrPipeline pipeline) {
            pipeline.abort();
            return;
        }

        AntlrTask<?> task;
        Throwable cancellationFailure = null;
        while ((task = antlrTasks.poll()) != null) {
            try {
                task.cancelAndClear();
            } catch (RuntimeException | Error ex) {
                if (cancellationFailure == null) {
                    cancellationFailure = ex;
                } else if (cancellationFailure != ex) {
                    cancellationFailure.addSuppressed(ex);
                }
            }
        }

        rethrowCancellationFailure(cancellationFailure);
    }

    /**
     * Requests cancellation of submitted tasks without mutating the queue's
     * owner-only ordering and accounting state. Explicit caller-owned queues do
     * not provide a safe cross-thread cancellation protocol and remain untouched.
     *
     * @param antlrTasks task queue whose submitted work should be interrupted
     */
    public static void requestAbort(Queue<AntlrTask<?>> antlrTasks) {
        if (antlrTasks instanceof AntlrPipeline pipeline) {
            pipeline.requestAbort();
        }
    }

    /**
     * Closes a manager-created parser queue. Compatibility queues supplied by
     * callers do not own an executor and remain untouched.
     *
     * @param antlrTasks task queue to close
     */
    public static void close(Queue<AntlrTask<?>> antlrTasks) {
        if (antlrTasks instanceof AntlrPipeline pipeline) {
            pipeline.close();
        }
    }

    /**
     * Returns whether a task queue has released all work and cancellation state.
     *
     * @param antlrTasks task queue to inspect
     * @return {@code true} when the queue can safely begin a new owner phase
     */
    public static boolean isDrained(Queue<AntlrTask<?>> antlrTasks) {
        return antlrTasks instanceof AntlrPipeline pipeline
                ? pipeline.isDrained()
                : antlrTasks.isEmpty();
    }

    /**
     * Rejects reuse of a task queue whose previous owner phase is incomplete.
     *
     * @param antlrTasks task queue to inspect
     * @throws IllegalStateException if pending work or cancellation state remains
     */
    public static void requireDrained(Queue<AntlrTask<?>> antlrTasks) {
        if (!isDrained(antlrTasks)) {
            throw new IllegalStateException("ANTLR task queue is not drained");
        }
    }

    /**
     * Copies suppressed cleanup failures without duplicating identities.
     *
     * @param primary exception that remains primary
     * @param source  wrapper or replaced exception holding cleanup failures
     */
    public static void mergeSuppressed(Throwable primary, Throwable source) {
        if (primary == source) {
            return;
        }
        for (Throwable candidate : source.getSuppressed()) {
            if (candidate != primary && !containsSuppressedIdentity(primary, candidate)) {
                primary.addSuppressed(candidate);
            }
        }
    }

    /**
     * Unwraps and rethrows specific exceptions from ExecutionException wrapper.
     * Handles InterruptedException and IOException by rethrowing them directly.
     * All other exceptions are wrapped in IllegalStateException.
     *
     * @param ex the ExecutionException to unwrap
     * @throws InterruptedException if the task was interrupted
     * @throws IOException if an I/O error occurred during parsing
     * @throws IllegalStateException for any other exception types
     */
    private static void handleAntlrTaskException(ExecutionException ex)
            throws InterruptedException, IOException {
        Throwable t = ex.getCause();
        if (t instanceof InterruptedException in) {
            mergeSuppressed(in, ex);
            throw in;
        }
        if (t instanceof IOException io) {
            mergeSuppressed(io, ex);
            throw io;
        }
        if (t instanceof MonitorCancelledRuntimeException cancellation) {
            mergeSuppressed(cancellation, ex);
            throw cancellation;
        }

        throw new IllegalStateException(ex);
    }

    private static void abortAfterFailure(Queue<AntlrTask<?>> antlrTasks,
                                          AntlrTask<?> currentTask, Throwable failure) {
        if (currentTask != null) {
            try {
                currentTask.cancelAndClear();
            } catch (RuntimeException | Error ex) {
                if (failure != ex) {
                    failure.addSuppressed(ex);
                }
            }
        }

        try {
            abort(antlrTasks);
        } catch (RuntimeException | Error ex) {
            if (failure != ex) {
                failure.addSuppressed(ex);
            }
        }
    }

    private static void rethrowCancellationFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static boolean containsSuppressedIdentity(Throwable primary, Throwable candidate) {
        for (Throwable existing : primary.getSuppressed()) {
            if (existing == candidate) {
                return true;
            }
        }
        return false;
    }

    private AntlrTaskManager() {
        // only static
    }
}
