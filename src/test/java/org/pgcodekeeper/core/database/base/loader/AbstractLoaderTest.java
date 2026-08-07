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
package org.pgcodekeeper.core.database.base.loader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;
import org.pgcodekeeper.core.database.base.parser.ParserExecutionPolicy;
import org.pgcodekeeper.core.database.base.schema.meta.MetaContainer;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

@Isolated("mutates the parser max-pending system property")
class AbstractLoaderTest {

    private String originalMaxPending;

    @BeforeEach
    void configureSingleTaskWindow() {
        originalMaxPending = System.getProperty(Consts.MAX_PENDING_TASKS);
        System.setProperty(Consts.MAX_PENDING_TASKS, "1");
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
    @EnumSource(FailureKind.class)
    void unsuccessfulLoadAbortsTasksAndAllowsCleanRetry(FailureKind failureKind) throws Exception {
        var database = mock(IDatabase.class);
        var loader = new FailingOnceLoader(mock(ISettings.class), database, failureKind);

        assertFailure(failureKind, loader);

        assertTrue(loader.externalFuture.isCancelled());
        assertTrue(loader.taskQueueIsEmpty());
        assertEquals(0, loader.finalizedCount);
        assertEquals(1, loader.releaseCount);

        assertSame(database, loader.load());
        assertTrue(loader.taskQueueIsEmpty());
        assertEquals(0, loader.finalizedCount);
        assertEquals(2, loader.releaseCount);

        loader.close();
        assertEquals(2, loader.releaseCount);
    }

    @Test
    void releaseRethrowingPrimaryFailureDoesNotMaskItWithSelfSuppression() {
        RuntimeException primary = new RuntimeException("controlled failure");
        var loader = new AbstractLoader<IDatabase>(mock(ISettings.class), "test") {
            @Override
            protected IDatabase loadInternal() {
                throw primary;
            }

            @Override
            protected IDatabase createDatabase() {
                return null;
            }

            @Override
            protected void releaseLoadResources() {
                throw primary;
            }
        };

        RuntimeException thrown = assertThrows(RuntimeException.class, loader::load);

        assertSame(primary, thrown);
    }

    @Test
    void borrowedParserQueueSurvivesChildCloseAndClosesWithRoot() throws Exception {
        var settings = new CoreSettings();
        settings.setParserExecutionPolicy(ParserExecutionPolicy.dedicated(1));
        var database = mock(IDatabase.class);
        var root = new CountingLoader(settings, database);
        var child = new CountingLoader(settings, database);

        child.borrowFrom(root);
        assertSame(root.parserQueue(), child.parserQueue());

        child.close();
        root.submitAndFinish();
        root.close();

        assertThrows(IllegalStateException.class, root::submitOne);
        assertThrows(IllegalStateException.class, child::submitOne);
    }

    @Test
    void externalLoaderCancelTerminatesActualOwnerThread() throws Exception {
        var loader = new BlockingParserLoader(mock(ISettings.class), mock(IDatabase.class));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<IDatabase> owner = executor.submit(loader::load);

        try {
            assertTrue(loader.parserEntered.await(5, TimeUnit.SECONDS));

            loader.cancel();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(loader.parserExited.await(5, TimeUnit.SECONDS));
            assertTrue(loader.taskQueueIsEmpty());
            assertEquals(0, loader.finalizedCount.get());
        } finally {
            owner.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void externalCancelDuringAnalysisDrainsActualWorkerBeforeOwnerReturns()
            throws Exception {
        var settings = new CoreSettings();
        settings.setVersion(PgSupportedVersion.VERSION_15);
        var db = new PgDatabase();
        var workerEntered = new CountDownLatch(1);
        var cancellationObserved = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        var workerExited = new AtomicBoolean();
        db.addAnalysisLauncher(blockingAnalysisLauncher(
                workerEntered, cancellationObserved, releaseWorker, workerExited));
        var loader = new CountingLoader(settings, db);
        var ownerDone = new CountDownLatch(1);
        var ownerThread = new AtomicReference<Thread>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<IDatabase> owner = executor.submit(() -> {
            ownerThread.set(Thread.currentThread());
            try {
                return loader.loadAndAnalyze();
            } finally {
                ownerDone.countDown();
            }
        });

        try {
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
            assertFalse(settings.getMonitor().isCancelled());
            assertOwnerBlockedInAnalysisFutureGet(ownerThread.get());

            loader.cancel();

            assertTrue(cancellationObserved.await(5, TimeUnit.SECONDS),
                    "loader cancellation did not interrupt the analysis worker blocked in finish");
            assertFalse(ownerDone.await(200, TimeUnit.MILLISECONDS),
                    "analysis owner returned before the cancelled worker exited");
            releaseWorker.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(workerExited.get());
            assertTrue(db.getAnalysisLaunchers().isEmpty());
            assertTrue(settings.getErrors().isEmpty(), settings.getErrors()::toString);
        } finally {
            releaseWorker.countDown();
            owner.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void fullAnalysisRejectsAndDrainsUndrainedStructuralQueue() throws Exception {
        var settings = new CoreSettings();
        settings.setVersion(PgSupportedVersion.VERSION_15);
        var analysisCalls = new AtomicInteger();
        var db = new PgDatabase();
        db.addAnalysisLauncher(countingAnalysisLauncher(analysisCalls));
        var loader = new UndrainedLoader(settings, db);

        try {
            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class, loader::loadAndAnalyze);

            assertTrue(thrown.getMessage().contains("not drained"), thrown::getMessage);
            assertEquals(0, analysisCalls.get());
            assertTrue(loader.externalFuture.isCancelled());
            assertTrue(loader.taskQueueIsEmpty());
            assertTrue(db.getAnalysisLaunchers().isEmpty());
        } finally {
            loader.close();
        }
    }

    @Test
    void uncancelledLoaderParserCancellationPreservesRuntimeIdentity() {
        var cancellation = new MonitorCancelledRuntimeException();
        var loader = new MonitorCancellationLoader(
                new CoreSettings(), mock(IDatabase.class), cancellation, false);

        MonitorCancelledRuntimeException thrown = assertThrows(
                MonitorCancelledRuntimeException.class, loader::load);

        assertSame(cancellation, thrown);
    }

    @Test
    void activeLoaderParserCancellationMapsToNeutralInterrupted() {
        var cleanupFailure = new IllegalStateException("controlled cleanup failure");
        var cancellation = new MonitorCancelledRuntimeException();
        cancellation.addSuppressed(cleanupFailure);
        var loader = new MonitorCancellationLoader(
                new CoreSettings(), mock(IDatabase.class), cancellation, true);

        InterruptedException thrown = assertThrows(
                InterruptedException.class, loader::load);

        assertAll(
                () -> assertNull(thrown.getMessage()),
                () -> assertNull(thrown.getCause()),
                () -> assertArrayEquals(
                        new Throwable[] { cleanupFailure }, thrown.getSuppressed()));
    }

    @Test
    void loadAfterExternalCancelRejectsBeforeLoadInternalEvenAfterOwnerCleanup() throws Exception {
        var loader = new BlockingParserLoader(mock(ISettings.class), mock(IDatabase.class));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<IDatabase> owner = executor.submit(loader::load);

        try {
            assertTrue(loader.parserEntered.await(5, TimeUnit.SECONDS));
            loader.cancel();
            assertThrows(ExecutionException.class, () -> owner.get(5, TimeUnit.SECONDS));

            assertThrows(InterruptedException.class, loader::load);
            assertEquals(1, loader.loadAttempts.get());
        } finally {
            owner.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelBeforeStructuralResultPublicationRejectsActiveLoad() throws Exception {
        var loader = new CompletingLoader(mock(ISettings.class), mock(IDatabase.class));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<IDatabase> owner = executor.submit(loader::load);

        try {
            assertTrue(loader.resultReady.await(5, TimeUnit.SECONDS));
            loader.cancel();
            loader.publishResult.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertNull(loader.getDatabase());
        } finally {
            loader.publishResult.countDown();
            owner.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeAfterSuccessfulLoadRejectsCachedLoadAndDoesNotReleaseResourcesTwice() throws Exception {
        var database = mock(IDatabase.class);
        var loader = new CountingLoader(mock(ISettings.class), database);

        assertSame(database, loader.load());
        assertEquals(1, loader.loadAttempts);
        assertEquals(1, loader.releaseCount);

        loader.close();
        loader.close();

        assertThrows(IllegalStateException.class, loader::load);
        assertEquals(1, loader.loadAttempts);
        assertEquals(1, loader.releaseCount);
        assertSame(database, loader.getDatabase());
    }

    @Test
    void closeBeforeLoadIsTerminalWithoutStartingOrReleasingAttempt() throws Exception {
        var loader = new CountingLoader(mock(ISettings.class), mock(IDatabase.class));

        loader.close();
        loader.close();

        assertThrows(IllegalStateException.class, loader::load);
        assertEquals(0, loader.loadAttempts);
        assertEquals(0, loader.releaseCount);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void closeDominatesCancellationRegardlessOfTransitionOrder(boolean cancelFirst) throws Exception {
        var loader = new CountingLoader(mock(ISettings.class), mock(IDatabase.class));

        if (cancelFirst) {
            loader.cancel();
            loader.close();
        } else {
            loader.close();
            loader.cancel();
        }

        assertThrows(IllegalStateException.class, loader::load);
        assertFalse(loader.cancellationRequested());
        assertEquals(0, loader.loadAttempts);
        assertEquals(0, loader.releaseCount);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void closeRunsAbortBeforeReleaseAndPreservesCleanupFailures(boolean sameFailure) throws Exception {
        var events = new ArrayList<String>();
        RuntimeException abortFailure = new IllegalStateException("controlled abort failure");
        RuntimeException releaseFailure = sameFailure
                ? abortFailure
                : new IllegalArgumentException("controlled release failure");
        var loader = new CloseCleanupLoader(mock(ISettings.class), mock(IDatabase.class),
                events, releaseFailure);
        loader.addCancellationTask(abortFailure);
        armLoadResourceRelease(loader);

        RuntimeException thrown = assertThrows(RuntimeException.class, loader::close);

        assertSame(abortFailure, thrown);
        assertEquals(List.of("abort", "release"), events);
        assertTrue(loader.taskQueueIsEmpty());
        if (sameFailure) {
            assertEquals(0, thrown.getSuppressed().length);
        } else {
            assertEquals(1, thrown.getSuppressed().length);
            assertSame(releaseFailure, thrown.getSuppressed()[0]);
        }

        assertDoesNotThrow(loader::close);
        assertEquals(List.of("abort", "release"), events);
        assertThrows(IllegalStateException.class, loader::load);
    }

    @Test
    void loaderInterfaceDefaultCloseRemainsCompatibilityNoOp() {
        assertDoesNotThrow(new CompatibilityLoader()::close);
    }

    @Test
    void loaderInterfaceExternalMetadataDefaultRejectsExplicitly() {
        UnsupportedOperationException thrown = assertThrows(
                UnsupportedOperationException.class,
                () -> new CompatibilityLoader().loadAndAnalyze(
                        new MetaContainer()));

        assertEquals("External metadata analysis is not supported", thrown.getMessage());
    }

    private static void armLoadResourceRelease(AbstractLoader<?> loader) throws Exception {
        Field field = AbstractLoader.class.getDeclaredField("loadResourcesReleased");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(loader)).set(false);
    }

    private static void assertOwnerBlockedInAnalysisFutureGet(Thread owner) {
        assertNotNull(owner, "analysis owner thread was not published");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        StackTraceElement[] lastTrace = new StackTraceElement[0];
        Thread.State lastState = owner.getState();
        boolean observedOnce = false;

        while (System.nanoTime() < deadline) {
            lastTrace = owner.getStackTrace();
            lastState = owner.getState();
            boolean inFutureGet = containsFrame(
                    lastTrace, FutureTask.class.getName(), "get");
            boolean inAntlrFinish = containsFrame(
                    lastTrace, AntlrTask.class.getName(), "finish");
            boolean blocked = lastState == Thread.State.WAITING
                    || lastState == Thread.State.TIMED_WAITING;
            if (inFutureGet && inAntlrFinish && blocked) {
                if (observedOnce) {
                    return;
                }
                observedOnce = true;
            } else {
                observedOnce = false;
            }
            Thread.yield();
        }

        fail("analysis owner did not remain blocked in FutureTask.get via AntlrTask.finish; "
                + "last state=" + lastState + ", stack="
                + java.util.Arrays.toString(lastTrace));
    }

    private static boolean containsFrame(
            StackTraceElement[] trace, String className, String methodName) {
        for (StackTraceElement frame : trace) {
            if (className.equals(frame.getClassName())
                    && methodName.equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static IAnalysisLauncher blockingAnalysisLauncher(
            CountDownLatch workerEntered, CountDownLatch cancellationObserved,
            CountDownLatch releaseWorker, AtomicBoolean workerExited) {
        return new IAnalysisLauncher() {
            private final PgFunction function = new PgFunction("blocking_analysis");

            @Override
            public IStatement getStmt() {
                return function;
            }

            @Override
            public void updateStmt(IDatabase database) {
                // no-op test launcher
            }

            @Override
            public Set<ObjectReference> launchAnalyze(List<Object> errors, IMetaContainer meta) {
                fail("monitored analysis overload was not used");
                return Set.of();
            }

            @Override
            public Set<ObjectReference> launchAnalyze(
                    List<Object> errors, IMetaContainer meta, IMonitor monitor) {
                workerEntered.countDown();
                boolean interrupted = false;
                try {
                    while (true) {
                        try {
                            releaseWorker.await();
                            return Set.of();
                        } catch (InterruptedException ex) {
                            interrupted = true;
                            cancellationObserved.countDown();
                        }
                    }
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    workerExited.set(true);
                }
            }

            @Override
            public List<ObjectLocation> getReferences() {
                return List.of();
            }

            @Override
            public String getSchemaName() {
                return null;
            }
        };
    }

    private static IAnalysisLauncher countingAnalysisLauncher(AtomicInteger calls) {
        return new IAnalysisLauncher() {
            private final PgFunction function = new PgFunction("counting_analysis");

            @Override
            public IStatement getStmt() {
                return function;
            }

            @Override
            public void updateStmt(IDatabase database) {
                // no-op test launcher
            }

            @Override
            public Set<ObjectReference> launchAnalyze(List<Object> errors, IMetaContainer meta) {
                calls.incrementAndGet();
                return Set.of();
            }

            @Override
            public List<ObjectLocation> getReferences() {
                return List.of();
            }

            @Override
            public String getSchemaName() {
                return null;
            }
        };
    }

    private static void assertFailure(FailureKind failureKind, FailingOnceLoader loader) {
        switch (failureKind) {
        case IO -> assertThrows(IOException.class, loader::load);
        case INTERRUPTED -> assertThrows(InterruptedException.class, loader::load);
        case CANCELLED -> assertThrows(MonitorCancelledRuntimeException.class, loader::load);
        }
    }

    private enum FailureKind {
        IO,
        INTERRUPTED,
        CANCELLED
    }

    private static final class FailingOnceLoader extends AbstractLoader<IDatabase> {

        private final IDatabase database;
        private final FailureKind failureKind;
        private final FutureTask<String> externalFuture = new FutureTask<>(() -> "external");
        private int attempts;
        private int finalizedCount;
        private int releaseCount;

        private FailingOnceLoader(ISettings settings, IDatabase database, FailureKind failureKind) {
            super(settings, "test");
            this.database = database;
            this.failureKind = failureKind;
        }

        @Override
        protected IDatabase loadInternal() throws IOException, InterruptedException {
            if (attempts++ == 0) {
                antlrTasks.add(new AntlrTask<>(externalFuture, ignored -> finalizedCount++));
                AntlrTaskManager.submit(antlrTasks, () -> "pending", ignored -> finalizedCount++);
                throwConfiguredFailure();
            }
            return database;
        }

        @Override
        protected IDatabase createDatabase() {
            return database;
        }

        @Override
        protected void releaseLoadResources() {
            releaseCount++;
        }

        private void throwConfiguredFailure() throws IOException, InterruptedException {
            switch (failureKind) {
            case IO -> throw new IOException("load failed");
            case INTERRUPTED -> throw new InterruptedException("load interrupted");
            case CANCELLED -> throw new MonitorCancelledRuntimeException("load cancelled");
            }
        }

        private boolean taskQueueIsEmpty() {
            return antlrTasks.isEmpty();
        }
    }

    private static final class BlockingParserLoader extends AbstractLoader<IDatabase> {

        private final IDatabase database;
        private final CountDownLatch parserEntered = new CountDownLatch(1);
        private final CountDownLatch parserExited = new CountDownLatch(1);
        private final AtomicInteger loadAttempts = new AtomicInteger();
        private final AtomicInteger finalizedCount = new AtomicInteger();

        private BlockingParserLoader(ISettings settings, IDatabase database) {
            super(settings, "blocking");
            this.database = database;
        }

        @Override
        protected IDatabase loadInternal() throws IOException, InterruptedException {
            loadAttempts.incrementAndGet();
            AntlrTaskManager.submit(antlrTasks, () -> {
                parserEntered.countDown();
                try {
                    new CountDownLatch(1).await();
                    return "parsed";
                } finally {
                    parserExited.countDown();
                }
            }, ignored -> finalizedCount.incrementAndGet());
            finishLoaders();
            return database;
        }

        @Override
        protected IDatabase createDatabase() {
            return database;
        }

        private boolean taskQueueIsEmpty() {
            return antlrTasks.isEmpty();
        }
    }

    private static final class CompletingLoader extends AbstractLoader<IDatabase> {

        private final IDatabase database;
        private final CountDownLatch resultReady = new CountDownLatch(1);
        private final CountDownLatch publishResult = new CountDownLatch(1);

        private CompletingLoader(ISettings settings, IDatabase database) {
            super(settings, "completing");
            this.database = database;
        }

        @Override
        protected IDatabase loadInternal() throws InterruptedException {
            resultReady.countDown();
            publishResult.await();
            return database;
        }

        @Override
        protected IDatabase createDatabase() {
            return database;
        }
    }

    private static final class UndrainedLoader extends AbstractLoader<IDatabase> {

        private final IDatabase database;
        private final FutureTask<String> externalFuture = new FutureTask<>(() -> "pending");

        private UndrainedLoader(ISettings settings, IDatabase database) {
            super(settings, "undrained");
            this.database = database;
        }

        @Override
        protected IDatabase loadInternal() {
            antlrTasks.add(new AntlrTask<>(externalFuture, ignored -> { }));
            return database;
        }

        @Override
        protected IDatabase createDatabase() {
            return database;
        }

        private boolean taskQueueIsEmpty() {
            return antlrTasks.isEmpty();
        }
    }

    private static final class MonitorCancellationLoader extends AbstractLoader<IDatabase> {

        private final IDatabase database;
        private final MonitorCancelledRuntimeException cancellation;
        private final boolean activateMonitor;

        private MonitorCancellationLoader(ISettings settings, IDatabase database,
                                          MonitorCancelledRuntimeException cancellation,
                                          boolean activateMonitor) {
            super(settings, "monitor-cancellation");
            this.database = database;
            this.cancellation = cancellation;
            this.activateMonitor = activateMonitor;
        }

        @Override
        protected IDatabase loadInternal() throws IOException, InterruptedException {
            AntlrTaskManager.submit(antlrTasks, () -> {
                if (activateMonitor) {
                    getMonitor().setCancelled(true);
                }
                throw cancellation;
            }, ignored -> { });
            finishLoaders();
            return database;
        }

        @Override
        protected IDatabase createDatabase() {
            return database;
        }
    }

    private static class CountingLoader extends AbstractLoader<IDatabase> {

        private final IDatabase database;
        private int loadAttempts;
        private int releaseCount;

        private CountingLoader(ISettings settings, IDatabase database) {
            super(settings, "counting");
            this.database = database;
        }

        @Override
        protected IDatabase loadInternal() {
            loadAttempts++;
            return database;
        }

        @Override
        protected IDatabase createDatabase() {
            return database;
        }

        @Override
        protected void releaseLoadResources() {
            releaseCount++;
        }

        private boolean cancellationRequested() {
            return isCancellationRequested();
        }

        private void borrowFrom(CountingLoader root) {
            borrowParserExecution(root);
        }

        private Queue<AntlrTask<?>> parserQueue() {
            return antlrTasks;
        }

        private void submitOne() {
            AntlrTaskManager.submit(antlrTasks, () -> 1, ignored -> { });
        }

        private void submitAndFinish() throws IOException, InterruptedException {
            submitOne();
            finishLoaders();
        }
    }

    private static final class CloseCleanupLoader extends CountingLoader {

        private final List<String> events;
        private final RuntimeException releaseFailure;

        private CloseCleanupLoader(ISettings settings, IDatabase database,
                                   List<String> events, RuntimeException releaseFailure) {
            super(settings, database);
            this.events = events;
            this.releaseFailure = releaseFailure;
        }

        private void addCancellationTask(RuntimeException failure) {
            FutureTask<String> future = new FutureTask<>(() -> "pending") {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    events.add("abort");
                    throw failure;
                }
            };
            antlrTasks.add(new AntlrTask<>(future, ignored -> { }));
        }

        @Override
        protected void releaseLoadResources() {
            events.add("release");
            throw releaseFailure;
        }

        private boolean taskQueueIsEmpty() {
            return antlrTasks.isEmpty();
        }
    }

    private static final class CompatibilityLoader implements ILoader {

        @Override
        public IDatabase load() {
            return null;
        }

        @Override
        public IDatabase loadAndAnalyze() {
            return null;
        }

        @Override
        public IDatabase getDatabase() {
            return null;
        }

        @Override
        public String getDatabaseName() {
            return "compatibility";
        }

        @Override
        public ISettings getSettings() {
            return null;
        }

        @Override
        public List<Object> getErrors() {
            return Collections.emptyList();
        }
    }
}
