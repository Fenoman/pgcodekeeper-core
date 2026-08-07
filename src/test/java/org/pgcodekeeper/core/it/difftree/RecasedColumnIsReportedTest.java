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
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.ch.ChDatabaseProvider;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;
import org.pgcodekeeper.core.utils.LogCapture;
import org.slf4j.event.Level;

/**
 * An operator who renames a column is told that the migration will not carry
 * its data across, before running it rather than after.
 * <p>
 * pgCodeKeeper has no rename to generate, so a renamed column reaches the
 * script as a {@code DROP} of the old one and an {@code ADD} of the new one -
 * two statements that are individually correct, that together throw away every
 * row of the column, and that say so nowhere. The whole of what is added here is
 * the saying so: every case below reads the script back and asserts that it did
 * not move.
 * <p>
 * <b>The half that matters is the silent one.</b> A warning under every dropped
 * column would be worth nothing, so the cases are weighted towards the pairs
 * that must stay quiet - a column really replaced by another, a column simply
 * removed, a column whose two spellings the lexer had already made one.
 */
class RecasedColumnIsReportedTest {

    private static final String TABLE = "rnm.doc";

    /** A column spelled with quotes, which is the only way to keep its case. */
    private static final String QUOTED_UPPER = """
            CREATE SCHEMA rnm;

            CREATE TABLE rnm.doc (
                id bigint NOT NULL,
                "NAME" text
            );""";

    /** The same column, respelled as an operator renaming it would spell it. */
    private static final String LOWER = """
            CREATE SCHEMA rnm;

            CREATE TABLE rnm.doc (
                id bigint NOT NULL,
                name text
            );""";

    /**
     * A case difference written without quotes. The lexer folds this to
     * {@code title}, so the two states hold the very same column and the pair
     * this is all about never arises. Spelled with another word than the rest
     * of the fixtures because {@code NAME} is a keyword of the grammar and
     * reaches the model unfolded, which is a fault of its own and not this one.
     */
    private static final String UNQUOTED_UPPER = """
            CREATE SCHEMA rnm;

            CREATE TABLE rnm.doc (
                id bigint NOT NULL,
                TITLE text
            );""";

    /** The same table with that column spelled in lower case. */
    private static final String UNQUOTED_LOWER = """
            CREATE SCHEMA rnm;

            CREATE TABLE rnm.doc (
                id bigint NOT NULL,
                title text
            );""";

    /** A column genuinely replaced by another one, which is not a rename. */
    private static final String OTHER_NAME = """
            CREATE SCHEMA rnm;

            CREATE TABLE rnm.doc (
                id bigint NOT NULL,
                caption text
            );""";

    /** The same table with the column gone and nothing put in its place. */
    private static final String NO_COLUMN = """
            CREATE SCHEMA rnm;

            CREATE TABLE rnm.doc (
                id bigint NOT NULL
            );""";

    private static final String CH_TABLE = "chrnm.doc";

    /** The same case-only pair in a dialect where case is part of a name. */
    private static final String CH_UPPER = """
            CREATE DATABASE chrnm;

            CREATE TABLE chrnm.doc
            (
                `id` Int64,
                `NAME` String
            )
            ENGINE = MergeTree
            ORDER BY id;""";

    private static final String CH_LOWER = """
            CREATE DATABASE chrnm;

            CREATE TABLE chrnm.doc
            (
                `id` Int64,
                `name` String
            )
            ENGINE = MergeTree
            ORDER BY id;""";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();
    private final ChDatabaseProvider chProvider = new ChDatabaseProvider();

    /**
     * The whole point: the pair is named out loud, and the script that produced
     * it is byte for byte the script that was produced before anybody was told.
     */
    @Test
    void aColumnRespelledInCaseAloneIsReported() throws IOException, InterruptedException {
        try (LogCapture log = LogCapture.start()) {
            String script = script(QUOTED_UPPER, LOWER, new CoreSettings());

            assertScriptIs("""
                    SET search_path = pg_catalog;

                    ALTER TABLE ONLY rnm.doc
                    \tDROP COLUMN "NAME";

                    ALTER TABLE rnm.doc
                    \tADD COLUMN name text;""", script);
            assertEquals(1, warnings(log).size(),
                    () -> "the respelled column must be reported exactly once: " + warnings(log));
            String warning = warnings(log).get(0);
            assertTrue(warning.contains("NAME") && warning.contains("name"),
                    () -> "the warning must name both spellings: " + warning);
        }
    }

    /**
     * The level is the message. Everything else said around here explains a
     * decision the migration took and is said at {@code info}; this one says
     * that a script about to be executed destroys data, and an operator who
     * filters their log to the things that matter must still be handed it.
     * Demoting it would leave every assertion above green and the promise
     * broken, so the promise is asserted rather than the wording alone.
     */
    @Test
    void theReportIsMadeAtWarnLevel() throws IOException, InterruptedException {
        try (LogCapture log = LogCapture.start()) {
            script(QUOTED_UPPER, LOWER, new CoreSettings());

            assertEquals(List.of(Level.WARN), log.levelsOf(TABLE),
                    "losing the data of a column is not an informational event");
        }
    }

    /**
     * The case the detection must not widen into: a column replaced by one with
     * an unrelated name is the plain drop-and-add it looks like, and gets the
     * same script with nothing said about it.
     */
    @Test
    void aColumnReplacedByAnotherNameIsNotReported() throws IOException, InterruptedException {
        try (LogCapture log = LogCapture.start()) {
            String script = script(LOWER, OTHER_NAME, new CoreSettings());

            assertTrue(script.contains("DROP COLUMN name") && script.contains("ADD COLUMN caption text"),
                    () -> "the fixture must really produce a drop and an add: " + script);
            assertEquals(List.of(), warnings(log),
                    "two different columns are not a rename");
        }
    }

    /**
     * A column that leaves with nothing arriving is a removal and is meant to
     * be one: there is no second half to mistake it for a rename.
     */
    @Test
    void aColumnRemovedWithNothingAddedIsNotReported() throws IOException, InterruptedException {
        try (LogCapture log = LogCapture.start()) {
            String script = script(QUOTED_UPPER, NO_COLUMN, new CoreSettings());

            assertTrue(script.contains("DROP COLUMN \"NAME\""),
                    () -> "the fixture must really drop the column: " + script);
            assertEquals(List.of(), warnings(log), "a removal on its own is not a rename");
        }
    }

    /**
     * Where the case never arises. An unquoted identifier is folded by the
     * lexer, so {@code TITLE} and {@code title} are one string by the time
     * anything compares them: no drop, no add, no script and nothing to say.
     */
    @Test
    void anUnquotedRespellingIsNotADifferenceAtAll() throws IOException, InterruptedException {
        try (LogCapture log = LogCapture.start()) {
            assertScriptIs("", script(UNQUOTED_UPPER, UNQUOTED_LOWER, new CoreSettings()));
            assertEquals(List.of(), warnings(log), "the two states hold the same column");
        }
    }

    /**
     * A column a {@code type=COLUMN} rule hides produces no statement, so the
     * data it holds is not going anywhere and there is nothing to warn about.
     * Warning here would tell an operator to fear a script that does not touch
     * the column at all.
     */
    @Test
    void aHiddenRespellingIsNotReported() throws IOException, InterruptedException {
        try (LogCapture log = LogCapture.start()) {
            CoreSettings hidden = new CoreSettings();
            hidden.getIgnoreList().add(columnRule("NAME"));
            hidden.getIgnoreList().add(columnRule("name"));

            assertScriptIs("", script(QUOTED_UPPER, LOWER, hidden));
            assertEquals(List.of(), warnings(log), "a hidden column is not migrated and cannot be lost");
        }
    }

    /**
     * The dialect that must stay quiet. ClickHouse keeps the case of a name
     * without being asked to, so {@code NAME} and {@code name} are two columns
     * as ordinary as {@code name} and {@code caption} are in PostgreSQL - and a
     * migration removing one and adding the other is doing what it says, not
     * hiding a rename inside it.
     */
    @Test
    void aRespellingIsNotARenameWhereCaseIsAnOrdinaryPartOfAName()
            throws IOException, InterruptedException {
        try (LogCapture log = LogCapture.start()) {
            String script = chScript(CH_UPPER, CH_LOWER);

            assertTrue(script.contains("DROP COLUMN `NAME`") && script.contains("ADD COLUMN `name`"),
                    () -> "the fixture must really produce a drop and an add: " + script);
            assertEquals(List.of(), log.messagesContaining(CH_TABLE),
                    "case tells ClickHouse columns apart, so this pair is two columns");
        }
    }

    private static IgnoredObject columnRule(String name) {
        return new IgnoredObject(name, null, false, false, false, false, EnumSet.of(DbObjType.COLUMN));
    }

    /**
     * Other test classes run beside this one and share the capture, so the
     * table of this fixture is what tells its messages apart from theirs.
     */
    private static List<String> warnings(LogCapture log) {
        return log.messagesContaining(TABLE);
    }

    private String script(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        LoadedComparison loaded = load(oldSql, newSql, settings);
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings);
    }

    private String chScript(String oldSql, String newSql) throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        settings.clearErrors();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> chProvider.getDumpLoader(source(oldSql), "old", sideSettings),
                        sideSettings -> chProvider.getDumpLoader(source(newSql), "new", sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        assertNotNull(loaded.newDatabase(), "fixture must load");
        return PgCodeKeeperApi.diff(chProvider, loaded.oldDatabase(), loaded.newDatabase(), settings);
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

    private static void assertScriptIs(String expected, String actual) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8),
                () -> "script must be byte for byte:\nexpected\n" + expected + "\nactual\n" + actual);
    }
}
