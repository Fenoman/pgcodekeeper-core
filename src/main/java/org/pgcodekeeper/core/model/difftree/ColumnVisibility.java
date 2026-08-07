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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.IConstraint;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.ignorelist.IgnoredObject.AddStatus;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.settings.ISettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells which columns of a table pgCodeKeeper manages.
 * <p>
 * A column named by a hiding rule leaves the comparison. It is never added,
 * never dropped and never altered, and it takes no part in deciding whether its
 * table changed: the two states of the database are compared as if the column
 * were not in either of them.
 * <p>
 * <b>Hiding lives in the comparison. It does not live in the generation.</b>
 * Nothing here is asked while a statement is being written. A migration script,
 * a database created from nothing and a script made by hand in the IDE all state
 * a table exactly as the project declares it, because the truth of a script is
 * the project - a column is written whenever the project holds it and is absent
 * whenever the project does not. That is safe precisely because of the paragraph
 * above: no statement <em>about</em> a hidden column is ever asked for, so
 * writing whatever is asked for can never write one.
 * <p>
 * The one place these rules do decide which columns are <em>written</em> is a
 * project file, see {@link #forProjectFile(List)}. That decision is taken while
 * the export builds the object to write, before any generator sees it.
 * <p>
 * They are asked one more thing, and it changes nothing at all: what to tell
 * whoever is reading the comparison, see {@link #markOf(IColumn)} and
 * {@link SqlMarkup}. The comparison pane of the Eclipse plugin renders both
 * states straight out of the loaded models, every column present, and colours
 * the ones a rule names - one shade for a column that leaves, another for a
 * column a rule names and something needs. Nothing is taken out of the text: a
 * column silently missing from a rendering is indistinguishable from a column
 * silently lost, and a rule that stopped firing would then be invisible too.
 * <p>
 * Two properties of that rule are deliberately narrower than the rules for
 * every other kind of object, and both exist because a column is not an object
 * a script may simply leave out - it is a part of the definition of its table,
 * and dropping a part of a definition rewrites the whole.
 * <ul>
 * <li><b>Only a rule that names {@code COLUMN} in its {@code type=} attribute
 * can hide a column.</b> A rule without a type attribute matches every type and
 * would, applied to columns as well, silently strip columns out of tables that
 * a list written years ago never meant to touch. The blast radius of that
 * mistake is a rewritten table rather than a skipped object, so a column asks
 * to be named on purpose.</li>
 * <li><b>A column no rule matches is visible</b>, whatever the mode of the list.
 * A white list hides everything it does not mention, which for columns would
 * turn {@code SHOW NONE mytable} into a table with no columns at all. Only a
 * rule that hides can hide a column.</li>
 * </ul>
 * Together the two make an ignore list without a single {@code type=COLUMN}
 * rule provably unable to change anything about columns, which is what keeps
 * every existing list generating exactly the script it generated before.
 * <p>
 * The third property is what makes the rule safe rather than merely narrow:
 * <b>a column is hidden only while nothing in the database needs it.</b> A
 * column named by a key, a check, an index, a policy, a trigger, a generated
 * column or a partition key stays managed as if no rule mentioned it, because
 * leaving it out of the project would leave the rest of that definition standing
 * on nothing. So does a column named from outside the table - by a view, a
 * function, an extended statistics object, the {@code OWNED BY} of a sequence -
 * which fails just as loudly and is not written inside the {@code CREATE TABLE}
 * to be noticed. A project relieved of such a column would then create a database
 * that does not work, so the rule applies exactly where the column is inert, and
 * one rule may well hide a column on one table and leave it alone on the next.
 * That is not an inconsistency but the whole point, and it is written to the log
 * so that nobody has to guess which of the two happened, see
 * {@link #pinnedColumns(ITable)}.
 * <p>
 * The same reasoning keeps a whole table whole where a dialect cannot write a
 * {@code CREATE} of no columns at all, see
 * {@link ITable#canCreateWithoutColumns()}: a file needs a column as surely as a
 * key needs the column it is built on.
 * <p>
 * What counts as needing a column is collected in one place,
 * {@link #neededColumns(ITable)}, and read from four sources: the column list a
 * constraint publishes, the references the loader resolved for the table, its
 * children and its columns, the clauses a dialect keeps as raw text, see
 * {@link ITable#getClausesNamingColumns()}, and the columns every other
 * statement of the database names, see
 * {@link IStatement#getReferencedColumns()}. The fourth of those is the whole
 * database and is read once for it rather than once per table, see
 * {@link ColumnUsers} for who holds that reading and for how long.
 * <p>
 * The rules themselves are read by {@link IgnoreListFilter}, the one
 * implementation of them, so this class only decides which rules are asked and
 * what an unmatched column means. Contrast {@link ChildVisibility}, which asks
 * the very same filter for the children of one container and does default to
 * the mode of the list: those children are objects in their own right.
 *
 * @see #forPair(ITable, ITable) for why both states of a table have their say
 * @see #forProjectFile(List) for the backstop that never lets a broken body out
 */
public final class ColumnVisibility {

    private static final Logger LOG = LoggerFactory.getLogger(ColumnVisibility.class);

    /** No rule can hide a column: every column is managed. */
    private static final ColumnVisibility ALL_VISIBLE = new ColumnVisibility(null, null, ColumnUsers.NONE);

    /** {@code null} when nothing can be hidden. */
    private final IgnoreListFilter filter;

    /**
     * The columns kept whatever the rules say, mapped to what keeps each of
     * them, already collected from both states of one table. {@code null} while
     * no pair has been bound, and then every question is answered out of the
     * table the column belongs to.
     */
    private final Map<String, String> pinned;

    /**
     * Who names the columns of a table, read once per database rather than once
     * per table. Never {@code null}, and shared with every visibility derived
     * from this one, see {@link #forPair(ITable, ITable)}.
     */
    private final ColumnUsers users;

    private ColumnVisibility(IgnoreListFilter filter, Map<String, String> pinned, ColumnUsers users) {
        this.filter = filter;
        this.pinned = pinned;
        this.users = users;
    }

    /**
     * The answer where nothing is hidden, for a caller that has no settings to
     * read an ignore list from and therefore manages every column.
     *
     * @return a visibility that hides nothing
     */
    public static ColumnVisibility all() {
        return ALL_VISIBLE;
    }

    /**
     * Reads the ignore list of a comparison.
     * <p>
     * The settings are asked for one thing besides their rules: the holder of
     * the index that answers what still names a column, see {@link ColumnUsers}.
     * They are the right place for it because they last exactly one operation
     * and no longer, and because they are already at hand wherever this question
     * is asked - an export asks it once per table, and every one of those calls
     * lands on the same holder.
     *
     * @param settings settings of the comparison, may be {@code null}
     * @return the columns that comparison manages
     */
    public static ColumnVisibility of(ISettings settings) {
        return settings == null ? ALL_VISIBLE
                : of(settings.getIgnoreList(), settings.getColumnUsers());
    }

    /**
     * Binds an ignore list to the columns it can hide.
     * <p>
     * For a caller holding rules rather than settings. The index is scoped to
     * the returned visibility and to everything derived from it, which is one
     * operation for the one caller there is - the difference tree of the Eclipse
     * plugin builds one of these and asks it about every table it shows.
     *
     * @param ignoreList the ignore list to apply, may be {@code null}
     * @param dbNames    database names matched against the {@code db=} rule
     *                   attribute; when none are given a rule scoped to a
     *                   database matches nothing and the column stays managed,
     *                   the same answer {@code TreeFlattener} gives an object
     *                   when the migration does not name its database
     * @return the columns that list leaves managed
     */
    public static ColumnVisibility of(IgnoreList ignoreList, String... dbNames) {
        return of(ignoreList, ColumnUsers.forOperation(), dbNames);
    }

    private static ColumnVisibility of(IgnoreList ignoreList, ColumnUsers users, String... dbNames) {
        if (ignoreList == null) {
            return ALL_VISIBLE;
        }

        List<IgnoredObject> hiding = null;
        for (IgnoredObject rule : ignoreList.getList()) {
            if (rule.getObjTypes().contains(DbObjType.COLUMN)) {
                if (hiding == null) {
                    hiding = new ArrayList<>();
                }
                hiding.add(rule);
            }
        }
        if (hiding == null || hiding.stream().allMatch(IgnoredObject::isShow)) {
            // no rule addresses a column, or none of the rules that do can hide
            return ALL_VISIBLE;
        }

        // the mode of this derived list is never read: only getMatchedStatus is
        // asked of the filter, and that is the answer of the rules alone
        IgnoreList columnRules = new IgnoreList();
        hiding.forEach(columnRules::add);
        return new ColumnVisibility(new IgnoreListFilter(columnRules, dbNames), null, users);
    }

    /**
     * Reports whether any column at all can be hidden, so that a caller may skip
     * the work of asking about each of them.
     *
     * @return true when at least one rule can hide a column
     */
    public boolean hidesAnything() {
        return filter != null;
    }

    /**
     * Reports whether pgCodeKeeper stops managing a column.
     *
     * @param column the column to decide about
     * @return true when the column is hidden and must be treated as absent from
     * both states of the database
     */
    public boolean isHidden(IColumn column) {
        return markOf(column) == ColumnMark.HIDDEN;
    }

    /**
     * Reports what these rules did to one column.
     * <p>
     * The same question {@link #isHidden(IColumn)} asks, answered in full: a
     * column a rule names and something needs is neither hidden nor untouched,
     * and telling the two apart is the whole of what a reader of a comparison
     * needs, see {@link ColumnMark}.
     *
     * @param column the column to decide about
     * @return what the rules did to it, {@link ColumnMark#MANAGED} when no rule
     * names it
     */
    public ColumnMark markOf(IColumn column) {
        if (filter == null || !(column.getParent() instanceof ITable table) || !hiddenByRule(column)) {
            return ColumnMark.MANAGED;
        }
        Map<String, String> kept = pinned == null ? neededColumns(table) : pinned;
        return kept.containsKey(column.getName()) ? ColumnMark.PINNED : ColumnMark.HIDDEN;
    }

    /**
     * Names every column of one state of a table that a rule names, and says of
     * each which of the two things happened to it.
     * <p>
     * The columns no rule names are not in the answer: there is nothing to tell
     * about them, and an empty answer is the answer for every ignore list that
     * hides no column - which is very nearly all of them.
     * <p>
     * Bind the pair first, see {@link #forPair(ITable, ITable)}, wherever both
     * states are to be shown at once. The index or the view that keeps a column
     * may exist on one side only, and two states asked separately would then be
     * marked differently for a reason that is not about either of them.
     *
     * @param table one state of a table
     * @return the marked columns in the order the table holds them, empty when
     * the rules name none of them
     */
    public Map<String, ColumnMark> marksIn(ITable table) {
        if (filter == null) {
            return Map.of();
        }

        Map<String, ColumnMark> marks = null;
        Map<String, String> kept = pinned;
        for (IColumn column : table.getColumns()) {
            if (!hiddenByRule(column)) {
                continue;
            }
            if (kept == null) {
                // reading this walks a database, so it is read once and only for
                // a table a rule really names a column of
                kept = neededColumns(table);
            }
            if (marks == null) {
                marks = new LinkedHashMap<>();
            }
            marks.put(column.getName(),
                    kept.containsKey(column.getName()) ? ColumnMark.PINNED : ColumnMark.HIDDEN);
        }
        return marks == null ? Map.of() : marks;
    }

    /**
     * Binds the two states of one table, so that a column needed by either of
     * them is kept in both.
     * <p>
     * The states must agree, or the comparison falls apart: were a column kept
     * on one side and dropped on the other, the two column lists would differ in
     * length and the table would be reported as changed while holding no change
     * a script could carry - the phantom this whole filtering exists to avoid.
     * Taking the union is also the honest answer, since an index that lives in
     * only one of the two states is an index the migration is about to create or
     * drop, and the column under it is being used either way.
     *
     * @param oldState the state a migration starts from, may be {@code null}
     * @param newState the state a migration produces, may be {@code null}
     * @return a visibility bound to those two states
     */
    public ColumnVisibility forPair(ITable oldState, ITable newState) {
        if (filter == null) {
            return this;
        }

        Map<String, String> union = new LinkedHashMap<>();
        if (oldState != null) {
            union.putAll(neededColumns(oldState));
        }
        if (newState != null) {
            // the state a migration starts from answers first, so a column both
            // states keep is reported against the object the reader is looking at
            neededColumns(newState).forEach(union::putIfAbsent);
        }
        return new ColumnVisibility(filter, union, users);
    }

    /**
     * Writes to the log every column of the table that a rule asks to hide while
     * the table still needs it.
     * <p>
     * At {@code info}, because the whole point is that it reaches whoever reads
     * the output of a migration in a pipeline: a rule that visibly applies to one
     * table and not to the next is a bug report waiting to be filed, unless the
     * reason is written down where it happens.
     *
     * @param oldState the state a migration starts from, may be {@code null}
     * @param newState the state a migration produces, may be {@code null}
     */
    public void reportPinnedColumns(ITable oldState, ITable newState) {
        if (filter == null || !LOG.isInfoEnabled()) {
            return;
        }

        Map<String, String> kept = new LinkedHashMap<>();
        ITable named = newState != null ? newState : oldState;
        if (oldState != null) {
            kept.putAll(pinnedColumns(oldState));
        }
        if (newState != null) {
            pinnedColumns(newState).forEach(kept::putIfAbsent);
        }

        for (var entry : kept.entrySet()) {
            LOG.info(Messages.ColumnVisibility_log_column_kept.formatted(
                    entry.getKey(), named.getQualifiedName(), entry.getValue()));
        }
    }

    /**
     * Names the columns of a table that a rule asks to hide while something
     * still needs them, together with what needs each of them.
     * <p>
     * The answer of a bound visibility is the answer of the pair, exactly as
     * {@link #markOf(IColumn)} answers, so that the reason shown for a kept
     * column is the reason it was kept. Unbound, every question is answered out
     * of the one state it is asked about.
     *
     * @param table one state of a table
     * @return the kept columns mapped to what keeps each of them, in the order
     * the table holds them, empty when the rules and the table do not disagree
     */
    public Map<String, String> pinnedColumns(ITable table) {
        if (filter == null) {
            return Map.of();
        }

        Map<String, String> needed = pinned;
        Map<String, String> kept = null;
        for (IColumn column : table.getColumns()) {
            if (!hiddenByRule(column)) {
                continue;
            }
            if (needed == null) {
                needed = neededColumns(table);
            }
            String reason = needed.get(column.getName());
            if (reason != null) {
                if (kept == null) {
                    kept = new LinkedHashMap<>();
                }
                kept.put(column.getName(), reason);
            }
        }
        return kept == null ? Map.of() : kept;
    }

    /**
     * Reports whether a rule asks for this column to be hidden, before the table
     * is asked whether it can spare it.
     */
    private boolean hiddenByRule(IColumn column) {
        if (filter == null || !(column.getParent() instanceof ITable)) {
            // a rule reaches the columns of a table and nothing else: the
            // attributes of a composite type and the columns of a ClickHouse
            // dictionary are of the same type, but they are diffed as a part of
            // the definition of their owner and never become a node of a tree,
            // so hiding one would rewrite that definition with nothing to warn
            // the author of the rule
            return false;
        }

        AddStatus status = filter.getMatchedStatus(
                new Column(column.getName(), qualifiedNameOf(column)));
        return status == AddStatus.SKIP || status == AddStatus.SKIP_SUBTREE;
    }

    /**
     * Keeps the managed columns of a table, in the order they were given.
     *
     * @param columns the columns of one table
     * @param <T>     the column type of the dialect
     * @return the given list itself when nothing is hidden, a copy without the
     * hidden columns otherwise
     */
    public <T extends IColumn> List<T> visible(List<T> columns) {
        if (filter == null) {
            return columns;
        }

        List<T> kept = null;
        int size = columns.size();
        for (int i = 0; i < size; i++) {
            T column = columns.get(i);
            if (isHidden(column)) {
                if (kept == null) {
                    kept = new ArrayList<>(columns.subList(0, i));
                }
            } else if (kept != null) {
                kept.add(column);
            }
        }

        return kept == null ? columns : kept;
    }

    /**
     * Keeps the managed columns of a table whose project file is about to be
     * written, and refuses to write one that cannot stand.
     * <p>
     * This is the one place a hidden column really goes missing from a
     * definition. A migration script writes every column it is given and asks
     * these rules nothing; here the project is being told what it declares, and
     * a column the project says it does not manage does not belong in its files.
     * So this is where a column that something still needs would do its damage:
     * a project file is read back by the loader and by everything downstream of
     * it, and one missing column takes an index, a view or a key with it.
     * <p>
     * Since a needed column is no longer hidden in the first place, the check
     * below cannot fire: it asks {@link #neededColumns(ITable)} the same
     * question that already decided the hiding. It is kept as what it now is, an
     * assertion that the two stay the same question. Should a later hand teach
     * one of them about a dependency and not the other, a broken body is refused
     * instead of being written out - which is the one outcome that must never
     * happen quietly.
     * <p>
     * One more thing a body cannot do without is a column at all, in the dialects
     * whose {@code CREATE} states its columns between parentheses that may not be
     * empty, see {@link ITable#canCreateWithoutColumns()}. There a rule that would
     * hide every column of a table hides none of them: the table is written whole,
     * and why is written to the log. The alternative is a file no parser will
     * read, which is the one thing this method exists to never produce.
     *
     * @param columns the columns of one table in their stored order
     * @param <T>     the column type of the dialect
     * @return the columns to write
     * @throws IllegalStateException when the body would not stand without a
     *                               column it is about to leave out
     */
    public <T extends IColumn> List<T> forProjectFile(List<T> columns) {
        List<T> kept = visible(columns);
        if (kept == columns || !(columns.get(0).getParent() instanceof ITable table)) {
            return kept;
        }

        if (kept.isEmpty() && !table.canCreateWithoutColumns()) {
            LOG.info(Messages.ColumnVisibility_log_table_kept_whole.formatted(table.getQualifiedName()));
            return columns;
        }

        Map<String, String> needed = neededColumns(table);
        for (T column : columns) {
            String user = needed.get(column.getName());
            if (user != null && !kept.contains(column)) {
                throw new IllegalStateException(Messages.ColumnVisibility_hidden_column_still_needed
                        .formatted(column.getName(), table.getQualifiedName(), user));
            }
        }
        return kept;
    }

    /**
     * The columns of one state of a table that something in that same state
     * needs, mapped to what needs them.
     * <p>
     * Four sources, because no one of them sees everything. A constraint
     * publishes the columns of a key, but a {@code CHECK} publishes none of the
     * columns of its condition. The references the loader resolved carry those,
     * and the columns an index reads through an expression, and the siblings a
     * generated column reads - but only for the dialects and clauses that are
     * analysed at all. What is left is kept as raw text by its dialect, and is
     * read as raw text here.
     * <p>
     * Every column of the table is asked what it depends on, the hidden ones
     * included. A hidden column that reads another one keeps that other one,
     * which is wider than strictly needed - both would go - and wide is the
     * direction that cannot break a script.
     * <p>
     * The fourth source is the rest of the database, see
     * {@link #addColumnsNamedElsewhere(IDatabase, ITable, Map)}. It is asked
     * last, so a column its own table already accounts for is reported against
     * the object inside that table, which is the shorter answer and the one every
     * caller of this had before there was a fourth source at all.
     */
    private Map<String, String> neededColumns(ITable table) {
        Map<String, String> needed = new LinkedHashMap<>();

        for (IConstraint constraint : table.getConstraints()) {
            for (String name : constraint.getColumns()) {
                needed.putIfAbsent(name, constraint.getQualifiedName());
            }
        }

        IDatabase db = databaseOf(table);
        if (db != null) {
            Stream.concat(Stream.concat(Stream.of(table), table.getChildren()), table.getColumns().stream())
                    .forEach(user -> user.getDependencies().stream()
                            .map(db::getStatement)
                            .filter(IColumn.class::isInstance)
                            .map(IColumn.class::cast)
                            .filter(column -> table.equals(column.getParent()) && !column.equals(user))
                            .forEach(column -> needed.putIfAbsent(column.getName(), user.getQualifiedName())));
        }

        addColumnsNamedInRawClauses(table, needed);
        if (db != null) {
            addColumnsNamedElsewhere(db, table, needed);
        }
        return needed;
    }

    /**
     * Keeps every column of this table that anything anywhere in the database
     * names.
     * <p>
     * A table is not the world. A view selecting a column, a function reading
     * one, an extended statistics object gathered on one, a sequence owned by one
     * - none of them is written inside the {@code CREATE TABLE}, two of them are
     * not even children of the table, and every one of them fails outright
     * against a table created without the column. What is inside the table is
     * only the part of the answer that is cheap to find.
     * <p>
     * The answer is read out of an index built by walking the database once and
     * reading the columns each statement names, see {@link ColumnUsers}. The
     * walk happens only for a table a rule actually names a column of, and never
     * at all for an ignore list that hides no column, see
     * {@link #isHidden(IColumn)}: a comparison that hides nothing pays nothing,
     * not even the walk.
     * <p>
     * Where the index is held is the whole of the cost, because the walk answers
     * for one table and for every table of the database at very nearly the same
     * price. Measured on a project of 13 323 tables and 48 353 statements: one
     * walk takes 34 ms and the whole index 60 ms, so asking per table turned a
     * 40 s export into 482 s and asking per database turns it back. The index
     * is therefore held for one operation by the settings of that operation -
     * not by the model, which is rebuilt one statement at a time while an editor
     * is open, and an index remembered across such a rebuild would answer for a
     * view that has since stopped reading the column.
     * <p>
     * Names are compared rather than resolved. A reference the analysis could not
     * resolve to a column of the model still holds its column here, which is the
     * wider answer and the one that cannot break a script.
     */
    private void addColumnsNamedElsewhere(IDatabase db, ITable table, Map<String, String> needed) {
        IStatement schema = table.getParent();
        if (schema == null) {
            return;
        }

        // columns are not descendants of their table, and no column of one table
        // can name a column of another, so the index built from the descendants
        // of the database holds every statement that could name a column of this
        // table, and holds them in the order a walk would have met them
        users.namingColumnsOf(db, schema.getName(), table.getName())
                .forEach((column, user) -> needed.putIfAbsent(column, user.getQualifiedName()));
    }

    /**
     * Reads the clauses a dialect keeps as the text they were written as - a
     * partition key, a distribution key, a ClickHouse engine, a computed column
     * of MS SQL - and keeps every column whose name occurs in one of them.
     * <p>
     * The match is on the name between word boundaries, so it also fires on a
     * name that merely happens to appear. That costs nothing: the answer is only
     * ever used to keep a column under management, which is what would have
     * happened without any rule at all.
     */
    private static void addColumnsNamedInRawClauses(ITable table, Map<String, String> needed) {
        Collection<String> clauses = table.getClausesNamingColumns();
        if (clauses.isEmpty()) {
            return;
        }

        for (IColumn column : table.getColumns()) {
            String name = column.getName();
            if (needed.containsKey(name)) {
                continue;
            }
            for (String clause : clauses) {
                if (namesColumn(clause, name)) {
                    needed.put(name, clause.strip());
                    break;
                }
            }
        }
    }

    /**
     * Whether a raw clause names an identifier, tested between word boundaries
     * so that {@code s_create_date} is not found inside {@code s_create_date_2}.
     */
    private static boolean namesColumn(String clause, String name) {
        int from = 0;
        while (true) {
            int at = clause.indexOf(name, from);
            if (at < 0) {
                return false;
            }
            int after = at + name.length();
            if (!isIdentifierPart(clause, at - 1) && !isIdentifierPart(clause, after)) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isIdentifierPart(String text, int at) {
        if (at < 0 || at >= text.length()) {
            return false;
        }
        char c = text.charAt(at);
        return c == '_' || Character.isLetterOrDigit(c);
    }

    /**
     * The database a statement belongs to. {@link IStatement#getDatabase()}
     * reaches it in one step from a schema alone, which a table is not.
     */
    private static IDatabase databaseOf(IStatement statement) {
        for (IStatement parent = statement.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof IDatabase db) {
                return db;
            }
        }
        return null;
    }

    /**
     * The name of a column as a {@code QUALIFIED} rule matches it, built exactly
     * the way {@link TreeElement#getQualifiedName()} builds the name of a node:
     * out of the raw names of the containers up to, but not including, the
     * database. The qualified name of the model quotes its identifiers and would
     * not match a rule written for the tree.
     */
    private static String qualifiedNameOf(IColumn column) {
        StringBuilder sb = new StringBuilder(column.getName());
        for (IStatement parent = column.getParent();
             parent != null && !(parent instanceof IDatabase);
             parent = parent.getParent()) {
            sb.insert(0, '.').insert(0, parent.getName());
        }
        return sb.toString();
    }

    /**
     * A column as the rules see it. Always of type {@code COLUMN}: the rules
     * that reach this far were already narrowed to the ones naming that type.
     */
    private record Column(String name, String qualifiedName) implements IgnoreRuleTarget {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getQualifiedName() {
            return qualifiedName;
        }

        @Override
        public DbObjType getType() {
            return DbObjType.COLUMN;
        }
    }
}
