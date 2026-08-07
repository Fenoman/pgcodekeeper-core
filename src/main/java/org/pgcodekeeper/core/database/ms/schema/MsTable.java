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
package org.pgcodekeeper.core.database.ms.schema;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.script.*;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.Pair;
import org.pgcodekeeper.core.utils.Utils;

/**
 * Represents a Microsoft SQL table with support for memory-optimized tables,
 * temporal tables, filestream data, and other Microsoft SQL specific features.
 */
public class MsTable extends MsAbstractStatementContainer implements ITable, ISimpleOptionContainer {

    private static final String MEMORY_OPTIMIZED = "MEMORY_OPTIMIZED";

    private final List<MsColumn> columns = new ArrayList<>();
    private final Map<String, String> options = new LinkedHashMap<>();
    private final Map<String, MsConstraint> constraints = new LinkedHashMap<>();

    /**
     * list of internal primary keys for memory optimized table
     */
    private List<MsConstraint> pkeys;
    private boolean ansiNulls;
    private Boolean isTracked;
    private String textImage;
    private String fileStream;
    private String tablespace;
    private String sysVersioning;
    private MsColumn periodStartCol;
    private MsColumn periodEndCol;

    /**
     * Creates a new Microsoft SQL table with the specified name.
     *
     * @param name the table name
     */
    public MsTable(String name) {
        super(name);
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        final StringBuilder sbSQL = new StringBuilder();
        appendName(sbSQL);
        appendColumns(sbSQL, script.getSettings());
        appendOptions(sbSQL);
        script.addStatement(sbSQL);
        appendAlterOptions(script);
        appendOwnerSQL(script);
        appendPrivileges(script);
        appendColumnsPriliges(script);
    }

    private void appendName(StringBuilder sbSQL) {
        sbSQL.append("SET QUOTED_IDENTIFIER ON").append(getSeparator()).append('\n');
        sbSQL.append("SET ANSI_NULLS ").append(ansiNulls ? "ON" : "OFF");
        sbSQL.append(getSeparator()).append('\n');
        sbSQL.append("CREATE TABLE ").append(getQualifiedName());
    }

    /**
     * The columns the body of a {@code CREATE} of this table is written with, and
     * therefore the columns every statement of that {@code CREATE} may name.
     * <p>
     * All of them. An ignore list is not asked here and is not asked anywhere
     * else in the generator: hiding lives in the comparison, see
     * {@link ColumnVisibility}. The one place the rules do decide which columns
     * are written is a project file, see
     * {@link StatementUtils#columnsForProjectFile(List, List, ColumnVisibility)},
     * and that decision is taken before the statement reaches a generator.
     * <p>
     * Kept as a method of its own because what a column of this dialect carries
     * beside its definition is written inside that definition, with one
     * exception - a privilege granted on the column alone, which is a statement
     * of its own that names it and fails outright against a table created without
     * it. Both have to come from one and the same list, and this is that list.
     *
     * @return the columns of this table
     */
    private List<MsColumn> columnsInCreateBody() {
        return columns;
    }

    private void appendColumns(StringBuilder sbSQL, ISettings settings) {
        sbSQL.append("(\n");
        int bodyStart = sbSQL.length();

        for (MsColumn column : StatementUtils.orderColumnsForWriting(columnsInCreateBody(), settings)) {
            sbSQL.append("\t");
            sbSQL.append(column.getFullDefinition());
            sbSQL.append(",\n");
        }

        for (MsConstraint con : getPkeys()) {
            if (con.isPrimaryKey()) {
                sbSQL.append("\t");
                String name = con.getName();
                if (!name.isEmpty()) {
                    sbSQL.append("CONSTRAINT ").append(quote(name)).append(' ');
                }
                sbSQL.append(con.getDefinition());
                sbSQL.append(",\n");
            }
        }

        // the separator is taken off what was written rather than off what the
        // table holds: a body that wrote nothing has no separator to take off,
        // and taking one anyway ate the parenthesis it opened with
        boolean written = sbSQL.length() > bodyStart;
        if (written) {
            sbSQL.setLength(sbSQL.length() - 2);
        }
        appendPeriodSystem(sbSQL, written);
        sbSQL.append('\n').append(')');
    }

    private void appendPeriodSystem(StringBuilder sb, boolean afterSomething) {
        if (periodStartCol != null && periodEndCol != null) {
            if (afterSomething) {
                sb.append(',');
            }
            sb.append("\n\t").append(periodSystemClause());
        }
    }

    /**
     * The {@code PERIOD FOR SYSTEM_TIME} of a system versioned table, as it is
     * written inside the body of the {@code CREATE}.
     */
    private String periodSystemClause() {
        return "PERIOD FOR SYSTEM_TIME (" + periodStartCol.getQuotedName()
                + ", " + periodEndCol.getQuotedName() + ")";
    }

    private void appendOptions(StringBuilder sbSQL) {
        int startLength = sbSQL.length();
        if (tablespace != null) {
            // tablespace already quoted
            sbSQL.append(" ON ").append(tablespace).append(' ');
        }

        if (textImage != null) {
            sbSQL.append("TEXTIMAGE_ON ").append(quote(textImage)).append(' ');
        }

        if (fileStream != null) {
            sbSQL.append("FILESTREAM_ON ").append(quote(fileStream)).append(' ');
        }

        if (sbSQL.length() > startLength) {
            sbSQL.setLength(sbSQL.length() - 1);
        }

        StringBuilder sb = new StringBuilder();
        for (Entry<String, String> entry : options.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            sb.append(key);
            if (!value.isEmpty()) {
                sb.append(" = ").append(value);
            }
            sb.append(", ");
        }

        if (!sb.isEmpty()) {
            sb.setLength(sb.length() - 2);
            sbSQL.append("\nWITH (").append(sb).append(')');
        }
    }

    private void appendAlterOptions(SQLScript script) {
        if (isTracked != null) {
            script.addStatement(enableTracking(), SQLActionType.END);
        }
        if (sysVersioning != null) {
            script.addStatement(enableSysVersioning(), SQLActionType.END);
        }
    }

    private void appendColumnsPriliges(SQLScript script) {
        for (MsColumn col : columnsInCreateBody()) {
            col.appendPrivileges(script);
        }
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        int startSize = script.getSize();
        MsTable newTable = (MsTable) newCondition;

        var settings = script.getSettings();
        if (isNeedRecreate(newTable) || isColumnsOrderChanged(newTable, settings)) {
            return ObjectState.RECREATE;
        }

        appendAlterOwner(newTable, script);
        compareTableOptions(newTable, script);
        alterPrivileges(newTable, script);

        return getObjectState(script, startSize);
    }

    private boolean isNeedRecreate(MsTable newTable) {
        return !Objects.equals(newTable.tablespace, tablespace)
                || ansiNulls != newTable.ansiNulls
                || !Utils.setLikeEquals(newTable.getPkeys(), getPkeys())
                || !Objects.equals(newTable.options, options)
                || !Objects.equals(newTable.fileStream, fileStream)
                || !Objects.equals(newTable.periodStartCol, periodStartCol)
                || !Objects.equals(newTable.periodEndCol, periodEndCol)
                || (newTable.textImage != null && textImage != null
                && !Objects.equals(newTable.textImage, textImage));
    }

    private boolean isColumnsOrderChanged(MsTable newTable, ISettings settings) {
        if (settings.isIgnoreColumnOrder()) {
            return false;
        }

        return StatementUtils.isColumnsOrderChanged(newTable.columns, columns);
    }

    /**
     * Compares table options between this table and the new table, generating appropriate SQL scripts
     * for change tracking and system versioning differences.
     *
     * @param newTable the new table to compare against
     * @param script the script to append SQL statements to
     */
    public void compareTableOptions(MsTable newTable, SQLScript script) {
        compareTracked(newTable, script);
        compareSysVersioning(newTable, script);
    }

    private void compareTracked(MsTable newTable, SQLScript script) {
        if (Objects.equals(isTracked, newTable.isTracked)) {
            if (isTracked != null && pkChanged(newTable)) {
                script.addStatement(disableTracking(), SQLActionType.BEGIN);
                script.addStatement(newTable.enableTracking(), SQLActionType.END);
            }
            return;
        }

        if (isTracked != null) {
            script.addStatement(disableTracking(), SQLActionType.MID);
        }

        if (newTable.isTracked != null) {
            script.addStatement(newTable.enableTracking(), SQLActionType.MID);
        }
    }

    private String enableTracking() {
        return getAlterTable() +
                " ENABLE CHANGE_TRACKING WITH (TRACK_COLUMNS_UPDATED = " +
                (isTracked() ? "ON" : "OFF") + ')';
    }

    private void compareSysVersioning(MsTable newTable, SQLScript script) {
        if (Objects.equals(sysVersioning, newTable.sysVersioning)) {
            if (sysVersioning != null && pkChanged(newTable)) {
                script.addStatement(disableSysVersioning(), SQLActionType.BEGIN);
                script.addStatement(newTable.enableSysVersioning(), SQLActionType.END);
            }
            return;
        }

        if (sysVersioning != null) {
            script.addStatement(disableSysVersioning(), SQLActionType.MID);
        }

        if (newTable.sysVersioning != null) {
            script.addStatement(newTable.enableSysVersioning(), SQLActionType.MID);
        }
    }

    private boolean pkChanged(MsTable table) {
        var oldPk = constraints.values().stream().filter(MsConstraintPk.class::isInstance).findAny().orElse(null);
        var newPk = table.constraints.values().stream().filter(MsConstraintPk.class::isInstance).findAny().orElse(null);
        return oldPk != null && newPk != null && !Objects.equals(oldPk, newPk);
    }

    @Override
    public void getDropSQL(SQLScript script, boolean generateExists) {
        if (isTracked() && constraints.values().stream().anyMatch(MsConstraintPk.class::isInstance)) {
            script.addStatement(disableTracking(), SQLActionType.BEGIN);
        }
        if (sysVersioning != null) {
            script.addStatement(disableSysVersioning(), SQLActionType.BEGIN);
        }

        super.getDropSQL(script, generateExists);
    }

    private String disableTracking() {
        return getAlterTable() + " DISABLE CHANGE_TRACKING";
    }

    private String disableSysVersioning() {
        return getAlterTable() + " SET (SYSTEM_VERSIONING = OFF)";
    }

    private String enableSysVersioning() {
        return getAlterTable() + " SET (SYSTEM_VERSIONING = " + sysVersioning + ')';
    }

    String getAlterTable() {
        return ALTER_TABLE + getQualifiedName();
    }

    @Override
    public void appendMoveDataSql(IStatement newCondition, SQLScript script, String tblTmpBareName,
                                  List<String> identityCols) {
        MsTable newTable = (MsTable) newCondition;
        List<String> colsForMovingData = getColsForMovingData(newTable);
        if (colsForMovingData.isEmpty()) {
            return;
        }

        String tblTmpQName = getParent().getQuotedName() + '.' + quote(tblTmpBareName);
        String cols = colsForMovingData.stream().map(this::quote).collect(Collectors.joining(", "));
        List<String> identityColsForMovingData = identityCols == null ? Collections.emptyList()
                : identityCols.stream().filter(colsForMovingData::contains).toList();
        writeInsert(script, newTable, tblTmpQName, identityColsForMovingData, cols);
    }

    /**
     * The names of the columns whose data is moved into the recreated table,
     * which are the columns that table was built with: a calculated column has
     * nothing to move, and everything else does.
     * <p>
     * The recreated table is built with every column of the state it is built
     * from, an ignore list notwithstanding, see {@link #columnsInCreateBody()}.
     * Leaving a column out here would therefore not spare it - it would silently
     * drop its data on a rebuild.
     */
    private List<String> getColsForMovingData(MsTable newTbl) {
        return newTbl.columnsInCreateBody().stream()
                .filter(c -> containsColumn(c.getName()))
                .filter(msCol -> msCol.getExpression() == null && msCol.getGenerated() == null)
                .map(MsColumn::getName).toList();
    }

    private void writeInsert(SQLScript script, MsTable newTable, String tblTmpQName,
                             List<String> identityColsForMovingData, String cols) {
        String tblQName = newTable.getQualifiedName();
        boolean newHasIdentity = newTable.getColumns().stream().anyMatch(c ->  ((MsColumn) c).isIdentity());
        if (newHasIdentity) {
            // There can only be one IDENTITY column per table in MSSQL.
            script.addStatement(getIdentInsertText(tblQName, true));
        }

        StringBuilder sbInsert = new StringBuilder();

        sbInsert.append("INSERT INTO ").append(tblQName).append('(').append(cols).append(")");
        sbInsert.append("\nSELECT ").append(cols).append(" FROM ").append(tblTmpQName);
        script.addStatement(sbInsert);

        if (newHasIdentity) {
            // There can only be one IDENTITY column per table in MSSQL.
            script.addStatement(getIdentInsertText(tblQName, false));
        }

        if (!identityColsForMovingData.isEmpty() && newHasIdentity) {
            // DECLARE'd var is only visible within its batch
            // so we shouldn't need unique names for them here
            // use the largest numeric type to fit any possible identity value
            StringBuilder sbSql = new StringBuilder();
            sbSql.append("DECLARE @restart_var numeric(38,0) = (SELECT IDENT_CURRENT (")
                    .append(Utils.quoteString(tblTmpQName))
                    .append("));\nDBCC CHECKIDENT (")
                    .append(Utils.quoteString(tblQName))
                    .append(", RESEED, @restart_var);");
            script.addStatement(sbSql);
        }
    }

    private String getIdentInsertText(String name, boolean isOn) {
        return "SET IDENTITY_INSERT " + name + (isOn ? " ON" : " OFF");
    }

    @Override
    public boolean compareIgnoringColumnOrder(ITable newTable) {
        return compare(newTable, false, ColumnVisibility.all());
    }

    /**
     * The parts of the body of this table that name its columns as text.
     * <p>
     * Three of them, and every one is a part of the {@code CREATE TABLE} rather
     * than a statement about a column, so a column any of them names cannot be
     * left out: the body would state a column it does not declare.
     * <ul>
     * <li>A computed column keeps the expression it reads its siblings from as
     * the text it was written as, and nothing resolves it to a reference.</li>
     * <li>A {@code PERIOD FOR SYSTEM_TIME} names the two columns that bound the
     * lifetime of a row.</li>
     * <li>The primary key of a memory optimized table is written inside the body,
     * and is held neither among the constraints of this table nor among its
     * children - {@link #getConstraints()} does not report it and no dependency
     * of anything records it - so nothing else can speak for its columns.</li>
     * </ul>
     */
    @Override
    public Collection<String> getClausesNamingColumns() {
        List<String> clauses = new ArrayList<>();
        for (MsColumn column : columns) {
            String expression = column.getExpression();
            if (expression != null) {
                clauses.add(expression);
            }
        }

        if (periodStartCol != null && periodEndCol != null) {
            clauses.add(periodSystemClause());
        }

        for (MsConstraint pkey : getPkeys()) {
            if (pkey.isPrimaryKey()) {
                clauses.add(pkey.getDefinition());
            }
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
        MsTable project = projectSide instanceof MsTable table ? table : null;
        List<MsColumn> forProject = StatementUtils.columnsForProjectFile(columns,
                project == null ? null : project.columns,
                ColumnVisibility.of(settings).forPair(project, this));
        if (forProject == columns) {
            return this;
        }

        MsTable adopted = (MsTable) deepCopy();
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
    private void replaceColumns(List<MsColumn> forProject) {
        columns.clear();
        for (MsColumn column : forProject) {
            addColumn((MsColumn) column.deepCopy());
        }
    }

    @Override
    public boolean isClustered() {
        if (super.isClustered()) {
            return true;
        }

        for (MsConstraint constr : constraints.values()) {
            if (constr instanceof IConstraintPk pk && pk.isClustered()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets the list of primary key constraints for memory-optimized tables.
     *
     * @return unmodifiable list of primary key constraints
     */
    public List<MsConstraint> getPkeys() {
        return pkeys == null ? Collections.emptyList() : Collections.unmodifiableList(pkeys);
    }

    public void setFileStream(String fileStream) {
        this.fileStream = fileStream;
        resetHash();
    }

    public void setTextImage(String textImage) {
        this.textImage = textImage;
        resetHash();
    }

    public void setAnsiNulls(boolean ansiNulls) {
        this.ansiNulls = ansiNulls;
        resetHash();
    }

    /**
     * Checks if change tracking is enabled for this table.
     *
     * @return true if change tracking is enabled
     */
    public boolean isTracked() {
        return isTracked != null && isTracked;
    }

    public void setTracked(final Boolean isTracked) {
        this.isTracked = isTracked;
        resetHash();
    }

    public String getTablespace() {
        return tablespace;
    }

    public void setTablespace(final String tablespace) {
        this.tablespace = tablespace;
        resetHash();
    }

    public void setPeriodStartCol(MsColumn periodStartCol) {
        this.periodStartCol = periodStartCol;
        resetHash();
    }

    public void setPeriodEndCol(MsColumn periodEndCol) {
        this.periodEndCol = periodEndCol;
        resetHash();
    }

    /**
     * Checks if this table is memory-optimized.
     *
     * @return true if the table is memory-optimized
     */
    public boolean isMemoryOptimized() {
        return "ON".equalsIgnoreCase(options.get(MEMORY_OPTIMIZED));
    }

    public void setSysVersioning(String sysVersioning) {
        this.sysVersioning = sysVersioning;
        resetHash();
    }

    @Override
    public void addChild(IStatement st) {
        switch (st.getStatementType()) {
            case CONSTRAINT -> addConstraint((MsConstraint) st);
            default -> super.addChild(st);
        }
    }

    private void addConstraint(MsConstraint constraint) {
        if (constraint.isPrimaryKey() && isMemoryOptimized()) {
            if (pkeys == null) {
                pkeys = new ArrayList<>();
            }
            pkeys.add(constraint);
        } else {
            addUnique(constraints, constraint);
        }
    }

    @Override
    public AbstractStatement getChild(String name, DbObjType type) {
        return switch (type) {
            case CONSTRAINT -> getChildByName(constraints, name);
            default -> super.getChild(name, type);
        };
    }

    @Override
    public Collection<IStatement> getChildrenByType(DbObjType type) {
        return switch (type) {
            case CONSTRAINT -> Collections.unmodifiableCollection(constraints.values());
            default -> super.getChildrenByType(type);
        };
    }

    @Override
    public void fillChildrenList(List<Collection<? extends AbstractStatement>> l) {
        super.fillChildrenList(l);
        l.add(constraints.values());
    }

    @Override
    public MsColumn getColumn(String name) {
        for (MsColumn column : columns) {
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
    public void addOption(String key, String value) {
        options.put(key, value);
        resetHash();
    }

    @Override
    public Map<String, String> getOptions() {
        return Collections.unmodifiableMap(options);
    }

    @Override
    public Stream<Pair<String, String>> getRelationColumns() {
        return columns.stream()
                .filter(c -> c.getType() != null)
                .map(c -> new Pair<>(c.getName(), c.getType()));
    }

    public void addColumn(final MsColumn column) {
        assertUnique(getColumn(column.getName()), column);
        columns.add(column);
        column.setParent(this);
        resetHash();
    }

    public MsConstraint getConstraint(String name) {
        return constraints.get(name);
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.putOrdered(columns);
        hasher.put(options);
        hasher.put(ansiNulls);
        hasher.put(textImage);
        hasher.put(fileStream);
        hasher.put(isTracked);
        hasher.put(tablespace);
        hasher.put(periodStartCol);
        hasher.put(periodEndCol);
        hasher.put(sysVersioning);
        hasher.putUnordered(getPkeys());
    }

    @Override
    public void computeChildrenHash(Hasher hasher) {
        super.computeChildrenHash(hasher);
        hasher.putUnordered(constraints);
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

        if (obj instanceof MsTable table && super.compare(table)) {
            List<MsColumn> mine = managed.visible(columns);
            List<MsColumn> other = managed.visible(table.columns);
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

    private boolean compareTable(AbstractStatement obj) {
        return obj instanceof MsTable table
                && ansiNulls == table.ansiNulls
                && Objects.equals(textImage, table.textImage)
                && Objects.equals(fileStream, table.fileStream)
                && Objects.equals(isTracked, table.isTracked)
                && Objects.equals(tablespace, table.tablespace)
                && Objects.equals(periodStartCol, table.periodStartCol)
                && Objects.equals(periodEndCol, table.periodEndCol)
                && Objects.equals(sysVersioning, table.sysVersioning)
                && Utils.setLikeEquals(getPkeys(), table.getPkeys());
    }

    @Override
    public boolean compareChildren(AbstractStatement obj) {
        return obj instanceof MsTable table && super.compareChildren(obj)
                && constraints.equals(table.constraints);
    }

    @Override
    protected MsTable getCopy() {
        MsTable table = new MsTable(name);
        for (var colSrc : columns) {
            table.addColumn((MsColumn) colSrc.deepCopy());
        }
        table.options.putAll(options);
        table.setAnsiNulls(ansiNulls);
        table.setTextImage(textImage);
        table.setFileStream(fileStream);
        table.setTracked(isTracked);
        table.setTablespace(tablespace);
        table.setPeriodStartCol(periodStartCol);
        table.setPeriodEndCol(periodEndCol);
        table.setSysVersioning(sysVersioning);

        if (pkeys != null) {
            table.pkeys = new ArrayList<>();
            table.pkeys.addAll(pkeys);
        }
        return table;
    }
}
