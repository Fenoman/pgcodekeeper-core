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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.IProjectInputFingerprintCapture;
import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.loader.AbstractDumpLoader;
import org.pgcodekeeper.core.database.base.loader.AbstractProjectLoader;
import org.pgcodekeeper.core.database.base.loader.LoaderFactories;
import org.pgcodekeeper.core.database.base.parser.AntlrError;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;
import org.pgcodekeeper.core.database.base.parser.ErrorTypes;
import org.pgcodekeeper.core.database.base.project.AbstractWorkDirs;
import org.pgcodekeeper.core.database.base.schema.meta.MetaUtils;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.loader.PgProjectLoader;
import org.pgcodekeeper.core.database.pg.project.PgModelExporter;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractTable;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoreParser;
import org.pgcodekeeper.core.it.IntegrationTestUtils;
import org.pgcodekeeper.core.library.Library;
import org.pgcodekeeper.core.library.LibraryXmlStore;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.settings.ProjectFileFilter;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pgcodekeeper.core.it.IntegrationTestUtils.*;

/**
 * Tests for PostgreSQL ProjectLoader functionality
 */
class PgProjectLoaderTest {

    private static final String RESOURCE_FIRST_LIB = "lib_first_table.sql";
    private static final String RESOURCE_SECOND_LIB = "lib_second_table.sql";
    private static final String RESOURCE_OVERRIDE_EMP = "override_emp.sql";
    private static final String RESOURCE_OVERRIDE_EMP_STAMP = "override_emp_stamp.sql";

    private final PgDatabaseProvider databaseProvider = new PgDatabaseProvider();

    @Test
    void inputFingerprintCaptureIsOptIn(@TempDir Path dir)
            throws Exception {
        Path project = createFingerprintProject(dir.resolve("opt-in"));
        try (var loader = new PgProjectLoader(project,
                new CoreSettings())) {
            var capture =
                    (IProjectInputFingerprintCapture) loader;

            loader.load();

            Assertions.assertTrue(
                    capture.getCapturedInputFingerprints().isEmpty());
        }
    }

    @Test
    void inputFingerprintCaptureMatchesExactRawDispatchedBytes(
            @TempDir Path dir) throws Exception {
        Path project = createFingerprintProject(
                dir.resolve("captured"));
        Path schema = project.resolve("SCHEMA/public/public.sql");
        Path table =
                project.resolve("SCHEMA/public/TABLE/item.sql");
        Path empty = project.resolve(
                "SCHEMA/public/VIEW/empty.sql");
        Files.createDirectories(empty.getParent());
        Files.write(empty, new byte[0]);
        Path override = project.resolve(
                "OVERRIDES/SCHEMA/public/TABLE/item.sql");
        Files.createDirectories(override.getParent());
        Files.writeString(override,
                "ALTER TABLE public.item OWNER TO app_owner;\r\n");
        Files.writeString(project.resolve(
                "SCHEMA/public/TABLE/not-sql.txt"), "ignored");

        List<ProjectInputFingerprint> fingerprints;
        List<Path> listed;
        try (var loader = new PgProjectLoader(project,
                new CoreSettings())) {
            var capture =
                    (IProjectInputFingerprintCapture) loader;
            capture.enableInputFingerprintCapture();

            listed = loader.listInputFiles();
            loader.load();
            fingerprints =
                    capture.getCapturedInputFingerprints();
        }

        Map<Path, ProjectInputFingerprint> byPath =
                fingerprints.stream().collect(
                        java.util.stream.Collectors.toMap(
                                ProjectInputFingerprint::path,
                                fingerprint -> fingerprint));
        Assertions.assertEquals(listed.stream()
                        .map(path -> path.toAbsolutePath()
                                .normalize())
                        .collect(java.util.stream.Collectors.toSet()),
                byPath.keySet());
        Assertions.assertEquals(Set.of(
                schema.toAbsolutePath().normalize(),
                table.toAbsolutePath().normalize(),
                empty.toAbsolutePath().normalize(),
                override.toAbsolutePath().normalize()),
                byPath.keySet());
        for (Path path : byPath.keySet()) {
            byte[] bytes = Files.readAllBytes(path);
            ProjectInputFingerprint fingerprint =
                    byPath.get(path);
            Assertions.assertEquals(bytes.length,
                    fingerprint.byteCount());
            Assertions.assertArrayEquals(
                    MessageDigest.getInstance("SHA-256")
                            .digest(bytes),
                    fingerprint.sha256());
        }
    }

    @Test
    void allowAllSkipsRootRelativizationAfterExistingFileChecks(@TempDir Path dir)
            throws IOException {
        Path project = Files.createDirectories(dir.resolve("project"));
        var loader = new RecordingPgProjectLoader(project, new CoreSettings());

        try (FileSystem zip = FileSystems.newFileSystem(dir.resolve("files.zip"),
                Map.of("create", "true"))) {
            Path sql = Files.writeString(zip.getPath("allowed.sql"), "SELECT 1;");
            Path text = Files.writeString(zip.getPath("not-sql.txt"), "text");

            Assertions.assertAll(
                    () -> assertTrue(loader.accepts(sql, null)),
                    () -> assertFalse(loader.accepts(sql, fileName -> false)),
                    () -> assertFalse(loader.accepts(text, null)));
        }
    }

    @Test
    void excludedSplitFileNeverCreatesDumpLoaderOrAntlrDiagnostic(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("excluded-split");
        Path brokenFile = createSplitProjectTable(project, "broken",
                "CREATE TABLE public.broken (\n");
        var settings = settingsWithFilter(dir.resolve("excluded.filter"),
                "EXCLUDE PATH SCHEMA/public/TABLE/broken.sql\n");
        var loader = new RecordingPgProjectLoader(project, settings);

        loader.load();

        Assertions.assertAll(
                () -> assertFalse(loader.wasDispatched(
                        "SCHEMA/public/TABLE/broken.sql")),
                () -> assertFalse(hasAntlrDiagnostic(settings, brokenFile)));
    }

    @Test
    void laterIncludeRestoresAFilteredException(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("included-split");
        Path brokenFile = createSplitProjectTable(project, "broken",
                "CREATE TABLE public.broken (\n");
        var settings = settingsWithFilter(dir.resolve("included.filter"), """
                EXCLUDE REGEX ^SCHEMA/public/TABLE/.*\\.sql$
                INCLUDE PATH SCHEMA/public/TABLE/broken.sql
                """);
        var loader = new RecordingPgProjectLoader(project, settings);

        loader.load();

        Assertions.assertAll(
                () -> assertTrue(loader.wasDispatched(
                        "SCHEMA/public/TABLE/broken.sql")),
                () -> assertTrue(hasAntlrDiagnostic(settings, brokenFile)));
    }

    @Test
    void projectFileFilterWorksForFlatLayout(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("flat");
        Files.createDirectories(project.resolve("SCHEMA"));
        Files.createDirectories(project.resolve("TABLE"));
        Files.writeString(project.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                AbstractWorkDirs.IS_SPLIT_BY_SCHEMA + "=false\n");
        Files.writeString(project.resolve("SCHEMA/public.sql"), "CREATE SCHEMA public;\n");
        Files.writeString(project.resolve("TABLE/public.keep.sql"),
                "CREATE TABLE public.keep (id integer);\n");
        Files.writeString(project.resolve("TABLE/public.drop.sql"),
                "CREATE TABLE public.drop (id integer);\n");
        var settings = settingsWithFilter(dir.resolve("flat.filter"),
                "EXCLUDE PATH TABLE/public.drop.sql\n");
        var loader = new RecordingPgProjectLoader(project, settings);

        PgDatabase database = loader.load();

        Assertions.assertAll(
                () -> assertTrue(loader.wasDispatched("TABLE/public.keep.sql")),
                () -> assertFalse(loader.wasDispatched("TABLE/public.drop.sql")),
                () -> assertNotNull(database.getStatement(
                        new ObjectReference("public", "keep", DbObjType.TABLE))),
                () -> assertNull(database.getStatement(
                        new ObjectReference("public", "drop", DbObjType.TABLE))));
    }

    @Test
    void ignoredSchemaCannotBeRestoredByProjectFileFilter(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("ignored-flat-schema");
        Files.createDirectories(project.resolve("SCHEMA"));
        Files.createDirectories(project.resolve("TABLE"));
        Files.writeString(project.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                AbstractWorkDirs.IS_SPLIT_BY_SCHEMA + "=false\n");
        Files.writeString(project.resolve("SCHEMA/hidden.sql"), "CREATE SCHEMA hidden;\n");
        Files.writeString(project.resolve("TABLE/hidden.secret.sql"),
                "CREATE TABLE hidden.secret (id integer);\n");
        Files.writeString(project.resolve(AbstractProjectLoader.IGNORE_SCHEMA_FILE),
                "SHOW ALL\nHIDE NONE hidden\n");
        var settings = settingsWithFilter(dir.resolve("ignored-schema.filter"), """
                EXCLUDE REGEX .*
                INCLUDE PATH TABLE/hidden.secret.sql
                """);
        var loader = new RecordingPgProjectLoader(project, settings);

        PgDatabase database = loader.load();

        Assertions.assertAll(
                () -> assertTrue(settings.getProjectFileFilter()
                        .isAllowed("TABLE/hidden.secret.sql")),
                () -> assertFalse(loader.wasDispatched("TABLE/hidden.secret.sql")),
                () -> assertNull(database.getStatement(
                        new ObjectReference("hidden", "secret", DbObjType.TABLE))));
    }

    @Test
    void overrideFilesUseTopLevelRootRelativePaths(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("filtered-overrides");
        createSplitProjectTable(project, "emp",
                "CREATE TABLE public.emp (id integer);\n");
        Path overrideDir = project.resolve("OVERRIDES/SCHEMA/public/TABLE");
        Files.createDirectories(overrideDir);
        Files.copy(TestUtils.getFilePath(RESOURCE_OVERRIDE_EMP, getClass()),
                overrideDir.resolve("emp.sql"));
        var settings = settingsWithFilter(dir.resolve("overrides.filter"),
                "EXCLUDE PATH OVERRIDES/SCHEMA/public/TABLE/emp.sql\n");
        var loader = new RecordingPgProjectLoader(project, settings);

        PgDatabase database = loader.load();
        IStatement emp = database.getStatement(
                new ObjectReference("public", "emp", DbObjType.TABLE));

        Assertions.assertAll(
                () -> assertTrue(loader.wasDispatched("SCHEMA/public/TABLE/emp.sql")),
                () -> assertFalse(loader.wasDispatched(
                        "OVERRIDES/SCHEMA/public/TABLE/emp.sql")),
                () -> assertNotNull(emp),
                () -> Assertions.assertNotEquals("override_user", emp.getOwner()));
    }

    @Test
    void inputEnumerationMatchesSplitProjectDispatch(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("enumerated-split");
        createSplitProjectTable(project, "keep",
                "CREATE TABLE public.keep (id integer);\n");
        createSplitProjectTable(project, "filtered",
                "CREATE TABLE public.filtered (id integer);\n");
        Path excludedSchema = project.resolve("SCHEMA/dummy_tmp/TABLE");
        Files.createDirectories(excludedSchema);
        Files.writeString(excludedSchema.resolve("ignored.sql"),
                "CREATE TABLE dummy_tmp.ignored (id integer);\n");
        Path overrideDir = project.resolve("OVERRIDES/SCHEMA/public/TABLE");
        Files.createDirectories(overrideDir);
        Files.copy(TestUtils.getFilePath(RESOURCE_OVERRIDE_EMP, getClass()),
                overrideDir.resolve("keep.sql"));
        var settings = settingsWithFilter(dir.resolve("enumerated-split.filter"),
                "EXCLUDE PATH SCHEMA/public/TABLE/filtered.sql\n");
        settings.setAdditionalExcludedSchemas(Set.of("dummy_tmp"));
        var loader = new RecordingPgProjectLoader(project, settings);

        List<String> enumerated = loader.listInputFiles().stream()
                .map(loader::relative)
                .toList();
        loader.load();

        Assertions.assertEquals(loader.dispatchedFiles(), enumerated);
    }

    @Test
    void inputEnumerationDoesNotConstructParsers(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("enumeration-only");
        Path broken = createSplitProjectTable(project, "broken",
                "CREATE TABLE public.broken (\n");
        var settings = new CoreSettings();
        var loader = new RecordingPgProjectLoader(project, settings);

        List<Path> files = loader.listInputFiles();

        Assertions.assertAll(
                () -> Assertions.assertTrue(files.contains(broken)),
                () -> Assertions.assertEquals(2, files.size()),
                () -> Assertions.assertTrue(loader.dispatchedFiles().isEmpty()),
                () -> Assertions.assertTrue(loader.parserTasksAreDrained()),
                () -> Assertions.assertTrue(settings.getErrors().isEmpty()),
                () -> Assertions.assertThrows(UnsupportedOperationException.class,
                        () -> files.add(project.resolve("other.sql"))));
    }

    @Test
    void inputEnumerationRejectsPreCancelledMonitor(@TempDir Path dir)
            throws IOException {
        Path project = dir.resolve("enumeration-pre-cancelled");
        createSplitProjectTable(project, "never-listed",
                "CREATE TABLE public.never_listed (id integer);\n");
        var monitor = new NullMonitor();
        monitor.setCancelled(true);
        var settings = new CoreSettings();
        settings.setMonitor(monitor);
        var loader = new RecordingPgProjectLoader(project, settings);

        Assertions.assertThrows(InterruptedException.class,
                loader::listInputFiles);
        Assertions.assertTrue(loader.dispatchedFiles().isEmpty());
    }

    @Test
    void inputEnumerationRechecksMonitorAfterPreLoad(@TempDir Path dir)
            throws IOException {
        Path project = dir.resolve("enumeration-cancelled-after-preload");
        createSplitProjectTable(project, "never-listed",
                "CREATE TABLE public.never_listed (id integer);\n");
        var monitor = new CancelAfterChecksMonitor(2);
        var settings = new CoreSettings();
        settings.setMonitor(monitor);
        var loader = new RecordingPgProjectLoader(project, settings);

        Assertions.assertThrows(InterruptedException.class,
                loader::listInputFiles);
        Assertions.assertAll(
                () -> Assertions.assertEquals(2, monitor.checks()),
                () -> Assertions.assertTrue(loader.dispatchedFiles().isEmpty()));
    }

    @Test
    void inputEnumerationStopsWhenMonitorCancelsDuringWalk(@TempDir Path dir)
            throws IOException {
        Path project = dir.resolve("enumeration-mid-cancelled");
        for (int i = 0; i < 20; i++) {
            createSplitProjectTable(project, "table_" + i,
                    "CREATE TABLE public.table_" + i + " (id integer);\n");
        }
        var monitor = new CancelAfterChecksMonitor(6);
        var settings = new CoreSettings();
        settings.setMonitor(monitor);
        var loader = new RecordingPgProjectLoader(project, settings);

        Assertions.assertThrows(InterruptedException.class,
                loader::listInputFiles);
        Assertions.assertAll(
                () -> Assertions.assertTrue(monitor.checks() >= 6),
                () -> Assertions.assertTrue(loader.dispatchedFiles().isEmpty()));
    }

    @Test
    void inputEnumerationRejectsCancelledLoaderLifecycle(@TempDir Path dir)
            throws IOException {
        Path project = dir.resolve("enumeration-loader-cancelled");
        createSplitProjectTable(project, "never-listed",
                "CREATE TABLE public.never_listed (id integer);\n");
        var loader = new RecordingPgProjectLoader(project, new CoreSettings());
        loader.cancel();

        Assertions.assertThrows(InterruptedException.class,
                loader::listInputFiles);
        Assertions.assertTrue(loader.dispatchedFiles().isEmpty());
    }

    @Test
    void inputEnumerationMatchesFlatProjectDispatch(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("enumerated-flat");
        Files.createDirectories(project.resolve("SCHEMA"));
        Files.createDirectories(project.resolve("TABLE/nested"));
        Files.writeString(project.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                AbstractWorkDirs.IS_SPLIT_BY_SCHEMA + "=false\n");
        Files.writeString(project.resolve("SCHEMA/public.sql"), "CREATE SCHEMA public;\n");
        Files.writeString(project.resolve("SCHEMA/dummy_tmp.sql"),
                "CREATE SCHEMA dummy_tmp;\n");
        Files.writeString(project.resolve("TABLE/public.keep.sql"),
                "CREATE TABLE public.keep (id integer);\n");
        Files.writeString(project.resolve("TABLE/public.filtered.sql"),
                "CREATE TABLE public.filtered (id integer);\n");
        Files.writeString(project.resolve("TABLE/dummy_tmp.ignored.sql"),
                "CREATE TABLE dummy_tmp.ignored (id integer);\n");
        Files.writeString(project.resolve("TABLE/nested/not-dispatched.sql"),
                "CREATE TABLE public.not_dispatched (id integer);\n");
        var settings = settingsWithFilter(dir.resolve("enumerated-flat.filter"),
                "EXCLUDE PATH TABLE/public.filtered.sql\n");
        settings.setAdditionalExcludedSchemas(Set.of("dummy_tmp"));
        var loader = new RecordingPgProjectLoader(project, settings);

        List<String> enumerated = loader.listInputFiles().stream()
                .map(loader::relative)
                .toList();
        loader.load();

        Assertions.assertEquals(loader.dispatchedFiles(), enumerated);
    }

    @Test
    void inputEnumerationUsesDeterministicSchemaOrder(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("enumerated-schema-order");
        createSplitSchemaTable(project, "zeta", "z_table");
        createSplitSchemaTable(project, "alpha", "a_table");
        var loader = new RecordingPgProjectLoader(project, new CoreSettings());

        List<String> enumerated = loader.listInputFiles().stream()
                .map(loader::relative)
                .toList();
        loader.load();

        List<String> expected = List.of(
                "SCHEMA/alpha/alpha.sql",
                "SCHEMA/zeta/zeta.sql",
                "SCHEMA/alpha/TABLE/a_table.sql",
                "SCHEMA/zeta/TABLE/z_table.sql");
        Assertions.assertAll(
                () -> Assertions.assertEquals(expected, enumerated),
                () -> Assertions.assertEquals(expected,
                        loader.dispatchedFiles()));
    }

    @Test
    void libraryInputEnumerationSkipsOverridesLikeRealLoad(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("enumerated-library");
        createSplitProjectTable(project, "emp",
                "CREATE TABLE public.emp (id integer);\n");
        Path overrideDir = project.resolve("OVERRIDES/SCHEMA/public/TABLE");
        Files.createDirectories(overrideDir);
        Files.copy(TestUtils.getFilePath(RESOURCE_OVERRIDE_EMP, getClass()),
                overrideDir.resolve("emp.sql"));
        var loader = new RecordingPgProjectLoader(project, new CoreSettings());
        loader.setLib(true);

        List<String> enumerated = loader.listInputFiles().stream()
                .map(loader::relative)
                .toList();
        loader.load();

        Assertions.assertAll(
                () -> Assertions.assertEquals(loader.dispatchedFiles(),
                        enumerated),
                () -> Assertions.assertTrue(enumerated.stream()
                        .noneMatch(path -> path.startsWith("OVERRIDES/"))));
    }

    @Test
    void disabledObjectReferenceIndexPreservesProjectSemantics(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("object-reference-policy");
        createObjectReferencePolicyProject(project);

        var enabledSettings = new CoreSettings();
        var disabledSettings = new DisabledObjectReferenceSettings();
        PgDatabase enabled = new PgProjectLoader(project, enabledSettings).loadAndAnalyze();
        PgDatabase disabled = new PgProjectLoader(project, disabledSettings).loadAndAnalyze();

        var tableReference = new ObjectReference("public", "base", DbObjType.TABLE);
        var viewReference = new ObjectReference("public", "base_view", DbObjType.VIEW);
        IStatement disabledTable = disabled.getStatement(tableReference);
        IStatement disabledView = disabled.getStatement(viewReference);

        Assertions.assertAll(
                () -> assertFalse(enabled.getObjReferences().isEmpty()),
                () -> assertTrue(disabled.getObjReferences().isEmpty()),
                () -> assertNotNull(disabledTable),
                () -> assertNotNull(disabledTable.getLocation()),
                () -> Assertions.assertEquals("base.sql",
                        Path.of(disabledTable.getLocation().getFilePath()).getFileName().toString()),
                () -> assertNotNull(disabledView),
                () -> assertTrue(disabledView.getDependencies().contains(tableReference)),
                () -> Assertions.assertEquals(dependencySnapshot(enabled), dependencySnapshot(disabled)),
                () -> Assertions.assertEquals(errorSnapshot(enabledSettings), errorSnapshot(disabledSettings)),
                () -> assertTrue(disabledSettings.getErrors().stream()
                        .filter(AntlrError.class::isInstance)
                        .map(AntlrError.class::cast)
                        .anyMatch(error -> error.getErrorType() == ErrorTypes.MISPLACEERROR)));
    }

    @Test
    void testProjectLoaderWithIgnoredSchemas(@TempDir Path dir) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        IDatabase dbDump = loadTestDump(databaseProvider, RESOURCE_DUMP, IntegrationTestUtils.class, settings);

        new PgModelExporter(dir, dbDump, Consts.UTF_8, settings).exportFull();

        createIgnoredSchemaFile(dir);

        IDatabase db = databaseProvider.getProjectLoader(dir, settings).load();

        for (var dbSchema : db.getSchemas()) {
            if (IGNORED_SCHEMAS_LIST.contains(dbSchema.getName())) {
                Assertions.fail("Ignored Schema loaded " + dbSchema.getName());
            } else {
                Assertions.assertEquals(dbDump.getSchema(dbSchema.getName()), dbSchema,
                        "Schema from dump isn't equal schema from loader");
            }
        }
    }

    @Test
    void testModelExporterWithIgnoredLists(@TempDir Path dir) throws IOException, InterruptedException {
        var settings = new CoreSettings();

        IDatabase dbDump = loadTestDump(databaseProvider, RESOURCE_DUMP, IntegrationTestUtils.class, settings);
        TreeElement root = DiffTree.create(settings, dbDump, null, null);
        root.setAllChecked();

        createIgnoreListFile(dir);
        Path listFile = dir.resolve(AbstractProjectLoader.IGNORE_FILE);

        IgnoreList ignoreList = new IgnoreList();
        IgnoreParser ignoreParser = new IgnoreParser(ignoreList);
        ignoreParser.parse(listFile);

        List<TreeElement> selected = new TreeFlattener().onlySelected().useIgnoreList(ignoreList)
                .onlyTypes(settings.getAllowedTypes()).flatten(root);

        new PgModelExporter(dir, dbDump, null, selected, Consts.UTF_8, settings).exportProject();

        IDatabase loader = databaseProvider.getProjectLoader(dir, settings).load();
        var ignoredObj = "people";
        boolean result = loader.getDescendants().map(IStatement::getName).noneMatch(ignoredObj::startsWith);
        assertTrue(result);
    }

    @Test
    void testProjectLoaderWithoutAutoLoad(@TempDir Path dir) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        settings.setDisableAutoLoad(true);
        Path projectDir = dir.resolve("project");

        createProject(projectDir, settings);

        String libPath = TestUtils.getFilePath(RESOURCE_FIRST_LIB, getClass()).toString();
        new LibraryXmlStore(projectDir.resolve(LibraryXmlStore.FILE_NAME)).writeDependencies(List.of(
                new Library("", libPath, false, "")), false);

        createIgnoredSchemaFile(projectDir);
        createIgnoreListFile(projectDir);

        IDatabase db = databaseProvider.getProjectLoader(projectDir, settings).load();

        boolean result = db.getSchemas().stream().map(IStatement::getName).anyMatch(IGNORED_SCHEMAS_LIST::contains);
        assertTrue(result, "Ignored schemas not loaded");
        assertTrue(settings.getIgnoreList().getList().isEmpty());

        assertNotLoaded(db, "lib_first_table");
    }

    @Test
    void directProjectPreLoadIsIdempotentWhenEnabled(@TempDir Path dir) throws Exception {
        Path projectDir = dir.resolve("enabled-preload");
        writePreLoadConfiguration(projectDir);
        var settings = new CoreSettings();
        var loader = new PgProjectLoader(projectDir, settings);

        loader.preLoad();
        loader.preLoad();

        Assertions.assertAll(
                () -> Assertions.assertEquals(List.of("preload_rule"), settings.getIgnoreList()
                        .getList().stream().map(rule -> rule.getName()).toList()),
                () -> assertFalse(settings.isAllowedSchema("preload_schema")),
                () -> Assertions.assertEquals(List.of("public.preload_source"), settings
                        .getAdditionalDependencies().stream()
                        .map(dependency -> dependency.source().getFullName()).toList()));
    }

    @Test
    void disabledInitialDirectPreLoadDecisionRemainsStable(@TempDir Path dir) throws Exception {
        Path projectDir = dir.resolve("disabled-preload");
        writePreLoadConfiguration(projectDir);
        var settings = new CoreSettings();
        settings.setDisableAutoLoad(true);
        var loader = new PgProjectLoader(projectDir, settings);

        loader.preLoad();
        settings.setDisableAutoLoad(false);
        loader.preLoad();

        Assertions.assertAll(
                () -> assertTrue(settings.getIgnoreList().getList().isEmpty()),
                () -> assertTrue(settings.isAllowedSchema("preload_schema")),
                () -> assertTrue(settings.getAdditionalDependencies().isEmpty()));
    }

    @Test
    void projectFactoryAcknowledgmentPreventsLaterPreLoadScan(@TempDir Path dir) throws Exception {
        Path projectDir = dir.resolve("factory-preload");
        writePreLoadConfiguration(projectDir);
        var settings = new CoreSettings();
        settings.setDisableAutoLoad(true);
        var factory = LoaderFactories.project(projectDir,
                sideSettings -> new PgProjectLoader(projectDir, sideSettings));

        factory.contributeCommonConfiguration(settings);
        var loader = (PgProjectLoader) factory.create(settings);
        settings.setDisableAutoLoad(false);
        loader.preLoad();
        loader.preLoad();

        Assertions.assertAll(
                () -> assertTrue(settings.getIgnoreList().getList().isEmpty()),
                () -> assertTrue(settings.isAllowedSchema("preload_schema")),
                () -> assertTrue(settings.getAdditionalDependencies().isEmpty()));
    }

    @Test
    void projectFactoryKeepsLibrariesStructureSqlAndOverridesSideLocal(@TempDir Path dir)
            throws Exception {
        Path oldProject = dir.resolve("old-project");
        Path newProject = dir.resolve("new-project");
        createProject(oldProject, new CoreSettings());
        createProject(newProject, new CoreSettings());

        Files.writeString(oldProject.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                "TRIGGER_FUNC=TRIGGER_FUNCTION");
        Path oldFunctionDir = oldProject.resolve("SCHEMA/public/FUNCTION");
        Path oldTriggerFunctionDir = oldProject.resolve("SCHEMA/public/TRIGGER_FUNCTION");
        Files.createDirectories(oldTriggerFunctionDir);
        Files.move(oldFunctionDir.resolve("emp_stamp.sql"),
                oldTriggerFunctionDir.resolve("emp_stamp.sql"));

        String libPath = TestUtils.getFilePath(RESOURCE_FIRST_LIB, getClass()).toString();
        new LibraryXmlStore(oldProject.resolve(LibraryXmlStore.FILE_NAME)).writeDependencies(
                List.of(new Library("", libPath, false, "")), false);

        Path overrideDir = oldProject.resolve("OVERRIDES/SCHEMA/public/TABLE");
        Files.createDirectories(overrideDir);
        Files.copy(TestUtils.getFilePath(RESOURCE_OVERRIDE_EMP, getClass()),
                overrideDir.resolve("emp.sql"));

        var common = new CoreSettings();
        var oldFactory = LoaderFactories.project(oldProject,
                settings -> new PgProjectLoader(oldProject, settings));
        var newFactory = LoaderFactories.project(newProject,
                settings -> new PgProjectLoader(newProject, settings));
        oldFactory.contributeCommonConfiguration(common);
        newFactory.contributeCommonConfiguration(common);
        ISettings oldSettings = common.copy();
        ISettings newSettings = common.copy();

        IDatabase oldDb = oldFactory.create(oldSettings).load();
        IDatabase newDb = newFactory.create(newSettings).load();
        var empReference = new ObjectReference("public", "emp", DbObjType.TABLE);
        var triggerFunctionReference = new ObjectReference(
                "public", "emp_stamp()", DbObjType.FUNCTION);

        Assertions.assertAll(
                () -> assertTrue(common.getIgnoreList().getList().isEmpty()),
                () -> assertTrue(common.getAdditionalDependencies().isEmpty()),
                () -> assertNotNull(oldDb.getStatement(triggerFunctionReference)),
                () -> assertNotNull(newDb.getStatement(triggerFunctionReference)),
                () -> assertLibLoaded(oldDb, "lib_first_table", true),
                () -> assertNotLoaded(newDb, "lib_first_table"),
                () -> Assertions.assertEquals("override_user",
                        oldDb.getStatement(empReference).getOwner()),
                () -> Assertions.assertNotEquals("override_user",
                        newDb.getStatement(empReference).getOwner()));
    }

    @Test
    void testProjectLoaderWithLibrary(@TempDir Path dir) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        Path projectDir = dir.resolve("project");
        createProject(projectDir, settings);

        String libPath = TestUtils.getFilePath(RESOURCE_FIRST_LIB, getClass()).toString();

        IDatabase db = databaseProvider.getProjectLoader(projectDir, settings, Collections.emptyList(),
                List.of(libPath), Collections.emptyList(), dir.resolve("meta")).load();

        assertLibLoaded(db, "lib_first_table", true);
    }

    @Test
    void testProjectLoaderWithLibraryWithoutPrivileges(@TempDir Path dir) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        Path projectDir = dir.resolve("project");
        createProject(projectDir, settings);

        String libPath = TestUtils.getFilePath(RESOURCE_FIRST_LIB, getClass()).toString();

        IDatabase db = databaseProvider.getProjectLoader(projectDir, settings, Collections.emptyList(),
                Collections.emptyList(), List.of(libPath), dir.resolve("meta")).load();

        assertLibLoaded(db, "lib_first_table", false);
    }

    @Test
    void testProjectLoaderWithLibraryFromXml(@TempDir Path dir) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        Path projectDir = dir.resolve("project");
        createProject(projectDir, settings);

        String libWithPrivPath = TestUtils.getFilePath(RESOURCE_FIRST_LIB, getClass()).toString();
        String libWithoutPrivPath = TestUtils.getFilePath(RESOURCE_SECOND_LIB, getClass()).toString();

        Path depsFile = projectDir.resolve(LibraryXmlStore.FILE_NAME);
        new LibraryXmlStore(depsFile).writeDependencies(List.of(
                new Library("", libWithPrivPath, false, ""),
                new Library("", libWithoutPrivPath, true, "")), false);

        IDatabase db = databaseProvider.getProjectLoader(projectDir, settings, List.of(depsFile.toString()),
                Collections.emptyList(), Collections.emptyList(), dir.resolve("meta")).load();

        assertLibLoaded(db, "lib_first_table", true);
        assertLibLoaded(db, "lib_second_table", false);
    }

    @Test
    void testProjectLoaderWithIsLibTrue(@TempDir Path dir) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        Path projectDir = dir.resolve("project");
        createProject(projectDir, settings);

        String libPath = TestUtils.getFilePath(RESOURCE_FIRST_LIB, getClass()).toString();
        new LibraryXmlStore(projectDir.resolve(LibraryXmlStore.FILE_NAME)).writeDependencies(List.of(
                new Library("", libPath, false, "")), false);

        String externalLibPath = TestUtils.getFilePath(RESOURCE_SECOND_LIB, getClass()).toString();
        Path externalXml = dir.resolve(".external");
        new LibraryXmlStore(externalXml).writeDependencies(List.of(
                new Library("", externalLibPath, false, "")), false);

        var loader = databaseProvider.getProjectLoader(projectDir, settings, List.of(externalXml.toString()),
                Collections.emptyList(), Collections.emptyList(), dir.resolve("meta"));
        loader.setLib(true);
        IDatabase db = loader.load();

        assertNotLoaded(db, "lib_first_table");
        assertNotLoaded(db, "lib_second_table");
    }

    @Test
    void testProjectLoaderWithOverrides(@TempDir Path dir) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        Path projectDir = dir.resolve("project");
        createProject(projectDir, settings);

        Path overrideDir = projectDir.resolve("OVERRIDES/SCHEMA/public/TABLE");
        Files.createDirectories(overrideDir);
        var empPath = TestUtils.getFilePath(RESOURCE_OVERRIDE_EMP, getClass());
        Files.copy(empPath, overrideDir.resolve("emp.sql"));

        IDatabase db = databaseProvider.getProjectLoader(projectDir, settings).load();

        var emp = db.getStatement(new ObjectReference("public", "emp", DbObjType.TABLE));
        assertNotNull(emp);
        var hasSelectGrant = emp.getPrivileges().stream().anyMatch(
                p -> !p.isRevoke() && "SELECT".equals(p.getPermission())
                        && "override_user".equals(p.getRole()));

        Assertions.assertEquals("override_user", emp.getOwner());
        assertTrue(hasSelectGrant);
    }

    private void assertLibLoaded(IDatabase db, String tableName, boolean isWithPrivileges) {
        var libTableRef = new ObjectReference("public", tableName, DbObjType.TABLE);
        var libTable = db.getStatement(libTableRef);

        assertNotNull(libTable);
        assertTrue(libTable.isLib());

        if (isWithPrivileges) {
            Assertions.assertEquals("lib_user", libTable.getOwner());
            Assertions.assertFalse(libTable.getPrivileges().isEmpty());
        } else {
            assertNull(libTable.getOwner());
            assertTrue(libTable.getPrivileges().isEmpty());
        }
    }

    @Test
    void testProjectLoaderWithAltDirs(@TempDir Path dir) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        Path projectDir = dir.resolve("project");
        createProject(projectDir, settings);

        Files.writeString(projectDir.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                "TRIGGER_FUNC=TRIGGER_FUNCTION");
        Path funcDir = projectDir.resolve("SCHEMA/public/FUNCTION");
        Path triggerFuncDir = projectDir.resolve("SCHEMA/public/TRIGGER_FUNCTION");
        Files.createDirectories(triggerFuncDir);
        Files.move(funcDir.resolve("emp_stamp.sql"), triggerFuncDir.resolve("emp_stamp.sql"));

        IDatabase db = databaseProvider.getProjectLoader(projectDir, settings).load();

        var regularFunc = db.getStatement(
                new ObjectReference("public", "people_worker_shedule()", DbObjType.FUNCTION));
        assertNotNull(regularFunc, "Regular function should be loaded from default directory");

        var triggerFunc = db.getStatement(
                new ObjectReference("public", "emp_stamp()", DbObjType.FUNCTION));
        assertNotNull(triggerFunc, "Trigger function should be loaded from alt directory");
    }

    @Test
    void testProjectLoaderWithOverridesAndAltDirs(@TempDir Path dir) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        Path projectDir = dir.resolve("project");
        createProject(projectDir, settings);

        Files.writeString(projectDir.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                "TRIGGER_FUNC=TRIGGER_FUNCTION");
        Path funcDir = projectDir.resolve("SCHEMA/public/FUNCTION");
        Path triggerFuncDir = projectDir.resolve("SCHEMA/public/TRIGGER_FUNCTION");
        Files.createDirectories(triggerFuncDir);
        Files.move(funcDir.resolve("emp_stamp.sql"), triggerFuncDir.resolve("emp_stamp.sql"));

        Path overrideDir = projectDir.resolve("OVERRIDES/SCHEMA/public/TRIGGER_FUNCTION");
        Files.createDirectories(overrideDir);
        var overridePath = TestUtils.getFilePath(RESOURCE_OVERRIDE_EMP_STAMP, getClass());
        Files.copy(overridePath, overrideDir.resolve("emp_stamp.sql"));

        IDatabase db = databaseProvider.getProjectLoader(projectDir, settings).load();

        var empStamp = db.getStatement(
                new ObjectReference("public", "emp_stamp()", DbObjType.FUNCTION));
        assertNotNull(empStamp);
        Assertions.assertEquals("override_user", empStamp.getOwner());
        var hasExecuteGrant = empStamp.getPrivileges().stream().anyMatch(
                p -> !p.isRevoke() && "EXECUTE".equals(p.getPermission())
                        && "override_user".equals(p.getRole()));
        assertTrue(hasExecuteGrant);
    }

    @Test
    void testSingleProjectFileLoader(@TempDir Path dir) throws IOException, InterruptedException {
        Path projectDir = dir.resolve("project");
        createProject(projectDir, new CoreSettings());

        Path tableFile = projectDir.resolve("SCHEMA/country/TABLE/city.sql");
        var dumpLoader = databaseProvider.getDumpLoader(tableFile, new CoreSettings());
        dumpLoader.setMode(ParserListenerMode.SINGLE);
        var db = dumpLoader.loadAndAnalyze();
        var ref = new ObjectReference("country", "city", DbObjType.TABLE);
        assertTrue(dumpLoader.getErrors().isEmpty());
        assertNotNull(db.getStatement(ref));

        dumpLoader = databaseProvider.getDumpLoader(tableFile, new CoreSettings());
        dumpLoader.setMode(ParserListenerMode.NORMAL);
        db = dumpLoader.loadAndAnalyze();
        assertFalse(dumpLoader.getErrors().isEmpty());
        assertNull(db.getStatement(ref));

        dumpLoader = databaseProvider.getDumpLoader(tableFile, new CoreSettings());
        dumpLoader.setMode(ParserListenerMode.REF);
        db = dumpLoader.loadAndAnalyze();
        assertTrue(dumpLoader.getErrors().isEmpty(), dumpLoader.getErrors().toString());
        assertNull(db.getStatement(ref));
    }

    @Test
    void testSingleFileLoaderSkipsCrossFileIndexAttach(@TempDir Path dir) throws IOException, InterruptedException {
        Path projectDir = dir.resolve("project");
        createProject(projectDir, new CoreSettings());

        Path file = projectDir.resolve("SCHEMA/public/TABLE/attach.sql");
        Files.writeString(file, "ALTER INDEX public.main_idx ATTACH PARTITION public.child_idx;");

        var dumpLoader = databaseProvider.getDumpLoader(file, new CoreSettings());
        dumpLoader.setMode(ParserListenerMode.SINGLE);
        dumpLoader.load();
        assertTrue(dumpLoader.getErrors().isEmpty(), dumpLoader.getErrors().toString());
    }

    @Test
    void testLoadFilesBatch(@TempDir Path dir) throws IOException, InterruptedException {
        Path projectDir = dir.resolve("project");
        createProject(projectDir, new CoreSettings());

        Path viewFile = projectDir.resolve("SCHEMA/country/VIEW/city_view.sql");
        Files.createDirectories(viewFile.getParent());
        Files.writeString(viewFile, "CREATE VIEW country.city_view AS SELECT * FROM country.city;");

        var loader = databaseProvider.getProjectLoader(projectDir, new CoreSettings());
        IDatabase db = loader.loadFiles(List.of(
                projectDir.resolve("SCHEMA/public/TABLE/emp.sql"),
                viewFile));

        assertNotNull(db.getStatement(new ObjectReference("public", "emp", DbObjType.TABLE)));
        assertNotNull(db.getStatement(new ObjectReference("country", "city_view", DbObjType.VIEW)));
        assertTrue(loader.getErrors().isEmpty(), loader.getErrors().toString());
        var definitions = MetaUtils.getObjDefinitions(db);
        assertFalse(definitions.isEmpty());
        assertNotNull(definitions.get(viewFile.toString()));
    }

    @Test
    void testLoadFilesCollectsOverrideReferences(@TempDir Path dir) throws IOException, InterruptedException {
        Path projectDir = dir.resolve("project");
        createProject(projectDir, new CoreSettings());
        Path overrideDir = projectDir.resolve("OVERRIDES/SCHEMA/public/TABLE");
        Files.createDirectories(overrideDir);
        Path overrideFile = overrideDir.resolve("emp.sql");
        Files.copy(TestUtils.getFilePath(RESOURCE_OVERRIDE_EMP, getClass()), overrideFile);

        var loader = databaseProvider.getProjectLoader(projectDir, new CoreSettings());
        IDatabase db = loader.loadFiles(List.of(projectDir.resolve("SCHEMA/public/TABLE/emp.sql"), overrideFile));
        assertTrue(loader.getErrors().isEmpty(), loader.getErrors().toString());

        var emp = db.getStatement(new ObjectReference("public", "emp", DbObjType.TABLE));
        assertNotNull(emp);
        Assertions.assertNotEquals("override_user", emp.getOwner());

        var isolatedLoader = databaseProvider.getProjectLoader(projectDir, new CoreSettings());
        IDatabase isolatedDb = isolatedLoader.loadFiles(List.of(overrideFile));
        assertTrue(isolatedLoader.getErrors().isEmpty(), isolatedLoader.getErrors().toString());
        assertFalse(isolatedDb.getObjReferences().isEmpty());
    }

    @Test
    void testSingleFileLoaderReportsMisplacedObjects(@TempDir Path dir) throws IOException, InterruptedException {
        Path projectDir = dir.resolve("project");
        createProject(projectDir, new CoreSettings());

        Path tableFile = projectDir.resolve("SCHEMA/country/TABLE/city.sql");
        Path misplacedFile = projectDir.resolve("SCHEMA/country/TABLE/misplaced.sql");
        Files.copy(tableFile, misplacedFile);

        var loader = databaseProvider.getProjectLoader(projectDir, new CoreSettings());
        loader.loadFiles(List.of(misplacedFile));
        assertTrue(loader.getErrors().stream().anyMatch(e -> e instanceof AntlrError err
                && ErrorTypes.MISPLACEERROR == err.getErrorType()), loader.getErrors().toString());
    }

    @Test
    void sortsColumnsOnceAfterProjectLoad(@TempDir Path dir) throws IOException, InterruptedException {
        Path oldProject = dir.resolve("old");
        Path newProject = dir.resolve("new");
        createInheritanceProject(oldProject, false);
        createInheritanceProject(newProject, true);

        var optimizedSettings = new CoreSettings();
        var optimizedLoader = new CountingPgProjectLoader(oldProject, optimizedSettings);
        PgDatabase optimized = optimizedLoader.load();

        var referenceSettings = new CoreSettings();
        PgDatabase reference = new ReferenceSortingPgProjectLoader(oldProject, referenceSettings).load();
        var grandchild = (PgAbstractTable) optimized.getStatement(
                new ObjectReference("public", "c_grandchild", DbObjType.TABLE));
        List<String> columnOrder = grandchild.getColumns().stream().map(IStatement::getName).toList();

        var optimizedDiffSettings = new CoreSettings();
        String optimizedDiff = PgCodeKeeperApi.diff(databaseProvider,
                new PgProjectLoader(oldProject, optimizedDiffSettings),
                new PgProjectLoader(newProject, optimizedDiffSettings), optimizedDiffSettings);
        var referenceDiffSettings = new CoreSettings();
        String referenceDiff = PgCodeKeeperApi.diff(databaseProvider,
                new ReferenceSortingPgProjectLoader(oldProject, referenceDiffSettings),
                new ReferenceSortingPgProjectLoader(newProject, referenceDiffSettings), referenceDiffSettings);

        Assertions.assertAll(
                () -> Assertions.assertEquals(1, optimizedLoader.getSortColumnsCallCount()),
                () -> Assertions.assertEquals(
                        List.of("a_parent", "child_local", "z_parent", "grandchild_local"), columnOrder),
                () -> Assertions.assertEquals(reference, optimized),
                () -> assertTrue(optimizedSettings.getErrors().isEmpty(), optimizedSettings.getErrors().toString()),
                () -> assertTrue(referenceSettings.getErrors().isEmpty(), referenceSettings.getErrors().toString()),
                () -> assertFalse(optimizedDiff.isBlank()),
                () -> Assertions.assertEquals(referenceDiff, optimizedDiff));
    }

    private void assertNotLoaded(IDatabase db, String tableName) {
        var libTableRef = new ObjectReference("public", tableName, DbObjType.TABLE);
        var libTable = db.getStatement(libTableRef);

        assertNull(libTable);
    }

    private void createProject(Path dir, ISettings settings)
            throws IOException, InterruptedException {
        IDatabase dbDump = loadTestDump(databaseProvider, RESOURCE_DUMP, IntegrationTestUtils.class, settings);
        new PgModelExporter(dir, dbDump, Consts.UTF_8, settings).exportFull();
    }

    private static CoreSettings settingsWithFilter(Path filterFile, String rules)
            throws IOException {
        Files.writeString(filterFile, rules);
        var settings = new CoreSettings();
        settings.setProjectFileFilter(ProjectFileFilter.parse(filterFile));
        return settings;
    }

    private static Path createSplitProjectTable(Path project, String tableName, String sql)
            throws IOException {
        Path schema = project.resolve("SCHEMA/public");
        Path tables = schema.resolve("TABLE");
        Files.createDirectories(tables);
        Files.writeString(schema.resolve("public.sql"), "CREATE SCHEMA public;\n");
        return Files.writeString(tables.resolve(tableName + ".sql"), sql);
    }

    private static Path createFingerprintProject(Path project)
            throws IOException {
        Path schema = Files.createDirectories(
                project.resolve("SCHEMA/public"));
        Path tables = Files.createDirectories(
                schema.resolve("TABLE"));
        Files.write(schema.resolve("public.sql"),
                "CREATE SCHEMA public;\r\n"
                        .getBytes(
                                java.nio.charset.StandardCharsets.UTF_8));
        Files.write(tables.resolve("item.sql"),
                ("CREATE TABLE public.item (id integer);\r\n"
                        + "-- точные исходные байты\r\n")
                                .getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8));
        return project;
    }

    private static void createSplitSchemaTable(Path project, String schemaName,
            String tableName) throws IOException {
        Path schema = project.resolve("SCHEMA").resolve(schemaName);
        Path tables = schema.resolve("TABLE");
        Files.createDirectories(tables);
        Files.writeString(schema.resolve(schemaName + ".sql"),
                "CREATE SCHEMA " + schemaName + ";\n");
        Files.writeString(tables.resolve(tableName + ".sql"),
                "CREATE TABLE " + schemaName + '.' + tableName
                        + " (id integer);\n");
    }

    private static boolean hasAntlrDiagnostic(ISettings settings, Path file) {
        return settings.getErrors().stream()
                .filter(AntlrError.class::isInstance)
                .map(AntlrError.class::cast)
                .anyMatch(error -> file.toString().equals(error.getFilePath()));
    }

    private static void writePreLoadConfiguration(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve(AbstractProjectLoader.IGNORE_FILE),
                "SHOW ALL\nHIDE NONE preload_rule\n");
        Files.writeString(projectDir.resolve(AbstractProjectLoader.IGNORE_SCHEMA_FILE),
                "SHOW ALL\nHIDE NONE preload_schema\n");
        Files.writeString(projectDir.resolve(AbstractProjectLoader.ADDITIONAL_DEPENDENCIES_FILE),
                "TABLE public.preload_source -> TABLE public.preload_target;\n");
    }

    private static void createInheritanceProject(Path project, boolean addGrandchildColumn) throws IOException {
        Path schema = project.resolve("SCHEMA/public");
        Path tables = schema.resolve("TABLE");
        Files.createDirectories(tables);
        Files.writeString(schema.resolve("public.sql"), "CREATE SCHEMA public;\n");
        Files.writeString(tables.resolve("a_parent.sql"), """
                CREATE TABLE public.a_parent (
                    z_parent integer,
                    a_parent text
                );
                """);
        Files.writeString(tables.resolve("b_child.sql"), """
                CREATE TABLE public.b_child (
                    child_local text
                ) INHERITS (public.a_parent);

                ALTER TABLE public.b_child ALTER COLUMN z_parent SET STATISTICS 10;
                ALTER TABLE public.b_child ALTER COLUMN a_parent SET STORAGE PLAIN;
                """);
        Files.writeString(tables.resolve("c_grandchild.sql"), """
                CREATE TABLE public.c_grandchild (
                    grandchild_local integer%s
                ) INHERITS (public.b_child);

                ALTER TABLE public.c_grandchild ALTER COLUMN z_parent SET STATISTICS 20;
                ALTER TABLE public.c_grandchild ALTER COLUMN a_parent SET STORAGE MAIN;
                ALTER TABLE public.c_grandchild ALTER COLUMN child_local SET STORAGE EXTENDED;
                ALTER TABLE public.c_grandchild
                    ADD CONSTRAINT c_grandchild_chk CHECK (a_parent IS NOT NULL) NOT VALID;
                """.formatted(addGrandchildColumn ? ",\n    grandchild_new text" : ""));
    }

    private static void createObjectReferencePolicyProject(Path project) throws IOException {
        Path schema = project.resolve("SCHEMA/public");
        Path tables = schema.resolve("TABLE");
        Path views = schema.resolve("VIEW");
        Files.createDirectories(tables);
        Files.createDirectories(views);
        Files.writeString(schema.resolve("public.sql"), "CREATE SCHEMA public;\n");
        Files.writeString(tables.resolve("base.sql"), """
                CREATE TABLE public.base (
                    id integer PRIMARY KEY
                );
                """);
        Files.writeString(views.resolve("base_view.sql"), """
                CREATE VIEW public.base_view AS
                SELECT id FROM public.base;
                """);
        Files.writeString(views.resolve("misplaced.sql"), """
                CREATE TABLE public.misplaced (
                    id integer
                );
                """);
    }

    private static Map<String, Set<ObjectReference>> dependencySnapshot(IDatabase database) {
        var result = new TreeMap<String, Set<ObjectReference>>();
        database.getDescendants().forEach(statement -> result.put(
                statement.getStatementType() + "|" + statement.getQualifiedName(),
                Set.copyOf(statement.getDependencies())));
        return result;
    }

    private static List<String> errorSnapshot(ISettings settings) {
        return settings.getErrors().stream()
                .map(error -> {
                    if (error instanceof AntlrError antlrError) {
                        return error.getClass().getName() + '|' + antlrError.getErrorType()
                                + '|' + antlrError.getMsg();
                    }
                    return error.getClass().getName() + '|' + error;
                })
                .sorted()
                .toList();
    }

    private static final class CountingPgProjectLoader extends PgProjectLoader {

        private CountingPgDatabase database;

        private CountingPgProjectLoader(Path dirPath, ISettings settings) {
            super(dirPath, settings);
        }

        @Override
        protected CountingPgDatabase createDatabase() {
            database = new CountingPgDatabase();
            return database;
        }

        private int getSortColumnsCallCount() {
            return database.sortColumnsCallCount;
        }
    }

    private static final class RecordingPgProjectLoader extends PgProjectLoader {

        private final Path project;
        private final List<String> dispatchedFiles = new ArrayList<>();

        private RecordingPgProjectLoader(Path project, ISettings settings) {
            super(project, settings);
            this.project = project;
        }

        @Override
        protected AbstractDumpLoader<PgDatabase> createDumpLoader(Path file) {
            dispatchedFiles.add(project.relativize(file).normalize().toString()
                    .replace('\\', '/'));
            return super.createDumpLoader(file);
        }

        private boolean wasDispatched(String relativePath) {
            return dispatchedFiles.contains(relativePath);
        }

        private List<String> dispatchedFiles() {
            return List.copyOf(dispatchedFiles);
        }

        private boolean parserTasksAreDrained() {
            return AntlrTaskManager.isDrained(antlrTasks);
        }

        private String relative(Path path) {
            return project.relativize(path).normalize().toString()
                    .replace('\\', '/');
        }

        private boolean accepts(Path file, Predicate<String> checkFilename) {
            return filterFile(file, checkFilename);
        }
    }

    private static final class ReferenceSortingPgProjectLoader extends PgProjectLoader {

        private ReferenceSortingPgProjectLoader(Path dirPath, ISettings settings) {
            super(dirPath, settings);
        }

        @Override
        protected AbstractDumpLoader<PgDatabase> createDumpLoader(Path file) {
            return new PgDumpLoader(file, settings);
        }
    }

    private static final class CountingPgDatabase extends PgDatabase {

        private int sortColumnsCallCount;

        @Override
        public void sortColumns() {
            sortColumnsCallCount++;
            super.sortColumns();
        }
    }

    private static final class DisabledObjectReferenceSettings extends CoreSettings {

        @Override
        public boolean isCollectObjectReferences() {
            return false;
        }
    }

    private static final class CancelAfterChecksMonitor extends NullMonitor {

        private final int cancelAt;
        private final AtomicInteger checks = new AtomicInteger();

        private CancelAfterChecksMonitor(int cancelAt) {
            this.cancelAt = cancelAt;
        }

        @Override
        public boolean isCancelled() {
            return checks.incrementAndGet() >= cancelAt;
        }

        private int checks() {
            return checks.get();
        }
    }

}
