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
package org.pgcodekeeper.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.script.IScriptBuilder;
import org.pgcodekeeper.core.database.base.project.AbstractWorkDirs;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.project.PgWorkDirs;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

class PgCodeKeeperApiTest {

    private static final String ORIGINAL = "_original.sql";
    private static final String NEW = "_new.sql";
    private static final String DIFF = "_diff.sql";
    private static final String OLD_DIAGNOSTIC = "OLD diagnostic";
    private static final String NEW_DIAGNOSTIC = "NEW diagnostic";

    private static final String PUBLIC_DIRECTORY = "SCHEMA/public/";
    private static final String TABLES_DIRECTORY = PUBLIC_DIRECTORY + "TABLE/";

    private ISettings settings;
    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @BeforeEach
    void initSettings() {
        settings = new CoreSettings();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "test_diff"
    })
    void diffTest(String testName) throws IOException, InterruptedException {
        var oldDbLoader = provider.getDumpLoader(getFilePath(testName + ORIGINAL), settings);
        var newDbLoader = provider.getDumpLoader(getFilePath(testName + NEW), settings);
        var expectedDiff = getExpectedDiff(testName);

        String actual = PgCodeKeeperApi.diff(provider, oldDbLoader, newDbLoader, settings);

        TestUtils.assertIgnoreNewLines(expectedDiff, actual);
        TestUtils.assertErrors(settings.getErrors());
    }


    @ParameterizedTest
    @CsvSource({
            "test_diff, false",
            "test_diff, true"})
    void loaderTest(String testName, boolean parallelLoad) throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        settings.setParallelLoad(parallelLoad);

        var oldDbLoader = provider.getDumpLoader(getFilePath(testName + ORIGINAL), settings);
        var newDbLoader = provider.getDumpLoader(getFilePath(testName + NEW), settings);
        var expectedDiff = getExpectedDiff(testName);

        String actual = PgCodeKeeperApi.diff(provider, oldDbLoader, newDbLoader, settings);

        TestUtils.assertIgnoreNewLines(expectedDiff, actual);
        TestUtils.assertErrors(settings.getErrors());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "test_ignore"
    })
    void diffWithIgnoreListTest(String testName) throws IOException, InterruptedException {
        var oldDbLoader = provider.getDumpLoader(getFilePath(testName + ORIGINAL), settings);
        var newDbLoader = provider.getDumpLoader(getFilePath(testName + NEW), settings);
        var ignoreListPath = getFilePath("ignore.pgcodekeeperignore");
        var expectedDiff = getExpectedDiff(testName);

        settings.addIgnoreList(ignoreListPath);
        String actual = PgCodeKeeperApi.diff(provider, oldDbLoader, newDbLoader, settings);

        TestUtils.assertIgnoreNewLines(expectedDiff, actual);
        TestUtils.assertErrors(settings.getErrors());
    }

    @Test
    void factoryOverloadsUseExactFinalSettingsDiagnosticsAndProgress()
            throws IOException, InterruptedException {
        var diffTreeSettings = new AtomicReference<ISettings>();
        var renderedSettings = new AtomicReference<ISettings>();
        var diffCommonSettings = new AtomicReference<ISettings>();
        var diffOldSideSettings = new AtomicReference<ISettings>();
        var diffNewSideSettings = new AtomicReference<ISettings>();
        var diffProgress = new FactoryMonitorOracle();
        var diffCaller = new TreeTrackingSettings(diffTreeSettings);
        diffCaller.setMonitor(diffProgress);
        PgDatabaseProvider recordingProvider = new PgDatabaseProvider() {
            @Override
            public IScriptBuilder getScriptBuilder(ISettings finalSettings) {
                renderedSettings.set(finalSettings);
                return super.getScriptBuilder(finalSettings);
            }
        };
        var diffFactories = trackingDumpFactories(recordingProvider, "test_diff",
                diffCommonSettings, diffOldSideSettings, diffNewSideSettings,
                diffProgress);

        String script = PgCodeKeeperApi.diff(
                recordingProvider, diffFactories, diffCaller);

        ISettings finalSettings = renderedSettings.get();
        List<Object> expectedDiagnostics = List.of(OLD_DIAGNOSTIC, NEW_DIAGNOSTIC);
        assertSame(diffCommonSettings.get(), finalSettings);
        assertSame(finalSettings, diffTreeSettings.get());
        assertNotSame(diffCaller, finalSettings);
        assertNotSame(diffOldSideSettings.get(), finalSettings);
        assertNotSame(diffNewSideSettings.get(), finalSettings);
        assertTrue(finalSettings.isAddTransaction());
        assertTrue(finalSettings.isIgnoreColumnOrder());
        assertFalse(diffCaller.isAddTransaction());
        assertFalse(diffCaller.isIgnoreColumnOrder());
        assertTrue(script.contains("START TRANSACTION;"));
        assertTrue(script.contains("COMMIT TRANSACTION;"));
        assertEquals(expectedDiagnostics, diffCaller.getErrors());
        assertEquals(expectedDiagnostics, finalSettings.getErrors());
        assertSame(diffCaller.getVersion(), finalSettings.getVersion());
        assertSame(PgSupportedVersion.VERSION_14, finalSettings.getVersion());
        diffProgress.assertCompleted(70,
                Messages.PgCodeKeeperApi_building_script, 10);

        var createTreeSettings = new AtomicReference<ISettings>();
        var treeCommonSettings = new AtomicReference<ISettings>();
        var treeOldSideSettings = new AtomicReference<ISettings>();
        var treeNewSideSettings = new AtomicReference<ISettings>();
        var treeProgress = new FactoryMonitorOracle();
        var treeCaller = new TreeTrackingSettings(createTreeSettings);
        treeCaller.setMonitor(treeProgress);
        var treeFactories = trackingDumpFactories(recordingProvider, "test_diff",
                treeCommonSettings, treeOldSideSettings, treeNewSideSettings,
                treeProgress);

        var tree = PgCodeKeeperApi.createTree(treeFactories, treeCaller);

        assertFalse(tree.getChildren().isEmpty());
        assertSame(treeCommonSettings.get(), createTreeSettings.get());
        assertNotSame(treeCaller, createTreeSettings.get());
        assertNotSame(treeOldSideSettings.get(), createTreeSettings.get());
        assertNotSame(treeNewSideSettings.get(), createTreeSettings.get());
        assertTrue(createTreeSettings.get().isIgnoreColumnOrder());
        assertFalse(treeCaller.isIgnoreColumnOrder());
        assertEquals(expectedDiagnostics, treeCaller.getErrors());
        assertEquals(expectedDiagnostics, treeCommonSettings.get().getErrors());
        assertSame(PgSupportedVersion.VERSION_14, treeCaller.getVersion());
        assertSame(treeCaller.getVersion(), treeCommonSettings.get().getVersion());
        treeProgress.assertCompleted(65,
                Messages.PgCodeKeeperApi_creating_tree, 5);
    }

    @Test
    void factoryAndDirectLoaderPathsProduceIdenticalScript()
            throws IOException, InterruptedException {
        var directSettings = new CoreSettings();
        String directScript = PgCodeKeeperApi.diff(provider,
                provider.getDumpLoader(getFilePath("test_diff" + ORIGINAL), directSettings),
                provider.getDumpLoader(getFilePath("test_diff" + NEW), directSettings),
                directSettings);
        var factorySettings = new CoreSettings();

        String factoryScript = PgCodeKeeperApi.diff(provider,
                dumpFactories(provider, "test_diff"), factorySettings);

        assertEquals(directScript, factoryScript);
        assertEquals(directSettings.getErrors(), factorySettings.getErrors());
        // The coordinator publishes its derived final version; the direct dump path does not.
        assertNull(directSettings.getVersion());
        assertSame(PgSupportedVersion.VERSION_14, factorySettings.getVersion());
    }

    @Test
    void exportTest(@TempDir Path tempDir) throws IOException, InterruptedException {
        var dumpFileName = "test_export.sql";
        var loader = provider.getDumpLoader(getFilePath(dumpFileName), settings);
        var exportedTableFile = tempDir.resolve(TABLES_DIRECTORY + "test_table.sql");
        var expectedContent = getFileContent(dumpFileName);

        PgCodeKeeperApi.exportToProject(provider, null, loader, tempDir, settings);

        assertFileContent(exportedTableFile, expectedContent);
        TestUtils.assertErrors(settings.getErrors());
    }

    @Test
    void exportWithIgnoreListTest(@TempDir Path tempDir) throws IOException, InterruptedException {
        var loader = provider.getDumpLoader(getFilePath("test_export_with_ignore_list.sql"), settings);
        var exportedTableFile = tempDir.resolve(TABLES_DIRECTORY + "test_table.sql");
        var ignoredTableFile = tempDir.resolve(TABLES_DIRECTORY + "ignored_table.sql");
        var expectedContent = getFileContent("test_export_with_ignore_list_exported.sql");
        var ignoreListPath = getFilePath("test_export_with_ignore_list.pgcodekeeperignore");

        settings.addIgnoreList(ignoreListPath);
        PgCodeKeeperApi.exportToProject(provider, null, loader, tempDir, settings);

        assertFileContent(exportedTableFile, expectedContent);
        assertFalse(Files.exists(ignoredTableFile));
        TestUtils.assertErrors(settings.getErrors());
    }

    @Test
    void updateProjectTest(@TempDir Path tempDir) throws IOException, InterruptedException {
        // Setup project structure with initial tables
        setupUpdateProjectStructure(tempDir);
        var oldDbLoader = provider.getProjectLoader(tempDir, settings);
        var newDbLoader = provider.getDumpLoader(getFilePath("test_update_project_new_dump.sql"), settings);
        var expectedContent = getFileContent("test_update_project_new_dump.sql");

        Path tablesDir = tempDir.resolve(TABLES_DIRECTORY);
        Path firstTableFile = tablesDir.resolve("first_table.sql");
        Path secondTableFile = tablesDir.resolve("second_table.sql");

        PgCodeKeeperApi.exportToProject(provider, oldDbLoader, newDbLoader, tempDir, settings);

        // Verify first table was removed and second table was updated
        assertFalse(Files.exists(firstTableFile));
        assertFileContent(secondTableFile, expectedContent);
        TestUtils.assertErrors(settings.getErrors());
    }

    @Test
    void updateProjectWithIgnoreListTest(@TempDir Path tempDir) throws IOException, InterruptedException {
        // Setup project structure with initial tables
        setupUpdateProjectStructure(tempDir);
        var oldDbLoader = provider.getProjectLoader(tempDir, settings);
        var newDbLoader = provider.getDumpLoader(getFilePath("test_update_project_new_dump.sql"), settings);
        var ignoreListPath = getFilePath("test_update_project_ignore_list.pgcodekeeperignore");

        var expectedFirstTableContent = getFileContent("test_update_project_old_first_table.sql");
        var expectedSecondTableContent = getFileContent("test_update_project_new_dump.sql");

        Path tablesDir = tempDir.resolve(TABLES_DIRECTORY);
        Path firstTableFile = tablesDir.resolve("first_table.sql");
        Path secondTableFile = tablesDir.resolve("second_table.sql");

        settings.addIgnoreList(ignoreListPath);
        PgCodeKeeperApi.exportToProject(provider, oldDbLoader, newDbLoader, tempDir, settings);

        // Verify both tables exist and have correct content
        assertFileContent(firstTableFile, expectedFirstTableContent);
        assertFileContent(secondTableFile, expectedSecondTableContent);
        TestUtils.assertErrors(settings.getErrors());
    }

    @Test
    void fullUpdatePreservesMetadataDirsTest(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path settingsDir = tempDir.resolve(".settings");
        Files.createDirectories(settingsDir);

        IDatabase newDb = provider.getDumpLoader(getFilePath("test_export.sql"), settings).loadAndAnalyze();
        provider.getProjectUpdater(newDb, null, List.of(), tempDir, false, new CoreSettings()).updateFull(true);

        assertTrue(Files.exists(settingsDir));
        TestUtils.assertErrors(settings.getErrors());
    }

    @Test
    void fullUpdateCleansRenamedDirsAndKeepsForeignTest(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        Files.createDirectories(tempDir.resolve("MIGRATION"));
        Files.createDirectories(tempDir.resolve("SCHEMA"));
        Files.writeString(tempDir.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME), "SCHEMA=DB\n");

        IDatabase newDb = provider.getDumpLoader(getFilePath("test_export.sql"), settings).loadAndAnalyze();
        provider.getProjectUpdater(newDb, null, List.of(), tempDir, false, new CoreSettings())
                .updateFull(true, new PgWorkDirs());

        assertTrue(Files.exists(tempDir.resolve("MIGRATION")));
        assertFalse(Files.exists(tempDir.resolve("SCHEMA")));
        TestUtils.assertErrors(settings.getErrors());
    }

    @Test
    void runSqlUsesExecutionSubMonitorForCancellation() throws Exception {
        IMonitor rootMonitor = mock(IMonitor.class);
        IMonitor subMonitor = mock(IMonitor.class);
        when(rootMonitor.createSubMonitor()).thenReturn(subMonitor);
        when(subMonitor.isCancelled()).thenReturn(true);
        CoreSettings executionSettings = new CoreSettings();
        executionSettings.setMonitor(rootMonitor);
        IJdbcConnector connector = mock(IJdbcConnector.class);
        PgDatabaseProvider executionProvider = new PgDatabaseProvider() {
            @Override
            public IJdbcConnector getJdbcConnector(String url) {
                return connector;
            }
        };

        assertThrows(InterruptedException.class,
                () -> PgCodeKeeperApi.runSQL(executionProvider, "test.sql", "select 1;",
                        "jdbc:controlled", executionSettings));

        verify(connector, never()).getConnection();
    }

    private void setupUpdateProjectStructure(Path tempDir) throws IOException {
        Path publicDir = tempDir.resolve(PUBLIC_DIRECTORY);
        Path tablesDir = tempDir.resolve(TABLES_DIRECTORY);
        Files.createDirectories(tablesDir);

        Path schemaFile = publicDir.resolve("public.sql");
        Path firstTableFile = tablesDir.resolve("first_table.sql");
        Path secondTableFile = tablesDir.resolve("second_table.sql");

        // Copy test files to project structure
        Files.copy(getFilePath("test_update_project_old_public.sql"), schemaFile, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(getFilePath("test_update_project_old_first_table.sql"), firstTableFile, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(getFilePath("test_update_project_old_second_table.sql"), secondTableFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path getFilePath(String fileName) {
        return TestUtils.getFilePath(fileName, getClass());
    }

    private String getFileContent(String fileName) throws IOException {
        return Files.readString(getFilePath(fileName));
    }

    private String getExpectedDiff(String baseName) throws IOException {
        return getFileContent(baseName + DIFF);
    }

    private ComparisonLoaderFactories dumpFactories(
            PgDatabaseProvider databaseProvider, String baseName) {
        return new ComparisonLoaderFactories(
                sideSettings -> databaseProvider.getDumpLoader(
                        getFilePath(baseName + ORIGINAL), sideSettings),
                sideSettings -> databaseProvider.getDumpLoader(
                        getFilePath(baseName + NEW), sideSettings));
    }

    private ComparisonLoaderFactories trackingDumpFactories(
            PgDatabaseProvider databaseProvider, String baseName,
            AtomicReference<ISettings> commonSettings,
            AtomicReference<ISettings> oldSideSettings,
            AtomicReference<ISettings> newSideSettings,
            FactoryMonitorOracle progress) {
        ILoaderFactory oldFactory = new ILoaderFactory() {
            @Override
            public ILoader create(ISettings sideSettings) throws IOException {
                oldSideSettings.set(sideSettings);
                sideSettings.addError(OLD_DIAGNOSTIC);
                ILoader loader = databaseProvider.getDumpLoader(
                        getFilePath(baseName + ORIGINAL), sideSettings);
                return progress.trackCreated("OLD", loader);
            }

            @Override
            public void contributeCommonConfiguration(ISettings settings) {
                commonSettings.set(settings);
                CoreSettings coreSettings = (CoreSettings) settings;
                coreSettings.setIgnoreColumnOrder(true);
                coreSettings.setAddTransaction(true);
            }
        };
        ILoaderFactory newFactory = sideSettings -> {
            newSideSettings.set(sideSettings);
            sideSettings.addError(NEW_DIAGNOSTIC);
            ILoader loader = databaseProvider.getDumpLoader(
                    getFilePath(baseName + NEW), sideSettings);
            return progress.trackCreated("NEW", loader);
        };
        return new ComparisonLoaderFactories(oldFactory, newFactory);
    }

    private void assertFileContent(Path filePath, String expectedContent) throws IOException {
        assertTrue(Files.exists(filePath));
        var actualContent = Files.readString(filePath);
        TestUtils.assertIgnoreNewLines(expectedContent, actualContent);
    }

    private static final class TreeTrackingSettings extends CoreSettings {

        private final AtomicReference<ISettings> treeSettings;

        private TreeTrackingSettings(AtomicReference<ISettings> treeSettings) {
            this.treeSettings = treeSettings;
        }

        @Override
        public CoreSettings shallowCopy() {
            var copy = new TreeTrackingSettings(treeSettings);
            copy.setIgnoreColumnOrder(super.isIgnoreColumnOrder());
            copy.setAddTransaction(super.isAddTransaction());
            return copy;
        }

        @Override
        public boolean isIgnoreColumnOrder() {
            treeSettings.compareAndSet(null, this);
            return super.isIgnoreColumnOrder();
        }
    }

    private static final class FactoryMonitorOracle extends NullMonitor {

        private static final List<String> CHILD_NAMES = List.of("API", "OLD", "NEW");

        private final List<String> timeline = Collections.synchronizedList(new ArrayList<>());
        private final IMonitor apiMonitor = new NullMonitor() {
            @Override
            public void setWorkRemaining(int size) {
                record("API setWorkRemaining=" + size);
            }

            @Override
            public void worked(int work) {
                record("API worked=" + work);
            }

            @Override
            public void setTaskName(String name) {
                record("API setTaskName=" + name);
            }
        };
        private final List<IMonitor> children = List.of(
                apiMonitor, new NullMonitor(), new NullMonitor());
        private int childIndex;

        @Override
        public IMonitor createSubMonitor() {
            if (childIndex >= children.size()) {
                throw new AssertionError("unexpected additional sub-monitor");
            }
            record("ROOT child=" + CHILD_NAMES.get(childIndex));
            return children.get(childIndex++);
        }

        private void assertCompleted(int totalWork, String finalTask, int finalWork) {
            List<String> events = snapshot();
            assertEquals(List.of(
                    "API setWorkRemaining=" + totalWork,
                    "API worked=60",
                    "API setTaskName=" + finalTask,
                    "API worked=" + finalWork), events.stream()
                            .filter(event -> event.startsWith("API "))
                            .toList());
            assertOccursInOrder(events,
                    "ROOT child=API",
                    "API setWorkRemaining=" + totalWork,
                    "ROOT child=OLD",
                    "ROOT child=NEW",
                    "OLD factory-created",
                    "NEW factory-created");
            assertSideCompletedBeforeProgress(events, "OLD");
            assertSideCompletedBeforeProgress(events, "NEW");
        }

        private ILoader trackCreated(String side, ILoader loader) {
            record(side + " factory-created");
            return new TimelineLoader(side, loader);
        }

        private void assertSideCompletedBeforeProgress(List<String> events, String side) {
            assertOccursInOrder(events,
                    side + " factory-created",
                    side + " structural-complete",
                    side + " analysis-complete",
                    side + " close-complete",
                    "API worked=60");
        }

        private static void assertOccursInOrder(List<String> events, String... expected) {
            int previous = -1;
            for (String event : expected) {
                int current = events.indexOf(event);
                assertTrue(current > previous,
                        "expected event order " + List.of(expected) + ", timeline=" + events);
                previous = current;
            }
        }

        private void record(String event) {
            timeline.add(event);
        }

        private List<String> snapshot() {
            synchronized (timeline) {
                return List.copyOf(timeline);
            }
        }

        private final class TimelineLoader implements ILoader {

            private final String side;
            private final ILoader delegate;

            private TimelineLoader(String side, ILoader delegate) {
                this.side = side;
                this.delegate = delegate;
            }

            @Override
            public IDatabase load() throws IOException, InterruptedException {
                IDatabase database = delegate.load();
                record(side + " structural-complete");
                return database;
            }

            @Override
            public void preLoad() throws IOException, InterruptedException {
                delegate.preLoad();
            }

            @Override
            public void cancel() throws IOException {
                delegate.cancel();
            }

            @Override
            public void close() throws IOException {
                delegate.close();
                record(side + " close-complete");
            }

            @Override
            public IDatabase loadAndAnalyze() throws IOException, InterruptedException {
                IDatabase database = delegate.loadAndAnalyze();
                record(side + " analysis-complete");
                return database;
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
        }
    }
}
