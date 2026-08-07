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
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * A {@code type=COLUMN} rule changes nothing about how a Microsoft SQL table is
 * written.
 * <p>
 * The promise is the one PostgreSQL keeps, see
 * {@link CreateScriptWritesEveryColumnTest}: hiding lives in the comparison, so
 * a {@code CREATE TABLE} states the table exactly as the project declares it and
 * every statement about a column - here a privilege granted on the column alone
 * - is written with it. This dialect is asked separately because it owns its
 * columns in a list of its own and writes them with code of its own, and because
 * three parts of its body name columns rather than declaring them: a
 * {@code PERIOD FOR SYSTEM_TIME}, the inline key of a memory optimized table,
 * and the column list of an index.
 * <p>
 * Every case is asserted against the very same fixture rendered with no ignore
 * list at all, byte for byte.
 */
class CreateScriptWritesEveryColumnMsTest {

    /**
     * The columns of the real ignore list this was written for, so that a rule
     * naming several columns is exercised the way it is actually written.
     */
    private static final List<String> AUDIT_COLUMNS = List.of(
            "s_audit_id_create", "s_audit_id_modif", "s_create_date", "s_creator", "s_modif_date", "s_owner");

    private static final String NOTHING = "CREATE SCHEMA [dbo]\nGO";

    private static final String GRANTED = """
            CREATE TABLE [dbo].[doc](
                [id] [bigint] NOT NULL,
                [s_creator] [nvarchar](50) NULL
            ) ON [PRIMARY]
            GO
            GRANT SELECT ON [dbo].[doc]([s_creator]) TO [test_user]
            GO""";

    private static final String TEMPORAL = """
            CREATE TABLE [dbo].[doc](
                [id] [bigint] NOT NULL,
                [s_create_date] [datetime2] GENERATED ALWAYS AS ROW START HIDDEN NOT NULL,
                [s_modif_date] [datetime2] GENERATED ALWAYS AS ROW END HIDDEN NOT NULL,
                PERIOD FOR SYSTEM_TIME ([s_create_date], [s_modif_date])
            ) ON [PRIMARY]
            GO""";

    private static final String MEMORY_OPTIMIZED = """
            CREATE TABLE [dbo].[doc](
                [id] [bigint] NOT NULL,
                [s_creator] [nvarchar](50) NOT NULL,
                CONSTRAINT [pk_doc] PRIMARY KEY NONCLUSTERED ([s_creator] ASC)
            ) WITH (MEMORY_OPTIMIZED = ON, DURABILITY = SCHEMA_AND_DATA)
            GO""";

    private static final String INDEXED = """
            CREATE TABLE [dbo].[doc](
                [id] [bigint] NOT NULL,
                [s_creator] [nvarchar](50) NOT NULL
            ) ON [PRIMARY]
            GO
            CREATE INDEX [ix_doc] ON [dbo].[doc] ([s_creator])
            GO""";

    private static final String NOTHING_BUT_AUDIT = """
            CREATE TABLE [dbo].[doc](
                [s_creator] [nvarchar](50) NULL
            ) ON [PRIMARY]
            GO""";

    static List<Fixture> fixtures() {
        return List.of(
                new Fixture("granted", GRANTED, "GRANT SELECT ON [dbo].[doc]([s_creator]) TO [test_user]"),
                new Fixture("temporal", TEMPORAL, "PERIOD FOR SYSTEM_TIME ([s_create_date], [s_modif_date])"),
                new Fixture("memory optimized", MEMORY_OPTIMIZED, "PRIMARY KEY NONCLUSTERED  ([s_creator])"),
                new Fixture("indexed", INDEXED, "CREATE NONCLUSTERED INDEX [ix_doc] ON [dbo].[doc] ([s_creator])"),
                new Fixture("audit only", NOTHING_BUT_AUDIT, "[s_creator] [nvarchar](50) NULL"));
    }

    private final MsDatabaseProvider provider = new MsDatabaseProvider();

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
     * The defect this replaced, read the other way round: the column stays in the
     * body of the {@code CREATE} and so does the privilege granted on it.
     */
    @Test
    void aCreatedTableGrantsOnTheColumnItWrites() throws IOException, InterruptedException {
        assertScriptIs("""
                SET QUOTED_IDENTIFIER ON
                GO
                SET ANSI_NULLS ON
                GO
                CREATE TABLE [dbo].[doc](
                \t[id] [bigint] NOT NULL,
                \t[s_creator] [nvarchar](50) NULL
                ) ON [PRIMARY]
                GO

                GRANT SELECT ON [dbo].[doc]([s_creator]) TO [test_user]
                GO""", script(NOTHING, GRANTED, auditHidden()));
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
                CREATE SCHEMA [dbo]
                GO
                CREATE TABLE [dbo].[doc](
                    [id] [bigint] NOT NULL,
                    [s_creator] [nvarchar](50) NULL
                ) ON [PRIMARY]
                GO""", """
                CREATE SCHEMA [dbo]
                GO
                CREATE TABLE [dbo].[doc](
                    [id] [bigint] NOT NULL,
                    [s_creator] [nvarchar](50) NULL
                ) ON [SECONDARY]
                GO""", settings);

        assertTrue(moved.contains("INSERT INTO [dbo].[doc]([id], [s_creator])"), moved);
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
            sb.append("CREATE TABLE [dbo].[doc_").append(i).append("](\n")
                    .append("\t[id] [bigint] NOT NULL,\n")
                    .append("\t[title] [nvarchar](").append(altered ? 100 : 50).append(") NULL,\n")
                    .append("\t[s_creator] [nvarchar](50) NULL\n")
                    .append(") ON [PRIMARY]\nGO\n");
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
