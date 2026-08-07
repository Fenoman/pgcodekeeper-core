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
package org.pgcodekeeper.core.model.difftree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.localizations.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Says out loud that a migration is about to drop one column of a table and add
 * another one whose name differs from it in case alone.
 * <p>
 * Such a pair is a rename that the operator wrote as a rename - {@code "NAME"}
 * became {@code name} - and that the migration carries out as a removal and an
 * addition, because a rename is not among the things this tool can express: an
 * action is created, dropped or altered, and nothing in the dependency resolver
 * knows how to leave the objects that hang off a column alone while its name
 * moves. The script is therefore correct in what it says and silent about what
 * it costs, and what it costs is every row of the old column.
 * <p>
 * <b>Why case alone and nothing else.</b> Any other drop-and-add pair is exactly
 * what it looks like, one column going and another arriving, and warning about
 * those would put a warning under every removed column in every migration. A
 * pair that differs only in case is not that: the lexer folds an unquoted
 * identifier, so a column spelled {@code TITLE} here and {@code title} there
 * reaches the model as one and the same string and makes no pair at all. Two
 * names that do reach it differing in case were quoted, which is a person
 * spelling one column two ways rather than two columns. Whether that reasoning
 * holds at all is a question for the dialect, and one dialect answers no, see
 * {@code AbstractScriptBuilder.isRecasedColumnARename}.
 * <p>
 * <b>One class of these warnings is about a script that cannot run rather than
 * about lost data.</b> A name the grammar takes for a keyword - {@code NAME} is
 * one - is carried through unfolded, so an unquoted {@code NAME} pairs with an
 * unquoted {@code name} although PostgreSQL folded both to one column when the
 * table was created. Applying that pair to a live database loses nothing,
 * because it does not get that far: {@code DROP COLUMN "NAME"} fails outright
 * with {@code column "NAME" of relation "doc" does not exist}. The pair is still
 * worth reporting - it is the one place where a script that cannot be applied
 * becomes visible before it is run - but what is wrong there is the parser
 * keeping a case the database never had, and the loss the message speaks of is
 * not what awaits that particular script.
 * <p>
 * Nothing here touches the script. The pair is reported and still generated
 * exactly as before, because the alternative - guessing that a rename was meant
 * and emitting one - would silently keep data the operator may have meant to
 * drop, which is the same fault in the other direction.
 * <p>
 * <b>Where this message ends up, and what not to expect of it.</b> It goes to
 * the log and nowhere else: the CLI is configured with a file appender and no
 * console one, so an operator watching a terminal will not see it, and in the
 * plugin it reaches the Error Log view, which has to be opened. That is a
 * deliberate choice of the owner rather than an oversight - the form is the one
 * the project already uses to say this kind of thing, see
 * {@link ColumnVisibility#reportPinnedColumns}, and a channel of its own would
 * have to be invented and carried. So the complaint this answers - that the tool
 * said nothing - is answered for whoever reads the log, and not for whoever only
 * watches the terminal. A maintainer who concludes one day that the warning does
 * not work should check where they are looking before checking this class.
 *
 * @see ColumnVisibility for the columns a migration is not allowed to speak of
 */
public final class RecasedColumns {

    private static final Logger LOG = LoggerFactory.getLogger(RecasedColumns.class);

    /**
     * Writes to the log every column of the table pair that leaves under one
     * spelling and arrives under another of the same name.
     * <p>
     * At {@code warn}, unlike the merely explanatory notes around it: this one
     * says that a script about to be executed loses data, and it has to stand
     * out in a pipeline log that is read after the fact as much as on a screen
     * that is read before it.
     *
     * @param oldState the state a migration starts from
     * @param newState the state a migration produces
     * @param managed  the columns the migration manages, as bound to this pair
     */
    public static void report(ITable oldState, ITable newState, ColumnVisibility managed) {
        if (!LOG.isWarnEnabled()) {
            return;
        }

        for (var entry : recasedColumns(oldState, newState, managed).entrySet()) {
            LOG.warn(Messages.RecasedColumns_log_column_recased.formatted(
                    entry.getKey(), newState.getQualifiedName(), entry.getValue()));
        }
    }

    /**
     * Names the columns of a table that the migration drops in favour of a
     * column spelled the same but in another case.
     * <p>
     * A column the other state also holds under its exact name is neither
     * dropped nor added and cannot be half of such a pair, so the answer is
     * looked for among the columns that one state has and the other does not.
     * A hidden column is left out for the same reason: it produces no statement
     * at all, see {@link DiffTree#addColumns}, so nothing about it can be lost.
     *
     * @param oldState the state a migration starts from
     * @param newState the state a migration produces
     * @param managed  the columns the migration manages, as bound to this pair
     * @return the dropped columns mapped to the added column each of them is a
     * respelling of, in the order the old state holds them, empty when the pair
     * holds no such column
     */
    public static Map<String, String> recasedColumns(ITable oldState, ITable newState, ColumnVisibility managed) {
        List<String> dropped = onlyIn(oldState, newState, managed);
        if (dropped.isEmpty()) {
            return Map.of();
        }

        List<String> added = onlyIn(newState, oldState, managed);
        if (added.isEmpty()) {
            return Map.of();
        }

        Map<String, String> recased = new LinkedHashMap<>();
        for (String from : dropped) {
            for (String to : added) {
                // the second half cannot fail while the candidates come from
                // onlyIn, which already refuses a name the other state holds;
                // it is kept because the requirement is that the two names
                // really differ, not that some other method saw to it
                if (from.equalsIgnoreCase(to) && !from.equals(to)) {
                    recased.put(from, to);
                    break;
                }
            }
        }
        return recased;
    }

    /**
     * @return the managed columns of the first state that the second one does
     * not hold under that exact name, in the order the first state holds them
     */
    private static List<String> onlyIn(ITable state, ITable other, ColumnVisibility managed) {
        Set<String> otherNames = new HashSet<>();
        for (IColumn column : other.getColumns()) {
            otherNames.add(column.getName());
        }

        List<String> names = new ArrayList<>();
        for (IColumn column : state.getColumns()) {
            if (!otherNames.contains(column.getName()) && !managed.isHidden(column)) {
                names.add(column.getName());
            }
        }
        return names;
    }

    private RecasedColumns() {
    }
}
