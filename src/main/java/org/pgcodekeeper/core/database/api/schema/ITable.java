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

import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.ISettings;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * Interface for database table
 */
public interface ITable extends IRelation, IStatementContainer {

    IColumn getColumn(String name);

    @Override
    default DbObjType getStatementType() {
        return DbObjType.TABLE;
    }

    Collection<IColumn> getColumns();

    Collection<IConstraint> getConstraints();

    /**
     * Creates a stream that includes the statement itself and its columns if it's a table.
     *
     * @param st the statement to process
     * @return a stream containing the statement and its columns (if applicable)
     */
    static Stream<? extends IStatement> columnAdder(IStatement st) {
        Stream<IStatement> newStream = Stream.of(st);
        if (st instanceof ITable table) {
            newStream = Stream.concat(newStream, table.getColumns().stream());
        }

        return newStream;
    }

    /**
     * Adds commands to the script for move data from the temporary table to the new table, given the identity columns,
     * and a command to delete the temporary table.
     */
    void appendMoveDataSql(IStatement newCondition, SQLScript script, String tblTmpBareName,
                                  List<String> identityCols);

    /**
     * Compares this table with the {@code newTable} to determine if a full table recreation is required.
     * A full recreation (DROP and CREATE) is needed when the tables differ in ways that cannot
     * be altered using ALTER TABLE statements.
     *
     * @param newTable the new table definition to compare against
     * @param settings application settings that may affect the comparison logic
     * @return {@code true} if the table requires recreation (DROP and CREATE) rather than
     * being alterable, {@code false} if the changes can be applied via ALTER TABLE
     *
     * @deprecated will be removed in a future version; use appendAlterSQL instead
     */
    @Deprecated(forRemoval = true)
    default boolean isRecreated(ITable newTable, ISettings settings) {
        var state = appendAlterSQL(newTable, new SQLScript(settings, getSeparator()));
        return ObjectState.RECREATE == state;
    }

    /**
     *
     * @param newTable new state of the table
     * @return true if the tables are identical
     */
    boolean compareIgnoringColumnOrder(ITable newTable);

    /**
     * Compares this state of the table, the one a migration would start from,
     * with the state that migration would produce, ignoring the differences
     * between their columns that the migration provably cannot express.
     * <p>
     * A difference no migration can express is not a difference a comparison
     * may report: it would name a change that no script can ever carry out.
     * Two of them exist, both about columns.
     * <ul>
     * <li>The order of the columns, when the settings ignore it. This is the
     * relaxation {@link #compareIgnoringColumnOrder(ITable)} makes.</li>
     * <li>A collation the target state does not name. pgCodeKeeper never emits
     * a collation reset, because it cannot tell a column declared without a
     * collation from one declared with the default collation of its type, so
     * the only collation it can write is the one the target names. This is the
     * default implementation of this interface, kept for the dialects whose
     * generator does act on such a difference; PostgreSQL overrides it.</li>
     * </ul>
     * The comparison is not symmetric: the receiver is the state the migration
     * starts from and {@code target} is the state it produces, the same roles
     * the generating methods give their arguments.
     *
     * @param target   the state the migration produces
     * @param settings settings of the comparison
     * @return true if the two states are equal up to those differences
     */
    default boolean compareIgnoringUnmigratableColumns(ITable target, ISettings settings) {
        return settings.isIgnoreColumnOrder() ? compareIgnoringColumnOrder(target) : compare(target);
    }

    /**
     * The parts of the body of this table that may name its columns as text
     * rather than as a reference the loader resolved.
     * <p>
     * A partition key, a Greenplum distribution key, a ClickHouse engine clause
     * and a computed column of MS SQL are all kept as the text they were written
     * as, so nothing points from them to the column they name and no dependency
     * of this table records them. Whoever must know which columns the table
     * cannot do without has to read them, see {@code ColumnVisibility}.
     * <p>
     * The clauses are returned as written, quoting and all. A reader may only
     * ask whether a name occurs in one of them, and must treat a chance
     * occurrence as an occurrence: the answer is used to keep a column, never to
     * drop one, so guessing wide is the harmless direction.
     *
     * @return the raw clauses, empty when the dialect keeps none
     */
    default Collection<String> getClausesNamingColumns() {
        return List.of();
    }

    /**
     * Whether a {@code CREATE} of this table can state a body with no columns at
     * all.
     * <p>
     * PostgreSQL can: {@code CREATE TABLE t ()} makes a table of no columns, and
     * a table of a composite type states only the columns it overrides and may
     * override none. Microsoft SQL and ClickHouse cannot - both write their
     * columns between parentheses that no server will parse empty.
     * <p>
     * Asked in one place, and only there: while an export decides which columns
     * a project file declares, see {@code ColumnVisibility#forProjectFile(List)}.
     * A rule that would leave such a table with no column at all leaves it whole
     * instead, because a project file of an empty body is a file the loader
     * cannot read back. No generator asks this - a script writes every column it
     * is given - so an empty body cannot arise anywhere else.
     * <p>
     * This is a fact about the dialect and not about the table: the answer is the
     * same for every table of a database, and it is asked here because this is
     * where a caller holding a table can reach it.
     *
     * @return true when a body of no columns is a body this dialect can write
     */
    default boolean canCreateWithoutColumns() {
        return true;
    }
}
