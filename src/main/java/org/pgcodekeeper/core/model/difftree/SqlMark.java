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

/**
 * What a comparison has to say about one stretch of a rendering it shows, for
 * telling whoever reads that rendering.
 * <p>
 * Three of the four say the same thing about their stretch - the comparison does
 * not manage it, and no migration script will carry it - and differ only in what
 * is not managed and what becomes of it. The remaining one says the opposite, and
 * exists because saying nothing would be read as the first: a table where one
 * audit column is marked and the next one is not raises the question of why, and
 * the answer is already known.
 * <p>
 * <b>Why the last two are not one mark.</b> Both stand beside a line no script
 * will carry, and there the likeness ends. A value the settings overlook is
 * overlooked because an operator said so, its difference is carried by whoever
 * owns the target database, and turning the setting off brings it back into the
 * comparison tomorrow. A collation the migration cannot express is not a choice
 * of anybody's and has no tomorrow: no setting governs it, no script of this
 * tool or any later one will write it, and the two databases will still differ
 * in it when everything else has long since converged. A reader told only "not
 * in the comparison" would file both under the same shrug, and would be wrong
 * about the one that matters.
 *
 * @see SqlMarkup for finding these stretches in a rendering
 * @see ColumnMark for the verdict on one column that the first two come from
 */
public enum SqlMark {

    /**
     * A {@code type=COLUMN} rule names this column and nothing needs it, so it
     * leaves the comparison altogether and is left out of the project file of
     * its table together with everything written about it.
     */
    COLUMN_LEAVING,

    /**
     * A {@code type=COLUMN} rule names this column, but an index, a key, a view
     * or a policy still needs it, so it is compared and written exactly as if no
     * rule mentioned it, see {@link ColumnMark#PINNED}.
     */
    COLUMN_KEPT,

    /**
     * A value the settings of the comparison tell it to overlook - the cache of
     * a sequence, the statistics target of a column - and that it therefore
     * never migrates, see {@link IgnoredValues}.
     * <p>
     * The value itself stays: it is written to the project files and to any
     * database created from them, and what the rendering shows is what those
     * will get. It is the <em>difference</em> between the two states that is
     * dropped.
     */
    VALUE_IGNORED,

    /**
     * A difference no migration script can express, so that it survives this
     * migration and every migration after it: the collation of a column that the
     * state a migration produces does not name, see
     * {@code PgColumn.compareIgnoring}.
     * <p>
     * No setting governs this and none can. A column declared without a
     * collation and a column declared with the default collation of its type are
     * the same thing to this tool, so a target state that names no collation is
     * not asking for one and the generator writes no clause to reach it. The
     * difference is therefore permanent, which is exactly what a reader looking
     * at two sides that plainly differ needs to be told, and exactly what
     * {@link #VALUE_IGNORED} would be lying about if it were used here.
     */
    VALUE_UNMIGRATABLE;

    /**
     * Reports whether this mark says its stretch takes no part in the
     * comparison, which is what a reader has to be told in words rather than in
     * colour alone.
     *
     * @return true for everything the comparison overlooks, false for a column
     * it manages after all
     */
    public boolean ignored() {
        return this != COLUMN_KEPT;
    }

    /**
     * The mark that carries the verdict on one column.
     *
     * @param mark what the rules did to the column, see
     *             {@link ColumnVisibility#markOf}
     * @return the mark of every stretch of the rendering that speaks about it
     * @throws IllegalArgumentException for {@link ColumnMark#MANAGED}, which is
     *                                  the answer for a column no rule names and
     *                                  is therefore nothing to tell anybody
     */
    public static SqlMark of(ColumnMark mark) {
        return switch (mark) {
            case HIDDEN -> COLUMN_LEAVING;
            case PINNED -> COLUMN_KEPT;
            case MANAGED -> throw new IllegalArgumentException(
                    "a column no rule names is not marked: " + mark);
        };
    }
}
