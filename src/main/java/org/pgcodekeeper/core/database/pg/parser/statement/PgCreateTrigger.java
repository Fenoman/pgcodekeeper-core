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

import java.util.*;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ISchema;
import org.pgcodekeeper.core.database.api.schema.IStatementContainer;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.*;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgTriggerAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.database.pg.schema.PgTrigger.TgTypes;
import org.pgcodekeeper.core.database.pg.utils.PgDiffUtils;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Parser for PostgreSQL CREATE TRIGGER statements.
 * <p>
 * This class handles parsing of trigger definitions including trigger timing
 * (BEFORE, AFTER, INSTEAD OF), events (INSERT, UPDATE, DELETE, TRUNCATE),
 * trigger functions, referencing clauses, and constraint triggers.
 * <p>
 * Deferrability is read only for a constraint trigger, although
 * {@code create_trigger_statement} ({@code SQLParser.g4:1176-1187}) offers
 * {@code table_deferrable?} and {@code table_initialy_immed?} to every trigger.
 * The grammar accepts more than the server does: measured on PostgreSQL 17.10,
 * {@code CREATE TRIGGER t ... DEFERRABLE} without {@code CONSTRAINT} is a syntax
 * error, and so is {@code INITIALLY DEFERRED} on its own. The DDL writer takes
 * the same reading - {@code PgTrigger.getCreationSQL} emits the clause under
 * {@code isConstraint} only.
 */
public final class PgCreateTrigger extends PgParserAbstract {

    private final Create_trigger_statementContext ctx;
    private final CommonTokenStream stream;

    /**
     * Constructs a new CreateTrigger parser.
     *
     * @param ctx      the CREATE TRIGGER statement context
     * @param db       the PostgreSQL database object
     * @param stream   the token stream for parsing
     * @param settings the ISettings object
     */
    public PgCreateTrigger(Create_trigger_statementContext ctx, PgDatabase db, CommonTokenStream stream,
                           ISettings settings) {
        super(db, settings);
        this.ctx = ctx;
        this.stream = stream;
    }

    @Override
    public void parseObject() {
        List<ParserRuleContext> ids = getIdentifiers(ctx.table_name);
        addObjReference(ids, DbObjType.TABLE, null);

        PgTrigger trigger = new PgTrigger(ctx.name.getText());
        if (ctx.AFTER() != null) {
            trigger.setType(TgTypes.AFTER);
        } else if (ctx.BEFORE() != null) {
            trigger.setType(TgTypes.BEFORE);
        } else if (ctx.INSTEAD() != null) {
            trigger.setType(TgTypes.INSTEAD_OF);
        }
        if (ctx.ROW() != null) {
            trigger.setForEachRow(true);
        }
        if (ctx.STATEMENT() != null) {
            trigger.setForEachRow(false);
        }
        trigger.setOnDelete(ctx.delete_true != null);
        trigger.setOnInsert(ctx.insert_true != null);
        trigger.setOnUpdate(ctx.update_true != null);
        trigger.setOnTruncate(ctx.truncate_true != null);
        trigger.setFunction(getFullCtxText(ctx.func_name));

        if (ctx.CONSTRAINT() != null) {
            trigger.setConstraint(true);
            // the tri-state this field is - null is not deferrable - is exactly
            // the three states pg_trigger can hold, so the reading is taken
            // whole rather than assembled from the two clauses here. Asking for
            // DEFERRABLE before looking at INITIALLY lost two of the three:
            // INITIALLY DEFERRED and a lone DEFERRABLE both left it null and
            // were written back as NOT DEFERRABLE INITIALLY IMMEDIATE, while the
            // catalog held t/t and t/f
            trigger.setImmediate(readDeferrability(ctx.table_deferrable(), ctx.table_initialy_immed()));

            if (ctx.referenced_table_name != null) {
                List<ParserRuleContext> refName = getIdentifiers(ctx.referenced_table_name);
                String refSchemaName = QNameParser.getSecondName(refName);
                String refRelName = QNameParser.getFirstName(refName);

                StringBuilder sb = new StringBuilder();
                if (refSchemaName == null) {
                    refSchemaName = getSchemaNameSafe(ids);
                }

                if (refSchemaName != null) {
                    sb.append(PgDiffUtils.getQuotedName(refSchemaName)).append('.');
                }
                sb.append(PgDiffUtils.getQuotedName(refRelName));

                addDepSafe(trigger, refName, DbObjType.TABLE);
                trigger.setRefTableName(sb.toString());
            }
        }

        for (Trigger_referencingContext ref : ctx.trigger_referencing()) {
            String name = ref.identifier().getText();
            if (ref.NEW() != null) {
                trigger.setNewTable(name);
            } else {
                trigger.setOldTable(name);
            }
        }

        Schema_qualified_name_nontypeContext funcNameCtx = ctx.func_name
                .schema_qualified_name_nontype();
        if (funcNameCtx.schema != null) {
            addDepSafe(trigger, getIdentifiers(funcNameCtx), DbObjType.FUNCTION, "()");
        }

        ParserRuleContext schemaCtx = QNameParser.getSchemaNameCtx(ids);
        ParserRuleContext parentCtx = QNameParser.getFirstNameCtx(ids);

        for (Identifier_listContext column : ctx.identifier_list()) {
            for (IdentifierContext nameCol : column.identifier()) {
                trigger.addUpdateColumn(nameCol.getText());
                addDepSafe(trigger, Arrays.asList(schemaCtx, parentCtx, nameCol), DbObjType.COLUMN);
            }
        }
        parseWhen(ctx.when_trigger(), trigger, db, fileName, stream);

        IStatementContainer cont = getSafe(ISchema::getStatementContainer,
                getSchemaSafe(ids), parentCtx);
        addSafe(cont, trigger, Arrays.asList(schemaCtx, parentCtx, ctx.name));
    }

    /**
     * Parses the WHEN clause of a trigger definition.
     * <p>
     * This method processes trigger conditions that determine when the trigger
     * should fire based on the values in the affected row.
     * <p>
     * This is the single parsing point shared by both WHEN clause sources -
     * the project-side {@code CREATE TRIGGER} parser and the JDBC catalog
     * reader re-parsing a {@code pg_get_triggerdef()} result - so the
     * normalized form is computed identically regardless of which one called in.
     *
     * @param whenCtx  the WHEN trigger context, may be null
     * @param trigger  the trigger object to configure
     * @param db       the database for analysis launchers
     * @param location the source location for error reporting
     * @param stream   the token stream that produced {@code whenCtx}, used to
     *                 build the normalized comparison form
     */
    public static void parseWhen(When_triggerContext whenCtx, PgTrigger trigger,
                                 IDatabase db, String location, CommonTokenStream stream) {
        if (whenCtx != null) {
            VexContext vex = whenCtx.vex();
            // Same token-level normalization CHECK/EXCLUDE/index predicates
            // already get: canonical whitespace and upper case for the reserved
            // words of the folded range SQLLexer.ALL..WITH, so a re-cased or
            // re-spaced WHEN condition no longer reads as changed.
            trigger.setWhen(getFullCtxText(vex), PgParserUtils.normalizeWhitespaceUnquoted(vex, stream));
            db.addAnalysisLauncher(new PgTriggerAnalysisLauncher(trigger, vex, location));
        }
    }

    @Override
    protected String getStmtAction() {
        List<ParserRuleContext> ids = new ArrayList<>(getIdentifiers(ctx.table_name));
        ids.add(ctx.name);
        return getStrForStmtAction(ACTION_CREATE, DbObjType.TRIGGER, ids);
    }
}
