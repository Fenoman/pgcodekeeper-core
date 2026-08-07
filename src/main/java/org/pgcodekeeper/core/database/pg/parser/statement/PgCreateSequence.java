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
 * Parser for PostgreSQL CREATE SEQUENCE statements.
 * <p>
 * This class handles parsing of sequence definitions including data type,
 * increment, min/max values, start value, cache, cycle options, and
 * ownership relationships.
 */
public final class PgCreateSequence extends PgParserAbstract {

    private final Create_sequence_statementContext ctx;

    /**
     * Constructs a new CreateSequence parser.
     *
     * @param ctx      the CREATE SEQUENCE statement context
     * @param db       the PostgreSQL database object
     * @param settings the ISettings object
     */
    public PgCreateSequence(Create_sequence_statementContext ctx, PgDatabase db, ISettings settings) {
        super(db, settings);
        this.ctx = ctx;
    }

    @Override
    public void parseObject() {
        List<ParserRuleContext> ids = getIdentifiers(ctx.name);
        PgSequence sequence = new PgSequence(QNameParser.getFirstName(ids));
        if (ctx.UNLOGGED() != null) {
            sequence.setLogged(false);
        }

        fillSequence(sequence, ctx.sequence_body());
        addSafe(getSchemaSafe(ids), sequence, ids);
    }

    /**
     * Fills sequence properties from a list of sequence body contexts.
     * <p>
     * This method processes sequence options like data type, cache, increment,
     * min/max values, start value, cycle behavior, and ownership.
     *
     * @param sequence the sequence object to populate
     * @param list     the list of sequence body contexts containing the options
     */
    public static void fillSequence(PgSequence sequence, List<Sequence_bodyContext> list) {
        fillSequence(sequence, list, false);
    }

    /**
     * Fills sequence properties from a list of sequence body contexts, either as
     * a statement that creates the sequence or as one that alters it.
     * <p>
     * The two differ in what silence means. A {@code CREATE} that names no
     * increment gives the sequence the default one and no bounds; an
     * {@code ALTER} that names no increment leaves the increment the sequence
     * already has. Everything else is read the same way by both, which is why
     * they share this loop: a sequence option read by a second route is an option
     * whose two spellings stop building one model.
     * <p>
     * {@code SEQUENCE NAME} is read by neither. It is a name, not a property, and
     * the name of a statement is final - the callers that need it take it before
     * they construct the sequence.
     * <p>
     * {@code OWNED BY NONE} takes the owner away rather than leaving it alone. It
     * is the second half of one alternative of the grammar, told from the naming
     * of a column here for the same reason {@code NO MAXVALUE} is told from
     * silence about the maximum: the tool writes the statement itself - see
     * {@code PgSequence.compareSequenceBody} - so a migration it generates has to
     * be one it can read back.
     *
     * @param sequence the sequence object to populate
     * @param list     the list of sequence body contexts containing the options
     * @param isAlter  true when the list restates options of a sequence that
     *                 already exists, so an option it does not name is kept
     */
    public static void fillSequence(PgSequence sequence, List<Sequence_bodyContext> list, boolean isAlter) {
        Long inc = null;
        Long maxValue = null;
        Long minValue = null;
        // the bound and the word taking it away are one alternative of the
        // grammar, so "NO MAXVALUE" and "no maximum named" are told apart here
        // and nowhere else. For a CREATE they mean the same thing; for an ALTER
        // the first takes a maximum away and the second leaves it alone
        boolean isMaxStated = false;
        boolean isMinStated = false;
        for (Sequence_bodyContext body : list) {
            isMaxStated |= body.MAXVALUE() != null;
            isMinStated |= body.MINVALUE() != null;
            if (body.type != null) {
                sequence.setDataType(body.type.getText());
            } else if (body.cache_val != null) {
                sequence.setCache(body.cache_val.getText());
            } else if (body.incr != null) {
                inc = Long.parseLong(body.incr.getText());
            } else if (body.maxval != null) {
                maxValue = Long.parseLong(body.maxval.getText());
            } else if (body.minval != null) {
                minValue = Long.parseLong(body.minval.getText());
            } else if (body.start_val != null) {
                sequence.setStartWith(body.start_val.getText());
            } else if (body.cycle_val != null) {
                sequence.setCycle(body.cycle_true == null);
            } else if (body.col_name != null) {
                // TODO incorrect qualified name work
                // also broken in altersequence
                List<ParserRuleContext> col = getIdentifiers(body.col_name);
                Tokens_nonreserved_except_function_typeContext word;
                if (col.size() != 1
                        || (word = body.col_name.identifier().tokens_nonreserved_except_function_type()) == null
                        || word.NONE() == null) {
                    sequence.setOwnedBy(new ObjectReference(QNameParser.getThirdName(col),
                            QNameParser.getSecondName(col), QNameParser.getFirstName(col), DbObjType.COLUMN));
                } else {
                    // NONE is a statement and not silence, the way NO MAXVALUE is:
                    // it takes the owning column away. Measured on PostgreSQL
                    // 17.10, ALTER SEQUENCE s OWNED BY NONE removes the
                    // deptype = 'a' row pg_depend held for the column. For a
                    // CREATE the sequence has no owner to take away and this is a
                    // fixed point, which is why the two statements can share it
                    sequence.setOwnedBy(null);
                }
            }
        }
        if (isAlter) {
            sequence.alterMinMaxInc(inc, maxValue, isMaxStated, minValue, isMinStated);
        } else {
            // a CREATE naming no increment gets the default one, and bounds it
            // does not name are the boundaries of its type
            sequence.setMinMaxInc(inc == null ? 1 : inc, maxValue, minValue, sequence.getDataType(), 0L);
        }
    }

    @Override
    protected String getStmtAction() {
        return getStrForStmtAction(ACTION_CREATE, DbObjType.SEQUENCE, getIdentifiers(ctx.name));
    }
}
