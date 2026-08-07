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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.database.api.jdbc.ISupportedVersion;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionContext;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionKey;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ComparisonSide;
import org.pgcodekeeper.core.database.api.loader.IComparisonExtensionBinding;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

@Isolated
class ComparisonLoaderCoordinatorTest {

    private static final long WAIT_SECONDS = 5;
    private static final Duration TERMINATION_TIMEOUT = Duration.ofMillis(100);

    @Test
    void commonConfigurationAndSideMonitorsPrecedeConstructionInLogicalOrder()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        var parentMonitor = new RecordingParentMonitor(events);
        caller.setMonitor(parentMonitor);
        var pair = pair(caller, events, null, version(17), null, version(17));

        new ComparisonLoaderCoordinator().load(pair.factories(), caller, ComparisonDepth.FULL);

        assertEquals(List.of(
                "OLD contribute", "NEW contribute",
                "monitor-1", "monitor-2",
                "OLD create", "NEW create"),
                List.copyOf(events).subList(0, 6));
        assertSame(pair.oldFactory.commonSettings, pair.newFactory.commonSettings);
        assertNotSame(caller, pair.oldFactory.commonSettings);
        assertNotSame(pair.oldFactory.commonSettings, pair.oldFactory.settings);
        assertNotSame(pair.oldFactory.commonSettings, pair.newFactory.settings);
        assertNotSame(pair.oldFactory.settings, pair.newFactory.settings);
        assertSame(pair.oldFactory.settings, pair.oldFactory.loader.getSettings());
        assertSame(pair.newFactory.settings, pair.newFactory.loader.getSettings());
        assertNotSame(parentMonitor.children.get(0), pair.oldFactory.settings.getMonitor());
        assertNotSame(parentMonitor.children.get(1), pair.newFactory.settings.getMonitor());
        assertNotSame(pair.oldFactory.settings.getMonitor(), pair.newFactory.settings.getMonitor());
        assertEquals(List.of(Thread.currentThread(), Thread.currentThread()),
                parentMonitor.creationThreads);
    }

    @Test
    void reverseCompletionStillReturnsOldThenNew() throws Exception {
        var pair = pair(new CoreSettings(), synchronizedList(),
                null, version(17), null, version(17));
        pair.oldPlan.loadStarted = latch();
        pair.oldPlan.loadRelease = latch();
        pair.newPlan.loadFinished = latch();
        pair.oldPlan.analysisStarted = latch();
        pair.oldPlan.analysisRelease = latch();
        pair.newPlan.analysisFinished = latch();

        AsyncLoad call = start(pair);
        await(pair.oldPlan.loadStarted);
        await(pair.newPlan.loadFinished);
        assertTrue(call.thread.isAlive(), "OLD structural load still holds the phase");
        pair.oldPlan.loadRelease.countDown();
        await(pair.oldPlan.analysisStarted);
        await(pair.newPlan.analysisFinished);
        pair.oldPlan.analysisRelease.countDown();

        var loaded = call.awaitSuccess();
        assertSame(pair.oldPlan.database, loaded.oldDatabase());
        assertSame(pair.newPlan.database, loaded.newDatabase());
    }

    @Test
    void noAnalysisStartsBeforeBothStructuralLoadsFinish() throws Exception {
        var pair = pair(new CoreSettings(), synchronizedList(),
                null, version(17), null, version(17));
        pair.oldPlan.loadStarted = latch();
        pair.oldPlan.loadRelease = latch();
        pair.newPlan.loadFinished = latch();

        AsyncLoad call = start(pair);
        await(pair.oldPlan.loadStarted);
        await(pair.newPlan.loadFinished);
        assertEquals(0, pair.oldPlan.analysisCalls.get());
        assertEquals(0, pair.newPlan.analysisCalls.get());
        pair.oldPlan.loadRelease.countDown();

        call.awaitSuccess();
        assertEquals(1, pair.oldPlan.analysisCalls.get());
        assertEquals(1, pair.newPlan.analysisCalls.get());
    }

    @Test
    void detectedMinimumIsPublishedEverywhereBeforeAnalysis() throws Exception {
        var oldVersion = version(14);
        var newVersion = version(17);
        var caller = new CoreSettings();
        var pair = pair(caller, synchronizedList(),
                oldVersion, version(18), newVersion, version(19));

        var loaded = new ComparisonLoaderCoordinator().load(pair.factories(), caller, ComparisonDepth.FULL);

        assertVersionIdentity(oldVersion, pair, loaded, caller);
    }

    @Test
    void oneDetectedVersionDoesNotFallbackToOtherDatabaseVersion() throws Exception {
        var detected = version(16);
        var caller = new CoreSettings();
        var pair = pair(caller, synchronizedList(),
                detected, version(18), null, version(14));

        var loaded = new ComparisonLoaderCoordinator().load(pair.factories(), caller, ComparisonDepth.FULL);

        assertVersionIdentity(detected, pair, loaded, caller);
    }

    @Test
    void onlyNewDetectedVersionDoesNotFallbackToOldDatabaseVersion() throws Exception {
        var detected = version(16);
        var caller = new CoreSettings();
        var pair = pair(caller, synchronizedList(),
                null, version(14), detected, version(18));

        var loaded = new ComparisonLoaderCoordinator().load(pair.factories(), caller, ComparisonDepth.FULL);

        assertVersionIdentity(detected, pair, loaded, caller);
    }

    @Test
    void equalDetectedVersionsPreserveOldIdentity() throws Exception {
        var oldVersion = version(15);
        var equalNewVersion = version(15);
        var caller = new CoreSettings();
        var pair = pair(caller, synchronizedList(),
                oldVersion, version(17), equalNewVersion, version(17));

        var loaded = new ComparisonLoaderCoordinator().load(pair.factories(), caller, ComparisonDepth.FULL);

        assertNotSame(oldVersion, equalNewVersion);
        assertVersionIdentity(oldVersion, pair, loaded, caller);
    }

    @Test
    void bothNullDetectedVersionsFallbackToDatabaseMinimum() throws Exception {
        var oldFallback = version(17);
        var newFallback = version(14);
        var caller = new CoreSettings();
        var pair = pair(caller, synchronizedList(),
                null, oldFallback, null, newFallback);

        var loaded = new ComparisonLoaderCoordinator().load(pair.factories(), caller, ComparisonDepth.FULL);

        assertVersionIdentity(newFallback, pair, loaded, caller);
    }

    @Test
    void equalBothNullFallbackVersionsPreserveOldIdentity() throws Exception {
        var oldFallback = version(15);
        var equalNewFallback = version(15);
        var caller = new CoreSettings();
        var pair = pair(caller, synchronizedList(),
                null, oldFallback, null, equalNewFallback);

        var loaded = new ComparisonLoaderCoordinator().load(pair.factories(), caller, ComparisonDepth.FULL);

        assertNotSame(oldFallback, equalNewFallback);
        assertVersionIdentity(oldFallback, pair, loaded, caller);
    }

    @Test
    void allMissingVersionsRemainNullWithoutPublishingNull() throws Exception {
        var caller = new CoreSettings();
        var pair = pair(caller, synchronizedList(), null, null, null, null);

        var loaded = new ComparisonLoaderCoordinator().load(pair.factories(), caller, ComparisonDepth.FULL);

        assertVersionIdentity(null, pair, loaded, caller);
    }

    /**
     * The effective version is detected during the structural phase and
     * published into both side settings, the common settings and the caller's
     * own before the depth branch is ever reached - so a load that stops after
     * the structural phase must publish exactly what a full load of the same
     * fixture publishes.
     * <p>
     * WHY this needs a test of its own: the publishing block sits just above
     * the depth branch and reads like an orphan of it, so the obvious tidying
     * move is to fold those four calls into the {@code FULL} branch. That
     * compiles, and every other test in this package stays green - the fixture
     * parity test cannot see it either, because {@code DiffTree.create} never
     * reads {@code settings.getVersion()}. What it does do is hand a structural
     * load a default version instead of the detected one, and that version is
     * what {@code AbstractStatement.checkSyntaxVersion} reads whenever {@code
     * isUseActualVersionSyntax} is on: the receive-only mode would write
     * different SQL into the project's files with nothing turning red.
     */
    @Test
    void structuralLoadPublishesTheSameVersionAFullLoadDoes() throws Exception {
        var lower = version(14);
        var higher = version(17);

        var fullCaller = new CoreSettings();
        var fullPair = pair(fullCaller, synchronizedList(),
                higher, version(18), lower, version(19));
        var full = new ComparisonLoaderCoordinator().load(
                fullPair.factories(), fullCaller, ComparisonDepth.FULL);
        assertVersionIdentity(lower, fullPair, full, fullCaller);

        var caller = new CoreSettings();
        var pair = pair(caller, synchronizedList(),
                higher, version(18), lower, version(19));

        var loaded = new ComparisonLoaderCoordinator().load(
                pair.factories(), caller, ComparisonDepth.STRUCTURAL_ONLY);

        assertEquals(0, pair.oldPlan.analysisCalls.get(),
                "fixture sanity check: a structural load must not analyze at all");
        assertEquals(0, pair.newPlan.analysisCalls.get(),
                "fixture sanity check: a structural load must not analyze at all");
        assertSame(full.comparisonSettings().getVersion(), loaded.comparisonSettings().getVersion(),
                "the depth of a load must not change which version it settles on");
        assertSame(lower, loaded.comparisonSettings().getVersion(),
                "the settings a structural load hands back carry the detected minimum, not a default");
        assertSame(lower, caller.getVersion(),
                "the caller's own settings are told the detected minimum at either depth");
        // the OLD side detected 17 by itself during its structural load, so
        // only the publishing step can be what brought it down to the pair's
        // effective 14
        assertSame(lower, pair.oldFactory.settings.getVersion(),
                "the OLD side settings carry the pair's effective version, not their own detection");
        assertSame(lower, pair.newFactory.settings.getVersion(),
                "the NEW side settings carry the pair's effective version");
    }

    @Test
    void errorsMergeOldThenNewRegardlessOfCompletionOrder() throws Exception {
        var caller = new CoreSettings();
        caller.addError("stale");
        var pair = pair(caller, synchronizedList(),
                null, version(17), null, version(17));
        pair.oldPlan.loadErrors = List.of("OLD load");
        pair.oldPlan.analysisErrors = List.of("OLD analyze");
        pair.newPlan.loadErrors = List.of("NEW load");
        pair.newPlan.analysisErrors = List.of("NEW analyze");
        pair.oldPlan.loadStarted = latch();
        pair.oldPlan.loadRelease = latch();
        pair.newPlan.loadFinished = latch();
        pair.oldPlan.analysisStarted = latch();
        pair.oldPlan.analysisRelease = latch();
        pair.newPlan.analysisFinished = latch();

        AsyncLoad call = start(pair);
        await(pair.oldPlan.loadStarted);
        await(pair.newPlan.loadFinished);
        pair.oldPlan.loadRelease.countDown();
        await(pair.oldPlan.analysisStarted);
        await(pair.newPlan.analysisFinished);
        pair.oldPlan.analysisRelease.countDown();
        var loaded = call.awaitSuccess();

        var expected = List.of("OLD load", "OLD analyze", "NEW load", "NEW analyze");
        assertSame(pair.oldPlan.database, loaded.oldDatabase());
        assertSame(pair.newPlan.database, loaded.newDatabase());
        assertEquals(expected, loaded.comparisonSettings().getErrors());
        assertEquals(expected, caller.getErrors());
    }

    @Test
    void sameLoaderPerformsBothPhasesAndAnalysisReturnsSameDatabase() throws Exception {
        var pair = pair(new CoreSettings(), synchronizedList(),
                null, version(17), null, version(17));

        var loaded = new ComparisonLoaderCoordinator().load(pair.factories(), pair.caller, ComparisonDepth.FULL);

        assertEquals(1, pair.oldFactory.createCalls.get());
        assertEquals(1, pair.newFactory.createCalls.get());
        assertEquals(1, pair.oldPlan.loadCalls.get());
        assertEquals(1, pair.newPlan.loadCalls.get());
        assertEquals(1, pair.oldPlan.analysisCalls.get());
        assertEquals(1, pair.newPlan.analysisCalls.get());
        assertSame(pair.oldPlan.database, loaded.oldDatabase());
        assertSame(pair.newPlan.database, loaded.newDatabase());
    }

    @Test
    void analysisReturningDifferentDatabaseIsRejected() {
        var pair = pair(new CoreSettings(), synchronizedList(),
                null, version(17), null, version(17));
        pair.oldPlan.analyzedDatabase = database(version(17));

        assertThrows(IllegalStateException.class,
                () -> new ComparisonLoaderCoordinator().load(pair.factories(), pair.caller, ComparisonDepth.FULL));
    }

    @Test
    void sameDatabaseOnBothSidesIsRejectedBeforeAnalysis() {
        var caller = new CoreSettings();
        var events = synchronizedList();
        var sharedDatabase = database(version(17));
        var oldPlan = new LoaderPlan("OLD", sharedDatabase, null);
        var newPlan = new LoaderPlan("NEW", sharedDatabase, null);
        var pair = new TestPair(caller, oldPlan, newPlan,
                new RecordingFactory(oldPlan, caller, events),
                new RecordingFactory(newPlan, caller, events));

        assertThrows(IllegalStateException.class,
                () -> new ComparisonLoaderCoordinator().load(pair.factories(), caller, ComparisonDepth.FULL));
        assertEquals(0, oldPlan.analysisCalls.get());
        assertEquals(0, newPlan.analysisCalls.get());
    }

    @Test
    void completedPhaseFuturesAreNotCancelledForLaterValidationFailure()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        caller.setMonitor(new RecordingParentMonitor(events));
        var sharedDatabase = database(version(17));
        var oldPlan = new LoaderPlan("OLD", sharedDatabase, null);
        var newPlan = new LoaderPlan("NEW", sharedDatabase, null);
        var pair = new TestPair(caller, oldPlan, newPlan,
                new RecordingFactory(oldPlan, caller, events),
                new RecordingFactory(newPlan, caller, events));
        var executor = new LifecycleRecordingExecutor(events, null);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        assertThrows(IllegalStateException.class,
                () -> coordinator.load(pair.factories(), caller, ComparisonDepth.FULL));

        assertEquals(0, oldPlan.analysisCalls.get());
        assertEquals(0, newPlan.analysisCalls.get());
        assertFalse(events.stream().anyMatch(event -> event.endsWith(" future cancel")),
                "completed structural handles are obsolete before validation");
        assertEquals(List.of(
                "parent cancel", "OLD cancel", "NEW cancel",
                "shutdownNow", "executor terminated", "OLD close", "NEW close"),
                lifecycleEvents(events));
        assertWorkersGone(pair);
    }

    @Test
    void aliasedSettingsCopyIsRejectedBeforeFactoryConstruction() {
        var caller = new AliasingSettings();
        var pair = pair(caller, synchronizedList(),
                null, version(17), null, version(17));

        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonLoaderCoordinator().load(pair.factories(), caller, ComparisonDepth.FULL));
        assertEquals(0, pair.oldFactory.createCalls.get());
        assertEquals(0, pair.newFactory.createCalls.get());
    }

    @Test
    void directFactoryNullAndForeignProductsAreRejectedBeforeSubmission() {
        var caller = new CoreSettings();
        ILoaderFactory nullFactory = settings -> null;
        ILoaderFactory unusedFactory = settings -> {
            throw new AssertionError("NEW must not be constructed after OLD rejection");
        };

        assertThrows(NullPointerException.class, () -> new ComparisonLoaderCoordinator()
                .load(new ComparisonLoaderFactories(nullFactory, unusedFactory), caller, ComparisonDepth.FULL));

        var foreign = new LoaderPlan("OLD", database(version(17)), null);
        var product = new RecordingLoader(foreign, new CoreSettings(),
                new CoreSettings(), caller, synchronizedList());
        ILoaderFactory foreignFactory = settings -> product;

        assertThrows(IllegalArgumentException.class, () -> new ComparisonLoaderCoordinator()
                .load(new ComparisonLoaderFactories(foreignFactory, unusedFactory), caller, ComparisonDepth.FULL));
        assertEquals(1, foreign.closeCalls.get(), "coordinator owns and closes returned product");
        assertEquals(0, foreign.loadCalls.get(), "rejected product is never submitted");
    }

    @Test
    void distinctInvalidNewProductCleansUpInLogicalOrderBeforeSubmission()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        var parentMonitor = new RecordingParentMonitor(events);
        caller.setMonitor(parentMonitor);
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var suppliedNewSettings = new AtomicReference<ISettings>();
        var foreignSettings = new CoreSettings();
        var oldCancelFailure = new IOException("OLD cancel failure");
        var newCancelFailure = new IOException("NEW cancel failure");
        var oldCloseFailure = new IOException("OLD close failure");
        var newCloseFailure = new IOException("NEW close failure");
        pair.oldPlan.cancelFailure = oldCancelFailure;
        pair.newPlan.cancelFailure = newCancelFailure;
        pair.oldPlan.closeFailure = oldCloseFailure;
        pair.newPlan.closeFailure = newCloseFailure;
        ILoaderFactory invalidNewFactory = settings -> {
            events.add("NEW create");
            suppliedNewSettings.set(settings);
            return new RecordingLoader(pair.newPlan, foreignSettings,
                    pair.oldFactory.commonSettings, caller, events);
        };
        var coordinator = new ComparisonLoaderCoordinator(() -> {
            throw new AssertionError("validation failure must precede executor creation");
        }, Duration.ofSeconds(WAIT_SECONDS));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> coordinator.load(new ComparisonLoaderFactories(
                        pair.oldFactory, invalidNewFactory), caller, ComparisonDepth.FULL));

        assertEquals("Loader must retain the supplied settings instance",
                failure.getMessage());
        assertSuppressedIdentity(failure,
                oldCancelFailure, newCancelFailure, oldCloseFailure, newCloseFailure);
        assertTrue(parentMonitor.isCancelled());
        assertTrue(pair.oldFactory.settings.getMonitor().isCancelled());
        assertTrue(suppliedNewSettings.get().getMonitor().isCancelled());
        assertEquals(List.of(
                "parent cancel", "OLD cancel", "NEW cancel",
                "OLD close", "NEW close"), lifecycleEvents(events));
        assertEquals(1, pair.oldPlan.cancelCalls.get());
        assertEquals(1, pair.newPlan.cancelCalls.get());
        assertEquals(1, pair.oldPlan.closeCalls.get());
        assertEquals(1, pair.newPlan.closeCalls.get());
        assertEquals(0, pair.oldPlan.loadCalls.get());
        assertEquals(0, pair.newPlan.loadCalls.get());
        assertEquals(0, pair.oldPlan.analysisCalls.get());
        assertEquals(0, pair.newPlan.analysisCalls.get());
        assertTrue(pair.oldPlan.workerThreads.isEmpty());
        assertTrue(pair.newPlan.workerThreads.isEmpty());
    }

    @Test
    void sameLoaderOnBothSidesIsRejectedBeforeSubmissionAndClosedOnce() throws Exception {
        var oldSettings = new AtomicReference<ISettings>();
        ILoader shared = mock(ILoader.class);
        when(shared.getSettings()).thenAnswer(invocation -> oldSettings.get());
        ILoaderFactory oldFactory = settings -> {
            oldSettings.set(settings);
            return shared;
        };
        ILoaderFactory newFactory = settings -> shared;

        assertThrows(IllegalArgumentException.class, () -> new ComparisonLoaderCoordinator()
                .load(new ComparisonLoaderFactories(oldFactory, newFactory), new CoreSettings(), ComparisonDepth.FULL));

        verify(shared, times(1)).close();
        verify(shared, never()).load();
        verify(shared, never()).loadAndAnalyze();
    }

    @Test
    void successTerminatesNamedDaemonWorkersBeforeClosingOldThenNew() throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var release = latch();
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = release;
        pair.newPlan.loadRelease = release;
        var executor = new TerminationRecordingExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);
        release.countDown();
        call.awaitSuccess();

        var workers = ConcurrentHashMap.<Thread>newKeySet();
        workers.addAll(pair.oldPlan.workerThreads);
        workers.addAll(pair.newPlan.workerThreads);
        assertEquals(Set.of("pgck-compare-1", "pgck-compare-2"),
                workers.stream().map(Thread::getName).collect(java.util.stream.Collectors.toSet()));
        assertTrue(workers.stream().allMatch(Thread::isDaemon));
        var completedEvents = List.copyOf(events);
        assertTrue(completedEvents.contains("OLD analyze exit"));
        assertTrue(completedEvents.contains("NEW analyze exit"));
        assertTrue(completedEvents.indexOf("OLD analyze exit")
                < completedEvents.indexOf("OLD close"));
        assertTrue(completedEvents.indexOf("NEW analyze exit")
                < completedEvents.indexOf("OLD close"));
        assertEquals(List.of("executor terminated", "OLD close", "NEW close"),
                completedEvents.stream()
                        .filter(event -> event.equals("executor terminated")
                                || event.endsWith(" close"))
                        .toList());
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
        assertTrue(workers.stream().noneMatch(Thread::isAlive));
    }

    @Test
    void firstFailureCancelsSiblingAndJoinsActualThread() throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        var parentMonitor = new RecordingParentMonitor(events);
        caller.setMonitor(parentMonitor);
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var primary = new IOException("OLD structural failure");
        var bothStarted = new CountDownLatch(2);
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.loadFailure = primary;
        pair.oldPlan.startErrors = List.of("OLD partial diagnostic");
        pair.newPlan.startErrors = List.of("NEW partial diagnostic");
        pair.oldPlan.cancelCalled = latch();
        pair.newPlan.cancelCalled = latch();

        AsyncLoad call = start(pair);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        assertSame(primary, failure);
        assertEquals(1, pair.oldPlan.cancelCalls.get());
        assertEquals(1, pair.newPlan.cancelCalls.get());
        assertTrue(pair.oldFactory.settings.getMonitor().isCancelled());
        assertTrue(pair.newFactory.settings.getMonitor().isCancelled());
        assertTrue(parentMonitor.isCancelled());
        assertEquals(List.of("OLD partial diagnostic", "NEW partial diagnostic"),
                caller.getErrors());
        assertEquals(caller.getErrors(), pair.oldFactory.commonSettings.getErrors());
        assertWorkersGone(pair);
        assertFalse(pair.oldPlan.closeWhileOwnerActive.get());
        assertFalse(pair.newPlan.closeWhileOwnerActive.get());
    }

    @Test
    void newFailureWaitsForOldOwnerFinallyBeforeReturning() throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var primary = new IOException("NEW structural failure");
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = latch();
        pair.newPlan.loadRelease = bothStarted;
        pair.newPlan.loadFailure = primary;
        pair.oldPlan.ownerExitStarted = latch();
        pair.oldPlan.ownerExitRelease = latch();
        var executor = new CleanupInterruptExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);
        try {
            await(pair.oldPlan.ownerExitStarted);
            await(executor.awaitEntered);
            assertTrue(call.thread.isAlive(),
                    "coordinator must remain in join while OLD owner finally is gated");
            assertTrue(pair.oldPlan.activeOwners.get() > 0);
            assertEquals(0, pair.oldPlan.closeCalls.get());
            assertEquals(0, pair.newPlan.closeCalls.get());
        } finally {
            pair.oldPlan.ownerExitRelease.countDown();
        }

        Throwable failure = call.awaitFailure();
        executor.awaitActualTermination();

        assertSame(primary, failure);
        assertEquals(1, pair.oldPlan.cancelCalls.get());
        assertEquals(1, pair.newPlan.cancelCalls.get());
        assertEquals(1, pair.oldPlan.ownerCleanupCalls.get());
        assertFalse(pair.oldPlan.closeWhileOwnerActive.get());
        assertFalse(pair.newPlan.closeWhileOwnerActive.get());
        assertWorkersGone(pair);
    }

    @Test
    void callerInterruptIsPrimaryAndInterruptFlagIsRestored() throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        var parentMonitor = new RecordingParentMonitor(events);
        caller.setMonitor(parentMonitor);
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = latch();
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.startErrors = List.of("OLD before caller interrupt");
        pair.newPlan.startErrors = List.of("NEW before caller interrupt");

        AsyncLoad call = start(pair);
        await(bothStarted);
        call.thread.interrupt();
        Throwable failure = call.awaitFailure();

        assertInstanceOf(InterruptedException.class, failure);
        assertEquals(0, failure.getSuppressed().length,
                "cleanup-induced worker interruptions are not independent failures");
        assertTrue(call.interruptedOnExit.get(),
                "the coordinator must restore the caller interrupt flag");
        assertEquals(List.of("OLD before caller interrupt", "NEW before caller interrupt"),
                caller.getErrors());
        assertEquals(1, pair.oldPlan.cancelCalls.get());
        assertEquals(1, pair.newPlan.cancelCalls.get());
        assertTrue(parentMonitor.isCancelled());
        assertWorkersGone(pair);
    }

    @Test
    void completedWorkerFailureCapturesLateCallerInterruptBeforeCleanup()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        var parentMonitor = new RecordingParentMonitor(events);
        caller.setMonitor(parentMonitor);
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var primary = new IOException("OLD structural failure");
        var bothStarted = new CountDownLatch(2);
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.loadFailure = primary;
        var executor = new InterruptingCompletedGetExecutor();
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        assertTrue(executor.interruptedCompletedGet.get(),
                "the seam must interrupt only after the failed Future is complete");
        assertSame(primary, failure,
                "the earlier worker failure remains the exact primary");
        assertEquals(0, failure.getSuppressed().length,
                "the late caller interrupt is status only, not another failure");
        assertFalse(parentMonitor.cancelSawInterrupt.get());
        assertFalse(pair.oldPlan.cancelSawInterrupt.get());
        assertFalse(pair.newPlan.cancelSawInterrupt.get());
        assertFalse(pair.oldPlan.closeSawInterrupt.get());
        assertFalse(pair.newPlan.closeSawInterrupt.get());
        assertTrue(call.interruptedOnExit.get(),
                "the late caller interrupt must be restored after cleanup");
        assertWorkersGone(pair);
    }

    @Test
    void cleanupAwaitInterruptClearsStickyFlagWithoutAddingFailure()
            throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var primary = new IOException("OLD structural failure");
        var bothStarted = new CountDownLatch(2);
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.loadFailure = primary;
        pair.newPlan.ignoreInterrupts = true;
        var executor = new StickyInterruptAwaitExecutor();
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);
        await(executor.firstAwaitInterrupted);
        pair.newPlan.loadRelease.countDown();
        Throwable failure = call.awaitFailure();

        assertSame(primary, failure);
        assertEquals(0, failure.getSuppressed().length,
                "cleanup interruption is status only, not another failure");
        assertFalse(executor.retrySawInterrupt.get(),
                "cleanup must explicitly clear a hostile await interrupt before retrying");
        assertTrue(call.interruptedOnExit.get());
        assertWorkersGone(pair);
    }

    @Test
    void secondaryCallerInterruptDuringCleanupOnlyRestoresFlag() throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = latch();
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.ignoreInterrupts = true;
        pair.newPlan.ignoreInterrupts = true;
        var executor = new CleanupInterruptExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));
        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);

        Throwable failure;
        try {
            call.thread.interrupt();
            await(executor.awaitEntered);
            call.thread.interrupt();
            await(executor.awaitInterrupted);
        } finally {
            pair.oldPlan.loadRelease.countDown();
            pair.newPlan.loadRelease.countDown();
        }
        failure = call.awaitFailure();
        executor.awaitActualTermination();

        assertInstanceOf(InterruptedException.class, failure);
        assertEquals(0, failure.getSuppressed().length,
                "a repeated caller interrupt is remembered, not a cleanup failure");
        assertTrue(call.interruptedOnExit.get());
        assertWorkersGone(pair);
    }

    @Test
    void independentSecondaryFailureIsSuppressedInLogicalOrder() throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var physicalFailureOrder = synchronizedList();
        var oldFailure = new IOException("OLD independent failure");
        var newFailure = new IOException("NEW independent failure");
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = latch();
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.failureOnInterrupt = oldFailure;
        pair.newPlan.failureOnInterrupt = newFailure;
        pair.oldPlan.failureOrder = physicalFailureOrder;
        pair.newPlan.failureOrder = physicalFailureOrder;
        var executor = new LifecycleRecordingExecutor(events, null);
        pair.oldPlan.failureAfterInterruptRelease = executor.newTaskExited;
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);
        call.thread.interrupt();
        Throwable primary = call.awaitFailure();

        assertInstanceOf(InterruptedException.class, primary);
        assertEquals(List.of("NEW", "OLD"), physicalFailureOrder,
                "physical failure order must be the reverse of logical suppression order");
        assertSuppressedIdentity(primary, oldFailure, newFailure);
        assertTrue(call.interruptedOnExit.get());
        assertWorkersGone(pair);
    }

    @Test
    void firstRecordedWorkerFailureWinsWhenOtherCompletionIsObservedFirst()
            throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var physicalFailureOrder = synchronizedList();
        var newPrimary = new IOException("NEW recorded first");
        var oldSecondary = new IOException("OLD observed first");
        var executor = new LifecycleRecordingExecutor(events, null);
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = executor.newTaskCompleted;
        pair.newPlan.loadRelease = bothStarted;
        pair.oldPlan.loadFailure = oldSecondary;
        pair.newPlan.loadFailure = newPrimary;
        pair.oldPlan.failureOrder = physicalFailureOrder;
        pair.newPlan.failureOrder = physicalFailureOrder;
        pair.newPlan.cancelCalled = latch();
        executor.newTaskExitRelease = pair.newPlan.cancelCalled;
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        assertEquals(List.of("NEW", "OLD"), physicalFailureOrder);
        assertSame(newPrimary, failure,
                "primary identity follows worker occurrence, not completion observation");
        assertSuppressedIdentity(newPrimary, oldSecondary);
        assertWorkersGone(pair);
    }

    @Test
    void inducedCancellationIsNotSuppressed() throws Exception {
        for (Throwable induced : List.of(
                new CancellationException("future cancellation"),
                new InterruptedException("worker interruption"),
                new MonitorCancelledRuntimeException("monitor cancellation"))) {
            var pair = pair(new CoreSettings(), synchronizedList(),
                    null, version(17), null, version(17));
            var bothStarted = new CountDownLatch(2);
            var primary = new IOException("OLD wins before "
                    + induced.getClass().getSimpleName());
            pair.oldPlan.loadStarted = bothStarted;
            pair.newPlan.loadStarted = bothStarted;
            pair.oldPlan.loadRelease = bothStarted;
            pair.newPlan.loadRelease = latch();
            pair.oldPlan.loadFailure = primary;
            pair.newPlan.failureOnInterrupt = induced;

            AsyncLoad call = start(pair);
            await(bothStarted);
            Throwable failure = call.awaitFailure();

            assertSame(primary, failure);
            assertSame(induced, pair.newPlan.loadThrown.get(),
                    "the cancellation-shaped failure must actually occur");
            assertEquals(0, failure.getSuppressed().length,
                    induced.getClass().getSimpleName()
                    + " induced after the primary must not be suppressed");
            assertWorkersGone(pair);
        }
    }

    @Test
    void cancellationShapedFailureBeforeAnyPrimaryPreservesIdentity()
            throws Exception {
        for (Throwable primary : List.of(
                new CancellationException("first future cancellation"),
                new InterruptedException("first worker interruption"),
                new MonitorCancelledRuntimeException("first monitor cancellation"))) {
            var pair = pair(new CoreSettings(), synchronizedList(),
                    null, version(17), null, version(17));
            var bothStarted = new CountDownLatch(2);
            pair.oldPlan.loadStarted = bothStarted;
            pair.newPlan.loadStarted = bothStarted;
            pair.oldPlan.loadRelease = bothStarted;
            pair.newPlan.loadRelease = latch();
            pair.oldPlan.loadFailure = primary;

            AsyncLoad call = start(pair);
            await(bothStarted);
            Throwable failure = call.awaitFailure();

            assertSame(primary, failure,
                    "cancellation is induced only after another primary exists");
            assertEquals(0, failure.getSuppressed().length);
            assertWorkersGone(pair);
        }
    }

    @Test
    void cleanupFailuresFollowDocumentedOrder() throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        caller.setMonitor(new RecordingParentMonitor(events));
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var primary = new IOException("OLD structural failure");
        var oldCancelFailure = new IOException("OLD cancel failure");
        var newCancelFailure = new IOException("NEW cancel failure");
        var terminationFailure = new IllegalStateException("termination probe failure");
        var oldCloseFailure = new IOException("OLD close failure");
        var newCloseFailure = new IOException("NEW close failure");
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.loadFailure = primary;
        pair.oldPlan.cancelFailure = oldCancelFailure;
        pair.newPlan.cancelFailure = newCancelFailure;
        pair.oldPlan.closeFailure = oldCloseFailure;
        pair.newPlan.closeFailure = newCloseFailure;
        var executor = new LifecycleRecordingExecutor(events, terminationFailure);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        assertSame(primary, failure);
        assertSuppressedIdentity(primary,
                oldCancelFailure, newCancelFailure, terminationFailure,
                oldCloseFailure, newCloseFailure);
        assertEquals(List.of(
                "parent cancel",
                "OLD cancel", "NEW cancel",
                "OLD future cancel", "NEW future cancel",
                "shutdownNow", "executor terminated",
                "executor termination failure",
                "OLD close", "NEW close"),
                lifecycleEvents(events));
        assertWorkersGone(pair);
        assertFalse(pair.oldPlan.closeWhileOwnerActive.get());
        assertFalse(pair.newPlan.closeWhileOwnerActive.get());
    }

    @Test
    void futureCancelFailureDoesNotPreventSiblingCancellationAndJoin()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        caller.setMonitor(new RecordingParentMonitor(events));
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var primary = new IOException("OLD structural failure");
        var futureCancelFailure = new IllegalStateException("OLD future cancel failure");
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.loadFailure = primary;
        var executor = new LifecycleRecordingExecutor(
                events, null, futureCancelFailure, null);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        assertSame(primary, failure);
        assertSuppressedIdentity(primary, futureCancelFailure);
        assertEquals(List.of(
                "parent cancel",
                "OLD cancel", "NEW cancel",
                "OLD future cancel", "NEW future cancel",
                "shutdownNow", "executor terminated",
                "OLD close", "NEW close"),
                lifecycleEvents(events));
        assertWorkersGone(pair);
    }

    @Test
    void terminationTimeoutIsExplicitCleanupFailure() throws Exception {
        var events = synchronizedList();
        var pair = hostileFailurePair(events);
        var executor = new DeadlineRecordingExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, TERMINATION_TIMEOUT);
        AsyncLoad call = start(pair, coordinator);
        await(pair.oldPlan.loadStarted);
        await(pair.newPlan.loadStarted);

        Throwable primary;
        try {
            primary = call.awaitFailure();
        } finally {
            pair.newPlan.loadRelease.countDown();
            executor.awaitActualTermination();
        }

        assertSame(pair.oldPlan.loadFailure, primary);
        assertTimeoutSuppressed(primary);
        assertTrue(events.contains("executor timeout"));
        assertEquals(1, pair.newPlan.ownerCleanupCalls.get());
        assertEquals(0, pair.newPlan.closeCalls.get());
        assertWorkersGone(pair);
    }

    @Test
    void terminationDeadlineStartsAfterCancellationCallsReturn() throws Exception {
        var events = synchronizedList();
        var pair = hostileFailurePair(events);
        var clock = new MutableNanoClock();
        var timeout = Duration.ofSeconds(5);
        pair.oldPlan.cancelAction = () -> clock.advance(Duration.ofSeconds(6));
        pair.newPlan.cancelAction = pair.newPlan.loadRelease::countDown;
        var executor = new PositiveAwaitRevealsTerminationExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, timeout, clock);
        AsyncLoad call = start(pair, coordinator);
        await(pair.oldPlan.loadStarted);

        Throwable primary = call.awaitFailure();

        assertSame(pair.oldPlan.loadFailure, primary);
        assertEquals(List.of(timeout.toNanos()), executor.awaitBudgets,
                "cancellation time must not consume the termination budget");
        assertEquals(0, primary.getSuppressed().length,
                "a terminated executor must not produce a false timeout");
        assertEquals(1, pair.oldPlan.closeCalls.get());
        assertEquals(1, pair.newPlan.closeCalls.get());
        assertTrue(executor.isActuallyTerminated());
        assertWorkersGone(pair);
    }

    @Test
    void cleanupReusesOneDeadlineAfterSpuriousAwaitReturn() throws Exception {
        var events = synchronizedList();
        var pair = hostileFailurePair(events);
        var executor = new DecreasingDeadlineExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, TERMINATION_TIMEOUT);
        AsyncLoad call = start(pair, coordinator);
        await(pair.oldPlan.loadStarted);

        Throwable primary;
        try {
            primary = call.awaitFailure();
        } finally {
            pair.newPlan.loadRelease.countDown();
            executor.awaitActualTermination();
        }

        assertSame(pair.oldPlan.loadFailure, primary);
        assertEquals(2, executor.awaitBudgets.size(),
                "one spurious false return should consume a second remaining budget");
        long firstBudget = executor.awaitBudgets.get(0);
        long secondBudget = executor.awaitBudgets.get(1);
        assertTrue(firstBudget > 0);
        assertTrue(secondBudget > 0);
        assertTrue(secondBudget < firstBudget,
                "the second await must receive only the remaining absolute deadline budget");
        assertTimeoutSuppressed(primary);
        assertEquals(0, pair.oldPlan.closeCalls.get());
        assertEquals(0, pair.newPlan.closeCalls.get());
        assertWorkersGone(pair);
    }

    @Test
    void awaitFailureIsRecordedOnceAndStopsRetryingBeforeTimeout() throws Exception {
        var events = synchronizedList();
        var pair = hostileFailurePair(events);
        var executor = new RepeatedAwaitFailureExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofMillis(10));
        AsyncLoad call = start(pair, coordinator);
        await(pair.oldPlan.loadStarted);

        Throwable primary;
        try {
            primary = call.awaitFailure();
        } finally {
            pair.newPlan.loadRelease.countDown();
            executor.awaitActualTermination();
        }

        assertSame(pair.oldPlan.loadFailure, primary);
        assertEquals(1, executor.awaitCalls.get(),
                "an await exception must stop retries when termination is unconfirmed");
        assertEquals(2, primary.getSuppressed().length);
        assertSame(executor.firstAwaitFailure, primary.getSuppressed()[0]);
        IOException timeout = assertInstanceOf(
                IOException.class, primary.getSuppressed()[1]);
        assertEquals(Messages.ComparisonLoaderCoordinator_termination_timeout,
                timeout.getMessage());
        assertEquals(0, pair.oldPlan.closeCalls.get());
        assertEquals(0, pair.newPlan.closeCalls.get());
        assertWorkersGone(pair);
    }

    @Test
    void terminationProbeFailureIsRecordedOnceAndStopsAwaitLoop() throws Exception {
        var events = synchronizedList();
        var pair = hostileFailurePair(events);
        var executor = new TerminationProbeFailureExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, TERMINATION_TIMEOUT);
        AsyncLoad call = start(pair, coordinator);
        await(pair.oldPlan.loadStarted);

        Throwable primary;
        try {
            primary = call.awaitFailure();
        } finally {
            pair.newPlan.loadRelease.countDown();
            executor.awaitActualTermination();
        }

        assertSame(pair.oldPlan.loadFailure, primary);
        assertEquals(1, executor.probeCalls.get(),
                "an isTerminated exception must stop further hostile probes");
        assertEquals(0, executor.awaitCalls.get(),
                "an initial probe exception leaves termination unconfirmed");
        assertEquals(2, primary.getSuppressed().length);
        assertSame(executor.firstProbeFailure, primary.getSuppressed()[0]);
        IOException timeout = assertInstanceOf(
                IOException.class, primary.getSuppressed()[1]);
        assertEquals(Messages.ComparisonLoaderCoordinator_termination_timeout,
                timeout.getMessage());
        assertEquals(0, pair.oldPlan.closeCalls.get());
        assertEquals(0, pair.newPlan.closeCalls.get());
        assertWorkersGone(pair);
    }

    @Test
    void successfulWorkTerminationTimeoutIsLocalizedPrimary() throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var executor = new SuccessfulWorkTimeoutExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, TERMINATION_TIMEOUT);

        Throwable failure;
        try {
            failure = assertThrows(Throwable.class,
                    () -> coordinator.load(pair.factories(), pair.caller, ComparisonDepth.FULL));
        } finally {
            executor.releaseTerminationReport();
            executor.awaitActualTermination();
        }

        IOException timeout = assertInstanceOf(IOException.class, failure);
        assertEquals(Messages.ComparisonLoaderCoordinator_termination_timeout,
                timeout.getMessage());
        assertEquals(0, timeout.getSuppressed().length,
                "the same deadline must not create a second timeout failure");
        assertEquals(0, pair.oldPlan.closeCalls.get());
        assertEquals(0, pair.newPlan.closeCalls.get());
        assertWorkersGone(pair);
    }

    @Test
    void cleanupReusesSuccessShutdownDeadline() throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var clock = new MutableNanoClock();
        var executor = new CrossShutdownDeadlineExecutor(events, clock);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofNanos(100), clock);

        Throwable failure = assertThrows(Throwable.class,
                () -> coordinator.load(pair.factories(), pair.caller, ComparisonDepth.FULL));

        IOException timeout = assertInstanceOf(IOException.class, failure);
        assertEquals(Messages.ComparisonLoaderCoordinator_termination_timeout,
                timeout.getMessage());
        assertEquals(List.of(100L, 40L), executor.awaitBudgets,
                "cleanup must receive only the remainder of the success deadline");
        assertEquals(0, timeout.getSuppressed().length,
                "both shutdown paths must share one timeout instance");
        assertEquals(0, pair.oldPlan.closeCalls.get());
        assertEquals(0, pair.newPlan.closeCalls.get());
        executor.awaitActualTermination();
        assertWorkersGone(pair);
    }

    @Test
    void terminationTimeoutDoesNotCloseLoaderBeforeOwnerExits() throws Exception {
        var events = synchronizedList();
        var pair = hostileFailurePair(events);
        var executor = new DeadlineRecordingExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, TERMINATION_TIMEOUT);
        AsyncLoad call = start(pair, coordinator);
        await(pair.oldPlan.loadStarted);
        await(pair.newPlan.loadStarted);

        Throwable failure = null;
        int closesWhileOwnerAlive;
        try {
            failure = call.awaitFailure();
            await(pair.newPlan.interruptObserved);
            assertTrue(pair.newPlan.activeOwners.get() > 0,
                    "hostile owner must still be running at the timeout boundary");
            closesWhileOwnerAlive = pair.newPlan.closeCalls.get();
        } finally {
            pair.newPlan.loadRelease.countDown();
            executor.awaitActualTermination();
        }

        assertSame(pair.oldPlan.loadFailure, failure);
        assertEquals(0, closesWhileOwnerAlive,
                "timeout must not close a loader concurrently with its owner");
        assertFalse(pair.newPlan.closeWhileOwnerActive.get());
        assertTimeoutSuppressed(failure);
        assertEquals(1, pair.newPlan.ownerCleanupCalls.get(),
                "the timed-out owner must run its own finally cleanup");
        assertEquals(0, pair.newPlan.closeCalls.get(),
                "TIMED_OUT ownership is not closed asynchronously by the coordinator");
        assertWorkersGone(pair);
    }

    @Test
    void returningButIneffectiveCancelTimesOutWithoutConcurrentClose() throws Exception {
        var events = synchronizedList();
        var pair = hostileFailurePair(events);
        pair.newPlan.cancelCalled = latch();
        var executor = new DeadlineRecordingExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, TERMINATION_TIMEOUT);
        AsyncLoad call = start(pair, coordinator);
        await(pair.oldPlan.loadStarted);
        await(pair.newPlan.loadStarted);

        Throwable failure = null;
        int closeCallsAtTimeout;
        try {
            failure = call.awaitFailure();
            await(pair.newPlan.interruptObserved);
            closeCallsAtTimeout = pair.newPlan.closeCalls.get();
        } finally {
            pair.newPlan.loadRelease.countDown();
            executor.awaitActualTermination();
        }

        assertSame(pair.oldPlan.loadFailure, failure);
        assertEquals(1, pair.newPlan.cancelCalls.get(),
                "cancel() returned normally but did not release the owner");
        assertEquals(0, closeCallsAtTimeout);
        assertFalse(pair.newPlan.closeWhileOwnerActive.get());
        assertTimeoutSuppressed(failure);
        assertEquals(1, pair.newPlan.ownerCleanupCalls.get());
        assertEquals(0, pair.newPlan.closeCalls.get());
        assertWorkersGone(pair);
    }

    @Test
    void errorFailurePreservesIdentity() throws Exception {
        var pair = pair(new CoreSettings(), synchronizedList(),
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var primary = new AssertionError("OLD fatal failure");
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.loadFailure = primary;

        AsyncLoad call = start(pair);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        assertSame(primary, failure);
        assertWorkersGone(pair);
    }

    @Test
    void unknownCheckedFailureUsesLocalizedWrapper() throws Exception {
        var pair = pair(new CoreSettings(), synchronizedList(),
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var primary = new Exception("unknown checked worker failure");
        var cleanupFailure = new IOException("OLD cancel failure");
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.loadFailure = primary;
        pair.oldPlan.cancelFailure = cleanupFailure;

        AsyncLoad call = start(pair);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        IOException wrapper = assertInstanceOf(IOException.class, failure);
        assertEquals(Messages.ComparisonLoaderCoordinator_failed,
                wrapper.getMessage());
        assertSame(primary, wrapper.getCause());
        assertSuppressedIdentity(wrapper, cleanupFailure);
        assertEquals(0, primary.getSuppressed().length,
                "the observation wrapper must not be fed back into its own cause");
        assertWorkersGone(pair);
    }

    @Test
    void secondaryUnknownCheckedObservationWrapperIsNotSuppressed() throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var oldPrimary = new Exception("OLD unknown checked primary");
        var newSecondary = new Exception("NEW unknown checked secondary");
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.oldPlan.loadFailure = oldPrimary;
        pair.newPlan.loadFailure = newSecondary;
        pair.oldPlan.cancelCalled = latch();
        var executor = new LifecycleRecordingExecutor(events, null);
        executor.oldTaskExitRelease = pair.oldPlan.cancelCalled;
        pair.newPlan.loadRelease = executor.oldTaskCompleted;
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        IOException wrapper = assertInstanceOf(IOException.class, failure);
        assertEquals(Messages.ComparisonLoaderCoordinator_failed,
                wrapper.getMessage());
        assertSame(oldPrimary, wrapper.getCause(),
                "OLD must win the raw primary CAS before NEW is observed");
        assertSuppressedIdentity(wrapper, newSecondary);
        assertEquals(0, oldPrimary.getSuppressed().length);
        assertEquals(0, newSecondary.getSuppressed().length);
        assertWorkersGone(pair);
    }

    @Test
    void oldLoaderIsClosedWhenNewFactoryFails() throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        var parentMonitor = new RecordingParentMonitor(events);
        caller.setMonitor(parentMonitor);
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var primary = new IOException("NEW factory failure");
        var newSettings = new AtomicReference<ISettings>();
        ILoaderFactory failingNewFactory = settings -> {
            newSettings.set(settings);
            throw primary;
        };

        IOException failure = assertThrows(IOException.class,
                () -> new ComparisonLoaderCoordinator().load(
                        new ComparisonLoaderFactories(
                                pair.oldFactory, failingNewFactory), pair.caller, ComparisonDepth.FULL));

        assertSame(primary, failure);
        assertTrue(parentMonitor.isCancelled());
        assertTrue(pair.oldFactory.settings.getMonitor().isCancelled());
        assertTrue(newSettings.get().getMonitor().isCancelled());
        assertEquals(1, pair.oldPlan.cancelCalls.get());
        assertEquals(1, pair.oldPlan.closeCalls.get());
        assertEquals(0, pair.oldPlan.loadCalls.get());
        assertTrue(pair.oldPlan.workerThreads.isEmpty());
        assertEquals(List.of("parent cancel", "OLD cancel", "OLD close"),
                lifecycleEvents(events));
        assertFalse(pair.oldPlan.closeWhileOwnerActive.get());
    }

    @Test
    void factoryInterruptionPreservesIdentityAndRestoresFlag() throws Exception {
        var pair = pair(new CoreSettings(), synchronizedList(),
                null, version(17), null, version(17));
        var primary = new InterruptedException("NEW factory interrupted");
        ILoaderFactory interruptedNewFactory = settings -> {
            throw primary;
        };

        try {
            InterruptedException failure = assertThrows(InterruptedException.class,
                    () -> new ComparisonLoaderCoordinator().load(
                            new ComparisonLoaderFactories(
                                    pair.oldFactory, interruptedNewFactory), pair.caller, ComparisonDepth.FULL));

            assertSame(primary, failure);
            assertTrue(Thread.currentThread().isInterrupted(),
                    "factory interruption must restore the caller flag");
            assertEquals(1, pair.oldPlan.cancelCalls.get());
            assertEquals(1, pair.oldPlan.closeCalls.get());
            assertEquals(0, pair.oldPlan.loadCalls.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preSetFactoryInterruptIsClearedDuringCleanupAndRestoredOnExit()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        var parentMonitor = new RecordingParentMonitor(events);
        caller.setMonitor(parentMonitor);
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var primary = new InterruptedException("NEW factory pre-set interrupt");
        ILoaderFactory interruptedNewFactory = settings -> {
            Thread.currentThread().interrupt();
            throw primary;
        };

        Thread.interrupted();
        try {
            InterruptedException failure = assertThrows(InterruptedException.class,
                    () -> new ComparisonLoaderCoordinator().load(
                            new ComparisonLoaderFactories(
                                    pair.oldFactory, interruptedNewFactory), caller, ComparisonDepth.FULL));

            assertSame(primary, failure);
            assertEquals(1, pair.oldPlan.cancelCalls.get());
            assertEquals(1, pair.oldPlan.closeCalls.get());
            assertFalse(parentMonitor.cancelSawInterrupt.get(),
                    "parent cancellation must run with a cleared interrupt flag");
            assertFalse(pair.oldPlan.cancelSawInterrupt.get(),
                    "loader cancellation must run with a cleared interrupt flag");
            assertFalse(pair.oldPlan.closeSawInterrupt.get(),
                    "loader close must run with a cleared interrupt flag");
            assertTrue(Thread.currentThread().isInterrupted(),
                    "the pre-set flag must be restored only after cleanup");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void firstExecutorSubmissionRejectionClosesUnsubmittedLoaders() throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        var parentMonitor = new RecordingParentMonitor(events);
        caller.setMonitor(parentMonitor);
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var rejection = new RejectedExecutionException("reject first submission");
        var executor = new RejectingExecutor(events, 1, rejection, null);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        RejectedExecutionException failure = assertThrows(
                RejectedExecutionException.class,
                () -> coordinator.load(pair.factories(), pair.caller, ComparisonDepth.FULL));

        assertSame(rejection, failure);
        assertEquals(0, pair.oldPlan.loadCalls.get());
        assertEquals(0, pair.newPlan.loadCalls.get());
        assertEquals(1, pair.oldPlan.cancelCalls.get());
        assertEquals(1, pair.newPlan.cancelCalls.get());
        assertEquals(1, pair.oldPlan.closeCalls.get());
        assertEquals(1, pair.newPlan.closeCalls.get());
        assertTrue(parentMonitor.isCancelled());
        assertTrue(pair.oldFactory.settings.getMonitor().isCancelled());
        assertTrue(pair.newFactory.settings.getMonitor().isCancelled());
        assertTrue(executor.isTerminated());
        assertEquals(List.of(
                "parent cancel", "OLD cancel", "NEW cancel",
                "shutdownNow", "executor terminated",
                "OLD close", "NEW close"), lifecycleEvents(events));
        assertFalse(pair.oldPlan.closeWhileOwnerActive.get());
        assertFalse(pair.newPlan.closeWhileOwnerActive.get());
        assertTrue(pair.oldPlan.workerThreads.isEmpty());
        assertTrue(pair.newPlan.workerThreads.isEmpty());
    }

    @Test
    void secondExecutorSubmissionRejectionCancelsAndJoinsAcceptedWorker()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        var parentMonitor = new RecordingParentMonitor(events);
        caller.setMonitor(parentMonitor);
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        pair.oldPlan.loadStarted = latch();
        pair.oldPlan.loadRelease = latch();
        pair.oldPlan.ownerExitStarted = latch();
        pair.oldPlan.ownerExitRelease = latch();
        var rejection = new RejectedExecutionException("reject second submission");
        var executor = new RejectingExecutor(
                events, 2, rejection, pair.oldPlan.loadStarted);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        AsyncLoad call = start(pair, coordinator);
        await(pair.oldPlan.loadStarted);
        try {
            await(pair.oldPlan.ownerExitStarted);
            assertTrue(call.thread.isAlive(),
                    "coordinator must join the accepted OLD owner before returning");
            assertEquals(0, pair.oldPlan.closeCalls.get());
            assertEquals(0, pair.newPlan.closeCalls.get());
        } finally {
            pair.oldPlan.ownerExitRelease.countDown();
        }
        RejectedExecutionException failure = assertInstanceOf(
                RejectedExecutionException.class, call.awaitFailure());

        assertSame(rejection, failure);
        assertEquals(1, pair.oldPlan.loadCalls.get());
        assertEquals(0, pair.newPlan.loadCalls.get());
        assertEquals(1, pair.oldPlan.cancelCalls.get());
        assertEquals(1, pair.newPlan.cancelCalls.get());
        assertEquals(1, pair.oldPlan.closeCalls.get());
        assertEquals(1, pair.newPlan.closeCalls.get());
        assertTrue(parentMonitor.isCancelled());
        assertTrue(executor.isTerminated());
        assertEquals(List.of(
                "parent cancel", "OLD cancel", "NEW cancel",
                "shutdownNow", "executor terminated",
                "OLD close", "NEW close"), lifecycleEvents(events));
        assertFalse(pair.oldPlan.closeWhileOwnerActive.get());
        assertFalse(pair.newPlan.closeWhileOwnerActive.get());
        assertWorkersGone(pair);
    }

    @Test
    void parentMonitorCancelFailureDoesNotStopRemainingCleanup() throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        var parentMonitor = new RecordingParentMonitor(events);
        var parentFailure = new IllegalStateException("parent monitor cancel failure");
        parentMonitor.cancelFailure = parentFailure;
        caller.setMonitor(parentMonitor);
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var primary = new IOException("OLD structural failure");
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.loadFailure = primary;
        var executor = new LifecycleRecordingExecutor(events, null);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        assertSame(primary, failure);
        assertSuppressedIdentity(primary, parentFailure);
        assertEquals(List.of(
                "parent cancel",
                "OLD cancel", "NEW cancel",
                "OLD future cancel", "NEW future cancel",
                "shutdownNow", "executor terminated",
                "OLD close", "NEW close"),
                lifecycleEvents(events));
        assertEquals(1, pair.oldPlan.closeCalls.get());
        assertEquals(1, pair.newPlan.closeCalls.get());
        assertWorkersGone(pair);
    }

    @Test
    void executorFactoryFailureBeforeSubmitCancelsAndClosesWithoutOwners()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        var parentMonitor = new RecordingParentMonitor(events);
        caller.setMonitor(parentMonitor);
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var primary = new IllegalStateException("executor factory failure");
        var coordinator = new ComparisonLoaderCoordinator(
                () -> {
                    throw primary;
                }, Duration.ofSeconds(WAIT_SECONDS));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> coordinator.load(pair.factories(), caller, ComparisonDepth.FULL));

        assertSame(primary, failure);
        assertTrue(parentMonitor.isCancelled());
        assertEquals(1, pair.oldPlan.cancelCalls.get());
        assertEquals(1, pair.newPlan.cancelCalls.get());
        assertEquals(1, pair.oldPlan.closeCalls.get());
        assertEquals(1, pair.newPlan.closeCalls.get());
        assertEquals(0, pair.oldPlan.loadCalls.get());
        assertEquals(0, pair.newPlan.loadCalls.get());
        assertTrue(pair.oldPlan.workerThreads.isEmpty());
        assertTrue(pair.newPlan.workerThreads.isEmpty());
    }

    @Test
    void coordinatorLocalizationKeysExistInRootAndRussianBundles() throws IOException {
        assertNotNull(Messages.ComparisonLoaderCoordinator_failed);
        assertFalse(Messages.ComparisonLoaderCoordinator_failed.isBlank());
        assertNotNull(Messages.ComparisonLoaderCoordinator_termination_timeout);
        assertFalse(Messages.ComparisonLoaderCoordinator_termination_timeout.isBlank());

        ResourceBundle root = ResourceBundle.getBundle(
                Messages.BUNDLE_NAME, Locale.ROOT);
        ResourceBundle russian = ResourceBundle.getBundle(
                Messages.BUNDLE_NAME, Locale.forLanguageTag("ru-RU"));
        for (String key : List.of(
                "ComparisonLoaderCoordinator_failed",
                "ComparisonLoaderCoordinator_termination_timeout")) {
            assertTrue(root.containsKey(key), "missing ROOT key " + key);
            assertFalse(root.getString(key).isBlank(), "blank ROOT value " + key);
            assertTrue(russian.containsKey(key), "missing ru_RU key " + key);
            assertFalse(russian.getString(key).isBlank(), "blank ru_RU value " + key);
        }
        assertEquals("Comparison loading failed",
                root.getString("ComparisonLoaderCoordinator_failed"));
        assertEquals(
                "Comparison loader executor did not terminate before the cleanup deadline",
                root.getString("ComparisonLoaderCoordinator_termination_timeout"));

        InputStream russianResource = ComparisonLoaderCoordinatorTest.class
                .getClassLoader().getResourceAsStream(
                        "org/pgcodekeeper/core/localizations/messages_ru_RU.properties");
        assertNotNull(russianResource, "missing direct ru_RU resource");
        var russianProperties = new Properties();
        try (russianResource;
                var reader = new InputStreamReader(
                        russianResource, StandardCharsets.UTF_8)) {
            russianProperties.load(reader);
        }
        assertEquals("Не удалось загрузить сравниваемые базы данных",
                russianProperties.getProperty(
                        "ComparisonLoaderCoordinator_failed"));
        assertEquals(
                "Исполнитель загрузчиков сравнения не завершил работу до истечения срока очистки",
                russianProperties.getProperty(
                        "ComparisonLoaderCoordinator_termination_timeout"));
    }

    @Test
    void terminationTimeoutMergesSafeDiagnosticPrefixOldThenNew() throws Exception {
        var events = synchronizedList();
        var pair = hostileFailurePair(events);
        pair.oldPlan.startErrors = List.of("OLD safe prefix");
        pair.newPlan.startErrors = List.of("NEW safe prefix");
        pair.newPlan.loadErrors = List.of("NEW after timeout");
        var executor = new DeadlineRecordingExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, TERMINATION_TIMEOUT);
        AsyncLoad call = start(pair, coordinator);
        await(pair.oldPlan.loadStarted);

        Throwable failure;
        List<Object> prefixAtReturn;
        try {
            failure = call.awaitFailure();
            prefixAtReturn = List.copyOf(pair.caller.getErrors());
        } finally {
            pair.newPlan.loadRelease.countDown();
            executor.awaitActualTermination();
        }

        assertSame(pair.oldPlan.loadFailure, failure);
        assertTimeoutSuppressed(failure);
        var expectedPrefix = List.<Object>of("OLD safe prefix", "NEW safe prefix");
        assertEquals(expectedPrefix, prefixAtReturn);
        assertEquals(expectedPrefix, pair.caller.getErrors(),
                "late owner diagnostics are outside the safe timeout snapshot");
        assertEquals(expectedPrefix, pair.oldFactory.commonSettings.getErrors());
        assertEquals(1, pair.newPlan.ownerCleanupCalls.get());
        assertEquals(0, pair.newPlan.closeCalls.get());
        assertWorkersGone(pair);
    }

    @Test
    void inducedCancellationCarrierSuppressedCleanupFailureIsRetained()
            throws Exception {
        var pair = pair(new CoreSettings(), synchronizedList(),
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var primary = new IOException("OLD structural failure");
        var carrier = new InterruptedException("induced worker interruption");
        var nestedCleanup = new IOException("worker-owned cleanup failure");
        carrier.addSuppressed(nestedCleanup);
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.loadFailure = primary;
        pair.newPlan.failureOnInterrupt = carrier;

        AsyncLoad call = start(pair);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        assertSame(primary, failure);
        assertSame(carrier, pair.newPlan.loadThrown.get());
        assertSuppressedIdentity(primary, nestedCleanup);
        assertWorkersGone(pair);
    }

    @Test
    void sideMonitorsShareStickyCancellation() {
        var token = new ComparisonCancellationToken();
        var oldDelegate = new NullMonitor();
        var newDelegate = new NullMonitor();
        IMonitor oldMonitor = token.wrap(oldDelegate);
        IMonitor newMonitor = token.wrap(newDelegate);

        oldMonitor.setCancelled(true);
        assertTrue(newMonitor.isCancelled());
        newMonitor.setCancelled(false);
        assertFalse(newDelegate.isCancelled(), "false still delegates to the side monitor");
        assertTrue(oldMonitor.isCancelled(), "false cannot clear shared cancellation");
        assertTrue(newMonitor.createSubMonitor().isCancelled(),
                "nested monitors retain the shared token");
    }

    @Test
    void aliasedSideMonitorIsRejectedBeforeFactoryConstruction() {
        var caller = new CoreSettings();
        IMonitor parent = mock(IMonitor.class);
        IMonitor sharedChild = new NullMonitor();
        when(parent.createSubMonitor()).thenReturn(sharedChild);
        caller.setMonitor(parent);
        var pair = pair(caller, synchronizedList(),
                null, version(17), null, version(17));

        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonLoaderCoordinator().load(pair.factories(), caller, ComparisonDepth.FULL));
        verify(parent, times(2)).createSubMonitor();
        assertEquals(0, pair.oldFactory.createCalls.get());
        assertEquals(0, pair.newFactory.createCalls.get());
    }

    @Test
    void comparisonExtensionsFollowSuccessfulLifecycleInLogicalOrder()
            throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var binding = new CoordinatorBinding("extension", events);
        installExtension(pair, extensionKey("extension", binding, events));
        var coordinator = new ComparisonLoaderCoordinator(() -> {
            events.add("executor create");
            return new TerminationRecordingExecutor(events);
        }, Duration.ofSeconds(WAIT_SECONDS));

        coordinator.load(pair.factories(), pair.caller, ComparisonDepth.FULL);

        assertBefore(events, "OLD extension register", "NEW extension register");
        assertBefore(events, "NEW extension register", "extension bind OLD/NEW");
        assertBefore(events, "extension bind OLD/NEW", "extension activate");
        assertBefore(events, "extension activate", "executor create");
        assertEquals(1, binding.loadedCalls.get(ComparisonSide.OLD).get());
        assertEquals(1, binding.loadedCalls.get(ComparisonSide.NEW).get());
        assertTrue(binding.loadedThreads.stream()
                .allMatch(thread -> thread.getName().startsWith("pgck-compare-")));
        assertBefore(events, "extension loaded OLD", "OLD analyze exit");
        assertBefore(events, "extension loaded NEW", "NEW analyze exit");
        assertBefore(events, "executor terminated", "extension close");
        assertBefore(events, "extension close", "OLD close");
        assertBefore(events, "OLD close", "NEW close");
        assertEquals(0, binding.failedCalls.get());
        assertEquals(0, binding.cancelCalls.get());
        assertEquals(1, binding.closeCalls.get());
    }

    @Test
    void structuralSideLoadedCallbackUnblocksPeerBeforePhaseBarrier()
            throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        pair.newPlan.loadRelease = latch();
        var binding = new CoordinatorBinding("extension", events);
        binding.loadedAction = (side, database) -> {
            if (side == ComparisonSide.OLD) {
                events.add("extension publish OLD");
                pair.newPlan.loadRelease.countDown();
            }
        };
        installExtension(pair, extensionKey("extension", binding, events));

        new ComparisonLoaderCoordinator().load(pair.factories(), pair.caller, ComparisonDepth.FULL);

        assertEquals(0, pair.newPlan.loadRelease.getCount(),
                "OLD sideLoaded must publish before NEW structural load can return");
        assertBefore(events, "extension publish OLD", "extension loaded NEW");
        assertEquals(1, pair.oldPlan.analysisCalls.get());
        assertEquals(1, pair.newPlan.analysisCalls.get());
    }

    @Test
    void workerFailureKeepsOriginalAndCancelsExtensionsBeforeEverythingElse()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        caller.setMonitor(new RecordingParentMonitor(events));
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var primary = new IOException("OLD structural failure");
        var sideFailedFailure = new IOException("extension sideFailed failure");
        var extensionCancelFailure = new IOException("extension cancel failure");
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.loadFailure = primary;
        var binding = new CoordinatorBinding("extension", events);
        binding.sideFailedFailure = sideFailedFailure;
        binding.cancelFailure = extensionCancelFailure;
        installExtension(pair, extensionKey("extension", binding, events));

        AsyncLoad call = start(pair);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        assertSame(primary, failure);
        assertSuppressedIdentity(primary, sideFailedFailure, extensionCancelFailure);
        assertBefore(events, "OLD owner cleanup", "extension failed OLD");
        assertBefore(events, "extension failed OLD", "extension cancel");
        assertBefore(events, "extension cancel", "parent cancel");
        assertBefore(events, "extension cancel", "OLD cancel");
        assertBefore(events, "extension close", "OLD close");
        assertEquals(1, binding.failedCalls.get());
        assertEquals(1, binding.cancelCalls.get());
        assertEquals(1, binding.closeCalls.get());
        assertWorkersGone(pair);
    }

    @Test
    void sideLoadedFailureBecomesExactPrimaryAndIsReportedBackToBinding()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        caller.setMonitor(new RecordingParentMonitor(events));
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        pair.newPlan.loadRelease = latch();
        pair.newPlan.cancelAction = pair.newPlan.loadRelease::countDown;
        var hookFailure = new IOException("OLD sideLoaded failure");
        var binding = new CoordinatorBinding("extension", events);
        binding.loadedFailure = hookFailure;
        binding.loadedFailureSide = ComparisonSide.OLD;
        installExtension(pair, extensionKey("extension", binding, events));

        Throwable failure = assertThrows(Throwable.class,
                () -> new ComparisonLoaderCoordinator().load(
                        pair.factories(), pair.caller, ComparisonDepth.FULL));

        assertSame(hookFailure, failure);
        assertSame(hookFailure, binding.sideFailedOriginal.get());
        assertEquals(1, binding.failedCalls.get());
        assertEquals(1, binding.cancelCalls.get());
        assertBefore(events, "extension loaded OLD", "extension failed OLD");
        assertBefore(events, "extension failed OLD", "extension cancel");
        assertBefore(events, "extension cancel", "parent cancel");
    }

    @Test
    void analysisFailureReportsFailureWithoutRepeatingStructuralLoadedCallback()
            throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var analysisFailure = new IOException("OLD analysis failure");
        pair.oldPlan.analysisFailure = analysisFailure;
        var binding = new CoordinatorBinding("extension", events);
        installExtension(pair, extensionKey("extension", binding, events));

        Throwable failure = assertThrows(Throwable.class,
                () -> new ComparisonLoaderCoordinator().load(
                        pair.factories(), pair.caller, ComparisonDepth.FULL));

        assertSame(analysisFailure, failure);
        assertSame(analysisFailure, binding.sideFailedOriginal.get());
        assertEquals(1, binding.loadedCalls.get(ComparisonSide.OLD).get());
        assertEquals(1, binding.loadedCalls.get(ComparisonSide.NEW).get());
        assertEquals(1, binding.failedCalls.get());
        assertEquals(1, binding.cancelCalls.get());
        assertEquals(1, binding.closeCalls.get());
    }

    @Test
    void sideFailurePublicationClassifiesReleasedPeerInterruptionAsInduced()
            throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var releaseNew = latch();
        var primary = new IOException("OLD primary");
        var induced = new InterruptedException("NEW released by sideFailed");
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = releaseNew;
        pair.oldPlan.loadFailure = primary;
        pair.newPlan.loadFailure = induced;
        var binding = new CoordinatorBinding("extension", events);
        binding.sideFailedAction = (side, failure) -> {
            if (side == ComparisonSide.OLD) {
                releaseNew.countDown();
            }
        };
        installExtension(pair, extensionKey("extension", binding, events));

        AsyncLoad call = start(pair);
        await(bothStarted);
        Throwable failure = call.awaitFailure();

        assertSame(primary, failure);
        assertSame(induced, pair.newPlan.loadThrown.get());
        assertEquals(0, failure.getSuppressed().length,
                "peer interruption caused by sideFailed publication is induced");
        assertEquals(1, binding.failedCalls.get(),
                "induced peer cancellation is not a second protocol failure");
        assertWorkersGone(pair);
    }

    @Test
    void extensionCancelDoesNotHoldCoordinatorLockNeededByPeerCancellation()
            throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = bothStarted;
        pair.oldPlan.loadFailure = new IOException("OLD independent failure");
        pair.newPlan.loadFailure = new IOException("NEW independent failure");
        var cancelTimedOut = new AtomicBoolean();
        var binding = new CoordinatorBinding("extension", events);
        installExtension(pair, extensionKey("extension", binding, events));
        binding.cancelAction = () -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!pair.oldFactory.settings.getMonitor().isCancelled()
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            if (!pair.oldFactory.settings.getMonitor().isCancelled()) {
                cancelTimedOut.set(true);
            }
        };

        AsyncLoad call = start(pair);
        await(bothStarted);
        call.awaitFailure();

        assertFalse(cancelTimedOut.get(),
                "peer must publish token cancellation without waiting for cancel callback");
        assertEquals(1, binding.cancelCalls.get());
        assertWorkersGone(pair);
    }

    @Test
    void registrationAndActivationInterruptionsRestoreCallerFlag() throws Exception {
        var registrationPair = pair(new CoreSettings(), synchronizedList(),
                null, version(17), null, version(17));
        var registrationInterrupt = new InterruptedException("OLD registration interrupted");
        registrationPair.oldPlan.extensionRegistrationFailure = registrationInterrupt;

        Thread.interrupted();
        try {
            InterruptedException failure = assertThrows(InterruptedException.class,
                    () -> new ComparisonLoaderCoordinator().load(
                            registrationPair.factories(), registrationPair.caller, ComparisonDepth.FULL));
            assertSame(registrationInterrupt, failure);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        var activationEvents = synchronizedList();
        var activationPair = pair(new CoreSettings(), activationEvents,
                null, version(17), null, version(17));
        var activationInterrupt = new InterruptedException("extension activation interrupted");
        var binding = new CoordinatorBinding("extension", activationEvents);
        binding.activationFailure = activationInterrupt;
        installExtension(activationPair,
                extensionKey("extension", binding, activationEvents));

        try {
            InterruptedException failure = assertThrows(InterruptedException.class,
                    () -> new ComparisonLoaderCoordinator().load(
                            activationPair.factories(), activationPair.caller, ComparisonDepth.FULL));
            assertSame(activationInterrupt, failure);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, binding.cancelCalls.get());
            assertEquals(1, binding.closeCalls.get());
            assertBefore(activationEvents, "extension close", "OLD close");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void callerInterruptCancelsExtensionFirstWithClearedFlagAndRestoresOnExit()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        caller.setMonitor(new RecordingParentMonitor(events));
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = latch();
        pair.newPlan.loadRelease = latch();
        var binding = new CoordinatorBinding("extension", events);
        installExtension(pair, extensionKey("extension", binding, events));

        AsyncLoad call = start(pair);
        await(bothStarted);
        call.thread.interrupt();
        Throwable failure = call.awaitFailure();

        assertInstanceOf(InterruptedException.class, failure);
        assertTrue(call.interruptedOnExit.get());
        assertFalse(binding.cancelSawInterrupt.get());
        assertFalse(binding.closeSawInterrupt.get());
        assertBefore(events, "extension cancel", "parent cancel");
        assertBefore(events, "extension cancel", "OLD cancel");
        assertBefore(events, "extension close", "OLD close");
        assertEquals(0, binding.failedCalls.get(),
                "caller interruption is not a logical side failure callback");
        assertWorkersGone(pair);
    }

    @Test
    void extensionCloseFailuresAreFlatBeforeLoaderCloseFailures() throws Exception {
        var events = synchronizedList();
        var pair = pair(new CoreSettings(), events,
                null, version(17), null, version(17));
        var first = new CoordinatorBinding("first extension", events);
        var second = new CoordinatorBinding("second extension", events);
        var firstClose = new IOException("first extension close failure");
        var secondClose = new AssertionError("second extension close failure");
        var oldClose = new IOException("OLD close failure");
        var newClose = new IOException("NEW close failure");
        first.closeFailure = firstClose;
        second.closeFailure = secondClose;
        pair.oldPlan.closeFailure = oldClose;
        pair.newPlan.closeFailure = newClose;
        installExtension(pair, extensionKey("first", first, events));
        var secondKey = extensionKey("second", second, events);
        pair.oldPlan.additionalExtensionKey = secondKey;
        pair.newPlan.additionalExtensionKey = secondKey;

        Throwable failure = assertThrows(Throwable.class,
                () -> new ComparisonLoaderCoordinator().load(
                        pair.factories(), pair.caller, ComparisonDepth.FULL));

        assertSame(firstClose, failure);
        assertSuppressedIdentity(firstClose, secondClose, oldClose, newClose);
        assertBefore(events, "first extension close", "second extension close");
        assertBefore(events, "second extension close", "OLD close");
        assertBefore(events, "OLD close", "NEW close");
    }

    @Test
    void terminationTimeoutNeverClosesExtensionWhileOwnerIsLive() throws Exception {
        var events = synchronizedList();
        var pair = hostileFailurePair(events);
        var binding = new CoordinatorBinding("extension", events);
        installExtension(pair, extensionKey("extension", binding, events));
        var executor = new DeadlineRecordingExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, TERMINATION_TIMEOUT);
        AsyncLoad call = start(pair, coordinator);
        await(pair.oldPlan.loadStarted);
        await(pair.newPlan.loadStarted);

        Throwable failure;
        try {
            failure = call.awaitFailure();
            assertTrue(pair.newPlan.activeOwners.get() > 0);
            assertEquals(0, binding.closeCalls.get());
        } finally {
            pair.newPlan.loadRelease.countDown();
            executor.awaitActualTermination();
        }

        assertSame(pair.oldPlan.loadFailure, failure);
        assertTimeoutSuppressed(failure);
        assertEquals(1, binding.cancelCalls.get());
        assertEquals(0, binding.closeCalls.get(),
                "timed-out ownership is never closed asynchronously");
        assertWorkersGone(pair);
    }

    @Test
    void timeoutRetainsIncrementalFailureBeforeLaterCancelBindingBlocks()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        caller.setMonitor(new RecordingParentMonitor(events));
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        var releaseNew = latch();
        var secondCancelEntered = latch();
        var secondCancelRelease = latch();
        var primary = new IOException("OLD primary before blocked extension cancel");
        var secondary = new IOException("NEW independent failure releases main");
        var firstCancelFailure = new IOException("first extension cancel failure");
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = releaseNew;
        pair.oldPlan.loadFailure = primary;
        pair.newPlan.loadFailure = secondary;
        var first = new CoordinatorBinding("first extension", events);
        first.cancelFailure = firstCancelFailure;
        var second = new CoordinatorBinding("second extension", events);
        second.cancelAction = () -> {
            secondCancelEntered.countDown();
            releaseNew.countDown();
            awaitUninterruptibly(secondCancelRelease);
        };
        installExtension(pair, extensionKey("first", first, events));
        var secondKey = extensionKey("second", second, events);
        pair.oldPlan.additionalExtensionKey = secondKey;
        pair.newPlan.additionalExtensionKey = secondKey;
        var executor = new DeadlineRecordingExecutor(events);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, TERMINATION_TIMEOUT);
        AsyncLoad call = start(pair, coordinator);
        await(bothStarted);
        await(secondCancelEntered);

        Throwable failure;
        try {
            failure = call.awaitFailure();
        } finally {
            secondCancelRelease.countDown();
            executor.awaitActualTermination();
        }

        assertSame(primary, failure);
        assertEquals(3, primary.getSuppressed().length);
        assertSame(secondary, primary.getSuppressed()[0]);
        assertSame(firstCancelFailure, primary.getSuppressed()[1],
                "an earlier cancel callback failure survives a later blocked callback");
        IOException timeout = assertInstanceOf(
                IOException.class, primary.getSuppressed()[2]);
        assertEquals(Messages.ComparisonLoaderCoordinator_termination_timeout,
                timeout.getMessage());
        assertEquals(0, first.closeCalls.get());
        assertEquals(0, second.closeCalls.get());
        assertWorkersGone(pair);
    }

    @Test
    void activatedBindingCleansBeforeLoadersWhenSecondSubmissionIsRejected()
            throws Exception {
        var events = synchronizedList();
        var caller = new CoreSettings();
        caller.setMonitor(new RecordingParentMonitor(events));
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        pair.oldPlan.loadStarted = latch();
        pair.oldPlan.loadRelease = latch();
        var binding = new CoordinatorBinding("extension", events);
        installExtension(pair, extensionKey("extension", binding, events));
        var rejection = new RejectedExecutionException("reject NEW submission");
        var executor = new RejectingExecutor(events, 2, rejection, pair.oldPlan.loadStarted);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofSeconds(WAIT_SECONDS));

        Throwable failure = assertThrows(Throwable.class,
                () -> coordinator.load(pair.factories(), caller, ComparisonDepth.FULL));

        assertSame(rejection, failure);
        assertEquals(1, binding.cancelCalls.get());
        assertEquals(1, binding.closeCalls.get());
        assertBefore(events, "extension cancel", "parent cancel");
        assertBefore(events, "extension close", "OLD close");
        assertBefore(events, "OLD close", "NEW close");
        assertWorkersGone(pair);
    }

    private static void assertVersionIdentity(ISupportedVersion expected, TestPair pair,
            LoadedComparison loaded, ISettings caller) {
        assertSame(expected, pair.oldPlan.sideVersionAtAnalysis);
        assertSame(expected, pair.oldPlan.commonVersionAtAnalysis);
        assertSame(expected, pair.oldPlan.callerVersionAtAnalysis);
        assertSame(expected, pair.newPlan.sideVersionAtAnalysis);
        assertSame(expected, pair.newPlan.commonVersionAtAnalysis);
        assertSame(expected, pair.newPlan.callerVersionAtAnalysis);
        assertSame(expected, loaded.comparisonSettings().getVersion());
        assertSame(expected, caller.getVersion());
    }

    private static void installExtension(
            TestPair pair, ComparisonExtensionKey<String> key) {
        pair.oldPlan.extensionKey = key;
        pair.oldPlan.extensionEndpoint = "OLD";
        pair.newPlan.extensionKey = key;
        pair.newPlan.extensionEndpoint = "NEW";
    }

    private static ComparisonExtensionKey<String> extensionKey(
            String name, CoordinatorBinding binding, List<String> events) {
        return new ComparisonExtensionKey<>(name, String.class, (oldEndpoint, newEndpoint) -> {
            events.add(binding.name + " bind " + oldEndpoint + "/" + newEndpoint);
            return Optional.of(binding);
        });
    }

    private static void assertBefore(List<String> events, String earlier, String later) {
        int earlierIndex = events.indexOf(earlier);
        int laterIndex = events.indexOf(later);
        assertTrue(earlierIndex >= 0, () -> "missing event: " + earlier + " in " + events);
        assertTrue(laterIndex >= 0, () -> "missing event: " + later + " in " + events);
        assertTrue(earlierIndex < laterIndex,
                () -> earlier + " must precede " + later + ": " + events);
    }

    private static TestPair hostileFailurePair(List<String> events) {
        var caller = new CoreSettings();
        caller.setMonitor(new RecordingParentMonitor(events));
        var pair = pair(caller, events,
                null, version(17), null, version(17));
        var bothStarted = new CountDownLatch(2);
        pair.oldPlan.loadStarted = bothStarted;
        pair.newPlan.loadStarted = bothStarted;
        pair.oldPlan.loadRelease = bothStarted;
        pair.newPlan.loadRelease = latch();
        pair.oldPlan.loadFailure = new IOException("OLD structural failure");
        pair.newPlan.ignoreInterrupts = true;
        pair.newPlan.interruptObserved = latch();
        return pair;
    }

    private static void assertWorkersGone(TestPair pair) throws InterruptedException {
        var workers = ConcurrentHashMap.<Thread>newKeySet();
        workers.addAll(pair.oldPlan.workerThreads);
        workers.addAll(pair.newPlan.workerThreads);
        assertFalse(workers.isEmpty(), "test must observe at least one real worker");
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
        assertTrue(workers.stream().noneMatch(Thread::isAlive),
                "coordinator returned with a live worker: " + workers);
    }

    private static void assertSuppressedIdentity(
            Throwable primary, Throwable... expected) {
        Throwable[] actual = primary.getSuppressed();
        assertEquals(expected.length, actual.length,
                "unexpected suppressed failures: " + List.of(actual));
        for (int i = 0; i < expected.length; i++) {
            assertSame(expected[i], actual[i],
                    "suppressed failure at logical index " + i);
        }
    }

    private static void assertTimeoutSuppressed(Throwable primary) {
        assertEquals(1, primary.getSuppressed().length,
                "termination timeout must be an explicit cleanup failure");
        IOException timeout = assertInstanceOf(
                IOException.class, primary.getSuppressed()[0]);
        assertEquals(Messages.ComparisonLoaderCoordinator_termination_timeout,
                timeout.getMessage());
    }

    private static List<String> lifecycleEvents(List<String> events) {
        return List.copyOf(events).stream()
                .filter(event -> event.equals("parent cancel")
                        || event.endsWith(" cancel")
                        || event.equals("shutdownNow")
                        || event.startsWith("executor ")
                        || event.endsWith(" close"))
                .toList();
    }

    private static TestPair pair(CoreSettings caller, List<String> events,
            ISupportedVersion oldDetected, ISupportedVersion oldDatabaseVersion,
            ISupportedVersion newDetected, ISupportedVersion newDatabaseVersion) {
        var oldPlan = new LoaderPlan("OLD", database(oldDatabaseVersion), oldDetected);
        var newPlan = new LoaderPlan("NEW", database(newDatabaseVersion), newDetected);
        var oldFactory = new RecordingFactory(oldPlan, caller, events);
        var newFactory = new RecordingFactory(newPlan, caller, events);
        return new TestPair(caller, oldPlan, newPlan, oldFactory, newFactory);
    }

    private static IDatabase database(ISupportedVersion version) {
        IDatabase database = mock(IDatabase.class);
        when(database.getVersion()).thenReturn(version);
        return database;
    }

    private static TestVersion version(int version) {
        return new TestVersion(version, Integer.toString(version));
    }

    private static CountDownLatch latch() {
        return new CountDownLatch(1);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "timed out waiting for test latch");
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

    private static List<String> synchronizedList() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    private static AsyncLoad start(TestPair pair) {
        return start(pair, new ComparisonLoaderCoordinator());
    }

    private static AsyncLoad start(TestPair pair,
            ComparisonLoaderCoordinator coordinator) {
        var result = new AtomicReference<LoadedComparison>();
        var failure = new AtomicReference<Throwable>();
        var interruptedOnExit = new AtomicBoolean();
        var thread = new Thread(() -> {
            try {
                result.set(coordinator.load(pair.factories(), pair.caller, ComparisonDepth.FULL));
            } catch (Throwable ex) {
                failure.set(ex);
            } finally {
                interruptedOnExit.set(Thread.currentThread().isInterrupted());
            }
        }, "comparison-coordinator-test-caller");
        thread.start();
        return new AsyncLoad(thread, result, failure, interruptedOnExit);
    }

    private record TestPair(
            CoreSettings caller,
            LoaderPlan oldPlan,
            LoaderPlan newPlan,
            RecordingFactory oldFactory,
            RecordingFactory newFactory) {

        ComparisonLoaderFactories factories() {
            return new ComparisonLoaderFactories(oldFactory, newFactory);
        }
    }

    private record TestVersion(int version, String text) implements ISupportedVersion {

        @Override
        public int getVersion() {
            return version;
        }

        @Override
        public String getText() {
            return text;
        }
    }

    private static final class LoaderPlan {

        private final String side;
        private final IDatabase database;
        private final ISupportedVersion detectedVersion;
        private final AtomicInteger loadCalls = new AtomicInteger();
        private final AtomicInteger analysisCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger ownerCleanupCalls = new AtomicInteger();
        private final AtomicInteger activeOwners = new AtomicInteger();
        private final AtomicBoolean closeWhileOwnerActive = new AtomicBoolean();
        private final AtomicBoolean cancelSawInterrupt = new AtomicBoolean();
        private final AtomicBoolean closeSawInterrupt = new AtomicBoolean();
        private final AtomicReference<Throwable> loadThrown = new AtomicReference<>();
        private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();

        private volatile IDatabase analyzedDatabase;
        private volatile CountDownLatch loadStarted = new CountDownLatch(0);
        private volatile CountDownLatch loadRelease = new CountDownLatch(0);
        private volatile CountDownLatch loadFinished = new CountDownLatch(0);
        private volatile CountDownLatch analysisStarted = new CountDownLatch(0);
        private volatile CountDownLatch analysisRelease = new CountDownLatch(0);
        private volatile CountDownLatch analysisFinished = new CountDownLatch(0);
        private volatile CountDownLatch cancelCalled = new CountDownLatch(0);
        private volatile CountDownLatch interruptObserved = new CountDownLatch(0);
        private volatile CountDownLatch ownerExited = latch();
        private volatile CountDownLatch ownerExitStarted = new CountDownLatch(0);
        private volatile CountDownLatch ownerExitRelease = new CountDownLatch(0);
        private volatile CountDownLatch failureAfterInterruptRelease =
                new CountDownLatch(0);
        private volatile List<Object> startErrors = List.of();
        private volatile List<Object> loadErrors = List.of();
        private volatile List<Object> analysisErrors = List.of();
        private volatile List<String> failureOrder;
        private volatile Throwable loadFailure;
        private volatile Throwable analysisFailure;
        private volatile Throwable failureOnInterrupt;
        private volatile Throwable cancelFailure;
        private volatile Throwable closeFailure;
        private volatile Throwable extensionRegistrationFailure;
        private volatile Runnable cancelAction;
        private volatile boolean ignoreInterrupts;
        private volatile ISupportedVersion sideVersionAtAnalysis;
        private volatile ISupportedVersion commonVersionAtAnalysis;
        private volatile ISupportedVersion callerVersionAtAnalysis;
        private volatile ComparisonExtensionKey<String> extensionKey;
        private volatile ComparisonExtensionKey<String> additionalExtensionKey;
        private volatile String extensionEndpoint;

        private LoaderPlan(String side, IDatabase database, ISupportedVersion detectedVersion) {
            this.side = side;
            this.database = database;
            this.detectedVersion = detectedVersion;
            this.analyzedDatabase = database;
        }
    }

    private static final class RecordingFactory implements ILoaderFactory {

        private final LoaderPlan plan;
        private final ISettings caller;
        private final List<String> events;
        private final AtomicInteger createCalls = new AtomicInteger();

        private volatile ISettings commonSettings;
        private volatile ISettings settings;
        private volatile RecordingLoader loader;

        private RecordingFactory(LoaderPlan plan, ISettings caller, List<String> events) {
            this.plan = plan;
            this.caller = caller;
            this.events = events;
        }

        @Override
        public void contributeCommonConfiguration(ISettings settings) {
            events.add(plan.side + " contribute");
            commonSettings = settings;
        }

        @Override
        public ILoader create(ISettings settings) {
            events.add(plan.side + " create");
            createCalls.incrementAndGet();
            this.settings = settings;
            loader = new RecordingLoader(plan, settings, commonSettings, caller, events);
            return loader;
        }
    }

    private static final class RecordingLoader implements ILoader {

        private final LoaderPlan plan;
        private final ISettings settings;
        private final ISettings common;
        private final ISettings caller;
        private final List<String> events;

        private RecordingLoader(LoaderPlan plan, ISettings settings, ISettings common,
                ISettings caller, List<String> events) {
            this.plan = plan;
            this.settings = settings;
            this.common = common;
            this.caller = caller;
            this.events = events;
        }

        @Override
        public void registerComparisonExtensions(ComparisonExtensionContext context)
                throws IOException, InterruptedException {
            throwConfigured(plan.extensionRegistrationFailure);
            if (plan.extensionKey != null) {
                events.add(plan.side + " extension register");
                context.register(plan.extensionKey, plan.extensionEndpoint);
            }
            if (plan.additionalExtensionKey != null) {
                events.add(plan.side + " additional extension register");
                context.register(plan.additionalExtensionKey, plan.extensionEndpoint);
            }
        }

        @Override
        public IDatabase load() throws IOException, InterruptedException {
            plan.loadCalls.incrementAndGet();
            recordWorker();
            plan.activeOwners.incrementAndGet();
            try {
                settings.addErrors(plan.startErrors);
                plan.loadStarted.countDown();
                awaitLoadRelease();
                if (plan.detectedVersion != null) {
                    settings.setVersion(plan.detectedVersion);
                }
                settings.addErrors(plan.loadErrors);
                plan.loadFinished.countDown();
                throwConfigured(plan.loadFailure);
                return plan.database;
            } finally {
                plan.ownerExitStarted.countDown();
                awaitUninterruptibly(plan.ownerExitRelease);
                plan.activeOwners.decrementAndGet();
                plan.ownerCleanupCalls.incrementAndGet();
                events.add(plan.side + " owner cleanup");
                plan.ownerExited.countDown();
            }
        }

        @Override
        public IDatabase loadAndAnalyze() throws IOException, InterruptedException {
            plan.analysisCalls.incrementAndGet();
            recordWorker();
            plan.analysisStarted.countDown();
            await(plan.analysisRelease);
            plan.sideVersionAtAnalysis = settings.getVersion();
            plan.commonVersionAtAnalysis = common.getVersion();
            plan.callerVersionAtAnalysis = caller.getVersion();
            settings.addErrors(plan.analysisErrors);
            events.add(plan.side + " analyze exit");
            plan.analysisFinished.countDown();
            throwConfigured(plan.analysisFailure);
            return plan.analyzedDatabase;
        }

        private void recordWorker() {
            plan.workerThreads.add(Thread.currentThread());
        }

        private void awaitLoadRelease() throws IOException, InterruptedException {
            while (true) {
                try {
                    await(plan.loadRelease);
                    return;
                } catch (InterruptedException interrupted) {
                    plan.interruptObserved.countDown();
                    if (plan.failureOnInterrupt != null) {
                        awaitUninterruptibly(plan.failureAfterInterruptRelease);
                        throwConfigured(plan.failureOnInterrupt);
                    }
                    if (!plan.ignoreInterrupts) {
                        throw interrupted;
                    }
                }
            }
        }

        private void throwConfigured(Throwable failure)
                throws IOException, InterruptedException {
            if (failure == null) {
                return;
            }
            if (plan.failureOrder != null) {
                plan.failureOrder.add(plan.side);
            }
            plan.loadThrown.set(failure);
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (failure instanceof InterruptedException interruptedFailure) {
                throw interruptedFailure;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            ComparisonLoaderCoordinatorTest.<RuntimeException>sneakyThrow(failure);
            throw new AssertionError("unreachable");
        }

        @Override
        public IDatabase getDatabase() {
            return plan.database;
        }

        @Override
        public String getDatabaseName() {
            return plan.side;
        }

        @Override
        public ISettings getSettings() {
            return settings;
        }

        @Override
        public List<Object> getErrors() {
            return Collections.unmodifiableList(settings.getErrors());
        }

        @Override
        public void cancel() throws IOException {
            plan.cancelSawInterrupt.set(Thread.currentThread().isInterrupted());
            plan.cancelCalls.incrementAndGet();
            events.add(plan.side + " cancel");
            plan.cancelCalled.countDown();
            if (plan.cancelAction != null) {
                plan.cancelAction.run();
            }
            if (plan.cancelFailure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (plan.cancelFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (plan.cancelFailure instanceof Error error) {
                throw error;
            }
        }

        @Override
        public void close() throws IOException {
            plan.closeSawInterrupt.set(Thread.currentThread().isInterrupted());
            if (plan.activeOwners.get() > 0) {
                plan.closeWhileOwnerActive.set(true);
            }
            plan.closeCalls.incrementAndGet();
            events.add(plan.side + " close");
            if (plan.closeFailure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (plan.closeFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (plan.closeFailure instanceof Error error) {
                throw error;
            }
        }
    }

    @FunctionalInterface
    private interface SideLoadedAction {
        void accept(ComparisonSide side, IDatabase database)
                throws IOException, InterruptedException;
    }

    private static final class CoordinatorBinding implements IComparisonExtensionBinding {

        private final String name;
        private final List<String> events;
        private final java.util.Map<ComparisonSide, AtomicInteger> loadedCalls = java.util.Map.of(
                ComparisonSide.OLD, new AtomicInteger(),
                ComparisonSide.NEW, new AtomicInteger());
        private final Set<Thread> loadedThreads = ConcurrentHashMap.newKeySet();
        private final AtomicInteger failedCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicReference<Throwable> sideFailedOriginal = new AtomicReference<>();
        private final AtomicBoolean cancelSawInterrupt = new AtomicBoolean();
        private final AtomicBoolean closeSawInterrupt = new AtomicBoolean();

        private volatile Throwable activationFailure;
        private volatile Throwable loadedFailure;
        private volatile ComparisonSide loadedFailureSide;
        private volatile Throwable sideFailedFailure;
        private volatile Throwable cancelFailure;
        private volatile Throwable closeFailure;
        private volatile SideLoadedAction loadedAction = (side, database) -> { };
        private volatile java.util.function.BiConsumer<ComparisonSide, Throwable>
                sideFailedAction = (side, failure) -> { };
        private volatile Runnable cancelAction = () -> { };

        private CoordinatorBinding(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void activate() throws IOException, InterruptedException {
            events.add(name + " activate");
            throwConfigured(activationFailure);
        }

        @Override
        public void sideLoaded(ComparisonSide side, IDatabase database)
                throws IOException, InterruptedException {
            loadedCalls.get(side).incrementAndGet();
            loadedThreads.add(Thread.currentThread());
            loadedAction.accept(side, database);
            events.add(name + " loaded " + side);
            if (loadedFailureSide == null || loadedFailureSide == side) {
                throwConfigured(loadedFailure);
            }
        }

        @Override
        public void sideFailed(ComparisonSide side, Throwable failure) throws IOException {
            failedCalls.incrementAndGet();
            sideFailedOriginal.compareAndSet(null, failure);
            sideFailedAction.accept(side, failure);
            events.add(name + " failed " + side);
            throwCleanup(sideFailedFailure);
        }

        @Override
        public void cancel() throws IOException {
            cancelSawInterrupt.set(Thread.currentThread().isInterrupted());
            cancelCalls.incrementAndGet();
            events.add(name + " cancel");
            cancelAction.run();
            throwCleanup(cancelFailure);
        }

        @Override
        public void close() throws IOException {
            closeSawInterrupt.set(Thread.currentThread().isInterrupted());
            closeCalls.incrementAndGet();
            events.add(name + " close");
            throwCleanup(closeFailure);
        }

        private static void throwConfigured(Throwable failure)
                throws IOException, InterruptedException {
            if (failure instanceof InterruptedException interruptedFailure) {
                throw interruptedFailure;
            }
            throwCleanup(failure);
        }

        private static void throwCleanup(Throwable failure) throws IOException {
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private static final class RecordingParentMonitor extends NullMonitor {

        private final List<String> events;
        private final List<IMonitor> children = new ArrayList<>();
        private final List<Thread> creationThreads = new ArrayList<>();
        private final AtomicBoolean cancelSawInterrupt = new AtomicBoolean();

        private volatile RuntimeException cancelFailure;

        private RecordingParentMonitor(List<String> events) {
            this.events = events;
        }

        @Override
        public void setCancelled(boolean value) {
            if (value) {
                cancelSawInterrupt.set(Thread.currentThread().isInterrupted());
                events.add("parent cancel");
            }
            super.setCancelled(value);
            if (value && cancelFailure != null) {
                throw cancelFailure;
            }
        }

        @Override
        public IMonitor createSubMonitor() {
            var child = new NullMonitor();
            children.add(child);
            creationThreads.add(Thread.currentThread());
            events.add("monitor-" + children.size());
            return child;
        }
    }

    private static final class AliasingSettings extends CoreSettings {

        @Override
        public ISettings copy() {
            return this;
        }
    }

    private static final class TerminationRecordingExecutor extends ThreadPoolExecutor {

        private final List<String> events;

        private TerminationRecordingExecutor(List<String> events) {
            super(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), new NamedDaemonThreadFactory());
            this.events = events;
        }

        @Override
        protected void terminated() {
            events.add("executor terminated");
        }
    }

    private static final class InterruptingCompletedGetExecutor
            extends ThreadPoolExecutor {

        private final AtomicBoolean interruptNextGet = new AtomicBoolean(true);
        private final AtomicBoolean interruptedCompletedGet = new AtomicBoolean();

        private InterruptingCompletedGetExecutor() {
            super(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), new NamedDaemonThreadFactory());
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
            return new FutureTask<>(callable) {
                @Override
                public T get() throws InterruptedException, ExecutionException {
                    if (interruptNextGet.compareAndSet(true, false)) {
                        interruptedCompletedGet.set(isDone());
                        Thread.currentThread().interrupt();
                    }
                    return super.get();
                }
            };
        }
    }

    private static final class StickyInterruptAwaitExecutor
            extends ThreadPoolExecutor {

        private final AtomicBoolean firstAwait = new AtomicBoolean(true);
        private final AtomicBoolean retrySawInterrupt = new AtomicBoolean();
        private final CountDownLatch firstAwaitInterrupted = latch();

        private StickyInterruptAwaitExecutor() {
            super(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), new NamedDaemonThreadFactory());
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            if (firstAwait.compareAndSet(true, false)) {
                Thread.currentThread().interrupt();
                firstAwaitInterrupted.countDown();
                throw new InterruptedException("hostile sticky cleanup interrupt");
            }
            if (Thread.currentThread().isInterrupted()) {
                retrySawInterrupt.set(true);
                Thread.interrupted();
            }
            return super.awaitTermination(timeout, unit);
        }
    }

    private static class LifecycleRecordingExecutor extends ThreadPoolExecutor {

        private final List<String> events;
        private final RuntimeException terminationFailure;
        private final RuntimeException oldFutureCancelFailure;
        private final RuntimeException newFutureCancelFailure;
        private final AtomicBoolean terminationFailureThrown = new AtomicBoolean();
        private final AtomicBoolean terminationAwaitAttempted = new AtomicBoolean();
        private final AtomicInteger nextSide = new AtomicInteger();
        private final CountDownLatch newTaskExited = latch();
        private final CountDownLatch oldTaskCompleted = latch();
        private final CountDownLatch newTaskCompleted = latch();

        private volatile CountDownLatch oldTaskExitRelease = new CountDownLatch(0);
        private volatile CountDownLatch newTaskExitRelease = new CountDownLatch(0);

        private LifecycleRecordingExecutor(
                List<String> events, RuntimeException terminationFailure) {
            this(events, terminationFailure, null, null);
        }

        private LifecycleRecordingExecutor(List<String> events,
                RuntimeException terminationFailure,
                RuntimeException oldFutureCancelFailure,
                RuntimeException newFutureCancelFailure) {
            super(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), new NamedDaemonThreadFactory());
            this.events = events;
            this.terminationFailure = terminationFailure;
            this.oldFutureCancelFailure = oldFutureCancelFailure;
            this.newFutureCancelFailure = newFutureCancelFailure;
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
            String side = nextSide.getAndIncrement() % 2 == 0 ? "OLD" : "NEW";
            RuntimeException cancelFailure = side.equals("OLD")
                    ? oldFutureCancelFailure
                    : newFutureCancelFailure;
            CountDownLatch taskCompleted = side.equals("OLD")
                    ? oldTaskCompleted
                    : newTaskCompleted;
            CountDownLatch taskExitRelease = side.equals("OLD")
                    ? oldTaskExitRelease
                    : newTaskExitRelease;
            return new LifecycleFutureTask<>(
                    callable, side, events, cancelFailure, newTaskExited,
                    taskCompleted, taskExitRelease);
        }

        @Override
        public List<Runnable> shutdownNow() {
            events.add("shutdownNow");
            return super.shutdownNow();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            terminationAwaitAttempted.set(true);
            boolean terminated = super.awaitTermination(timeout, unit);
            if (terminated && terminationFailure != null
                    && terminationFailureThrown.compareAndSet(false, true)) {
                events.add("executor termination failure");
                throw terminationFailure;
            }
            return terminated;
        }

        @Override
        public boolean isTerminated() {
            if (terminationFailure != null && !terminationAwaitAttempted.get()) {
                return false;
            }
            return super.isTerminated();
        }

        @Override
        protected void terminated() {
            events.add("executor terminated");
        }
    }

    private static final class LifecycleFutureTask<T> extends FutureTask<T> {

        private final String side;
        private final List<String> events;
        private final RuntimeException cancelFailure;
        private final CountDownLatch newTaskExited;
        private final CountDownLatch taskCompleted;
        private final CountDownLatch taskExitRelease;

        private LifecycleFutureTask(
                Callable<T> callable, String side, List<String> events,
                RuntimeException cancelFailure, CountDownLatch newTaskExited,
                CountDownLatch taskCompleted, CountDownLatch taskExitRelease) {
            super(callable);
            this.side = side;
            this.events = events;
            this.cancelFailure = cancelFailure;
            this.newTaskExited = newTaskExited;
            this.taskCompleted = taskCompleted;
            this.taskExitRelease = taskExitRelease;
        }

        @Override
        public void run() {
            try {
                super.run();
            } finally {
                taskCompleted.countDown();
                if ("NEW".equals(side)) {
                    newTaskExited.countDown();
                }
                awaitUninterruptibly(taskExitRelease);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            events.add(side + " future cancel");
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelFailure != null) {
                throw cancelFailure;
            }
            return cancelled;
        }
    }

    private static final class SuccessfulWorkTimeoutExecutor
            extends LifecycleRecordingExecutor {

        private final CountDownLatch revealTermination = latch();
        private final AtomicBoolean terminationRevealed = new AtomicBoolean();

        private SuccessfulWorkTimeoutExecutor(List<String> events) {
            super(events, null);
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            super.awaitTermination(timeout, unit);
            return revealTermination.await(timeout, unit) && super.isTerminated();
        }

        @Override
        public boolean isTerminated() {
            return terminationRevealed.get() && super.isTerminated();
        }

        private void releaseTerminationReport() {
            terminationRevealed.set(true);
            revealTermination.countDown();
        }

        private void awaitActualTermination() throws InterruptedException {
            assertTrue(super.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                    "executor did not actually terminate");
        }
    }

    private static final class CrossShutdownDeadlineExecutor
            extends LifecycleRecordingExecutor {

        private final MutableNanoClock clock;
        private final List<Long> awaitBudgets = new ArrayList<>();

        private CrossShutdownDeadlineExecutor(
                List<String> events, MutableNanoClock clock) {
            super(events, null);
            this.clock = clock;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            long budget = unit.toNanos(timeout);
            awaitBudgets.add(budget);
            if (awaitBudgets.size() == 1) {
                clock.advanceNanos(60);
            } else {
                clock.advanceNanos(budget);
            }
            return false;
        }

        private void awaitActualTermination() throws InterruptedException {
            assertTrue(super.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                    "executor did not actually terminate");
        }
    }

    private static final class CleanupInterruptExecutor
            extends LifecycleRecordingExecutor {

        private final CountDownLatch awaitEntered = latch();
        private final CountDownLatch awaitInterrupted = latch();

        private CleanupInterruptExecutor(List<String> events) {
            super(events, null);
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            awaitEntered.countDown();
            try {
                return super.awaitTermination(timeout, unit);
            } catch (InterruptedException ex) {
                awaitInterrupted.countDown();
                throw ex;
            }
        }

        private void awaitActualTermination() throws InterruptedException {
            assertTrue(super.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                    "executor did not terminate after owner release");
        }
    }

    private static final class RepeatedAwaitFailureExecutor
            extends LifecycleRecordingExecutor {

        private final AtomicInteger awaitCalls = new AtomicInteger();
        private final RuntimeException firstAwaitFailure =
                new IllegalStateException("first await failure");
        private final RuntimeException secondAwaitFailure =
                new IllegalStateException("second await failure");
        private final CountDownLatch deadlineGuard = latch();

        private RepeatedAwaitFailureExecutor(List<String> events) {
            super(events, null);
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            int call = awaitCalls.incrementAndGet();
            if (call == 1) {
                throw firstAwaitFailure;
            }
            if (call == 2) {
                throw secondAwaitFailure;
            }
            return deadlineGuard.await(timeout, unit);
        }

        private void awaitActualTermination() throws InterruptedException {
            assertTrue(super.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                    "executor did not terminate after hostile owner release");
        }
    }

    private static final class TerminationProbeFailureExecutor
            extends LifecycleRecordingExecutor {

        private final AtomicInteger probeCalls = new AtomicInteger();
        private final AtomicInteger awaitCalls = new AtomicInteger();
        private final RuntimeException firstProbeFailure =
                new IllegalStateException("first termination probe failure");

        private TerminationProbeFailureExecutor(List<String> events) {
            super(events, null);
        }

        @Override
        public boolean isTerminated() {
            int call = probeCalls.incrementAndGet();
            if (call == 1) {
                throw firstProbeFailure;
            }
            throw new IllegalStateException("termination probe failure " + call);
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            awaitCalls.incrementAndGet();
            return super.awaitTermination(timeout, unit);
        }

        private void awaitActualTermination() throws InterruptedException {
            assertTrue(super.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                    "executor did not terminate after hostile owner release");
        }
    }

    private static final class DeadlineRecordingExecutor
            extends LifecycleRecordingExecutor {

        private final List<String> events;

        private DeadlineRecordingExecutor(List<String> events) {
            super(events, null);
            this.events = events;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            boolean terminated = super.awaitTermination(timeout, unit);
            if (!terminated) {
                events.add("executor timeout");
            }
            return terminated;
        }

        private void awaitActualTermination() throws InterruptedException {
            assertTrue(super.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                    "executor did not terminate after hostile owner release");
        }
    }

    private static final class DecreasingDeadlineExecutor
            extends LifecycleRecordingExecutor {

        private final List<Long> awaitBudgets = new ArrayList<>();

        private DecreasingDeadlineExecutor(List<String> events) {
            super(events, null);
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            awaitBudgets.add(unit.toNanos(timeout));
            if (awaitBudgets.size() == 1) {
                return false;
            }
            return super.awaitTermination(timeout, unit);
        }

        private void awaitActualTermination() throws InterruptedException {
            assertTrue(super.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                    "executor did not terminate after hostile owner release");
        }
    }

    private static final class PositiveAwaitRevealsTerminationExecutor
            extends LifecycleRecordingExecutor {

        private final List<Long> awaitBudgets = new ArrayList<>();
        private final AtomicBoolean awaitCalled = new AtomicBoolean();

        private PositiveAwaitRevealsTerminationExecutor(List<String> events) {
            super(events, null);
        }

        @Override
        public boolean isTerminated() {
            return awaitCalled.get() && super.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            awaitBudgets.add(unit.toNanos(timeout));
            awaitCalled.set(true);
            return super.awaitTermination(timeout, unit);
        }

        private boolean isActuallyTerminated() {
            return super.isTerminated();
        }
    }

    private static final class MutableNanoClock implements LongSupplier {

        private final AtomicLong now = new AtomicLong();

        @Override
        public long getAsLong() {
            return now.get();
        }

        private void advance(Duration duration) {
            now.addAndGet(duration.toNanos());
        }

        private void advanceNanos(long nanos) {
            now.addAndGet(nanos);
        }
    }

    private static final class RejectingExecutor extends ThreadPoolExecutor {

        private final List<String> events;
        private final int rejectedExecution;
        private final RejectedExecutionException rejection;
        private final CountDownLatch acceptedTaskStarted;
        private final AtomicInteger executions = new AtomicInteger();

        private RejectingExecutor(List<String> events, int rejectedExecution,
                RejectedExecutionException rejection,
                CountDownLatch acceptedTaskStarted) {
            super(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), new NamedDaemonThreadFactory());
            this.events = events;
            this.rejectedExecution = rejectedExecution;
            this.rejection = rejection;
            this.acceptedTaskStarted = acceptedTaskStarted;
        }

        @Override
        public void execute(Runnable command) {
            int execution = executions.incrementAndGet();
            if (execution == rejectedExecution) {
                throw rejection;
            }
            super.execute(command);
            if (execution == 1 && acceptedTaskStarted != null) {
                try {
                    await(acceptedTaskStarted);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                            "test interrupted while awaiting accepted task", ex);
                }
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            events.add("shutdownNow");
            return super.shutdownNow();
        }

        @Override
        protected void terminated() {
            events.add("executor terminated");
        }
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable task) {
            var thread = new Thread(task, "pgck-compare-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    private record AsyncLoad(
            Thread thread,
            AtomicReference<LoadedComparison> result,
            AtomicReference<Throwable> failure,
            AtomicBoolean interruptedOnExit) {

        LoadedComparison awaitSuccess() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertFalse(thread.isAlive(), "coordinator did not terminate");
            if (failure.get() != null) {
                throw new AssertionError("coordinator failed", failure.get());
            }
            assertNotNull(result.get());
            return result.get();
        }

        Throwable awaitFailure() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertFalse(thread.isAlive(), "coordinator did not terminate");
            assertNull(result.get(), "failed coordinator unexpectedly returned a result");
            assertNotNull(failure.get(), "coordinator unexpectedly succeeded");
            return failure.get();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
