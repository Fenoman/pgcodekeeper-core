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
package org.pgcodekeeper.core.it.loader.pg;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.parser.AntlrError;
import org.pgcodekeeper.core.database.base.parser.launcher.AbstractAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.settings.CoreSettings;

@Isolated("mutates the parser max-pending system property")
class PgPipelineParityTest {

    private static final List<String> EXPECTED_SERIAL_ERROR_ORDER = List.of(
            "bad_one.sql|4|0|mismatched input ')' expecting Operator, Number, Identifier, String",
            "bad_view.sql|2|14|mismatched input 'public' expecting EOF, ';'",
            "bad_one.sql|4|0|mismatched input ')' expecting Operator, Number, Identifier, String",
            "bad_view.sql|2|14|mismatched input 'public' expecting EOF, ';'");

    private static final List<String> EXPECTED_LAUNCHER_ORDER = List.of(
            "org.pgcodekeeper.core.database.pg.parser.launcher.PgConstraintAnalysisLauncher"
                    + "|CONSTRAINT|public.child.child_payload_nn",
            "org.pgcodekeeper.core.database.pg.parser.launcher.PgViewAnalysisLauncher"
                    + "|VIEW|public.child_view",
            "org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher"
                    + "|FUNCTION|public.child_value(integer)",
            "org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher"
                    + "|FUNCTION|public.parent_exists(integer)",
            "org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher"
                    + "|FUNCTION|public.parent_payload(integer)",
            "org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher"
                    + "|FUNCTION|public.parent_payload_atomic(integer)");

    private final PgDatabaseProvider provider = new PgDatabaseProvider();
    private String originalMaxPending;

    @BeforeEach
    void rememberMaxPendingProperty() {
        originalMaxPending = System.getProperty(Consts.MAX_PENDING_TASKS);
    }

    @AfterEach
    void restoreMaxPendingProperty() {
        if (originalMaxPending == null) {
            System.clearProperty(Consts.MAX_PENDING_TASKS);
        } else {
            System.setProperty(Consts.MAX_PENDING_TASKS, originalMaxPending);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void boundedAndUnboundedComparisonsAreByteAndModelEquivalent(boolean parallelLoad)
            throws IOException, InterruptedException {
        ComparisonResult unbounded = compareValidFixture(0, parallelLoad);
        ComparisonResult serial = compareValidFixture(1, parallelLoad);
        ComparisonResult concurrent = compareValidFixture(2, parallelLoad);

        assertFalse(unbounded.sql().isBlank(), "fixture must produce a non-empty migration");
        assertComparisonParity(unbounded, serial);
        assertComparisonParity(unbounded, concurrent);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void boundedAndUnboundedErrorContentIsEquivalent(boolean parallelLoad)
            throws IOException, InterruptedException {
        ErrorResult unbounded = compareErrorFixture(0, parallelLoad);
        ErrorResult serial = compareErrorFixture(1, parallelLoad);
        ErrorResult concurrent = compareErrorFixture(2, parallelLoad);

        assertTrue(unbounded.errors().size() >= 4, "fixture must report both controlled errors for both models");
        assertEquals(errorMultiset(unbounded.errors()), errorMultiset(serial.errors()));
        assertEquals(errorMultiset(unbounded.errors()), errorMultiset(concurrent.errors()));
        assertEquals(unbounded.sql(), serial.sql());
        assertEquals(unbounded.sql(), concurrent.sql());

        if (!parallelLoad) {
            assertEquals(EXPECTED_SERIAL_ERROR_ORDER, serial.errors());
            assertEquals(EXPECTED_SERIAL_ERROR_ORDER, concurrent.errors());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void functionBodyLaunchersDeferParserContextsUntilFullAnalysis(int maxPending)
            throws IOException, InterruptedException, ReflectiveOperationException {
        System.setProperty(Consts.MAX_PENDING_TASKS, Integer.toString(maxPending));
        Path project = TestUtils.getFilePath("pipeline_parity/new", getClass());
        IDatabase db = provider.getProjectLoader(project, settings(false)).load();

        assertEquals(EXPECTED_LAUNCHER_ORDER, launcherSnapshot(db));

        List<IAnalysisLauncher> functionLaunchers = db.getAnalysisLaunchers().stream()
                .filter(PgFuncProcAnalysisLauncher.class::isInstance)
                .toList();

        assertEquals(4, functionLaunchers.size(), "fixture must cover SQL, PL/pgSQL and BEGIN ATOMIC bodies");
        Field contextField = AbstractAnalysisLauncher.class.getDeclaredField("ctx");
        contextField.setAccessible(true);
        for (IAnalysisLauncher launcher : functionLaunchers) {
            assertNull(contextField.get(launcher),
                    () -> "function launcher retained parsed context for " + launcher.getStmt().getQualifiedName());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void deferredFunctionParserPreservesNestedErrorLocation(int maxPending)
            throws IOException, InterruptedException {
        System.setProperty(Consts.MAX_PENDING_TASKS, Integer.toString(maxPending));
        Path project = TestUtils.getFilePath("pipeline_function_errors", getClass());
        var settings = settings(false);

        provider.getProjectLoader(project, settings).loadAndAnalyze();

        // A quoted body is reparsed after the enclosing statement, but its errors
        // address the same file: bad_body.sql, not a synthetic routine label.
        List<String> expected = List.of(
                "bad_atomic.sql|5|12|no viable alternative at input '(;'",
                "bad_body.sql|6|12|no viable alternative at input '(;'");
        List<String> actual = errorSnapshot(settings.getErrors());
        assertEquals(errorMultiset(expected), errorMultiset(actual));
        assertEquals(expected, actual);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void deferredFunctionBodyErrorAddressesAnExistingProjectFile(int maxPending)
            throws IOException, InterruptedException {
        System.setProperty(Consts.MAX_PENDING_TASKS, Integer.toString(maxPending));
        Path project = TestUtils.getFilePath("pipeline_function_errors", getClass());
        var settings = settings(false);

        provider.getProjectLoader(project, settings).loadAndAnalyze();

        // The editor turns an error into a marker by resolving its file path back
        // to a workspace file, so an error whose path is not a file is invisible.
        // A quoted routine body is the only place the reparse could lose that
        // address, so assert it on the body error specifically.
        Path body = project.resolve(Path.of("SCHEMA", "public", "FUNCTION", "bad_body.sql"));
        List<Path> bodyErrorPaths = settings.getErrors().stream()
                .filter(AntlrError.class::isInstance)
                .map(AntlrError.class::cast)
                .map(AntlrError::getFilePath)
                .map(Path::of)
                .filter(path -> path.endsWith(Path.of("FUNCTION", "bad_body.sql")))
                .toList();

        assertEquals(1, bodyErrorPaths.size(),
                () -> "quoted body error must address bad_body.sql, got "
                        + errorSnapshot(settings.getErrors()));
        Path reported = bodyErrorPaths.get(0);
        assertTrue(reported.isAbsolute(), () -> reported + " must be an absolute path");
        assertTrue(Files.isRegularFile(reported), () -> reported + " must be an existing file");
        assertEquals(body.toRealPath(), reported.toRealPath(),
                "body error must address the very file the body was read from");
    }

    private ComparisonResult compareValidFixture(int maxPending, boolean parallelLoad)
            throws IOException, InterruptedException {
        System.setProperty(Consts.MAX_PENDING_TASKS, Integer.toString(maxPending));
        Path oldProject = TestUtils.getFilePath("pipeline_parity/old", getClass());
        Path newProject = TestUtils.getFilePath("pipeline_parity/new", getClass());

        var preSettings = settings(false);
        IDatabase oldBeforeAnalyze = provider.getProjectLoader(oldProject, preSettings).load();
        IDatabase newBeforeAnalyze = provider.getProjectLoader(newProject, preSettings).load();
        List<String> oldLaunchers = launcherSnapshot(oldBeforeAnalyze);
        List<String> newLaunchers = launcherSnapshot(newBeforeAnalyze);
        List<String> preErrors = errorSnapshot(preSettings.getErrors());

        var diffSettings = settings(parallelLoad);
        var oldLoader = provider.getProjectLoader(oldProject, diffSettings);
        var newLoader = provider.getProjectLoader(newProject, diffSettings);
        String sql = PgCodeKeeperApi.diff(provider, oldLoader, newLoader, diffSettings);
        IDatabase oldDb = oldLoader.getDatabase();
        IDatabase newDb = newLoader.getDatabase();

        return new ComparisonResult(oldDb, newDb, sql, errorSnapshot(diffSettings.getErrors()), preErrors,
                oldLaunchers, newLaunchers,
                dependencySnapshot(oldDb), dependencySnapshot(newDb),
                referenceSnapshot(oldDb), referenceSnapshot(newDb));
    }

    private ErrorResult compareErrorFixture(int maxPending, boolean parallelLoad)
            throws IOException, InterruptedException {
        System.setProperty(Consts.MAX_PENDING_TASKS, Integer.toString(maxPending));
        Path project = TestUtils.getFilePath("pipeline_errors", getClass());
        var settings = settings(parallelLoad);
        var oldLoader = provider.getProjectLoader(project, settings);
        var newLoader = provider.getProjectLoader(project, settings);

        String sql = PgCodeKeeperApi.diff(provider, oldLoader, newLoader, settings);

        return new ErrorResult(sql, errorSnapshot(settings.getErrors()));
    }

    private static CoreSettings settings(boolean parallelLoad) {
        var settings = new CoreSettings();
        settings.setParallelLoad(parallelLoad);
        settings.setEnableFunctionBodiesDependencies(true);
        return settings;
    }

    private static void assertComparisonParity(ComparisonResult expected, ComparisonResult actual) {
        assertAll(
                () -> assertEquals(expected.oldDb(), actual.oldDb()),
                () -> assertEquals(expected.newDb(), actual.newDb()),
                () -> assertTrue(expected.errors().isEmpty(), expected.errors().toString()),
                () -> assertTrue(actual.errors().isEmpty(), actual.errors().toString()),
                () -> assertTrue(expected.preErrors().isEmpty(), expected.preErrors().toString()),
                () -> assertTrue(actual.preErrors().isEmpty(), actual.preErrors().toString()),
                () -> assertEquals(expected.oldLaunchers(), actual.oldLaunchers()),
                () -> assertEquals(expected.newLaunchers(), actual.newLaunchers()),
                () -> assertEquals(expected.oldDependencies(), actual.oldDependencies()),
                () -> assertEquals(expected.newDependencies(), actual.newDependencies()),
                () -> assertEquals(expected.oldReferences(), actual.oldReferences()),
                () -> assertEquals(expected.newReferences(), actual.newReferences()),
                () -> assertEquals(expected.sql(), actual.sql()));
    }

    private static List<String> launcherSnapshot(IDatabase db) {
        return db.getAnalysisLaunchers().stream()
                .map(PgPipelineParityTest::launcherKey)
                .toList();
    }

    private static String launcherKey(IAnalysisLauncher launcher) {
        var statement = launcher.getStmt();
        return launcher.getClass().getName() + '|' + statement.getStatementType() + '|'
                + statement.getQualifiedName();
    }

    private static Map<String, Set<ObjectReference>> dependencySnapshot(IDatabase db) {
        var result = new TreeMap<String, Set<ObjectReference>>();
        db.getDescendants().forEach(statement -> result.put(
                statement.getStatementType() + "|" + statement.getQualifiedName(),
                Set.copyOf(statement.getDependencies())));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Set<ObjectLocation>> referenceSnapshot(IDatabase db) {
        var result = new TreeMap<String, Set<ObjectLocation>>();
        db.getObjReferences().forEach((file, locations) -> result.put(file, Set.copyOf(locations)));
        return Collections.unmodifiableMap(result);
    }

    private static List<String> errorSnapshot(List<Object> errors) {
        var result = new ArrayList<String>(errors.size());
        for (Object error : errors) {
            if (error instanceof AntlrError antlrError) {
                String fileName = Path.of(antlrError.getFilePath()).getFileName().toString();
                result.add(fileName + '|' + antlrError.getLineNumber() + '|'
                        + antlrError.getCharPositionInLine() + '|' + antlrError.getMsg());
            } else {
                result.add(error.toString());
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, Long> errorMultiset(List<String> errors) {
        return errors.stream().collect(Collectors.groupingBy(
                Function.identity(), TreeMap::new, Collectors.counting()));
    }

    private record ComparisonResult(
            IDatabase oldDb,
            IDatabase newDb,
            String sql,
            List<String> errors,
            List<String> preErrors,
            List<String> oldLaunchers,
            List<String> newLaunchers,
            Map<String, Set<ObjectReference>> oldDependencies,
            Map<String, Set<ObjectReference>> newDependencies,
            Map<String, Set<ObjectLocation>> oldReferences,
            Map<String, Set<ObjectLocation>> newReferences) {
    }

    private record ErrorResult(String sql, List<String> errors) {
    }
}
