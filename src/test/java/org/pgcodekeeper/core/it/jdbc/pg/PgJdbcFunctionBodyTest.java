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

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.database.base.parser.AntlrError;
import org.pgcodekeeper.core.database.base.parser.FullAnalyze;
import org.pgcodekeeper.core.database.base.parser.ScriptParser;
import org.pgcodekeeper.core.database.base.parser.launcher.AbstractAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.jdbc.PgJdbcConnector;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.BodyType;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.ParseDiagnosticPolicy;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.testcontainer.TestContainerType;

@Isolated("mutates the shared PG16 testcontainer")
class PgJdbcFunctionBodyTest {

    private static final String FIXTURE = "pg_16_lazy_function_bodies.sql";

    private static final List<String> EXPECTED_ROUTINE_ORDER = List.of(
            "FUNCTION|public.lazy_sql(integer)",
            "FUNCTION|public.lazy_plpgsql(integer)",
            "FUNCTION|public.lazy_atomic(integer)",
            "FUNCTION|public.lazy_internal(integer)",
            "FUNCTION|public.lazy_bad_sql()",
            "FUNCTION|public.lazy_bad_plpgsql()");

    private static final List<String> EXPECTED_JDBC_LAUNCHER_ORDER = List.of(
            "FUNCTION|public.lazy_sql(integer)|SQL|REPORT",
            "FUNCTION|public.lazy_plpgsql(integer)|PLPGSQL|REPORT",
            "FUNCTION|public.lazy_atomic(integer)|FUNCTION_BODY|REPORT",
            "FUNCTION|public.lazy_bad_sql()|SQL|REPORT",
            "FUNCTION|public.lazy_bad_plpgsql()|PLPGSQL|REPORT");

    private static final List<String> EXPECTED_DUMP_LAUNCHER_ORDER = List.of(
            "FUNCTION|public.lazy_sql(integer)|SQL|REPORT",
            "FUNCTION|public.lazy_plpgsql(integer)|PLPGSQL|REPORT",
            "FUNCTION|public.lazy_atomic(integer)|FUNCTION_BODY|SUPPRESS_DUPLICATE",
            "FUNCTION|public.lazy_bad_sql()|SQL|REPORT",
            "FUNCTION|public.lazy_bad_plpgsql()|PLPGSQL|REPORT");

    // Lines 22 and 24 of the fixture: a body error already carried the position it
    // has in the file, and now it carries the file too instead of a routine label.
    private static final List<String> EXPECTED_DUMP_DIAGNOSTIC_ORDER = List.of(
            FIXTURE + "|22|24|extraneous input ')' expecting EOF, ';'",
            FIXTURE + "|24|34|extraneous input ')' expecting ';'");

    private static final List<String> EXPECTED_JDBC_DIAGNOSTIC_ORDER = List.of(
            "jdbc:/public/lazy_bad_sql|1|7|extraneous input ')' expecting EOF, ';'",
            "jdbc:/public/lazy_bad_plpgsql|1|13|extraneous input ')' expecting ';'");

    private static final List<String> EXPECTED_ROUTINE_DEPENDENCY_ORDER = List.of(
            "FUNCTION|public.lazy_sql(integer)->public (SCHEMA)",
            "FUNCTION|public.lazy_sql(integer)->public.lazy_dep (TABLE)",
            "FUNCTION|public.lazy_sql(integer)->public.lazy_dep.id (COLUMN)",
            "FUNCTION|public.lazy_plpgsql(integer)->public (SCHEMA)",
            "FUNCTION|public.lazy_plpgsql(integer)->public.lazy_dep (TABLE)",
            "FUNCTION|public.lazy_plpgsql(integer)->public.lazy_dep.id (COLUMN)",
            "FUNCTION|public.lazy_atomic(integer)->public (SCHEMA)",
            "FUNCTION|public.lazy_atomic(integer)->public.lazy_dep (TABLE)",
            "FUNCTION|public.lazy_atomic(integer)->public.lazy_dep.id (COLUMN)",
            "FUNCTION|public.lazy_internal(integer)->",
            "FUNCTION|public.lazy_bad_sql()->",
            "FUNCTION|public.lazy_bad_plpgsql()->");

    private static final List<String> EXPECTED_ROUTINE_REFERENCE_ORDER = List.of(
            "FUNCTION|public.lazy_sql(integer)->public (SCHEMA)",
            "FUNCTION|public.lazy_sql(integer)->public.lazy_dep (TABLE)",
            "FUNCTION|public.lazy_sql(integer)->public.lazy_dep.id (COLUMN)",
            "FUNCTION|public.lazy_sql(integer)->public.lazy_dep.id (COLUMN)",
            "FUNCTION|public.lazy_plpgsql(integer)->public (SCHEMA)",
            "FUNCTION|public.lazy_plpgsql(integer)->public.lazy_dep (TABLE)",
            "FUNCTION|public.lazy_plpgsql(integer)->public.lazy_dep.id (COLUMN)",
            "FUNCTION|public.lazy_plpgsql(integer)->public.lazy_dep.id (COLUMN)",
            "FUNCTION|public.lazy_atomic(integer)->public (SCHEMA)",
            "FUNCTION|public.lazy_atomic(integer)->public.lazy_dep (TABLE)",
            "FUNCTION|public.lazy_atomic(integer)->public.lazy_dep (TABLE)",
            "FUNCTION|public.lazy_atomic(integer)->public.lazy_dep (TABLE)",
            "FUNCTION|public.lazy_bad_sql()->",
            "FUNCTION|public.lazy_bad_plpgsql()->");

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @Test
    void jdbcFunctionBodiesStayCompactUntilFullAnalysisAndMatchDumpOracle() throws Exception {
        String url = TestContainerType.PG_16.getUrl();
        var connector = new PgJdbcConnector(url);
        Path fixture = TestUtils.getFilePath(FIXTURE, getClass());
        String script = Files.readString(fixture);
        var jdbcSettings = settings();

        cleanup(connector);
        try {
            var fixtureLoader = provider.getDumpLoader(
                    () -> new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                    FIXTURE, settings());
            new JdbcRunner(new NullMonitor()).runBatches(connector,
                    new ScriptParser(fixtureLoader, FIXTURE, script).batch(), null);

            var jdbcLoader = provider.getJdbcLoader(url, jdbcSettings);
            IDatabase db = jdbcLoader.load();
            List<IAnalysisLauncher> functionLaunchers = db.getAnalysisLaunchers().stream()
                    .filter(PgFuncProcAnalysisLauncher.class::isInstance)
                    .toList();

            assertEquals(EXPECTED_ROUTINE_ORDER, routineOrder(db));
            assertEquals(5, functionLaunchers.size(), () -> db.getDescendants()
                    .map(statement -> statement.getStatementType() + "|" + statement.getQualifiedName())
                    .sorted()
                    .toList().toString());
            Field contextField = AbstractAnalysisLauncher.class.getDeclaredField("ctx");
            contextField.setAccessible(true);
            Field locationField = AbstractAnalysisLauncher.class.getDeclaredField("location");
            locationField.setAccessible(true);
            for (IAnalysisLauncher launcher : functionLaunchers) {
                assertAll(
                        () -> assertNull(contextField.get(launcher),
                                launcher.getStmt().getQualifiedName()),
                        () -> assertEquals("jdbc:/public/" + launcher.getStmt().getBareName(),
                                locationField.get(launcher)));
            }
            assertEquals(EXPECTED_JDBC_LAUNCHER_ORDER, launcherOrder(functionLaunchers));

            var dumpSettings = settings();
            IDatabase expected = provider.getDumpLoader(fixture, dumpSettings).load();
            List<IAnalysisLauncher> expectedFunctionLaunchers = expected.getAnalysisLaunchers().stream()
                    .filter(PgFuncProcAnalysisLauncher.class::isInstance)
                    .toList();
            assertEquals(5, expectedFunctionLaunchers.size());
            assertAll(
                    () -> assertEquals(EXPECTED_ROUTINE_ORDER, routineOrder(expected)),
                    () -> assertEquals(EXPECTED_DUMP_LAUNCHER_ORDER,
                            launcherOrder(expectedFunctionLaunchers)));

            FullAnalyze.fullAnalyze(expected, dumpSettings.getErrors(), dumpSettings.getVersion());
            FullAnalyze.fullAnalyze(db, jdbcSettings.getErrors(), jdbcSettings.getVersion());

            assertAll(
                    () -> assertEquals("", PgCodeKeeperApi.diff(
                            provider, expected, db, comparisonSettings())),
                    () -> assertEquals(errorMessages(dumpSettings.getErrors()),
                            errorMessages(jdbcSettings.getErrors())),
                    () -> assertEquals(EXPECTED_DUMP_DIAGNOSTIC_ORDER,
                            diagnosticOrder(dumpSettings.getErrors())),
                    () -> assertEquals(EXPECTED_JDBC_DIAGNOSTIC_ORDER,
                            diagnosticOrder(jdbcSettings.getErrors())),
                    () -> assertEquals(dependencySnapshot(expected), dependencySnapshot(db)),
                    () -> assertEquals(EXPECTED_ROUTINE_DEPENDENCY_ORDER,
                            orderedRoutineDependencies(expected)),
                    () -> assertEquals(EXPECTED_ROUTINE_DEPENDENCY_ORDER,
                            orderedRoutineDependencies(db)),
                    () -> assertEquals(EXPECTED_ROUTINE_REFERENCE_ORDER,
                            orderedReferences(expectedFunctionLaunchers)),
                    () -> assertEquals(EXPECTED_ROUTINE_REFERENCE_ORDER,
                            orderedReferences(functionLaunchers)));
        } finally {
            cleanup(connector);
        }
    }

    private static CoreSettings settings() {
        var settings = new CoreSettings();
        settings.setEnableFunctionBodiesDependencies(true);
        settings.setIgnorePrivileges(true);
        return settings;
    }

    private static CoreSettings comparisonSettings() {
        var settings = settings();
        // The stock testcontainer has the bootstrap plpgsql extension, while
        // the focused fixture intentionally models only its public objects.
        settings.setAllowedTypes(Arrays.stream(DbObjType.values())
                .filter(type -> type != DbObjType.EXTENSION)
                .toList());
        return settings;
    }

    private static void cleanup(PgJdbcConnector connector) throws Exception {
        try (Connection connection = connector.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP FUNCTION IF EXISTS public.lazy_sql(integer)");
            statement.execute("DROP FUNCTION IF EXISTS public.lazy_plpgsql(integer)");
            statement.execute("DROP FUNCTION IF EXISTS public.lazy_atomic(integer)");
            statement.execute("DROP FUNCTION IF EXISTS public.lazy_internal(integer)");
            statement.execute("DROP FUNCTION IF EXISTS public.lazy_bad_sql()");
            statement.execute("DROP FUNCTION IF EXISTS public.lazy_bad_plpgsql()");
            statement.execute("DROP TABLE IF EXISTS public.lazy_dep");
        }
    }

    private static List<String> errorMessages(List<Object> errors) {
        return errors.stream()
                .map(error -> error instanceof AntlrError antlr ? antlr.getMsg() : error.toString())
                .toList();
    }

    private static List<String> routineOrder(IDatabase db) {
        return db.getDescendants()
                .filter(statement -> statement.getStatementType().in(
                        DbObjType.FUNCTION, DbObjType.PROCEDURE, DbObjType.AGGREGATE))
                .map(statement -> statement.getStatementType() + "|" + statement.getQualifiedName())
                .toList();
    }

    private static List<String> launcherOrder(List<IAnalysisLauncher> launchers)
            throws ReflectiveOperationException {
        Field bodyTypeField = PgFuncProcAnalysisLauncher.class.getDeclaredField("bodyType");
        bodyTypeField.setAccessible(true);
        Field diagnosticPolicyField = PgFuncProcAnalysisLauncher.class
                .getDeclaredField("diagnosticPolicy");
        diagnosticPolicyField.setAccessible(true);

        var result = new ArrayList<String>(launchers.size());
        for (IAnalysisLauncher launcher : launchers) {
            BodyType bodyType = (BodyType) bodyTypeField.get(launcher);
            ParseDiagnosticPolicy diagnosticPolicy =
                    (ParseDiagnosticPolicy) diagnosticPolicyField.get(launcher);
            result.add(launcher.getStmt().getStatementType() + "|"
                    + launcher.getStmt().getQualifiedName() + "|"
                    + bodyType + "|" + diagnosticPolicy);
        }
        return List.copyOf(result);
    }

    private static List<String> diagnosticOrder(List<Object> errors) {
        return errors.stream().map(error -> {
            if (!(error instanceof AntlrError antlr)) {
                return error.toString();
            }
            String location = antlr.getFilePath().startsWith("jdbc:")
                    ? antlr.getFilePath()
                    : Path.of(antlr.getFilePath()).getFileName().toString();
            return location + '|' + antlr.getLineNumber() + '|'
                    + antlr.getCharPositionInLine() + '|' + antlr.getMsg();
        }).toList();
    }

    private static Map<String, Set<ObjectReference>> dependencySnapshot(IDatabase db) {
        var result = new TreeMap<String, Set<ObjectReference>>();
        db.getDescendants()
                .filter(statement -> statement.getQualifiedName().equals("public")
                        || statement.getQualifiedName().startsWith("public."))
                .forEach(statement -> result.put(
                        statement.getStatementType() + "|" + statement.getQualifiedName(),
                        Set.copyOf(statement.getDependencies())));
        return Collections.unmodifiableMap(result);
    }

    private static List<String> orderedRoutineDependencies(IDatabase db) {
        var result = new ArrayList<String>();
        db.getDescendants()
                .filter(statement -> statement.getStatementType().in(
                        DbObjType.FUNCTION, DbObjType.PROCEDURE, DbObjType.AGGREGATE))
                .forEach(statement -> {
                    String prefix = statement.getStatementType() + "|"
                            + statement.getQualifiedName() + "->";
                    if (statement.getDependencies().isEmpty()) {
                        result.add(prefix);
                    } else {
                        statement.getDependencies().forEach(reference -> result.add(prefix + reference));
                    }
                });
        return List.copyOf(result);
    }

    private static List<String> orderedReferences(List<IAnalysisLauncher> launchers) {
        var result = new ArrayList<String>();
        for (IAnalysisLauncher launcher : launchers) {
            String prefix = launcher.getStmt().getStatementType() + "|"
                    + launcher.getStmt().getQualifiedName() + "->";
            if (launcher.getReferences().isEmpty()) {
                result.add(prefix);
            } else {
                launcher.getReferences().forEach(location -> result.add(prefix
                        + Objects.requireNonNull(location.getObjectReference(),
                                () -> "null body reference for "
                                        + launcher.getStmt().getQualifiedName())));
            }
        }
        return List.copyOf(result);
    }
}
