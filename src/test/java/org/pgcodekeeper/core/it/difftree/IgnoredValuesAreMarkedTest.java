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
import org.pgcodekeeper.core.model.difftree.IgnoredValues;
import org.pgcodekeeper.core.model.difftree.SqlMark;
import org.pgcodekeeper.core.model.difftree.SqlMarkup;
import org.pgcodekeeper.core.model.difftree.SqlMarkup.Marked;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * A reader of a comparison is told which lines of it state a value the settings
 * tell the comparison to overlook, see {@link IgnoredValues}.
 * <p>
 * The complaint this answers is a plain one: with {@code --ignore-sequence-cache}
 * or {@code --ignore-column-statistics} on, the two sides of the pane still show
 * their own cache and their own statistics target, they still differ, and
 * nothing on the screen says that no migration is going to touch them. Every
 * test here is about the one thing that makes such a mark worth having - that it
 * is true. A line the migration is about to carry must not be marked, so the
 * script of the very same comparison is read back and asked.
 * <p>
 * Nothing here is about writing. The marks are read off models that come out of
 * the reading exactly as they went in, and the scripts asserted below are the
 * scripts those settings always produced.
 *
 * @see org.pgcodekeeper.core.model.difftree.SqlMarkupTest for what is made of a
 * rendering once the values are known
 * @see ShownColumnsAreMarkedTest for the other half of what the pane marks
 */
class IgnoredValuesAreMarkedTest {

    private static final ObjectReference SEQUENCE = new ObjectReference("chk", "s_id", DbObjType.SEQUENCE);
    private static final ObjectReference TABLE = new ObjectReference("chk", "doc", DbObjType.TABLE);
    private static final ObjectReference TITLE = new ObjectReference("chk", "doc", "title", DbObjType.COLUMN);

    /**
     * Both sides hold every object; the cache, the identity cache and the
     * statistics target differ, and so does one thing per object that the
     * migration really does carry.
     */
    private static final String BOTH_SIDES = """
            CREATE SCHEMA chk;

            CREATE SEQUENCE chk.s_id
                INCREMENT BY %s
                CACHE %s;

            CREATE TABLE chk.doc (
                id bigint GENERATED ALWAYS AS IDENTITY (SEQUENCE NAME chk.doc_id_seq CACHE %s),
                title text,
                payload text
            );

            ALTER TABLE chk.doc ALTER COLUMN title SET STATISTICS %s;

            ALTER TABLE chk.doc ALTER COLUMN title SET STORAGE %s;""";

    private static final String NOTHING = "CREATE SCHEMA chk;";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    /**
     * The whole of what was asked for: the cache of a sequence, the cache of an
     * identity and the statistics target of a column are marked, and the
     * migration of that very comparison carries none of the three while carrying
     * everything else that differs.
     */
    @Test
    void theOverlookedValuesAreMarkedAndTheScriptCarriesNoneOfThem() throws IOException, InterruptedException {
        CoreSettings settings = overlooking(true, true);
        LoadedComparison loaded = bothSides(settings);

        assertEquals(List.of("\tCACHE 10;"), markedIn(loaded, SEQUENCE, settings), "the cache of a sequence");
        assertEquals(List.of(
                "\tCACHE 7",
                "ALTER TABLE ONLY chk.doc ALTER COLUMN title SET STATISTICS 100;"),
                markedIn(loaded, TABLE, settings), "the cache of an identity and a statistics target");

        String script = script(loaded, settings);
        assertTrue(script.contains("INCREMENT BY") && script.contains("SET STORAGE"),
                "the fixture must migrate what really differs:\n" + script);
        assertFalse(script.contains("CACHE"), "and must carry no cache, or the mark is a lie:\n" + script);
        assertFalse(script.contains("STATISTICS"), "and no statistics target either:\n" + script);
    }

    /**
     * A setting that is off marks nothing, because a difference that is not
     * overlooked is a difference the migration is about to carry. The same
     * fixture, the same models, the same lines - and the script now names both.
     */
    @Test
    void aSettingThatIsOffMarksNothing() throws IOException, InterruptedException {
        CoreSettings settings = overlooking(false, false);
        LoadedComparison loaded = bothSides(settings);

        assertEquals(List.of(), markedIn(loaded, SEQUENCE, settings), "nothing is overlooked about the sequence");
        assertEquals(List.of(), markedIn(loaded, TABLE, settings), "nor about the table");

        String script = script(loaded, settings);
        assertTrue(script.contains("CACHE"), "the migration carries the cache now:\n" + script);
        assertTrue(script.contains("SET STATISTICS"), "and the statistics target:\n" + script);
    }

    /** Each setting answers for itself, and neither answers for the other. */
    @Test
    void eachSettingMarksItsOwnValueAlone() throws IOException, InterruptedException {
        CoreSettings cacheOnly = overlooking(true, false);
        assertEquals(List.of("\tCACHE 7"), markedIn(bothSides(cacheOnly), TABLE, cacheOnly),
                "the cache alone, with the statistics target left to the migration");

        CoreSettings statisticsOnly = overlooking(false, true);
        assertEquals(List.of("ALTER TABLE ONLY chk.doc ALTER COLUMN title SET STATISTICS 100;"),
                markedIn(bothSides(statisticsOnly), TABLE, statisticsOnly), "and the other way round");
    }

    /**
     * A sequence one side does not hold is created whole by the migration, cache
     * and all, so its cache is not overlooked and is not marked. Both settings
     * are about a <em>difference</em>, and a difference needs two states.
     */
    @Test
    void aSequenceOnOneSideOnlyIsNotMarked() throws IOException, InterruptedException {
        CoreSettings settings = overlooking(true, true);
        LoadedComparison loaded = load(NOTHING, BOTH_SIDES.formatted("1", "10", "7", "100", "EXTERNAL"), settings);

        assertEquals(List.of(), markedIn(loaded, SEQUENCE, settings), "nothing about a sequence being created");
        assertTrue(script(loaded, settings).contains("CACHE 10"),
                "and the migration really does write its cache:\n" + script(loaded, settings));
    }

    /**
     * The same for a column: one the migration is about to add is added with the
     * statistics target the project states, so that target is not overlooked and
     * is not marked - while every column both sides hold beside it still is.
     */
    @Test
    void aColumnOnOneSideOnlyIsNotMarked() throws IOException, InterruptedException {
        String withoutTitle = """
                CREATE SCHEMA chk;

                CREATE TABLE chk.doc (
                    payload text
                );""";
        String withTitle = """
                CREATE SCHEMA chk;

                CREATE TABLE chk.doc (
                    title text,
                    payload text
                );

                ALTER TABLE chk.doc ALTER COLUMN title SET STATISTICS 100;

                ALTER TABLE chk.doc ALTER COLUMN payload SET STATISTICS 500;""";

        CoreSettings settings = overlooking(true, true);
        LoadedComparison loaded = load(withoutTitle, withTitle, settings);

        assertEquals(List.of("ALTER TABLE ONLY chk.doc ALTER COLUMN payload SET STATISTICS 500;"),
                markedIn(loaded, TABLE, settings), "the column both sides hold, and only that one");
        assertTrue(script(loaded, settings).contains("ALTER COLUMN title SET STATISTICS 100"),
                "the added column carries its target into the migration:\n" + script(loaded, settings));
    }

    /**
     * A column that gains an identity is asking for the whole
     * {@code ADD GENERATED} clause to be written, cache included, so that cache
     * is not overlooked either. Two identities compared against each other are
     * the only case where the setting applies.
     */
    @Test
    void aColumnThatGainsAnIdentityIsNotMarked() throws IOException, InterruptedException {
        String plain = """
                CREATE SCHEMA chk;

                CREATE TABLE chk.doc (
                    id bigint,
                    title text
                );""";
        String identity = """
                CREATE SCHEMA chk;

                CREATE TABLE chk.doc (
                    id bigint GENERATED ALWAYS AS IDENTITY (SEQUENCE NAME chk.doc_id_seq CACHE 7),
                    title text
                );""";

        CoreSettings settings = overlooking(true, true);
        LoadedComparison loaded = load(plain, identity, settings);

        assertEquals(List.of(), markedIn(loaded, TABLE, settings), "nothing about an identity being added");
        assertTrue(script(loaded, settings).contains("CACHE 7"),
                "and the migration really does write its cache:\n" + script(loaded, settings));
    }

    /**
     * The models come out of the reading exactly as they went in, and so does the
     * migration script. A rendering shown to somebody is not allowed to have
     * touched either.
     */
    @Test
    void theMarkingChangesNothingItReads() throws IOException, InterruptedException {
        CoreSettings settings = overlooking(true, true);
        LoadedComparison loaded = bothSides(settings);
        IStatement table = loaded.newDatabase().getStatement(TABLE);
        String before = table.getSQL(false, settings);
        String script = script(loaded, settings);

        assertFalse(markedIn(loaded, TABLE, settings).isEmpty(), "the fixture must have something to mark");

        assertEquals(before, table.getSQL(false, settings), "the model must render exactly as it did before");
        assertEquals(script, script(loaded, settings), "and must migrate exactly as it did before");
    }

    /**
     * A comparison whose settings overlook nothing pays nothing: no reading of a
     * rendering, no question asked per object. That is very nearly every project
     * there is.
     */
    @Test
    void withoutTheSettingsNothingIsAsked() throws IOException, InterruptedException {
        CoreSettings settings = overlooking(false, false);
        LoadedComparison loaded = bothSides(settings);

        assertTrue(IgnoredValues.of(settings, loaded.oldDatabase().getStatement(TABLE),
                loaded.newDatabase().getStatement(TABLE)).isEmpty(), "there is nothing to look for");
        assertEquals(IgnoredValues.NONE, IgnoredValues.of(settings, null, null), "and nothing to look in");
    }

    /**
     * A column shown on its own is marked exactly as it is inside its table: the
     * pane shows either, and the same answer has to come out of both.
     */
    @Test
    void aColumnShownOnItsOwnIsMarkedToo() throws IOException, InterruptedException {
        CoreSettings settings = overlooking(true, true);
        LoadedComparison loaded = bothSides(settings);

        assertEquals(List.of("ALTER TABLE ONLY chk.doc\n\tALTER COLUMN title SET STATISTICS 100;"),
                markedIn(loaded, TITLE, settings));
    }

    /** Microsoft SQL writes the cache of a sequence of its own, and it is marked. */
    @Test
    void aMicrosoftSequenceIsMarkedToo() throws IOException, InterruptedException {
        String ms = """
                CREATE SCHEMA [dbo]
                GO
                CREATE SEQUENCE [dbo].[s_id]
                	AS [bigint]
                	START WITH 1
                	INCREMENT BY %s
                	CACHE %s
                GO""";

        CoreSettings settings = overlooking(true, true);
        assertEquals(List.of("\tCACHE 20"), markedIn(
                load(new MsDatabaseProvider(), ms.formatted("2", "30"), ms.formatted("1", "20"), settings),
                new ObjectReference("dbo", "s_id", DbObjType.SEQUENCE), settings));
    }

    /**
     * A setting that wraps a statement in {@code DO $$ ... $$} so that it can be
     * run twice does not put the value inside it out of reach.
     * <p>
     * This is the case that was reported. The cache of an identity is written
     * inside the definition of its column and that definition is wrapped, see
     * {@code PgAbstractTable.writeColumn}, so the whole clause stood inside what
     * reads as a literal. The columns of the same table, declared in the body of
     * the {@code CREATE}, were marked as always - which is what made the miss
     * look like the identity branch failing rather than the reading of the text.
     * Whether the wrapper is written is a preference of the operator and says
     * nothing about what migrates, so the marking may not depend on it.
     */
    @Test
    void aValueWrappedInADoBlockIsMarkedJustTheSame() throws IOException, InterruptedException {
        CoreSettings settings = overlooking(true, true);
        settings.setGenerateExists(true);
        settings.setGenerateExistDoBlock(true);
        LoadedComparison loaded = bothSides(settings);

        String sql = loaded.newDatabase().getStatement(TABLE).getSQL(false, settings);
        assertTrue(sql.contains("DO $$"), "the fixture must wrap the identity:\n" + sql);
        assertEquals(List.of(
                "\t\tCACHE 7",
                "ALTER TABLE ONLY chk.doc ALTER COLUMN title SET STATISTICS 100;"),
                markedIn(loaded, TABLE, settings),
                "the cache is marked through the wrapper, indent and all, and the rest as before");

        String script = script(loaded, settings);
        assertFalse(script.contains("CACHE"), "and the migration still carries no cache:\n" + script);
    }

    /**
     * The body of a function is not a statement of the object being rendered and
     * is left alone, wrapper or no wrapper.
     * <p>
     * Without this the next hand would read every dollar-quoted body there is. A
     * routine that mentions {@code CACHE} mentions it in code of another
     * language: nothing there is a clause of this comparison, and marking a line
     * of it would tell a reader that a line of a routine body is outside the
     * comparison - which is not what the settings said about anything.
     */
    @Test
    void theBodyOfARoutineIsStillReadAsText() throws IOException, InterruptedException {
        String withRoutine = """
                CREATE SCHEMA chk;

                CREATE SEQUENCE chk.s_id
                    INCREMENT BY %s
                    CACHE %s;

                CREATE FUNCTION chk.f() RETURNS text AS $$
                BEGIN
                    RETURN 'CACHE 999';
                END;
                $$ LANGUAGE plpgsql;""";

        CoreSettings settings = overlooking(true, true);
        LoadedComparison loaded = load(withRoutine.formatted("2", "20"), withRoutine.formatted("1", "10"), settings);

        ObjectReference routine = new ObjectReference("chk", "f()", DbObjType.FUNCTION);
        IStatement shown = loaded.newDatabase().getStatement(routine);
        assertNotNull(shown, "fixture must offer the routine");
        String sql = shown.getSQL(false, settings);
        assertTrue(sql.contains("CACHE 999"), "the fixture must name a cache inside the body:\n" + sql);

        assertEquals(List.of(), SqlMarkup.rangesIn(sql, Map.of(),
                IgnoredValues.of(settings, loaded.oldDatabase().getStatement(routine), shown)),
                "a cache written in a routine body is text");
        assertEquals(List.of("\tCACHE 10;"), markedIn(loaded, SEQUENCE, settings),
                "while the sequence beside it is marked as always");
    }

    /** The lines of one state of an object that carry an overlooked value. */
    private static List<String> markedIn(LoadedComparison loaded, ObjectReference ref, CoreSettings settings) {
        IStatement inOldState = loaded.oldDatabase() == null ? null : loaded.oldDatabase().getStatement(ref);
        IStatement shown = loaded.newDatabase().getStatement(ref);
        assertNotNull(shown, "fixture must offer the object: " + ref);

        String sql = shown.getSQL(false, settings);
        List<String> lines = new ArrayList<>();
        for (Marked range : SqlMarkup.rangesIn(sql, Map.of(), IgnoredValues.of(settings, inOldState, shown))) {
            assertEquals(SqlMark.VALUE_IGNORED, range.mark(), "no column rule is in play here");
            lines.add(sql.substring(range.offset(), range.offset() + range.length()));
        }
        return lines;
    }

    private String script(LoadedComparison loaded, CoreSettings settings) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings);
    }

    private LoadedComparison bothSides(CoreSettings settings) throws IOException, InterruptedException {
        return load(BOTH_SIDES.formatted("2", "20", "9", "500", "MAIN"),
                BOTH_SIDES.formatted("1", "10", "7", "100", "EXTERNAL"), settings);
    }

    private static CoreSettings overlooking(boolean cache, boolean statistics) {
        CoreSettings settings = new CoreSettings();
        settings.setIgnoreSequenceCache(cache);
        settings.setIgnoreColumnStatistics(statistics);
        return settings;
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
