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
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Alter_sequence_statementContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Schema_alterContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Sequence_bodyContext;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Set_loggedContext;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;
import org.pgcodekeeper.core.exception.UnresolvedReferenceException;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Parser for PostgreSQL ALTER SEQUENCE statements.
 * <p>
 * The options the statement states are read by the routine the {@code CREATE}
 * reads them with ({@link PgCreateSequence#fillSequence}), told that this is an
 * alter: an option a {@code CREATE} does not name is the default, while one this
 * statement does not name is the one the sequence already has. A sequence option
 * read by a second route is an option whose two spellings stop building one
 * model.
 * <p>
 * Counted one alternative at a time against {@code SQLParser.g4:1429-1439},
 * eight of the nine alternatives of {@code sequence_body} reach the model:
 * {@code AS type}, {@code INCREMENT}, {@code MINVALUE}, {@code MAXVALUE},
 * {@code START}, {@code CACHE}, {@code CYCLE} and both halves of an
 * {@code OWNED BY} - the column it names, and the {@code NONE} that takes the
 * owner away. The ninth, {@code SEQUENCE NAME}, is read by neither
 * statement: it is a name rather than a property, and PostgreSQL 17.10 refuses
 * it here outright - {@code invalid sequence option SEQUENCE NAME}, measured -
 * so the grammar accepts more than the server does.
 * <p>
 * {@code RESTART} states no part of the DDL. It sets the value the sequence is
 * next to hand out, which is state of the database, and the field that looks
 * like one - {@code START WITH} - is a different thing; it is reported as a
 * danger statement and stored nowhere. {@code RENAME TO} and {@code SET SCHEMA}
 * change the identity of the sequence rather than its content and are
 * deliberately not applied, as they are for a table and for a domain: a project
 * file writes the name and the schema it means in the {@code CREATE} itself.
 * <p>
 * {@code SET LOGGED}/{@code UNLOGGED} is a sibling of the body rather than an
 * alternative of it ({@code SQLParser.g4:602-610}) and is read by
 * {@link #setLogged}, for a sequence the schema holds and for the sequence
 * behind an identity column alike.
 */
public final class PgAlterSequence extends PgParserAbstract {

    private final Alter_sequence_statementContext ctx;

    /**
     * Constructs a new AlterSequence parser.
     *
     * @param ctx      the ALTER SEQUENCE statement context
     * @param db       the PostgreSQL database object
     * @param settings the ISettings object
     */
    public PgAlterSequence(Alter_sequence_statementContext ctx, PgDatabase db, ISettings settings) {
        super(db, settings);
        this.ctx = ctx;
    }

    @Override
    public void parseObject() {
        List<ParserRuleContext> ids = getIdentifiers(ctx.name);
        ObjectLocation loc = addObjReference(ids, DbObjType.SEQUENCE, ACTION_ALTER);
        var loggedCtx = ctx.set_logged();
        if (loggedCtx != null) {
            setLogged(loggedCtx, ids);
            return;
        }
        PgSequence sequence = getSafe(PgSchema::getSequence, getSchemaSafe(ids), QNameParser.getFirstNameCtx(ids));

        List<Sequence_bodyContext> bodies = ctx.sequence_body();
        for (Sequence_bodyContext seqbody : bodies) {
            // the owning table is a reference this statement registers and the
            // CREATE does not, so it stays here; the owning column itself goes
            // into the model with the rest of the body, below. A qualified name
            // is the only one that names a table at all - an unqualified column
            // has none, and OWNED BY NONE names nothing
            if (seqbody.OWNED() != null && seqbody.col_name != null) {
                List<ParserRuleContext> col = getIdentifiers(seqbody.col_name);
                if (col.size() > 1) {
                    addObjReference(col.subList(0, col.size() - 1), DbObjType.TABLE, null);
                }
            }
        }

        // the sequence is null in REF mode, which collects the references above
        // and writes no model at all
        if (sequence != null && !bodies.isEmpty()) {
            PgCreateSequence.fillSequence(sequence, bodies, true);
        }

        if (!ctx.RESTART().isEmpty()) {
            loc.setWarning(DangerStatement.RESTART_WITH);
        }
    }

    /**
     * Writes the logged state onto the sequence the statement names, whether the
     * schema holds it or an identity column does. The second is looked for
     * through {@link PgColumn} because the sequence behind an identity column is
     * not a child of the {@link PgSchema}.
     * <p>
     * A sequence the schema does have used to return from here with nothing
     * written, so {@code ALTER SEQUENCE public.s SET UNLOGGED} left the model
     * logged and against an unlogged database the tool wrote
     * {@code ALTER SEQUENCE public.s SET LOGGED} back - the state the file had
     * just left. The early return came in with {@code a4c6662b}, whose changelog
     * calls it a fix for a parsing error, and the parsing error is what the loop
     * below does with a regular sequence: it finds no identity column of that
     * name and throws, so the whole file failed to load. Both halves are needed,
     * and only the value was missing - the return itself stays.
     * <p>
     * The location of the object is checked for the identity sequence and not for
     * this one, which is the shape upstream left and is kept deliberately.
     * Checking it here would raise {@code MisplacedObjectException} on a
     * statement that is silent today - a new error out of legal input, which is a
     * decision of its own rather than a part of reading the value.
     * <p>
     * Measured on PostgreSQL 17.10: {@code ALTER SEQUENCE s SET UNLOGGED} on a
     * regular sequence moves {@code pg_class.relpersistence} from {@code 'p'} to
     * {@code 'u'}, which is the state {@code CREATE UNLOGGED SEQUENCE s} is
     * created in.
     *
     * @param loggedCtx - {@link Set_loggedContext}
     * @param ids       - list of {@link ParserRuleContext} where store {@link PgSequence} qualified name
     * @throws UnresolvedReferenceException if object not found or location is broken
     */
    private void setLogged(Set_loggedContext loggedCtx, List<ParserRuleContext> ids) {
        if (ParserListenerMode.REF == getParserMode()) {
            return;
        }

        boolean isLogged = loggedCtx.LOGGED() != null;

        var schema = getSchemaSafe(ids);
        var seqName = QNameParser.getFirstName(ids);

        PgSequence sequence = schema.getSequence(seqName);
        if (sequence != null) {
            sequence.setLogged(isLogged);
            return;
        }

        var nameToken = QNameParser.getFirstNameCtx(ids).getStart();

        for (var table : schema.getTables()) {
            for (var column : table.getColumns()) {
                PgColumn col = (PgColumn) column;
                PgSequence seq = col.getSequence();
                if (seq != null && seqName.equals(seq.getName())) {
                    checkLocation(table, nameToken);
                    seq.setLogged(isLogged);
                    return;
                }
            }
        }

        throw new UnresolvedReferenceException(
                Messages.Utils_not_object_in_database.formatted(seqName), nameToken);
    }

    @Override
    protected ObjectLocation fillQueryLocation(ParserRuleContext ctx) {
        ObjectLocation loc = super.fillQueryLocation(ctx);
        if (!((Schema_alterContext) ctx).alter_sequence_statement().RESTART().isEmpty()) {
            loc.setWarning(DangerStatement.RESTART_WITH);
        }
        return loc;
    }

    @Override
    protected String getStmtAction() {
        return getStrForStmtAction(ACTION_ALTER, DbObjType.SEQUENCE, getIdentifiers(ctx.name));
    }
}
