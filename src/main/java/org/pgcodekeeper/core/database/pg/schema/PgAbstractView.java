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
 **
 * Copyright 2006 StartNet s.r.o.
 *
 * Distributed under MIT license
 *******************************************************************************/
package org.pgcodekeeper.core.database.pg.schema;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Stream;

import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.Pair;

/**
 * Base PostgreSQL view implementation.
 * Provides common functionality for PostgreSQL views including query handling,
 * column comments, view options, and default values management.
 *
 * @author fordfrog
 */
public abstract class PgAbstractView extends PgAbstractStatementContainer implements IView, ISimpleOptionContainer {

    public static final String CHECK_OPTION = "check_option";

    protected static final String ALTER_COLUMN = " ALTER COLUMN ";

    private static final String COLUMN_COMMENT = "COMMENT ON COLUMN %s.%s IS %s";

    protected final Map<String, String> options = new LinkedHashMap<>();

    protected String query;

    private final Map<String, String> columnComments = new LinkedHashMap<>();
    private final List<String> columnNames = new ArrayList<>();

    private String normalizedQuery;

    /**
     * Catalog-fed column names and {@code format_type} spellings of a
     * JDBC-loaded view, in {@code attnum} order and without the dropped ones.
     * A project-side view leaves this {@code null}, which is what routes it
     * through the sequential analysis-driven initialization instead.
     * <p>
     * Besides feeding expression analysis through
     * {@link #getRelationColumns()}, the names answer the one question a
     * JDBC-loaded view cannot answer for itself - what its column list would
     * have said, see {@link #isSameColumnNames}. The types take no part in any
     * comparison.
     */
    private List<Pair<String, String>> relationColumns;

    protected PgAbstractView(String name) {
        super(name);
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        final StringBuilder sbSQL = new StringBuilder(query.length() * 2);

        sbSQL.append("CREATE ").append(getTypeName()).append(' ');
        appendIfNotExists(sbSQL, script.getSettings());
        sbSQL.append(getQualifiedName());
        appendColumnNames(sbSQL);
        appendOptions(sbSQL);

        script.addStatement(sbSQL);
        appendOwnerSQL(script);
        appendPrivileges(script);
        appendDefaultValues(script);
        appendComments(script);
    }

    private void appendColumnNames(final StringBuilder sbSQL) {
        if (columnNames.isEmpty()) {
            return;
        }

        sbSQL.append(" (");
        for (String columnName : columnNames) {
            sbSQL.append(quote(columnName)).append(", ");
        }
        sbSQL.setLength(sbSQL.length() - 2);
        sbSQL.append(')');
    }

    protected void appendOptions(StringBuilder sbSQL) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (!CHECK_OPTION.equals(entry.getKey())) {
                sb.append(entry.getKey());
                if (!entry.getValue().isEmpty()) {
                    sb.append("=").append(entry.getValue());
                }
                sb.append(", ");
            }
        }

        if (!sb.isEmpty()) {
            sb.setLength(sb.length() - 2);
            sbSQL.append("\nWITH (").append(sb).append(")");
        }
    }

    protected void appendDefaultValues(SQLScript script) {
        // noimpl
    }

    @Override
    public void appendComments(SQLScript script) {
        super.appendComments(script);
        appendChildrenComments(script);
    }

    private void appendChildrenComments(SQLScript script) {
        for (final Entry<String, String> columnComment : columnComments.entrySet()) {
            script.addCommentStatement(COLUMN_COMMENT.formatted(getQualifiedName(),
                    quote(columnComment.getKey()), columnComment.getValue()));
        }
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        PgAbstractView newAbstractView = (PgAbstractView) newCondition;
        if (needDrop(newAbstractView, script.getSettings())) {
            return ObjectState.RECREATE;
        }

        int startSize = script.getSize();
        alterViewOptions(script, newAbstractView);

        appendAlterOwner(newAbstractView, script);
        alterPrivileges(newAbstractView, script);
        compareOptions(newAbstractView, script);
        appendAlterComments(newAbstractView, script);

        return getObjectState(script, startSize);
    }

    /**
     * Returns true if either column names or query of the view has been
     * modified.
     * <p>
     * Both, and unconditionally: a view is its query, and nothing else here
     * writes that query out. {@link #appendAlterSQL} carries default values,
     * owner, privileges, options and comments, so a query this method lets
     * through leaves no statement behind at all - the script comes out empty
     * while the diff tree, whose {@link #compare} does read the normalized
     * query, still shows the view as changed. The query used to be compared
     * only when neither side spelled its column names out, which made the
     * explicit column list of a hand-written view enough to lose a rewrite of
     * its body without a word.
     * <p>
     * The answer is a recreate rather than a {@code CREATE OR REPLACE VIEW},
     * for the same reason it already was one for a view with no column list:
     * the server accepts a replacement only when the new query yields the same
     * columns, of the same types, in the same order, and this comparison knows
     * the query as text - it cannot promise that. A materialized view has no
     * replacement form at all, and this method serves both.
     *
     * @param newView new view
     * @param settings the settings to use for SQL generation and formatting 
     * @return true if view has been modified, otherwise false
     */
    protected boolean needDrop(final PgAbstractView newView, ISettings settings) {
        if (getClass() != newView.getClass()) {
            return true;
        }

        if (!Objects.equals(normalizedQuery, newView.normalizedQuery)) {
            return true;
        }

        return !isSameColumnNames(newView);
    }

    /**
     * Reports whether the two views name their columns the same way.
     * <p>
     * The list of {@code CREATE VIEW v (a, b) AS ...} names the output columns
     * of the query and does nothing else: the server keeps the names on the
     * columns and {@code pg_get_viewdef} writes them back as aliases inside the
     * query. So a view read over JDBC never carries a list of its own, and the
     * two sides used to be compared text against text - a hand-written view
     * with an explicit column list was recreated on every run, its dependants
     * with it, measured on PostgreSQL 17.10 against a file whose query text was
     * already exactly what the catalog prints.
     * <p>
     * What the database side does carry is {@link #relationColumns}, the names
     * the columns actually have. A list that names those very columns, in that
     * very order, would produce the view that is already there - so it is not a
     * difference. That is the only relaxation: two project files, neither of
     * which knows the catalog's columns, are still compared list against list,
     * and so is a list that names anything else.
     * <p>
     * The query is asked separately and always, by both callers. Without it
     * this would be far too generous: a project list may well rename what the
     * query returns, and then the query texts differ and the view is recreated
     * for that.
     */
    private boolean isSameColumnNames(PgAbstractView view) {
        return columnNames.equals(view.columnNames)
                || namesTheseColumns(view) || view.namesTheseColumns(this);
    }

    /**
     * Reports whether this view - the JDBC-loaded one - has exactly the columns
     * the other one's list names.
     *
     * @param project the side that spells a column list out
     */
    private boolean namesTheseColumns(PgAbstractView project) {
        if (relationColumns == null || !columnNames.isEmpty()
                || project.columnNames.size() != relationColumns.size()) {
            return false;
        }

        for (int i = 0; i < relationColumns.size(); i++) {
            if (!relationColumns.get(i).getFirst().equals(project.columnNames.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void appendAlterComments(AbstractStatement newObj, SQLScript script) {
        PgAbstractView newView = (PgAbstractView) newObj;
        super.appendAlterComments(newView, script);
        appendAlterChildrenComments(newObj, script);
    }

    private void appendAlterChildrenComments(AbstractStatement newObj, SQLScript script) {
        PgAbstractView newView = (PgAbstractView) newObj;
        for (final Entry<String, String> newColumnComment : newView.columnComments.entrySet()) {
            String newColumn = newColumnComment.getKey();
            String newValue = newColumnComment.getValue();

            String oldValue = columnComments.get(newColumn);
            if (!Objects.equals(oldValue, newValue)) {
                script.addCommentStatement(COLUMN_COMMENT.formatted(getQualifiedName(),
                        quote(newColumn), newValue));
            }
        }

        for (final Entry<String, String> columnComment : columnComments.entrySet()) {
            String oldColumn = columnComment.getKey();

            if (!newView.columnComments.containsKey(oldColumn)) {
                script.addCommentStatement(COLUMN_COMMENT.formatted(getQualifiedName(),
                        quote(oldColumn), "NULL"));
            }
        }
    }

    protected abstract void alterViewOptions(SQLScript script, PgAbstractView newAbstractView);

    @Override
    public boolean canDropBeforeCreate() {
        return true;
    }

    /**
     * Adds a column name to this view.
     *
     * @param colName column name to add
     */
    public void addColumnName(String colName) {
        columnNames.add(colName);
        resetHash();
    }

    /**
     * Sets the view query and its normalized form.
     *
     * @param query           original query text
     * @param normalizedQuery normalized query for comparison
     */
    public void setQuery(final String query, final String normalizedQuery) {
        this.query = query;
        this.normalizedQuery = normalizedQuery;
        resetHash();
    }

    @Override
    public Map<String, String> getOptions() {
        return Collections.unmodifiableMap(options);
    }

    @Override
    public void addOption(String option, String value) {
        options.put(option, value);
        resetHash();
    }

    /**
     * Removes an option by name, if the view carries one under that name.
     * <p>
     * The counterpart of {@link #addOption(String, String)}, for the
     * {@code RESET (...)} of {@code ALTER VIEW} and
     * {@code ALTER MATERIALIZED VIEW}. A file states the options the view ends
     * up with, so one it resets has to leave the model, or the database keeps
     * an option the project no longer sets - {@code security_invoker} among
     * them, which decides whose privileges the query runs with.
     * <p>
     * A name that matches nothing is left alone rather than reported, the same
     * answer {@code PgAbstractTable.removeOption} gives.
     *
     * @param option option name, spelled as {@link #addOption} received it
     */
    public void removeOption(String option) {
        if (options.remove(option) != null) {
            resetHash();
        }
    }

    /**
     * Adds a comment to a view column.
     *
     * @param columnName column name
     * @param comment    comment text (ignored if null or empty)
     */
    public void addColumnComment(String columnName, String comment) {
        if (comment == null || comment.isEmpty()) {
            return;
        }

        columnComments.put(columnName, comment);
        resetHash();
    }

    @Override
    public Stream<Pair<String, String>> getRelationColumns() {
        return relationColumns == null ? null : relationColumns.stream();
    }

    /**
     * Adds one catalog-fed relation column. Used only by the JDBC views
     * reader; project loaders leave the columns unset, which routes the view
     * through the sequential analysis-driven initialization instead.
     *
     * @param columnName column name
     * @param columnType column type produced by {@code pg_catalog.format_type}
     */
    public void addRelationColumn(String columnName, String columnType) {
        if (relationColumns == null) {
            relationColumns = new ArrayList<>();
        }
        relationColumns.add(new Pair<>(columnName, columnType));
    }

    /**
     * The column list is deliberately left out.
     * <p>
     * {@code Comparison.compare} asks the hash before it asks anything else and
     * takes a difference it alone can see as a difference in the object, so a
     * field the comparison may overlook - see {@link #isSameColumnNames} - can
     * have no place here, or the relaxation would never be reached. Leaving it
     * out only makes this guard weaker, never wrong: {@code equals} still asks
     * {@link #compare}, which does compare the lists wherever the catalog has
     * nothing to say about them.
     */
    @Override
    public void computeHash(Hasher hasher) {
        hasher.put(normalizedQuery);
        hasher.put(columnComments);
        hasher.put(options);
    }

    @Override
    public Collection<IConstraint> getConstraints() {
        return Collections.emptyList();
    }

    @Override
    public boolean compare(IStatement obj) {
        return obj instanceof PgAbstractView view
                && super.compare(view)
                && Objects.equals(normalizedQuery, view.normalizedQuery)
                && isSameColumnNames(view)
                && columnComments.equals(view.columnComments)
                && options.equals(view.options);
    }

    @Override
    protected PgAbstractStatementContainer getCopy() {
        PgAbstractView view = getViewCopy();
        view.query = query;
        view.normalizedQuery = normalizedQuery;
        view.columnNames.addAll(columnNames);
        view.columnComments.putAll(columnComments);
        view.options.putAll(options);
        if (relationColumns != null) {
            view.relationColumns = new ArrayList<>(relationColumns);
        }
        return view;
    }

    protected abstract PgAbstractView getViewCopy();
}