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
package org.pgcodekeeper.core.model.difftree;

import org.pgcodekeeper.core.database.api.schema.DbObjType;

/**
 * The three properties every ignore rule is matched against: the name of an
 * object, its qualified name and its type.
 * <p>
 * A node of a diff tree has them, and so has an object that never became a node
 * of one - the comparison pane renders the children of a container straight from
 * the loaded model. Both must be hidden by exactly the same rules, so both reach
 * {@link IgnoreListFilter} through this view and the rules stay read in one
 * place.
 * <p>
 * {@link TreeElement} implements it directly, so the passes that build and
 * flatten a tree pay nothing for the indirection.
 */
interface IgnoreRuleTarget {

    /**
     * @return the bare object name a rule without {@code QUALIFIED} matches
     */
    String getName();

    /**
     * @return the dot-delimited name up to, but not including, the database, as
     * {@link TreeElement#getQualifiedName()} builds it: a rule with
     * {@code QUALIFIED} matches this one
     */
    String getQualifiedName();

    /**
     * @return the object type a {@code type=} rule attribute matches
     */
    DbObjType getType();
}
