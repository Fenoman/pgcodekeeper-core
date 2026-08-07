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
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.*;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Parser for PostgreSQL ALTER INDEX statements.
 * <p>
 * This class handles parsing of index alterations including index inheritance
 * operations and ALTER INDEX ALL statements that affect all indexes in a tablespace.
 * <p>
 * Counted one at a time against {@code SQLParser.g4:571-579}, four of the seven
 * alternatives of {@code index_def_action} reach the model:
 * {@code ATTACH PARTITION}, and the three that state a property the
 * {@code CREATE INDEX} could have stated inline - {@code SET (...)},
 * {@code RESET (...)} and {@code SET TABLESPACE}, see
 * {@link #fillIndexProperty}. The tool writes the first of those itself
 * ({@code compare_indices_diff.sql} carries it as expected output), so unread
 * it was a migration pgcodekeeper generated and could not read back.
 * <p>
 * Of the three that are left, {@code RENAME TO} states the identity of the
 * index rather than its content and is deliberately not applied, as it is for a
 * table and for a sequence: a project file writes the name it means in the
 * {@code CREATE} itself. {@code DEPENDS ON EXTENSION} and
 * {@code ALTER COLUMN n SET STATISTICS} state something the model holds nowhere
 * - there is no field for either on {@link PgIndex} or on the columns it keeps,
 * and neither reader writes one - so they are dropped rather than written back
 * the wrong way round: no comparison sees them from either side.
 */
public final class PgAlterIndex extends PgParserAbstract {

    private final Alter_index_statementContext ctx;
    private final String alterIdxAllAction;

    /**
     * Constructs a new AlterIndex parser.
     *
     * @param ctx      the ALTER INDEX statement context
     * @param db       the PostgreSQL database object
     * @param settings the ISettings object
     */
    public PgAlterIndex(Alter_index_statementContext ctx, PgDatabase db, ISettings settings) {
        super(db, settings);
        this.ctx = ctx;
        alterIdxAllAction = ctx.ALL() == null ? null : "ALTER INDEX ALL";
    }

    @Override
    public void parseObject() {
        if (alterIdxAllAction != null) {
            ObjectLocation loc = new ObjectLocation.Builder()
                    .setAction(alterIdxAllAction)
                    .setCtx(ctx.getParent())
                    .build();

            db.addReference(fileName, loc);
            return;
        }

        List<ParserRuleContext> ids = getIdentifiers(ctx.schema_qualified_name());

        Index_def_actionContext action = ctx.index_def_action();
        Schema_qualified_nameContext inherit = action.index;

        if (inherit != null) {
            // in this case inherit is real index name
            List<ParserRuleContext> idsInh = getIdentifiers(inherit);
            PgSchema schema = getSchemaSafe(idsInh);
            ParserRuleContext inhName = QNameParser.getFirstNameCtx(idsInh);

            String inhSchemaName = getSchemaNameSafe(ids);
            String inhTableName = QNameParser.getFirstName(ids);

            addObjReference(idsInh, DbObjType.INDEX, ACTION_ALTER);
            if (schema == null) {
                return;
            }

            PgIndex index = schema.getIndexByName(inhName.getText());
            if (index != null) {
                doSafe((i, o) -> i.addInherit(inhSchemaName, inhTableName), index, null);
                addDepSafe(index, ids, DbObjType.INDEX);
            } else if (ParserListenerMode.SINGLE != getParserMode()) {
                getSafe(PgSchema::getConstraintByName, schema, inhName);
            }

        } else {
            addObjReference(ids, DbObjType.INDEX, ACTION_ALTER);
            fillIndexProperty(ids, action);
        }
    }

    /**
     * Reads the three alternatives that state a property the
     * {@code CREATE INDEX} states inline - {@code SET (...)},
     * {@code RESET (...)} and {@code SET TABLESPACE}.
     * <p>
     * Left unread, each made the tool write back the state the file had just
     * left: measured, a project saying {@code SET (fillfactor=70)} produced
     * {@code ALTER INDEX public.idx RESET (fillfactor)} against a database where
     * that very statement had been applied, because the model held the index the
     * {@code CREATE} alone described.
     * <p>
     * The storage parameters go through the routine the {@code CREATE} reads
     * them with, so that the two spellings build one model. An index takes no
     * {@code toast.} prefix and no {@code OIDS}, which is why this is
     * {@code fillOptionParams} directly rather than the table's
     * {@code parseOptions}.
     * <p>
     * {@code NOWAIT} is deliberately not read, on the reading a drop's
     * {@code CASCADE} gets: it says how the database should carry the move out,
     * while the file states where the index ends up.
     * <p>
     * The name has to resolve, as it does for every other statement that writes
     * to the model, and the statement's own {@code IF EXISTS} is the author's
     * word that the index may not be there. An index name is looked up across
     * the whole schema because that is where the model keeps it - under the
     * table or the view it belongs to - and a name that is a constraint's index
     * instead is resolved through the constraint, as the {@code ATTACH} branch
     * above does. A constraint's index parameters have no writer here: the model
     * offers {@code PgIndexParamContainer.addParam} but nothing that takes a
     * parameter away, so reading half the pair would leave {@code RESET}
     * silently doing nothing.
     *
     * @param ids    the index's own name
     * @param action the single action the statement carries
     */
    private void fillIndexProperty(List<ParserRuleContext> ids, Index_def_actionContext action) {
        Storage_parametersContext params = action.storage_parameters();
        Identifier_list_in_parenContext reset = action.identifier_list_in_paren();
        Set_tablespaceContext space = action.set_tablespace();

        if (params == null && reset == null && space == null) {
            // RENAME TO, DEPENDS ON EXTENSION and the column statistics, none
            // of which the model holds - see the class javadoc
            return;
        }

        PgSchema schema = getSchemaSafe(ids);
        ParserRuleContext nameCtx = QNameParser.getFirstNameCtx(ids);
        PgIndex index = schema == null ? null : schema.getIndexByName(nameCtx.getText());

        if (index == null) {
            if (schema != null && ctx.if_exists() == null
                    && ParserListenerMode.SINGLE != getParserMode()) {
                getSafe(PgSchema::getConstraintByName, schema, nameCtx);
            }
            return;
        }

        if (params != null) {
            for (Storage_parameter_optionContext option : params.storage_parameter_option()) {
                VexContext value = option.vex();
                fillStorageParam(value == null ? "" : value.getText(),
                        option.storage_parameter_name().getText(), false, index::addOption);
            }
        }

        if (reset != null) {
            for (IdentifierContext name : reset.identifier_list().identifier()) {
                fillOptionParams(null, name.getText(), false, (option, value) -> index.removeOption(option));
            }
        }

        if (space != null) {
            index.setTablespace(space.identifier().getText());
        }
    }

    @Override
    protected String getStmtAction() {
        return alterIdxAllAction != null ? alterIdxAllAction
                : getStrForStmtAction(ACTION_ALTER, DbObjType.INDEX,
                getIdentifiers(ctx.schema_qualified_name()));
    }
}
