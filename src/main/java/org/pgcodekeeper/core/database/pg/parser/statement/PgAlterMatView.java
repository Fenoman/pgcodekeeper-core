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

import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.*;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Parser for PostgreSQL ALTER MATERIALIZED VIEW statements.
 * <p>
 * This class handles parsing of materialized view alterations including
 * setting clustered indexes and handling ALTER MATERIALIZED VIEW ALL
 * operations that affect all materialized views in a tablespace.
 * <p>
 * Counted one at a time against {@code SQLParser.g4:638-648}, five of the nine
 * alternatives of {@code materialized_view_action} reach the model:
 * {@code CLUSTER ON}, and the four closed after it -
 * {@code SET WITHOUT CLUSTER}, {@code SET ACCESS METHOD}, {@code SET (...)} and
 * {@code RESET (...)}, see {@link #fillMatViewProperty}. The access method was
 * the worst of them, because it is one of the fields {@code needDrop} reads:
 * unread, a file stating one produced {@code DROP MATERIALIZED VIEW} plus
 * {@code CREATE}, measured - the whole object rebuilt to undo what the file
 * asked for.
 * <p>
 * The four that are left all state something of a single column
 * ({@code SET STATISTICS}, {@code SET (...)}, {@code RESET (...)} and
 * {@code SET STORAGE}), and the model holds no such thing: a view's columns are
 * name and type pairs derived from its query, with nowhere to put a storage
 * parameter. They are dropped rather than written back the wrong way round - no
 * comparison sees them from either side. The identity alternatives of the outer
 * rule - {@code RENAME TO}, {@code SET SCHEMA}, {@code RENAME COLUMN} and
 * {@code DEPENDS ON EXTENSION} - are deliberately not applied, as they are for
 * a plain view.
 */
public final class PgAlterMatView extends PgParserAbstract {

    private final Alter_materialized_view_statementContext ctx;
    private final String action;

    /**
     * Constructs a new AlterMatView parser.
     *
     * @param ctx      the ALTER MATERIALIZED VIEW statement context
     * @param db       the PostgreSQL database object
     * @param settings the ISettings object
     */
    public PgAlterMatView(Alter_materialized_view_statementContext ctx, PgDatabase db, ISettings settings) {
        super(db, settings);
        this.ctx = ctx;
        this.action = ctx.ALL() != null ? "ALTER MATERIALIZED VIEW ALL" : "ALTER MATERIALIZED";
    }

    @Override
    public void parseObject() {
        if (ctx.ALL() == null) {
            List<ParserRuleContext> ids = getIdentifiers(ctx.schema_qualified_name());
            addObjReference(ids, DbObjType.VIEW, action);

            PgAbstractView view = getSafe(PgSchema::getView, getSchemaSafe(ids), QNameParser.getFirstNameCtx(ids));
            var alterAction = ctx.alter_materialized_view_action();
            if (alterAction != null) {
                for (var act : alterAction.materialized_view_action()) {
                    var indexNameCtx = act.index_name;
                    if (indexNameCtx != null) {
                        ParserRuleContext indexName = QNameParser.getFirstNameCtx(getIdentifiers(indexNameCtx));
                        PgIndex index = getSafe(PgAbstractView::getIndex, view, indexName);
                        doSafe(PgIndex::setClustered, index, true);
                    } else if (view != null) {
                        fillMatViewProperty(view, act);
                    }
                }
            }
        } else {
            db.addReference(fileName, new ObjectLocation.Builder()
                    .setAction(action).setCtx(ctx.getParent()).build());
        }
    }

    /**
     * Reads the four alternatives that state a property the
     * {@code CREATE MATERIALIZED VIEW} states inline.
     * <p>
     * {@code SET WITHOUT CLUSTER} is the other half of the pair whose
     * {@code CLUSTER ON} half already had a writer, and it goes through the
     * container's own {@code clearClustered}, as it does for a table: the
     * clustered flag lives on the index rather than on the view, and the file
     * says only that no index of this view is clustered any more.
     * <p>
     * The storage parameters go through the routine the {@code CREATE} reads
     * them with. {@code SET ACCESS METHOD} writes the field the {@code USING}
     * clause writes; it takes no {@code DEFAULT} here, unlike the table's, so
     * there is no default access method to thread in.
     * <p>
     * A statement naming a plain view is left alone rather than reported: the
     * grammar tells the two apart by the word {@code MATERIALIZED}, so a file
     * that reaches here about a {@code PgView} states something PostgreSQL
     * itself refuses.
     *
     * @param view the view the statement names
     * @param act  one action of the statement's list
     */
    private void fillMatViewProperty(PgAbstractView view,
                                     Materialized_view_actionContext act) {
        if (act.ALTER() != null) {
            // the four column alternatives, which the model holds nowhere -
            // see the class javadoc
            return;
        }

        if (act.WITHOUT() != null) {
            doSafe((v, o) -> v.clearClustered(), view, null);
            return;
        }

        var methodCtx = act.access_method_name;
        if (methodCtx != null) {
            if (view instanceof PgMaterializedView matView) {
                doSafe(PgMaterializedView::setMethod, matView, methodCtx.getText());
            }
            return;
        }

        Storage_parametersContext params = act.storage_parameters();
        if (params != null) {
            for (Storage_parameter_optionContext option : params.storage_parameter_option()) {
                VexContext value = option.vex();
                fillStorageParam(value == null ? "" : value.getText(),
                        option.storage_parameter_name().getText(), false, view::addOption);
            }
        }

        Names_in_parensContext reset = act.names_in_parens();
        if (reset != null) {
            for (Schema_qualified_nameContext name : reset.names_references().schema_qualified_name()) {
                fillOptionParams(null, QNameParser.getFirstName(getIdentifiers(name)), false,
                        (option, value) -> view.removeOption(option));
            }
        }
    }

    @Override
    protected String getStmtAction() {
        if (ctx.ALL() != null) {
            return action;
        }
        return getStrForStmtAction(action, DbObjType.VIEW,
                ctx.schema_qualified_name().identifier());
    }
}