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
package org.pgcodekeeper.core.it.loader.ch;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.loader.IProjectInputFingerprintCapture;
import org.pgcodekeeper.core.database.base.loader.AbstractDumpLoader;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;
import org.pgcodekeeper.core.database.ch.loader.ChProjectLoader;
import org.pgcodekeeper.core.database.ch.schema.ChDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.settings.ProjectFileFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class ChProjectLoaderTest {

    @Test
    void inputEnumerationMatchesChProjectDispatch(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path project = dir.resolve("enumerated-ch");
        writeProjectFile(project, "DATABASE/app/app.sql",
                "CREATE DATABASE app ENGINE = Atomic;\n");
        writeProjectFile(project, "DATABASE/app/TABLE/keep.sql", """
                CREATE TABLE app.keep (id Int32)
                ENGINE = MergeTree
                ORDER BY id;
                """);
        writeProjectFile(project, "DATABASE/app/TABLE/filtered.sql", """
                CREATE TABLE app.filtered (id Int32)
                ENGINE = MergeTree
                ORDER BY id;
                """);
        writeProjectFile(project, "DATABASE/dummy_tmp/dummy_tmp.sql",
                "CREATE DATABASE dummy_tmp ENGINE = Atomic;\n");
        writeProjectFile(project, "DATABASE/dummy_tmp/TABLE/hidden.sql", """
                CREATE TABLE dummy_tmp.hidden (id Int32)
                ENGINE = MergeTree
                ORDER BY id;
                """);
        writeProjectFile(project, "OVERRIDES/DATABASE/app/TABLE/keep.sql",
                "GRANT SELECT ON app.keep TO reader;\n");
        var settings = settingsWithFilter(dir.resolve("ch.filter"),
                "EXCLUDE PATH DATABASE/app/TABLE/filtered.sql\n");
        settings.setAdditionalExcludedSchemas(Set.of("dummy_tmp"));
        var loader = new RecordingChProjectLoader(project, settings);
        var capture = (IProjectInputFingerprintCapture) loader;
        capture.enableInputFingerprintCapture();

        List<String> enumerated = loader.listInputFiles().stream()
                .map(loader::relative)
                .toList();

        Assertions.assertAll(
                () -> Assertions.assertEquals(List.of(
                        "DATABASE/app/app.sql",
                        "DATABASE/app/TABLE/keep.sql",
                        "OVERRIDES/DATABASE/app/TABLE/keep.sql"), enumerated),
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

    private static final class RecordingChProjectLoader extends ChProjectLoader {

        private final Path project;
        private final List<String> dispatchedFiles = new ArrayList<>();

        private RecordingChProjectLoader(Path project, ISettings settings) {
            super(project, settings);
            this.project = project;
        }

        @Override
        protected AbstractDumpLoader<ChDatabase> createDumpLoader(Path file) {
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
