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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.ch.ChDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * A {@code type=COLUMN} rule changes nothing about how a ClickHouse table is
 * written.
 * <p>
 * The promise is the one PostgreSQL keeps, see
 * {@link CreateScriptWritesEveryColumnTest}. ClickHouse writes almost everything
 * a column carries inside the definition of that column, so what is left to
 * watch is the body around it: a projection, the {@code CHECK} of a Log table
 * and the sorting key of an engine are all parts of the body written as the text
 * they came in.
 * <p>
 * Every case is asserted against the very same fixture rendered with no ignore
 * list at all, byte for byte.
 */
class CreateScriptWritesEveryColumnChTest {

    private static final List<String> AUDIT_COLUMNS = List.of(
            "s_audit_id_create", "s_audit_id_modif", "s_create_date", "s_creator", "s_modif_date", "s_owner");

    private static final String NOTHING = "CREATE DATABASE default;";

    private static final String AUDIT_ONLY = """
            CREATE TABLE default.doc
            (
                `s_creator` String
            )
            ENGINE = Log;""";

    private static final String PROJECTED = """
            CREATE TABLE default.doc
            (
                `id` Int64,
                `s_creator` String,
                PROJECTION p1 (SELECT s_creator ORDER BY s_creator)
            )
            ENGINE = MergeTree
            ORDER BY id;""";

    private static final String CHECKED = """
            CREATE TABLE default.doc
            (
                `id` Int64,
                `s_creator` String,
                CONSTRAINT c1 CHECK s_creator != ''
            )
            ENGINE = Log;""";

    private static final String SORTED = """
            CREATE TABLE default.doc
            (
                `id` Int64,
                `s_creator` String
            )
            ENGINE = MergeTree
            ORDER BY s_creator;""";

    private static final String COMMENTED = """
            CREATE TABLE default.doc
            (
                `id` Int64,
                `s_creator` String COMMENT 'author'
            )
            ENGINE = Log;""";

    static List<Fixture> fixtures() {
        return List.of(
                new Fixture("audit only", AUDIT_ONLY, "`s_creator` String"),
                new Fixture("projected", PROJECTED, "PROJECTION p1 (SELECT s_creator ORDER BY s_creator)"),
                new Fixture("checked", CHECKED, "CONSTRAINT c1 CHECK s_creator != ''"),
                new Fixture("sorted", SORTED, "ORDER BY s_creator"),
                new Fixture("commented", COMMENTED, "`s_creator` String COMMENT 'author'"));
    }

    private final ChDatabaseProvider provider = new ChDatabaseProvider();

    /**
     * The whole promise in one case per shape: the rules do not change a byte of
     * what is written, and what is written names the column the rules were about.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theRulesChangeNothingAboutHowATableIsCreated(Fixture fixture) throws IOException, InterruptedException {
        String withRules = script(NOTHING, fixture.sql(), auditHidden());
        String without = script(NOTHING, fixture.sql(), new CoreSettings());

        assertTrue(without.contains(fixture.named()),
                "the fixture must produce " + fixture.named() + ":\n" + without);
        assertScriptIs(without, withRules);
    }

    /**
     * The defect this replaced, read the other way round: a column of the project
     * is written into the body of the {@code CREATE} and takes its comment along.
     */
    @Test
    void aCreatedTableWritesEveryColumnOfTheProject() throws IOException, InterruptedException {
        assertScriptIs("""
                CREATE TABLE default.doc
                (
                \t`id` Int64,
                \t`s_creator` String COMMENT 'author'
                )
                ENGINE = Log;""", script(NOTHING, COMMENTED, auditHidden()));
    }

    /**
     * A recreated table is filled from the copy of the old one, and the columns
     * named there are the columns the new table was built with. It is built with
     * every column the project declares, so every one of them is moved - leaving
     * one out would silently drop its data on a table rebuild.
     */
    @Test
    void aRecreatedTableMovesEveryColumnItWasBuiltWith() throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        settings.setDataMovementMode(true);

        String moved = script("""
                CREATE DATABASE default;

                CREATE TABLE default.doc
                (
                    `id` Int64,
                    `s_creator` String
                )
                ENGINE = MergeTree
                ORDER BY id;""", """
                CREATE DATABASE default;

                CREATE TABLE default.doc
                (
                    `id` Int64,
                    `s_creator` String
                )
                ENGINE = ReplacingMergeTree
                ORDER BY id;""", settings);

        assertTrue(moved.contains("INSERT INTO default.doc(id, s_creator)"), moved);
    }

    /**
     * The reading that decides whether anything still names a column is done once
     * for a database and not once per table, in this dialect as in PostgreSQL:
     * every question this dialect asks goes through the holder of the operation,
     * see {@link HiddenColumnIndexScopeTest} for what that holder is and why it
     * is the whole of the cost.
     */
    @Test
    void theCountOfReadingsDoesNotFollowTheCountOfTables() throws IOException, InterruptedException {
        int forThree = readingsComparing(3);
        int forThirty = readingsComparing(30);

        assertEquals(forThree, forThirty, "a database is read for itself, not for each of its tables: 3 tables read "
                + forThree + " times and 30 tables read " + forThirty + " times");
        assertTrue(forThirty > 0, "a comparison that can hide a column does read the database");
        assertTrue(forThirty <= 2, "at most one reading per compared state, but read " + forThirty + " times");
    }

    private int readingsComparing(int tables) throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        script(manyTables(tables, false), manyTables(tables, true), settings);
        return settings.getColumnUsers().indexesBuilt();
    }

    private static String manyTables(int count, boolean altered) {
        StringBuilder sb = new StringBuilder(NOTHING).append('\n');
        for (int i = 0; i < count; i++) {
            sb.append("\nCREATE TABLE default.doc_").append(i).append("\n(\n")
                    .append("\t`id` Int64,\n")
                    .append("\t`title` ").append(altered ? "Nullable(String)" : "String").append(",\n")
                    .append("\t`s_creator` String\n")
                    .append(")\nENGINE = Log;\n");
        }
        return sb.toString();
    }

    private static CoreSettings auditHidden() {
        CoreSettings settings = new CoreSettings();
        AUDIT_COLUMNS.forEach(column -> settings.getIgnoreList()
                .add(new IgnoredObject(column, null, false, false, false, false, EnumSet.of(DbObjType.COLUMN))));
        return settings;
    }

    private String script(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        settings.clearErrors();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> provider.getDumpLoader(source(oldSql), "old", sideSettings),
                        sideSettings -> provider.getDumpLoader(source(newSql), "new", sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        assertNotNull(loaded.newDatabase(), "fixture must load");
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings);
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertScriptIs(String expected, String actual) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8),
                () -> "script must be byte for byte:\nexpected\n" + expected + "\nactual\n" + actual);
    }

    /**
     * One shape of a table this dialect can write, together with a part of the
     * script that names the column the rules were about, so that a case is known
     * to be about it at all.
     */
    record Fixture(String name, String sql, String named) {

        @Override
        public String toString() {
            return name;
        }
    }
}
