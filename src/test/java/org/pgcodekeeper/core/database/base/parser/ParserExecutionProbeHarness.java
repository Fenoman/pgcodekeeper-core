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
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explicit test-only observer for parser scope allocation, worker bounds and
 * executor termination.
 */
public final class ParserExecutionProbeHarness implements AutoCloseable {

    private final List<Session> sessions =
            Collections.synchronizedList(new ArrayList<>());
    private final AutoCloseable installation;

    public static ParserExecutionProbeHarness install() {
        return new ParserExecutionProbeHarness();
    }

    private ParserExecutionProbeHarness() {
        this.installation = ParserExecutionObservers.install(() -> {
            var session = new Session();
            sessions.add(session);
            return session;
        });
    }

    public int sessionCount() {
        return sessions.size();
    }

    public Session onlySession() {
        synchronized (sessions) {
            if (sessions.size() != 1) {
                throw new IllegalStateException(
                        "Expected one parser scope, got " + sessions.size());
            }
            return sessions.get(0);
        }
    }

    @Override
    public void close() throws Exception {
        installation.close();
    }

    public static final class Session implements ParserExecutionObserver {

        private final AtomicInteger scopes = new AtomicInteger();
        private final AtomicInteger queues = new AtomicInteger();
        private final AtomicInteger lazyExecutors = new AtomicInteger();
        private final Semaphore admissionWaits = new Semaphore(0);
        private TrackingExecutor executor;

        @Override
        public ExecutorService observeExecutor(ExecutorService executor) {
            this.executor = new TrackingExecutor(executor);
            return this.executor;
        }

        @Override
        public void scopeCreated(ExecutorService executor) {
            scopes.incrementAndGet();
            if (executor instanceof LazyExecutorService) {
                lazyExecutors.incrementAndGet();
            }
        }

        @Override
        public void queueCreated(boolean root) {
            queues.incrementAndGet();
        }

        @Override
        public void admissionWaitStarted() {
            admissionWaits.release();
        }

        public boolean awaitAdmissionWait(long timeout, TimeUnit unit)
                throws InterruptedException {
            return admissionWaits.tryAcquire(timeout, unit);
        }

        public Snapshot snapshot() {
            TrackingExecutor current = executor;
            if (current == null) {
                return new Snapshot(Set.of(), 0, 0, 0,
                        scopes.get(), queues.get(),
                        lazyExecutors.get(), false, false);
            }
            return current.snapshot(
                    scopes.get(), queues.get(), lazyExecutors.get());
        }
    }

    public record Snapshot(Set<String> workerNames, int peakWorkers,
                           int liveWorkers, int submittedTasks,
                           int scopes, int queues,
                           int lazyExecutors, boolean shutdown,
                           boolean terminated) {

        public Snapshot {
            workerNames = Set.copyOf(workerNames);
        }
    }

    private static final class TrackingExecutor extends AbstractExecutorService {

        private final ExecutorService delegate;
        private final Set<String> workerNames = ConcurrentHashMap.newKeySet();
        private final AtomicInteger submittedTasks = new AtomicInteger();
        private final AtomicInteger activeWorkers = new AtomicInteger();
        private final AtomicInteger peakWorkers = new AtomicInteger();

        private TrackingExecutor(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            submittedTasks.incrementAndGet();
            try {
                delegate.execute(() -> {
                    workerNames.add(Thread.currentThread().getName());
                    int active = activeWorkers.incrementAndGet();
                    peakWorkers.accumulateAndGet(active, Math::max);
                    try {
                        command.run();
                    } finally {
                        activeWorkers.decrementAndGet();
                    }
                });
            } catch (RuntimeException | Error ex) {
                submittedTasks.decrementAndGet();
                throw ex;
            }
        }

        private Snapshot snapshot(int scopes, int queues, int lazyExecutors) {
            boolean terminated = delegate.isTerminated();
            return new Snapshot(workerNames, peakWorkers.get(),
                    terminated ? 0 : workerNames.size(), submittedTasks.get(),
                    scopes, queues,
                    lazyExecutors, delegate.isShutdown(), terminated);
        }
    }
}
