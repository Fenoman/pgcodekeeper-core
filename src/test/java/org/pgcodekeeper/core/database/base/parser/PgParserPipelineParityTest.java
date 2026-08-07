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
package org.pgcodekeeper.core.database.base.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionContext;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * End-to-end parity guard for the parser strategy and bounded parser pipeline.
 * The reference cell uses the full-LL parser, an unbounded eager parser queue
 * and sequential source/target loading.
 */
@Isolated("mutates the global parser strategy and parser max-pending property")
class PgParserPipelineParityTest {

    private static final String RESOURCE_ROOT =
            "/org/pgcodekeeper/core/it/loader/pg/";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();
    private final String originalMaxPending =
            System.getProperty(Consts.MAX_PENDING_TASKS);

    @AfterEach
    void restoreMaxPendingProperty() {
        if (originalMaxPending == null) {
            System.clearProperty(Consts.MAX_PENDING_TASKS);
        } else {
            System.setProperty(Consts.MAX_PENDING_TASKS, originalMaxPending);
        }
    }

    @Test
    void parserAndPipelineModesAreByteAndModelEquivalent()
            throws IOException, InterruptedException {
        ScenarioResult baseline = runScenario(
                TwoStageAntlrParse.Strategy.LL_ONLY, 0, false);

        assertAll("fixture contract",
                () -> assertFalse(baseline.comparison().sql().isBlank(),
                        "fixture must produce a non-empty migration"),
                () -> assertTrue(baseline.comparison().errors().isEmpty(),
                        baseline.comparison().errors()::toString),
                () -> assertFalse(baseline.comparison()
                        .oldLaunchers().isEmpty(),
                        "old launcher snapshot must be meaningful"),
                () -> assertFalse(baseline.comparison()
                        .newLaunchers().isEmpty(),
                        "new launcher snapshot must be meaningful"),
                () -> assertTrue(baseline.diagnosticSql().isEmpty(),
                        "identical error fixtures must not produce SQL"),
                () -> assertTrue(baseline.diagnostics().size() >= 4,
                        "error fixture must exercise both diagnostics on both sides"));

        for (TwoStageAntlrParse.Strategy strategy
                : TwoStageAntlrParse.Strategy.values()) {
            for (int maxPending : List.of(0, 1, 2)) {
                for (boolean parallelLoad : List.of(false, true)) {
                    String cell = strategy + "/maxPending=" + maxPending
                            + "/parallelLoad=" + parallelLoad;
                    ScenarioResult actual = runScenario(
                            strategy, maxPending, parallelLoad);

                    assertScenarioParity(
                            baseline, actual, parallelLoad, cell);
                    if (strategy == TwoStageAntlrParse.Strategy.TWO_STAGE) {
                        assertAll(cell,
                                () -> assertTrue(actual.sllSuccesses() > 0,
                                        "two-stage cell did not exercise SLL success"),
                                () -> assertTrue(actual.llFallbacks() > 0,
                                        "two-stage cell did not exercise LL fallback"));
                    } else {
                        assertAll(cell,
                                () -> assertEquals(0, actual.sllSuccesses()),
                                () -> assertEquals(0, actual.llFallbacks()));
                    }
                }
            }
        }
    }

    private ScenarioResult runScenario(TwoStageAntlrParse.Strategy strategy,
            int maxPending, boolean parallelLoad)
            throws IOException, InterruptedException {
        System.setProperty(Consts.MAX_PENDING_TASKS,
                Integer.toString(maxPending));
        long sllBefore = TwoStageAntlrParse.getSllSuccessCount();
        long fallbackBefore = TwoStageAntlrParse.getLlFallbackCount();
        TwoStageAntlrParse.setStrategyForTests(strategy);
        try {
            ComparisonResult comparison = compareValidFixture(parallelLoad);
            DiagnosticResult diagnostics = compareErrorFixture(parallelLoad);
            return new ScenarioResult(comparison, diagnostics.sql(),
                    diagnostics.errors(),
                    TwoStageAntlrParse.getSllSuccessCount() - sllBefore,
                    TwoStageAntlrParse.getLlFallbackCount() - fallbackBefore);
        } finally {
            TwoStageAntlrParse.setStrategyForTests(
                    TwoStageAntlrParse.Strategy.TWO_STAGE);
        }
    }

    private ComparisonResult compareValidFixture(boolean parallelLoad)
            throws IOException, InterruptedException {
        Path oldProject = resource("pipeline_parity/old");
        Path newProject = resource("pipeline_parity/new");

        var diffSettings = settings(parallelLoad);
        var oldLoader = new LauncherSnapshotLoader(
                provider.getProjectLoader(oldProject, diffSettings));
        var newLoader = new LauncherSnapshotLoader(
                provider.getProjectLoader(newProject, diffSettings));
        String sql = PgCodeKeeperApi.diff(
                provider, oldLoader, newLoader, diffSettings);
        IDatabase oldDb = oldLoader.getDatabase();
        IDatabase newDb = newLoader.getDatabase();

        return new ComparisonResult(oldDb, newDb, sql,
                diagnosticSnapshot(diffSettings.getErrors()),
                oldLoader.getLauncherSnapshot(),
                newLoader.getLauncherSnapshot(),
                dependencySnapshot(oldDb), dependencySnapshot(newDb),
                referenceSnapshot(oldDb), referenceSnapshot(newDb));
    }

    private DiagnosticResult compareErrorFixture(boolean parallelLoad)
            throws IOException, InterruptedException {
        Path project = resource("pipeline_errors");
        var settings = settings(parallelLoad);
        var oldLoader = provider.getProjectLoader(project, settings);
        var newLoader = provider.getProjectLoader(project, settings);

        String sql = PgCodeKeeperApi.diff(
                provider, oldLoader, newLoader, settings);

        return new DiagnosticResult(sql,
                diagnosticSnapshot(settings.getErrors()));
    }

    private Path resource(String relativePath) {
        return TestUtils.getFilePath(
                RESOURCE_ROOT + relativePath, getClass());
    }

    private static CoreSettings settings(boolean parallelLoad) {
        var settings = new CoreSettings();
        settings.setParallelLoad(parallelLoad);
        settings.setEnableFunctionBodiesDependencies(true);
        return settings;
    }

    private static void assertScenarioParity(ScenarioResult expected,
            ScenarioResult actual, boolean parallelLoad, String cell) {
        assertAll(cell,
                () -> assertEquals(expected.comparison().oldDb(),
                        actual.comparison().oldDb(), "old model"),
                () -> assertEquals(expected.comparison().newDb(),
                        actual.comparison().newDb(), "new model"),
                () -> assertEquals(expected.comparison().sql(),
                        actual.comparison().sql(), "migration bytes"),
                () -> assertEquals(expected.comparison().errors(),
                        actual.comparison().errors(), "comparison diagnostics"),
                () -> assertEquals(expected.comparison().oldLaunchers(),
                        actual.comparison().oldLaunchers(), "old launchers"),
                () -> assertEquals(expected.comparison().newLaunchers(),
                        actual.comparison().newLaunchers(), "new launchers"),
                () -> assertEquals(expected.comparison().oldDependencies(),
                        actual.comparison().oldDependencies(), "old dependencies"),
                () -> assertEquals(expected.comparison().newDependencies(),
                        actual.comparison().newDependencies(), "new dependencies"),
                () -> assertEquals(expected.comparison().oldReferences(),
                        actual.comparison().oldReferences(), "old references"),
                () -> assertEquals(expected.comparison().newReferences(),
                        actual.comparison().newReferences(), "new references"),
                () -> assertEquals(expected.diagnosticSql(),
                        actual.diagnosticSql(), "error-fixture migration bytes"),
                // Concurrent source/target loaders do not define a shared
                // publication order for diagnostics; their exact content and
                // multiplicity are the cross-mode contract.
                () -> assertEquals(canonicalDiagnostics(expected.diagnostics()),
                        canonicalDiagnostics(actual.diagnostics()),
                        "error-fixture diagnostic content"),
                () -> {
                    if (!parallelLoad) {
                        assertEquals(expected.diagnostics(),
                                actual.diagnostics(),
                                "sequential diagnostic order");
                    }
                });
    }

    private static List<String> launcherSnapshot(IDatabase db) {
        return db.getAnalysisLaunchers().stream()
                .map(PgParserPipelineParityTest::launcherKey)
                .toList();
    }

    private static String launcherKey(IAnalysisLauncher launcher) {
        var statement = launcher.getStmt();
        return launcher.getClass().getName() + '|'
                + statement.getStatementType() + '|'
                + statement.getQualifiedName();
    }

    private static Map<String, Set<ObjectReference>> dependencySnapshot(
            IDatabase db) {
        var result = new TreeMap<String, Set<ObjectReference>>();
        db.getDescendants().forEach(statement -> result.put(
                statement.getStatementType() + "|"
                        + statement.getQualifiedName(),
                Set.copyOf(statement.getDependencies())));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Set<ObjectLocation>> referenceSnapshot(
            IDatabase db) {
        var result = new TreeMap<String, Set<ObjectLocation>>();
        db.getObjReferences().forEach((file, locations) ->
                result.put(file, Set.copyOf(locations)));
        return Collections.unmodifiableMap(result);
    }

    private static List<String> diagnosticSnapshot(List<Object> errors) {
        var result = new ArrayList<String>(errors.size());
        for (Object error : errors) {
            if (error instanceof AntlrError antlrError) {
                String fileName = Path.of(antlrError.getFilePath())
                        .getFileName().toString();
                result.add(fileName + '|' + antlrError.getLineNumber() + '|'
                        + antlrError.getCharPositionInLine() + '|'
                        + antlrError.getStart() + '|'
                        + antlrError.getStop() + '|'
                        + antlrError.getText() + '|' + antlrError.getMsg());
            } else {
                result.add(error.toString());
            }
        }
        return List.copyOf(result);
    }

    private static List<String> canonicalDiagnostics(List<String> diagnostics) {
        return diagnostics.stream().sorted().toList();
    }

    private record ScenarioResult(
            ComparisonResult comparison,
            String diagnosticSql,
            List<String> diagnostics,
            long sllSuccesses,
            long llFallbacks) {
    }

    private record ComparisonResult(
            IDatabase oldDb,
            IDatabase newDb,
            String sql,
            List<String> errors,
            List<String> oldLaunchers,
            List<String> newLaunchers,
            Map<String, Set<ObjectReference>> oldDependencies,
            Map<String, Set<ObjectReference>> newDependencies,
            Map<String, Set<ObjectLocation>> oldReferences,
            Map<String, Set<ObjectLocation>> newReferences) {
    }

    private record DiagnosticResult(String sql, List<String> errors) {
    }

    /** Captures launchers in the same worker that performs the real load. */
    private static final class LauncherSnapshotLoader implements ILoader {

        private final ILoader delegate;
        private final AtomicReference<List<String>> launcherSnapshot =
                new AtomicReference<>();

        private LauncherSnapshotLoader(ILoader delegate) {
            this.delegate = delegate;
        }

        @Override
        public IDatabase load() throws IOException, InterruptedException {
            return delegate.load();
        }

        @Override
        public void preLoad() throws IOException, InterruptedException {
            delegate.preLoad();
        }

        @Override
        public void registerComparisonExtensions(
                ComparisonExtensionContext context)
                throws IOException, InterruptedException {
            delegate.registerComparisonExtensions(context);
        }

        @Override
        public IDatabase loadAndAnalyze()
                throws IOException, InterruptedException {
            IDatabase database = delegate.load();
            launcherSnapshot.set(PgParserPipelineParityTest
                    .launcherSnapshot(database));
            return delegate.loadAndAnalyze();
        }

        @Override
        public IDatabase getDatabase() {
            return delegate.getDatabase();
        }

        @Override
        public String getDatabaseName() {
            return delegate.getDatabaseName();
        }

        @Override
        public ISettings getSettings() {
            return delegate.getSettings();
        }

        @Override
        public List<Object> getErrors() {
            return delegate.getErrors();
        }

        @Override
        public void cancel() throws IOException {
            delegate.cancel();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private List<String> getLauncherSnapshot() {
            return Objects.requireNonNull(launcherSnapshot.get(),
                    "launcher snapshot was not captured");
        }
    }
}
