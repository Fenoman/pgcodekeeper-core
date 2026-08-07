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
package org.pgcodekeeper.core.database.api.launcher;

import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.monitor.IMonitor;
import java.util.List;
import java.util.Set;

/**
 * This class and all child classes contains statement, its contexts and
 * implementation of logic for launch the analysis of statement's contexts.
 */
public interface IAnalysisLauncher {

    /**
     * Returns the estimated number of parser-input bytes retained while this
     * launcher is analyzed. Launchers that already own a parser context do not
     * retain deferred parser input and keep the compatibility value of zero.
     *
     * @return nonnegative estimated UTF-8 parser-input size
     */
    default long getEstimatedParseBytes() {
        return 0;
    }

    IStatement getStmt();

    void updateStmt(IDatabase database);

    Set<ObjectReference> launchAnalyze(List<Object> errors, IMetaContainer meta);

    /**
     * Launches analysis with cooperative cancellation. Existing launchers that
     * only implement the compatibility method are checked immediately before
     * and after their analysis.
     *
     * @param errors  list to collect analysis errors
     * @param meta    metadata container for dependency resolution
     * @param monitor operation monitor
     * @return set of dependencies found
     * @throws InterruptedException if analysis is cancelled
     */
    default Set<ObjectReference> launchAnalyze(
            List<Object> errors, IMetaContainer meta, IMonitor monitor)
            throws InterruptedException {
        IMonitor.checkCancelled(monitor);
        Set<ObjectReference> dependencies = launchAnalyze(errors, meta);
        IMonitor.checkCancelled(monitor);
        return dependencies;
    }

    List<ObjectLocation> getReferences();

    /**
     * Declares whether the analysis result of this launcher will be read
     * through {@link #getReferences()}. A launcher told {@code false} must
     * leave that list empty: on the CLI diff path the owning database does not
     * index object references, so every offset-corrected copy this launcher
     * would build is allocated, retained for the whole analysis and then
     * dropped unread.
     *
     * @param collectReferences false when nobody will read the references
     */
    default void setCollectReferences(boolean collectReferences) {
        // Launchers that never build references have nothing to switch off.
    }

    String getSchemaName();

    /**
     * Releases the parser input this launcher retains for its analysis, which
     * will not run. A model whose analysis result is replayed from a cache
     * still owns the deferred routine body sources the structural load handed
     * to its launchers, and those must be closed as deterministically as a
     * real analysis closes them.
     */
    default void releaseWithoutAnalysis() {
        // Launchers that retain nothing beyond their parser context.
    }
}
