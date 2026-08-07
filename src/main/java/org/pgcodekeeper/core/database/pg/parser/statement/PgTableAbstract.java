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
package org.pgcodekeeper.core.database.pg.parser.statement;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.ActionContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Col_labelContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Collate_identifierContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Compression_identifierContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Constr_bodyContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Constraint_commonContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Data_typeContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Define_columnsContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Define_foreign_optionsContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Encoding_identifierContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Foreign_optionContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.IdentifierContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Identity_bodyContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Index_columnContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Index_parametersContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Like_optionContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.List_of_type_column_defContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Names_in_parensContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Nulls_distinctionContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Schema_qualified_nameContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Sequence_bodyContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Storage_directiveContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Storage_optionContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Storage_parameter_nameContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Storage_parameter_optionContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Storage_parametersContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Table_column_defContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Table_column_definitionContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Table_deferrableContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Table_enforcedContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Table_initialy_immedContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Table_of_type_column_defContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.VexContext;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgConstraintAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgVexAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractForeignTable;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractTable;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgConstraint;
import org.pgcodekeeper.core.database.pg.schema.PgConstraintCheck;
import org.pgcodekeeper.core.database.pg.schema.PgConstraintExclude;
import org.pgcodekeeper.core.database.pg.schema.PgConstraintFk;
import org.pgcodekeeper.core.database.pg.schema.PgConstraintNotNull;
import org.pgcodekeeper.core.database.pg.schema.PgConstraintPk;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgIndexParamContainer;
import org.pgcodekeeper.core.database.pg.schema.PgPartitionTable;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;
import org.pgcodekeeper.core.database.pg.utils.PgDiffUtils;
import org.pgcodekeeper.core.exception.UnresolvedReferenceException;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Abstract base class for PostgreSQL table-related statement parsers.
 * <p>
 * This class provides common functionality for parsing table definitions,
 * including columns, constraints, inheritance, and storage parameters.
 * It serves as the foundation for CREATE TABLE and ALTER TABLE parsers.
 */
public abstract class PgTableAbstract extends PgParserAbstract {

    private static final String COMMENTS = "COMMENTS";
    private static final String COMPRESSION = "COMPRESSION";
    private static final String DEFAULTS = "DEFAULTS";
    private static final String GENERATED = "GENERATED";
    private static final String IDENTITY = "IDENTITY";
    private static final String STATISTICS = "STATISTICS";
    private static final String STORAGE = "STORAGE";

    /**
     * What {@code INCLUDING ALL} names, which is every word of
     * {@code like_option} the model has somewhere to put. {@code CONSTRAINTS}
     * and {@code INDEXES} are the two it deliberately leaves out - see
     * {@link #fillLikeColumns}.
     */
    private static final Set<String> LIKE_ALL = Set.of(
            COMMENTS, COMPRESSION, DEFAULTS, GENERATED, IDENTITY, STATISTICS, STORAGE);

    private final CommonTokenStream stream;

    /**
     * Constructs a new TableAbstract parser.
     *
     * @param db       the PostgreSQL database object
     * @param stream   the token stream for parsing
     * @param settings the ISettings object
     */
    protected PgTableAbstract(PgDatabase db, CommonTokenStream stream, ISettings settings) {
        super(db, settings);
        this.stream = stream;
    }

    protected void fillTypeColumns(List_of_type_column_defContext columns,
            PgAbstractTable table, String schemaName, String tablespace) {
        if (columns == null) {
            return;
        }
        for (Table_of_type_column_defContext colCtx : columns.table_of_type_column_def()) {
            if (colCtx.tabl_constraint != null) {
                addTableConstraint(colCtx.tabl_constraint, table, schemaName, tablespace);
            } else {
                addColumn(colCtx.identifier().getText(), colCtx.constraint_common(), table, schemaName);
            }
        }
    }

    protected void addTableConstraint(Constraint_commonContext tblConstrCtx,
            PgAbstractTable table, String schemaName, String tablespace) {
        if (tblConstrCtx.constr_body().NULL() != null) {
            addNotNullTableConstraint(tblConstrCtx, table);
            return;
        }
        PgConstraint constrBlank = createTableConstraintBlank(tblConstrCtx);
        processTableConstraintBlank(tblConstrCtx, constrBlank, schemaName,
                table.getName(), tablespace, fileName);
        doSafe(PgAbstractTable::addChild, table, constrBlank);
    }

    protected void addNotNullTableConstraint(Constraint_commonContext tblConstrCtx, PgAbstractTable table) {
        var body = tblConstrCtx.constr_body();
        if (body.NOT() != null) {
            var colNameCtx = body.col_name;
            fillColNotNull(table, tblConstrCtx, colNameCtx);
        } else {
            throw new IllegalArgumentException(Messages.PgTableAbstract_unsupported_constraint_type);
        }
    }

    protected void fillColNotNull(PgAbstractTable table, Constraint_commonContext tblConstrCtx,
                                  Schema_qualified_nameContext colNameCtx) {
        var col = table != null ? (PgColumn) table.getColumn(colNameCtx.getText()) : null;
        if (col != null) {
            fillColNotNull(col, table, tblConstrCtx);
        } else if (table instanceof PgPartitionTable) {
            addColumn(colNameCtx.getText(), Collections.singletonList(tblConstrCtx), table, table.getSchemaName());
            col = table.getColumn(colNameCtx.getText());
            fillColNotNull(col, table, tblConstrCtx);
        }
    }

    protected void fillColNotNull(PgColumn col, PgAbstractTable table, Constraint_commonContext constraint) {
        var body = constraint.constr_body();

        if (body.NOT() == null) {
            return;
        }

        var constrIdentifier = constraint.identifier();
        String constrName = constrIdentifier != null ? constrIdentifier.getText() : null;

        var notNullConstraint = addSimpleNotNull(col, table.getName(), constrName);
        notNullConstraint.setNoInherit(body.inherit_option() != null);
        notNullConstraint.setNotValid(constraint.VALID() != null);
    }

    protected PgConstraintNotNull addSimpleNotNull(PgColumn col, String tableName, String name) {
        var notNull = name != null ? new PgConstraintNotNull(name) : new PgConstraintNotNull(tableName, col.getName());
        col.setNotNullConstraint(notNull);
        notNull.setParent(col);

        return notNull;
    }

    private void addTableConstraint(Constraint_commonContext ctx, PgColumn col,
            PgAbstractTable table, String schemaName) {
        Constr_bodyContext body = ctx.constr_body();
        PgConstraint constr = null;
        String colName = col.getName();

        VexContext def = body.default_expr;
        if (def != null) {
            // Same token-level normalization CHECK/EXCLUDE/index predicates and
            // the trigger WHEN and rule WHERE conditions already get: canonical
            // whitespace and upper case for the reserved words of the folded
            // range SQLLexer.ALL..WITH, so a re-cased or re-spaced column
            // DEFAULT no longer reads as changed.
            col.setDefaultValue(getExpressionText(def, stream),
                    PgParserUtils.normalizeWhitespaceUnquoted(def, stream));
            db.addAnalysisLauncher(new PgVexAnalysisLauncher(col, def, fileName));
        } else if (body.NULL() != null) {
            fillColNotNull(col, table, ctx);
        } else if (body.REFERENCES() != null) {
            IdentifierContext id = ctx.identifier();
            String constrName = id == null ? PgDiffUtils.getDefaultObjectName(table.getName(), colName, "fkey") : id.getText();
            constr = new PgConstraintFk(constrName);
            fillConstrFk((PgConstraintFk) constr, body, colName, schemaName, table.getName());
        } else if (body.UNIQUE() != null || body.PRIMARY() != null) {
            IdentifierContext id = ctx.identifier();
            String constrName;
            if (id != null) {
                constrName = id.getText();
            } else if (body.PRIMARY() != null) {
                constrName = PgDiffUtils.getDefaultObjectName(table.getName(), null, "pkey");
            } else {
                constrName = PgDiffUtils.getDefaultObjectName(table.getName(), colName, "key");
            }
            constr = new PgConstraintPk(constrName, body.PRIMARY() != null);
            fillConstrPk((PgConstraintPk) constr, body, col, colName, schemaName, table.getName());
        } else if (body.CHECK() != null) {
            IdentifierContext id = ctx.identifier();
            String constrName;
            if (id != null) {
                constrName = id.getText();
            } else {
                constrName = PgDiffUtils.getDefaultObjectName(table.getName(), colName, "check");
            }
            constr = new PgConstraintCheck(constrName);
            fillConstrCheck((PgConstraintCheck) constr, body, true);
            VexContext expCtx = body.expression;
            db.addAnalysisLauncher(new PgConstraintAnalysisLauncher(constr, expCtx, fileName));
        } else if (body.identity_body() != null) {
            Identity_bodyContext identity = body.identity_body();
            String name = PgDiffUtils.getDefaultObjectName(table.getName(), colName, "seq");
            for (Sequence_bodyContext bodyCtx : identity.sequence_body()) {
                if (bodyCtx.NAME() != null) {
                    name = QNameParser.getFirstName(getIdentifiers(bodyCtx.name));
                }
            }
            PgSequence sequence = new PgSequence(name);
            sequence.setDataType(col.getType());
            PgCreateSequence.fillSequence(sequence, identity.sequence_body());

            col.setSequence(sequence);
            col.setIdentityType(identity.ALWAYS() != null ? "ALWAYS" : "BY DEFAULT");
        } else if (body.GENERATED() != null) {
            col.setGenerationOption(body.STORED() != null ? "STORED" : "VIRTUAL");
            VexContext genExpr = body.vex();
            // a generation expression is held in the same field as a DEFAULT and
            // is normalized the same way, see above
            col.setDefaultValue(getExpressionText(genExpr, stream),
                    PgParserUtils.normalizeWhitespaceUnquoted(genExpr, stream));
            db.addAnalysisLauncher(new PgVexAnalysisLauncher(col, genExpr, fileName));
        }

        if (constr != null) {
            appendConstrCommon(ctx, constr);
            table.addChild(constr);
        }
    }

    protected void fillColumns(Define_columnsContext columnsCtx, PgAbstractTable table,
                               String schemaName, String tablespace) {
        for (Table_column_defContext colCtx : columnsCtx.table_column_def()) {
            if (colCtx.tabl_constraint != null) {
                addTableConstraint(colCtx.tabl_constraint, table, schemaName, tablespace);
            } else if (colCtx.table_column_definition() != null) {
                Table_column_definitionContext column = colCtx.table_column_definition();
                addColumn(column.identifier().getText(), column.data_type(), column.storage_option(),
                        column.collate_identifier(), column.compression_identifier(),
                        column.constraint_common(), column.encoding_identifier(),
                        column.define_foreign_options(), table, schemaName);
            } else {
                // the third alternative of table_column_def
                // (SQLParser.g4:1917-1921), which used to fall past both tests
                // above and leave the loop body empty
                fillLikeColumns(colCtx, table);
            }
        }

        Names_in_parensContext parentTable = columnsCtx.names_in_parens();
        if (parentTable != null) {
            for (Schema_qualified_nameContext nameInher : parentTable.names_references().schema_qualified_name()) {
                addInherit(table, getIdentifiers(nameInher));
            }
        }
    }

    /**
     * Reads {@code LIKE source_table [ INCLUDING ... ]}, the third alternative
     * of {@code table_column_def}.
     * <p>
     * Left unread it was the worst thing a {@code CREATE TABLE} could say,
     * because the loop above simply had no branch for it: the table came out of
     * the parser with zero columns and no dependency on the table it was copied
     * from. Against a database where those columns exist the comparison then
     * wrote an {@code ALTER TABLE ... DROP COLUMN} for every one of them,
     * measured.
     * <p>
     * The columns are copied here rather than the clause being remembered,
     * because that is what the server does: {@code LIKE} is a one-time copy and
     * leaves no trace in the catalogue, so a {@code pg_dump} of the result
     * writes the columns out in full. A model that kept the clause instead would
     * still differ from every database it was compared against.
     * <p>
     * What is copied follows the options one at a time. The name, the type and
     * the collation are unconditional, and so is {@code NOT NULL} - measured on
     * PostgreSQL 17.10, a bare {@code LIKE} carries it and no option turns it
     * off. The rest are the six the model holds per column: {@code DEFAULTS},
     * {@code GENERATED}, {@code IDENTITY}, {@code STORAGE},
     * {@code COMPRESSION}, {@code STATISTICS} and {@code COMMENTS}.
     * {@code ALL} names every one of them; {@code EXCLUDING} takes one away,
     * and the options are read left to right as the server reads them.
     * <p>
     * {@code CONSTRAINTS} and {@code INDEXES} are deliberately not copied, and
     * this is the boundary of the fix rather than an oversight. Both name
     * objects, and PostgreSQL gives the copies names of its own choosing; the
     * model cannot predict them, so a copy would either duplicate the source's
     * name - which makes the {@code CREATE} this model writes illegal - or
     * invent one, which the comparison would drop and recreate on every run
     * against the real database. The columns are the part that can be right, and
     * they are the part whose absence produced a {@code DROP COLUMN}.
     * <p>
     * The dependency is registered whether or not the source resolves, because
     * it is a reference this statement makes; the source has to resolve for the
     * copy, and an unknown name is reported the way every other unresolved name
     * is. A source read after this file leaves the table as it was before this
     * change - empty - and says so.
     *
     * @param colCtx the {@code LIKE} element
     * @param table  the table being defined
     */
    private void fillLikeColumns(Table_column_defContext colCtx, PgAbstractTable table) {
        List<ParserRuleContext> srcIds = getIdentifiers(colCtx.schema_qualified_name());
        addDepSafe(table, srcIds, DbObjType.TABLE);

        if (ParserListenerMode.REF == getParserMode()) {
            return;
        }

        PgAbstractTable src = getSafe(PgSchema::getTable, getSchemaSafe(srcIds),
                QNameParser.getFirstNameCtx(srcIds));

        Set<String> including = readLikeOptions(colCtx.like_option());
        for (IColumn column : src.getColumns()) {
            table.addColumn(copyLikeColumn((PgColumn) column, including, table.getName()));
        }
    }

    /**
     * The set of {@code INCLUDING} words in force after the whole option list
     * has been read.
     * <p>
     * {@code ALL} stands for every word, and the list is applied left to right,
     * so {@code INCLUDING ALL EXCLUDING DEFAULTS} is every word but that one -
     * the order the server applies them in.
     */
    private static Set<String> readLikeOptions(List<Like_optionContext> options) {
        Set<String> including = new HashSet<>();
        for (Like_optionContext option : options) {
            Set<String> named = option.ALL() != null ? LIKE_ALL
                    : Set.of(option.getStop().getText().toUpperCase(Locale.ROOT));
            if (option.INCLUDING() != null) {
                including.addAll(named);
            } else {
                including.removeAll(named);
            }
        }
        return including;
    }

    /**
     * A copy of one column of the source under the options in force.
     * <p>
     * Built field by field rather than through {@code PgColumn.getCopy}, because
     * the two answer different questions: a copy carries everything the column
     * has, while this carries what the statement asked for. The {@code NOT NULL}
     * is copied as an object of its own, since the model hangs it off the column
     * and a shared instance would give two tables one constraint.
     */
    private static PgColumn copyLikeColumn(PgColumn src, Set<String> including, String tableName) {
        PgColumn copy = new PgColumn(src.getName());
        copy.setType(src.getType());
        copy.setCollation(src.getCollation());

        PgConstraintNotNull notNull = src.getNotNullConstraint();
        if (notNull != null) {
            // rebuilt rather than copied, because the copy would otherwise carry
            // the source's constraint name - a second constraint of that name in
            // the same schema, which the server refuses. The name here is the
            // one the model derives for a NOT NULL nobody named, built from this
            // table rather than the one copied from
            var copied = new PgConstraintNotNull(tableName, src.getName());
            copied.setNoInherit(notNull.isNoInherit());
            copy.setNotNullConstraint(copied);
            copied.setParent(copy);
        }

        boolean isGenerated = src.isGenerated();
        if (including.contains(isGenerated ? GENERATED : DEFAULTS)) {
            // one field holds both, so which word admits it depends on which
            // kind the source column is
            copy.setDefaultValue(src.getDefaultValue(), src.getDefaultValueNormalized());
            copy.setGenerationOption(src.getGenerationOption());
        }
        if (including.contains(IDENTITY)) {
            copy.setIdentityType(src.getIdentityType());
            copy.setSequence(src.getSequence());
        }
        if (including.contains(STORAGE)) {
            copy.setStorage(src.getStorage());
        }
        if (including.contains(COMPRESSION)) {
            copy.setCompression(src.getCompression());
        }
        if (including.contains(STATISTICS)) {
            copy.setStatistics(src.getStatistics());
        }
        if (including.contains(COMMENTS)) {
            copy.setComment(src.getComment());
        }
        return copy;
    }

    protected void addColumn(String columnName, Data_typeContext datatype, Storage_optionContext storage,
                             Collate_identifierContext collate, Compression_identifierContext compression,
                             List<Constraint_commonContext> constraints, Encoding_identifierContext encOptions,
                             Define_foreign_optionsContext options, PgAbstractTable table, String schemaName) {
        PgColumn col = new PgColumn(columnName);
        if (datatype != null) {
            col.setType(getTypeName(datatype));
            addTypeDepcy(datatype, col);
        }
        if (storage != null) {
            col.setStorage(storage.getText());
        }
        if (compression != null && compression.compression_method != null) {
            col.setCompression(compression.compression_method.getText());
        }
        if (collate != null) {
            col.setCollation(getFullCtxText(collate.collation));
            addDepSafe(col, getIdentifiers(collate.collation), DbObjType.COLLATION);
        }
        for (Constraint_commonContext column_constraint : constraints) {
            addTableConstraint(column_constraint, col, table, schemaName);
        }
        if (options != null && table instanceof PgAbstractForeignTable) {
            for (Foreign_optionContext option : options.foreign_option()) {
                var opt = option.sconst();
                String value = opt == null ? "" : opt.getText();
                fillOptionParams(value, option.col_label().getText(), false, col::addForeignOption);
            }
        }
        if (encOptions != null) {
            for (Storage_directiveContext option : encOptions.storage_directive()) {
                if (option.compress_type != null) {
                    col.setCompressType(option.compress_type.getText());
                } else if (option.compress_level != null) {
                    col.setCompressLevel(Integer.parseInt(option.compress_level.getText()));
                } else if (option.block_size != null) {
                    col.setBlockSize(Integer.parseInt(option.block_size.getText()));
                }
            }
        }

        doSafe(PgAbstractTable::addColumn, table, col);
    }

    protected void addColumn(String columnName, List<Constraint_commonContext> constraints,
            PgAbstractTable table, String schemaName) {
        addColumn(columnName, null, null, null, null, constraints, null, null, table, schemaName);
    }

    /**
     * Reads a list of storage parameters into the table.
     * <p>
     * Shared by the two statements that carry one - the {@code WITH (...)} of a
     * {@code CREATE TABLE} and the {@code SET (...)} of an {@code ALTER} - so
     * that the two spellings build one model. Two of the parameters do not go
     * into the option map at all and that is what makes sharing the routine
     * worth more than the six lines it saves: {@code OIDS} is a field of the
     * table, and a {@code toast.} parameter is held under its prefixed name.
     *
     * @param options the parameters the statement lists
     * @param table   the table they are stated of
     */
    protected void parseOptions(List<Storage_parameter_optionContext> options, PgAbstractTable table) {
        for (Storage_parameter_optionContext option : options) {
            Storage_parameter_nameContext key = option.storage_parameter_name();
            List<Col_labelContext> optionIds = key.col_label();
            VexContext valueCtx = option.vex();
            String value = valueCtx == null ? "" : valueCtx.getText();
            String optionText = key.getText();
            if ("OIDS".equalsIgnoreCase(optionText)) {
                if ("TRUE".equalsIgnoreCase(value) || "'TRUE'".equalsIgnoreCase(value)) {
                    table.setHasOids(true);
                }
            } else if ("toast".equals(QNameParser.getSecondName(optionIds))) {
                fillStorageParam(value, QNameParser.getFirstName(optionIds), true, table::addOption);
            } else {
                fillStorageParam(value, optionText, false, table::addOption);
            }
        }
    }

    protected void addInherit(PgAbstractTable table, List<ParserRuleContext> idsInh) {
        String inhSchemaName = getSchemaNameSafe(idsInh);
        String inhTableName = QNameParser.getFirstName(idsInh);
        table.addInherits(inhSchemaName, inhTableName);
        addDepSafe(table, idsInh, DbObjType.TABLE);
    }

    /**
     * Creates a blank constraint object based on the constraint type in the context.
     *
     * @param ctx the constraint common context
     * @return a new constraint object of the appropriate type
     * @throws IllegalArgumentException if the constraint type is not supported
     */
    protected static PgConstraint createTableConstraintBlank(Constraint_commonContext ctx) {
        IdentifierContext id = ctx.identifier();
        String constrName = id == null ? "" : id.getText();

        var body = ctx.constr_body();
        if (body.PRIMARY() != null || body.UNIQUE() != null) {
            return new PgConstraintPk(constrName, body.PRIMARY() != null);
        }
        if (body.FOREIGN() != null) {
            return new PgConstraintFk(constrName);
        }
        if (body.EXCLUDE() != null) {
            return new PgConstraintExclude(constrName);
        }
        if (body.CHECK() != null) {
            return new PgConstraintCheck(constrName);
        }

        throw new IllegalArgumentException(Messages.PgTableAbstract_unsupported_constraint_type);
    }

    protected void processTableConstraintBlank(Constraint_commonContext ctx,
                                               PgConstraint constrBlank, String schemaName, String tableName,
                                               String tablespace, String location) {
        Constr_bodyContext constrBody = ctx.constr_body();

        if (constrBlank instanceof PgConstraintFk fk) {
            fillConstrFk(fk, constrBody, null, schemaName, tableName);
        } else if (constrBlank instanceof PgConstraintExclude exclude) {
            fillConstrExcl(exclude, constrBody, schemaName, tableName);
            for (Index_columnContext col : constrBody.index_column()) {
                db.addAnalysisLauncher(new PgConstraintAnalysisLauncher(constrBlank, col.vex(), location));
                Storage_parametersContext params = col.storage_parameters();
                if (params != null) {
                    for (Storage_parameter_optionContext o : params.storage_parameter_option()) {
                        db.addAnalysisLauncher(new PgConstraintAnalysisLauncher(constrBlank, o.vex(), location));
                    }
                }
            }
        } else if (constrBlank instanceof PgConstraintPk pk) {
            fillConstrPk(pk, constrBody, null, null, schemaName, tableName);
        } else if (constrBlank instanceof PgConstraintCheck check) {
            fillConstrCheck(check, constrBody, false);
        }

        if (tablespace != null) {
            Index_parametersContext param = constrBody.index_parameters();
            if (param == null || param.USING() == null) {
                ((PgIndexParamContainer) constrBlank).setTablespace(tablespace);
            }
        }

        appendConstrCommon(ctx, constrBlank);
        constrBlank.setNotValid(ctx.VALID() != null);

        VexContext exp = constrBody.vex();
        if (exp != null) {
            db.addAnalysisLauncher(new PgConstraintAnalysisLauncher(constrBlank, exp, location));
        }
    }

    private void fillConstrFk(PgConstraintFk constrFk, Constr_bodyContext body, String columnName, String schemaName,
                              String tableName) {
        Schema_qualified_nameContext tblRef = body.schema_qualified_name();
        List<ParserRuleContext> ids = getIdentifiers(tblRef);

        String refSchemaName = QNameParser.getSchemaName(ids);
        if (refSchemaName == null && columnName != null) {
            return;
        }

        String refTableName = QNameParser.getFirstName(ids);

        ObjectLocation loc = addObjReference(ids, DbObjType.TABLE, null);
        ObjectReference fTable = loc.getObjectReference();
        constrFk.setForeignSchema(refSchemaName);
        constrFk.setForeignTable(refTableName);
        constrFk.addDependency(fTable);

        var cols = body.col_period;
        if (columnName != null) {
            constrFk.addColumn(columnName);
            constrFk.addDependency(new ObjectReference(schemaName, tableName, columnName, DbObjType.COLUMN));
        } else if (cols != null) {
            for (Schema_qualified_nameContext name : cols.schema_qualified_name()) {
                String colName = QNameParser.getFirstName(getIdentifiers(name));
                constrFk.addDependency(new ObjectReference(schemaName, tableName, colName, DbObjType.COLUMN));
                constrFk.addColumn(colName);
            }
            if (cols.name_with_period() != null) {
                String colName = QNameParser.getFirstName(
                        getIdentifiers(cols.name_with_period().schema_qualified_name()));
                constrFk.addDependency(new ObjectReference(schemaName, tableName, colName, DbObjType.COLUMN));
                constrFk.setPeriodColumn(colName);
            }
        }

        var refs = body.ref_period;
        if (refs != null) {
            List<Schema_qualified_nameContext> columns = refs.schema_qualified_name();
            if (columnName != null && columns.size() != 1) {
                throw new UnresolvedReferenceException(Messages.PgTableAbstract_number_columns_not_match, tblRef.start);
            }

            for (Schema_qualified_nameContext column : columns) {
                var fColumn = QNameParser.getFirstName(getIdentifiers(column));
                constrFk.addForeignColumn(fColumn);
                constrFk.addDependency(new ObjectReference(refSchemaName, refTableName, fColumn, DbObjType.COLUMN));
            }
            if (refs.name_with_period() != null) {
                var periodColName = QNameParser.getFirstName(
                        getIdentifiers(refs.name_with_period().schema_qualified_name()));
                constrFk.setPeriodRefColumn(periodColName);
                constrFk.addDependency(new ObjectReference(refSchemaName, refTableName, periodColName, DbObjType.COLUMN));
            }
        }

        if (body.FULL() != null) {
            constrFk.setMatch("FULL");
        } else if (body.SIMPLE() != null) {
            constrFk.setMatch("SIMPLE");
        }

        for (var chAct : body.changed_action()) {
            var action = chAct.action();
            if (chAct.DELETE() != null) {
                constrFk.setDelAction(getAction(action));
                var columns = action.col;
                if (columns != null) {
                    for (var column : columns.names_references().schema_qualified_name()) {
                        constrFk.addDelActCol(QNameParser.getFirstName(getIdentifiers(column)));
                    }
                }
            } else {
                constrFk.setUpdAction(getAction(action));
            }
        }
    }

    private void fillConstrPk(PgConstraintPk constrPk, Constr_bodyContext body, PgColumn col, String colName,
                              String schemaName, String tableName) {
        if (body.PRIMARY() != null) {
            if (col != null) {
                addSimpleNotNull(col, tableName, null);
            }
        } else {
            Nulls_distinctionContext dist = body.nulls_distinction();
            constrPk.setDistinct(dist != null && dist.NOT() != null);
        }

        if (colName != null) {
            constrPk.addColumn(colName);
            constrPk.addDependency(new ObjectReference(schemaName, tableName, colName, DbObjType.COLUMN));
        } else if (body.col_overlaps != null) {
            var cols = body.col_overlaps;
            for (Schema_qualified_nameContext name : cols.schema_qualified_name()) {
                String columnName = QNameParser.getFirstName(getIdentifiers(name));
                constrPk.addDependency(new ObjectReference(schemaName, tableName, columnName, DbObjType.COLUMN));
                constrPk.addColumn(columnName);
            }
            if (cols.name_without_overlaps() != null) {
                String withoutOverlaps = QNameParser.getFirstName(getIdentifiers(cols.name_without_overlaps()
                        .schema_qualified_name()));
                constrPk.addDependency(new ObjectReference(schemaName, tableName, withoutOverlaps, DbObjType.COLUMN));
                constrPk.setWithoutOverlapsColumn(withoutOverlaps);
            }
        }
        // else: a column list is optional in the grammar because "PRIMARY KEY/UNIQUE
        // USING INDEX <name>" adopts an existing index instead of listing columns of
        // its own - same "USING INDEX has two shapes" case handled in fillParam() below,
        // just one statement earlier. There is nothing to fill in here either.

        fillParam(constrPk, body.index_parameters(), schemaName, tableName);
    }

    private void fillConstrCheck(PgConstraintCheck constrCheck, Constr_bodyContext constrBody, boolean isNeedParens) {
        String open = isNeedParens ? "(" : "";
        String close = isNeedParens ? ")" : "";
        // The parens are part of the text on both sides, so they have to wrap
        // the normalized form as well - otherwise one side carries them and the
        // other does not, and every CHECK looks changed.
        String expr = open + getFullCtxText(constrBody.expression) + close;
        String normalized = open
                + PgParserUtils.normalizeWhitespaceUnquoted(constrBody.expression, stream)
                + close;
        constrCheck.setExpression(expr, normalized);
        constrCheck.setInherit(constrBody.inherit_option() == null);
    }

    private void fillConstrExcl(PgConstraintExclude constrExcl, Constr_bodyContext body, String schemaName,
                                String tableName) {
        if (body.index_method != null) {
            constrExcl.setIndexMethod(body.index_method.getText());
        }
        fillSimpleColumns(constrExcl, body.index_column(), body.all_op(), stream);
        fillParam(constrExcl, body.index_parameters(), schemaName, tableName);
        if (body.where != null) {
            constrExcl.setPredicate(getFullCtxText(body.exp),
                    PgParserUtils.normalizeWhitespaceUnquoted(body.exp, stream));
        }
    }

    private void fillParam(PgIndexParamContainer constr, Index_parametersContext parameters, String schemaName,
                           String tableName) {
        if (parameters.including_index() != null) {
            fillIncludingDepcy(parameters.including_index(), (AbstractStatement) constr, schemaName, tableName);
            for (var incl : parameters.including_index().identifier()) {
                constr.addInclude(incl.getText());
            }
        }
        if (parameters.with_storage_parameter() != null) {
            var stParams = parameters.with_storage_parameter();
            if (stParams != null && !stParams.isEmpty()) {
                for (var stParam : stParams.storage_parameters().storage_parameter_option()) {
                    var value = stParam.vex();
                    constr.addParam(stParam.storage_parameter_name().getText(),
                            canonicalStorageParamValue(value != null ? value.getText() : null));
                }
            }
        }
        // USING INDEX has two shapes and only one of them is a tablespace.
        // "USING INDEX <name>" adopts an existing index; PostgreSQL reports the
        // resulting key as an ordinary one afterwards and pg_dump writes it as
        // ordinary too, so the adopted name carries no meaning for comparison -
        // but the constraint's own getDefinition() still needs it to regenerate
        // a statement PostgreSQL accepts, since a bare "PRIMARY KEY"/"UNIQUE"
        // with neither a column list nor an adopted index is a syntax error.
        // Asking USING() and then dereferencing table_space() on the other shape
        // used to lose the whole statement to a swallowed NPE.
        if (parameters.table_space() != null) {
            constr.setTablespace(parameters.table_space().identifier().getText());
        } else if (parameters.schema_qualified_name() != null && constr instanceof PgConstraintPk pk) {
            pk.setUsingIndexName(QNameParser.getFirstName(getIdentifiers(parameters.schema_qualified_name())));
        }
    }

    private String getAction(ActionContext action) {
        if (action.cascade_restrict() != null) {
            return action.cascade_restrict().CASCADE() != null ? "CASCADE" : "RESTRICT";
        }
        if (action.SET() != null) {
            return action.NULL() != null ? "SET NULL" : "SET DEFAULT";
        }
        return null;
    }

    private static void appendConstrCommon(Constraint_commonContext ctx, PgConstraint constr) {
        setDeferrability(constr, ctx.table_deferrable(), ctx.table_initialy_immed());
        Table_enforcedContext enf = ctx.table_enforced();
        constr.setNotEnforced(enf != null && enf.NOT() != null);
    }

    /**
     * Deferrability as a constraint stores it - a pair of booleans - out of the
     * one reading of the two clauses that
     * {@link PgParserAbstract#readDeferrability} gives them. One method rather
     * than one per statement because the same two clauses are written in three
     * places here - inline beside a column, after a table constraint, and on an
     * {@code ALTER CONSTRAINT} - and a second copy of the formula is a second
     * answer waiting to happen: the two spellings of one constraint have to
     * build one model.
     * <p>
     * The pair is the constraint's own encoding of the three states the server
     * holds, and it can spell a fourth the server refuses. That is why the
     * implication itself lives one level up, where the constraint trigger reads
     * it too, and only the encoding is written here.
     *
     * @param constr the constraint the clauses are stated of
     * @param defer  the {@code DEFERRABLE} clause, or null if none was written
     * @param init   the {@code INITIALLY} clause, or null if none was written
     */
    protected static void setDeferrability(PgConstraint constr, Table_deferrableContext defer,
                                           Table_initialy_immedContext init) {
        Boolean immediate = readDeferrability(defer, init);
        constr.setDeferrable(immediate != null);
        constr.setInitially(Boolean.FALSE.equals(immediate));
    }
}