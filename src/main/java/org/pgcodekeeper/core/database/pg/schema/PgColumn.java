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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.IForeignTable;
import org.pgcodekeeper.core.database.api.schema.ISimpleOptionContainer;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.api.schema.ObjectState;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.ISettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL column implementation.
 * Stores column information including data type, constraints, storage parameters,
 * statistics, compression settings, and identity properties.
 */
public class PgColumn extends PgAbstractStatement
        implements ISimpleOptionContainer, ICompressOptionContainer, IColumn {

    private static final Logger LOG = LoggerFactory.getLogger(PgColumn.class);

    private static final String ALTER_FOREIGN_OPTION = "%s OPTIONS (%s %s %s)";
    private static final String COMPRESSION_CONST = " COMPRESSION ";
    private static final String ALTER_COLUMN = "\n\tALTER COLUMN ";
    private static final String COLLATE = " COLLATE ";
    private static final String NULL = " NULL";
    private static final String NOT_NULL = " NOT NULL";

    /** The {@code storage_option} that names no storage of its own, see {@link #normalizeStorage}. */
    private static final String DEFAULT_STORAGE = "DEFAULT";


    private final Map<String, String> options = new LinkedHashMap<>(0);
    private final Map<String, String> fOptions = new LinkedHashMap<>(0);

    private Integer statistics;
    private String storage;
    private PgSequence sequence;
    private String identityType;
    private String compression;
    private boolean isInherit;
    private String generationOption;
    private PgConstraintNotNull notNullConstraint;
    private String type;
    private String collation;
    private String defaultValue;

    /**
     * The default - or the {@code GENERATED ALWAYS AS} expression, which is held
     * in the same field - as the comparison sees it: the same tokens with
     * canonical spacing, and the reserved words of the folded range
     * {@code SQLLexer.ALL..WITH} raised to upper case. Only that range folds, so
     * a word outside it - {@code IS}, for one - is still compared as written.
     * <p>
     * {@link #defaultValue} keeps the text the DDL is written from, because a
     * project file must round-trip exactly as its author wrote it.
     * <p>
     * The two halves must be present or absent together, which is why
     * {@link #setDefaultValue(String, String)} takes both at once.
     * {@link #definitionDefaultNotNull}, {@link #generatedAlways},
     * {@link #compareNotNull} and {@code PgAbstractTable.fillInheritOptions}
     * decide from {@link #defaultValue} whether to emit anything at all, while
     * {@link #computeHash(Hasher)} and the comparison read only this field.
     * Filling the raw half alone parts the two in both directions: the script
     * carries a {@code DEFAULT} the comparison believes is not there, and a
     * default the grammar could not read compares equal to no default at all,
     * since an empty normalized half is exactly what a column without a
     * {@code DEFAULT} has. So the reader fills both halves with the catalog's
     * own text before it submits the parse, and the finalizer overwrites the
     * normalized one when the parse succeeds.
     * <p>
     * The gates that decide what an <i>already required</i> {@code ALTER} writes
     * read the raw half instead, deliberately: {@link #isGeneratedColumnChanged}
     * and the drop-first test, both in {@link #appendAlterSQL}, the
     * {@link #compareDefaults} call there, and {@link #isJoinable}. None of them
     * can be asked about a pair this field calls equal, and the reason is one
     * step earlier than the script: {@code DiffTree.addColumns} gives a column a
     * tree element only where {@code equals} is false, and
     * {@code AbstractStatement.equals} is {@code compare} plus the parent names
     * and the children - so such a pair has no element for an action to be built
     * from at all.
     * <p>
     * Where something else does differ, the raw halves are compared again and a
     * re-spelling is paid for. Measured over the whole pipeline, the price is
     * not one statement: with a type change it is the redundant pair
     * {@code DROP DEFAULT} / {@code SET DEFAULT}, and for a generated column
     * {@link #isGeneratedColumnChanged} demands the raw texts be equal, so any
     * other difference alongside a re-spelled expression recreates the column -
     * {@code DROP COLUMN} then {@code ADD COLUMN}, a full rewrite of a
     * {@code STORED} one. Left as it is deliberately: this is the behaviour
     * every such pair had before the normalized half existed, which strictly
     * fewer pairs now reach.
     * <p>
     * {@link #compareDefaults} is reached from the create and the drop path too,
     * where one side is {@code null} and there is no pair to compare; there it
     * only emits, and the raw half is the text it must emit.
     */
    private String defaultValueNormalized;

    // greenplum type fields
    private String compressType;
    private int compressLevel = -1;
    private int blockSize;

    /**
     * Creates a new PostgreSQL column.
     *
     * @param name column name
     */
    public PgColumn(String name) {
        super(name);
    }

    public String getFullDefinition() {
        final StringBuilder sbDefinition = new StringBuilder();
        String cName = getQuotedName();
        sbDefinition.append(cName);

        if (type == null) {
            sbDefinition.append(" WITH OPTIONS");
        } else {
            sbDefinition.append(' ');
            sbDefinition.append(type);
            if (compression != null) {
                sbDefinition.append(COMPRESSION_CONST).append(quote(compression));
            }

            if (collation != null) {
                sbDefinition.append(COLLATE).append(collation);
            }
        }

        definitionDefaultNotNull(sbDefinition);

        generatedAlways(sbDefinition);

        appendCompressOptions(sbDefinition);
        return sbDefinition.toString();
    }

    private void definitionDefaultNotNull(StringBuilder sbDefinition) {
        if (defaultValue != null && generationOption == null) {
            sbDefinition
                    .append(" DEFAULT ")
                    .append(defaultValue);
        }

        if (notNullConstraint != null && !notNullConstraint.isNotValid()) {
            notNullConstraint.getDefinitionForColumn(sbDefinition);
        }
    }

    private void generatedAlways(StringBuilder sbDefinition) {
        if (generationOption != null) {
            sbDefinition.append(" GENERATED ALWAYS AS (")
                    .append(defaultValue)
                    .append(")");
            if (!"VIRTUAL".equals(generationOption)) {
                sbDefinition.append(" ")
                        .append(generationOption);
            }
        }
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        StringBuilder sb = new StringBuilder();

        boolean isMergeDefaultNotNull = false;
        if (type != null && getParentCol((PgAbstractTable) parent) == null) {
            sb.append(getAlterTable(false));
            sb.append("\n\tADD COLUMN ");
            appendIfNotExists(sb, script.getSettings());
            sb.append(getQuotedName())
                    .append(' ')
                    .append(type);
            if (compression != null) {
                sb.append(COMPRESSION_CONST).append(quote(compression));
            }
            if (collation != null) {
                sb.append(COLLATE).append(collation);
            }

            // a NOT VALID not-null is left out of every column definition
            // (definitionDefaultNotNull, and no server takes it there - measured
            // on PostgreSQL 18.4 and 17.10 alike), so merging it would write it
            // nowhere at all: the constraint hangs off this column rather than
            // standing in the diff tree, and compareNotNull below - the one
            // branch that knows to write it as an ALTER TABLE ... ADD CONSTRAINT
            // - is skipped for exactly the same flag. The price of splitting it
            // out is the UPDATE the merge exists to avoid, paid only by this
            // shape of constraint
            isMergeDefaultNotNull = notNullConstraint != null && !notNullConstraint.isNotValid();
            if (isMergeDefaultNotNull) {
                // for NOT NULL columns we'd emit a time-consuming UPDATE column=DEFAULT anyway,
                // so we can merge DEFAULT with column definition with no performance loss
                // this operation also becomes fast on PostgreSQL 11+ (metadata only operation)
                definitionDefaultNotNull(sb);
            }

            generatedAlways(sb);
            appendCompressOptions(sb);

            script.addStatement(sb);
        }

        // column may have a default expression or a generation expression
        // (https://www.postgresql.org/docs/12/catalog-pg-attribute.html) (param - 'atthasdef')
        if (!isMergeDefaultNotNull && generationOption == null) {
            compareDefaults(null, defaultValue, new AtomicBoolean(), script);
            compareNotNull(null, this, script);
        }
        compareStorages(null, storage, script);

        appendPrivileges(script);

        compareForeignOptions(Collections.emptyMap(), fOptions, script);
        writeOptions(true, script);

        compareStats(null, statistics, script);
        compareIdentity(null, identityType, null, sequence, script);

        appendComments(script);
    }

    private String getAlterTable(boolean only) {
        return ((PgAbstractTable) parent).getAlterTable(only);
    }

    private void appendCompressOptions(StringBuilder sb) {
        if (!hasCompressOptions()) {
            return;
        }

        sb.append(" ENCODING (");
        if (compressType != null) {
            sb.append("COMPRESSTYPE = ").append(compressType).append(", ");
        }

        if (compressLevel != -1) {
            sb.append("COMPRESSLEVEL = ").append(compressLevel).append(", ");
        }

        if (blockSize != 0) {
            sb.append("BLOCKSIZE = ").append(blockSize).append(", ");
        }
        sb.setLength(sb.length() - 2);
        sb.append(")");
    }

    private boolean hasCompressOptions() {
        return compressType != null || compressLevel != -1 || blockSize != 0;
    }

    private String getAlterTableColumn(boolean only, String column) {
        return getAlterTableColumn(only, column, true);
    }

    private String getAlterTableColumn(boolean only, String column, boolean needAlterTable) {
        StringBuilder sb = new StringBuilder();
        if (needAlterTable) {
            sb.append(getAlterTable(only));
        }
        sb.append(ALTER_COLUMN).append(quote(column));
        return sb.toString();
    }

    @Override
    public void getDropSQL(SQLScript script, boolean optionExists) {
        if (type != null && getParentCol((PgAbstractTable) parent) == null) {
            StringBuilder dropSb = new StringBuilder();
            dropSb.append(getAlterTable(isNeedOnly(script.getSettings()) && isOnlyAcceptedByParent()))
                    .append("\n\tDROP COLUMN ");
            if (optionExists) {
                dropSb.append(IF_EXISTS);
            }
            dropSb.append(getQuotedName());
            script.addStatement(dropSb);
            return;
        }

        compareDefaults(defaultValue, null, null, script);
        compareNotNull(this, null,script);
        compareStorages(storage, null, script);

        alterPrivileges(new PgColumn(name), script);

        compareForeignOptions(fOptions, Collections.emptyMap(), script);
        writeOptions(false, script);
        if (!script.getSettings().isIgnoreColumnStatistics()) {
            compareStats(statistics, null, script);
        }
        compareIdentity(identityType, null, sequence, null, script);

        appendComments(script);
    }

    /**
     * Reports whether the parent table takes {@code ONLY} on the three forms
     * that ask - {@code DROP COLUMN}, {@code DROP NOT NULL} and
     * {@code ALTER COLUMN ... SET/RESET (...)}.
     * <p>
     * PostgreSQL rejects the word on a declaratively partitioned parent for the
     * two that take something away ("cannot drop column from only the
     * partitioned table when partitions exist"); the third neither recurses nor
     * objects, and has always been written the way its neighbours are.
     * <p>
     * This is a rule of the server, so the setting of the caller does not
     * replace it: {@link PgAbstractStatement#isNeedOnly} bounds the answer from
     * above where the two meet, and the {@code SET/RESET (...)} form asks this
     * one alone, being on TimescaleDB's ONLY whitelist.
     *
     * @return true if parent table isn't partition table with existing partitions, false otherwise
     */
    private boolean isOnlyAcceptedByParent() {
        if (parent instanceof PgAbstractRegularTable regTable) {
            return regTable.getPartitionBy() == null;
        }
        return true;
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        int startSize = script.getSize();
        PgColumn newColumn = (PgColumn) newCondition;

        if (!compareGenerationOption(newColumn, script.getSettings())) {
            return ObjectState.RECREATE;
        }

        if (!compareCompressOptions(newColumn)) {
            if (checkSyntaxVersion(script.getSettings(), PgSupportedVersion.GP_VERSION_7)
                    && newColumn.hasCompressOptions()) {
                StringBuilder sb = new StringBuilder();
                sb.append(getAlterTableColumn(false, name)).append(" SET");
                newColumn.appendCompressOptions(sb);
                script.addStatement(sb);
            } else {
                return ObjectState.RECREATE;
            }
        }

        boolean isNeedDropDefault = !Objects.equals(type, newColumn.type)
                && !Objects.equals(defaultValue, newColumn.defaultValue);
        if (isNeedDropDefault && null == generationOption) {
            compareDefaults(defaultValue, null, null, script);
        }
        AtomicBoolean isNeedDependencies = new AtomicBoolean();
        StringBuilder typeBuilder = new StringBuilder();
        compareTypes(this, newColumn, isNeedDependencies, typeBuilder, true, true, script.getSettings());
        if (!typeBuilder.isEmpty()) {
            script.addStatement(typeBuilder);
        }

        String oldDefault = isNeedDropDefault ? null : defaultValue;
        if (null == generationOption) {
            compareDefaults(oldDefault, newColumn.defaultValue, isNeedDependencies, script);
        } else if (!Objects.equals(oldDefault, newColumn.defaultValue)) {
            StringBuilder sql = new StringBuilder();
            sql.append(getAlterTableColumn(true, name));
            sql.append(" SET EXPRESSION AS (" + newColumn.defaultValue + ')');
            script.addStatement(sql);
        }
        compareNotNull(this, newColumn, script);
        compareStorages(storage, newColumn.storage, script);
        compareCompression(compression, newColumn.compression, script);

        alterPrivileges(newColumn, script);

        compareOptions(newColumn, script);
        compareForeignOptions(fOptions, newColumn.fOptions, script);
        if (!script.getSettings().isIgnoreColumnStatistics()) {
            compareStats(statistics, newColumn.statistics, script);
        }

        compareIdentity(identityType, newColumn.identityType, sequence, newColumn.sequence, script);
        appendAlterComments(newColumn, script);
        return getObjectState(isNeedDependencies.get(), script, startSize);
    }

    private boolean compareGenerationOption(PgColumn newColumn, ISettings settings) {
        if (!Objects.equals(generationOption, newColumn.generationOption)) {
            return false;
        }

        if (generationOption == null) {
            return true;
        }

        return Objects.equals(type, newColumn.type)
            && Objects.equals(collation, newColumn.collation)
            && (Objects.equals(defaultValue, newColumn.defaultValue)
                    || checkSyntaxVersion(settings, PgSupportedVersion.VERSION_17));
    }

    private boolean compareCompressOptions(PgColumn newColumn) {
        return Objects.equals(compressType, newColumn.compressType)
                && compressLevel == newColumn.compressLevel
                && blockSize == newColumn.blockSize;
    }

    /**
     * Writes SET/RESET options for column to StringBuilder
     *
     * @param isCreate if true SET options, else RESET
     * @param script   for collect sql statements
     */
    private void writeOptions(boolean isCreate, SQLScript script) {
        if (!options.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            // AT_SetOptions is on TimescaleDB's ONLY whitelist (process_utility.c:5074),
            // so this form keeps the word even when the setting drops it elsewhere.
            sb.append(getAlterTableColumn(isOnlyAcceptedByParent(), name));
            sb.append(isCreate ? " SET (" : " RESET (");
            for (Entry<String, String> option : options.entrySet()) {
                sb.append(option.getKey());
                if (isCreate && !option.getValue().isEmpty()) {
                    sb.append('=').append(option.getValue());
                }
                sb.append(", ");
            }
            sb.setLength(sb.length() - 2);
            sb.append(")");
            script.addStatement(sb);
        }
    }

    /**
     * Compare columns identity and write difference to StringBuilder.
     *
     * @param oldIdentityType old column identity type
     * @param newIdentityType new column identity type
     * @param oldSequence     old column identity sequence
     * @param newSequence     new column identity sequence
     * @param script          for collect sql statements
     */
    private void compareIdentity(String oldIdentityType, String newIdentityType,
                                 PgSequence oldSequence, PgSequence newSequence, SQLScript script) {
        if (!Objects.equals(oldIdentityType, newIdentityType)) {
            StringBuilder sb = new StringBuilder();
            sb.append(getAlterTableColumn(false, name));

            if (newIdentityType == null) {
                sb.append(" DROP IDENTITY");
                if (script.getSettings().isGenerateExists()) {
                    sb.append(" IF EXISTS");
                }
            } else if (oldIdentityType == null) {
                sb.append(" ADD GENERATED ")
                        .append(newIdentityType)
                        .append(" AS IDENTITY (")
                        .append("\n\tSEQUENCE NAME ")
                        .append(newSequence.getQualifiedName());
                newSequence.fillSequenceBody(sb);
                sb.append("\n)");
            } else {
                sb.append(" SET GENERATED ").append(newIdentityType);
            }
            script.addStatement(sb);
        }

        if (oldSequence != null && newSequence != null &&
                !Objects.equals(oldSequence, newSequence)) {
            var newName = newSequence.getName();
            if (!oldSequence.getName().equals(newName)) {
                script.addStatement(oldSequence.getRenameCommand(newName));
            }

            oldSequence.appendAlterSQL(newSequence, script);
        }
    }

    /**
     * Checks if this column can be joined with another column in a single ALTER statement.
     * <p>
     * The join is possible exactly while the type and the collation are the only
     * difference the migration writes, so the differences the settings keep out
     * of the script are no obstacle to it either.
     *
     * @param newColumn column to compare with
     * @param settings  settings of the migration
     * @return true if columns can be joined in one ALTER statement
     */
    public boolean isJoinable(PgColumn newColumn, ISettings settings) {
        return newColumn.type != null
                && (!Objects.equals(type, newColumn.type)
                || !Objects.equals(collation, newColumn.collation))
                && Objects.equals(defaultValue, newColumn.defaultValue)
                && Objects.equals(notNullConstraint != null, newColumn.notNullConstraint != null)
                && compareColOptions(newColumn, ColumnRelaxations.forMigrationTarget(settings))
                && Objects.equals(comment, newColumn.comment);
    }

    /**
     * Generates SQL for joining column changes in a single ALTER statement.
     *
     * @param sb               StringBuilder to append SQL to
     * @param newColumn        new column state
     * @param isNeedAlterTable whether to include ALTER TABLE prefix
     * @param isLastColumn     whether this is the last column in a multi-column ALTER
     * @param settings         generation settings
     */
    public void joinAction(StringBuilder sb, PgColumn newColumn, boolean isNeedAlterTable,
                           boolean isLastColumn, ISettings settings) {
        compareTypes(this, newColumn, new AtomicBoolean(), sb, isNeedAlterTable, isLastColumn, settings);
    }

    /**
     * Compares two columns types and collations and write difference to StringBuilder.
     * If the values are not equal, then the column will be changed with dependencies.
     * Adds warning as SQL comment.
     *
     * @param oldColumn           old column
     * @param newColumn           new column
     * @param isNeedDependencies  if set true, column will be changed with dependencies
     * @param sb                  StringBuilder for difference
     * @param isNeedAlterTable    if true ALTER TABLE sentence added before ALTER COLUMN
     * @param isLastColumn        if true will be added ";" in the end of ALTER COLUMN. If false then - ",".
     */
    private void compareTypes(PgColumn oldColumn, PgColumn newColumn, AtomicBoolean isNeedDependencies,
                              StringBuilder sb, boolean isNeedAlterTable, boolean isLastColumn, ISettings settings) {
        String oldType = oldColumn.type;
        String newType = newColumn.type;
        if (newType == null) {
            return;
        }

        String oldCollation = oldColumn.collation;
        String newCollation = newColumn.collation;

        if (!Objects.equals(oldType, newType) || (newCollation != null && !newCollation.equals(oldCollation))) {
            isNeedDependencies.set(true);
            sb.append(getAlterTableColumn(false, newColumn.name, isNeedAlterTable));
            sb.append(" TYPE ").append(newType);

            if (newCollation != null) {
                sb.append(COLLATE).append(newCollation);
            }

            if (settings.isPrintUsing() && !(parent instanceof IForeignTable)) {
                sb.append(" USING ").append(newColumn.getQuotedName())
                        .append("::").append(newType);
            }
            sb.append(isLastColumn ? ";" : ",");
            sb.append(" /* ").append(Messages.Table_TypeParameterChange.formatted(
                    newColumn.parent.getParent().getName() + '.' + newColumn.parent.getName(),
                    oldType, newType)).append(" */");
        }
    }

    /**
     * Compares two columns foreign options and write difference to StringBuilder.
     *
     * @param oldForeignOptions old column foreign options
     * @param newForeignOptions new column foreign options
     * @param script            collection for actions
     */
    private void compareForeignOptions(Map<String, String> oldForeignOptions, Map<String, String> newForeignOptions,
                                       SQLScript script) {
        if (!oldForeignOptions.isEmpty() || !newForeignOptions.isEmpty()) {
            oldForeignOptions.forEach((key, value) -> {
                if (newForeignOptions.containsKey(key)) {
                    String newValue = newForeignOptions.get(key);
                    if (!Objects.equals(value, newValue)) {
                        script.addStatement(getAlterOption("SET", key, newValue));
                    }
                } else {
                    script.addStatement(getAlterOption("DROP", key, ""));
                }
            });

            newForeignOptions.forEach((key, value) -> {
                if (!oldForeignOptions.containsKey(key)) {
                    script.addStatement(getAlterOption("ADD", key, value));
                }
            });
        }
    }

    private String getAlterOption(String action, String key, String value) {
        return ALTER_FOREIGN_OPTION.formatted(getAlterTableColumn(false, name), action, key, value);
    }

    /**
     * Compares not-null values of two columns and writes difference to script.
     *
     * @param oldColumn old column state
     * @param newColumn new column state
     * @param script    script for collect sql statements
     */
    private void compareNotNull(PgColumn oldColumn, PgColumn newColumn, SQLScript script) {
        boolean isOldNotNull = oldColumn != null && notNullConstraint != null;
        boolean isNewNotNull = newColumn != null && newColumn.notNullConstraint != null;

        if (!isNewNotNull && !isOldNotNull) {
            return;
        }

        // DROP
        if (isOldNotNull && !isNewNotNull) {
            script.addStatement(getAlterTableColumn(
                    isNeedOnly(script.getSettings()) && isOnlyAcceptedByParent(), name) + " DROP" + NOT_NULL);
            return;
        }

        // CREATE
        var newNotNullConstraint = newColumn.notNullConstraint;
        if (!isOldNotNull) {
            if (newColumn.defaultValue != null) {
                String sql = "UPDATE " + parent.getQualifiedName() +
                        "\n\tSET " + getQuotedName() +
                        " = DEFAULT WHERE " + getQuotedName() +
                        " IS" + NULL;
                script.addStatement(sql);
            }

            if (newNotNullConstraint.isComplexNotNull()) {
                newNotNullConstraint.getCreationSQL(script);
            } else {
                script.addStatement(getAlterTableColumn(false, name) + " SET" + NOT_NULL);
            }
            return;
        }

        // ALTER
        var newName = newNotNullConstraint.getName();
        if (!notNullConstraint.getName().equals(newName)) {
            script.addStatement(notNullConstraint.getRenameCommand(newName));
        }

        notNullConstraint.appendAlterSQL(newNotNullConstraint, script);
    }

    /**
     * Compares two columns default values and write difference to StringBuilder. If
     * the default values are not equal, and the new value is not null, then the
     * column will be changed with dependencies.
     *
     * @param oldDefault         old column default value
     * @param newDefault         new column default value
     * @param isNeedDependencies if set true, column will be changed with dependencies
     * @param script             for collect sql statements
     */
    private void compareDefaults(String oldDefault, String newDefault, AtomicBoolean isNeedDependencies, SQLScript script) {
        if (!Objects.equals(oldDefault, newDefault)) {
            StringBuilder sql = new StringBuilder();
            sql.append(getAlterTableColumn(isNeedOnly(script.getSettings()), name));
            if (newDefault == null) {
                sql.append(" DROP DEFAULT");
            } else {
                sql.append(" SET DEFAULT ").append(newDefault);
                isNeedDependencies.set(true);
            }
            script.addStatement(sql);
        }
    }

    /**
     * Compares two columns statistics and write difference to StringBuilder.
     * <p>
     * The two callers that produce an {@code ALTER} out of a difference - the
     * alter of a column and the branch of its drop that only undoes its local
     * settings - ask this only while
     * {@link ISettings#isIgnoreColumnStatistics()} is off. The caller that
     * creates a column asks it regardless, because the target the project
     * declares is the one deliberate statement about the column it makes, the
     * same rule the cache of a sequence follows.
     *
     * @param oldStat old column statistics
     * @param newStat new column statistics
     * @param script  for collect sql statements
     */
    private void compareStats(Integer oldStat, Integer newStat, SQLScript script) {
        Integer newStatValue = null;

        if (newStat != null && (!newStat.equals(oldStat))) {
            newStatValue = newStat;
        } else if (oldStat != null && newStat == null) {
            newStatValue = -1;
        }
        if (newStatValue != null) {
            script.addStatement(
                    getAlterTableColumn(isNeedOnly(script.getSettings()), name) + " SET STATISTICS " + newStatValue);
        }
    }

    /**
     * Compares two columns storages and writes difference to StringBuilder. If new
     * column doesn't have storage, adds warning as SQL comment.
     *
     * @param oldStorage old column storage
     * @param newStorage new column storage
     * @param script     for collect sql statements
     */
    private void compareStorages(String oldStorage, String newStorage, SQLScript script) {
        String oldNormalized = normalizeStorage(oldStorage);
        String newNormalized = normalizeStorage(newStorage);

        StringBuilder sql;
        if (newStorage == null && oldNormalized != null) {
            sql = new StringBuilder();
            sql.append(Messages.Storage_WarningUnableToDetermineStorageType.formatted(
                    parent.getName(), name));
            script.addStatement(sql);
        } else if (newStorage != null && !Objects.equals(newNormalized, oldNormalized)) {
            // the author's own spelling is what gets written, the normalized
            // one only decides whether anything gets written at all
            script.addStatement(
                    getAlterTableColumn(isNeedOnly(script.getSettings()), name) + " SET STORAGE " + newStorage);
        }
    }

    /**
     * The storage as the comparison, the hash and the generator all see it -
     * one question, asked in one place, because a comparison that finds a
     * difference the generator will not write is a difference no migration can
     * ever close.
     * <p>
     * Two spellings fold into one state here.
     * <p>
     * <b>Case.</b> {@code PgTablesReader} writes the word in upper case, while
     * a project file keeps it as written: {@code storage_option} is spelled out
     * of keywords, and the lexer folds identifiers only. The server reads both
     * as the same thing, so the model must too.
     * <p>
     * <b>The type's own default.</b> The reader fills this field only while
     * {@code attstorage} differs from the {@code typstorage} of the column's
     * type, so a column left at its type's default carries no storage at all on
     * the database side - and {@code STORAGE DEFAULT} is exactly how a project
     * file says that. Told apart, the two produce a {@code SET STORAGE DEFAULT}
     * on every run: the server sets {@code attstorage} back to where it already
     * was, and the next read says nothing again.
     * <p>
     * What this cannot fold is the type's default named outright - a project
     * that writes {@code STORAGE EXTENDED} on a {@code text} column. Which
     * value a type defaults to is a fact of the catalog, and the project side
     * has no catalog to ask; the database side keeps answering {@code null} and
     * the difference stands. Filling the field on the database side always
     * instead would trade this narrow case for a storage on every column of
     * every table.
     *
     * @param storage the storage as one of the sides spells it
     * @return the canonical spelling, or null for a column left at the default
     *         of its type
     */
    private static String normalizeStorage(String storage) {
        if (storage == null || DEFAULT_STORAGE.equalsIgnoreCase(storage)) {
            return null;
        }
        return storage.toUpperCase(Locale.ROOT);
    }

    private void compareCompression(String oldCompression, String newCompression, SQLScript script) {
        boolean only = isNeedOnly(script.getSettings());
        if (newCompression == null && oldCompression != null) {
            script.addStatement(getAlterTableColumn(only, name) + " SET COMPRESSION DEFAULT");
            return;
        }
        if (newCompression == null || newCompression.equalsIgnoreCase(oldCompression)) {
            return;
        }
        script.addStatement(getAlterTableColumn(only, name) + " SET COMPRESSION " + quote(newCompression));
    }

    /**
     * Returns the parent column for given column or null if given column hasn't
     * parent column.
     *
     * @param tbl table to search inheritance hierarchy from
     * @return parent column or null if no parent column exists
     */
    public PgColumn getParentCol(PgAbstractTable tbl) {
        for (Inherits in : tbl.getInherits()) {
            IStatement parent = getDatabase().getStatement(new ObjectReference(in.key(), in.value(), DbObjType.TABLE));
            if (parent == null) {
                var msg = Messages.PgColumn_no_such_object_of_inheritance.formatted(in.getQualifiedName());
                LOG.error(msg);
                continue;
            }

            PgAbstractTable parentTbl = (PgAbstractTable) parent;
            PgColumn parentCol = parentTbl.getColumn(name);
            if (parentCol == null) {
                parentCol = getParentCol(parentTbl);
            }
            if (parentCol != null) {
                // if not found continue searching through other inherit entries
                return parentCol;
            }
        }

        return null;
    }

    @Override
    public Map<String, String> getOptions() {
        return Collections.unmodifiableMap(options);
    }

    @Override
    public void addOption(String attribute, String value) {
        this.options.put(attribute, value);
        resetHash();
    }

    /**
     * Takes a storage parameter away, for the {@code ALTER COLUMN ... RESET (...)}
     * of a project file.
     * <p>
     * The counterpart of {@link #addOption(String, String)}, and the same reading
     * {@code PgAbstractTable.removeOption} gives the table's own half: a file
     * states the parameters the column ends up with, so one it resets has to
     * leave the model, or the database keeps a parameter the project no longer
     * sets.
     * <p>
     * A name that matches nothing is left alone rather than reported, which is
     * the server's answer too - measured on PostgreSQL 17.10 for the table's
     * parameters, resetting one that was never set raises nothing.
     *
     * @param option option name, spelled as {@link #addOption} received it
     */
    public void removeOption(String option) {
        if (options.containsKey(option)) {
            options.remove(option);
            resetHash();
        }
    }

    public Map<String, String> getForeignOptions() {
        return Collections.unmodifiableMap(fOptions);
    }

    /**
     * Adds a foreign table option to this column.
     *
     * @param attribute option name
     * @param value     option value
     */
    public void addForeignOption(String attribute, String value) {
        this.fOptions.put(attribute, value);
        resetHash();
    }

    @Override
    public void setCompressType(String compressType) {
        this.compressType = compressType;
        resetHash();
    }

    @Override
    public void setCompressLevel(int compressLevel) {
        this.compressLevel = compressLevel;
        resetHash();
    }

    @Override
    public void setBlockSize(int blockSize) {
        this.blockSize = blockSize;
        resetHash();
    }

    public boolean isInherit() {
        return isInherit;
    }

    public void setInherit(boolean isInherit) {
        this.isInherit = isInherit;
        resetHash();
    }

    @Override
    public boolean isNotNull() {
        return notNullConstraint != null;
    }

    public boolean isGenerated() {
        return generationOption != null;
    }

    /** {@code STORED} or {@code VIRTUAL}, or null when the column is not generated. */
    public String getGenerationOption() {
        return generationOption;
    }

    /**
     * The default as the comparison sees it, beside {@link #getDefaultValue()},
     * which is the text the DDL is written from. A copy that carried one
     * without the other would compare unequal to the column it was copied from.
     */
    public String getDefaultValueNormalized() {
        return defaultValueNormalized;
    }

    public String getCompression() {
        return compression;
    }

    public void setGenerationOption(String generationOption) {
        this.generationOption = generationOption;
        resetHash();
    }

    public void setStatistics(final Integer statistics) {
        this.statistics = statistics;
        resetHash();
    }

    public Integer getStatistics() {
        return statistics;
    }

    public String getStorage() {
        return storage;
    }

    public void setStorage(final String storage) {
        this.storage = storage;
        resetHash();
    }

    public PgSequence getSequence() {
        return sequence;
    }

    public void setSequence(final PgSequence sequence) {
        this.sequence = sequence;
        resetHash();
    }

    public void setIdentityType(final String identityType) {
        this.identityType = identityType;
        resetHash();
    }

    public String getIdentityType() {
        return identityType;
    }

    /**
     * The sequence is held here rather than in the schema, and the whole of it -
     * its cache among the rest - is written inside the definition of this
     * column.
     */
    @Override
    public boolean hasIdentitySequence() {
        return sequence != null;
    }

    public void setCompression(String compression) {
        this.compression = compression;
        resetHash();
    }

    public PgConstraintNotNull getNotNullConstraint() {
        return notNullConstraint;
    }

    public void setNotNullConstraint(PgConstraintNotNull notNullConstraint) {
        this.notNullConstraint = notNullConstraint;
        resetHash();
    }

    /**
     * @param defaultValue           the default or generation expression as written, used for DDL output
     * @param defaultValueNormalized the same expression normalized for comparison
     */
    public void setDefaultValue(String defaultValue, String defaultValueNormalized) {
        this.defaultValue = defaultValue;
        this.defaultValueNormalized = defaultValueNormalized;
        resetHash();
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setType(String type) {
        this.type = type;
        resetHash();
    }

    @Override
    public String getType() {
        return type;
    }

    public void setCollation(String collation) {
        this.collation = collation;
        resetHash();
    }

    public String getCollation() {
        return collation;
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.put(type);
        hasher.put(collation);
        hasher.put(defaultValueNormalized);
        hasher.put(statistics);
        hasher.put(normalizeStorage(storage));
        hasher.put(options);
        hasher.put(fOptions);
        hasher.put(compressType);
        hasher.put(compressLevel);
        hasher.put(blockSize);
        hasher.put(sequence);
        hasher.put(compression);
        hasher.put(identityType);
        hasher.put(isInherit);
        hasher.put(generationOption);
        hasher.put(notNullConstraint);
    }

    @Override
    public boolean compare(IStatement obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof PgColumn col && compare(col, ColumnRelaxations.none());
    }

    /**
     * Compares this state of the column, the one a migration would start from,
     * with the state that migration would produce, overlooking the differences
     * the given relaxations name.
     * <p>
     * A collation the target state does not name cannot be migrated.
     * {@link #compareTypes} writes the collation of the target state and nothing
     * else: with no collation named there it emits no collation clause at all,
     * deliberately, because a column declared without a collation and a column
     * declared with the default collation of its type are the same thing to this
     * tool and it must not reset the one to reach the other. Reporting the
     * difference would name a change no script can carry out. A generated column
     * is exempt: {@link #isGeneratedColumnChanged} recreates it on any collation
     * change, so there the difference is migratable.
     * <p>
     * The cache of an identity sequence is migratable and is overlooked only
     * because the operator asked for it, in which case
     * {@link PgSequence#appendAlterSQL} writes nothing for it either.
     *
     * @param target      the state the migration produces
     * @param relaxations the differences to overlook
     * @return true if the two states are equal up to those differences
     */
    public boolean compareIgnoring(PgColumn target, ColumnRelaxations relaxations) {
        return this == target || compare(target, relaxations);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Answered by the very relaxation the comparison applies, so that a reader
     * is never told a line is outside the migration on grounds the migration
     * itself did not use, see {@link #compareIgnoring}. The named collation has
     * to be on this side and the silence on the target side; the other way round
     * the migration writes the clause and there is nothing to say.
     * <p>
     * The cheap half of the answer is asked first. Every column of every table
     * shown in a comparison pane reaches this, and almost none of them names a
     * collation at all - while the full comparison behind it walks a dozen
     * fields and the identity sequence.
     */
    @Override
    public boolean differsOnlyInUnmigratableCollation(IColumn target) {
        return target instanceof PgColumn other
                && collation != null && other.collation == null
                // the collations differ, so the unrelaxed comparison is already
                // known to reject this pair and is not worth asking
                && compare(other, ColumnRelaxations.collationOnly());
    }

    /**
     * @param col         the other state of this column, the one a migration
     *                    would produce where the relaxations are direction bound
     * @param relaxations the differences to overlook
     */
    private boolean compare(PgColumn col, ColumnRelaxations relaxations) {
        return super.compare(col)
                && Objects.equals(type, col.type)
                && compareCollations(col, relaxations.unmigratableCollation())
                && Objects.equals(defaultValueNormalized, col.defaultValueNormalized)
                && compareColOptions(col, relaxations);
    }

    private boolean compareCollations(PgColumn col, boolean isColTheCollationTarget) {
        if (Objects.equals(collation, col.collation)) {
            return true;
        }
        return isColTheCollationTarget && col.collation == null
                && generationOption == null && col.generationOption == null;
    }

    private boolean compareColOptions(PgColumn col, ColumnRelaxations relaxations) {
        return (relaxations.columnStatistics() || Objects.equals(statistics, col.statistics))
                && Objects.equals(normalizeStorage(storage), normalizeStorage(col.storage))
                && options.equals(col.options)
                && fOptions.equals(col.fOptions)
                && compareCompressOptions(col)
                && compareSequences(col.sequence, relaxations.sequenceCache())
                && Objects.equals(compression, col.compression)
                && Objects.equals(identityType, col.identityType)
                && isInherit == col.isInherit
                && Objects.equals(generationOption, col.generationOption)
                && Objects.equals(notNullConstraint, col.notNullConstraint);
    }

    /**
     * Compares the identity sequences of two states of a column, optionally
     * without their cache.
     * <p>
     * The relaxed answer carries the hash guard of the sequence with it, for the
     * reason {@code Comparison} states: a comparison and a hash never quite
     * cover the same fields, and a pair only one of them tells apart must not be
     * called equal.
     */
    private boolean compareSequences(PgSequence other, boolean ignoreCache) {
        if (Objects.equals(sequence, other)) {
            return true;
        }
        return ignoreCache && sequence != null && other != null
                && sequence.compareIgnoringCache(other)
                && sequence.hashIgnoringCache() == other.hashIgnoringCache();
    }

    /**
     * Reports whether the project states a value of this column that the
     * settings declare the project's own.
     * <p>
     * Asked by the table before it copies itself, so that a table with nothing
     * to take over is handed on as it is. The question and the writing below
     * read the same two answers, so neither can take over what the other would
     * not have.
     *
     * @param project  the state of this column the project holds
     * @param settings the settings of the export
     * @return true if the project owns a value this column holds differently
     */
    boolean ownsAnythingOf(PgColumn project, ISettings settings) {
        return !Objects.equals(statistics, adoptedStatistics(project, settings))
                || sequence != adoptedSequence(project, settings);
    }

    /**
     * Writes into this column every value of it the project owns.
     * <p>
     * The one mutating step of the adoption, and the reason it is safe: this is
     * only ever called on a column of a freshly copied table, never on one of a
     * table a database still holds. The identity sequence is replaced by a copy
     * of itself rather than written into, because a copied column shares the
     * sequence of the column it was copied from.
     *
     * @param project  the state of this column the project holds
     * @param settings the settings of the export
     */
    void adoptFrom(PgColumn project, ISettings settings) {
        setStatistics(adoptedStatistics(project, settings));
        setSequence(adoptedSequence(project, settings));
    }

    private Integer adoptedStatistics(PgColumn project, ISettings settings) {
        return settings.isIgnoreColumnStatistics() ? project.statistics : statistics;
    }

    /**
     * The identity sequence this column is to carry: its own, or a copy of it
     * holding what the project owns. The sequence itself decides what that is,
     * so the cache of an identity sequence and the cache of a standalone one
     * cannot come to mean different things.
     */
    private PgSequence adoptedSequence(PgColumn project, ISettings settings) {
        if (sequence == null || project.sequence == null) {
            return sequence;
        }
        return (PgSequence) sequence.adoptUnmanaged(project.sequence, settings);
    }

    /**
     * This column under another name, for the {@code ALTER TABLE ... RENAME
     * COLUMN} of a project file. The name of a statement is final, so a rename
     * is a new object and every field of the old one has to be carried into it -
     * which is why this goes through {@link #getCopy(String)} and
     * {@link #copyCommon}, the two lists the ordinary copy uses, rather than a
     * third list of its own.
     * <p>
     * The {@code NOT NULL} constraint is the one thing copied instead of shared.
     * It reads the column it belongs to through its parent -
     * {@link PgConstraintNotNull#getDefinition()} writes
     * {@code getParent().getQuotedName()} - so a shared one would go on
     * describing the old name, and {@code AbstractStatement.computeNamesHash}
     * would hash it under the old name too.
     * <p>
     * Its own name is kept as it is, which is what the server does: measured on
     * PostgreSQL 18.4, after {@code RENAME COLUMN c1 TO r1} the automatically
     * named constraint is still {@code t_c1_not_null} while its definition reads
     * {@code NOT NULL r1}. The copy recomputes the derived name from the new
     * parent and therefore starts reporting a custom name, which is exactly what
     * the reader builds from that catalog row.
     *
     * @param newName the name to copy this column under
     * @return the copy, parentless, for the table to adopt
     */
    public PgColumn renamedCopy(String newName) {
        PgColumn copy = copyCommon(getCopy(newName));
        if (notNullConstraint != null) {
            var constraint = (PgConstraintNotNull) notNullConstraint.shallowCopy();
            copy.setNotNullConstraint(constraint);
            constraint.setParent(copy);
        }
        return copy;
    }

    @Override
    protected PgColumn getCopy() {
        return getCopy(name);
    }

    private PgColumn getCopy(String name) {
        PgColumn copy = new PgColumn(name);
        copy.type = type;
        copy.collation = collation;
        copy.setDefaultValue(defaultValue, defaultValueNormalized);
        copy.setStatistics(statistics);
        copy.setStorage(storage);
        copy.options.putAll(options);
        copy.fOptions.putAll(fOptions);
        copy.setCompressType(compressType);
        copy.setCompressLevel(compressLevel);
        copy.setBlockSize(blockSize);
        copy.setIdentityType(identityType);
        copy.setSequence(sequence);
        copy.setCompression(compression);
        copy.setInherit(isInherit);
        copy.setGenerationOption(generationOption);
        copy.setNotNullConstraint(notNullConstraint);
        return copy;
    }
}
