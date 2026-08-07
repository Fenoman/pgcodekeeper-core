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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.loader.AbstractDumpLoader;
import org.pgcodekeeper.core.database.base.loader.AbstractLibraryLoader;
import org.pgcodekeeper.core.database.base.loader.AbstractLoader;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.loader.PgLibraryLoader;
import org.pgcodekeeper.core.database.pg.loader.PgProjectLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.library.Library;
import org.pgcodekeeper.core.library.LibraryXmlStore;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

@Isolated("installs the package-local parser execution observer")
class ParserExecutionGraphIntegrationTest {

    private static final int WORKERS = 2;

    @Test
    void actualProjectGraphUsesOneDedicatedParserOperation(@TempDir Path dir)
            throws Exception {
        Path project = createProjectGraph(dir);

        try (var probe = ParserExecutionProbeHarness.install()) {
            var loader = new ObservedPgProjectLoader(project, dedicatedSettings(),
                    dir.resolve("meta"));
            Queue<AntlrTask<?>> rootTasks = loader.parserTasks();
            PgDatabase database;

            try (loader) {
                database = loader.load();
                assertRunning(probe.onlySession().snapshot(), 1);
            }

            assertClosed(probe.onlySession().snapshot(), rootTasks);
            assertGraphLoaded(database);
            assertEquals(1, probe.sessionCount());
        }
    }

    @Test
    void cancellationInterruptsActiveProjectFileChild(@TempDir Path dir)
            throws Exception {
        Path project = createProjectGraph(dir);
        var blocker = new BlockingParserTask();

        try (var probe = ParserExecutionProbeHarness.install()) {
            var loader = new BlockingPgProjectLoader(project, dedicatedSettings(),
                    dir.resolve("meta"), blocker, false);
            cancelActiveChild(loader, blocker, probe);
        }
    }

    @Test
    void cancellationInterruptsActiveZipLibraryChild(@TempDir Path dir)
            throws Exception {
        Path project = createProjectGraph(dir);
        var blocker = new BlockingParserTask();

        try (var probe = ParserExecutionProbeHarness.install()) {
            var loader = new BlockingPgProjectLoader(project, dedicatedSettings(),
                    dir.resolve("meta"), blocker, true);
            cancelActiveChild(loader, blocker, probe);
            assertTrue(blocker.source().toString().endsWith("zip.sql"),
                    () -> String.valueOf(blocker.source()));
        }
    }

    @Test
    void inheritedFileLoadersAllocateNoParserInfrastructure() throws Exception {
        var settings = dedicatedSettings();

        try (var probe = ParserExecutionProbeHarness.install()) {
            Queue<AntlrTask<?>> rootTasks = AntlrTaskManager.createTaskQueue(
                    settings.getParserExecutionPolicy());
            try {
                for (int i = 0; i < 22_000; i++) {
                    var child = new InheritedLoader(settings, rootTasks);
                    assertSame(rootTasks, child.parserTasks());
                    child.close();
                }

                var snapshot = probe.onlySession().snapshot();
                assertEquals(1, probe.sessionCount());
                assertEquals(1, snapshot.scopes());
                assertEquals(1, snapshot.queues());
                assertEquals(1, snapshot.lazyExecutors());
            } finally {
                AntlrTaskManager.close(rootTasks);
            }
        }
    }

    private static void cancelActiveChild(ObservedPgProjectLoader loader,
            BlockingParserTask blocker, ParserExecutionProbeHarness probe)
            throws Exception {
        Queue<AntlrTask<?>> rootTasks = loader.parserTasks();
        ExecutorService owner = Executors.newSingleThreadExecutor();
        Future<PgDatabase> load = owner.submit(loader::load);

        try {
            assertTrue(blocker.entered.await(10, TimeUnit.SECONDS));
            assertSame(rootTasks, blocker.queue());
            assertRunning(probe.onlySession().snapshot(), 1);

            loader.cancel();
            assertTrue(blocker.interrupted.await(10, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> load.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedException,
                    () -> String.valueOf(failure.getCause()));
        } finally {
            blocker.release.countDown();
            loader.close();
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertClosed(probe.onlySession().snapshot(), rootTasks);
    }

    private static void assertRunning(
            ParserExecutionProbeHarness.Snapshot snapshot, int queues) {
        assertEquals(1, snapshot.scopes());
        assertEquals(queues, snapshot.queues());
        assertEquals(1, snapshot.lazyExecutors());
        assertFalse(snapshot.workerNames().isEmpty());
        assertTrue(snapshot.workerNames().stream()
                .allMatch(name -> name.startsWith("pgck-antlr-index-")));
        assertTrue(snapshot.peakWorkers() >= 1);
        assertTrue(snapshot.peakWorkers() <= WORKERS);
    }

    private static void assertClosed(
            ParserExecutionProbeHarness.Snapshot snapshot,
            Queue<AntlrTask<?>> rootTasks) {
        assertEquals(0, snapshot.liveWorkers());
        assertTrue(snapshot.shutdown());
        assertTrue(snapshot.terminated());
        assertThrows(IllegalStateException.class,
                () -> AntlrTaskManager.submit(rootTasks,
                        () -> null, ignored -> { }));
    }

    private static CoreSettings dedicatedSettings() {
        var settings = new CoreSettings();
        settings.setParserExecutionPolicy(
                ParserExecutionPolicy.dedicated(WORKERS));
        return settings;
    }

    private static Path createProjectGraph(Path dir) throws IOException {
        Path project = dir.resolve("project");
        writeTable(project, "public", "root_table");

        Path simpleLibrary = Files.createDirectories(
                dir.resolve("simple-library"));
        Files.writeString(simpleLibrary.resolve("simple.sql"),
                "CREATE SCHEMA simple_lib; "
                        + "CREATE TABLE simple_lib.simple_library_table"
                        + "(id integer);\n");

        Path nestedProject = dir.resolve("nested-project");
        Files.writeString(Files.createDirectories(nestedProject)
                .resolve(Consts.FILENAME_WORKING_DIR_MARKER), "");
        writeTable(nestedProject, "nested_lib", "nested_table");

        Path zipLibrary = dir.resolve("library.zip");
        try (FileSystem zip = FileSystems.newFileSystem(zipLibrary,
                Map.of("create", "true"))) {
            Files.writeString(zip.getPath("zip.sql"),
                    "CREATE SCHEMA zip_lib; "
                            + "CREATE TABLE zip_lib.zip_library_table"
                            + "(id integer);\n");
        }

        new LibraryXmlStore(project.resolve(LibraryXmlStore.FILE_NAME))
                .writeDependencies(List.of(
                        new Library("", zipLibrary.toString(), false, ""),
                        new Library("", simpleLibrary.toString(), false, ""),
                        new Library("", nestedProject.toString(), false, "")),
                        false);
        return project;
    }

    private static void writeTable(Path project, String schemaName,
            String tableName) throws IOException {
        Path schema = Files.createDirectories(
                project.resolve("SCHEMA").resolve(schemaName));
        Files.writeString(schema.resolve(schemaName + ".sql"),
                "CREATE SCHEMA " + schemaName + ";\n");
        Files.writeString(Files.createDirectories(schema.resolve("TABLE"))
                        .resolve(tableName + ".sql"),
                "CREATE TABLE " + schemaName + '.' + tableName
                        + "(id integer);\n");
    }

    private static void assertGraphLoaded(PgDatabase database) {
        assertNotNull(database.getStatement(new ObjectReference(
                "public", "root_table", DbObjType.TABLE)));
        assertNotNull(database.getStatement(new ObjectReference(
                "simple_lib", "simple_library_table", DbObjType.TABLE)));
        assertNotNull(database.getStatement(new ObjectReference(
                "nested_lib", "nested_table", DbObjType.TABLE)));
        assertNotNull(database.getStatement(new ObjectReference(
                "zip_lib", "zip_library_table", DbObjType.TABLE)));
    }

    private static class ObservedPgProjectLoader extends PgProjectLoader {

        private ObservedPgProjectLoader(Path project, ISettings settings,
                Path metaPath) {
            super(project, settings, List.of(), List.of(), List.of(), metaPath);
        }

        final Queue<AntlrTask<?>> parserTasks() {
            return antlrTasks;
        }
    }

    private static final class BlockingPgProjectLoader
            extends ObservedPgProjectLoader {

        private final BlockingParserTask blocker;
        private final boolean blockLibrary;

        private BlockingPgProjectLoader(Path project, ISettings settings,
                Path metaPath, BlockingParserTask blocker,
                boolean blockLibrary) {
            super(project, settings, metaPath);
            this.blocker = blocker;
            this.blockLibrary = blockLibrary;
        }

        @Override
        protected AbstractDumpLoader<PgDatabase> createDumpLoader(Path file) {
            return blockLibrary
                    ? super.createDumpLoader(file)
                    : new BlockingPgDumpLoader(file, settings, false,
                            antlrTasks, blocker);
        }

        @Override
        protected AbstractLibraryLoader<PgDatabase> createLibraryLoader(
                PgDatabase db) {
            return blockLibrary
                    ? new BlockingPgLibraryLoader(db, metaPath,
                            new HashSet<>(), settings, antlrTasks, blocker)
                    : super.createLibraryLoader(db);
        }
    }

    private static final class BlockingPgLibraryLoader
            extends PgLibraryLoader {

        private final BlockingParserTask blocker;

        private BlockingPgLibraryLoader(PgDatabase database, Path metaPath,
                Set<String> loadedPaths, ISettings settings,
                Queue<AntlrTask<?>> rootTasks, BlockingParserTask blocker) {
            super(database, metaPath, loadedPaths, settings, rootTasks);
            this.blocker = blocker;
        }

        @Override
        protected PgDumpLoader getDumpLoader(Path path, ISettings settings) {
            return new BlockingPgDumpLoader(path, settings, true,
                    antlrTasks, blocker);
        }
    }

    private static final class BlockingPgDumpLoader extends PgDumpLoader {

        private final Path source;
        private final BlockingParserTask blocker;

        private BlockingPgDumpLoader(Path source, ISettings settings,
                boolean sortColumnsAfterParse,
                Queue<AntlrTask<?>> rootTasks, BlockingParserTask blocker) {
            super(source, settings, sortColumnsAfterParse, rootTasks);
            this.source = source;
            this.blocker = blocker;
        }

        @Override
        public void loadWithoutAnalyze(PgDatabase db,
                Queue<AntlrTask<?>> antlrTasks) {
            super.loadWithoutAnalyze(db, antlrTasks);
            blocker.submit(source, antlrTasks);
        }
    }

    private static final class BlockingParserTask {

        private final AtomicBoolean armed = new AtomicBoolean(true);
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Queue<AntlrTask<?>> queue;
        private volatile Path source;

        private void submit(Path source, Queue<AntlrTask<?>> queue) {
            if (!armed.compareAndSet(true, false)) {
                return;
            }
            this.source = source;
            this.queue = queue;
            AntlrTaskManager.submit(queue, () -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                    throw ex;
                }
                return null;
            }, ignored -> { });
        }

        private Queue<AntlrTask<?>> queue() {
            return queue;
        }

        private Path source() {
            return source;
        }
    }

    private static final class InheritedLoader
            extends AbstractLoader<PgDatabase> {

        private InheritedLoader(ISettings settings,
                Queue<AntlrTask<?>> rootTasks) {
            super(settings, "child", rootTasks);
        }

        @Override
        protected PgDatabase loadInternal() {
            return createDatabase();
        }

        @Override
        protected PgDatabase createDatabase() {
            return new PgDatabase();
        }

        private Queue<AntlrTask<?>> parserTasks() {
            return antlrTasks;
        }
    }
}
