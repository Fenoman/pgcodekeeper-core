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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.ch.ChDatabaseProvider;
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * The one property the whole mechanism rests on: a column a {@code type=COLUMN}
 * rule hides takes no part in a comparison, in either direction.
 * <p>
 * Generation writes whatever it is given - a migration script and a database
 * created from scratch both state the table exactly as the project declares it,
 * see {@link CreateScriptWritesEveryColumnTest}. That is safe only because
 * nothing ever asks generation to produce a statement <em>about</em> a hidden
 * column: the comparison never reports one as added, dropped or altered, so no
 * such statement is ever requested.
 * <p>
 * Read the other way round, this is the case that would end a production
 * database. The project of the fixture below is the real one: it declares the
 * business columns of a table and leaves the six audit columns to a trigger on
 * the server. A comparison that hid nothing would answer
 * {@code ALTER TABLE ... DROP COLUMN s_create_date} for every one of eleven
 * thousand tables. So the assertion is not that the script is short - it is that
 * the script is empty, byte for byte, while the very same pair with no rule at
 * all produces the drops.
 * <p>
 * Both directions are asked, because a column missing from the project and a
 * column missing from the database are two different code paths, and all three
 * dialects are asked, because each owns its columns in a list of its own.
 *
 * @see HiddenColumnMigrationTest for what the rule does to a column both states
 * hold
 */
class HiddenColumnIsNeverDroppedTest {

    private static final List<String> AUDIT_COLUMNS = List.of(
            "s_audit_id_create", "s_audit_id_modif", "s_create_date", "s_creator", "s_modif_date", "s_owner");

    static List<Dialect> dialects() {
        return List.of(new PgDialect(), new MsDialect(), new ChDialect());
    }

    /**
     * The shape of the accident this exists to prevent: the project declares no
     * audit column, the database is full of them, and the migration says nothing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void aColumnTheProjectDoesNotDeclareIsNeverDropped(Dialect dialect) throws IOException, InterruptedException {
        assertScriptIs("", script(dialect, dialect.withAudit(), dialect.withoutAudit(), auditHidden()));
    }

    /**
     * The other direction, which is the same promise seen from the database: a
     * column the project declares and the database does not hold is not added
     * either. The rule says pgCodeKeeper does not manage the column, and that is
     * an answer about the column rather than about one side of it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void aColumnTheDatabaseDoesNotHoldIsNeverAdded(Dialect dialect) throws IOException, InterruptedException {
        assertScriptIs("", script(dialect, dialect.withoutAudit(), dialect.withAudit(), auditHidden()));
    }

    /**
     * The control that gives the two cases above their meaning: with no rule
     * naming the columns the very same pairs do produce a script, and it is the
     * script that would run against production.
     * <p>
     * How a dialect spells taking a column out is its own business - two of them
     * write a {@code DROP COLUMN} and ClickHouse rewrites the table - so the
     * dialect states the evidence and the shared part of the case only demands
     * that something be written at all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void theSameColumnsMoveWhenNothingIsHidden(Dialect dialect) throws IOException, InterruptedException {
        String dropping = script(dialect, dialect.withAudit(), dialect.withoutAudit(), new CoreSettings());
        String adding = script(dialect, dialect.withoutAudit(), dialect.withAudit(), new CoreSettings());

        assertFalse(dropping.isBlank(), "the fixture must really hold something to take out");
        for (String evidence : dialect.removalEvidence()) {
            assertTrue(dropping.contains(evidence), "expected " + evidence + " in:\n" + dropping);
        }
        for (String column : AUDIT_COLUMNS) {
            assertTrue(adding.contains(column),
                    "the fixture must really hold something to write: " + column + "\n" + adding);
        }
    }

    /**
     * A table that does change is migrated for the change it holds, and the
     * columns the rule names are still left where they are. Without this the
     * cases above could be passing because the table never reached the script at
     * all.
     */
    @Test
    void aTableMigratedForItsOwnColumnsStillDropsNothingHidden() throws IOException, InterruptedException {
        PgDialect dialect = new PgDialect();
        String migrated = script(dialect, dialect.withAudit(), """
                CREATE SCHEMA dbo;

                CREATE TABLE dbo.doc (
                    id bigint NOT NULL,
                    title character varying(200)
                );""", auditHidden());

        assertTrue(migrated.contains("ALTER COLUMN title TYPE character varying(200)"),
                "the visible column must still be migrated:\n" + migrated);
        for (String column : AUDIT_COLUMNS) {
            assertFalse(migrated.contains(column),
                    "no statement may name the hidden column " + column + ":\n" + migrated);
        }
    }

    private static CoreSettings auditHidden() {
        CoreSettings settings = new CoreSettings();
        AUDIT_COLUMNS.forEach(column -> settings.getIgnoreList()
                .add(new IgnoredObject(column, null, false, false, false, false, EnumSet.of(DbObjType.COLUMN))));
        return settings;
    }

    private String script(Dialect dialect, String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        settings.clearErrors();
        IDatabaseProvider provider = dialect.provider();
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
     * One table of a project, in the two states this asks about, together with
     * the way the dialect spells adding and dropping a column of it.
     */
    interface Dialect {

        IDatabaseProvider provider();

        /** The table as the database holds it: business columns and audit columns. */
        String withAudit();

        /** The same table as the project declares it: business columns alone. */
        String withoutAudit();

        /**
         * The statements that must appear when the audit columns leave the
         * database, spelled the way this dialect spells them.
         */
        List<String> removalEvidence();
    }

    private static final class PgDialect implements Dialect {

        @Override
        public IDatabaseProvider provider() {
            return new PgDatabaseProvider();
        }

        @Override
        public String withAudit() {
            return """
                    CREATE SCHEMA dbo;

                    CREATE TABLE dbo.doc (
                        id bigint NOT NULL,
                        title text,
                        s_audit_id_create bigint,
                        s_audit_id_modif bigint,
                        s_create_date timestamp without time zone,
                        s_creator text,
                        s_modif_date timestamp without time zone,
                        s_owner text
                    );""";
        }

        @Override
        public String withoutAudit() {
            return """
                    CREATE SCHEMA dbo;

                    CREATE TABLE dbo.doc (
                        id bigint NOT NULL,
                        title text
                    );""";
        }

        @Override
        public List<String> removalEvidence() {
            return AUDIT_COLUMNS.stream().map(column -> "DROP COLUMN " + column).toList();
        }

        @Override
        public String toString() {
            return "PG";
        }
    }

    private static final class MsDialect implements Dialect {

        @Override
        public IDatabaseProvider provider() {
            return new MsDatabaseProvider();
        }

        @Override
        public String withAudit() {
            return """
                    CREATE SCHEMA [dbo]
                    GO
                    CREATE TABLE [dbo].[doc](
                        [id] [bigint] NOT NULL,
                        [title] [nvarchar](50) NULL,
                        [s_audit_id_create] [bigint] NULL,
                        [s_audit_id_modif] [bigint] NULL,
                        [s_create_date] [datetime2] NULL,
                        [s_creator] [nvarchar](50) NULL,
                        [s_modif_date] [datetime2] NULL,
                        [s_owner] [nvarchar](50) NULL
                    ) ON [PRIMARY]
                    GO""";
        }

        @Override
        public String withoutAudit() {
            return """
                    CREATE SCHEMA [dbo]
                    GO
                    CREATE TABLE [dbo].[doc](
                        [id] [bigint] NOT NULL,
                        [title] [nvarchar](50) NULL
                    ) ON [PRIMARY]
                    GO""";
        }

        @Override
        public List<String> removalEvidence() {
            return AUDIT_COLUMNS.stream().map(column -> "DROP COLUMN [" + column + ']').toList();
        }

        @Override
        public String toString() {
            return "MS";
        }
    }

    private static final class ChDialect implements Dialect {

        @Override
        public IDatabaseProvider provider() {
            return new ChDatabaseProvider();
        }

        @Override
        public String withAudit() {
            return """
                    CREATE DATABASE default;

                    CREATE TABLE default.doc
                    (
                        `id` Int64,
                        `title` String,
                        `s_audit_id_create` Int64,
                        `s_audit_id_modif` Int64,
                        `s_create_date` DateTime,
                        `s_creator` String,
                        `s_modif_date` DateTime,
                        `s_owner` String
                    )
                    ENGINE = Log;""";
        }

        @Override
        public String withoutAudit() {
            return """
                    CREATE DATABASE default;

                    CREATE TABLE default.doc
                    (
                        `id` Int64,
                        `title` String
                    )
                    ENGINE = Log;""";
        }

        /**
         * ClickHouse answers a changed column list by rewriting the table, so
         * what proves the columns left is the table being dropped and built
         * again out of the two the project declares.
         */
        @Override
        public List<String> removalEvidence() {
            return List.of("DROP TABLE default.doc;", """
                    CREATE TABLE default.doc
                    (
                    \t`id` Int64,
                    \t`title` String
                    )
                    ENGINE = Log;""");
        }

        @Override
        public String toString() {
            return "CH";
        }
    }
}
