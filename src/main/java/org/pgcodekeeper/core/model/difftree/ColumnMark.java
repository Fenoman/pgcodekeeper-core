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

import org.pgcodekeeper.core.database.api.schema.IColumn;

/**
 * What a {@code type=COLUMN} rule of a comparison did to one column, for telling
 * whoever reads that comparison.
 * <p>
 * Three answers rather than two, and the third is the point. A rule that names a
 * column does not always hide it: the column stays whenever anything still needs
 * it, see {@link ColumnVisibility}. Told only which columns are hidden, a reader
 * of a table where one audit column is missing and the next one is not has no way
 * to tell a rule that applied from a rule that never fired, and both look exactly
 * like a column somebody forgot. The answer is computed either way, so it is worth
 * saying out loud.
 *
 * @see ColumnVisibility#markOf(IColumn)
 * @see SqlMarkup for finding what a rendering says about such a column
 * @see SqlMark for how these three reach a reader together with the values a
 * comparison overlooks
 */
public enum ColumnMark {

    /**
     * No rule names this column, or none that can hide one. It takes part in the
     * comparison and enters the project files like any other column, and there is
     * nothing to tell anybody about it.
     */
    MANAGED,

    /**
     * A rule names this column and nothing needs it, so pgCodeKeeper stops
     * managing it: it leaves both states of the comparison, no migration script
     * ever names it, and it is left out of the project file of its table together
     * with everything written about it.
     */
    HIDDEN,

    /**
     * A rule names this column, but an index, a constraint, a view, a policy, an
     * {@code OWNED BY} or a statistics object still needs it, so it stays managed
     * exactly as if no rule mentioned it.
     * <p>
     * A project relieved of such a column would create a database that does not
     * work, which is why the rule steps aside here, see
     * {@link ColumnVisibility#pinnedColumns(org.pgcodekeeper.core.database.api.schema.ITable)}
     * for what holds each of them.
     */
    PINNED
}
