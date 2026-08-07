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

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Creates the dedicated thread-pool object only when the first task runs. */
final class LazyExecutorService extends AbstractExecutorService {

    private final Supplier<ExecutorService> factory;
    private ExecutorService delegate;
    private boolean shutdown;

    LazyExecutorService(Supplier<ExecutorService> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
        if (delegate != null) {
            delegate.shutdown();
        }
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        return delegate == null ? Collections.emptyList() : delegate.shutdownNow();
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown && (delegate == null || delegate.isTerminated());
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        ExecutorService current;
        synchronized (this) {
            if (delegate == null) {
                return shutdown;
            }
            current = delegate;
        }
        return current.awaitTermination(timeout, unit);
    }

    @Override
    public synchronized void execute(Runnable command) {
        if (shutdown) {
            throw new RejectedExecutionException("Executor already shut down");
        }
        if (delegate == null) {
            delegate = factory.get();
        }
        delegate.execute(command);
    }
}
