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

import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Result of loading both comparison sides once. The databases are loaded to
 * the requested {@link ComparisonDepth} and read-only afterwards, so they can
 * be diffed repeatedly with different post-load settings (ignore lists, script
 * generation flags) without reloading.
 *
 * @param oldDatabase        the loaded old (target) database model
 * @param newDatabase        the loaded new (source) database model
 * @param comparisonSettings the final comparison settings: a copy of the
 *                           caller settings with factory contributions applied,
 *                           the detected server version published and load
 *                           diagnostics merged
 * @param depth              how deep this load went: a
 *                           {@link ComparisonDepth#STRUCTURAL_ONLY} model carries
 *                           no dependencies whatsoever and must never be scripted,
 *                           while a {@link ComparisonDepth#FULL} one ran the
 *                           analysis phase - necessary for a script, and not by
 *                           itself a promise that every dependency was resolved;
 *                           see {@link ComparisonDepth} for the setting that
 *                           leaves part of a full load unanalyzed
 */
public record LoadedComparison(
        IDatabase oldDatabase,
        IDatabase newDatabase,
        ISettings comparisonSettings,
        ComparisonDepth depth) {

    public LoadedComparison {
        Objects.requireNonNull(oldDatabase, "oldDatabase");
        Objects.requireNonNull(newDatabase, "newDatabase");
        Objects.requireNonNull(comparisonSettings, "comparisonSettings");
        Objects.requireNonNull(depth, "depth");
    }
}
