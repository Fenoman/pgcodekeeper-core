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
package org.pgcodekeeper.core.database.pg.schema;

import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.base.schema.StatementUtils;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.script.SQLActionType;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.Pair;
import org.pgcodekeeper.core.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Base PostgreSQL table class providing common functionality for all PostgreSQL table types.
 * Handles table inheritance, trigger states, column management, and various PostgreSQL-specific
 * table features like WITH OIDS, row-level security, and storage parameters.
 *
 * @author galiev_mr
 * @since 5.3.1.
 */
public abstract class PgAbstractTable extends PgAbstractStatementContainer implements ITable, IOptionContainer {

    protected static final String ALTER_COLUMN = " ALTER COLUMN ";

    /**
     * List of Greenplum-specific storage options.
     */
    private static final List<String> GP_OPTION_LIST = List.of(
            "appendonly",
            "appendoptimized",
            "blocksize",
            "orientation",
            "checksum",
            "compresstype",
            "compresslevel",
            "analyze_hll_non_part_table");

    /**
     * List of Greenplum-specific storage options that can be changed by ALTER TABLE since Greenplum 7.
     */
    protected static final Set<String> GP_COMPRESS_OPTION_LIST = Set.of(
            "blocksize",
            "compresstype",
            "compresslevel");

    /**
     * Sets the identity sequence of a rebuilt table past the values that were
     * moved into it, from the sequence the renamed original still owns.
     * <p>
     * The column is named twice and the two namings are not the same one.
     * {@code %3$s} stands where an identifier stands, so it is quoted the way
     * an identifier is quoted; {@code %2$s} stands inside a string literal, and
     * the second argument of {@code pg_get_serial_sequence} is read verbatim -
     * PostgreSQL hands it straight to {@code get_attnum}, which never sees a
     * quote as anything but part of a name. So a column called {@code "Id"}
     * looked up as {@code '"Id"'} is a column that does not exist, and the
     * {@code DECLARE} raises - after the {@code INSERT} of this same rebuild
     * has already moved the rows.
     * <p>
     * The table is the other way round: {@code %1$s} is a literal too, but one
     * {@code textToQualifiedNameList} parses as a qualified name, so it wants
     * the quoted spelling. Both literals still have to survive an apostrophe in
     * a name, which is what {@link Utils#quoteString} is for and what the
     * single quotes this template no longer carries used to prevent.
     */
    private static final String RESTART_SEQUENCE_QUERY = """
            DO LANGUAGE plpgsql $_$
            DECLARE restart_var bigint = (SELECT COALESCE(
                (SELECT nextval(pg_get_serial_sequence(%1$s, %2$s))),
                (SELECT MAX(%3$s) + 1 FROM %4$s),
                1));
            BEGIN
                EXECUTE $$ ALTER TABLE %4$s ALTER COLUMN %3$s RESTART WITH $$ || restart_var || ';' ;
            END
            $_$""";

    private static final String CHANGE_TRIGGER_STATE =
            "ALTER TABLE %1$s %2$s TRIGGER %3$s";

    protected final List<Inherits> inherits = new ArrayList<>();
    protected final List<PgColumn> columns = new ArrayList<>();
    /**
     * Name-based lookup index over {@link #columns}. Maintained by
     * {@link #addColumn(PgColumn)} - the single mutation funnel for the column
     * list; {@link #sortColumns()} only reorders the list, so the index stays
     * valid. Not part of hash/compare state: the ordered list is authoritative.
     */
    private final Map<String, PgColumn> columnsByName = new HashMap<>();
    protected final Map<String, String> options = new LinkedHashMap<>();

    protected boolean hasOids;

    private static final Logger LOG = LoggerFactory.getLogger(PgAbstractTable.class);
    private final Map<String, PgConstraint> constraints = new LinkedHashMap<>();
    private final Map<String, String> triggerStates = new HashMap<>();

    protected PgAbstractTable(String name) {
        super(name);
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        final StringBuilder sbSQL = new StringBuilder();

        SQLScript temp = new SQLScript(script.getSettings(), getSeparator());

        appendName(sbSQL, script.getSettings());
        appendColumns(sbSQL, temp);
        appendInherit(sbSQL);
        appendOptions(sbSQL);
        script.addStatement(sbSQL);

        script.addAllStatements(temp);

        appendNotNullTableConstraints(script);
        appendAlterOptions(script);

        appendOwnerSQL(script);
        appendPrivileges(script);
        appendColumnsPrivileges(script);
        appendColumnsStatistics(script);
        appendTriggerStates(script);
        appendComments(script);
    }

    /**
     * Fills tables parents, parents are stored in 'inherits' list.<br>
     * May be overridden by subclasses.
     * <br><br>
     * For example:
     * <br><br>
     * INHERITS (first_parent, schema_name.second_parent)
     *
     * @param sbSQL - StringBuilder for inherits
     */
    protected void appendInherit(StringBuilder sbSQL) {
        if (!inherits.isEmpty()) {
            sbSQL.append("\nINHERITS (");
            for (final Inherits tableName : inherits) {
                sbSQL.append(tableName.getQualifiedName());
                sbSQL.append(", ");
            }
            sbSQL.setLength(sbSQL.length() - 2);
            sbSQL.append(")");
        }
    }

    /**
     * The columns the body of a {@code CREATE} of this table is written with,
     * and therefore the columns every statement of that {@code CREATE} may name.
     * <p>
     * All of them. An ignore list is not asked here and is not asked anywhere
     * else in the generator: hiding lives in the comparison, see
     * {@link ColumnVisibility}. A rule that names a column keeps it out of every
     * comparison, so no migration ever adds, drops or alters it - and what a
     * script does write, it writes from the project, exactly as the project
     * declares it. The one place the rules do decide which columns are written
     * is a project file, see
     * {@link StatementUtils#columnsForProjectFile(List, List, ColumnVisibility)},
     * and that decision is taken before the statement reaches a generator.
     * <p>
     * Kept as a method of its own rather than read off the field at each call
     * site because everything a column carries beside its definition is written
     * by a statement that names it - a comment, a statistics target, a
     * privilege, a {@code NOT NULL} constraint held under a name - and every one
     * of those fails outright against a table created without the column. All of
     * it has to come from one and the same list, and this is that list.
     *
     * @return the columns of this table
     */
    protected List<PgColumn> columnsInCreateBody() {
        return columns;
    }

    private void appendNotNullTableConstraints(SQLScript script) {
        columnsInCreateBody().forEach(col -> {
            var notNullConstraint = col.getNotNullConstraint();
            if (notNullConstraint == null ) {
                return;
            }

            if (notNullConstraint.isNotValid()) {
                notNullConstraint.getCreationSQL(script);
            } else if (notNullConstraint.isComplexNotNull()) {
                notNullConstraint.appendOptions(script, new StringBuilder(), false);
            }
        });
    }

    protected void appendColumnsPrivileges(SQLScript script) {
        for (PgColumn col : columnsInCreateBody()) {
            col.appendPrivileges(script);
        }
    }

    protected void appendColumnsStatistics(SQLScript script) {
        columnsInCreateBody().stream().filter(c -> c.getStatistics() != null)
                .forEach(column -> {
                    StringBuilder sql = new StringBuilder();
                    sql.append(getAlterTable(isNeedOnly(script.getSettings())));
                    sql.append(ALTER_COLUMN);
                    sql.append(column.getQuotedName());
                    sql.append(" SET STATISTICS ");
                    sql.append(column.getStatistics());
                    script.addStatement(sql);
                });
    }

    @Override
    public void appendComments(SQLScript script) {
        super.appendComments(script);
        appendChildrenComments(script);
    }

    private void appendChildrenComments(SQLScript script) {
        for (var column : columnsInCreateBody()) {
            column.appendComments(script);
        }
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        int startSize = script.getSize();
        PgAbstractTable newTable = (PgAbstractTable) newCondition;

        var settings = script.getSettings();
        if (isNeedRecreate(newTable, settings) || isColumnsOrderChanged(newTable, settings)) {
            return ObjectState.RECREATE;
        }

        compareTableTypes(newTable, script);
        compareInherits(newTable, script);
        compareOptions(newTable, script);
        appendAlterOwner(newTable, script);
        compareTableOptions(newTable, script);
        alterPrivileges(newTable, script);
        compareTriggerStates(newTable, script);
        appendAlterComments(newTable, script);

        return getObjectState(script, startSize);
    }

    /**
     * Checks whether the table needs to be recreated due to changes in its options.
     *
     * @param newTable     the reference table to compare with
     * @param settings     configuration settings
     * @return {@code true} if recreation is required, {@code false} otherwise
     */
    protected boolean isNeedRecreate(PgAbstractTable newTable, ISettings settings) {
        if (options.equals(newTable.getOptions())) {
            return false;
        }

        var isGp7Syntax = checkSyntaxVersion(settings, PgSupportedVersion.GP_VERSION_7);

        // check greenplum options
        for (String gpOption : GP_OPTION_LIST) {
            if (isGp7Syntax && GP_COMPRESS_OPTION_LIST.contains(gpOption)) {
                continue;
            }

            if (!Objects.equals(options.get(gpOption), newTable.getOption(gpOption))) {
                return true;
            }
        }

        return false;
    }

    protected boolean isColumnsOrderChanged(PgAbstractTable newTable, ISettings settings) {
        // broken inherit algorithm
        if (!(newTable instanceof PgTypedTable)
                && inherits.isEmpty() && newTable.inherits.isEmpty()) {
            if (settings.isIgnoreColumnOrder()) {
                return false;
            }

            return StatementUtils.isColumnsOrderChanged(newTable.columns, columns);
        }

        return false;
    }

    protected void compareInherits(PgAbstractTable newTable, SQLScript script) {
        List<Inherits> newInherits = newTable.inherits;

        if (newTable instanceof PgPartitionTable) {
            return;
        }

        inherits.stream()
                .filter(e -> !newInherits.contains(e))
                .forEach(e -> script.addStatement(getInheritsActions(e, "\n\tNO INHERIT ")));

        newInherits.stream()
                .filter(e -> !inherits.contains(e))
                .forEach(e -> script.addStatement(getInheritsActions(e, "\n\tINHERIT ")));
    }

    private String getInheritsActions(Inherits inh, String state) {
        return getAlterTable(false) + state + inh.getQualifiedName();
    }

    /**
     * Compare <b>TABLE</b> options by alter table statement
     *
     * @param newTable - new table
     * @param script   - script for statements
     */
    protected void compareTableOptions(PgAbstractTable newTable, SQLScript script) {
        if (hasOids != newTable.hasOids) {
            StringBuilder sql = new StringBuilder();
            sql.append(getAlterTable(true))
                    .append(" SET ")
                    .append(newTable.hasOids ? "WITH" : "WITHOUT")
                    .append(" OIDS");
            script.addStatement(sql);
        }
    }

    private void compareTriggerStates(PgAbstractTable newTable, SQLScript script) {
        var newTriggers = newTable.triggerStates;
        if (!triggerStates.equals(newTriggers)) {
            newTriggers.entrySet().stream()
                    .filter(tr -> !Objects.equals(tr.getValue(), triggerStates.get(tr.getKey())))
                    .forEach(tr -> addTriggerToScript(tr, script));
        }
    }

    private void appendTriggerStates(SQLScript script) {
        for (var state : triggerStates.entrySet()) {
            addTriggerToScript(state, script);
        }
    }

    /**
     * Writes the statement that states the firing state of a trigger this table
     * inherits.
     * <p>
     * The key is quoted, the same way {@code PgTrigger.addAlterTable} quotes
     * the name of a trigger of the table's own - one operation, and the two
     * paths to it have to spell it alike. The map holds bare names, both
     * writers seeing to that: the catalog answers {@code tgname} unquoted, and
     * the lexer takes the quotes off a {@code QuotedIdentifier} at the token
     * level, so a name written back as it is stored is an unquoted one - which
     * the server folds to lower case, naming a trigger that does not exist.
     */
    private void addTriggerToScript(Entry<String, String> tg, SQLScript script) {
        String changeTgState = CHANGE_TRIGGER_STATE.formatted(getQualifiedName(), tg.getValue(), quote(tg.getKey()));
        script.addStatement(changeTgState, SQLActionType.END);
    }

    /**
     * Sorts columns on table.
     * <br><br>
     * First the usual columns in the order of adding,
     * then sorted alphabetically the inheritance columns
     */
    public void sortColumns() {
        if (inherits.isEmpty()) {
            return;
        }

        columns.sort((e1, e2) -> {
            boolean first = e1.isInherit();
            boolean second = e2.isInherit();
            if (first && second) {
                return e1.getName().compareTo(e2.getName());
            } else {
                return -Boolean.compare(first, second);
            }
        });

        resetHash();
    }

    @Override
    public boolean compareIgnoringColumnOrder(ITable newTable) {
        return compare(newTable, false, ColumnRelaxations.none(), ColumnVisibility.all());
    }

    @Override
    public boolean compareIgnoringUnmigratableColumns(ITable target, ISettings settings) {
        return compare(target, !settings.isIgnoreColumnOrder(), ColumnRelaxations.forMigrationTarget(settings),
                ColumnVisibility.of(settings).forPair(this, target));
    }

    @Override
    public void appendMoveDataSql(IStatement newCondition, SQLScript script, String tblTmpBareName,
                                  List<String> identityCols) {
        PgAbstractTable newTable = (PgAbstractTable) newCondition;
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
     * Returns the names of the columns from which data will be moved to another
     * table: the columns the recreated table was built with and this one holds
     * too, calculated ones aside, since those have nothing to move.
     * <p>
     * The recreated table is built with every column of the state it is built
     * from, an ignore list notwithstanding, see
     * {@link #columnsInCreateBody()}. Leaving a column out here would therefore
     * not spare it - it would silently drop its data on a rebuild.
     */
    private List<String> getColsForMovingData(PgAbstractTable newTable) {
        return newTable.getColumns().stream()
                .filter(c -> getColumn(c.getName()) != null)
                .map(PgColumn.class::cast)
                .filter(pgCol -> !pgCol.isGenerated())
                .map(PgColumn::getName)
                .toList();
    }

    private void writeInsert(SQLScript script, PgAbstractTable newTable, String tblTmpQName,
                               List<String> identityColsForMovingData, String cols) {
        String tblQName = newTable.getQualifiedName();
        StringBuilder sbInsert = new StringBuilder();
        sbInsert.append("INSERT INTO ").append(tblQName).append('(').append(cols).append(")");
        if (!identityColsForMovingData.isEmpty()) {
            sbInsert.append("\nOVERRIDING SYSTEM VALUE");
        }
        sbInsert.append("\nSELECT ").append(cols).append(" FROM ").append(tblTmpQName);
        script.addStatement(sbInsert);

        for (String colName : identityColsForMovingData) {
            script.addStatement(RESTART_SEQUENCE_QUERY.formatted(
                    Utils.quoteString(tblTmpQName), Utils.quoteString(colName), quote(colName), tblQName));
        }
    }

    /**
     * Writes every column of the table into the body of its CREATE statement.
     * <p>
     * The columns keep the order of the table, the only one a migration script
     * may carry. A rendering meant for a human eye may ask for the order of
     * their names instead, see {@link ISettings#isSortColumnsForDisplay()}. The
     * list is the one every other statement of this {@code CREATE} is written
     * from as well, see {@link #columnsInCreateBody()}.
     *
     * @param sbSQL  StringBuilder for the column definitions
     * @param script collection for the statements a column needs beside its
     *               definition
     */
    protected void writeColumns(StringBuilder sbSQL, SQLScript script) {
        var settings = script.getSettings();
        for (PgColumn column : StatementUtils.orderColumnsForWriting(columnsInCreateBody(), settings)) {
            writeColumn(column, sbSQL, script);
        }
    }

    protected void writeColumn(PgColumn column, StringBuilder sbSQL, SQLScript script) {
        boolean isInherit = column.isInherit();
        if (isInherit) {
            fillInheritOptions(column, script);
        } else {
            sbSQL.append("\t");
            sbSQL.append(column.getFullDefinition());
            sbSQL.append(",\n");
        }
        if (column.getStorage() != null) {
            StringBuilder sql = new StringBuilder();
            sql.append(getAlterTable(isInherit))
                    .append(ALTER_COLUMN)
                    .append(column.getQuotedName())
                    .append(" SET STORAGE ")
                    .append(column.getStorage());
            script.addStatement(sql);
        }

        writeOptions(column, script, isInherit);
        PgSequence sequence = column.getSequence();
        if (sequence != null) {
            StringBuilder sbSeq = new StringBuilder();
            if (script.getSettings().isGenerateExistDoBlock()) {
                StringBuilder tmpSb = new StringBuilder();
                writeSequences(column, tmpSb);
                appendSqlWrappedInDo(sbSeq, tmpSb, DUPLICATE_RELATION);
            } else {
                writeSequences(column, sbSeq);
                sbSeq.setLength(sbSeq.length() - 1);
            }
            script.addStatement(sbSeq);
        }
    }

    private void fillInheritOptions(PgColumn column, SQLScript script) {
        if (column.isNotNull()) {
            script.addStatement(getAlterColumn(column) + " SET NOT NULL");
        }
        if (column.getDefaultValue() != null) {
            script.addStatement(getAlterColumn(column) + " SET DEFAULT " + column.getDefaultValue());
        }
    }

    private String getAlterColumn(PgColumn column) {
        return getAlterTable(true) + ALTER_COLUMN + column.getQuotedName();
    }

    private void writeOptions(PgColumn column, SQLScript script, boolean isInherit) {
        Map<String, String> opts = column.getOptions();
        Map<String, String> fOpts = column.getForeignOptions();

        if (!opts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(getAlterTable(isInherit))
                    .append(ALTER_COLUMN)
                    .append(column.getQuotedName())
                    .append(" SET (");

            for (Entry<String, String> option : opts.entrySet()) {
                sb.append(option.getKey());
                if (!option.getValue().isEmpty()) {
                    sb.append('=').append(option.getValue());
                }
                sb.append(", ");
            }
            sb.setLength(sb.length() - 2);
            sb.append(")");
            script.addStatement(sb);
        }

        if (!fOpts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(getAlterTable(isInherit))
                    .append(ALTER_COLUMN)
                    .append(column.getQuotedName())
                    .append(" OPTIONS (");

            for (Entry<String, String> option : fOpts.entrySet()) {
                sb.append(option.getKey());
                if (!option.getValue().isEmpty()) {
                    sb.append(' ').append(option.getValue());
                }
                sb.append(", ");
            }
            sb.setLength(sb.length() - 2);
            sb.append(")");
            script.addStatement(sb);
        }
    }

    protected PgSequence writeSequences(PgColumn column, StringBuilder sbOption) {
        PgSequence sequence = column.getSequence();
        sbOption.append(getAlterTable(false))
                .append(ALTER_COLUMN)
                .append(column.getQuotedName())
                .append(" ADD GENERATED ")
                .append(column.getIdentityType())
                .append(" AS IDENTITY (");
        sbOption.append("\n\tSEQUENCE NAME ").append(sequence.getQualifiedName());
        sequence.fillSequenceBody(sbOption);
        sbOption.append("\n);");
        return sequence;
    }

    /**
     * Appends CREATE TABLE statement beginning
     * <br><br>
     * Expected:
     * <br><br>
     * CREATE [ [ GLOBAL | LOCAL ] { TEMPORARY | TEMP } | UNLOGGED | FOREIGN ] TABLE [ IF NOT EXISTS ] table_name
     *
     * @param sbSQL    - StringBuilder for statement
     * @param settings - {@link ISettings} stores settings for correct script generation
     */
    protected abstract void appendName(StringBuilder sbSQL, ISettings settings);

    /**
     * Fills columns and their options to create table statement. Options will be
     * appends after CREATE TABLE statement. <br>
     * Must be overridden by subclasses
     *
     * @param sbSQL  - StringBuilder for columns
     * @param script - collection for options
     */
    protected abstract void appendColumns(StringBuilder sbSQL, SQLScript script);

    /**
     * Appends table storage parameters or server options, part of create statement;
     *
     * @param sbSQL - StringBuilder for options
     */
    protected abstract void appendOptions(StringBuilder sbSQL);

    /**
     * Appends <b>TABLE</b> options by alter table statement
     * <br><br>
     * For example:
     * <br><br>
     * ALTER TABLE table_name SET WITH OID;
     * <br>
     *
     * @param script - SQLScript for options
     */
    protected abstract void appendAlterOptions(SQLScript script);

    /**
     * Compare tables types and generate transform scripts for change tables type
     *
     * @param newTable - new table
     * @param script   - script for statements
     */
    protected abstract void compareTableTypes(PgAbstractTable newTable, SQLScript script);

    /**
     * Generates beginning of alter table statement.
     *
     * @param only if true, append 'ONLY' to statement
     * @return alter table statement beginning in String format
     */
    protected abstract String getAlterTable(boolean only);

    @Override
    public List<IColumn> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    @Override
    public void fillChildrenList(List<Collection<? extends AbstractStatement>> l) {
        super.fillChildrenList(l);
        l.add(constraints.values());
    }

    @Override
    public void addChild(IStatement st) {
        if (DbObjType.CONSTRAINT == st.getStatementType()) {
            addConstraint((PgConstraint) st);
            return;
        }

        super.addChild(st);
    }

    @Override
    public AbstractStatement getChild(String name, DbObjType type) {
        if (DbObjType.CONSTRAINT == type) {
            return getConstraint(name);
        }

        return super.getChild(name, type);
    }

    @Override
    public Collection<IStatement> getChildrenByType(DbObjType type) {
        if (DbObjType.CONSTRAINT == type) {
            return Collections.unmodifiableCollection(constraints.values());
        }
        return super.getChildrenByType(type);
    }

    @Override
    public boolean isClustered() {
        if (super.isClustered()) {
            return true;
        }

        for (PgConstraint constr : constraints.values()) {
            if (constr instanceof IConstraintPk pk && pk.isClustered()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Leaves the table with nothing clustered, looking in both places a table
     * keeps a clustered object - see {@link #isClustered()} above, which has to
     * look in both for the same reason: a table may be clustered on a key
     * rather than on an index of its own, and then it has no clustered index at
     * all.
     * <hr><br>
     * {@inheritDoc}
     */
    @Override
    public void clearClustered() {
        super.clearClustered();
        for (PgConstraint constr : constraints.values()) {
            if (constr instanceof PgConstraintPk pk) {
                pk.setClustered(false);
            }
        }
    }

    /**
     * Getter for {@link #constraints}. The list cannot be modified.
     *
     * @return {@link #constraints}
     */
    @Override
    public Collection<IConstraint> getConstraints() {
        return Collections.unmodifiableCollection(constraints.values());
    }

    @Override
    public void addOption(String option, String value) {
        options.put(option, value);
        resetHash();
    }

    /**
     * Removes an option by name, if the table carries one under that name.
     * <p>
     * The counterpart of {@link #addOption(String, String)}, for the two
     * statements of a project file that take an option away: the
     * {@code ALTER TABLE ... RESET (...)} of a storage parameter and the
     * {@code OPTIONS (DROP ...)} of a foreign table. A file states the options
     * the table ends up with, so one it resets has to leave the model, or the
     * database keeps a parameter the project no longer sets.
     * <p>
     * A name that matches nothing is left alone rather than reported, as in
     * {@link #removeConstraint(String)} - and here the server agrees outright:
     * measured on PostgreSQL 17.10, resetting a parameter that was never set
     * raises nothing.
     *
     * @param option option name, spelled as {@link #addOption} received it
     */
    public void removeOption(String option) {
        if (options.containsKey(option)) {
            options.remove(option);
            resetHash();
        }
    }

    private String getOption(String option) {
        return options.get(option);
    }

    @Override
    public Map<String, String> getOptions() {
        return Collections.unmodifiableMap(options);
    }


    @Override
    public Stream<Pair<String, String>> getRelationColumns() {
        Stream<Pair<String, String>> localColumns = columns.stream()
                .filter(c -> c.getType() != null)
                .map(c -> new Pair<>(c.getName(), c.getType()));
        if (inherits.isEmpty()) {
            return localColumns;
        }

        Stream<Pair<String, String>> inhColumns = Stream.empty();
        for (Inherits inht : inherits) {
            String schemaName = inht.key();
            ISchema inhtSchema = schemaName == null ? getContainingSchema() : getDatabase().getSchema(schemaName);
            if (inhtSchema != null) {
                String tableName = inht.value();
                PgAbstractTable inhtTable = (PgAbstractTable) inhtSchema.getChild(tableName, DbObjType.TABLE);
                if (inhtTable != null) {
                    inhColumns = Stream.concat(inhColumns, inhtTable.getRelationColumns());
                } else {
                    var msg = Messages.AbstractPgTable_log_inherits_not_found.formatted(schemaName, tableName);
                    LOG.warn(msg);
                }
            } else {
                var msg = Messages.AbstractPgTable_log_schemas_not_found.formatted(schemaName);
                LOG.warn(msg);
            }
        }
        return Stream.concat(inhColumns, localColumns);
    }

    /**
     * Adds a parent table to the inheritance list.
     *
     * @param schemaName parent table schema name.
     * @param tableName  parent table name
     */
    public void addInherits(final String schemaName, final String tableName) {
        inherits.add(new Inherits(schemaName, tableName));
        resetHash();
    }

    /**
     * Removes a parent table from the inheritance list, if the table has one
     * under that name.
     * <p>
     * The counterpart of {@link #addInherits(String, String)}, for the
     * {@code ALTER TABLE ... NO INHERIT} a project file may carry: the file
     * states the parents the table ends up with, so one it detaches has to
     * leave the model, or the {@code CREATE} goes on writing an
     * {@code INHERITS} clause the project removed.
     * <p>
     * Only the entry goes. The columns the table declares stay where they are,
     * which is the shape the model already has: a child's own column list never
     * held the parent's columns to begin with - {@code PgCreateTable} records
     * the parent and nothing else - so there is nothing here to take away.
     * <p>
     * A name that matches nothing is left alone rather than reported, as in
     * {@link #removeConstraint(String)}.
     *
     * @param schemaName parent table schema name
     * @param tableName  parent table name
     */
    public void removeInherits(final String schemaName, final String tableName) {
        if (inherits.remove(new Inherits(schemaName, tableName))) {
            resetHash();
        }
    }

    /**
     * Getter for {@link #inherits}.
     *
     * @return {@link #inherits}
     */
    public List<Inherits> getInherits() {
        return Collections.unmodifiableList(inherits);
    }

    /**
     * Checks if this table has any inheritance relationships.
     *
     * @return true if table inherits from other tables
     */
    public boolean hasInherits() {
        return !inherits.isEmpty();
    }

    /**
     * Sets the state of a trigger this table inherits, which is the only kind
     * of trigger it holds a state for without holding the trigger itself.
     * <p>
     * The enabled default is spelled {@link PgTriggerState#ENABLE} here and
     * {@code null} on a {@link PgTrigger} of this table's own. That split is
     * not made here: {@code PgTriggersReader.readEnabledState} makes it, in one
     * method, on its {@code isChild} argument - a catalog {@code 'O'} comes
     * back as {@code ENABLE} for an inherited trigger and as {@code null} for
     * an ordinary one. This map has no spelling for {@code null} at all,
     * because its values go straight into the statement
     * {@link #addTriggerToScript} writes, so a caller passing one means the
     * default and gets the word.
     *
     * @param triggerName name of the trigger
     * @param state       desired trigger state, {@code null} for the enabled
     *                    default
     */
    public void putTriggerState(String triggerName, PgTriggerState state) {
        triggerStates.put(triggerName, (state == null ? PgTriggerState.ENABLE : state).getValue());
        resetHash();
    }

    /**
     * Gives every trigger of this table the state a statement that names none
     * of them states - the {@code ALL} and {@code USER} spellings of
     * {@code ALTER TABLE ... {ENABLE|DISABLE} TRIGGER}.
     * <p>
     * Both places a table keeps a trigger state are written, for the same
     * reason {@link #removeConstraint(String)} searches both places a table
     * keeps constraints: the triggers of its own and the states of the ones it
     * inherits are one set to the statement, and writing only the first would
     * leave a partition's inherited trigger in a state the file has just swept
     * away. What the model cannot do either way is invent the names of
     * inherited triggers no statement in the file has named.
     *
     * @param state desired state for all of them, {@code null} for the enabled
     *              default
     */
    public void setEveryTriggerState(PgTriggerState state) {
        for (PgTrigger trigger : getTriggers()) {
            trigger.setTriggerState(state);
        }
        for (String triggerName : List.copyOf(triggerStates.keySet())) {
            putTriggerState(triggerName, state);
        }
    }

    public void setHasOids(final boolean hasOids) {
        this.hasOids = hasOids;
        resetHash();
    }

    public PgConstraint getConstraint(final String name) {
        var constraint = getChildByName(constraints, name);
        if (constraint != null) {
            return constraint;
        }

        return columns.stream()
                .map(PgColumn::getNotNullConstraint)
                .filter(Objects::nonNull)
                .filter(notNullConstraint -> notNullConstraint.getName().equals(name))
                .findAny()
                .orElse(null);
    }

    /**
     * Finds column according to specified column {@code name}.
     *
     * @param name name of the column to be searched
     * @return found column or null if no such column has been found
     */
    @Override
    public PgColumn getColumn(final String name) {
        return columnsByName.get(name);
    }

    protected void addConstraint(PgConstraint constraint) {
        addUnique(constraints, constraint);
    }

    /**
     * Removes a constraint by name, if the table has one under that name.
     * <p>
     * The counterpart of {@link #addConstraint(PgConstraint)}, for the
     * {@code ALTER TABLE ... DROP CONSTRAINT} a project file may carry: the file
     * states the constraints the table ends up with, so one it drops has to
     * leave the model, or the database keeps a constraint the project no longer
     * has.
     * <p>
     * Searches both places a table keeps constraints. Five of the six kinds live
     * in {@link #constraints}; a named {@code NOT NULL} does not - it hangs off
     * {@link PgColumn#getNotNullConstraint()}, so a table whose only constraint
     * is one of those returns an empty {@link #getConstraints()}. Dropping it
     * makes the column nullable, which is what the server does too - measured on
     * PostgreSQL 18.4, {@code pg_attribute.attnotnull} goes to false.
     * <p>
     * A name that matches nothing is left alone rather than reported: the caller
     * decides whether an unknown name is an error, because only the caller knows
     * whether the statement said {@code IF EXISTS}.
     *
     * @param name constraint name, spelled as {@link #addConstraint} received it
     */
    public void removeConstraint(String name) {
        if (constraints.remove(getNameInCorrectCase(name)) != null) {
            resetHash();
            return;
        }

        for (PgColumn col : columns) {
            var notNull = col.getNotNullConstraint();
            if (notNull != null && notNull.getName().equals(name)) {
                // resets the column's hash, and resetHash walks the parent
                // chain, so this table's hash is dropped with it
                col.setNotNullConstraint(null);
                return;
            }
        }
    }

    /**
     * Renames a constraint, keeping its place among the others.
     * <p>
     * For the {@code ALTER TABLE ... RENAME CONSTRAINT} of a project file,
     * which states content and not identity: the table it renames a constraint
     * of is the same table, while its {@code CREATE} would otherwise go on
     * writing the old constraint name.
     * <p>
     * Searches both places a table keeps constraints, as
     * {@link #removeConstraint(String)} does - a named {@code NOT NULL} hangs
     * off {@link PgColumn#getNotNullConstraint()} and a rename that looked only
     * in the map would silently do nothing to it. That one is re-parented to
     * its column, because {@link PgConstraintNotNull#getDefinition()} reads the
     * column through {@code getParent()} and
     * {@code AbstractStatement.computeNamesHash} walks the same chain.
     * <p>
     * The rename is a replacement rather than a mutation because the name of a
     * statement is final; see {@link PgConstraint#renamedCopy(String)} for what
     * the new object has to carry over. The place is kept for the reason it is
     * kept for a column: a {@code CREATE} written from this model lists the
     * constraints in this order, so moving one rewrites a project file that has
     * not otherwise changed. A name that matches nothing is left alone, and a
     * name the table already uses raises, as it does on the server.
     *
     * @param oldName the constraint to rename
     * @param newName the name to give it
     */
    public void renameConstraint(String oldName, String newName) {
        String oldKey = getNameInCorrectCase(oldName);
        PgConstraint constraint = constraints.get(oldKey);
        if (constraint != null) {
            PgConstraint renamed = constraint.renamedCopy(newName);
            String newKey = getNameInCorrectCase(newName);
            assertUnique(constraints.get(newKey), renamed);

            // rebuilt rather than removed and re-put, which would move the
            // constraint to the end of the insertion order this map keeps
            Map<String, PgConstraint> inPlace = new LinkedHashMap<>();
            constraints.forEach((key, value) ->
                    inPlace.put(key.equals(oldKey) ? newKey : key, key.equals(oldKey) ? renamed : value));
            constraints.clear();
            constraints.putAll(inPlace);

            renamed.setParent(this);
            resetHash();
            return;
        }

        for (PgColumn col : columns) {
            var notNull = col.getNotNullConstraint();
            if (notNull != null && notNull.getName().equals(oldName)) {
                var renamed = (PgConstraintNotNull) notNull.renamedCopy(newName);
                // resets the column's hash, and resetHash walks the parent
                // chain, so this table's hash is dropped with it
                col.setNotNullConstraint(renamed);
                renamed.setParent(col);
                return;
            }
        }
    }

    public void addColumn(final PgColumn column) {
        PgColumn found = columnsByName.putIfAbsent(column.getName(), column);
        assertUnique(found, column);
        columns.add(column);
        column.setParent(this);
        resetHash();
    }

    /**
     * Removes a column by name, if the table has one under that name.
     * <p>
     * The counterpart of {@link #addColumn(PgColumn)}, for the
     * {@code ALTER TABLE ... DROP COLUMN} a project file may carry: the file
     * states the columns the table ends up with, so one it drops has to leave
     * the model, or the database keeps a column the project no longer has.
     * <p>
     * Nothing else in the model is touched, and that is a boundary rather than
     * an omission. PostgreSQL drops what depended on the column - measured on
     * 18.4, a {@code CHECK} over it and an index on it both disappear, and a
     * view over it refuses the drop outright - while here an index, a constraint
     * or a view naming that column keeps naming it. The names those objects hold
     * are plain strings and nothing resolves them: a reference to a column that
     * is not there is silent through loading and through the full analysis alike
     * (measured), because the analysis records dependencies at object
     * granularity and never at column granularity. So the project file is left
     * saying what it says, and cascading is a decision of its own.
     * <p>
     * A name that matches nothing is left alone rather than reported, as in
     * {@link #removeConstraint(String)}: only the caller knows whether the
     * statement said {@code IF EXISTS}.
     *
     * @param name column name, spelled as {@link #addColumn} received it
     */
    public void removeColumn(String name) {
        PgColumn column = columnsByName.remove(name);
        if (column != null) {
            columns.remove(column);
            resetHash();
        }
    }

    /**
     * Renames a column, keeping its place among the others.
     * <p>
     * For the {@code ALTER TABLE ... RENAME COLUMN} of a project file, which
     * states content and not identity: the table it renames a column of is the
     * same table, while its {@code CREATE} would otherwise go on writing the old
     * column name.
     * <p>
     * The place is kept because it is part of the table:
     * {@link #computeHash(Hasher)} hashes the columns in order and
     * {@code StatementUtils.isColumnsOrderChanged} reports on that order. The
     * server keeps it too - a renamed column stays at its {@code attnum}.
     * <p>
     * The rename is a replacement rather than a mutation because the name of a
     * statement is final; see {@link PgColumn#renamedCopy(String)} for what the
     * new object has to carry over. A name that matches nothing is left alone,
     * and a name the table already uses raises, as it does on the server.
     *
     * @param oldName the column to rename
     * @param newName the name to give it
     */
    public void renameColumn(String oldName, String newName) {
        PgColumn column = columnsByName.get(oldName);
        if (column == null) {
            return;
        }

        PgColumn renamed = column.renamedCopy(newName);
        PgColumn found = columnsByName.putIfAbsent(newName, renamed);
        assertUnique(found, renamed);
        columnsByName.remove(oldName);
        columns.set(columns.indexOf(column), renamed);
        renamed.setParent(this);
        resetHash();
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.putOrdered(columns);
        hasher.put(options);
        hasher.put(hasOids);
        hasher.putOrdered(inherits);
        hasher.put(triggerStates);
    }

    @Override
    public void computeChildrenHash(Hasher hasher) {
        super.computeChildrenHash(hasher);
        hasher.putUnordered(constraints);
    }

    @Override
    public boolean compare(IStatement obj) {
        return compare(obj, true, ColumnRelaxations.none(), ColumnVisibility.all());
    }

    /**
     * @param obj              the other state of this table, the one a migration
     *                         would produce where the relaxations are direction
     *                         bound
     * @param checkColumnOrder true to treat the order of the columns as part of
     *                         their state
     * @param relaxations      the column differences to overlook
     * @param managed          the columns the migration manages; the others take
     *                         no part in the comparison
     */
    private boolean compare(IStatement obj, boolean checkColumnOrder, ColumnRelaxations relaxations,
                            ColumnVisibility managed) {
        if (obj instanceof PgAbstractTable table && super.compare(obj)) {
            return compareColumns(table, checkColumnOrder, relaxations, managed)
                    && getClass().equals(table.getClass())
                    && options.equals(table.options)
                    && compareTable(table);
        }

        return false;
    }

    private boolean compareColumns(PgAbstractTable table, boolean checkColumnOrder,
                                   ColumnRelaxations relaxations, ColumnVisibility managed) {
        List<PgColumn> mine = managed.visible(columns);
        List<PgColumn> other = managed.visible(table.columns);
        if (checkColumnOrder ? mine.equals(other) : Utils.setLikeEquals(mine, other)) {
            return true;
        }

        return relaxations.any() && compareColumnsIgnoring(mine, other, checkColumnOrder, relaxations);
    }

    /**
     * Retries the comparison of the columns above overlooking the differences
     * the generator writes nothing for, see {@link ColumnRelaxations}. Only ever
     * asked after the plain comparison said no, so it decides nothing on its own
     * and costs nothing while the states match.
     */
    private static boolean compareColumnsIgnoring(List<PgColumn> mine, List<PgColumn> other,
                                                  boolean checkColumnOrder, ColumnRelaxations relaxations) {
        int size = mine.size();
        if (size != other.size()) {
            return false;
        }

        for (int i = 0; i < size; i++) {
            PgColumn column = mine.get(i);
            // names are unique within a table, so matching them by name is the
            // same set-like comparison the ordered branch makes positionally
            PgColumn match = checkColumnOrder ? other.get(i) : findColumn(other, column.getName());
            if (match == null || !column.compareIgnoring(match, relaxations)) {
                return false;
            }
        }

        return true;
    }

    private static PgColumn findColumn(List<PgColumn> columns, String name) {
        for (PgColumn column : columns) {
            if (column.getName().equals(name)) {
                return column;
            }
        }
        return null;
    }

    protected boolean compareTable(PgAbstractTable table) {
        return hasOids == table.hasOids
                && inherits.equals(table.inherits)
                && triggerStates.equals(table.triggerStates);
    }

    @Override
    public boolean compareChildren(AbstractStatement obj) {
        return obj instanceof PgAbstractTable table && super.compareChildren(obj)
                && constraints.equals(table.constraints);
    }

    /**
     * A table carries what the project owns in its columns: which columns there
     * are at all, the statistics target of one, and the cache of the sequence
     * behind an identity column.
     * <p>
     * The cache is the case that this exists for. The sequence of an identity
     * column is written inside the file of its table, so an export that was only
     * ever meant to fetch a missing trigger rewrites it along with everything
     * else, and the setting that keeps the cache out of the comparison keeps it
     * out of the difference tree as well - leaving nothing to show what was
     * about to be overwritten.
     * <p>
     * For a value that is a column's own - the statistics target, the cache -
     * only a column the project declares as well has anything to give: for one
     * the project does not hold there is no project value to fall back on, and
     * the state of the database is written, as for every other property. The set
     * of columns is the project's whether it declares any of them or not, see
     * {@link #columnsForProjectFile(PgAbstractTable, ISettings)}.
     * <hr><br>
     * {@inheritDoc}
     */
    @Override
    public IStatement adoptUnmanaged(IStatement projectSide, ISettings settings) {
        PgAbstractTable project = projectSide instanceof PgAbstractTable table ? table : null;
        List<PgColumn> forProject = columnsForProjectFile(project, settings);
        boolean carriesValues = project != null && carriesValuesOf(project, settings);
        if (forProject == columns && !carriesValues) {
            return this;
        }

        PgAbstractTable adopted = (PgAbstractTable) deepCopy();
        if (forProject != columns) {
            adopted.replaceColumns(forProject);
        }
        if (carriesValues) {
            for (PgColumn column : adopted.columns) {
                PgColumn inProject = project.getColumn(column.getName());
                if (inProject != null) {
                    column.adoptFrom(inProject, settings);
                }
            }
        }
        return attachCopy(adopted);
    }

    /**
     * The columns a project file of this table holds, in the order they are
     * written in, see {@link StatementUtils#columnsForProjectFile(List, List,
     * ColumnVisibility)} for what the answer is and why.
     *
     * @param project  the state of this table the project holds, {@code null}
     *                 when the project holds none
     * @param settings the settings of the export
     * @return the list of this table itself when the rules leave every column
     * alone, a new list otherwise
     */
    private List<PgColumn> columnsForProjectFile(PgAbstractTable project, ISettings settings) {
        return StatementUtils.columnsForProjectFile(columns, project == null ? null : project.columns,
                ColumnVisibility.of(settings).forPair(project, this));
    }

    /**
     * Replaces the columns of this table with copies of the given ones.
     * <p>
     * The one mutating step of the adoption of a column set, and the reason it
     * is safe: only ever called on a freshly copied table, never on one a
     * database still holds. The columns are copied in turn because a column
     * belongs to the table it is added to, and some of them belong to the model
     * of the project, which the caller may still be using.
     */
    private void replaceColumns(List<PgColumn> forProject) {
        columns.clear();
        columnsByName.clear();
        for (PgColumn column : forProject) {
            addColumn((PgColumn) column.deepCopy());
        }
    }

    /**
     * Reports whether the project states a value of a column of this table that
     * the settings declare the project's own, so that a table with nothing to
     * take over is handed on as it is.
     */
    private boolean carriesValuesOf(PgAbstractTable project, ISettings settings) {
        return (settings.isIgnoreSequenceCache() || settings.isIgnoreColumnStatistics())
                && columns.stream().anyMatch(column -> project.ownsAnythingOf(column, settings));
    }

    /**
     * Reports whether this state of the table, the one the project holds, states
     * a value of the given column of the other state that the settings declare
     * the project's own.
     */
    private boolean ownsAnythingOf(PgColumn column, ISettings settings) {
        PgColumn inProject = getColumn(column.getName());
        return inProject != null && column.ownsAnythingOf(inProject, settings);
    }

    /**
     * Hands everything this table holds over to another table, leaving this one
     * empty.
     * <p>
     * For the one thing a project file can say that changes a table's class
     * rather than a field of it: {@code ALTER TABLE ... ATTACH PARTITION} makes
     * a {@link PgSimpleTable} a {@link PgPartitionTable} and {@code DETACH}
     * makes it a plain table again. The bound of a partition is final, as the
     * name of a statement is, so the statement is read by building the other
     * kind and moving into it - see {@code PgSchema.replaceTable}, which puts
     * the new object where the old one stood.
     * <p>
     * The children are <b>moved and not copied</b>, which is the whole reason
     * this method exists beside {@link #getCopy()}. Every analysis launcher a
     * {@code CREATE TABLE} registers holds a child object - a column for a
     * {@code DEFAULT} or a generation expression, a constraint for a
     * {@code CHECK} - and never the table, so moving the children keeps every
     * launcher pointing at an object whose parent chain still reaches the
     * database. Copies would leave them all analysing a table the schema no
     * longer has, and their dependencies would go nowhere without a word.
     * <p>
     * The inheritance list is deliberately not moved: it is the one thing the
     * two kinds do not agree about. A partition keeps its parent there and a
     * plain table keeps the tables it inherits from, so the caller states it -
     * one parent for an attach, none for a detach.
     *
     * @param target the table to hand everything over to, freshly built and empty
     */
    public void moveInto(PgAbstractTable target) {
        target.setOwner(getOwner());
        target.setComment(getComment());
        target.deps.addAll(deps);
        target.privileges.addAll(privileges);
        target.meta.copy(meta);
        target.setLocation(getLocation());

        target.options.putAll(options);
        target.setHasOids(hasOids);
        target.triggerStates.putAll(triggerStates);

        for (PgColumn column : new ArrayList<>(columns)) {
            // let go before adopting: addColumn parents the column itself, and
            // setParent refuses a statement that already has one - the guard
            // that keeps a child of two tables from existing at all
            column.setParent(null);
            target.addColumn(column);
        }
        columns.clear();
        columnsByName.clear();

        for (AbstractStatement child : getChildren().toList()) {
            // addUnique parents it, as addColumn does above
            child.setParent(null);
            target.addChild(child);
        }
        resetHash();
    }

    @Override
    protected PgAbstractTable getCopy() {
        PgAbstractTable copy = getTableCopy();
        for (PgColumn colSrc : columns) {
            copy.addColumn((PgColumn) colSrc.deepCopy());
        }
        copy.options.putAll(options);
        copy.setHasOids(hasOids);
        copy.inherits.addAll(inherits);
        copy.triggerStates.putAll(triggerStates);
        return copy;
    }

    protected abstract PgAbstractTable getTableCopy();
}
