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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.database.base.parser.AntlrError;
import org.pgcodekeeper.core.database.base.parser.ScriptParser;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.jdbc.PgJdbcConnector;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.loader.PgProjectLoader;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyAnalysisStats;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.testcontainer.TestContainerType;

/**
 * End-to-end contract of {@code pgRoutineBodySkipMatchedAnalysis} on a real
 * server: by default matched late-bound bodies are not parsed on either
 * comparison side, every eligible old-side (database) body additionally skips
 * its analysis regardless of the match verdict and, when unmatched, is never
 * fetched at all. Project-side changed bodies keep their parse, dependencies
 * and diagnostics, and the documented default-behavior script difference
 * against the disable switch is the omission of collateral recreates of
 * late-bound functions in a drop cascade.
 */
@Isolated("mutates the shared PG16 testcontainer and the process-wide analysis counters")
class PgJdbcRoutineSkipMatchedAnalysisTest {

    private static final String SCHEMA = "skip_match";

    /** Byte-identical on both sides: a late-bound body reading the table. */
    private static final String MATCHED_BODY =
            " BEGIN RETURN (SELECT count(*) FROM skip_match.t1); END ";

    /** Server-side body of the changed function: valid, reads the table. */
    private static final String CHANGED_BODY_SERVER =
            " BEGIN RETURN (SELECT id FROM skip_match.t1 LIMIT 1); END ";

    /** Project-side body of the changed function: broken on purpose. */
    private static final String CHANGED_BODY_PROJECT = " BEGIN RETURN ); END ";

    /** Project-only body: valid, late-bound and absent from the server. */
    private static final String PROJECT_ONLY_BODY =
            " BEGIN RETURN (SELECT max(id) FROM skip_match.t1); END ";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @TempDir
    Path tempDir;

    @Test
    void skipMatchedAnalysisDropsOnlyCollateralRecreatesAndKeepsChangedDiagnostics()
            throws Exception {
        String url = TestContainerType.PG_16.getUrl();
        var connector = new PgJdbcConnector(url);
        cleanup(connector);
        try {
            applyFixture(connector, serverScript());
            Path project = writeProject();

            // rollback path: the disable switch restores full analysis
            PgRoutineBodyAnalysisStats.reset();
            var disabledSettings = comparisonSettings();
            disabledSettings.setPgRoutineBodySkipMatchedAnalysis(false);
            var disabledJdbcDb = new AtomicReference<IDatabase>();
            String scriptDisabled = coordinatedDiff(
                    connector, project, disabledSettings, disabledJdbcDb);
            long disabledSkipped = PgRoutineBodyAnalysisStats.getSkippedBodies();
            long disabledParsed = PgRoutineBodyAnalysisStats.getParsedBodies();
            long disabledSkippedOldSide = PgRoutineBodyAnalysisStats.getSkippedOldSideBodies();
            long disabledDivergent = PgRoutineBodyAnalysisStats.getDivergentUnfetchedBodies();

            // default behavior: skip is on without touching the setting
            PgRoutineBodyAnalysisStats.reset();
            var defaultSettings = comparisonSettings();
            var defaultJdbcDb = new AtomicReference<IDatabase>();
            String scriptDefault = coordinatedDiff(
                    connector, project, defaultSettings, defaultJdbcDb);
            long defaultSkipped = PgRoutineBodyAnalysisStats.getSkippedBodies();
            long defaultParsed = PgRoutineBodyAnalysisStats.getParsedBodies();
            long defaultSkippedBytes = PgRoutineBodyAnalysisStats.getSkippedBytes();
            long defaultSkippedOldSide = PgRoutineBodyAnalysisStats.getSkippedOldSideBodies();
            long defaultDivergent = PgRoutineBodyAnalysisStats.getDivergentUnfetchedBodies();
            long defaultDivergentBytes = PgRoutineBodyAnalysisStats.getDivergentUnfetchedBytes();

            long matchedBodyBytes = MATCHED_BODY.getBytes(StandardCharsets.UTF_8).length;
            long changedServerBodyBytes =
                    CHANGED_BODY_SERVER.getBytes(StandardCharsets.UTF_8).length;
            assertAll(
                    // both scripts recreate the partitioned table
                    () -> assertTrue(scriptDisabled.contains("DROP TABLE skip_match.t1"),
                            () -> scriptDisabled),
                    () -> assertTrue(scriptDefault.contains("DROP TABLE skip_match.t1"),
                            () -> scriptDefault),
                    () -> assertTrue(scriptDisabled.contains("PARTITION BY RANGE"),
                            () -> scriptDisabled),
                    () -> assertTrue(scriptDefault.contains("PARTITION BY RANGE"),
                            () -> scriptDefault),
                    // disabled: body dependencies force a collateral recreate
                    // of the unchanged late-bound function
                    () -> assertTrue(scriptDisabled.contains("f_matched"),
                            () -> "disable switch must recreate the dependent function:\n"
                                    + scriptDisabled),
                    // default, documented difference: the byte-identical
                    // late-bound function contributes no dependencies and is
                    // not collaterally recreated
                    () -> assertFalse(scriptDefault.contains("f_matched"),
                            () -> "default must not touch the matched function:\n"
                                    + scriptDefault),
                    // the changed function is always rebuilt from its new body
                    () -> assertTrue(scriptDisabled.contains("f_changed"),
                            () -> scriptDisabled),
                    () -> assertTrue(scriptDefault.contains("f_changed"),
                            () -> scriptDefault),
                    // a server-absent project-only function must never be
                    // mistaken for a matched body and skipped
                    () -> assertTrue(scriptDisabled.contains(
                            "CREATE OR REPLACE FUNCTION skip_match.f_project_only()"),
                            () -> "disable switch must create the project-only function:\n"
                                    + scriptDisabled),
                    () -> assertTrue(scriptDefault.contains(
                            "CREATE OR REPLACE FUNCTION skip_match.f_project_only()"),
                            () -> "default must create the project-only function:\n"
                                    + scriptDefault),
                    // counters: two sides x one matched body, plus the
                    // old-side (database) changed body skipped without a match
                    () -> assertEquals(0, disabledSkipped),
                    () -> assertEquals(0, disabledSkippedOldSide),
                    () -> assertEquals(0, disabledDivergent),
                    () -> assertEquals(5, disabledParsed),
                    () -> assertEquals(2, defaultSkipped),
                    () -> assertEquals(1, defaultSkippedOldSide),
                    // project-side changed and project-only bodies are parsed
                    () -> assertEquals(2, defaultParsed),
                    () -> assertEquals(2 * matchedBodyBytes, defaultSkippedBytes),
                    // the unmatched old-side body is additionally never fetched
                    () -> assertEquals(1, defaultDivergent),
                    () -> assertEquals(changedServerBodyBytes, defaultDivergentBytes),
                    // error-reporting parity for the broken changed body
                    () -> assertEquals(diagnostics(disabledSettings),
                            diagnostics(defaultSettings)),
                    () -> assertEquals(1, diagnostics(defaultSettings).size(),
                            () -> diagnostics(defaultSettings).toString()),
                    // deps absent for every skipped database body, present with
                    // the disable switch
                    () -> assertEquals(Set.of(), routineDependencies(
                            defaultJdbcDb.get(), "f_matched")),
                    () -> assertEquals(Set.of(), routineDependencies(
                            defaultJdbcDb.get(), "f_changed")),
                    () -> assertFalse(routineDependencies(
                            disabledJdbcDb.get(), "f_matched").isEmpty()),
                    () -> assertFalse(routineDependencies(
                            disabledJdbcDb.get(), "f_changed").isEmpty()));
        } finally {
            cleanup(connector);
        }
    }

    private String coordinatedDiff(PgJdbcConnector connector, Path project,
            CoreSettings settings, AtomicReference<IDatabase> jdbcDbSink) throws Exception {
        ILoaderFactory projectFactory =
                sideSettings -> new PgProjectLoader(project, sideSettings);
        ILoaderFactory jdbcFactory = sideSettings ->
                new PgJdbcLoader(connector, Consts.UTC, sideSettings) {
                    @Override
                    public org.pgcodekeeper.core.database.pg.schema.PgDatabase loadInternal()
                            throws java.io.IOException, InterruptedException {
                        var db = super.loadInternal();
                        jdbcDbSink.set(db);
                        return db;
                    }
                };
        return PgCodeKeeperApi.diff(provider,
                new ComparisonLoaderFactories(jdbcFactory, projectFactory), settings);
    }

    private static CoreSettings comparisonSettings() {
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(true);
        settings.setVersion(PgSupportedVersion.VERSION_16);
        settings.setAllowedTypes(Arrays.stream(DbObjType.values())
                .filter(type -> type != DbObjType.EXTENSION)
                .toList());
        settings.setPgRoutineBodyHashFirst(true);
        return settings;
    }

    private static String serverScript() {
        return """
                CREATE SCHEMA skip_match;

                CREATE TABLE skip_match.t1 (id integer, val text);

                CREATE FUNCTION skip_match.f_matched() RETURNS bigint
                    LANGUAGE plpgsql
                    AS $$%s$$;

                CREATE FUNCTION skip_match.f_changed() RETURNS integer
                    LANGUAGE plpgsql
                    AS $$%s$$;
                """.formatted(MATCHED_BODY, CHANGED_BODY_SERVER);
    }

    private static String projectTableScript() {
        return "CREATE TABLE skip_match.t1 (id integer, val text) PARTITION BY RANGE (id);\n";
    }

    private static String projectMatchedFunctionScript() {
        return """
                CREATE FUNCTION skip_match.f_matched() RETURNS bigint
                    LANGUAGE plpgsql
                    AS $$%s$$;
                """.formatted(MATCHED_BODY);
    }

    private static String projectChangedFunctionScript() {
        return """
                CREATE FUNCTION skip_match.f_changed() RETURNS integer
                    LANGUAGE plpgsql
                    AS $$%s$$;
                """.formatted(CHANGED_BODY_PROJECT);
    }

    private static String projectOnlyFunctionScript() {
        return """
                CREATE FUNCTION skip_match.f_project_only() RETURNS bigint
                    LANGUAGE plpgsql
                    AS $$%s$$;
                """.formatted(PROJECT_ONLY_BODY);
    }

    private void applyFixture(PgJdbcConnector connector, String script) throws Exception {
        var fixtureSettings = new CoreSettings();
        fixtureSettings.setIgnorePrivileges(true);
        var fixtureLoader = provider.getDumpLoader(
                () -> new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                "skip_match_fixture", fixtureSettings);
        new JdbcRunner(new NullMonitor()).runBatches(connector,
                new ScriptParser(fixtureLoader, "skip_match_fixture", script).batch(), null);
    }

    private Path writeProject() throws Exception {
        Path project = tempDir.resolve("skip-match-project");
        Path schemaDir = Files.createDirectories(
                project.resolve("SCHEMA").resolve(SCHEMA));
        Files.writeString(schemaDir.resolve(SCHEMA + ".sql"),
                "CREATE SCHEMA skip_match;\n", StandardCharsets.UTF_8);
        Path tableDir = Files.createDirectories(schemaDir.resolve("TABLE"));
        Files.writeString(tableDir.resolve("t1.sql"),
                projectTableScript(), StandardCharsets.UTF_8);
        Path functionDir = Files.createDirectories(schemaDir.resolve("FUNCTION"));
        Files.writeString(functionDir.resolve("f_matched.sql"),
                projectMatchedFunctionScript(), StandardCharsets.UTF_8);
        Files.writeString(functionDir.resolve("f_changed.sql"),
                projectChangedFunctionScript(), StandardCharsets.UTF_8);
        Files.writeString(functionDir.resolve("f_project_only.sql"),
                projectOnlyFunctionScript(), StandardCharsets.UTF_8);
        return project;
    }

    private static List<String> diagnostics(CoreSettings settings) {
        return settings.getErrors().stream()
                .map(error -> error instanceof AntlrError antlr
                        ? antlr.getLineNumber() + "|" + antlr.getCharPositionInLine()
                                + "|" + antlr.getMsg()
                        : error.toString())
                .toList();
    }

    private static Set<String> routineDependencies(IDatabase db, String functionName) {
        IStatement function = db.getDescendants()
                .filter(statement -> statement.getStatementType() == DbObjType.FUNCTION)
                .filter(statement -> statement.getName().startsWith(functionName))
                .findFirst()
                .orElseThrow();
        return function.getDependencies().stream()
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void cleanup(PgJdbcConnector connector) throws Exception {
        try (Connection connection = connector.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
