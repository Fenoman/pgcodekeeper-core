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
package org.pgcodekeeper.core.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pgcodekeeper.core.monitor.IMonitor;

/**
 * Comparison-wide cancellation state shared by the two side-local monitors.
 */
final class ComparisonCancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    void cancel() {
        cancelled.set(true);
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    IMonitor wrap(IMonitor delegate) {
        return new ComparisonMonitor(Objects.requireNonNull(delegate, "delegate"), this);
    }

    private static final class ComparisonMonitor implements IMonitor {

        private final IMonitor delegate;
        private final ComparisonCancellationToken token;

        private ComparisonMonitor(IMonitor delegate, ComparisonCancellationToken token) {
            this.delegate = delegate;
            this.token = token;
        }

        @Override
        public void setCancelled(boolean value) {
            if (value) {
                token.cancel();
            }
            delegate.setCancelled(value);
        }

        @Override
        public boolean isCancelled() {
            return token.isCancelled() || delegate.isCancelled();
        }

        @Override
        public void worked(int work) {
            delegate.worked(work);
        }

        @Override
        public IMonitor createSubMonitor() {
            return token.wrap(delegate.createSubMonitor());
        }

        @Override
        public void setWorkRemaining(int remaining) {
            delegate.setWorkRemaining(remaining);
        }

        @Override
        public void setTaskName(String name) {
            delegate.setTaskName(name);
        }
    }
}
