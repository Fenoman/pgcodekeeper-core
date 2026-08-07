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
package org.pgcodekeeper.core.it.loader.ms;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.loader.IProjectInputFingerprintCapture;
import org.pgcodekeeper.core.database.base.loader.AbstractDumpLoader;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.database.ms.loader.MsProjectLoader;
import org.pgcodekeeper.core.database.ms.project.MsModelExporter;
import org.pgcodekeeper.core.database.ms.schema.MsDatabase;
import org.pgcodekeeper.core.it.IntegrationTestUtils;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.settings.ProjectFileFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.pgcodekeeper.core.it.IntegrationTestUtils.*;

/**
 * Tests for MS SQL ProjectLoader functionality
 */
class MsProjectLoaderTest {

    @Test
    void inputEnumerationMatchesMsProjectDispatch(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("enumerated-ms");
        writeProjectFile(project, "Security/Schemas/app.sql",
                "CREATE SCHEMA [app];\n");
        writeProjectFile(project, "Security/Schemas/dummy_tmp.sql",
                "CREATE SCHEMA [dummy_tmp];\n");
        writeProjectFile(project, "Tables/app.keep.sql",
                "CREATE TABLE [app].[keep] ([id] int);\n");
        writeProjectFile(project, "Tables/app.filtered.sql",
                "CREATE TABLE [app].[filtered] ([id] int);\n");
        writeProjectFile(project, "Tables/dummy_tmp.hidden.sql",
                "CREATE TABLE [dummy_tmp].[hidden] ([id] int);\n");
        writeProjectFile(project, "OVERRIDES/Tables/app.keep.sql",
                "ALTER AUTHORIZATION ON OBJECT::[app].[keep] TO [dbo];\n");
        var settings = settingsWithFilter(dir.resolve("ms.filter"),
                "EXCLUDE PATH Tables/app.filtered.sql\n");
        settings.setAdditionalExcludedSchemas(Set.of("dummy_tmp"));
        var loader = new RecordingMsProjectLoader(project, settings);
        var capture = (IProjectInputFingerprintCapture) loader;
        capture.enableInputFingerprintCapture();

        List<String> enumerated = loader.listInputFiles().stream()
                .map(loader::relative)
                .toList();

        Assertions.assertAll(
                () -> Assertions.assertEquals(List.of(
                        "Security/Schemas/app.sql",
                        "Tables/app.keep.sql",
                        "OVERRIDES/Tables/app.keep.sql"), enumerated),
                () -> Assertions.assertTrue(loader.dispatchedFiles().isEmpty()),
                () -> Assertions.assertTrue(loader.parserTasksAreDrained()),
                () -> Assertions.assertTrue(settings.getErrors().isEmpty()));

        loader.load();

        Assertions.assertAll(
                () -> Assertions.assertEquals(enumerated,
                        loader.dispatchedFiles()),
                () -> Assertions.assertEquals(
                        Set.copyOf(enumerated),
                        capture.getCapturedInputFingerprints()
                                .stream()
                                .map(fingerprint -> loader.relative(
                                        fingerprint.path()))
                                .collect(java.util.stream.Collectors.toSet())),
                () -> Assertions.assertTrue(settings.getErrors().isEmpty(),
                        settings.getErrors().toString()));
    }

    @Test
    void testProjectLoaderWithIgnoredSchemas(@TempDir Path dir) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        MsDatabaseProvider databaseProvider = new MsDatabaseProvider();
        var msDbDump = loadTestDump(databaseProvider, RESOURCE_MS_DUMP, IntegrationTestUtils.class, settings);

        new MsModelExporter(dir, msDbDump, Consts.UTF_8, settings).exportFull();

        createIgnoredSchemaFile(dir);

        var db = databaseProvider.getProjectLoader(dir, settings).load();

        for (var dbSchema : db.getSchemas()) {
            if (IGNORED_SCHEMAS_LIST.contains(dbSchema.getName())) {
                Assertions.fail("Ignored Schema loaded " + dbSchema.getName());
            } else {
                Assertions.assertEquals(msDbDump.getSchema(dbSchema.getName()), dbSchema,
                        "Schema from ms dump isn't equal schema from loader");
            }
        }
    }

    private static CoreSettings settingsWithFilter(Path filterFile, String rules)
            throws IOException {
        Files.writeString(filterFile, rules);
        var settings = new CoreSettings();
        settings.setProjectFileFilter(ProjectFileFilter.parse(filterFile));
        return settings;
    }

    private static void writeProjectFile(Path project, String relativePath,
            String sql) throws IOException {
        Path file = project.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, sql);
    }

    private static final class RecordingMsProjectLoader extends MsProjectLoader {

        private final Path project;
        private final List<String> dispatchedFiles = new ArrayList<>();

        private RecordingMsProjectLoader(Path project, ISettings settings) {
            super(project, settings);
            this.project = project;
        }

        @Override
        protected AbstractDumpLoader<MsDatabase> createDumpLoader(Path file) {
            dispatchedFiles.add(relative(file));
            return super.createDumpLoader(file);
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
    }
}
