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

import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.ch.parser.generated.CHParser.*;
import org.pgcodekeeper.core.database.ch.schema.*;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Parser for ClickHouse CREATE DICTIONARY statements.
 * Handles dictionary creation including attributes, primary keys, source configurations,
 * and various dictionary options like LIFETIME, LAYOUT, RANGE, and SETTINGS.
 */
public final class ChCreateDictionary extends ChParserAbstract {

    private final Create_dictinary_stmtContext ctx;

    /**
     * Creates a parser for ClickHouse CREATE DICTIONARY statements.
     *
     * @param ctx      the ANTLR parse tree context for the CREATE DICTIONARY statement
     * @param db       the ClickHouse database schema being processed
     * @param stream   the token stream for expression normalization
     * @param settings parsing configuration settings
     */
    public ChCreateDictionary(Create_dictinary_stmtContext ctx, ChDatabase db, CommonTokenStream stream,
                              ISettings settings) {
        super(db, stream, settings);
        this.ctx = ctx;
    }

    @Override
    public void parseObject() {
        List<ParserRuleContext> ids = getIdentifiers(ctx.qualified_name());
        String name = QNameParser.getFirstName(ids);
        ChDictionary dictionary = new ChDictionary(name);
        parseObject(dictionary);
        addSafe(getSchemaSafe(ids), dictionary, ids);
    }

    /**
     * Parses dictionary details including attributes, primary key, options, and comments.
     * Processes dictionary attributes with their types, default values, and expressions.
     *
     * @param dictionary the dictionary object to populate with parsed information
     */
    public void parseObject(ChDictionary dictionary) {
        for (var attrCtx : ctx.dictionary_attr_def()) {
            var col = new ChColumn(attrCtx.identifier().getText());
            setDataType(col, attrCtx.data_type());
            if (attrCtx.DEFAULT() != null) {
                col.setDefaultType("DEFAULT");
                var literalCtx = attrCtx.literal();
                col.setDefaultValue(getFullCtxText(literalCtx), normalize(literalCtx));
            } else if (attrCtx.EXPRESSION() != null) {
                col.setDefaultType("EXPRESSION");
                var exprCtx = attrCtx.expr();
                col.setDefaultValue(getFullCtxText(exprCtx), normalize(exprCtx));
            }
            var attrOptCtx = attrCtx.attr_def_option();
            if (attrOptCtx != null) {
                col.setOption(attrOptCtx.getText());
            }
            dictionary.addColumn(col);
        }
        if (ctx.PRIMARY() != null) {
            var pkCtx = ctx.expr_list();
            dictionary.setPk(getFullCtxText(pkCtx), normalize(pkCtx));
        }
        for (var optionCtx : ctx.dictionary_option()) {
            parseOption(optionCtx, dictionary);
        }
        var commentCtx = ctx.comment_expr();
        if (commentCtx != null) {
            dictionary.setComment(commentCtx.STRING_LITERAL().getText());
        }
    }

    /**
     * Fills one dictionary option. Each of the three single-valued ones -
     * {@code LIFETIME}, {@code LAYOUT} and {@code RANGE} - is handed over twice:
     * once as the author wrote it, for the DDL, and once normalized, for the
     * comparison.
     * <p>
     * The {@code SETTINGS} values stay raw on both sides. They are {@code expr}
     * by grammar ({@code pair: identifier EQ_SINGLE expr}), so this is a
     * decision rather than an oversight: it leaves a dictionary setting
     * compared exactly like the engine setting of a table, which is held raw
     * as well, and the reason recorded on the {@code options} field of
     * {@link ChEngine} is about that map, not this one. Normalizing one of the
     * two and not the other is the choice that would need arguing.
     */
    private void parseOption(Dictionary_optionContext option, ChDictionary dictionary) {
        if (option.SOURCE() != null) {
            parseSource(option, dictionary);
            return;
        }

        if (option.LIFETIME() != null) {
            var lifeTimeCtx = option.life_time_expr();
            dictionary.setLifeTime(getFullCtxText(lifeTimeCtx), normalize(lifeTimeCtx));
            return;
        }

        if (option.LAYOUT() != null) {
            var layOutCtx = option.layout_expr();
            dictionary.setLayOut(getFullCtxText(layOutCtx), normalize(layOutCtx));
            return;
        }

        if (option.RANGE() != null) {
            var rangeCtx = option.range_expr();
            dictionary.setRange(getFullCtxText(rangeCtx), normalize(rangeCtx));
            return;
        }

        if (option.SETTINGS() != null) {
            for (var optionCtx : option.pairs().pair()) {
                dictionary.addOption(optionCtx.identifier().getText(), getFullCtxText(optionCtx.expr()));
            }
        }
    }

    private void parseSource(Dictionary_optionContext option, ChDictionary dictionary) {
        var sourceType = option.identifier().getText();
        dictionary.setSourceType(sourceType);
        String sourceTableName = null;
        String sourceDbName = null;
        for (var argCtx : option.dictionary_arg_expr()) {
            String value = getFullCtxText(argCtx.dictionary_arg_value());
            String key = argCtx.identifier().getText();
            dictionary.addSource(key, value);

            if ("clickhouse".equalsIgnoreCase(sourceType)) {
                if ("table".equalsIgnoreCase(key)) {
                    sourceTableName = value.replace("'", "");
                } else if ("db".equalsIgnoreCase(key)) {
                    sourceDbName = value.replace("'", "");
                }
            }
        }

        if (sourceTableName != null) {
            if (sourceDbName == null) {
                sourceDbName = "default";
            }
            dictionary.addDependency(new ObjectReference(sourceDbName, sourceTableName, DbObjType.TABLE));
        }
    }

    @Override
    protected String getStmtAction() {
        return getStrForStmtAction(ACTION_CREATE, DbObjType.DICTIONARY, ctx.qualified_name());
    }
}