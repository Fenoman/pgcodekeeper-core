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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.database.base.parser.ScriptParser;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.jdbc.PgJdbcConnector;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;
import org.pgcodekeeper.core.model.graph.NotAllowedObjectException;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.testcontainer.TestContainerType;

@Isolated("mutates the shared PG16 and PG18 testcontainers")
@ResourceLock(AbstractPgGpJdbcLoaderTest.SHARED_PG_TEST_DATABASE)
class PgSequenceOwnedBySurvivalMigrationJdbcTest {

    private static final String ORIGINAL_FIXTURE = "pg_sequence_survival_original.sql";
    private static final String TARGET_FIXTURE = "pg_sequence_survival_target.sql";
    private static final String IGNORE_SCHEMAS =
            "pg_sequence_survival.pgcodekeeperignoreschema";
    private static final String IGNORE_OBJECTS =
            "pg_sequence_survival.pgcodekeeperignore";

    private static final String SCHEMA = "pgck_sequence_survival";
    private static final String MOVE_SEQUENCE = "move_seq";
    private static final String DETACH_SEQUENCE = "detach_seq";
    private static final String RECREATE_SEQUENCE = "recreate_seq";
    private static final Map<String, Long> SEQUENCE_VALUES = Map.of(
            MOVE_SEQUENCE, 4_101L,
            DETACH_SEQUENCE, 4_201L,
            RECREATE_SEQUENCE, 4_301L);

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = TestContainerType.class, names = {"PG_16", "PG_18"})
    void ownedSequencesSurviveColumnDropsAndConverge(TestContainerType type)
            throws Exception {
        var connector = new PgJdbcConnector(type.getUrl());
        try {
            cleanup(connector);
            runScript(connector, ORIGINAL_FIXTURE, readFixture(ORIGINAL_FIXTURE), type);
            setSequenceValues(connector);
            MigrationState baseline = readMigrationState(connector);

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
            runScript(connector, "generated sequence survival migration", migration, type);

            MigrationState state = readMigrationState(connector);
            assertAll(
                    () -> assertEquals(Set.of(MOVE_SEQUENCE, DETACH_SEQUENCE,
                            RECREATE_SEQUENCE), state.sequences().keySet()),
                    () -> assertEquals(new OwnedBy(SCHEMA, "b", "id"),
                            state.sequences().get(MOVE_SEQUENCE).ownedBy()),
                    () -> assertEquals(new OwnedBy(null, null, null),
                            state.sequences().get(DETACH_SEQUENCE).ownedBy()),
                    () -> assertEquals(new OwnedBy(SCHEMA, "recreate_table", "id"),
                            state.sequences().get(RECREATE_SEQUENCE).ownedBy()),
                    () -> assertSequenceIdentityAndValues(baseline, state),
                    () -> assertEquals(0, state.remainingDroppedColumns()));

            CoreSettings comparisonSettings = settings(type);
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

    @ParameterizedTest(name = "missing owners {0}")
    @EnumSource(value = TestContainerType.class, names = {"PG_16", "PG_18"})
    void missingOwnerMetadataFailsClosedBeforeTableRecreate(TestContainerType type)
            throws Exception {
        var connector = new PgJdbcConnector(type.getUrl());
        try {
            cleanup(connector);
            runScript(connector, ORIGINAL_FIXTURE, readFixture(ORIGINAL_FIXTURE), type);

            CoreSettings ignoredOwners = settings(type);
            ignoredOwners.setIgnorePrivileges(true);
            IDatabase actualDatabase = provider.getJdbcLoader(
                    type.getUrl(), ignoredOwners).loadAndAnalyze();
            IDatabase targetDatabase = provider.getDumpLoader(
                    TestUtils.getFilePath(TARGET_FIXTURE, getClass()),
                    ignoredOwners).loadAndAnalyze();

            var sequence = (PgSequence) actualDatabase.getStatement(new ObjectReference(
                    SCHEMA, RECREATE_SEQUENCE, DbObjType.SEQUENCE));
            assertNull(sequence.getOwner(), "JDBC owner metadata was not ignored");

            var failure = assertThrows(NotAllowedObjectException.class,
                    () -> PgCodeKeeperApi.diff(
                            provider, actualDatabase, targetDatabase, ignoredOwners));
            assertEquals("Owned sequence pgck_sequence_survival.recreate_seq cannot be "
                    + "attached to table pgck_sequence_survival.recreate_table because "
                    + "matching owner metadata is unavailable.", failure.getMessage());
        } finally {
            cleanup(connector);
        }
    }

    private static void assertMigrationOrder(String migration) {
        int moveDetach = migration.indexOf(
                "ALTER SEQUENCE pgck_sequence_survival.move_seq\n\tOWNED BY NONE;");
        int detachDetach = migration.indexOf(
                "ALTER SEQUENCE pgck_sequence_survival.detach_seq\n\tOWNED BY NONE;");
        int moveColumnDrop = migration.indexOf(
                "ALTER TABLE ONLY pgck_sequence_survival.a\n\tDROP COLUMN move_id;");
        int detachColumnDrop = migration.indexOf(
                "ALTER TABLE ONLY pgck_sequence_survival.a\n\tDROP COLUMN detach_id;");
        int moveAttach = migration.indexOf(
                "ALTER SEQUENCE pgck_sequence_survival.move_seq\n"
                        + "\tOWNED BY pgck_sequence_survival.b.id;");
        int recreateDetach = migration.indexOf(
                "ALTER SEQUENCE pgck_sequence_survival.recreate_seq\n\tOWNED BY NONE;");
        int recreateDrop = migration.indexOf(
                "DROP TABLE pgck_sequence_survival.recreate_table;");
        int recreateCreate = migration.indexOf(
                "CREATE TABLE pgck_sequence_survival.recreate_table");
        int recreateOwner = migration.indexOf(
                "ALTER TABLE pgck_sequence_survival.recreate_table OWNER TO test;");
        int recreateAttach = migration.indexOf(
                "ALTER SEQUENCE pgck_sequence_survival.recreate_seq\n"
                        + "\tOWNED BY pgck_sequence_survival.recreate_table.id;");

        assertAll(
                () -> assertTrue(moveDetach >= 0 && moveDetach < moveColumnDrop, migration),
                () -> assertTrue(detachDetach >= 0 && detachDetach < detachColumnDrop, migration),
                () -> assertTrue(moveColumnDrop >= 0 && detachColumnDrop >= 0
                        && moveAttach > moveColumnDrop && moveAttach > detachColumnDrop, migration),
                () -> assertTrue(recreateDetach >= 0 && recreateDetach < recreateDrop, migration),
                () -> assertTrue(recreateDrop >= 0 && recreateDrop < recreateCreate
                        && recreateCreate < recreateOwner && recreateOwner < recreateAttach,
                        migration),
                () -> assertFalse(migration.contains(
                        "DROP SEQUENCE pgck_sequence_survival.recreate_seq"), migration),
                () -> assertFalse(migration.contains(
                        "CREATE SEQUENCE pgck_sequence_survival.recreate_seq"), migration));
    }

    private static void assertSequenceIdentityAndValues(MigrationState baseline,
                                                        MigrationState actual) {
        for (var entry : SEQUENCE_VALUES.entrySet()) {
            SequenceState before = baseline.sequences().get(entry.getKey());
            SequenceState after = actual.sequences().get(entry.getKey());
            assertEquals(before.oid(), after.oid(), entry.getKey() + " OID changed");
            assertEquals(entry.getValue(), after.lastValue(),
                    entry.getKey() + " last_value changed");
        }
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
                fixture, PgSequenceOwnedBySurvivalMigrationJdbcTest.class),
                StandardCharsets.UTF_8);
    }

    private static void cleanup(PgJdbcConnector connector) throws Exception {
        try (Connection connection = connector.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private static void setSequenceValues(PgJdbcConnector connector) throws Exception {
        try (Connection connection = connector.getConnection();
                var statement = connection.createStatement()) {
            for (var entry : SEQUENCE_VALUES.entrySet()) {
                statement.execute("SELECT pg_catalog.setval('" + SCHEMA + '.'
                        + entry.getKey() + "'::pg_catalog.regclass, "
                        + entry.getValue() + ", true)");
            }
        }
    }

    private static MigrationState readMigrationState(PgJdbcConnector connector)
            throws Exception {
        String dependencyQuery = """
                SELECT seq.relname, seq.oid, tbl_ns.nspname, tbl.relname, attr.attname,
                       seq_state.last_value
                FROM pg_catalog.pg_class seq
                JOIN pg_catalog.pg_namespace seq_ns ON seq_ns.oid = seq.relnamespace
                LEFT JOIN pg_catalog.pg_sequences seq_state
                  ON seq_state.schemaname = seq_ns.nspname
                 AND seq_state.sequencename = seq.relname
                LEFT JOIN pg_catalog.pg_depend dependency
                  ON dependency.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
                 AND dependency.objid = seq.oid
                 AND dependency.refclassid = 'pg_catalog.pg_class'::pg_catalog.regclass
                 AND dependency.refobjsubid != 0
                 AND dependency.deptype IN ('a', 'i')
                LEFT JOIN pg_catalog.pg_class tbl ON tbl.oid = dependency.refobjid
                LEFT JOIN pg_catalog.pg_namespace tbl_ns ON tbl_ns.oid = tbl.relnamespace
                LEFT JOIN pg_catalog.pg_attribute attr
                  ON attr.attrelid = tbl.oid
                 AND attr.attnum = dependency.refobjsubid
                WHERE seq_ns.nspname = 'pgck_sequence_survival'
                  AND seq.relname IN ('move_seq', 'detach_seq', 'recreate_seq')
                  AND seq.relkind = 'S'
                ORDER BY seq.relname
                """;
        String remainingColumnsQuery = """
                SELECT pg_catalog.count(*)
                FROM pg_catalog.pg_attribute attr
                JOIN pg_catalog.pg_class tbl ON tbl.oid = attr.attrelid
                JOIN pg_catalog.pg_namespace ns ON ns.oid = tbl.relnamespace
                WHERE ns.nspname = 'pgck_sequence_survival'
                  AND tbl.relname = 'a'
                  AND attr.attname IN ('move_id', 'detach_id')
                  AND NOT attr.attisdropped
                """;

        try (Connection connection = connector.getConnection();
                var dependencyStatement = connection.createStatement();
                var dependencyResult = dependencyStatement.executeQuery(dependencyQuery)) {
            Map<String, SequenceState> sequences = new HashMap<>();
            while (dependencyResult.next()) {
                sequences.put(dependencyResult.getString(1),
                        new SequenceState(dependencyResult.getLong(2),
                                new OwnedBy(dependencyResult.getString(3),
                                        dependencyResult.getString(4),
                                        dependencyResult.getString(5)),
                                dependencyResult.getLong(6)));
            }

            try (var columnStatement = connection.createStatement();
                    var columnResult = columnStatement.executeQuery(remainingColumnsQuery)) {
                assertTrue(columnResult.next(), "column count row is missing");
                int remainingColumns = columnResult.getInt(1);
                assertFalse(columnResult.next(), "column count row is duplicated");
                return new MigrationState(sequences, remainingColumns);
            }
        }
    }

    private record MigrationState(Map<String, SequenceState> sequences,
                                  int remainingDroppedColumns) {
    }

    private record SequenceState(long oid, OwnedBy ownedBy, long lastValue) {
    }

    private record OwnedBy(String schema, String table, String column) {
    }
}
