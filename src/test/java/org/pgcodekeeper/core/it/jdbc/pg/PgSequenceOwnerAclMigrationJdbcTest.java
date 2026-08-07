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
package org.pgcodekeeper.core.it.jdbc.pg;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.Locale;

import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.database.base.parser.ScriptParser;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.jdbc.PgJdbcConnector;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.testcontainer.TestContainerType;

@Isolated("mutates the shared PG16 and PG18 testcontainers")
@ResourceLock(AbstractPgGpJdbcLoaderTest.SHARED_PG_TEST_DATABASE)
class PgSequenceOwnerAclMigrationJdbcTest {

    private static final String ORIGINAL_FIXTURE = "pg_sequence_owner_acl_original.sql";
    private static final String TARGET_FIXTURE = "pg_sequence_owner_acl_target.sql";
    private static final String IGNORE_SCHEMAS =
            "pg_sequence_owner_acl.pgcodekeeperignoreschema";
    private static final String IGNORE_OBJECTS =
            "pg_sequence_owner_acl.pgcodekeeperignore";

    private static final String SCHEMA = "pgck_sequence_owner_acl";
    private static final String TABLE = "owner_acl_table";
    private static final String SEQUENCE = "owner_acl_seq";
    private static final String OLD_OWNER = "pgck_soa_old";
    private static final String NEW_OWNER = "pgck_soa_new";
    private static final String READER = "pgck_soa_reader";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = TestContainerType.class, names = {"PG_16", "PG_18"})
    void ownedSequenceOwnerAndAclMigrationExecutesAndConverges(TestContainerType type)
            throws Exception {
        var connector = new PgJdbcConnector(type.getUrl());
        try {
            cleanup(connector);
            createRoles(connector);
            runScript(connector, ORIGINAL_FIXTURE, readFixture(ORIGINAL_FIXTURE), type);

            CoreSettings migrationSettings = settings(type);
            String migration = PgCodeKeeperApi.diff(
                    provider,
                    provider.getDumpLoader(
                            TestUtils.getFilePath(ORIGINAL_FIXTURE, getClass()),
                            migrationSettings),
                    provider.getDumpLoader(
                            TestUtils.getFilePath(TARGET_FIXTURE, getClass()),
                            migrationSettings),
                    migrationSettings);

            assertMigrationOrder(migration);
            runScript(connector, "generated sequence owner migration", migration, type);

            MigrationState state = readMigrationState(connector);
            assertAll(
                    () -> assertEquals(NEW_OWNER, state.tableOwner()),
                    () -> assertEquals(NEW_OWNER, state.sequenceOwner()),
                    () -> assertEquals(SCHEMA, state.ownedBySchema()),
                    () -> assertEquals(TABLE, state.ownedByTable()),
                    () -> assertEquals("id", state.ownedByColumn()),
                    () -> assertEquals("USAGE:false", state.readerAcl()));

            CoreSettings comparisonSettings = settings(type);
            assertFalse(comparisonSettings.isIgnorePrivileges());
            IDatabase targetDatabase = loadFixture(TARGET_FIXTURE, type);
            IDatabase actualDatabase = provider.getJdbcLoader(
                    type.getUrl(), comparisonSettings).loadAndAnalyze();
            String secondDiff = PgCodeKeeperApi.diff(
                    provider, targetDatabase, actualDatabase, comparisonSettings);
            assertEquals("", secondDiff, secondDiff);
        } finally {
            cleanup(connector);
        }
    }

    private static void assertMigrationOrder(String migration) {
        int tableOwner = migration.indexOf(
                "ALTER TABLE pgck_sequence_owner_acl.owner_acl_table OWNER TO pgck_soa_new;");
        int sequenceOwner = migration.indexOf(
                "ALTER SEQUENCE pgck_sequence_owner_acl.owner_acl_seq OWNER TO pgck_soa_new;");
        int readerGrant = migration.indexOf(
                "GRANT USAGE ON SEQUENCE pgck_sequence_owner_acl.owner_acl_seq TO pgck_soa_reader;");

        assertTrue(tableOwner >= 0 && sequenceOwner > tableOwner && readerGrant > sequenceOwner,
                migration);
    }

    private IDatabase loadFixture(String fixture, TestContainerType type) throws Exception {
        return provider.getDumpLoader(
                TestUtils.getFilePath(fixture, getClass()), settings(type)).loadAndAnalyze();
    }

    private void runScript(PgJdbcConnector connector, String name, String script,
                           TestContainerType type) throws Exception {
        var loader = provider.getDumpLoader(
                () -> new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                name, settings(type));
        var parser = new ScriptParser(loader, name, script);
        assertNull(parser.getErrorMessage(), parser.getErrorMessage());
        new JdbcRunner(new NullMonitor()).runBatches(connector, parser.batch(), null);
    }

    private CoreSettings settings(TestContainerType type) throws Exception {
        var settings = new CoreSettings();
        settings.setVersion(type.getVersion());
        settings.setIgnorePrivileges(false);
        settings.addIgnoreSchemaList(TestUtils.getFilePath(IGNORE_SCHEMAS, getClass()));
        settings.addIgnoreList(TestUtils.getFilePath(
                type.name().toLowerCase(Locale.ROOT) + ".pgcodekeeperignore", getClass()));
        settings.addIgnoreList(TestUtils.getFilePath(IGNORE_OBJECTS, getClass()));
        assertTrue(settings.isAllowedSchema(SCHEMA));
        assertFalse(settings.isAllowedSchema("public"));
        return settings;
    }

    private static String readFixture(String fixture) throws Exception {
        return Files.readString(TestUtils.getFilePath(
                fixture, PgSequenceOwnerAclMigrationJdbcTest.class), StandardCharsets.UTF_8);
    }

    private static void createRoles(PgJdbcConnector connector) throws Exception {
        try (Connection connection = connector.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + OLD_OWNER + " NOLOGIN");
            statement.execute("CREATE ROLE " + NEW_OWNER + " NOLOGIN");
            statement.execute("CREATE ROLE " + READER + " NOLOGIN");
        }
    }

    private static void cleanup(PgJdbcConnector connector) throws Exception {
        try (Connection connection = connector.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("DROP ROLE IF EXISTS " + READER);
            statement.execute("DROP ROLE IF EXISTS " + NEW_OWNER);
            statement.execute("DROP ROLE IF EXISTS " + OLD_OWNER);
        }
    }

    private static MigrationState readMigrationState(PgJdbcConnector connector)
            throws Exception {
        String query = """
                SELECT pg_catalog.pg_get_userbyid(tbl.relowner),
                       pg_catalog.pg_get_userbyid(seq.relowner),
                       tbl_ns.nspname,
                       tbl.relname,
                       attr.attname,
                       COALESCE((
                           SELECT pg_catalog.string_agg(
                                      acl.privilege_type || ':' || acl.is_grantable::pg_catalog.text,
                                      ',' ORDER BY acl.privilege_type, acl.is_grantable)
                           FROM pg_catalog.aclexplode(seq.relacl) AS acl
                           JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
                           WHERE grantee.rolname = 'pgck_soa_reader'
                       ), '')
                FROM pg_catalog.pg_class seq
                JOIN pg_catalog.pg_namespace seq_ns ON seq_ns.oid = seq.relnamespace
                JOIN pg_catalog.pg_depend dependency
                  ON dependency.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
                 AND dependency.objid = seq.oid
                 AND dependency.refclassid = 'pg_catalog.pg_class'::pg_catalog.regclass
                 AND dependency.refobjsubid != 0
                 AND dependency.deptype IN ('a', 'i')
                JOIN pg_catalog.pg_class tbl ON tbl.oid = dependency.refobjid
                JOIN pg_catalog.pg_namespace tbl_ns ON tbl_ns.oid = tbl.relnamespace
                JOIN pg_catalog.pg_attribute attr
                  ON attr.attrelid = tbl.oid
                 AND attr.attnum = dependency.refobjsubid
                WHERE seq_ns.nspname = 'pgck_sequence_owner_acl'
                  AND seq.relname = 'owner_acl_seq'
                  AND seq.relkind = 'S'
                """;

        try (Connection connection = connector.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery(query)) {
            assertTrue(result.next(), "owned sequence catalog row is missing");
            var state = new MigrationState(
                    result.getString(1), result.getString(2), result.getString(3),
                    result.getString(4), result.getString(5), result.getString(6));
            assertFalse(result.next(), "owned sequence catalog row is duplicated");
            return state;
        }
    }

    private record MigrationState(String tableOwner, String sequenceOwner,
                                  String ownedBySchema, String ownedByTable,
                                  String ownedByColumn, String readerAcl) {
    }
}
