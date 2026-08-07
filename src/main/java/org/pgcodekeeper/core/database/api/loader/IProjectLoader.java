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
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.pgcodekeeper.core.database.api.schema.IDatabase;

/**
 * Interface for project loader
 */
public interface IProjectLoader extends ILoader {

    /**
     * Loads the given project files in isolation, without reading the whole
     * project.
     *
     * @param files project files to parse
     * @return database populated from the given files only
     */
    IDatabase loadFiles(Collection<Path> files) throws IOException, InterruptedException;

    /**
     * Acknowledges that common project configuration was contributed before
     * loader construction. The factory owner calls this on its owner thread
     * before publishing the loader. Implementations must make repeated calls
     * idempotent.
     * <p>
     * The default rejects factory use explicitly so older third-party project
     * loaders cannot silently scan shared settings from a worker thread.
     */
    default void markCommonConfigurationContributed() {
        throw new UnsupportedOperationException(
                "Project loader does not support common configuration acknowledgment");
    }

    /**
     * Enumerates the local SQL files that this loader would dispatch for the
     * project and its overrides, applying the same layout, schema and
     * root-relative file filters as a real load. External libraries are not
     * included.
     *
     * @return immutable paths in loader dispatch order
     * @throws IOException if project configuration or directories cannot be read
     * @throws InterruptedException if enumeration is cancelled
     */
    default List<Path> listInputFiles() throws IOException, InterruptedException {
        throw new UnsupportedOperationException(
                "Project loader does not support input enumeration");
    }
}
