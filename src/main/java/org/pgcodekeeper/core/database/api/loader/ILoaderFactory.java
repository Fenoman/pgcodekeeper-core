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

import org.pgcodekeeper.core.settings.ISettings;

/**
 * Factory for a loader participating in a coordinated comparison.
 * <p>
 * A factory owns every loader it constructs until {@link #create(ISettings)}
 * returns it successfully. If creation fails after allocating a resource-owning
 * loader, the implementation must close that unpublished partial product because
 * the caller cannot observe or clean it up.
 */
@FunctionalInterface
public interface ILoaderFactory {

    /**
     * Creates a loader using exactly the supplied side-local settings instance.
     * Ownership transfers to the caller only after this method returns normally.
     *
     * @param settings side-local settings for the new loader
     * @return constructed loader, never {@code null}
     */
    ILoader create(ISettings settings) throws IOException, InterruptedException;

    /**
     * Contributes configuration shared by both comparison sides before either
     * loader is constructed. Ordinary dump and JDBC factories contribute nothing.
     *
     * @param settings common settings receiving the contribution
     */
    default void contributeCommonConfiguration(ISettings settings)
            throws IOException, InterruptedException {
        // compatibility no-op
    }
}
