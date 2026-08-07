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
package org.pgcodekeeper.core.database.api.schema;

/**
 * Interface for database column
 */
public interface IColumn extends ISubElement {

    @Override
    default DbObjType getStatementType() {
        return DbObjType.COLUMN;
    }
    String getType();
    boolean isNotNull();

    /**
     * Reports whether this column owns an identity sequence.
     * <p>
     * Such a sequence is not an object of its own: its clauses are written
     * inside the definition of the column that owns it, and it comes and goes
     * with that definition. Asked by whoever has to tell one state of a column
     * from another without looking at the text - the cache of an identity
     * sequence is only ever left out of a migration while both states have one
     * to compare, see {@link org.pgcodekeeper.core.model.difftree.IgnoredValues}.
     *
     * @return true when the column is declared {@code GENERATED ... AS IDENTITY}
     * with a sequence of its own; false in every dialect that writes no cache
     * for one
     */
    default boolean hasIdentitySequence() {
        return false;
    }

    /**
     * Reports whether the whole difference between this state of a column and
     * the state a migration would produce is a collation that migration cannot
     * express.
     * <p>
     * Such a difference is dropped by the comparison itself, always and without
     * a setting to govern it, see {@code PgColumn.compareIgnoring}: the target
     * state names no collation, so the generator writes no collation clause and
     * there is nothing a script could carry. Asked by whoever has to tell a
     * reader of a comparison why two columns that plainly differ on the screen
     * are going to keep differing after the migration has run.
     * <p>
     * <b>The whole difference, and not a part of it.</b> A column whose type or
     * default differs as well is migrated, and that migration writes the line
     * this answer would have marked - so the answer is false there, and the only
     * mistake it is allowed to make is that one, in that direction.
     *
     * @param target the state the migration produces, which is the side whose
     *               silence about a collation decides this
     * @return true when a collation is all that differs and no script can carry
     * it; false in every dialect whose generator writes the collation of either
     * side
     */
    default boolean differsOnlyInUnmigratableCollation(IColumn target) {
        return false;
    }
}
