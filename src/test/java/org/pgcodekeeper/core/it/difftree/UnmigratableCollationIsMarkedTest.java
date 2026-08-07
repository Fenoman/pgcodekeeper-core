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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.IgnoredValues;
import org.pgcodekeeper.core.model.difftree.SqlMark;
import org.pgcodekeeper.core.model.difftree.SqlMarkup;
import org.pgcodekeeper.core.model.difftree.SqlMarkup.Marked;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * A reader of a comparison is told which lines of it state a difference that no
 * migration script can express, so that it is not mistaken for one the migration
 * is about to carry.
 * <p>
 * The difference is a column collation the state a migration produces does not
 * name, see {@link ColumnCollationComparisonTest} for why the comparison drops
 * it. What makes it worth a mark of its own is not that it is dropped - the
 * settings drop a cache and a statistics target too, see
 * {@link IgnoredValuesAreMarkedTest} - but that it is dropped <em>for good</em>.
 * A cache converges the moment the operator turns the setting off; this one
 * converges never, by this migration or any after it, and the two sides go on
 * showing plainly different text forever with nothing on the screen to say why.
 * <p>
 * <b>What is not marked matters more than what is.</b> The comparison hands out
 * this verdict only for a column whose <em>whole</em> difference is that
 * collation, so half the cases below are about a line the migration really does
 * write and must therefore stay unmarked - the collation the target does name,
 * the generated column that is recreated for one, the column that differs in
 * something else besides. Each of them reads the script of its own comparison
 * back and asks it.
 *
 * @see #aTableWhoseOnlyDifferenceIsTheCollationNeverReachesAReader for the one
 * case this marking cannot reach, and why
 */
class UnmigratableCollationIsMarkedTest {

    private static final ObjectReference TABLE = new ObjectReference("chk", "doc", DbObjType.TABLE);
    private static final ObjectReference TITLE = new ObjectReference("chk", "doc", "title", DbObjType.COLUMN);

    /**
     * A table whose collated column is accompanied by a difference of its own,
     * which is what keeps the table in the comparison at all.
     */
    private static final String DATABASE = """
            CREATE SCHEMA chk;

            CREATE TABLE chk.doc (
                id bigint,
                title text COLLATE pg_catalog."ru_RU" NOT NULL,
                payload text
            );

            COMMENT ON COLUMN chk.doc.title IS 'the title';""";

    private static final String PROJECT = """
            CREATE SCHEMA chk;

            CREATE TABLE chk.doc (
                id integer,
                title text NOT NULL,
                payload text
            );

            COMMENT ON COLUMN chk.doc.title IS 'the title';""";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    /**
     * The whole of what was asked for: the declaration of the column is marked
     * on both sides of the screen, and the migration of that very comparison
     * carries nothing about it while carrying what really differs.
     */
    @Test
    void theCollationNoScriptCanExpressIsMarkedOnBothSides() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        LoadedComparison loaded = load(DATABASE, PROJECT, settings);

        assertEquals(List.of("U \ttitle text COLLATE pg_catalog.\"ru_RU\" NOT NULL,"),
                markedIn(loaded, TABLE, settings, false), "the state the migration starts from");
        assertEquals(List.of("U \ttitle text NOT NULL,"),
                markedIn(loaded, TABLE, settings, true), "and the state it produces");

        String script = script(loaded, settings);
        assertTrue(script.contains("ALTER COLUMN id TYPE integer"),
                "the fixture must migrate what really differs:\n" + script);
        assertFalse(script.contains("title"), "and must say nothing of the marked column:\n" + script);
    }

    /**
     * The mark is not the one the settings produce. Both stand beside a line no
     * script will carry and there the likeness ends: one is a preference that
     * can be turned off tomorrow, the other is a difference that outlives every
     * migration, and a reader given one word for both has been told the wrong
     * thing.
     */
    @Test
    void theMarkIsNotTheOneAnOverlookedValueWears() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        settings.setIgnoreSequenceCache(true);
        settings.setIgnoreColumnStatistics(true);

        LoadedComparison loaded = load(DATABASE + """


                ALTER TABLE chk.doc ALTER COLUMN payload SET STATISTICS 500;""",
                PROJECT + """


                ALTER TABLE chk.doc ALTER COLUMN payload SET STATISTICS 100;""", settings);

        assertEquals(List.of(
                "U \ttitle text COLLATE pg_catalog.\"ru_RU\" NOT NULL,",
                "V ALTER TABLE ONLY chk.doc ALTER COLUMN payload SET STATISTICS 500;"),
                markedIn(loaded, TABLE, settings, false),
                "the two reasons keep their own mark on the same rendering");
    }

    /**
     * A collation the state the migration produces does name is written by that
     * migration, so it is a difference like any other and marking it would be
     * the very lie this exists to prevent.
     */
    @Test
    void aCollationTheMigrationDoesExpressIsNotMarked() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        LoadedComparison loaded = load(PROJECT, DATABASE, settings);

        assertEquals(List.of(), markedIn(loaded, TABLE, settings, false));
        assertEquals(List.of(), markedIn(loaded, TABLE, settings, true));
        assertTrue(script(loaded, settings).contains("COLLATE pg_catalog.\"ru_RU\""),
                "the migration really does write it:\n" + script(loaded, settings));
    }

    /**
     * A column that differs in something else besides is migrated, and that
     * migration writes the very line a mark would have told the reader to stop
     * looking at. The collation still does not travel - it never does - but the
     * line is not the place to say so.
     */
    @Test
    void aColumnThatDiffersInSomethingElseIsNotMarked() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        LoadedComparison loaded = load(DATABASE, PROJECT.replace("title text NOT NULL", "title text"), settings);

        assertEquals(List.of(), markedIn(loaded, TABLE, settings, false), "the line is about to be written");
        assertTrue(script(loaded, settings).contains("ALTER COLUMN title DROP NOT NULL"),
                "and here is the migration writing it:\n" + script(loaded, settings));
    }

    /**
     * A generated column is recreated on any collation change, so there the
     * difference is migratable and must not be marked.
     */
    @Test
    void aGeneratedColumnIsNotMarked() throws IOException, InterruptedException {
        String generated = """
                CREATE SCHEMA chk;

                CREATE TABLE chk.doc (
                    payload text,
                    title text %sGENERATED ALWAYS AS (payload) STORED
                );""";

        CoreSettings settings = new CoreSettings();
        LoadedComparison loaded = load(generated.formatted("COLLATE pg_catalog.\"ru_RU\" "),
                generated.formatted(""), settings);

        assertEquals(List.of(), markedIn(loaded, TABLE, settings, false));
        assertTrue(script(loaded, settings).contains("DROP COLUMN title"),
                "the migration recreates it, collation and all:\n" + script(loaded, settings));
    }

    /**
     * A column one side does not hold is created with the collation it is
     * declared with, which is a collation the script does write, so nothing
     * about it is passed over and nothing about it is marked.
     */
    @Test
    void aColumnOnOneSideOnlyIsNotMarked() throws IOException, InterruptedException {
        String without = """
                CREATE SCHEMA chk;

                CREATE TABLE chk.doc (
                    payload text
                );""";

        CoreSettings settings = new CoreSettings();
        LoadedComparison loaded = load(without, DATABASE, settings);

        assertEquals(List.of(), markedIn(loaded, TABLE, settings, true));
        assertTrue(script(loaded, settings).contains("COLLATE pg_catalog.\"ru_RU\""),
                "the added column carries its collation into the migration:\n" + script(loaded, settings));
    }

    /**
     * A column shown on its own is marked exactly as it is inside its table: the
     * pane shows either, and the same answer has to come out of both. The
     * statement that declares it is marked whole, and the comment that travels
     * beside it - a difference the migration would carry perfectly well - is
     * left alone.
     */
    @Test
    void aColumnShownOnItsOwnIsMarkedToo() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        LoadedComparison loaded = load(DATABASE, PROJECT, settings);

        assertEquals(List.of("U ALTER TABLE chk.doc\n\tADD COLUMN title text COLLATE pg_catalog.\"ru_RU\" NOT NULL;"),
                markedIn(loaded, TITLE, settings, false));
        assertEquals(List.of("U ALTER TABLE chk.doc\n\tADD COLUMN title text NOT NULL;"),
                markedIn(loaded, TITLE, settings, true));
    }

    /**
     * The one case this marking cannot reach, stated here so that nobody has to
     * rediscover it.
     * <p>
     * A table whose <em>only</em> difference is such a collation compares equal,
     * which is the whole point of the relaxation, and an object that compares
     * equal never enters the difference tree - so there is no row to select and
     * no rendering to mark. The marking is display only and a display cannot put
     * an object back into a tree; reaching this case means changing what the
     * comparison reports, which is a decision about the comparison and not about
     * the pane.
     * <p>
     * What is left is the case above, where the table is in the tree for another
     * reason and the collated column sits in it saying nothing. That is the case
     * an operator actually meets on a real schema, and it is the one that used
     * to be silent.
     */
    @Test
    void aTableWhoseOnlyDifferenceIsTheCollationNeverReachesAReader() throws IOException, InterruptedException {
        String collated = """
                CREATE SCHEMA chk;

                CREATE TABLE chk.doc (
                    title text COLLATE pg_catalog."ru_RU"
                );""";
        String plain = """
                CREATE SCHEMA chk;

                CREATE TABLE chk.doc (
                    title text
                );""";

        CoreSettings settings = new CoreSettings();
        LoadedComparison loaded = load(collated, plain, settings);
        TreeElement root = DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());

        assertEquals(List.of(), tableNames(root), "the table compares equal and is not in the tree");
        assertFalse(IgnoredValues.of(settings, loaded.oldDatabase().getStatement(TABLE),
                loaded.newDatabase().getStatement(TABLE)).isEmpty(),
                "the verdict is there for whoever does show the object, it is the tree that has no row");
    }

    /**
     * Microsoft SQL writes the collation of either side, so no difference in one
     * is ever passed over there and nothing about it is marked.
     */
    @Test
    void aDialectThatMigratesEveryCollationMarksNothing() throws IOException, InterruptedException {
        String ms = """
                CREATE SCHEMA [dbo]
                GO
                CREATE TABLE [dbo].[doc] (
                	[title] [nvarchar](100) %sNOT NULL)
                GO""";

        CoreSettings settings = new CoreSettings();
        LoadedComparison loaded = load(new MsDatabaseProvider(),
                ms.formatted("COLLATE Cyrillic_General_CI_AS "), ms.formatted(""), settings);

        assertEquals(List.of(), markedIn(loaded, new ObjectReference("dbo", "doc", DbObjType.TABLE),
                settings, false));
    }

    /**
     * The models come out of the reading exactly as they went in, and so does the
     * migration script. A rendering shown to somebody is not allowed to have
     * touched either.
     */
    @Test
    void theMarkingChangesNothingItReads() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        LoadedComparison loaded = load(DATABASE, PROJECT, settings);
        IStatement table = loaded.newDatabase().getStatement(TABLE);
        String before = table.getSQL(false, settings);
        String script = script(loaded, settings);
        List<String> tree = tableNames(DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase()));

        assertFalse(markedIn(loaded, TABLE, settings, false).isEmpty(), "the fixture must have something to mark");

        assertEquals(before, table.getSQL(false, settings), "the model must render exactly as it did before");
        assertEquals(script, script(loaded, settings), "and must migrate exactly as it did before");
        assertEquals(tree, tableNames(DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase())),
                "and must be compared exactly as it was before");
    }

    /**
     * The marked lines of one state of an object, each as the letter of its mark
     * and the text it covers.
     *
     * @param target whether to read the state the migration produces rather than
     *               the one it starts from
     */
    private static List<String> markedIn(LoadedComparison loaded, ObjectReference ref, CoreSettings settings,
            boolean target) {
        IStatement from = loaded.oldDatabase() == null ? null : loaded.oldDatabase().getStatement(ref);
        IStatement to = loaded.newDatabase().getStatement(ref);
        IStatement shown = target ? to : from;
        assertNotNull(shown, "fixture must offer the object: " + ref);

        String sql = shown.getSQL(false, settings);
        List<String> lines = new ArrayList<>();
        for (Marked range : SqlMarkup.rangesIn(sql, Map.of(), IgnoredValues.of(settings, from, to))) {
            lines.add(letterOf(range.mark()) + " "
                    + sql.substring(range.offset(), range.offset() + range.length()));
        }
        return lines;
    }

    private static char letterOf(SqlMark mark) {
        return switch (mark) {
            case VALUE_UNMIGRATABLE -> 'U';
            case VALUE_IGNORED -> 'V';
            case COLUMN_LEAVING -> 'H';
            case COLUMN_KEPT -> 'P';
        };
    }

    private static List<String> tableNames(TreeElement root) {
        List<String> names = new ArrayList<>();
        collectTables(root, names);
        return names;
    }

    private static void collectTables(TreeElement el, List<String> names) {
        if (el.getType() == DbObjType.TABLE) {
            names.add(el.getName());
        }
        for (TreeElement child : el.getChildren()) {
            collectTables(child, names);
        }
    }

    private String script(LoadedComparison loaded, CoreSettings settings) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings);
    }

    private LoadedComparison load(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        return load(provider, oldSql, newSql, settings);
    }

    private static LoadedComparison load(IDatabaseProvider dialect, String oldSql, String newSql,
            CoreSettings settings) throws IOException, InterruptedException {
        settings.clearErrors();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> dialect.getDumpLoader(source(oldSql), "old", sideSettings),
                        sideSettings -> dialect.getDumpLoader(source(newSql), "new", sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        assertNotNull(loaded.newDatabase(), "fixture must load");
        return loaded;
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }
}
