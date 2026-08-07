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
package org.pgcodekeeper.core.database.api.loader;

import java.io.IOException;

import org.pgcodekeeper.core.database.api.schema.IDatabase;

/**
 * One negotiated, operation-local extension shared by a pair of comparison
 * loaders.
 * <p>
 * The coordinator invokes successful lifecycle callbacks in logical binding
 * order. Failure cleanup is best effort: every binding is notified even when an
 * earlier binding throws. Implementations must tolerate {@link #cancel()} and
 * {@link #close()} after a partially completed {@link #activate()} sequence.
 * OLD and NEW callbacks may execute concurrently, and {@link #cancel()} may
 * overlap a callback on the peer side. These methods must therefore be
 * thread-safe and non-blocking. In particular, {@link #sideLoaded} must publish
 * availability rather than wait for the peer. {@link #close()} runs only after
 * all loader owners have joined and never concurrently with another callback.
 */
public interface IComparisonExtensionBinding extends AutoCloseable {

    /**
     * Activates this binding after every complete endpoint pair has been bound.
     */
    default void activate() throws IOException, InterruptedException {
        // compatibility no-op
    }

    /**
     * Reports successful structural loading for one logical side.
     *
     * @param side logical side that completed
     * @param database structurally loaded database
     */
    default void sideLoaded(ComparisonSide side, IDatabase database)
            throws IOException, InterruptedException {
        // compatibility no-op
    }

    /**
     * Reports the original failure from one logical side.
     *
     * @param side logical side that failed
     * @param failure original side failure
     */
    default void sideFailed(ComparisonSide side, Throwable failure) throws IOException {
        // compatibility no-op
    }

    /**
     * Requests one-shot best-effort cancellation of extension work.
     */
    default void cancel() throws IOException {
        // compatibility no-op
    }

    /**
     * Releases extension resources after all loader owners have terminated.
     */
    @Override
    default void close() throws IOException {
        // compatibility no-op
    }
}
