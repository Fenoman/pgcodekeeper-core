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
package org.pgcodekeeper.core.it.difftree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.ch.ChDatabaseProvider;
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.model.difftree.ColumnMark;
import org.pgcodekeeper.core.model.difftree.IgnoredValues;
import org.pgcodekeeper.core.model.difftree.SqlMark;
import org.pgcodekeeper.core.model.difftree.SqlMarkup;
import org.pgcodekeeper.core.model.difftree.SqlMarkup.Marked;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * A reader of a comparison is shown every column and is told which of them a
 * rule names, see {@link ColumnVisibility#markOf} and {@link SqlMarkup}.
 * <p>
 * Hiding a column from the pane was tried and taken back. A column silently
 * missing from a rendering cannot be told from a column silently lost, which is
 * the most expensive mistake there is to make about a table; and a rule that had
 * quietly stopped firing - a column renamed, a typo in the list - would have had
 * nothing left to show it by. So the text stays whole and carries a mark.
 * <p>
 * Nothing here is about writing. The marks are read off models that are asserted
 * to come out of the reading exactly as they went in, and the migration script
 * of the very same comparison is asserted to be what it always was.
 *
 * @see org.pgcodekeeper.core.model.difftree.SqlMarkupTest for what is made of
 * a rendering once the marks are known
 * @see HiddenColumnOutsideUserTest for which columns a rule may hide at all
 */
class ShownColumnsAreMarkedTest {

    private static final List<String> AUDIT_COLUMNS = List.of(
            "s_audit_id_create", "s_audit_id_modif", "s_create_date", "s_creator", "s_modif_date", "s_owner");

    private static final String NOTHING = "CREATE SCHEMA chk;";

    /** The reported table: one real difference among six inert audit columns. */
    private static final String CLOSE_PERIODS = """
            CREATE SCHEMA chk;

            CREATE TABLE chk.sd_close_periods (
                id bigint NOT NULL,
                c_host_name text %s,
                s_audit_id_create bigint,
                s_audit_id_modif bigint,
                s_create_date timestamp without time zone,
                s_creator text,
                s_modif_date timestamp without time zone,
                s_owner text
            );""";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    /**
     * The whole of what was asked for: every column is still on the screen, the
     * six a rule names are marked as leaving, and the one that really differs is
     * marked as nothing at all.
     */
    @Test
    void everyColumnIsShownAndTheNamedOnesAreMarked() throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        LoadedComparison loaded = load(CLOSE_PERIODS.formatted("NOT NULL"), CLOSE_PERIODS.formatted(""), settings);
        Shown shown = shown(loaded, settings);

        for (String column : AUDIT_COLUMNS) {
            assertTrue(shown.sql.contains(column), "a marked column is still shown: " + column + '\n' + shown.sql);
        }
        assertEquals(AUDIT_COLUMNS.size() + 2, table(loaded.newDatabase()).getColumns().size(),
                "and so is every other one");

        assertEquals(List.of(
                "H \ts_audit_id_create bigint,",
                "H \ts_audit_id_modif bigint,",
                "H \ts_create_date timestamp without time zone,",
                "H \ts_creator text,",
                "H \ts_modif_date timestamp without time zone,",
                "H \ts_owner text"),
                shown.marked(), "exactly the six the rules name, and nothing else");
    }

    /**
     * Everything written <em>about</em> a marked column is marked with it. Each
     * of these is a statement of its own that names the column and each follows
     * it out of a project file, so one left unmarked beside a marked declaration
     * would be a column half accounted for.
     */
    @Test
    void everythingWrittenAboutAMarkedColumnIsMarkedWithIt() throws IOException, InterruptedException {
        String sql = CLOSE_PERIODS.formatted("NOT NULL") + """


                ALTER TABLE chk.sd_close_periods
                    ADD CONSTRAINT nn_s_create_date NOT NULL s_create_date NOT VALID;

                COMMENT ON COLUMN chk.sd_close_periods.s_create_date IS 'when the row appeared';

                ALTER TABLE chk.sd_close_periods ALTER COLUMN s_create_date SET STATISTICS 100;

                ALTER TABLE chk.sd_close_periods ALTER COLUMN s_creator SET STORAGE EXTERNAL;

                CREATE ROLE reader;

                GRANT SELECT(s_create_date) ON TABLE chk.sd_close_periods TO reader;""";

        CoreSettings settings = auditHidden();
        Shown shown = shown(load(NOTHING, sql, settings), settings);

        for (String statement : List.of("COMMENT ON COLUMN", "SET STATISTICS", "SET STORAGE", "GRANT SELECT",
                "nn_s_create_date")) {
            assertTrue(shown.sql.contains(statement),
                    "the fixture must state it, or nothing is being proved: " + statement + '\n' + shown.sql);
            assertTrue(shown.marked().stream().anyMatch(line -> line.contains(statement)),
                    "and it must be marked with the column it names: " + statement + '\n' + shown.marked());
        }
    }

    /**
     * And it stays marked when the setting of the day wraps it in
     * {@code DO $$ ... $$} so that it can be run twice.
     * <p>
     * A constraint that holds the {@code NOT NULL} of a column is a part of that
     * column and is marked with it, see {@link SqlMarkup}; the wrapper is a
     * preference of the operator, says nothing about what migrates, and must
     * therefore change no mark. A key or a check is an object of its own and is
     * left alone inside the wrapper exactly as it is outside one, which is what
     * the second half of this asserts: reading into the wrapper may not start
     * painting whole keys over.
     */
    @Test
    void aColumnWrappedInADoBlockIsMarkedJustTheSame() throws IOException, InterruptedException {
        String sql = CLOSE_PERIODS.formatted("NOT NULL") + """


                ALTER TABLE chk.sd_close_periods
                    ADD CONSTRAINT nn_s_create_date NOT NULL s_create_date NOT VALID;

                ALTER TABLE chk.sd_close_periods
                    ADD CONSTRAINT pk_sd_close_periods PRIMARY KEY (s_creator);""";

        CoreSettings settings = auditHidden();
        settings.setGenerateExists(true);
        settings.setGenerateExistDoBlock(true);
        Shown shown = shown(load(NOTHING, sql, settings), settings);

        assertTrue(shown.sql.contains("DO $$"), "the fixture must wrap them:\n" + shown.sql);
        assertTrue(shown.marked().stream().anyMatch(line -> line.contains("nn_s_create_date")),
                "the NOT NULL of a marked column is marked through the wrapper:\n" + shown.marked());
        assertFalse(shown.marked().stream().anyMatch(line -> line.contains("pk_sd_close_periods")),
                "while a key stays an object of its own, wrapper or no wrapper:\n" + shown.marked());
    }

    /**
     * A column something needs carries the other mark, and what needs it is
     * known by name: that is the answer to the question the first mark would
     * otherwise leave open - why this audit column and not the one below it.
     */
    @Test
    void aColumnSomethingNeedsIsMarkedApartAndTheReasonIsKnown() throws IOException, InterruptedException {
        String sql = CLOSE_PERIODS.formatted("NOT NULL") + """


                CREATE VIEW chk.sd_close_periods_v AS
                    SELECT id, s_creator FROM chk.sd_close_periods;""";

        CoreSettings settings = auditHidden();
        LoadedComparison loaded = load(NOTHING, sql, settings);
        ITable table = table(loaded.newDatabase());
        ColumnVisibility managed = ColumnVisibility.of(settings).forPair(null, table);

        assertEquals(ColumnMark.PINNED, managed.marksIn(table).get("s_creator"), "a column a view reads stays");
        assertEquals(ColumnMark.HIDDEN, managed.marksIn(table).get("s_owner"), "and the one nothing reads does not");
        assertEquals(Map.of("s_creator", "chk.sd_close_periods_v"), managed.pinnedColumns(table),
                "and what keeps it is known, so a reader can be told");

        Shown shown = shown(loaded, settings);
        assertTrue(shown.marked().contains("P \ts_creator text,"), shown.marked().toString());
        assertTrue(shown.marked().contains("H \ts_owner text"), shown.marked().toString());
    }

    /**
     * Both states of a table are marked alike, because the rules are bound to
     * the pair, see {@link ColumnVisibility#forPair(ITable, ITable)}. Here the
     * index that keeps the column exists in one state only: were each state
     * asked about itself, the same column would be marked as leaving on one side
     * of the screen and as staying on the other, and the mark would be saying
     * something about the migration rather than about the column.
     */
    @Test
    void bothStatesAreMarkedAlike() throws IOException, InterruptedException {
        String withIndex = CLOSE_PERIODS.formatted("NOT NULL") + """


                CREATE INDEX sd_close_periods_created_idx ON chk.sd_close_periods (s_create_date);""";

        CoreSettings settings = auditHidden();
        LoadedComparison loaded = load(withIndex, CLOSE_PERIODS.formatted(""), settings);
        ITable inDatabase = table(loaded.oldDatabase());
        ITable inProject = table(loaded.newDatabase());
        ColumnVisibility bound = ColumnVisibility.of(settings).forPair(inDatabase, inProject);

        assertEquals(ColumnMark.PINNED, bound.marksIn(inDatabase).get("s_create_date"),
                "the state holding the index keeps the column it needs");
        assertEquals(ColumnMark.PINNED, bound.marksIn(inProject).get("s_create_date"),
                "so does the state that does not hold the index, or the two disagree");

        assertEquals(ColumnMark.HIDDEN,
                ColumnVisibility.of(settings).forPair(null, inProject).marksIn(inProject).get("s_create_date"),
                "asked about itself alone that state would mark it as leaving, which is why it is not asked");
    }

    /**
     * The models come out of a reading exactly as they went in. They are the
     * models a migration script is generated from and a project file is written
     * from, and a rendering shown to somebody is not allowed to have touched
     * them.
     */
    @Test
    void theMarkingLeavesTheModelAsItWasLoaded() throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        LoadedComparison loaded = load(NOTHING, CLOSE_PERIODS.formatted("NOT NULL"), settings);
        ITable table = table(loaded.newDatabase());
        String before = table.getSQL(false, settings);

        Shown shown = shown(loaded, settings);
        assertFalse(shown.marked().isEmpty(), "the fixture must have something to mark");

        assertEquals(before, table.getSQL(false, settings), "the model must render exactly as it did before");
        assertEquals(AUDIT_COLUMNS.size() + 2, table.getColumns().size(), "and must keep every column");
    }

    /**
     * The migration script of the very same comparison is what it always was.
     * Marking a column is not an ignore rule of its own and adds nothing to what
     * the rules already decide about scripts.
     */
    @Test
    void theScriptIsUnchangedByWhatWasMarked() throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        LoadedComparison loaded = load(CLOSE_PERIODS.formatted("NOT NULL"), CLOSE_PERIODS.formatted(""), settings);

        String before = PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings);
        shown(loaded, settings);
        String after = PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings);

        assertEquals(before, after, "showing a comparison may not change what it migrates");
        assertTrue(before.contains("c_host_name"), "the fixture must migrate the one real difference:\n" + before);
        assertFalse(before.contains("s_creator"), "and no marked column, as it never did:\n" + before);
    }

    /**
     * An ignore list that can hide no column marks nothing and pays nothing at
     * all - not a walk of its database, not a reading of a rendering - which is
     * what very nearly every project there is will be doing.
     */
    @Test
    void withoutAColumnRuleNothingIsMarked() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        LoadedComparison loaded = load(NOTHING, CLOSE_PERIODS.formatted("NOT NULL"), settings);
        ITable table = table(loaded.newDatabase());
        ColumnVisibility managed = ColumnVisibility.of(settings);

        assertFalse(managed.hidesAnything(), "no rule here can hide a column");
        assertEquals(Map.of(), managed.marksIn(table), "so there is nothing to mark");
        assertEquals(List.of(), SqlMarkup.rangesIn(table.getSQL(false, settings), managed.marksIn(table), IgnoredValues.NONE),
                "and nothing to read a rendering for");
    }

    /** A table of Microsoft SQL keeps its columns in a list of its own. */
    @Test
    void aMicrosoftTableIsMarkedToo() throws IOException, InterruptedException {
        assertDialectMarksTheColumn(new MsDatabaseProvider(), """
                CREATE SCHEMA [dbo]
                GO
                CREATE TABLE [dbo].[doc](
                	[id] [bigint] NOT NULL,
                	[title] [nvarchar](50) NULL,
                	[s_creator] [nvarchar](50) NULL
                ) ON [PRIMARY]
                GO""", new ObjectReference("dbo", "doc", DbObjType.TABLE),
                "H \t[s_creator] [nvarchar](50) NULL");
    }

    /** And so does a table of ClickHouse. */
    @Test
    void aClickHouseTableIsMarkedToo() throws IOException, InterruptedException {
        assertDialectMarksTheColumn(new ChDatabaseProvider(), """
                CREATE DATABASE default;

                CREATE TABLE default.doc
                (
                	`id` Int64,
                	`title` String,
                	`s_creator` String
                )
                ENGINE = Log;""", new ObjectReference("default", "doc", DbObjType.TABLE),
                "H \t`s_creator` String");
    }

    private void assertDialectMarksTheColumn(IDatabaseProvider dialect, String sql, ObjectReference doc,
            String expected) throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        IDatabase db = dialect.getDumpLoader(source(sql), "database", settings).loadAndAnalyze();
        TestUtils.assertErrors(settings.getErrors());
        ITable table = (ITable) db.getStatement(doc);
        assertNotNull(table, "fixture must load");

        String before = table.getSQL(false, settings);
        assertEquals(List.of(expected), markedIn(before, ColumnVisibility.of(settings).forPair(null, table), table));
        assertEquals(before, table.getSQL(false, settings), "the model must render exactly as it did before");
    }

    /** One state of a table, as a reader of the comparison is shown it. */
    private record Shown(String sql, List<String> marked) {
    }

    private static Shown shown(LoadedComparison loaded, CoreSettings settings) {
        ITable inDatabase = tableOrNone(loaded.oldDatabase());
        ITable inProject = table(loaded.newDatabase());
        ColumnVisibility bound = ColumnVisibility.of(settings).forPair(inDatabase, inProject);
        String sql = inProject.getSQL(false, settings);
        return new Shown(sql, markedIn(sql, bound, inProject));
    }

    /** The marked stretches of a rendering, each as the letter of its mark. */
    private static List<String> markedIn(String sql, ColumnVisibility bound, ITable table) {
        List<String> lines = new ArrayList<>();
        for (Marked range : SqlMarkup.rangesIn(sql, bound.marksIn(table), IgnoredValues.NONE)) {
            lines.add((range.mark() == SqlMark.COLUMN_KEPT ? 'P' : 'H') + " "
                    + sql.substring(range.offset(), range.offset() + range.length()));
        }
        return lines;
    }

    private static ITable table(IDatabase db) {
        ITable table = tableOrNone(db);
        assertNotNull(table, "fixture must offer the table");
        return table;
    }

    /** The state of the table that side holds, {@code null} when it holds none. */
    private static ITable tableOrNone(IDatabase db) {
        return db == null ? null
                : (ITable) db.getStatement(new ObjectReference("chk", "sd_close_periods", DbObjType.TABLE));
    }

    private static CoreSettings auditHidden() {
        CoreSettings settings = new CoreSettings();
        AUDIT_COLUMNS.forEach(column -> settings.getIgnoreList()
                .add(new IgnoredObject(column, null, false, false, false, false, EnumSet.of(DbObjType.COLUMN))));
        return settings;
    }

    private LoadedComparison load(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        settings.clearErrors();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> provider.getDumpLoader(source(oldSql), "old", sideSettings),
                        sideSettings -> provider.getDumpLoader(source(newSql), "new", sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        assertNotNull(loaded.newDatabase(), "fixture must load");
        return loaded;
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }
}
