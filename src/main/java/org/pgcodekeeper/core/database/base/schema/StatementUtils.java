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
package org.pgcodekeeper.core.database.base.schema;

import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Utility class providing common functionality for database statement operations.
 * Contains helper methods for column ordering, SQL generation, and option handling
 * across different database types.
 */
public final class StatementUtils {

    private static final Comparator<IColumn> DISPLAY_ORDER =
            Comparator.comparing(IColumn::getName, Comparator.nullsFirst(Utils::compareByCodePoints));

    /**
     * Returns the columns a project file of a table holds, in the order they are
     * written in.
     * <p>
     * A column an ignore list hides is not pgCodeKeeper's to write, so it does
     * not enter a project file, see {@link ColumnVisibility#forProjectFile(List)}.
     * This is the only answer of that kind anywhere: a migration script writes
     * every column it is given and asks the rules nothing, because the truth of
     * a script is the project and the truth of a project is decided here. What
     * the project already declares stays the project's, though: a hidden column
     * it holds is kept with the definition the project gives it, whether the
     * database still holds that column or not. The database disagreeing about a
     * column it was told not to manage says nothing about that column.
     * <p>
     * The columns the migration manages keep the order and the state of the
     * database, as every other property of the table does.
     * <p>
     * Every dialect owns its columns in a list of its own and its own table class
     * answers {@code adoptUnmanaged} out of it, so the reasoning above lives here
     * rather than three times over.
     *
     * @param database the columns of the state of the table the database holds
     * @param project  the columns of the state the project holds, {@code null}
     *                 when the project holds no such table
     * @param managed  the rules, already bound to both states of the table
     * @param <T>      the column type of the dialect
     * @return the list of the database itself when the rules leave every column
     * alone, a new list otherwise
     */
    public static <T extends IColumn> List<T> columnsForProjectFile(List<T> database, List<T> project,
                                                                    ColumnVisibility managed) {
        if (!managed.hidesAnything()) {
            return database;
        }

        // the columns of the database the project is to declare, refusal and all
        List<T> managedColumns = managed.forProjectFile(database);
        if (managedColumns == database && project == null) {
            return database;
        }
        Set<String> managedNames = new HashSet<>();
        managedColumns.forEach(column -> managedNames.add(column.getName()));

        List<T> forProject = new ArrayList<>(database.size());
        boolean changed = false;
        for (T column : database) {
            if (managedNames.contains(column.getName())) {
                forProject.add(column);
                continue;
            }
            T inProject = findColumn(project, column.getName());
            if (inProject == null) {
                changed = true;
            } else {
                forProject.add(inProject);
                changed |= !inProject.equals(column);
            }
        }

        changed |= keepHiddenColumnsOnlyTheProjectHolds(database, project, managed, forProject);
        return changed ? forProject : database;
    }

    /**
     * Puts back every hidden column the project declares while the state of the
     * database does not hold it at all.
     * <p>
     * Each keeps the neighbour it has in the project: it is written right after
     * the nearest column that precedes it there and is one of the columns already
     * collected. The position is of no consequence to a comparison - a hidden
     * column takes no part in one - and this is the position that leaves the file
     * of the project as it was.
     *
     * @return true if anything was put back
     */
    private static <T extends IColumn> boolean keepHiddenColumnsOnlyTheProjectHolds(
            List<T> database, List<T> project, ColumnVisibility managed, List<T> forProject) {
        if (project == null) {
            return false;
        }

        boolean kept = false;
        for (int i = 0; i < project.size(); i++) {
            T column = project.get(i);
            if (findColumn(database, column.getName()) != null || !managed.isHidden(column)) {
                continue;
            }
            forProject.add(positionAfterPredecessor(project, i, forProject), column);
            kept = true;
        }
        return kept;
    }

    /**
     * Where a column of the project belongs among the columns already collected:
     * right after the nearest column preceding it in the project that is one of
     * them, or at the front when none of them is.
     */
    private static <T extends IColumn> int positionAfterPredecessor(List<T> project, int at, List<T> forProject) {
        for (int i = at - 1; i >= 0; i--) {
            String name = project.get(i).getName();
            for (int j = 0; j < forProject.size(); j++) {
                if (forProject.get(j).getName().equals(name)) {
                    return j + 1;
                }
            }
        }
        return 0;
    }

    private static <T extends IColumn> T findColumn(List<T> columns, String name) {
        if (columns == null) {
            return null;
        }
        for (T column : columns) {
            if (column.getName().equals(name)) {
                return column;
            }
        }
        return null;
    }

    /**
     * Returns the columns of a table in the order they are to be written in.
     * <p>
     * That is the order they are stored in, which is the order of the table, and
     * only a rendering meant for a human eye may replace it with the order of
     * their names. See {@link ISettings#isSortColumnsForDisplay()} for why, and
     * for the promise that no migration script is ever rendered this way.
     *
     * @param columns  the columns of a table in their stored order
     * @param settings settings of the rendering
     * @return the given list itself, or a name-ordered copy of it
     */
    public static <T extends IColumn> List<T> orderColumnsForWriting(List<T> columns, ISettings settings) {
        if (columns.size() < 2 || !settings.isSortColumnsForDisplay()) {
            return columns;
        }

        List<T> ordered = new ArrayList<>(columns);
        ordered.sort(DISPLAY_ORDER);
        return ordered;
    }

    /**
     * Checks if the order of the table columns has changed.
     *
     * <b>Example:</b>
     * <p>
     * original columns : c1, c2, c3<br>
     * new columns      : c2, c3, c1
     * <p>
     * Column c1 was moved to last index and method will return true
     *
     * <b>Example:</b>
     * <p>
     * original columns : c1, c2, c3<br>
     * new columns      : c2, c3, c4
     * <p>
     * Column c1 was deleted and column c4 was added. Method will return false.
     *
     * <b>Example:</b>
     * <p>
     * original columns : c1, c2, c3<br>
     * new columns      : c1, c4, c2, c3
     * <p>
     * Column c4 was added between old columns: c1 and c2. Method will return true.
     *
     * <b>Example:</b>
     * <p>
     * original columns : c2, c3, inherit(some table)<br>
     * new columns      : c1, c2, c3
     * <p>
     * Some table is no longer inherited. If table did not have a column c1,
     * we must return true, but we cannot track this right now. Method will return false.
     *
     * @param newColumns new columns
     * @param oldColumns old columns
     * @return true if order was changed or order is ignored
     * @since 5.1.7
     */
    public static boolean isColumnsOrderChanged(List<? extends IColumn> newColumns,
                                                List<? extends IColumn> oldColumns) {
        // last founded column
        int i = -1;
        for (IColumn col : newColumns) {
            // old column index
            int index = 0;
            // search old column index by new column name
            for (; index < oldColumns.size(); index++) {
                if (col.getName().equals(oldColumns.get(index).getName())) {
                    break;
                }
            }

            if (index == oldColumns.size()) {
                // New column was not found in original table.
                // After this column can be only new columns.
                i = Integer.MAX_VALUE;
            } else if (index < i) {
                // New column was found in original table
                // but one of previous columns was not found
                // or was located on more later index
                return true;
            } else {
                // New column was found in original table.
                // Safe index of column in original table.
                i = index;
            }
        }

        return false;
    }

    /**
     * Appends column names to a StringBuilder with proper quoting for the database type.
     * Appends nothing at all for an empty collection: a constraint can legitimately reach
     * this call with no known columns (a primary key or unique constraint that adopts an
     * existing index instead of listing its own), and the unconditional variant used to
     * assume a trailing ", " to trim, cutting into whatever the caller had written before
     * the opening paren instead and leaving unbalanced, unparseable output.
     *
     * @param sbSQL the StringBuilder to append to
     * @param cols the collection of column names
     * @param quoter quoting operator
     */
    public static void appendCols(StringBuilder sbSQL, Collection<String> cols, UnaryOperator<String> quoter) {
        if (cols.isEmpty()) {
            return;
        }
        sbSQL.append('(');
        for (var col : cols) {
            sbSQL.append(quoter.apply(col));
            sbSQL.append(", ");
        }
        sbSQL.setLength(sbSQL.length() - 2);
        sbSQL.append(')');
    }

    /**
     * Appends options to a StringBuilder enclosed in parentheses.
     *
     * @param sbSQL the StringBuilder to append to
     * @param options the map of options to append
     * @param delimiter option/value delimiter
     */
    public static void appendOptionsWithParen(StringBuilder sbSQL, Map<String, String> options, String delimiter) {
        sbSQL.append(" (");
        appendOptions(sbSQL, options, delimiter);
        sbSQL.append(')');
    }

    /**
     * Appends a collection of strings to a StringBuilder with a specified delimiter.
     *
     * @param sbSQL the StringBuilder to append to
     * @param collection the collection of strings to append
     * @param delimiter the delimiter to use between elements
     * @param needParens whether to enclose the result in parentheses
     */
    public static void appendCollection(StringBuilder sbSQL, Collection<String> collection,
                                        String delimiter, boolean needParens) {
        if (collection.isEmpty()) {
            return;
        }

        if (needParens) {
            sbSQL.append(" (");
        }
        for (var element : collection) {
            sbSQL.append(element).append(delimiter);
        }
        sbSQL.setLength(sbSQL.length() - delimiter.length());
        if (needParens) {
            sbSQL.append(')');
        }
    }

    /**
     * Appends parameters/options at StringBuilder. This StringBuilder used in
     * schema package Constraint's classes in the method getDefinition()
     *
     * @param sbSQL      the StringBuilder from method getDefinition()
     * @param options    the Map&lt;String, String&gt; where key is parameter/option and
     *                   value is value of this parameter/option
     * @param delimiter  option/value delimiter
     */
    public static void appendOptions(StringBuilder sbSQL, Map<String, String> options, String delimiter) {
        for (var option : options.entrySet()) {
            sbSQL.append(option.getKey());
            var value = option.getValue();
            if (value != null && !value.isEmpty()) {
                sbSQL.append(delimiter).append(value);
            }
            sbSQL.append(", ");
        }
        sbSQL.setLength(sbSQL.length() - 2);
    }

    /**
     * Gets the full bare name of a statement by concatenating parent names.
     * Returns a dot-delimited path from the top-level container down to the statement,
     * excluding the database level.
     *
     * @param st the statement to get the full bare name for
     * @return the full bare name path (e.g., "schema.table.column")
     */
    public static String getFullBareName(IStatement st) {
        StringBuilder sb = new StringBuilder(st.getBareName());
        var par = st.getParent();
        while (par != null && !(par instanceof IDatabase)) {
            sb.insert(0, '.').insert(0, par.getBareName());
            par = par.getParent();
        }

        return sb.toString();
    }

    private StatementUtils() {
    }
}
