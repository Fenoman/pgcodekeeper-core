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

import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.settings.ISettings;

import java.io.IOException;
import java.util.List;

/**
 * Interface for database loader
 */
public interface ILoader extends AutoCloseable {

    /**
     * Loads the database schema.
     *
     * @return loaded database
     */
    IDatabase load() throws IOException, InterruptedException;

    /**
     * Pre loads the required items, do nothing by default
     */
    default void preLoad() throws IOException, InterruptedException {}

    /**
     * Registers optional operation-local endpoints used only when this loader
     * participates in the coordinated factory comparison API. The supplied
     * facade is permanently bound to this loader's logical side and is sealed
     * when this method returns. Ordinary loaders inherit the compatibility
     * no-op.
     *
     * @param context side-bound extension registration facade
     */
    default void registerComparisonExtensions(ComparisonExtensionContext context)
            throws IOException, InterruptedException {
        // compatibility no-op
    }

    /**
     * Requests best-effort cancellation of active work owned directly by this
     * loader. Returning from this method does not by itself guarantee that all
     * delegated work has terminated. Implementations that do not support
     * external cancellation retain the compatibility no-op.
     *
     * @throws IOException if requesting cancellation fails
     */
    default void cancel() throws IOException {
        // compatibility no-op
    }

    /**
     * Releases resources owned by this loader after its active load has
     * terminated. Callers must not close a loader concurrently with
     * {@link #load()} or {@link #loadAndAnalyze()}; request cancellation and join
     * the owner first. A loader may also be closed immediately after construction,
     * before {@link #preLoad()} or {@link #load()}, when factory validation rejects
     * an unpublished product. Implementations that do not retain closeable state
     * inherit the compatibility no-op.
     *
     * @throws IOException if resource cleanup fails
     */
    @Override
    default void close() throws IOException {
        // compatibility no-op
    }

    /**
     * Loads the database schema and runs full expression analysis.
     *
     * @return loaded and fully analyzed database
     */
    IDatabase loadAndAnalyze() throws IOException, InterruptedException;

    /**
     * Loads the database schema and analyzes it against an external metadata
     * context. Implementations that cannot preserve this context reject the
     * operation explicitly so callers can safely fall back to a full load.
     *
     * @param metadata external metadata context
     * @return loaded and fully analyzed database
     */
    default IDatabase loadAndAnalyze(IMetaContainer metadata)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException(
                "External metadata analysis is not supported");
    }

    /**
     * @return previously loaded database, or null if {@link #load()} has not been called
     */
    IDatabase getDatabase();

    /**
     * @return name identifying the database source (database name, file name, or project directory name)
     */
    String getDatabaseName();

    /**
     * @return configuration settings
     */
    ISettings getSettings();

    /**
     * @return unmodifiable list of errors during loading
     */
    List<Object> getErrors();
}
