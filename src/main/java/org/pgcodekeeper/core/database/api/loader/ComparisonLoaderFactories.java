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

import java.util.Objects;

/**
 * Ordered OLD and NEW loader factories for one comparison.
 *
 * @param oldFactory factory for the logical OLD side
 * @param newFactory factory for the logical NEW side
 */
public record ComparisonLoaderFactories(ILoaderFactory oldFactory, ILoaderFactory newFactory) {

    public ComparisonLoaderFactories {
        Objects.requireNonNull(oldFactory, "oldFactory");
        Objects.requireNonNull(newFactory, "newFactory");
    }
}
