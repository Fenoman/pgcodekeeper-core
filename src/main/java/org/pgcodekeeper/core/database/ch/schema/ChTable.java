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
package org.pgcodekeeper.core.database.ch.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.IConstraint;
import org.pgcodekeeper.core.database.api.schema.IOptionContainer;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectState;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.base.schema.StatementUtils;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.Pair;
import org.pgcodekeeper.core.utils.Utils;

/**
 * Represents a ClickHouse table with engine configuration and projections.
 * Supports ClickHouse-specific features like table engines, projections, and specialized DDL operations.
 */
public class ChTable extends ChAbstractStatement implements ITable, IOptionContainer {

    protected final List<ChColumn> columns = new ArrayList<>();

    private final Map<String, String> projections = new LinkedHashMap<>();

    /**
     * The body of every {@link #projections} entry as the comparison sees it,
     * under the same key: the same tokens with canonical spacing, and the
     * reserved words of the folded range {@code CHLexer.ALL..WITH} raised to
     * upper case. Only that range folds - {@code SELECT} and {@code GROUP} are
     * inside it, {@code BY} is not, so re-casing {@code BY} alone in a projection
     * body still reads as a change.
     * <p>
     * {@link #projections} keeps the text the DDL is written from, because a
     * project file must round-trip exactly as its author wrote it.
     * {@link #addProjection(String, String, String)} takes a body and its
     * normalized twin together, so a projection cannot be added to one map alone.
     */
    private final Map<String, String> projectionsNormalized = new LinkedHashMap<>();

    private final Map<String, String> options = new LinkedHashMap<>();
    private final Map<String, ChIndex> indexes = new LinkedHashMap<>();
    private final Map<String, ChConstraint> constraints = new LinkedHashMap<>();

    private ChEngine engine;

    /**
     * Creates a new ClickHouse table with the specified name.
     *
     * @param name the name of the table
     */
    public ChTable(String name) {
        super(name);
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        var sb = new StringBuilder();
        sb.append("CREATE TABLE ");
        var settings = script.getSettings();
        appendIfNotExists(sb, settings);
        sb.append(getQualifiedName());
        var clusterName = settings.getClusterName();
        if (null != clusterName && !clusterName.isBlank()) {
            sb.append(" ON CLUSTER ").append(clusterName);
        }
        sb.append("\n(");
        int bodyStart = sb.length();
        appendTableBody(sb, settings);
        // the separator is taken off what was written rather than off what the
        // table holds: a body that wrote nothing has no separator to take off,
        // and taking one anyway ate the parenthesis it opened with
        if (sb.length() > bodyStart) {
            sb.setLength(sb.length() - 1);
        }
        sb.append("\n)");

        engine.appendCreationSQL(sb);

        if (getComment() != null) {
            sb.append("\nCOMMENT ").append(getComment());
        }
        script.addStatement(sb);
    }

    /**
     * The columns the body of a {@code CREATE} of this table is written with.
     * <p>
     * All of them. An ignore list is not asked here and is not asked anywhere
     * else in the generator: hiding lives in the comparison, see
     * {@link ColumnVisibility}. The one place the rules do decide which columns
     * are written is a project file, see
     * {@link StatementUtils#columnsForProjectFile(List, List, ColumnVisibility)},
     * and that decision is taken before the statement reaches a generator.
     *
     * @return the columns of this table
     */
    protected List<ChColumn> columnsInCreateBody() {
        return columns;
    }

    protected void appendTableBody(StringBuilder sb, ISettings settings) {
        for (ChColumn column : StatementUtils.orderColumnsForWriting(columnsInCreateBody(), settings)) {
            sb.append("\n\t").append(column.getFullDefinition()).append(',');
        }

        for (Entry<String, String> proj : projections.entrySet()) {
            sb.append("\n\tPROJECTION ").append(proj.getKey()).append(' ').append(proj.getValue()).append(',');
        }
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        int startSize = script.getSize();
        ChTable newTable = (ChTable) newCondition;

        var settings = script.getSettings();
        if (isNeedRecreate(newTable) || isColumnsOrderChanged(newTable, settings)) {
            return ObjectState.RECREATE;
        }

        compareProjections(newTable, script);
        engine.appendAlterSQL(newTable.engine, getAlterTable(), script);
        compareComment(newTable.getComment(), script);
        return getObjectState(script, startSize);
    }

    protected boolean isNeedRecreate(ChTable newTable) {
        var newEngine = newTable.engine;
        return !engine.compareUnalterable(newEngine)
                && !engine.isModifybleSampleBy(newEngine);
    }

    /**
     * Whether a projection changed is decided on the normalized bodies, so that
     * a re-spaced one is not dropped and re-added; what the statement carries is
     * the new table's own spelling.
     */
    private void compareProjections(ChTable newTable, SQLScript script) {
        Map<String, String> newNormalized = newTable.projectionsNormalized;
        if (Objects.equals(projectionsNormalized, newNormalized)) {
            return;
        }
        Set<String> toDrops = new HashSet<>();
        Map<String, String> toAdds = new LinkedHashMap<>();

        for (Entry<String, String> projection : projectionsNormalized.entrySet()) {
            var key = projection.getKey();
            if (!newNormalized.containsKey(key)) {
                toDrops.add(key);
                continue;
            }
            if (!Objects.equals(newNormalized.get(key), projection.getValue())) {
                toDrops.add(key);
                toAdds.put(key, newTable.projections.get(key));
            }
        }

        for (Entry<String, String> newProjection : newTable.projections.entrySet()) {
            var key = newProjection.getKey();
            if (!projectionsNormalized.containsKey(key)) {
                toAdds.put(key, newProjection.getValue());
            }
        }

        appendAlterProjections(toDrops, toAdds, script);
    }

    private void appendAlterProjections(Set<String> toDrops, Map<String, String> toAdds, SQLScript script) {
        for (String toDrop : toDrops) {
            script.addStatement(getAlterTable() + "\n\tDROP PROJECTION IF EXISTS " + toDrop);
        }
        for (Entry<String, String> toAdd : toAdds.entrySet()) {
            StringBuilder sb = new StringBuilder();
            sb.append(getAlterTable()).append("\n\tADD PROJECTION ");
            appendIfNotExists(sb, script.getSettings());
            sb.append(toAdd.getKey()).append(' ').append(toAdd.getValue());
            script.addStatement(sb);
        }
    }

    private void compareComment(String newComment, SQLScript script) {
        if (Objects.equals(getComment(), newComment)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(getAlterTable()).append("\n\tMODIFY COMMENT ");
        sb.append(Objects.requireNonNullElse(newComment, "''"));
        script.addStatement(sb);
    }

    public String getAlterTable() {
        return ALTER_TABLE + getQualifiedName();
    }

    @Override
    public void compareOptions(IOptionContainer newContainer, SQLScript script) {
        // no impl
    }

    protected boolean isColumnsOrderChanged(ChTable newTable, ISettings settings) {
        if (settings.isIgnoreColumnOrder()) {
            return false;
        }

        return StatementUtils.isColumnsOrderChanged(newTable.columns, columns);
    }

    @Override
    public boolean compareIgnoringColumnOrder(ITable newTable) {
        return compare(newTable, false, ColumnVisibility.all());
    }

    @Override
    public void appendMoveDataSql(IStatement newCondition, SQLScript script, String tblTmpBareName,
                                  List<String> identityCols) {
        ChTable newTable = (ChTable) newCondition;
        List<String> colsForMovingData = getColsForMovingData(newTable);
        if (colsForMovingData.isEmpty()) {
            return;
        }

        String tblTmpQName = getParent().getQuotedName() + '.' + quote(tblTmpBareName);
        String cols = colsForMovingData.stream().map(this::quote).collect(Collectors.joining(", "));
        writeInsert(script, newTable, tblTmpQName, cols);
    }

    private void writeInsert(SQLScript script, ChTable newTable, String tblTmpQName, String cols) {
        StringBuilder sbInsert = new StringBuilder();
        sbInsert.append("INSERT INTO ").append(newTable.getQualifiedName()).append('(').append(cols).append(")");
        sbInsert.append("\nSELECT ").append(cols).append(" FROM ").append(tblTmpQName);
        script.addStatement(sbInsert);
    }

    /**
     * Adds a projection to this table, taking its body and the normalized twin
     * together so that a caller cannot supply one half and forget the other.
     *
     * @param key                  the projection name
     * @param expression           the projection body as written, used for DDL output
     * @param expressionNormalized the same body normalized for comparison
     */
    public void addProjection(String key, String expression, String expressionNormalized) {
        projections.put(key, expression);
        projectionsNormalized.put(key, expressionNormalized);
        resetHash();
    }

    /**
     * The parts of the body of this table that name its columns as text.
     * <p>
     * The engine names the columns it sorts and partitions by, and a projection
     * selects the columns it is built over; neither is resolved to a reference
     * and a projection is not even a child of this table, so nothing else can
     * speak for the columns they name. Both are parts of the {@code CREATE TABLE}
     * rather than statements about a column, so a column either of them names
     * cannot be left out: the body would state a column it does not declare.
     */
    @Override
    public Collection<String> getClausesNamingColumns() {
        if (projections.isEmpty()) {
            return engine == null ? List.of() : engine.getClausesNamingColumns();
        }

        List<String> clauses = new ArrayList<>(projections.values());
        if (engine != null) {
            clauses.addAll(engine.getClausesNamingColumns());
        }
        return clauses;
    }

    /**
     * A {@code CREATE TABLE} of this dialect states its columns between
     * parentheses that no server will parse empty.
     * <hr><br>
     * {@inheritDoc}
     */
    @Override
    public boolean canCreateWithoutColumns() {
        return false;
    }

    /**
     * A table of this dialect carries what the project owns in one thing only:
     * which columns there are at all.
     * <hr><br>
     * {@inheritDoc}
     */
    @Override
    public IStatement adoptUnmanaged(IStatement projectSide, ISettings settings) {
        ChTable project = projectSide instanceof ChTable table ? table : null;
        List<ChColumn> forProject = StatementUtils.columnsForProjectFile(columns,
                project == null ? null : project.columns,
                ColumnVisibility.of(settings).forPair(project, this));
        if (forProject == columns) {
            return this;
        }

        ChTable adopted = (ChTable) deepCopy();
        adopted.replaceColumns(forProject);
        return attachCopy(adopted);
    }

    /**
     * Replaces the columns of this table with copies of the given ones.
     * <p>
     * The one mutating step of the adoption of a column set, and the reason it is
     * safe: only ever called on a freshly copied table, never on one a database
     * still holds. The columns are copied in turn because a column belongs to the
     * table it is added to, and some of them belong to the model of the project,
     * which the caller may still be using.
     */
    private void replaceColumns(List<ChColumn> forProject) {
        columns.clear();
        for (ChColumn column : forProject) {
            addColumn((ChColumn) column.deepCopy());
        }
    }

    public void setEngine(ChEngine engine) {
        this.engine = engine;
        resetHash();
    }

    /**
     * Sets the {@code PRIMARY KEY} this table states among its elements rather
     * than among its engine options. It is the same key either way and reaches
     * the same engine field, so it has to be handed over the same way: as
     * written, for the DDL, and normalized, for the comparison.
     *
     * @param pkExpr           expression text as written, used for DDL output
     * @param pkExprNormalized the same expression normalized for comparison
     */
    public void setPkExpr(String pkExpr, String pkExprNormalized) {
        engine.setPrimaryKey(pkExpr, pkExprNormalized);
        resetHash();
    }

    /**
     * The names of the columns whose data is moved into the recreated table,
     * which are the columns that table was built with.
     * <p>
     * The recreated table is built with every column of the state it is built
     * from, an ignore list notwithstanding, see {@link #columnsInCreateBody()}.
     * Leaving a column out here would therefore not spare it - it would silently
     * drop its data on a rebuild.
     */
    protected List<String> getColsForMovingData(ChTable newTable) {
        return newTable.columnsInCreateBody().stream()
                .map(IColumn::getName)
                .filter(this::containsColumn)
                .toList();
    }

    @Override
    public void fillChildrenList(List<Collection<? extends AbstractStatement>> l) {
        l.add(indexes.values());
        l.add(constraints.values());
    }

    /**
     * Checks if this container has any clustered indexes or constraints.
     */
    public boolean isClustered() {
        for (var ind : indexes.values()) {
            if (ind.isClustered()) {
                return true;
            }
        }

        return false;
    }

    @Override
    public AbstractStatement getChild(String name, DbObjType type) {
        return switch (type) {
            case INDEX -> getChildByName(indexes, name);
            case CONSTRAINT -> getChildByName(constraints, name);
            default -> null;
        };
    }

    @Override
    public Collection<IStatement> getChildrenByType(DbObjType type) {
        return switch (type) {
            case INDEX -> Collections.unmodifiableCollection(indexes.values());
            case CONSTRAINT -> Collections.unmodifiableCollection(constraints.values());
            default -> List.of();
        };
    }

    @Override
    public void addChild(IStatement st) {
        DbObjType type = st.getStatementType();
        switch (type) {
            case INDEX:
                addUnique(indexes, (ChIndex) st);
                break;
            case CONSTRAINT:
                addUnique(constraints, (ChConstraint) st);
                break;
            default:
                throw new IllegalArgumentException(Messages.Statement_unsupported_child_type.formatted(type));
        }
    }

    /**
     * Finds column according to specified column {@code name}.
     *
     * @param name name of the column to be searched
     * @return found column or null if no such column has been found
     */
    @Override
    public ChColumn getColumn(final String name) {
        for (ChColumn column : columns) {
            if (column.getName().equals(name)) {
                return column;
            }
        }
        return null;
    }

    /**
     * Getter for {@link #columns}. The list cannot be modified.
     *
     * @return {@link #columns}
     */
    @Override
    public List<IColumn> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    @Override
    public Collection<IConstraint> getConstraints() {
        return Collections.unmodifiableCollection(constraints.values());
    }

    @Override
    public Stream<Pair<String, String>> getRelationColumns() {
        return columns.stream()
                .filter(c -> c.getType() != null)
                .map(c -> new Pair<>(c.getName(), c.getType()));
    }

    @Override
    public Map<String, String> getOptions() {
        return Collections.unmodifiableMap(options);
    }

    /**
     * Gets the value for the specified option.
     *
     * @param option the option key
     * @return the option value, or null if not found
     */
    public String getOption(String option) {
        return options.get(option);
    }

    @Override
    public void addOption(String option, String value) {
        options.put(option, value);
        resetHash();
    }

    /**
     * Adds a column to the table.
     *
     * @param column the column to add
     */
    public void addColumn(final ChColumn column) {
        assertUnique(getColumn(column.getName()), column);
        columns.add(column);
        column.setParent(this);
        resetHash();
    }

    /**
     * Checks if a column with the specified name exists.
     *
     * @param name the column name
     * @return true if column exists, false otherwise
     */
    public boolean containsColumn(final String name) {
        return getColumn(name) != null;
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.putOrdered(columns);
        hasher.put(options);
        hasher.put(projectionsNormalized);
        hasher.put(engine);
    }

    @Override
    public void computeChildrenHash(Hasher hasher) {
        hasher.putUnordered(constraints);
        hasher.putUnordered(indexes);
    }

    @Override
    public boolean compare(IStatement obj) {
        return compare(obj, true, ColumnVisibility.all());
    }

    @Override
    public boolean compareIgnoringUnmigratableColumns(ITable target, ISettings settings) {
        return compare(target, !settings.isIgnoreColumnOrder(),
                ColumnVisibility.of(settings).forPair(this, target));
    }

    private boolean compare(IStatement obj, boolean checkColumnOrder, ColumnVisibility managed) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof ChTable table && super.compare(obj)) {
            List<ChColumn> mine = managed.visible(columns);
            List<ChColumn> other = managed.visible(table.columns);
            boolean isColumnsEqual;
            if (checkColumnOrder) {
                isColumnsEqual = mine.equals(other);
            } else {
                isColumnsEqual = Utils.setLikeEquals(mine, other);
            }

            return isColumnsEqual
                    && getClass().equals(table.getClass())
                    && options.equals(table.options)
                    && compareTable(table);
        }

        return false;
    }

    protected boolean compareTable(AbstractStatement obj) {
        return obj instanceof ChTable table
                && Objects.equals(projectionsNormalized, table.projectionsNormalized)
                && Objects.equals(engine, table.engine);
    }

    @Override
    public boolean compareChildren(AbstractStatement obj) {
        return obj instanceof ChTable table && super.compareChildren(obj)
                && constraints.equals(table.constraints)
                && indexes.equals(table.indexes);
    }

    @Override
    protected ChTable getCopy() {
        ChTable copy = getTableCopy();
        for (var colSrc : columns) {
            copy.addColumn((ChColumn) colSrc.deepCopy());
        }
        copy.options.putAll(options);
        copy.projections.putAll(projections);
        copy.projectionsNormalized.putAll(projectionsNormalized);
        copy.setEngine(ChEngine.copyOf(engine));
        return copy;
    }

    protected ChTable getTableCopy() {
        return new ChTable(name);
    }
}
