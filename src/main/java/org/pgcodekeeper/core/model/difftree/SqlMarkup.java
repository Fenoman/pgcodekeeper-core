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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Finds the stretches of a rendered statement that a comparison has something to
 * say about, so that a reader can be shown which lines those are.
 * <p>
 * Two things are looked for, and they share this class because they share the
 * reading of the text - which is the whole cost of the job: the columns a
 * {@code type=COLUMN} rule names, see {@link ColumnVisibility#marksIn}, and the
 * values the settings tell the comparison to overlook, see
 * {@link IgnoredValues}.
 * <p>
 * <b>Reading only.</b> Nothing here parses SQL for meaning, decides anything
 * about a comparison or touches a model: it is given a finished rendering and a
 * verdict, and answers with the stretches of that text the verdict is about.
 * What is done with them - a colour, a ruler mark, nothing at all - is the
 * caller's business, and no caller of this exists on any path that writes.
 * <p>
 * <b>A column is more than its declaration.</b> Everything a column carries
 * beside the line inside the {@code CREATE} body is written by a statement of
 * its own that names it: its comment, its statistics target, its storage mode,
 * a privilege granted on it alone, a {@code NOT NULL} it holds under a name, the
 * default a Microsoft SQL table keeps as a constraint. Every one of those
 * follows the column out of a project file, so a rendering that marked the
 * declaration alone would be telling half the truth.
 * <p>
 * <b>A statement that defines another object is left alone.</b> An index, a key,
 * a policy or a trigger that names a column is the reason that column is
 * {@link ColumnMark#PINNED} rather than hidden - it is never why a column
 * leaves - and painting a whole index over would say something the marking does
 * not mean. Which of them holds a column is a better question for words than for
 * colour, and {@link ColumnVisibility#pinnedColumns} answers it.
 * <p>
 * <b>The match is on names and is deliberately wide.</b> An identifier equal to
 * the name of a marked column marks the statement it stands in, wherever in that
 * statement it stands. A role or a table named exactly like a hidden column of
 * the same table would therefore be marked as well. That costs a reader one
 * coloured line and can cost nothing else, while narrowing it would risk the one
 * thing that must not happen - a statement about a hidden column left unmarked
 * beside the column it belongs to.
 * <p>
 * <b>A value is marked by the clause that states it, and by no more.</b> The
 * cache of a sequence is one clause among several that are compared as usual, so
 * the line it stands on is marked and the {@code CREATE SEQUENCE} around it is
 * not. A statistics target is a statement of its own - it exists to state that
 * target and states nothing else - so the statement is marked whole, its
 * {@code ALTER TABLE} header included, which would otherwise be left reading
 * like a change of its own on the line above.
 * <p>
 * <b>An unmigratable collation is marked by the declaration of its column.</b>
 * A collation has no clause of its own: it is written in the middle of the line
 * that declares the column, between the type and the default. Marking that
 * fragment alone would put a background over a part of a line, and marking the
 * line is honest here for a reason the collation does not share with the other
 * two - the answer is only ever given for a column whose <em>whole</em>
 * difference is that collation, see
 * {@link org.pgcodekeeper.core.database.api.schema.IColumn#differsOnlyInUnmigratableCollation},
 * so nothing else on the line is migrating either. A column whose type or
 * default differs as well is migrated and is not marked at all. The two places a
 * declaration is written are both marked and no third is: the entry inside the
 * body of a {@code CREATE}, and the {@code ADD COLUMN} a column shown on its own
 * is rendered as. The
 * comment, the privilege and the statistics target of that same column are
 * compared and migrated as usual and are left alone, which is why the wide name
 * match above is not used here.
 * <p>
 * Text is read the way every dialect writes it: identifiers quoted with
 * {@code "}, {@code `} or {@code []}, literals in {@code '} and {@code $tag$},
 * comments in {@code --} and slash-star. A name inside a literal or a comment is
 * not an identifier and is not matched - a comment that mentions {@code CACHE}
 * or a text that quotes {@code SET STATISTICS} is text.
 * <p>
 * <b>With one exception, and it is not a body anybody wrote.</b> A rendering may
 * put a statement inside {@code DO $$ ... $$} so that it can be run twice, see
 * {@code PgAbstractStatement.appendSqlWrappedInDo}. What stands there is a
 * statement of the object being rendered and is read as one, see
 * {@link #opensDoBody}; the body of a function is a literal and stays one.
 *
 * @see ColumnVisibility for which columns are marked and why
 * @see IgnoredValues for which values are marked and why
 */
public final class SqlMarkup {

    /** The words that turn {@code ADD CONSTRAINT} into an object of its own. */
    private static final List<String> CONSTRAINT_KINDS =
            List.of("PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "EXCLUDE");

    /** How many words {@code IF NOT EXISTS} stands between a keyword and a name. */
    private static final int EXISTS_GUARD_WORDS = 3;

    /**
     * A stretch of a rendering the comparison has something to say about.
     * <p>
     * Always whole lines, and never overlapping another: a background drawn over
     * a part of a line reads as an accident, and the line is what a reader
     * follows.
     *
     * @param offset where the stretch begins in the rendering
     * @param length how long it is, line delimiters excluded
     * @param mark   what the comparison has to say about it
     */
    public record Marked(int offset, int length, SqlMark mark) {
    }

    private SqlMarkup() {
    }

    /**
     * Finds every stretch of a rendering the comparison has something to say
     * about.
     *
     * @param sql    a rendering of one object, as a reader is shown it
     * @param marks  the columns to look for, mapped to what the rules did to
     *               each, see {@link ColumnVisibility#marksIn}; may be
     *               {@code null}
     * @param values the values of this object the settings overlook, see
     *               {@link IgnoredValues#of}; may be {@code null}
     * @return the stretches to mark, ordered and disjoint, empty when there is
     * nothing to mark
     */
    public static List<Marked> rangesIn(String sql, Map<String, ColumnMark> marks, IgnoredValues values) {
        boolean noColumns = marks == null || marks.isEmpty();
        boolean noValues = values == null || values.isEmpty();
        if (sql == null || sql.isEmpty() || (noColumns && noValues)) {
            return List.of();
        }

        Map<String, ColumnMark> columns = noColumns ? Map.of() : marks;
        IgnoredValues ignored = noValues ? IgnoredValues.NONE : values;
        List<Marked> found = new ArrayList<>();
        for (List<Atom> statement : statementsOf(sql)) {
            markStatement(sql, statement, columns, ignored, found);
        }

        // a value stands inside a statement that may itself be marked, so the
        // two are not found in the order they are written in
        found.sort(Comparator.comparingInt(Marked::offset));
        return coalesce(found);
    }

    private static void markStatement(String sql, List<Atom> atoms, Map<String, ColumnMark> marks,
            IgnoredValues values, List<Marked> found) {
        if (isTableDefinition(atoms)) {
            // the body of a CREATE states columns, and no dialect states a cache
            // or a statistics target among them - a collation it does state, and
            // inside the entry of the column that carries it
            markColumnEntries(sql, atoms, marks, values, found);
            return;
        }

        markIgnoredValues(sql, atoms, values, found);
        if (definesAnotherObject(atoms)) {
            return;
        }

        SqlMark mark = null;
        for (Atom atom : atoms) {
            if (atom.word()) {
                mark = quieter(mark, markOf(marks.get(atom.text())));
            }
        }
        if (mark != null) {
            found.add(wholeLines(sql, atoms.get(0).offset(), atoms.get(atoms.size() - 1).end(), mark));
        }
    }

    /**
     * Marks the clauses of a statement that state a value the settings overlook.
     * <p>
     * Asked of every statement that does not create a table, the one a
     * {@code CREATE SEQUENCE} included: a statement that brings another object
     * into being is not why a column is marked, but it is very much where the
     * cache of a sequence is written.
     */
    private static void markIgnoredValues(String sql, List<Atom> atoms, IgnoredValues values,
            List<Marked> found) {
        if (values.isEmpty()) {
            return;
        }

        String added = addedColumn(atoms);
        if (added != null && values.collationColumns().contains(added)) {
            // the statement declares the column and declares nothing else, so
            // the ALTER TABLE header belongs to the declaration as much as the
            // header of a statistics target belongs to its own
            found.add(wholeLines(sql, atoms.get(0).offset(), atoms.get(atoms.size() - 1).end(),
                    SqlMark.VALUE_UNMIGRATABLE));
        }

        String column = alteredColumn(atoms);
        boolean sequence = namesWord(atoms, "SEQUENCE");
        for (int i = 0; i < atoms.size(); i++) {
            Atom atom = atoms.get(i);
            if (sequence && isWord(atom, "CACHE") && cacheIsIgnored(values, column)) {
                // the number after it is not an atom, and NO stands before it:
                // the line is what states the clause and the line is what carries
                found.add(wholeLines(sql, atom.offset(), atom.end(), SqlMark.VALUE_IGNORED));
            } else if (column != null && isWord(atom, "STATISTICS") && i > 0 && isWord(atoms.get(i - 1), "SET")
                    && values.statisticsColumns().contains(column)) {
                found.add(wholeLines(sql, atoms.get(0).offset(), atoms.get(atoms.size() - 1).end(),
                        SqlMark.VALUE_IGNORED));
            }
        }
    }

    /**
     * Whether the cache written in this statement is one of the overlooked ones.
     * A statement that alters a column states the cache of the identity sequence
     * of that column; one that does not states the cache of a sequence that is
     * an object of its own.
     */
    private static boolean cacheIsIgnored(IgnoredValues values, String column) {
        return column == null ? values.sequenceCache() : values.cacheColumns().contains(column);
    }

    /** The column an {@code ALTER ... COLUMN} names, {@code null} when none is named. */
    private static String alteredColumn(List<Atom> atoms) {
        for (int i = 0; i + 2 < atoms.size(); i++) {
            if (isWord(atoms.get(i), "ALTER") && isWord(atoms.get(i + 1), "COLUMN") && atoms.get(i + 2).word()) {
                return atoms.get(i + 2).text();
            }
        }
        return null;
    }

    /**
     * The column an {@code ADD COLUMN} declares, {@code null} when the statement
     * declares none. The guard the settings may put in front of the name is
     * skipped; a column really called {@code IF} is written quoted and is not
     * mistaken for it.
     */
    private static String addedColumn(List<Atom> atoms) {
        for (int i = 0; i + 2 < atoms.size(); i++) {
            if (!isWord(atoms.get(i), "ADD") || !isWord(atoms.get(i + 1), "COLUMN")) {
                continue;
            }
            int at = i + 2;
            if (isWord(atoms.get(at), "IF")) {
                at += EXISTS_GUARD_WORDS;
            }
            return at < atoms.size() && atoms.get(at).word() ? atoms.get(at).text() : null;
        }
        return null;
    }

    private static boolean namesWord(List<Atom> atoms, String word) {
        for (Atom atom : atoms) {
            if (isWord(atom, word)) {
                return true;
            }
        }
        return false;
    }

    /** What a rendering says about a column, {@code null} when it says nothing. */
    private static SqlMark markOf(ColumnMark verdict) {
        return verdict == null || verdict == ColumnMark.MANAGED ? null : SqlMark.of(verdict);
    }

    /**
     * Marks the declaration of every marked column inside the body of a
     * {@code CREATE}.
     * <p>
     * An entry of that body is a column when it begins with the name of one: a
     * key or a check written among the columns begins with a word of the dialect
     * and can match nothing, since only the names a rule produced are ever
     * looked for.
     */
    private static void markColumnEntries(String sql, List<Atom> atoms, Map<String, ColumnMark> marks,
            IgnoredValues values, List<Marked> found) {
        int body = indexOfBody(atoms);
        if (body < 0) {
            return;
        }

        Atom first = null;
        Atom last = null;
        for (int i = body + 1; i < atoms.size(); i++) {
            Atom atom = atoms.get(i);
            if (atom.depth() == 0) {
                // the parenthesis that closes the body
                break;
            }
            if (atom.depth() == 1 && !atom.word() && ",".equals(atom.text())) {
                markEntry(sql, first, last, marks, values, found);
                first = null;
                last = null;
            } else {
                if (first == null) {
                    first = atom;
                }
                last = atom;
            }
        }
        markEntry(sql, first, last, marks, values, found);
    }

    private static void markEntry(String sql, Atom first, Atom last, Map<String, ColumnMark> marks,
            IgnoredValues values, List<Marked> found) {
        if (first == null || !first.word()) {
            return;
        }
        SqlMark mark = quieter(markOf(marks.get(first.text())),
                values.collationColumns().contains(first.text()) ? SqlMark.VALUE_UNMIGRATABLE : null);
        if (mark != null) {
            found.add(wholeLines(sql, first.offset(), last.end(), mark));
        }
    }

    /** Where the body of a {@code CREATE} opens, {@code -1} when it does not. */
    private static int indexOfBody(List<Atom> atoms) {
        for (int i = 0; i < atoms.size(); i++) {
            Atom atom = atoms.get(i);
            if (!atom.word() && "(".equals(atom.text()) && atom.depth() == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether a statement creates the table whose columns are being looked for.
     * Every dialect writes {@code CREATE}, whatever it puts between that and
     * {@code TABLE}, and writes the columns in the first parentheses after it.
     */
    private static boolean isTableDefinition(List<Atom> atoms) {
        if (atoms.isEmpty() || !isWord(atoms.get(0), "CREATE")) {
            return false;
        }
        for (Atom atom : atoms) {
            if (!atom.word() && "(".equals(atom.text())) {
                return false;
            }
            if (isWord(atom, "TABLE")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a statement brings another object into being rather than saying
     * something about a column.
     * <p>
     * Everything a dialect creates outright it creates with {@code CREATE}. What
     * is left is the constraint written as an {@code ALTER}, which is an object
     * of its own when it is a key, a check or an exclusion - and is a part of a
     * column of its own table when it is the {@code NOT NULL} or the
     * {@code DEFAULT} that column holds under a name.
     */
    private static boolean definesAnotherObject(List<Atom> atoms) {
        if (atoms.isEmpty()) {
            return true;
        }
        if (isWord(atoms.get(0), "CREATE")) {
            return true;
        }
        for (int i = 0; i + 3 < atoms.size(); i++) {
            if (isWord(atoms.get(i), "ADD") && isWord(atoms.get(i + 1), "CONSTRAINT")
                    && isConstraintKind(atoms.get(i + 3))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConstraintKind(Atom atom) {
        for (String kind : CONSTRAINT_KINDS) {
            if (isWord(atom, kind)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWord(Atom atom, String word) {
        return atom.word() && !atom.quoted() && atom.text().equalsIgnoreCase(word);
    }

    /**
     * The mark a stretch carries when two of them speak about it, which is the
     * one that claims the least.
     * <p>
     * A column something needs is the calmer answer and wins a line it shares
     * with anything: what it says - that the line is compared as usual - is true
     * of the line as a whole, while any of the other three would be telling a
     * reader to stop looking at a line the migration may well carry. Among those
     * that do say a line is overlooked, a column leaving is the larger fact and
     * the one worth naming, since the line goes out of the project files
     * altogether and what is written on it stops mattering. Between the last
     * two, the settings claim less than a difference nothing can migrate, and
     * under-claiming is the only mistake this is allowed to make. All of them
     * are read the same way by whoever only wants to know whether the line takes
     * part, see {@link SqlMark#ignored()}.
     */
    private static SqlMark quieter(SqlMark held, SqlMark next) {
        if (held == null) {
            return next;
        }
        if (next == null || held == next) {
            return held;
        }
        if (held == SqlMark.COLUMN_KEPT || next == SqlMark.COLUMN_KEPT) {
            return SqlMark.COLUMN_KEPT;
        }
        if (held == SqlMark.COLUMN_LEAVING || next == SqlMark.COLUMN_LEAVING) {
            return SqlMark.COLUMN_LEAVING;
        }
        return SqlMark.VALUE_IGNORED;
    }

    /** The whole lines a stretch of text falls on, delimiters excluded. */
    private static Marked wholeLines(String sql, int from, int to, SqlMark mark) {
        int start = from;
        while (start > 0 && sql.charAt(start - 1) != '\n') {
            start--;
        }
        int end = to;
        while (end < sql.length() && sql.charAt(end) != '\n') {
            end++;
        }
        if (end > start && sql.charAt(end - 1) == '\r') {
            end--;
        }
        return new Marked(start, end - start, mark);
    }

    /**
     * Joins the stretches that meet, so that no reader is shown a seam and no
     * caller has to cope with two marks over one character.
     */
    private static List<Marked> coalesce(List<Marked> found) {
        if (found.size() < 2) {
            return found;
        }

        List<Marked> joined = new ArrayList<>(found.size());
        for (Marked next : found) {
            int at = joined.size() - 1;
            Marked held = at < 0 ? null : joined.get(at);
            if (held != null && next.offset() <= held.offset() + held.length()) {
                int end = Math.max(held.offset() + held.length(), next.offset() + next.length());
                joined.set(at, new Marked(held.offset(), end - held.offset(),
                        quieter(held.mark(), next.mark())));
            } else {
                joined.add(next);
            }
        }
        return joined;
    }

    // ----------------------------------------------------------------- reading

    /**
     * One thing a statement is made of: a name, a word of the dialect, or one of
     * the few characters that shape a statement.
     *
     * @param offset where it begins
     * @param end    where it ends
     * @param text   a name as its model holds it, a word as it was written, or
     *               the character itself
     * @param word   whether it is a name or a word rather than a character
     * @param quoted whether a name was written quoted, which a word never is
     * @param depth  how many parentheses it stands inside
     */
    private record Atom(int offset, int end, String text, boolean word, boolean quoted, int depth) {
    }

    /**
     * Reads a rendering into statements.
     * <p>
     * A statement ends where every dialect ends one: at a semicolon, at a
     * {@code GO} of its own line, or at a blank line - which is what separates
     * the statements of a rendering that has no semicolons to separate them.
     * Only outside parentheses, so that a blank line inside a body separates
     * nothing.
     */
    private static List<List<Atom>> statementsOf(String sql) {
        List<List<Atom>> statements = new ArrayList<>();
        read(sql, 0, sql.length(), statements);
        return statements;
    }

    /**
     * Reads one stretch of a rendering into statements, appending them to what
     * is already found.
     * <p>
     * A stretch rather than the whole text, because the body of a {@code DO} is
     * read by a call of its own - see {@link #opensDoBody} - and the offsets of
     * everything inside it have to stay the offsets of the rendering the caller
     * was given. Nothing here is ever handed a copy of a substring for that
     * reason.
     *
     * @param sql        the whole rendering
     * @param from       where this stretch begins
     * @param to         where it ends
     * @param statements what has been read so far
     */
    private static void read(String sql, int from, int to, List<List<Atom>> statements) {
        List<Atom> current = new ArrayList<>();
        int depth = 0;
        int i = from;
        int n = to;

        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i = endOfLine(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = endOfBlockComment(sql, i + 2);
                continue;
            }
            if (c == '\'') {
                i = endOfLiteral(sql, i, escapesWithBackslash(sql, i));
                continue;
            }
            if (c == '$') {
                String tag = dollarTagAt(sql, i);
                if (tag != null) {
                    int body = i + tag.length();
                    int close = sql.indexOf(tag, body);
                    if (close < 0 || close > n) {
                        close = -1;
                    }
                    if (opensDoBody(current)) {
                        // the wrapper this tool writes around a statement of its
                        // own, not a body somebody wrote: read what it wraps
                        current = breakStatement(statements, current);
                        read(sql, body, close < 0 ? n : close, statements);
                    }
                    i = close < 0 ? n : close + tag.length();
                    continue;
                }
            }
            if (c == '\n' && depth == 0 && isBlankLineAhead(sql, i + 1)) {
                current = breakStatement(statements, current);
                i++;
                continue;
            }
            if (c == '"' || c == '`' || c == '[') {
                int end = endOfQuotedName(sql, i, c);
                if (end > i) {
                    current.add(new Atom(i, end, unquote(sql.substring(i + 1, end - 1), closerOf(c)),
                            true, true, depth));
                    i = end;
                    continue;
                }
            }
            if (isNameStart(c)) {
                int end = endOfName(sql, i);
                String text = sql.substring(i, end);
                if (depth == 0 && "GO".equalsIgnoreCase(text) && isAloneOnItsLine(sql, i, end)) {
                    current = breakStatement(statements, current);
                } else {
                    current.add(new Atom(i, end, text, true, false, depth));
                }
                i = end;
                continue;
            }
            if (c == '(') {
                current.add(new Atom(i, i + 1, "(", false, false, depth));
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
                current.add(new Atom(i, i + 1, ")", false, false, depth));
            } else if (c == ',') {
                current.add(new Atom(i, i + 1, ",", false, false, depth));
            } else if (c == ';' && depth == 0) {
                current.add(new Atom(i, i + 1, ";", false, false, depth));
                current = breakStatement(statements, current);
            }
            i++;
        }

        breakStatement(statements, current);
    }

    /**
     * Whether the dollar-quoted stretch about to be read is the body of a
     * {@code DO}, which is to say a wrapper this tool wrote itself.
     * <p>
     * <b>A body somebody wrote is text and stays text.</b> The body of a
     * function is a literal as far as this class is concerned, exactly as the
     * class comment says: a routine that mentions {@code CACHE} mentions it in
     * code of another language that no comparison of this rendering is about.
     * The body of a {@code DO} is the opposite case - it holds one statement of
     * the very object being rendered, put there by
     * {@code PgAbstractStatement.appendSqlWrappedInDo} so that the statement can
     * be run twice, see {@code PgAbstractTable.writeColumn} for the identity of
     * a column and {@code PgConstraint} for a constraint. Left unread, the cache
     * of an identity and every column named by a wrapped constraint would go
     * unmarked for no reason a reader could see: the same rendering marks them
     * whenever the setting that writes the wrapper happens to be off.
     * <p>
     * <b>And only the shape this tool writes.</b> The statement has to be the
     * bare word {@code DO} and nothing else, which is what the wrapper opens
     * with. A {@code DO} written by hand with a language in front of its body is
     * left alone, as is every other dollar-quoted body there is: under-marking
     * is the only mistake this class is allowed to make.
     */
    private static boolean opensDoBody(List<Atom> statement) {
        return statement.size() == 1 && isWord(statement.get(0), "DO");
    }

    private static List<Atom> breakStatement(List<List<Atom>> statements, List<Atom> current) {
        if (!current.isEmpty()) {
            statements.add(current);
        }
        return new ArrayList<>();
    }

    /** Whether nothing but whitespace stands between here and the next line. */
    private static boolean isBlankLineAhead(String sql, int from) {
        for (int i = from; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\n') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAloneOnItsLine(String sql, int from, int to) {
        for (int i = from - 1; i >= 0 && sql.charAt(i) != '\n'; i--) {
            if (!Character.isWhitespace(sql.charAt(i))) {
                return false;
            }
        }
        for (int i = to; i < sql.length() && sql.charAt(i) != '\n'; i++) {
            if (!Character.isWhitespace(sql.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int endOfLine(String sql, int from) {
        int at = sql.indexOf('\n', from);
        return at < 0 ? sql.length() : at;
    }

    private static int endOfBlockComment(String sql, int from) {
        // PostgreSQL nests them, and a dialect that does not never writes one inside another
        int depth = 1;
        for (int i = from; i < sql.length() - 1; i++) {
            if (sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                depth++;
                i++;
            } else if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/' && --depth == 0) {
                return i + 2;
            }
        }
        return sql.length();
    }

    /** Whether a literal is one of the {@code E'...'} kind, where a backslash escapes. */
    private static boolean escapesWithBackslash(String sql, int quote) {
        if (quote == 0) {
            return false;
        }
        char before = sql.charAt(quote - 1);
        return (before == 'E' || before == 'e') && (quote < 2 || !isNamePart(sql.charAt(quote - 2)));
    }

    private static int endOfLiteral(String sql, int from, boolean backslashEscapes) {
        for (int i = from + 1; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\') {
                i++;
            } else if (c == '\'') {
                if (i + 1 >= sql.length() || sql.charAt(i + 1) != '\'') {
                    return i + 1;
                }
                i++;
            }
        }
        return sql.length();
    }

    /**
     * The {@code $tag$} that opens a dollar-quoted stretch here, or {@code null}
     * when the dollar opens none - it is a valid character of a name as well.
     * <p>
     * The tag rather than the end of the stretch, because a caller that reads
     * the inside of one needs to know where the inside begins.
     */
    private static String dollarTagAt(String sql, int from) {
        if (from > 0 && isNamePart(sql.charAt(from - 1))) {
            return null;
        }
        int tagEnd = from + 1;
        while (tagEnd < sql.length() && isTagPart(sql.charAt(tagEnd))) {
            tagEnd++;
        }
        if (tagEnd >= sql.length() || sql.charAt(tagEnd) != '$') {
            return null;
        }
        return sql.substring(from, tagEnd + 1);
    }

    /**
     * The end of a quoted name, or {@code from} itself when the character opens
     * no name: a bracket is how Microsoft SQL quotes one and how PostgreSQL
     * writes an array, and only one of the two is ever closed on its line.
     */
    private static int endOfQuotedName(String sql, int from, char opener) {
        char closer = closerOf(opener);
        for (int i = from + 1; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == closer) {
                if (i + 1 >= sql.length() || sql.charAt(i + 1) != closer) {
                    return i + 1;
                }
                i++;
            } else if (opener == '[' && c == '\n') {
                return from;
            }
        }
        return from;
    }

    private static char closerOf(char opener) {
        return opener == '[' ? ']' : opener;
    }

    private static String unquote(String text, char closer) {
        return text.indexOf(closer) < 0 ? text
                : text.replace(String.valueOf(closer).repeat(2), String.valueOf(closer));
    }

    private static int endOfName(String sql, int from) {
        int i = from + 1;
        while (i < sql.length() && isNamePart(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isNameStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isNamePart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    private static boolean isTagPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }
}
