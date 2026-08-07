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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.base.loader.AbstractDumpLoader;
import org.pgcodekeeper.core.database.base.project.AbstractWorkDirs;
import org.pgcodekeeper.core.database.pg.loader.PgProjectLoader;
import org.pgcodekeeper.core.database.pg.loader.PreanalyzedProjectLoader;
import org.pgcodekeeper.core.database.pg.routine.ReusableProjectRoutineBodySnapshot;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

class PgAdditionalExcludedSchemasTest {

    @Test
    void additionalExclusionsRejectAmbiguousOrLossyFilesystemNames() {
        var settings = new CoreSettings();

        var dotted = assertThrows(IllegalArgumentException.class,
                () -> settings.setAdditionalExcludedSchemas(Set.of("tenant.eu")));
        var unsafe = assertThrows(IllegalArgumentException.class,
                () -> settings.setAdditionalExcludedSchemas(Set.of("sales/eu")));
        var empty = assertThrows(IllegalArgumentException.class,
                () -> settings.setAdditionalExcludedSchemas(Set.of("")));

        assertTrue(dotted.getMessage().contains("tenant.eu"));
        assertTrue(unsafe.getMessage().contains("sales/eu"));
        assertTrue(empty.getMessage().contains("empty"));
    }

    @Test
    void additionalExclusionsRejectNullsAndAllowAnEmptySetToClear() {
        var settings = new CoreSettings();
        settings.setAdditionalExcludedSchemas(Set.of("dummy_tmp"));
        settings.setAdditionalExcludedSchemas(Set.of());
        assertFalse(settings.isAdditionalSchemaExcluded("dummy_tmp"));

        var nullSet = assertThrows(NullPointerException.class,
                () -> settings.setAdditionalExcludedSchemas(null));
        assertEquals("schemaNames", nullSet.getMessage());

        var setWithNull = new HashSet<String>();
        setWithNull.add(null);
        var nullName = assertThrows(NullPointerException.class,
                () -> settings.setAdditionalExcludedSchemas(setWithNull));
        assertEquals("schemaName", nullName.getMessage());
    }

    @Test
    void additionalExclusionsAreDefensiveCopiedAndProjectLoaderScoped() {
        var source = new HashSet<>(Set.of("dummy_tmp"));
        var settings = new CoreSettings();
        settings.setAdditionalExcludedSchemas(source);
        source.add("app");

        assertTrue(settings.isAllowedSchema("dummy_tmp"));
        assertTrue(settings.isAdditionalSchemaExcluded("dummy_tmp"));
        assertFalse(settings.isAdditionalSchemaExcluded("dummy_tmp_2"));
        assertFalse(settings.isAdditionalSchemaExcluded("DUMMY_TMP"));
        assertFalse(settings.isAdditionalSchemaExcluded("app"));

        ISettings copy = settings.copy();
        assertTrue(copy.isAllowedSchema("dummy_tmp"));
        assertTrue(copy.isAdditionalSchemaExcluded("dummy_tmp"));

        var independent = new CoreSettings();
        assertFalse(independent.isAdditionalSchemaExcluded("dummy_tmp"));
    }

    @Test
    void splitSchemaIsRejectedBeforeAnyDumpLoaderIsCreated(@TempDir Path project)
            throws Exception {
        createSplitSchema(project, "app", "keep");
        createSplitSchema(project, "dummy_tmp", "skip");
        var settings = new CoreSettings();
        settings.setAdditionalExcludedSchemas(Set.of("dummy_tmp"));

        try (var loader = new RecordingPgProjectLoader(project, settings)) {
            loader.load();

            assertTrue(loader.wasDispatched("SCHEMA/app/app.sql"));
            assertTrue(loader.wasDispatched("SCHEMA/app/TABLE/keep.sql"));
            assertFalse(loader.dispatchedUnder("SCHEMA/dummy_tmp/"));
        }
    }

    @Test
    void flatSchemaIsRejectedBeforeAnyDumpLoaderIsCreated(@TempDir Path project)
            throws Exception {
        Files.createDirectories(project.resolve("SCHEMA"));
        Files.createDirectories(project.resolve("TABLE"));
        Files.writeString(project.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                AbstractWorkDirs.IS_SPLIT_BY_SCHEMA + "=false\n");
        Files.writeString(project.resolve("SCHEMA/app.sql"),
                "CREATE SCHEMA app;\n");
        Files.writeString(project.resolve("SCHEMA/dummy_tmp.sql"),
                "CREATE SCHEMA dummy_tmp;\n");
        Files.writeString(project.resolve("TABLE/app.keep.sql"),
                "CREATE TABLE app.keep (id integer);\n");
        Files.writeString(project.resolve("TABLE/dummy_tmp.skip.sql"),
                "CREATE TABLE dummy_tmp.skip (id integer);\n");
        var settings = new CoreSettings();
        settings.setAdditionalExcludedSchemas(Set.of("dummy_tmp"));

        try (var loader = new RecordingPgProjectLoader(project, settings)) {
            loader.load();

            assertTrue(loader.wasDispatched("SCHEMA/app.sql"));
            assertTrue(loader.wasDispatched("TABLE/app.keep.sql"));
            assertFalse(loader.wasDispatched("SCHEMA/dummy_tmp.sql"));
            assertFalse(loader.wasDispatched("TABLE/dummy_tmp.skip.sql"));
        }
    }

    @Test
    void emptyAdditionalExclusionsPreserveLegacyFlatIgnoreSchemaMatching(
            @TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("SCHEMA"));
        Files.createDirectories(project.resolve("TABLE"));
        Files.writeString(project.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                AbstractWorkDirs.IS_SPLIT_BY_SCHEMA + "=false\n");
        Files.writeString(project.resolve("SCHEMA/tenant.sql"),
                "CREATE SCHEMA tenant;\n");
        Files.writeString(project.resolve("SCHEMA/tenant.eu.sql"),
                "CREATE SCHEMA \"tenant.eu\";\n");
        Files.writeString(project.resolve("TABLE/tenant.keep.sql"),
                "CREATE TABLE tenant.keep (id integer);\n");
        Files.writeString(project.resolve("TABLE/tenant.eu.keep.sql"),
                "CREATE TABLE \"tenant.eu\".keep (id integer);\n");
        Path ignoreSchemas = project.resolve(".pgcodekeeperignoreschema");
        Files.writeString(ignoreSchemas, "SHOW ALL\nHIDE NONE tenant\n");

        var settings = new CoreSettings();
        settings.addIgnoreSchemaList(ignoreSchemas);
        try (var loader = new RecordingPgProjectLoader(project, settings)) {
            loader.load();

            assertFalse(loader.wasDispatched("SCHEMA/tenant.sql"));
            assertFalse(loader.wasDispatched("TABLE/tenant.keep.sql"));
            assertFalse(loader.wasDispatched("SCHEMA/tenant.eu.sql"));
            assertFalse(loader.wasDispatched("TABLE/tenant.eu.keep.sql"));
        }
    }

    @Test
    void flatExclusionPreservesDottedSchemaPrefixCollision(
            @TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("SCHEMA"));
        Files.createDirectories(project.resolve("TABLE"));
        Files.writeString(project.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                AbstractWorkDirs.IS_SPLIT_BY_SCHEMA + "=false\n");
        Files.writeString(project.resolve("SCHEMA/tenant.sql"),
                "CREATE SCHEMA tenant;\n");
        Files.writeString(project.resolve("SCHEMA/tenant.eu.sql"),
                "CREATE SCHEMA \"tenant.eu\";\n");
        Files.writeString(project.resolve("TABLE/tenant.keep.sql"),
                "CREATE TABLE tenant.keep (id integer);\n");
        Files.writeString(project.resolve("TABLE/tenant.eu.keep.sql"),
                "CREATE TABLE \"tenant.eu\".keep (id integer);\n");

        var settings = new CoreSettings();
        settings.setAdditionalExcludedSchemas(Set.of("tenant"));
        try (var loader = new RecordingPgProjectLoader(project, settings)) {
            loader.load();

            assertFalse(loader.wasDispatched("SCHEMA/tenant.sql"));
            assertFalse(loader.wasDispatched("TABLE/tenant.keep.sql"));
            assertTrue(loader.wasDispatched("SCHEMA/tenant.eu.sql"));
            assertTrue(loader.wasDispatched("TABLE/tenant.eu.keep.sql"));
        }
    }

    @Test
    void flatPartialProjectDoesNotGuessSchemaFromAmbiguousObjectFileName(
            @TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("TABLE"));
        Files.writeString(project.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                AbstractWorkDirs.IS_SPLIT_BY_SCHEMA + "=false\n");
        Files.writeString(project.resolve("TABLE/tenant.eu.keep.sql"),
                "CREATE TABLE \"tenant.eu\".keep (id integer);\n");

        var settings = new CoreSettings();
        settings.setAdditionalExcludedSchemas(Set.of("tenant"));
        try (var loader = new RecordingPgProjectLoader(project, settings)) {
            loader.load();

            assertTrue(loader.wasDispatched("TABLE/tenant.eu.keep.sql"));
        }
    }

    @Test
    void flatPartialSchemaManifestDoesNotHideDottedSchemaObject(
            @TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("SCHEMA"));
        Files.createDirectories(project.resolve("TABLE"));
        Files.writeString(project.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                AbstractWorkDirs.IS_SPLIT_BY_SCHEMA + "=false\n");
        Files.writeString(project.resolve("SCHEMA/tenant.sql"),
                "CREATE SCHEMA tenant;\n");
        Files.writeString(project.resolve("TABLE/tenant.eu.keep.sql"),
                "CREATE TABLE \"tenant.eu\".keep (id integer);\n");

        var settings = new CoreSettings();
        settings.setAdditionalExcludedSchemas(Set.of("tenant"));
        try (var loader = new RecordingPgProjectLoader(project, settings)) {
            loader.load();

            assertFalse(loader.wasDispatched("SCHEMA/tenant.sql"));
            assertTrue(loader.wasDispatched("TABLE/tenant.eu.keep.sql"));
        }
    }

    @Test
    void flatAmbiguousFileOfExcludedSchemaIsDroppedWithoutAnError(
            @TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("SCHEMA"));
        Files.createDirectories(project.resolve("TABLE"));
        Files.writeString(project.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                AbstractWorkDirs.IS_SPLIT_BY_SCHEMA + "=false\n");
        Files.writeString(project.resolve("SCHEMA/app.sql"),
                "CREATE SCHEMA app;\n");
        Files.writeString(project.resolve("SCHEMA/dummy_tmp.sql"),
                "CREATE SCHEMA dummy_tmp;\n");
        Files.writeString(project.resolve("TABLE/app.keep.sql"),
                "CREATE TABLE app.keep (id integer);\n");
        // Ambiguous by name: "dummy_tmp" plus a dotted object name. The file
        // filter cannot tell it from a quoted "dummy_tmp.skip" schema.
        Files.writeString(project.resolve("TABLE/dummy_tmp.skip.leak.sql"),
                "CREATE TABLE dummy_tmp.\"skip.leak\" (id integer);\n");

        var settings = new CoreSettings();
        settings.setAdditionalExcludedSchemas(Set.of("dummy_tmp"));

        try (var loader = new RecordingPgProjectLoader(project, settings)) {
            PgDatabase db = loader.load();

            // the ambiguity is only resolvable by the parser, so the file is
            // still dispatched, but nothing of the excluded schema is loaded
            assertTrue(loader.wasDispatched("TABLE/dummy_tmp.skip.leak.sql"));
            assertNull(db.getSchema("dummy_tmp"));
            assertNotNull(db.getSchema("app"));
            assertNotNull(db.getSchema("app").getTable("keep"));
            assertEquals(List.of(), settings.getErrors());
        }
    }

    @Test
    void flatAmbiguousFileOfMissingSchemaStillReportsAnError(
            @TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("SCHEMA"));
        Files.createDirectories(project.resolve("TABLE"));
        Files.writeString(project.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME),
                AbstractWorkDirs.IS_SPLIT_BY_SCHEMA + "=false\n");
        Files.writeString(project.resolve("SCHEMA/app.sql"),
                "CREATE SCHEMA app;\n");
        Files.writeString(project.resolve("TABLE/tenant.eu.keep.sql"),
                "CREATE TABLE \"tenant.eu\".keep (id integer);\n");

        var settings = new CoreSettings();
        settings.setAdditionalExcludedSchemas(Set.of("dummy_tmp"));

        try (var loader = new RecordingPgProjectLoader(project, settings)) {
            loader.load();

            assertTrue(loader.wasDispatched("TABLE/tenant.eu.keep.sql"));
            assertEquals(1, settings.getErrors().size());
        }
    }

    @Test
    void reusableModelCaptureIsRefusedWhileExclusionsAreActive(
            @TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("SCHEMA"));
        var settings = new CoreSettings();
        settings.setAdditionalExcludedSchemas(Set.of("dummy_tmp"));

        try (var loader = new PgProjectLoader(project, settings)) {
            var refused = assertThrows(IllegalStateException.class,
                    loader::enableReusableModelCapture);
            assertTrue(refused.getMessage().contains("exclusions"));
        }
    }

    @Test
    void preanalyzedModelIsRefusedWhileExclusionsAreActive() {
        var database = new PgDatabase(false);
        var snapshot = ReusableProjectRoutineBodySnapshot.capture(database);
        var settings = new CoreSettings();
        settings.setAdditionalExcludedSchemas(Set.of("dummy_tmp"));

        var refused = assertThrows(IllegalArgumentException.class,
                () -> new PreanalyzedProjectLoader(database, snapshot,
                        settings, "project"));
        assertTrue(refused.getMessage().contains("exclusions"));

        // the same model stays reusable for a load without exclusions
        assertNotNull(new PreanalyzedProjectLoader(database, snapshot,
                new CoreSettings(), "project"));
    }

    private static void createSplitSchema(Path project, String schema,
            String table) throws IOException {
        Path schemaDir = Files.createDirectories(
                project.resolve("SCHEMA").resolve(schema));
        Path tableDir = Files.createDirectories(schemaDir.resolve("TABLE"));
        Files.writeString(schemaDir.resolve(schema + ".sql"),
                "CREATE SCHEMA " + schema + ";\n");
        Files.writeString(tableDir.resolve(table + ".sql"),
                "CREATE TABLE " + schema + '.' + table + " (id integer);\n");
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

        private boolean dispatchedUnder(String relativeDirectory) {
            return dispatchedFiles.stream().anyMatch(
                    path -> path.startsWith(relativeDirectory));
        }
    }
}
