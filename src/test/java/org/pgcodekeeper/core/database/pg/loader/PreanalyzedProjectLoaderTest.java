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
package org.pgcodekeeper.core.database.pg.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.IComparisonAnalysisLifecycle;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.routine.DeferredRoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyAnalysisStats;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCatalog;
import org.pgcodekeeper.core.database.pg.routine.ReusableProjectRoutineBodySnapshot;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

@Isolated("reads and resets the process-wide routine body analysis counters")
class PreanalyzedProjectLoaderTest {

    private static final String PROJECT_FUNCTION = """
            CREATE FUNCTION public.answer(p integer)
            RETURNS integer
            LANGUAGE plpgsql
            AS $function$
            BEGIN
                RETURN p + 1;
            END
            $function$;
            """;

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @Test
    void preanalyzedOldModelUsesBothCoordinatorPhasesWithoutReparseAndMatchesFreshTree(
            @TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        Path originalDatabase = root.resolve("original.sql");
        Path changedDatabase = root.resolve("changed.sql");
        writeProject(project);
        Files.writeString(originalDatabase, """
                CREATE SCHEMA public;
                CREATE TABLE public.items (id integer);
                """ + PROJECT_FUNCTION);
        Files.writeString(changedDatabase, """
                CREATE SCHEMA public;
                CREATE TABLE public.items (id integer, label text);
                """);

        AtomicReference<PgProjectLoader> seedLoader = new AtomicReference<>();
        LoadedComparison seed = PgCodeKeeperApi.loadForComparison(
                factories(project, originalDatabase, seedLoader, true),
                new CoreSettings());
        ReusableProjectRoutineBodySnapshot snapshot = seedLoader.get()
                .takeReusableProjectRoutineBodySnapshot().orElseThrow();
        assertTrue(seed.oldDatabase().getAnalysisLaunchers().isEmpty());

        PgRoutineBodyAnalysisStats.reset();
        AtomicReference<PreanalyzedProjectLoader> warmLoader = new AtomicReference<>();
        LoadedComparison warm = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(settings -> {
                    var loader = new PreanalyzedProjectLoader(
                            (PgDatabase) seed.oldDatabase(), snapshot,
                            settings, "project");
                    warmLoader.set(loader);
                    return loader;
                }, settings -> provider.getDumpLoader(changedDatabase, settings)),
                new CoreSettings());
        TreeElement warmTree = PgCodeKeeperApi.createTree(warm);

        assertSame(seed.oldDatabase(), warm.oldDatabase());
        assertSame(seed.oldDatabase(), warmLoader.get().getDatabase());
        assertEquals(0, PgRoutineBodyAnalysisStats.getParsedBodies(),
                "the preanalyzed project side must not reparse routine bodies");

        LoadedComparison fresh = PgCodeKeeperApi.loadForComparison(
                factories(project, changedDatabase, new AtomicReference<>(), false),
                new CoreSettings());
        TreeElement freshTree = PgCodeKeeperApi.createTree(fresh);

        assertEquals(treeSnapshot(freshTree), treeSnapshot(warmTree));
    }

    @Test
    void snapshotStaysUnavailableUntilBothAnalysisPhasesFinishAndIsOneShot(
            @TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        writeProject(project);
        var peer = new BlockingAnalysisLoader(false);
        peer.blockClose(null);
        var projectLoader = new AtomicReference<PgProjectLoader>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LoadedComparison> comparison = executor.submit(() ->
                PgCodeKeeperApi.loadForComparison(
                        new ComparisonLoaderFactories(settings -> {
                            var loader = provider.getProjectLoader(project, settings);
                            loader.enableReusableModelCapture();
                            projectLoader.set(loader);
                            return loader;
                        }, settings -> peer.withSettings(settings)),
                        new CoreSettings()));

        try {
            assertTrue(peer.analysisEntered.await(5, TimeUnit.SECONDS));
            awaitCondition(() -> {
                PgProjectLoader loader = projectLoader.get();
                return loader != null && loader.getDatabase() != null
                        && loader.getDatabase().getAnalysisLaunchers().isEmpty();
            });
            assertTrue(projectLoader.get()
                    .takeReusableProjectRoutineBodySnapshot().isEmpty());

            peer.releaseAnalysis.countDown();
            assertTrue(peer.closeEntered.await(5, TimeUnit.SECONDS));
            assertTrue(projectLoader.get()
                    .takeReusableProjectRoutineBodySnapshot().isEmpty(),
                    "snapshot must remain pending until peer close succeeds");
            peer.releaseClose.countDown();
            comparison.get(5, TimeUnit.SECONDS);

            assertTrue(projectLoader.get()
                    .takeReusableProjectRoutineBodySnapshot().isPresent());
            assertTrue(projectLoader.get()
                    .takeReusableProjectRoutineBodySnapshot().isEmpty());
        } finally {
            peer.releaseAnalysis.countDown();
            peer.releaseClose.countDown();
            comparison.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedPeerCloseNeverPublishesReusableSnapshot(
            @TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        writeProject(project);
        IOException closeFailure =
                new IOException("controlled peer close failure");
        var peer = new BlockingAnalysisLoader(false);
        peer.blockClose(closeFailure);
        var projectLoader = new AtomicReference<PgProjectLoader>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LoadedComparison> comparison = executor.submit(() ->
                PgCodeKeeperApi.loadForComparison(
                        new ComparisonLoaderFactories(settings -> {
                            var loader = provider.getProjectLoader(
                                    project, settings);
                            loader.enableReusableModelCapture();
                            projectLoader.set(loader);
                            return loader;
                        }, settings -> peer.withSettings(settings)),
                        new CoreSettings()));

        try {
            assertTrue(peer.analysisEntered.await(5, TimeUnit.SECONDS));
            peer.releaseAnalysis.countDown();
            assertTrue(peer.closeEntered.await(5, TimeUnit.SECONDS));
            assertTrue(projectLoader.get()
                    .takeReusableProjectRoutineBodySnapshot().isEmpty());

            peer.releaseClose.countDown();
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> comparison.get(5, TimeUnit.SECONDS));
            assertSame(closeFailure, failure.getCause());
            assertTrue(projectLoader.get()
                    .takeReusableProjectRoutineBodySnapshot().isEmpty());
        } finally {
            peer.releaseAnalysis.countDown();
            peer.releaseClose.countDown();
            comparison.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationWhilePeerCloseIsBlockedNeverPublishesReusableSnapshot(
            @TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        writeProject(project);
        var peer = new BlockingAnalysisLoader(false);
        peer.blockClose(null);
        var projectLoader = new AtomicReference<PgProjectLoader>();
        var caller = new CoreSettings();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LoadedComparison> comparison = executor.submit(() ->
                PgCodeKeeperApi.loadForComparison(
                        new ComparisonLoaderFactories(settings -> {
                            var loader = provider.getProjectLoader(
                                    project, settings);
                            loader.enableReusableModelCapture();
                            projectLoader.set(loader);
                            return loader;
                        }, settings -> peer.withSettings(settings)),
                        caller));

        try {
            assertTrue(peer.analysisEntered.await(5, TimeUnit.SECONDS));
            peer.releaseAnalysis.countDown();
            assertTrue(peer.closeEntered.await(5, TimeUnit.SECONDS));

            caller.getMonitor().setCancelled(true);
            peer.releaseClose.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> comparison.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(projectLoader.get()
                    .takeReusableProjectRoutineBodySnapshot().isEmpty());
        } finally {
            peer.releaseAnalysis.countDown();
            peer.releaseClose.countDown();
            comparison.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptWhilePeerCloseIsBlockedNeverPublishesReusableSnapshot(
            @TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        writeProject(project);
        var peer = new BlockingAnalysisLoader(false);
        peer.blockClose(null);
        var projectLoader = new AtomicReference<PgProjectLoader>();
        var coordinatorThread = new AtomicReference<Thread>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LoadedComparison> comparison = executor.submit(() -> {
            coordinatorThread.set(Thread.currentThread());
            return PgCodeKeeperApi.loadForComparison(
                    new ComparisonLoaderFactories(settings -> {
                        var loader = provider.getProjectLoader(
                                project, settings);
                        loader.enableReusableModelCapture();
                        projectLoader.set(loader);
                        return loader;
                    }, settings -> peer.withSettings(settings)),
                    new CoreSettings());
        });

        try {
            assertTrue(peer.analysisEntered.await(5, TimeUnit.SECONDS));
            peer.releaseAnalysis.countDown();
            assertTrue(peer.closeEntered.await(5, TimeUnit.SECONDS));

            coordinatorThread.get().interrupt();
            peer.releaseClose.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> comparison.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(projectLoader.get()
                    .takeReusableProjectRoutineBodySnapshot().isEmpty());
        } finally {
            peer.releaseAnalysis.countDown();
            peer.releaseClose.countDown();
            comparison.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedFinalErrorMergeNeverPublishesReusableSnapshot(
            @TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        writeProject(project);
        var projectLoader = new AtomicReference<PgProjectLoader>();
        var caller = new FinalMergeFailingSettings();
        var peer = new BlockingAnalysisLoader(false);
        peer.releaseAnalysis.countDown();

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> PgCodeKeeperApi.loadForComparison(
                        new ComparisonLoaderFactories(settings -> {
                            var loader = provider.getProjectLoader(
                                    project, settings);
                            loader.enableReusableModelCapture();
                            projectLoader.set(loader);
                            return loader;
                        }, settings -> peer.withSettings(settings)),
                        caller));

        assertSame(caller.failure, failure);
        assertTrue(projectLoader.get()
                .takeReusableProjectRoutineBodySnapshot().isEmpty());
    }

    @Test
    void failedPeerAnalysisSuccessCallbackRevokesBothSides(
            @TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        writeProject(project);
        var projectLoader = new AtomicReference<PgProjectLoader>();
        var peer = new BlockingAnalysisLoader(false);
        peer.analysisSuccessFailure =
                new IllegalStateException("controlled lifecycle failure");
        peer.releaseAnalysis.countDown();

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> PgCodeKeeperApi.loadForComparison(
                        new ComparisonLoaderFactories(settings -> {
                            var loader = new FailureRecordingProjectLoader(
                                    project, settings);
                            loader.enableReusableModelCapture();
                            projectLoader.set(loader);
                            return loader;
                        }, settings -> peer.withSettings(settings)),
                        new CoreSettings()));

        assertSame(peer.analysisSuccessFailure, failure);
        assertEquals(1, peer.failedCalls);
        assertTrue(((FailureRecordingProjectLoader) projectLoader.get())
                .failedCalls > 0);
        assertTrue(projectLoader.get()
                .takeReusableProjectRoutineBodySnapshot().isEmpty());
    }

    @Test
    void failedPeerAnalysisDiscardsPendingSnapshot(@TempDir Path root)
            throws Exception {
        Path project = root.resolve("project");
        writeProject(project);
        var peer = new BlockingAnalysisLoader(true);
        var projectLoader = new AtomicReference<PgProjectLoader>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LoadedComparison> comparison = executor.submit(() ->
                PgCodeKeeperApi.loadForComparison(
                        new ComparisonLoaderFactories(settings -> {
                            var loader = provider.getProjectLoader(project, settings);
                            loader.enableReusableModelCapture();
                            projectLoader.set(loader);
                            return loader;
                        }, settings -> peer.withSettings(settings)),
                        new CoreSettings()));

        try {
            assertTrue(peer.analysisEntered.await(5, TimeUnit.SECONDS));
            awaitCondition(() -> {
                PgProjectLoader loader = projectLoader.get();
                return loader != null && loader.getDatabase() != null
                        && loader.getDatabase().getAnalysisLaunchers().isEmpty();
            });
            peer.releaseAnalysis.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> comparison.get(5, TimeUnit.SECONDS));
            assertSame(peer.failure, failure.getCause());
            assertTrue(projectLoader.get()
                    .takeReusableProjectRoutineBodySnapshot().isEmpty());
        } finally {
            peer.releaseAnalysis.countDown();
            comparison.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void captureKeepsProjectFullBodyAnalysisButDoesNotMutateSharedSetting(
            @TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        writeProject(project);
        var settings = new CoreSettings();
        var loader = provider.getProjectLoader(project, settings);
        loader.enableReusableModelCapture();

        PgDatabase database = loader.load();
        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(database);
        var function = database.getSchema("public").getFunction("answer(integer)");
        var candidate = catalog.removeCandidate(
                org.pgcodekeeper.core.database.pg.routine.RoutineIdentity.from(function));
        candidate.shareTo(new DeferredRoutineBodySource(candidate.authorization()));

        PgRoutineBodyAnalysisStats.reset();
        loader.loadAndAnalyze();

        assertTrue(settings.isPgRoutineBodySkipMatchedAnalysis(),
                "capture must not disable the setting inherited by the NEW side");
        assertEquals(1, PgRoutineBodyAnalysisStats.getParsedBodies());
        assertEquals(0, PgRoutineBodyAnalysisStats.getSkippedBodies());
        assertTrue(loader.takeReusableProjectRoutineBodySnapshot().isEmpty(),
                "standalone analysis is not a successful coordinated comparison");
        loader.close();
    }

    @Test
    void captureCannotBeEnabledAfterLoadingStartsBeforeDatabaseIsPublished(
            @TempDir Path root) throws Exception {
        var loader = new BlockingProjectLoader(root, new CoreSettings());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<PgDatabase> load = executor.submit(loader::load);

        try {
            assertTrue(loader.loadEntered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    loader::enableReusableModelCapture);
        } finally {
            loader.releaseLoad.countDown();
            load.get(5, TimeUnit.SECONDS);
            loader.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private ComparisonLoaderFactories factories(Path project, Path database,
            AtomicReference<PgProjectLoader> loaderRef, boolean capture) {
        return new ComparisonLoaderFactories(settings -> {
            var loader = provider.getProjectLoader(project, settings);
            if (capture) {
                loader.enableReusableModelCapture();
            }
            loaderRef.set(loader);
            return loader;
        }, settings -> provider.getDumpLoader(database, settings));
    }

    private static void writeProject(Path project) throws IOException {
        write(project.resolve("SCHEMA/public/public.sql"),
                "CREATE SCHEMA public;");
        write(project.resolve("SCHEMA/public/TABLE/items.sql"),
                "CREATE TABLE public.items (id integer);");
        write(project.resolve("SCHEMA/public/FUNCTION/answer.sql"),
                PROJECT_FUNCTION);
    }

    private static void write(Path path, String sql) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, sql + '\n');
    }

    private static List<String> treeSnapshot(TreeElement root) {
        var result = new ArrayList<String>();
        appendTree(root, "", result);
        return result;
    }

    private static void appendTree(
            TreeElement element, String parent, List<String> result) {
        String path = parent + '/' + element.getName();
        result.add(path + '|' + element.getType() + '|' + element.getSide());
        for (TreeElement child : element.getChildren()) {
            appendTree(child, path, result);
        }
    }

    private static void awaitCondition(BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not met before timeout");
            }
            Thread.sleep(10);
        }
    }

    private static final class BlockingAnalysisLoader
            implements ILoader, IComparisonAnalysisLifecycle {

        private final CountDownLatch analysisEntered = new CountDownLatch(1);
        private final CountDownLatch releaseAnalysis = new CountDownLatch(1);
        private CountDownLatch closeEntered = new CountDownLatch(0);
        private CountDownLatch releaseClose = new CountDownLatch(0);
        private final IOException failure = new IOException("controlled peer failure");
        private final boolean fail;
        private final PgDatabase database = new PgDatabase();
        private int failedCalls;
        private RuntimeException analysisSuccessFailure;
        private IOException closeFailure;
        private ISettings settings;

        private BlockingAnalysisLoader(boolean fail) {
            this.fail = fail;
        }

        private BlockingAnalysisLoader withSettings(ISettings supplied) {
            settings = supplied;
            return this;
        }

        private void blockClose(IOException failure) {
            closeEntered = new CountDownLatch(1);
            releaseClose = new CountDownLatch(1);
            closeFailure = failure;
        }

        @Override
        public IDatabase load() {
            return database;
        }

        @Override
        public IDatabase loadAndAnalyze() throws IOException, InterruptedException {
            analysisEntered.countDown();
            releaseAnalysis.await();
            if (fail) {
                throw failure;
            }
            return database;
        }

        @Override
        public IDatabase getDatabase() {
            return database;
        }

        @Override
        public String getDatabaseName() {
            return "blocking-peer";
        }

        @Override
        public ISettings getSettings() {
            return settings;
        }

        @Override
        public List<Object> getErrors() {
            return settings == null
                    ? List.of() : Collections.unmodifiableList(settings.getErrors());
        }

        @Override
        public void cancel() {
            releaseAnalysis.countDown();
            releaseClose.countDown();
        }

        @Override
        public void close() throws IOException {
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        @Override
        public void comparisonAnalysisSucceeded() {
            if (analysisSuccessFailure != null) {
                throw analysisSuccessFailure;
            }
        }

        @Override
        public void comparisonFailed() {
            failedCalls++;
        }
    }

    private static final class FinalMergeFailingSettings
            extends CoreSettings {

        private final RuntimeException failure =
                new IllegalStateException("controlled final merge failure");

        @Override
        public void addErrors(Collection<Object> errors) {
            throw failure;
        }
    }

    private static final class FailureRecordingProjectLoader
            extends PgProjectLoader {

        private int failedCalls;

        private FailureRecordingProjectLoader(
                Path project, ISettings settings) {
            super(project, settings);
        }

        @Override
        public synchronized void comparisonFailed() {
            failedCalls++;
            super.comparisonFailed();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class BlockingProjectLoader extends PgProjectLoader {

        private final CountDownLatch loadEntered = new CountDownLatch(1);
        private final CountDownLatch releaseLoad = new CountDownLatch(1);

        private BlockingProjectLoader(Path path, ISettings settings) {
            super(path, settings);
        }

        @Override
        public PgDatabase loadInternal() throws InterruptedException {
            loadEntered.countDown();
            releaseLoad.await();
            return new PgDatabase();
        }
    }
}
