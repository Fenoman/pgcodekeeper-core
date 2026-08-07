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
package org.pgcodekeeper.core.database.ch.parser.statement;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.base.parser.statement.ParserAbstract;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.ch.parser.ChParserUtils;
import org.pgcodekeeper.core.database.ch.parser.generated.CHParser.*;
import org.pgcodekeeper.core.database.ch.parser.launcher.ChExpressionAnalysisLauncher;
import org.pgcodekeeper.core.database.ch.schema.*;
import org.pgcodekeeper.core.database.ch.utils.ChDiffUtils;
import org.pgcodekeeper.core.settings.ISettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Abstract base class for ClickHouse SQL statement parsers.
 * Provides common functionality for parsing ClickHouse-specific database objects
 * including tables, columns, engines, constraints, and indexes.
 */
public abstract class ChParserAbstract extends ParserAbstract<ChDatabase> {

    /**
     * The stream the parsed contexts came from, needed to normalize an
     * expression for comparison. Null whenever the constructing statement did
     * not supply one, and that is all this field says about a statement: one
     * that passes null may still hold expressions, it simply does not normalize
     * them. {@link #normalize(ParserRuleContext)} throws on a null stream
     * rather than silently falling back to the raw text, so such a statement
     * has to be given the stream before it can start.
     */
    protected final CommonTokenStream stream;

    /**
     * For statements that normalize nothing they store.
     * <p>
     * Which is not the same as storing no expression. {@link ChCreateSchema}
     * keeps the {@code ENGINE} clause of a database - assembled by hand out of
     * the text of each argument - and still passes null here, because the
     * database side of that clause is never parsed: {@code ChSchemasReader}
     * takes {@code engine_full} as a string. Normalizing the project side alone
     * would make every schema read as changed.
     *
     * @param db       the ClickHouse database schema being processed
     * @param settings parsing configuration settings
     */
    protected ChParserAbstract(ChDatabase db, ISettings settings) {
        this(db, null, settings);
    }

    /**
     * @param db       the ClickHouse database schema being processed
     * @param stream   the token stream the contexts came from
     * @param settings parsing configuration settings
     */
    protected ChParserAbstract(ChDatabase db, CommonTokenStream stream, ISettings settings) {
        super(db, settings);
        this.stream = stream;
    }

    /**
     * Extracts identifier contexts from a qualified name.
     *
     * @param qNameCtx the qualified name context to process
     * @return list of identifier contexts from the qualified name
     */
    public static List<ParserRuleContext> getIdentifiers(Qualified_nameContext qNameCtx) {
        List<ParserRuleContext> ids = new ArrayList<>(3);
        ids.addAll(qNameCtx.identifier());
        return ids;
    }

    protected ChColumn getColumn(Table_column_defContext column) {
        List<ParserRuleContext> ids = getIdentifiers(column.qualified_name());
        var col = new ChColumn(QNameParser.getFirstName(ids));
        Data_type_exprContext typeExpr = column.data_type_expr();
        var dataType = typeExpr.data_type();
        if (dataType != null) {
            setDataType(col, dataType);
            var defType = typeExpr.table_column_property_expr();
            if (defType != null) {
                if (defType.DEFAULT() != null) {
                    col.setDefaultType("DEFAULT");
                } else if (defType.MATERIALIZED() != null) {
                    col.setDefaultType("MATERIALIZED");
                } else if (defType.ALIAS() != null) {
                    col.setDefaultType("ALIAS");
                } else if (defType.EPHEMERAL() != null) {
                    col.setDefaultType("EPHEMERAL");
                }
                if (defType.expr() != null) {
                    setExprWithAnalyze(ChColumn::setDefaultValue, col, defType.expr());
                }
            }
        }
        if (isNullable(column.not_null()) || isNullable(typeExpr.not_null())) {
            col.setNotNull(false);
        }
        if (column.comment_expr() != null) {
            col.setComment(column.comment_expr().STRING_LITERAL().getText());
        }
        if (column.codec_expr() != null) {
            for (var codec : column.codec_expr().codec_arg_expr()) {
                col.addCodec(getFullCtxText(codec));
            }
        }
        if (column.TTL() != null) {
            setExprWithAnalyze(ChColumn::setTtl, col, column.ttl);
        }
        return col;
    }

    private boolean isNullable(Not_nullContext notNullContext) {
        return notNullContext != null && notNullContext.NOT() == null;
    }

    protected void setDataType(ChColumn col, Data_typeContext dataType) {
        if (dataType.NULLABLE() != null) {
            dataType = dataType.nullable_data_type;
            col.setNotNull(false);
        }
        col.setType(getFullCtxText(dataType));
    }

    protected ChEngine getEnginePart(Engine_clauseContext engineClause) {
        if (engineClause == null) {
            return null;
        }

        var engineCtx = engineClause.engine_expr();
        ChEngine engine = new ChEngine(engineCtx.NULL() != null ? "Null" : getFullCtxText(engineCtx.identifier()));
        var bodyCtx = engineCtx.expr_list();
        if (bodyCtx != null) {
            engine.setBody(getFullCtxText(bodyCtx), normalize(bodyCtx));
        }
        for (var option : engineClause.engine_option()) {
            parseEngineOption(engine, option);
        }
        if (Objects.equals(engine.getName(), "MergeTree") && !engine.containsOption("index_granularity")) {
            engine.addOption("index_granularity", "8192");
        }

        return engine;
    }

    /**
     * Fills one engine clause. Each of the five expression-shaped ones is handed
     * over twice - once as the author wrote it, for the DDL, and once normalized,
     * for the comparison.
     * <p>
     * The {@code SETTINGS} values are the exception and stay raw on both sides;
     * see the {@code options} field of {@link ChEngine} for why.
     */
    protected void parseEngineOption(ChEngine engine, Engine_optionContext optionCtx) {
        var orderBy = optionCtx.order_by_clause();
        if (orderBy != null) {
            var orderByList = orderBy.order_expr_list();
            if (orderByList != null) {
                engine.setOrderBy(getFullCtxText(orderByList), normalize(orderByList));
            } else {
                // the list is not optional in the grammar, so this branch is
                // reached only when a parse error lost it - the empty sort key
                // ORDER BY () does arrive here as a list whose text is "()".
                // The same "()" stands in for both halves because it normalizes
                // to itself, having neither whitespace nor a reserved word
                engine.setOrderBy("()", "()");
            }
            return;
        }

        var pk = optionCtx.primary_key_clause();
        if (pk != null) {
            engine.setPrimaryKey(getFullCtxText(pk.expr()), normalize(pk.expr()));
            return;
        }

        var partBy = optionCtx.partition_by_clause();
        if (partBy != null) {
            engine.setPartitionBy(getFullCtxText(partBy.expr()), normalize(partBy.expr()));
            return;
        }

        var ttl = optionCtx.ttl_clause();
        if (ttl != null) {
            var ttlList = ttl.ttl_expr_list();
            engine.setTtl(getFullCtxText(ttlList), normalize(ttlList));
            return;
        }

        var settings = optionCtx.settings_clause();
        if (settings != null) {
            for (var setting : settings.pairs().pair()) {
                engine.addOption(setting.identifier().getText(), getFullCtxText(setting.expr()));
            }
            return;
        }

        var sampleBy = optionCtx.sample_by_clause();
        if (sampleBy != null) {
            engine.setSampleBy(getFullCtxText(sampleBy.expr()), normalize(sampleBy.expr()));
        }
    }

    protected ChConstraint getConstraint(Table_constraint_defContext constraintCtx) {
        var constr = new ChConstraint(constraintCtx.identifier().getText(), constraintCtx.ASSUME() != null);
        setExprWithAnalyze(ChConstraint::setExpr, constr, constraintCtx.expr());
        return constr;
    }

    protected ChIndex getIndex(Table_index_defContext indexCtx) {
        var index = new ChIndex(indexCtx.identifier().getText());
        var indexTypeDefCtx = indexCtx.index_type_def();
        setExprWithAnalyze(ChIndex::setExpr, index, indexTypeDefCtx.expr());
        index.setType(getFullCtxText(indexTypeDefCtx.index_type()));
        var granVal = indexTypeDefCtx.gran;
        if (granVal != null) {
            index.setGranVal(Integer.parseInt(granVal.getText()));
        }
        return index;
    }

    /**
     * Sets one of the four table expressions that pass through here: a column
     * {@code DEFAULT}, a column {@code TTL}, a constraint body or an index
     * expression. Each is handed over twice - once as the author wrote it, for
     * the DDL, and once normalized, for the comparison.
     * <p>
     * Other ClickHouse expressions never reach this method. A dictionary
     * attribute is set on the very same {@link ChColumn} setter, but directly,
     * by {@link ChCreateDictionary}; the engine clauses, the table
     * {@code PRIMARY KEY}, a table projection, a policy {@code USING} and a
     * function body each have a setter of their own.
     */
    private <T extends AbstractStatement> void setExprWithAnalyze(ExprSetter<T> adder, T stmt, ExprContext ctx) {
        adder.accept(stmt, getFullCtxText(ctx), normalize(ctx));
        db.addAnalysisLauncher(new ChExpressionAnalysisLauncher(stmt, ctx, fileName));
    }

    /**
     * @param ctx the expression to normalize
     * @return the same tokens with canonical spacing, and the reserved words of
     *         the folded range {@code CHLexer.ALL..WITH} raised to upper case;
     *         a word outside that range comes back as it was written, because
     *         {@link ChParserUtils#getTokenText} folds that range and no other
     */
    protected String normalize(ParserRuleContext ctx) {
        Objects.requireNonNull(stream, () -> getClass().getSimpleName()
                + " parses expressions and must be constructed with a token stream");
        return ChParserUtils.normalizeWhitespaceUnquoted(ctx, stream);
    }

    /**
     * Accepts a raw expression and its normalized twin together, so a caller
     * cannot supply one half and forget the other.
     *
     * @param <T> the statement the expression belongs to
     */
    @FunctionalInterface
    protected interface ExprSetter<T> {

        /**
         * @param stmt           the statement to fill
         * @param expr           the expression text as written, for the DDL
         * @param exprNormalized the same expression as the comparison sees it
         */
        void accept(T stmt, String expr, String exprNormalized);
    }

    @Override
    protected ObjectLocation getLocation(List<? extends ParserRuleContext> ids, DbObjType type, String action,
                                         boolean isDep, String signature, LocationType locationType) {
        ParserRuleContext nameCtx = QNameParser.getFirstNameCtx(ids);

        if (type == DbObjType.FUNCTION) {
            return buildLocation(nameCtx, action, locationType, new ObjectReference(nameCtx.getText(), type));
        }

        if (type == DbObjType.POLICY) {
            String shortName = nameCtx.getText();
            String tableName = getFullCtxText(QNameParser.getSchemaNameCtx(ids));
            String fullName = shortName + " ON " + tableName;
            return buildLocation(nameCtx, action, locationType, new ObjectReference(fullName, type));
        }

        return super.getLocation(ids, type, action, isDep, signature, locationType);
    }

    protected <T extends AbstractStatement> void addRoles(UsersContext usersCtx, T stmt,
                                                          BiConsumer<T, String> addRoleMethod, BiConsumer<T, String> addExceptMethod, String ignoreRole) {
        if (usersCtx == null) {
            return;
        }

        for (var roleCtx : usersCtx.roles.identifier()) {
            String role = roleCtx.getText();
            addDepSafe(stmt, List.of(roleCtx), DbObjType.ROLE);
            if (!ignoreRole.equalsIgnoreCase(role)) {
                addRoleMethod.accept(stmt, role);
            }

        }

        var exceptRolesCtx = usersCtx.excepts;
        if (exceptRolesCtx != null) {
            for (var exceptCtx : exceptRolesCtx.identifier()) {
                addExceptMethod.accept(stmt, exceptCtx.getText());
                addDepSafe(stmt, List.of(exceptCtx), DbObjType.ROLE);
            }
        }
    }

    @Override
    protected ChSchema createSchema(String name) {
        return new ChSchema(name);
    }

    @Override
    protected boolean isSystemSchema(String schema) {
        return ChDiffUtils.isSystemSchema(schema);
    }

    @Override
    protected ChSchema getSchemaSafe(List<? extends ParserRuleContext> ids) {
        return (ChSchema) super.getSchemaSafe(ids);
    }
}
