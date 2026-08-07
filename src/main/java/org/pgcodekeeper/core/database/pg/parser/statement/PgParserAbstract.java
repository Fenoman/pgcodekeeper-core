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

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.base.parser.CodeUnitToken;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.base.parser.statement.ParserAbstract;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.base.schema.Argument;
import org.pgcodekeeper.core.database.base.schema.SimpleColumn;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.*;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.database.pg.utils.PgDiffUtils;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.Pair;
import org.pgcodekeeper.core.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Abstract base class for PostgreSQL statement parsers that provides common
 * operations and utilities for parsing PostgreSQL database objects.
 * <p>
 * This class extends ParserAbstract to handle PostgreSQL-specific parsing
 * operations such as column definitions, data types, operators, and other
 * database constructs specific to PostgreSQL syntax.
 */
public abstract class PgParserAbstract extends ParserAbstract<PgDatabase> {

    /**
     * Constructs a new PostgreSQL parser with the specified database and settings.
     *
     * @param db       the PostgreSQL database object
     * @param settings the ISettings object
     */
    protected PgParserAbstract(PgDatabase db, ISettings settings) {
        super(db, settings);
    }

    protected void fillSimpleColumns(ISimpleColumnContainer cont, List<Index_columnContext> cols,
                                     List<All_opContext> operators, CommonTokenStream stream) {
        // we need this variable for take correct context from List
        int counter = 0;

        for (Index_columnContext col : cols) {
            SimpleColumn simpCol;
            var collate = col.column.collate_identifier();
            if (collate == null) {
                simpCol = new SimpleColumn(getFullCtxText(col.column),
                        PgParserUtils.normalizeWhitespaceUnquoted(col.column, stream));
            } else {
                simpCol = new SimpleColumn(getFullCtxText(col.column.vex()),
                        PgParserUtils.normalizeWhitespaceUnquoted(col.column.vex(0), stream));
                simpCol.setCollation(getFullCtxText(collate.collation));
                addDepSafe((AbstractStatement) cont, getIdentifiers(collate.collation), DbObjType.COLLATION);
            }

            var opClass = col.operator_class;
            if (opClass != null) {
                simpCol.setOpClass(opClass.getText());

                // only for index
                var opClassParams = col.storage_parameters();
                if (opClassParams != null) {
                    for (var param : opClassParams.storage_parameter_option()) {
                        simpCol.addOpClassParam(param.storage_parameter_name().getText(), param.vex().getText());
                    }
                }
            }

            // only for constraint exclude
            if (operators != null) {
                simpCol.setOperator(operators.get(counter).getText());
                counter++;
            }

            addNullOrdering(simpCol, col);
            cont.addColumn(simpCol);
        }
    }

    private static void addNullOrdering(SimpleColumn sCol, Index_columnContext col) {
        var ordSpec = col.order_specification();
        if (ordSpec != null) {
            sCol.setDesc(ordSpec.DESC() != null);
        }

        var nullOrd = col.null_ordering();
        if (nullOrd == null) {
            return;
        }

        if (sCol.isDesc()) {
            if (nullOrd.LAST() != null) {
                sCol.setNullsOrdering(" NULLS LAST");
            }
        } else if (nullOrd.FIRST() != null) {
            sCol.setNullsOrdering(" NULLS FIRST");
        }
    }

    /**
     * Processes option parameters into key-value pairs.
     *
     * @param options    the option strings to parse
     * @param c          the consumer to receive each key-value pair
     * @param isToast    whether these are TOAST options
     * @param forceQuote whether to force quoting of values
     * @param isQuoted   whether values are already quoted
     */
    public static void fillOptionParams(String[] options, BiConsumer<String, String> c,
                                        boolean isToast, boolean forceQuote, boolean isQuoted) {
        for (String pair : options) {
            int sep = pair.indexOf('=');
            String option;
            String value;
            if (sep == -1) {
                option = pair;
                value = "";
            } else {
                option = pair.substring(0, sep);
                value = pair.substring(sep + 1);
            }
            if (!isQuoted && (forceQuote || !PgDiffUtils.isValidId(value, false, false))) {
                // only quote non-ids, do not quote columns
                // pg_dump behavior
                value = Utils.quoteString(value);
            }
            fillOptionParams(value, option, isToast, c);
        }
    }

    /**
     * Processes a single storage parameter, in the one spelling both sides of
     * the comparison reach.
     * <p>
     * A storage parameter is stated twice in different hands. A project file
     * states it as its author typed it - {@code WITH (fillfactor=70)} - while
     * the database side arrives through
     * {@link #fillOptionParams(String[], BiConsumer, boolean, boolean, boolean)},
     * which quotes every value that is not an identifier, or, for an index,
     * through {@code pg_get_indexdef}, which quotes it in the server. Read as
     * written, the two never met: the comparison saw {@code 70} against
     * {@code '70'}, wrote {@code SET (fillfactor=70)}, and the catalog - which
     * stores {@code fillfactor=70} either way, measured on 17.10 - came back
     * unchanged, so the same statement was written again on the next run and on
     * every run after it. On a {@code PRIMARY KEY} or {@code UNIQUE} constraint,
     * where the parameters are part of what cannot be altered, the same
     * difference dropped and added the constraint, rebuilding its index every
     * time.
     * <p>
     * The canonical spelling is the quoted one, because it is the one the tool
     * already writes for every object it reads from a database and the one
     * {@code pg_dump} writes; taking the other direction would rewrite every
     * exported project file instead. The value is first read the way the server
     * reads it - a quoted literal keeps its text, a bare word is lowered, since
     * the server's scanner lowers an unquoted word before it reaches
     * {@code reloptions} (measured: {@code autovacuum_enabled=TRUE} is stored as
     * {@code true}) - and then quoted by the same rule the database side uses.
     * So {@code 70}, {@code '70'} and the catalog's own {@code 70} all end at
     * {@code '70'}, and {@code TRUE}, {@code true} and {@code 'true'} all end at
     * {@code 'true'}.
     * <p>
     * Only storage parameters take this road. A foreign {@code OPTIONS} value is
     * a string literal on both sides already - the parser reads {@code sconst}
     * with its quotes, and the readers of those objects pass
     * {@code forceQuote} - and a text search dictionary's options are read from
     * the catalog quoted as they were written, so quoting either again would
     * break a pair that already matches.
     *
     * @param value   the option value, as the statement wrote it
     * @param option  the option name
     * @param isToast whether this is a TOAST option
     * @param c       the consumer to receive the key-value pair
     */
    public static void fillStorageParam(String value, String option, boolean isToast,
                                        BiConsumer<String, String> c) {
        fillOptionParams(canonicalStorageParamValue(value), option, isToast, c);
    }

    /**
     * The spelling of a storage parameter value the model holds, whichever side
     * stated it. See {@link #fillStorageParam(String, String, boolean, BiConsumer)}.
     *
     * @param value the value as the statement wrote it, may be null or empty
     * @return the canonical spelling, null and empty unchanged
     */
    public static String canonicalStorageParamValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        String content;
        if (value.length() > 1 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            content = value.substring(1, value.length() - 1).replace("''", "'");
        } else {
            content = value.toLowerCase(Locale.ROOT);
        }

        // the rule the database side is quoted by, applied to the same reading
        return PgDiffUtils.isValidId(content, false, false) ? content : Utils.quoteString(content);
    }

    /**
     * Processes a single option parameter.
     *
     * @param value   the option value
     * @param option  the option name
     * @param isToast whether this is a TOAST option
     * @param c       the consumer to receive the key-value pair
     */
    public static void fillOptionParams(String value, String option, boolean isToast,
                                        BiConsumer<String, String> c) {
        String quotedOption = PgDiffUtils.getQuotedName(option);
        if (isToast) {
            quotedOption = "toast." + quotedOption;
        }
        c.accept(quotedOption, value);
    }

    /**
     * The deferrability the two clauses state, in the reading PostgreSQL gives
     * them, as the one tri-state answer the server can hold: {@code null} when
     * the object is not deferrable, {@code TRUE} when it is deferrable and
     * initially immediate, {@code FALSE} when it is deferrable and initially
     * deferred.
     * <p>
     * {@code INITIALLY DEFERRED} implies {@code DEFERRABLE}. The server's own
     * grammar takes deferrability from either word, so an object declared with
     * the short spelling reaches the catalog deferrable and comes back out
     * spelled in full. Measured on 17.10 for a table constraint - inline in a
     * {@code CREATE TABLE}, in an {@code ADD CONSTRAINT} and after an
     * {@code ALTER CONSTRAINT} - and again for a {@code CREATE CONSTRAINT
     * TRIGGER}, where {@code INITIALLY DEFERRED} alone reaches
     * {@code pg_trigger} as {@code tgdeferrable = t, tginitdeferred = t} and a
     * lone {@code DEFERRABLE} as {@code t / f}. Without the implication a file
     * writing either short form and the catalog row that same statement produces
     * could not compare equal, and no run could change it.
     * <p>
     * {@code INITIALLY IMMEDIATE} on its own does not fire it: measured, both
     * objects stay at {@code f / f}, the same state a statement naming neither
     * word arrives at.
     * <p>
     * {@code NOT DEFERRABLE INITIALLY DEFERRED} is not a fourth state to model.
     * The server refuses it outright - {@code constraint declared INITIALLY
     * DEFERRED must be DEFERRABLE}, measured on 17.10 for a table constraint, an
     * {@code ALTER CONSTRAINT} and a constraint trigger alike - so illegal input
     * is answered with a state a catalog can hold rather than with one it cannot.
     * <p>
     * This returns the reading and writes nothing, because the two objects that
     * need it store it differently: {@code PgTrigger.isImmediate} is this
     * tri-state itself, while a {@code PgConstraint} keeps a pair of booleans.
     * The implication is the part they share, so it is the part that is written
     * once.
     *
     * @param defer the {@code DEFERRABLE} clause, or null if none was written
     * @param init  the {@code INITIALLY} clause, or null if none was written
     * @return null when not deferrable, {@code TRUE} for initially immediate,
     *         {@code FALSE} for initially deferred
     */
    protected static Boolean readDeferrability(Table_deferrableContext defer, Table_initialy_immedContext init) {
        boolean initiallyDeferred = init != null && init.IMMEDIATE() == null;
        if (!initiallyDeferred && (defer == null || defer.NOT() != null)) {
            return null;
        }
        return !initiallyDeferred;
    }

    protected static void fillIncludingDepcy(Including_indexContext incl, AbstractStatement st, String schema, String table) {
        for (IdentifierContext inclCol : incl.identifier()) {
            st.addDependency(new ObjectReference(schema, table, inclCol.getText(), DbObjType.COLUMN));
        }
    }

    protected void addTypeDepcy(Data_typeContext ctx, AbstractStatement st) {
        Schema_qualified_name_nontypeContext qname = ctx.predefined_type().schema_qualified_name_nontype();
        if (qname != null && qname.identifier() != null) {
            addDepSafe(st, getIdentifiers(qname), DbObjType.TYPE);
        }
    }

    /**
     * Fill owner
     *
     * @param owner parser context with owner
     * @param st    object
     */
    protected void fillOwnerTo(IdentifierContext owner, AbstractStatement st) {
        if (owner == null || settings.isIgnorePrivileges() || ParserListenerMode.REF == getParserMode()) {
            return;
        }
        st.setOwner(owner.getText());
    }

    protected boolean parseBoolean(Boolean_valueContext boolCtx) {
        String bool = boolCtx.sconst() != null
                ? unquoteQuotedString(boolCtx.sconst()).getFirst()
                : boolCtx.getText();
        bool = bool.toLowerCase(Locale.ROOT);
        return switch (bool) {
            case "1", "true", "on", "yes" -> true;
            case "0", "false", "off", "no" -> false;
            default -> /* TODO throw instead? */ false;
        };
    }

    /**
     * Unquotes a string constant from a parser context and returns both the unquoted
     * string and the corresponding token.
     *
     * @param ctx the string constant context to unquote
     * @return a pair containing the unquoted string and its token
     */
    public static Pair<String, Token> unquoteQuotedString(SconstContext ctx) {
        TerminalNode string = ctx.StringConstant();
        if (string == null) {
            string = ctx.UnicodeEscapeStringConstant();
        }

        if (string != null) {
            String text = string.getText();
            int start = text.indexOf('\'') + 1;

            Token t = string.getSymbol();
            CodeUnitToken cuToken = (CodeUnitToken) t;
            CodeUnitToken copy = new CodeUnitToken(cuToken);

            copy.setCodeUnitStart(cuToken.getCodeUnitStart() + start);
            copy.setCodeUnitPositionInLine(cuToken.getCodeUnitPositionInLine() + start);
            copy.setCodeUnitStop(cuToken.getCodeUnitStop() - 1);

            return new Pair<>(PgDiffUtils.unquoteQuotedString(text, start), copy);
        }

        List<TerminalNode> dollarText = ctx.Text_between_Dollar();
        if (dollarText.isEmpty()) {
            Token closingDelimiter = ctx.EndDollarStringConstant().getSymbol();
            return new Pair<>("", closingDelimiter);
        }

        TerminalNode firstNode = dollarText.get(0);
        Token firstToken = firstNode.getSymbol();
        if (dollarText.size() == 1) {
            return new Pair<>(firstNode.getText(), firstToken);
        }

        Token lastToken = dollarText.get(dollarText.size() - 1).getSymbol();
        String text = firstToken.getInputStream().getText(Interval.of(
                firstToken.getStartIndex(), lastToken.getStopIndex()));
        return new Pair<>(text, firstToken);
    }

    /**
     * Extracts identifier contexts from a schema-qualified name context.
     *
     * @param qNameCtx the schema-qualified name context
     * @return a list of parser rule contexts representing the identifiers
     */
    public static List<ParserRuleContext> getIdentifiers(Schema_qualified_nameContext qNameCtx) {
        List<ParserRuleContext> ids = new ArrayList<>(3);
        ids.add(qNameCtx.identifier());
        ids.addAll(qNameCtx.identifier_reserved());
        return ids;
    }

    /**
     * Extracts identifier contexts from a schema-qualified non-type name context.
     *
     * @param qNameNonTypeCtx the schema-qualified non-type name context
     * @return a list of parser rule contexts representing the identifiers
     */
    public static List<ParserRuleContext> getIdentifiers(Schema_qualified_name_nontypeContext qNameNonTypeCtx) {
        List<ParserRuleContext> ids;
        Identifier_nontypeContext singleId = qNameNonTypeCtx.identifier_nontype();
        if (singleId != null) {
            ids = new ArrayList<>(1);
            ids.add(singleId);
        } else {
            ids = new ArrayList<>(2);
            ids.add(qNameNonTypeCtx.schema);
            ids.add(qNameNonTypeCtx.identifier_reserved_nontype());
        }
        return ids;
    }

    /**
     * Extracts identifier contexts from an operator name context.
     *
     * @param operQNameCtx the operator name context
     * @return a list of parser rule contexts representing the operator identifiers
     */
    public static List<ParserRuleContext> getIdentifiers(Operator_nameContext operQNameCtx) {
        List<ParserRuleContext> ids = new ArrayList<>(2);
        ids.add(operQNameCtx.schema_name);
        ids.add(operQNameCtx.operator);
        return ids;
    }

    /**
     * Extracts the normalized type name from a data type context, converting
     * PostgreSQL type aliases to their canonical forms.
     *
     * @param datatype the data type context
     * @return the normalized type name string
     */
    public static String getTypeName(Data_typeContext datatype) {
        String full = getFullCtxText(datatype);
        Predefined_typeContext typeCtx = datatype.predefined_type();

        String type = getFullCtxText(typeCtx);
        if (type.startsWith("\"")) {
            return full;
        }

        String newType = convertAlias(type);
        if (!Objects.equals(type, newType)) {
            return full.replace(type, newType);
        }

        return full;
    }

    private static String convertAlias(String type) {
        String alias = type.toLowerCase(Locale.ROOT);

        switch (alias) {
            case "int8":
                return "bigint";
            case "bool":
                return "boolean";
            case "float8":
                return "double precision";
            case "int", "int4":
                return "integer";
            case "float4":
                return "real";
            case "int2":
                return "smallint";
            default:
                break;
        }

        if (PgDiffUtils.startsWithId(alias, "varbit", 0)) {
            return "bit varying" + type.substring("varbit".length());
        }

        if (PgDiffUtils.startsWithId(alias, "varchar", 0)) {
            return "character varying" + type.substring("varchar".length());
        }

        if (PgDiffUtils.startsWithId(alias, "char", 0)) {
            return "character" + type.substring("char".length());
        }

        if (PgDiffUtils.startsWithId(alias, "decimal", 0)) {
            return "numeric" + type.substring("decimal".length());
        }

        if (PgDiffUtils.startsWithId(alias, "timetz", 0)) {
            return "time" + type.substring("timetz".length()) + " with time zone";
        }

        if (PgDiffUtils.startsWithId(alias, "timestamptz", 0)) {
            return "timestamp" + type.substring("timestamptz".length()) + " with time zone";
        }

        return type;
    }

    /**
     * Parses an operator signature from the operator name and arguments context.
     *
     * @param name            the operator name
     * @param operatorArgsCtx the operator arguments context
     * @return the formatted operator signature string
     */
    public static String parseOperatorSignature(String name, Operator_argsContext operatorArgsCtx) {
        PgOperator oper = new PgOperator(name);
        Data_typeContext leftType = null;
        Data_typeContext rightType = null;
        if (operatorArgsCtx != null) {
            leftType = operatorArgsCtx.left_type;
            rightType = operatorArgsCtx.right_type;
        }

        oper.setLeftArg(leftType == null ? null : getTypeName(leftType));
        oper.setRightArg(rightType == null ? null : getTypeName(rightType));
        return oper.getSignature();
    }

    /**
     * Parses function arguments from the function arguments context.
     *
     * @param argsContext the function arguments context
     * @return the formatted arguments string
     */
    public String parseArguments(Function_argsContext argsContext) {
        return parseSignature(null, argsContext);
    }

    /**
     * Parses a function signature from the function name and arguments context.
     *
     * @param name        the function name (can be null for unnamed functions)
     * @param argsContext the function arguments context
     * @return the formatted function signature string
     */
    public static String parseSignature(String name, Function_argsContext argsContext) {
        PgAbstractFunction function = new PgFunction(name == null ? "noname" : name);
        fillFuncArgs(argsContext.function_arguments(), function);
        if (argsContext.agg_order() != null) {
            fillFuncArgs(argsContext.agg_order().function_arguments(), function);
        }
        String signature = function.getSignature();
        if (name == null) {
            signature = signature.substring("noname".length());
        }
        return signature;
    }

    private static void fillFuncArgs(List<Function_argumentsContext> argsCtx, PgAbstractFunction function) {
        for (Function_argumentsContext argument : argsCtx) {
            String type = getTypeName(argument.data_type());
            Identifier_nontypeContext name = argument.identifier_nontype();
            function.addArgument(
                    new Argument(parseArgMode(argument.argmode()), name != null ? name.getText() : null, type));
        }
    }

    // for greenplum
    protected String parseDistribution(Distributed_clauseContext dist) {
        if (dist == null) {
            return null;
        }

        StringBuilder distribution = new StringBuilder();
        distribution.append("DISTRIBUTED ");
        if (dist.BY() != null) {
            distribution.append("BY (");
            for (Column_operator_classContext column_op_class : dist.column_operator_class()) {
                distribution.append(column_op_class.identifier().getText());
                Schema_qualified_nameContext opClassCtx = column_op_class.schema_qualified_name();
                if (opClassCtx != null) {
                    distribution.append(" ").append(opClassCtx.getText());
                }
                distribution.append(", ");
            }
            distribution.setLength(distribution.length() - 2);
            distribution.append(")");
        } else if (dist.RANDOMLY() != null) {
            distribution.append("RANDOMLY");
        } else {
            distribution.append("REPLICATED");
        }
        return distribution.toString();
    }

    @Override
    protected ObjectLocation getLocation(List<? extends ParserRuleContext> ids, DbObjType type, String action,
                                         boolean isDep, String signature, LocationType locationType) {
        ParserRuleContext nameCtx = QNameParser.getFirstNameCtx(ids);
        if (type == DbObjType.CAST) {
            return buildLocation(nameCtx, action, locationType,
                    new ObjectReference(getCastName((Cast_nameContext) nameCtx), DbObjType.CAST));
        }

        if (type == DbObjType.USER_MAPPING) {
            return buildLocation(nameCtx, action, locationType,
                    new ObjectReference(getUserMappingName((User_mapping_nameContext) nameCtx), DbObjType.USER_MAPPING));
        }
        return super.getLocation(ids, type, action, isDep, signature, locationType);
    }

    @Override
    protected String getNameWithSignature(String name, String signature) {
        if (signature != null) {
            // PG functions have a name with optional quoting, which is used when searching in the database
            name = PgDiffUtils.getQuotedName(name) + signature;
        }
        return name;
    }

    protected String getCastName(Cast_nameContext nameCtx) {
        return ICast.getSimpleName(getFullCtxText(nameCtx.source), getFullCtxText(nameCtx.target));
    }

    protected String getUserMappingName(User_mapping_nameContext nameCtx) {
        return (nameCtx.user_name() != null ? nameCtx.user_name().getText() : nameCtx.USER().getText()) + " SERVER "
                + nameCtx.identifier().getText();
    }

    @Override
    protected PgSchema getSchemaSafe(List<? extends ParserRuleContext> ids) {
        return (PgSchema) super.getSchemaSafe(ids);
    }

    @Override
    protected PgSchema createSchema(String name) {
        return new PgSchema(name);
    }

    @Override
    protected boolean isSystemSchema(String schema) {
        return PgDiffUtils.isSystemSchema(schema);
    }
}
