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
package org.pgcodekeeper.core.database.api.script;

import java.io.IOException;
import java.util.function.Supplier;

import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.graph.DepcyResolver.DepcyGraphs;

/**
 * Interface for script builder
 */
public interface IScriptBuilder {

    /**
     * Gets selected elements from root, compares them between source and target
     * and generates a migration script.
     *
     * @param root  the root of the diff tree
     * @param oldDb the source database schema
     * @param newDb the target database schema
     * @return SQL migration script
     * @throws IOException if an I/O error occurs
     */
    public String createScript(TreeElement root, IDatabase oldDb, IDatabase newDb) throws IOException;

    /**
     * Builds a script like {@link #createScript(TreeElement, IDatabase, IDatabase)}, reusing a
     * pair of dependency graphs the caller already built for these two models.
     * <p>
     * A dependency graph is a function of its database model alone, so a caller that scripts
     * one loaded comparison several times over - with different post-load settings each time -
     * can build the pair once and pass it to every call. The default implementation ignores
     * the offer and builds its own, which costs time but never correctness.
     *
     * @param sharedGraphs source of graphs built from these same two models, consulted only if
     *                     graphs are needed at all, or {@code null} to build a fresh pair
     * @return SQL migration script
     * @throws IOException if an I/O error occurs
     */
    default String createScript(TreeElement root, IDatabase oldDb, IDatabase newDb,
                                Supplier<DepcyGraphs> sharedGraphs) throws IOException {
        return createScript(root, oldDb, newDb);
    }
}
