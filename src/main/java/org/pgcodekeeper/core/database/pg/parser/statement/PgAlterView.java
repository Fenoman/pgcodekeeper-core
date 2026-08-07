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

import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.*;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgVexAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Parser for PostgreSQL ALTER VIEW statements.
 * <p>
 * This class handles parsing of view alterations including setting and dropping
 * default values for view columns. These operations affect how the view
 * behaves during INSERT operations.
 * <p>
 * Counted one at a time against {@code SQLParser.g4:615-623}, four of the seven
 * alternatives of {@code alter_view_action} reach the model: the column default
 * pair, and the option pair {@code SET (...)} / {@code RESET (...)} closed after
 * them - see {@link #fillViewOption}. The tool writes both halves of that pair
 * itself ({@code add_view_option_diff.sql} and
 * {@code delete_view_option_diff.sql} carry them as expected output), so unread
 * they were migrations pgcodekeeper generated and could not read back.
 * <p>
 * The other three - {@code RENAME COLUMN}, {@code RENAME TO} and
 * {@code SET SCHEMA} - state the identity of the view or of one of its columns
 * rather than its content, and are deliberately not applied: a project file
 * writes the names and the schema it means in the {@code CREATE} itself, which
 * is the reading {@code ALTER TABLE}, {@code ALTER DOMAIN} and
 * {@code ALTER SEQUENCE} already get. A view's column names are stated by its
 * query, not by a list the model can rewrite.
 */
public final class PgAlterView extends PgParserAbstract {

    private final Alter_view_statementContext ctx;
    private final CommonTokenStream stream;

    /**
     * Constructs a new AlterView parser.
     *
     * @param ctx      the ALTER VIEW statement context
     * @param db       the PostgreSQL database object
     * @param stream   the token stream for parsing
     * @param settings the ISettings object
     */
    public PgAlterView(Alter_view_statementContext ctx, PgDatabase db, CommonTokenStream stream, ISettings settings) {
        super(db, settings);
        this.ctx = ctx;
        this.stream = stream;
    }

    @Override
    public void parseObject() {
        List<ParserRuleContext> ids = getIdentifiers(ctx.name);
        var st = getSafe(PgSchema::getView, getSchemaSafe(ids), QNameParser.getFirstNameCtx(ids));
        Alter_view_actionContext action = ctx.alter_view_action();
        if (st instanceof PgView dbView) {
            if (action.set_def_column() != null) {
                VexContext exp = action.set_def_column().vex();
                doSafe((s, o) -> {
                    s.addColumnDefaultValue(getFullCtxText(action.column_name), getExpressionText(exp, stream));
                    db.addAnalysisLauncher(new PgVexAnalysisLauncher(s, exp, fileName));
                }, dbView, null);
            }
            if (action.drop_def() != null) {
                doSafe(PgView::removeColumnDefaultValue, dbView, getFullCtxText(action.column_name));
            }
        }
        if (st != null) {
            // the options are held by the base class, so this half is the same
            // for whichever kind of view the schema turns out to hold
            fillViewOption(st, action);
        }

        addObjReference(ids, DbObjType.VIEW, ACTION_ALTER);
    }

    /**
     * Reads the option pair, which states what the {@code WITH (...)} of the
     * {@code CREATE VIEW} states inline.
     * <p>
     * Left unread it was the worst alternative of the statement, because
     * {@code security_invoker} decides whose privileges the view's query runs
     * with: measured, a project file setting it produced
     * {@code ALTER VIEW public.v RESET (security_invoker)} against a database
     * where that very statement had been applied - a change of who may read
     * what, written back the other way round.
     * <p>
     * Both halves go through the routine the {@code CREATE} reads them with, so
     * that the two spellings build one model. {@code check_option} is one of the
     * options this map holds, spelled by the {@code CREATE} as a clause of its
     * own; stated here it lands under the same key, which is what makes an
     * {@code ALTER} setting it and a {@code CREATE} declaring it comparable.
     *
     * @param view   the view the statement names
     * @param action the single action it carries
     */
    private void fillViewOption(PgAbstractView view, Alter_view_actionContext action) {
        Storage_parametersContext params = action.storage_parameters();
        if (params != null) {
            for (Storage_parameter_optionContext option : params.storage_parameter_option()) {
                VexContext value = option.vex();
                fillStorageParam(value == null ? "" : value.getText(),
                        option.storage_parameter_name().getText(), false, view::addOption);
            }
        }

        Names_in_parensContext reset = action.names_in_parens();
        if (reset != null) {
            for (Schema_qualified_nameContext name : reset.names_references().schema_qualified_name()) {
                fillOptionParams(null, QNameParser.getFirstName(getIdentifiers(name)), false,
                        (option, value) -> view.removeOption(option));
            }
        }
    }

    @Override
    protected String getStmtAction() {
        return getStrForStmtAction(ACTION_ALTER, DbObjType.VIEW, getIdentifiers(ctx.name));
    }
}
