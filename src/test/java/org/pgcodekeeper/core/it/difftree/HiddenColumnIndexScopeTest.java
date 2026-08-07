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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.model.difftree.ColumnUsers;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * How long the index that decides hidden columns lives, and how often it is
 * built.
 * <p>
 * Deciding whether anything outside a table still names one of its columns means
 * reading the whole database, see {@link ColumnVisibility}. Asked once per table
 * that turned an export of 13 323 tables from 40 s into 482 s; asked once for
 * the whole database it costs 60 ms and the export pays for exactly one. The
 * difference is entirely a question of who holds the answer and for how long,
 * and that is what is pinned here:
 * <ul>
 * <li>one index per state of the database, however many tables ask;</li>
 * <li>none at all when no rule can hide a column;</li>
 * <li>a copy of the settings starts with none, because a copy is a new
 * operation - the CLI builds every output of a batch from one, and the pairs of
 * databases those outputs compare are not the same pairs;</li>
 * <li>an index answers for the database it was built from and for no other.</li>
 * </ul>
 *
 * @see HiddenColumnOutsideUserTest for what the index is asked and why
 */
class HiddenColumnIndexScopeTest {

    private static final List<String> AUDIT_COLUMNS = List.of(
            "s_audit_id_create", "s_audit_id_modif", "s_create_date", "s_creator", "s_modif_date", "s_owner");

    private static final String NOTHING = "CREATE SCHEMA public;";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    /**
     * The whole of the fix, as a number that does not move: a comparison builds
     * one index per state it compares, and adding ten times as many tables to
     * both states does not add a single build.
     */
    @Test
    void theCountOfIndexesDoesNotFollowTheCountOfTables() throws IOException, InterruptedException {
        int forThree = indexesBuiltComparing(3, auditHidden());
        int forThirty = indexesBuiltComparing(30, auditHidden());

        assertEquals(forThree, forThirty,
                "an index is built for a database, not for a table: 3 tables built " + forThree
                        + " and 30 tables built " + forThirty);
        assertTrue(forThirty > 0, "a comparison that can hide a column does read the database");
        assertTrue(forThirty <= 2,
                "at most one index per compared state, but built " + forThirty);
    }

    /**
     * An ignore list no {@code type=COLUMN} rule appears in cannot hide a column,
     * so it must not read the database at all - not once, not cheaply, not
     * lazily. This is what keeps every list that existed before this mechanism
     * paying exactly nothing for it.
     */
    @Test
    void anIgnoreListThatHidesNoColumnBuildsNoIndex() throws IOException, InterruptedException {
        assertEquals(0, indexesBuiltComparing(30, new CoreSettings()),
                "nothing can be hidden, so nothing may be read");
    }

    /**
     * A rule that names a type other than {@code COLUMN} is still no rule about
     * columns, and a rule that only shows cannot hide either.
     */
    @Test
    void anIgnoreListWithoutAHidingColumnRuleBuildsNoIndex() throws IOException, InterruptedException {
        CoreSettings tables = new CoreSettings();
        tables.getIgnoreList().add(new IgnoredObject(
                "doc_0", null, false, false, false, false, EnumSet.of(DbObjType.TABLE)));

        CoreSettings shows = new CoreSettings();
        shows.getIgnoreList().add(new IgnoredObject(
                "s_creator", null, true, false, false, false, EnumSet.of(DbObjType.COLUMN)));

        assertEquals(0, indexesBuiltComparing(5, tables), "a rule about tables says nothing about columns");
        assertEquals(0, indexesBuiltComparing(5, shows), "a rule that shows can hide nothing");
    }

    /**
     * The index lives exactly one operation, and a copy of the settings is
     * another one.
     * <p>
     * The CLI builds every output of a batch run from a copy of one settings
     * instance. Those outputs compare their own pairs of databases, so an index
     * carried into a copy would be an index of somebody else's database kept
     * alive past the operation that built it - which is the one thing the
     * lifetime of this holder exists to rule out.
     */
    @Test
    void aCopyOfTheSettingsStartsWithNoIndex() throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        assertEquals(0, indexesBuilt(settings), "nothing asked yet");
        compare(5, settings);
        assertTrue(indexesBuilt(settings) > 0, "the comparison asked");

        CoreSettings shallow = settings.shallowCopy();
        assertNotSame(settings.getColumnUsers(), shallow.getColumnUsers(),
                "a copy must hold its own");
        assertEquals(0, indexesBuilt(shallow), "a copy starts with nothing read");

        var full = settings.copy();
        assertNotSame(settings.getColumnUsers(), full.getColumnUsers(),
                "a copy must hold its own");
        assertEquals(0, indexesBuilt(full), "a copy starts with nothing read");
    }

    /**
     * The two states of one comparison are two databases, and the index of one
     * must never answer for the other. Here the view that holds the column lives
     * in the old state alone, so the same question asked about the same schema
     * and the same table name has two different right answers.
     */
    @Test
    void anIndexAnswersForItsOwnDatabaseAlone() throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        LoadedComparison loaded = load("""
                CREATE SCHEMA public;

                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_creator text
                );

                CREATE VIEW public.doc_v AS
                    SELECT id, s_creator FROM public.doc;""", """
                CREATE SCHEMA public;

                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_creator text
                );""", settings);

        ColumnVisibility managed = ColumnVisibility.of(settings);
        assertEquals(Map.of("s_creator", "public.doc_v"), managed.pinnedColumns(table(loaded.oldDatabase())),
                "the state that holds the view keeps the column");
        assertEquals(Map.of(), managed.pinnedColumns(table(loaded.newDatabase())),
                "the state that holds no view keeps nothing");
        assertEquals(2, indexesBuilt(settings), "one index per state, and the two do not answer for each other");
    }

    /**
     * Two operations are two holders, whatever else the settings behind them
     * have in common. A settings implementation that is none of ours has no
     * operation to scope an index to and gets the holder that remembers nothing
     * instead of somebody else's answer; ours always has one.
     */
    @Test
    void everySettingsInstanceHoldsItsOwn() {
        CoreSettings one = auditHidden();
        CoreSettings two = auditHidden();

        assertNotSame(one.getColumnUsers(), two.getColumnUsers(), "two operations, two holders");
        assertNotSame(ColumnUsers.NONE, one.getColumnUsers(),
                "settings of ours do have an operation to scope an index to");
        assertEquals(0, indexesBuilt(one), "a holder starts with nothing read");
    }

    private static CoreSettings auditHidden() {
        CoreSettings settings = new CoreSettings();
        AUDIT_COLUMNS.forEach(column -> settings.getIgnoreList()
                .add(new IgnoredObject(column, null, false, false, false, false, EnumSet.of(DbObjType.COLUMN))));
        return settings;
    }

    private static int indexesBuilt(ISettings settings) {
        return settings.getColumnUsers().indexesBuilt();
    }

    private int indexesBuiltComparing(int tables, CoreSettings settings)
            throws IOException, InterruptedException {
        compare(tables, settings);
        return indexesBuilt(settings);
    }

    /**
     * A comparison of two states that differ in every table, so that every table
     * is asked about its columns on both sides.
     */
    private void compare(int tables, CoreSettings settings) throws IOException, InterruptedException {
        LoadedComparison loaded = load(fixture(tables, false), fixture(tables, true), settings);
        PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings);
    }

    /**
     * Tables carrying the audit columns, each with a view outside it selecting
     * one of them, so that the index has something to find for every table.
     */
    private static String fixture(int tables, boolean altered) {
        return NOTHING + IntStream.range(0, tables).mapToObj(i -> """

                CREATE TABLE public.doc_%1$d (
                    id bigint NOT NULL,
                    payload%2$s text,
                    s_creator text,
                    s_modif_date timestamp without time zone
                );

                CREATE VIEW public.doc_%1$d_v AS
                    SELECT id, s_creator FROM public.doc_%1$d;""".formatted(i, altered ? "_new" : ""))
                .collect(Collectors.joining("\n"));
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

    private static ITable table(IDatabase db) {
        return (ITable) db.getStatement(new ObjectReference("public", "doc", DbObjType.TABLE));
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }
}
