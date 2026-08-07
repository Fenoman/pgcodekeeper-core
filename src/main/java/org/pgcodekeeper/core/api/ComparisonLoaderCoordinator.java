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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pgcodekeeper.core.database.api.jdbc.ISupportedVersion;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ComparisonSide;
import org.pgcodekeeper.core.database.api.loader.IComparisonAnalysisLifecycle;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.telemetry.ComparisonCancellationDrainResult;
import org.pgcodekeeper.core.telemetry.ComparisonCancellationDrainTelemetry;
import org.pgcodekeeper.core.telemetry.ComparisonStage;
import org.pgcodekeeper.core.telemetry.ComparisonStageTelemetry;
import org.pgcodekeeper.core.telemetry.ComparisonTelemetryPublisher;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.utils.PhaseTimer;
import org.pgcodekeeper.core.utils.Utils;

/**
 * Loads both logical comparison sides in parallel while keeping structural
 * loading and full analysis in separate, ordered phases.
 */
final class ComparisonLoaderCoordinator {

    private static final Duration DEFAULT_TERMINATION_TIMEOUT = Duration.ofSeconds(30);

    private final Supplier<ExecutorService> executorFactory;
    private final long terminationTimeoutNanos;
    private final LongSupplier nanoTime;
    private final LongSupplier telemetryNanoTime;

    ComparisonLoaderCoordinator() {
        this(() -> Executors.newFixedThreadPool(2, new ComparisonThreadFactory()),
                DEFAULT_TERMINATION_TIMEOUT, System::nanoTime, System::nanoTime);
    }

    ComparisonLoaderCoordinator(Supplier<ExecutorService> executorFactory,
            Duration terminationTimeout) {
        this(executorFactory, terminationTimeout, System::nanoTime, System::nanoTime);
    }

    ComparisonLoaderCoordinator(Supplier<ExecutorService> executorFactory,
            Duration terminationTimeout, LongSupplier nanoTime) {
        this(executorFactory, terminationTimeout, nanoTime, System::nanoTime);
    }

    ComparisonLoaderCoordinator(Supplier<ExecutorService> executorFactory,
            Duration terminationTimeout, LongSupplier nanoTime,
            LongSupplier telemetryNanoTime) {
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
        Objects.requireNonNull(terminationTimeout, "terminationTimeout");
        if (terminationTimeout.isNegative() || terminationTimeout.isZero()) {
            throw new IllegalArgumentException("terminationTimeout must be positive");
        }
        terminationTimeoutNanos = terminationTimeout.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.telemetryNanoTime = Objects.requireNonNull(
                telemetryNanoTime, "telemetryNanoTime");
    }

    LoadedComparison load(ComparisonLoaderFactories factories, ISettings caller,
            ComparisonDepth depth) throws IOException, InterruptedException {
        Objects.requireNonNull(factories, "factories");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(depth, "depth");

        StageTelemetry stageTelemetry = new StageTelemetry(
                caller.getComparisonTelemetry(), telemetryNanoTime);
        long prepareStart = stageTelemetry.start();
        OperationState state = null;
        ISettings common = null;
        ISettings oldSettings = null;
        ISettings newSettings = null;
        boolean errorMergeAttempted = false;

        try {
            caller.clearErrors();
            caller.resetVersion();

            IMonitor parentMonitor = Objects.requireNonNull(
                    caller.getMonitor(), "caller monitor");
            state = new OperationState(parentMonitor,
                    new ComparisonCancellationToken(), stageTelemetry,
                    depth == ComparisonDepth.FULL);

            common = distinctCopy(caller, "common", caller);
            clearRuntime(common);

            factories.oldFactory().contributeCommonConfiguration(common);
            factories.newFactory().contributeCommonConfiguration(common);

            oldSettings = distinctCopy(common, "OLD", caller, common);
            newSettings = distinctCopy(common, "NEW", caller, common, oldSettings);
            clearRuntime(oldSettings);
            clearRuntime(newSettings);

            installSideMonitors(state, oldSettings, newSettings);

            createValidated(state, ComparisonSide.OLD,
                    factories.oldFactory(), oldSettings, null);
            createValidated(state, ComparisonSide.NEW,
                    factories.newFactory(), newSettings, state.oldLoader);

            registerComparisonExtensions(
                    state, ComparisonSide.OLD, state.oldLoader);
            registerComparisonExtensions(
                    state, ComparisonSide.NEW, state.newLoader);
            state.extensions.activate();
            stageTelemetry.finish(ComparisonStage.PREPARE, prepareStart);

            state.executor = Objects.requireNonNull(executorFactory.get(), "executor");
            Databases structural = runPhase(state, false);
            if (structural.oldDatabase == structural.newDatabase) {
                throw new IllegalStateException(
                        "OLD and NEW loaders must return different database instances");
            }

            ISupportedVersion oldDetected = oldSettings.getVersion();
            ISupportedVersion newDetected = newSettings.getVersion();
            ISupportedVersion effectiveVersion = oldDetected != null || newDetected != null
                    ? minimum(oldDetected, newDetected)
                    : minimum(structural.oldDatabase.getVersion(),
                            structural.newDatabase.getVersion());

            // published before the depth branch on purpose: both depths hand
            // this version back to the caller, and a structural load has no
            // later phase left to do it in - see
            // ComparisonLoaderCoordinatorTest#structuralLoadPublishesTheSameVersionAFullLoadDoes
            publishVersion(oldSettings, effectiveVersion);
            publishVersion(newSettings, effectiveVersion);
            publishVersion(common, effectiveVersion);
            publishVersion(caller, effectiveVersion);

            Databases analyzed = structural;
            if (depth == ComparisonDepth.FULL) {
                analyzed = runPhase(state, true);
                requireSameDatabase("OLD", structural.oldDatabase, analyzed.oldDatabase);
                requireSameDatabase("NEW", structural.newDatabase, analyzed.newDatabase);
                notifyAnalysisSucceeded(state.oldLoader);
                notifyAnalysisSucceeded(state.newLoader);
            } else {
                releaseUnanalyzedLaunchers(structural.oldDatabase);
                releaseUnanalyzedLaunchers(structural.newDatabase);
            }

            long closeStart = stageTelemetry.start();
            shutdownAndJoin(state);

            state.closeAttempted = true;
            closeResources(state.extensions, state.oldLoader, state.newLoader);
            stageTelemetry.finish(ComparisonStage.LOADERS_CLOSE, closeStart);

            errorMergeAttempted = true;
            mergeErrors(oldSettings, newSettings, common, caller);
            checkComparisonCancelled(state);
            notifyComparisonSucceeded(state.oldLoader);
            notifyComparisonSucceeded(state.newLoader);

            // this path loads the two sides through factories and never reaches
            // Utils.loadDatabases, so the library privilege reset has to happen
            // here as well; a side that skipped it would report a privilege
            // difference for an object whose library was read without them
            if (!caller.isIgnorePrivileges()) {
                Utils.resetLibraryPrivileges(structural.oldDatabase, structural.newDatabase);
            }

            return new LoadedComparison(
                    structural.oldDatabase, structural.newDatabase, common, depth);
        } catch (Throwable failure) {
            if (state == null) {
                if (common != null && !errorMergeAttempted) {
                    try {
                        mergeErrors(oldSettings, newSettings, common, caller);
                    } catch (RuntimeException | Error mergeFailure) {
                        addSuppressed(failure, mergeFailure);
                    }
                }
                rethrow(failure);
                throw new AssertionError("unreachable");
            }
            notifyComparisonFailed(state, state.oldLoader);
            notifyComparisonFailed(state, state.newLoader);
            if (failure instanceof InterruptedException interruptedFailure
                    && !state.isRecordedSideFailure(failure)) {
                state.recordCallerInterrupt(interruptedFailure);
            } else {
                state.recordExternalFailure(failure);
            }
            if (!state.closeAttempted) {
                cleanupAfterFailure(state);
            }
            if (common != null && !errorMergeAttempted) {
                try {
                    mergeErrors(oldSettings, newSettings, common, caller);
                } catch (RuntimeException | Error mergeFailure) {
                    state.addCleanupFailure(mergeFailure);
                }
            }
            Throwable primary = state.attachFailures();
            rethrow(primary);
            throw new AssertionError("unreachable");
        } finally {
            if (state != null && state.restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ISettings distinctCopy(ISettings source, String name,
            ISettings... forbidden) {
        ISettings copy = Objects.requireNonNull(source.copy(), name + " settings");
        for (ISettings alias : forbidden) {
            if (copy == alias) {
                throw new IllegalArgumentException(
                        name + " settings must be an independent copy");
            }
        }
        return copy;
    }

    private static void clearRuntime(ISettings settings) {
        settings.clearErrors();
        settings.resetVersion();
    }

    private static void installSideMonitors(OperationState state,
            ISettings oldSettings, ISettings newSettings) {
        IMonitor oldDelegate = Objects.requireNonNull(
                state.parentMonitor.createSubMonitor(), "OLD monitor");
        IMonitor newDelegate = Objects.requireNonNull(
                state.parentMonitor.createSubMonitor(), "NEW monitor");
        if (oldDelegate == newDelegate) {
            throw new IllegalArgumentException(
                    "OLD and NEW sides must have different monitor instances");
        }

        oldSettings.setMonitor(state.token.wrap(oldDelegate));
        newSettings.setMonitor(state.token.wrap(newDelegate));
    }

    private static void createValidated(OperationState state, ComparisonSide side,
            ILoaderFactory factory, ISettings settings, ILoader alreadyOwned)
            throws IOException, InterruptedException {
        ILoader loader = Objects.requireNonNull(factory.create(settings), "loader");
        if (loader == alreadyOwned) {
            throw new IllegalArgumentException(
                    "OLD and NEW factories must create different loader instances");
        }
        if (side == ComparisonSide.OLD) {
            state.oldLoader = loader;
        } else {
            state.newLoader = loader;
        }
        if (loader.getSettings() != settings) {
            throw new IllegalArgumentException(
                    "Loader must retain the supplied settings instance");
        }
    }

    private static void registerComparisonExtensions(OperationState state,
            ComparisonSide side, ILoader loader)
            throws IOException, InterruptedException {
        Throwable failure = null;
        try {
            loader.registerComparisonExtensions(state.extensions.context(side));
        } catch (IOException | InterruptedException | RuntimeException | Error ex) {
            failure = ex;
        }
        try {
            state.extensions.seal(side);
        } catch (RuntimeException | Error sealFailure) {
            if (failure == null) {
                failure = sealFailure;
            } else {
                addSuppressed(failure, sealFailure);
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static Databases runPhase(OperationState state, boolean analyze)
            throws IOException, InterruptedException {
        long barrierStart = state.stageTelemetry.start();
        state.beginPhase();
        var completion = new ExecutorCompletionService<SideDatabase>(state.executor);
        state.oldFuture = completion.submit(() -> runSide(
                state, ComparisonSide.OLD, state.oldLoader, analyze));
        state.oldSubmitted = true;
        state.anySubmission = true;
        state.newFuture = completion.submit(() -> runSide(
                state, ComparisonSide.NEW, state.newLoader, analyze));
        state.newSubmitted = true;
        state.anySubmission = true;

        IDatabase oldDatabase = null;
        IDatabase newDatabase = null;
        for (int i = 0; i < 2; i++) {
            SideDatabase result = getCompleted(state, completion);
            if (result.side == ComparisonSide.OLD) {
                oldDatabase = result.database;
            } else {
                newDatabase = result.database;
            }
        }
        Databases result = new Databases(
                Objects.requireNonNull(oldDatabase, "OLD database"),
                Objects.requireNonNull(newDatabase, "NEW database"));
        state.finishPhase();
        state.stageTelemetry.finish(
                analyze ? ComparisonStage.ANALYSIS_BARRIER
                        : ComparisonStage.STRUCTURAL_BARRIER,
                barrierStart);
        return result;
    }

    private static SideDatabase runSide(OperationState state, ComparisonSide side,
            ILoader loader, boolean analyze) throws Exception {
        try {
            long telemetryStart = state.stageTelemetry.start();
            long start = PhaseTimer.start();
            IDatabase database = analyze
                    ? loader.loadAndAnalyze()
                    : loader.load();
            long telemetryElapsed = state.stageTelemetry.elapsed(telemetryStart);
            database = requireDatabase(side, database);
            if (!analyze) {
                state.extensions.sideLoaded(side, database);
            }
            recordSideLoadElapsed(state, side, loader,
                    analyze || !state.analysisPlanned, start);
            state.stageTelemetry.publish(sideStage(side, analyze), telemetryElapsed,
                    isReusableModelCapture(loader));
            return new SideDatabase(side, database);
        } catch (Exception failure) {
            failSide(state, side, failure);
            throw failure;
        } catch (Error failure) {
            failSide(state, side, failure);
            throw failure;
        }
    }

    /**
     * Reports whether this side pays the reusable-model capture cost, so a
     * cold run that is slower on purpose stays distinguishable in telemetry.
     */
    private static boolean isReusableModelCapture(ILoader loader) {
        return loader instanceof IComparisonAnalysisLifecycle lifecycle
                && lifecycle.isReusableModelCaptureEnabled();
    }

    private static ComparisonStage sideStage(ComparisonSide side, boolean analyze) {
        if (analyze) {
            return side == ComparisonSide.OLD
                    ? ComparisonStage.OLD_FULL_ANALYZE
                    : ComparisonStage.NEW_FULL_ANALYZE;
        }
        return side == ComparisonSide.OLD
                ? ComparisonStage.OLD_STRUCTURAL_LOAD
                : ComparisonStage.NEW_STRUCTURAL_LOAD;
    }

    /**
     * Accumulates this side's own on-thread load time across whichever passes
     * this operation runs, and logs the per-side total once the last of them
     * is over - the analysis pass for a full load, the structural pass for a
     * structural one, which has no analysis pass to wait for. Asking "did this
     * side analyze" instead would drop these lines from exactly the mode the
     * depth parameter exists to make fast: the accumulator would fill up and
     * be thrown away unread.
     * Barrier wait between the passes is deliberately excluded; both sides run
     * concurrently, so per-side totals may overlap in wall time.
     */
    private static void recordSideLoadElapsed(OperationState state, ComparisonSide side,
            ILoader loader, boolean lastPhase, long startNanos) {
        long elapsed = PhaseTimer.elapsed(startNanos);
        if (elapsed == 0L) {
            return;
        }
        AtomicLong sideTotal = side == ComparisonSide.OLD
                ? state.oldLoadNanos
                : state.newLoadNanos;
        long accumulated = sideTotal.addAndGet(elapsed);
        if (lastPhase) {
            PhaseTimer.endAccumulated(
                    side == ComparisonSide.OLD ? "load_old_db" : "load_new_db",
                    accumulated, loader.getClass().getSimpleName());
        }
    }

    private static void failSide(OperationState state,
            ComparisonSide side, Throwable failure) {
        boolean induced = state.recordWorkerFailure(side, failure);
        try {
            if (!induced) {
                for (Throwable callbackFailure :
                        state.extensions.sideFailed(side, failure)) {
                    addSuppressed(failure, callbackFailure);
                }
            }
        } catch (RuntimeException | Error callbackProtocolFailure) {
            addSuppressed(failure, callbackProtocolFailure);
        } finally {
            cancelExtensionsAndToken(state);
        }
    }

    private static IDatabase requireDatabase(
            ComparisonSide side, IDatabase database) {
        return Objects.requireNonNull(database, side + " database");
    }

    private static SideDatabase getCompleted(OperationState state,
            ExecutorCompletionService<SideDatabase> completion)
            throws IOException, InterruptedException {
        Future<SideDatabase> completed;
        try {
            completed = completion.take();
        } catch (InterruptedException callerInterrupt) {
            state.recordCallerInterrupt(callerInterrupt);
            throw callerInterrupt;
        }
        try {
            return completed.get();
        } catch (InterruptedException callerInterrupt) {
            state.recordCallerInterrupt(callerInterrupt);
            throw callerInterrupt;
        } catch (ExecutionException ex) {
            rethrow(ex.getCause());
            throw new AssertionError("unreachable");
        }
    }

    private static ISupportedVersion minimum(
            ISupportedVersion oldVersion, ISupportedVersion newVersion) {
        if (oldVersion == null) {
            return newVersion;
        }
        if (newVersion == null) {
            return oldVersion;
        }
        return oldVersion.getVersion() <= newVersion.getVersion()
                ? oldVersion
                : newVersion;
    }

    private static void publishVersion(ISettings settings,
            ISupportedVersion version) {
        settings.resetVersion();
        if (version != null) {
            settings.setVersion(version);
        }
    }

    /**
     * Drops the analysis launchers of a model whose analysis phase will never
     * run.
     * <p>
     * The structural phase registers a launcher per analyzable statement, and
     * each one pins a parser context - through its tokens, the character stream
     * of a whole source file - or, for a routine, its deferred body source.
     * Only {@code FullAnalyze} and the analysis replay release them, and a
     * {@link ComparisonDepth#STRUCTURAL_ONLY} load reaches neither, so the
     * returned model would carry every parsed file for as long as the caller
     * keeps it. That model is read-only and can never be scripted, so nothing
     * downstream can ask for what is released here.
     *
     * @param database structural model handed back to the caller
     */
    private static void releaseUnanalyzedLaunchers(IDatabase database) {
        for (IAnalysisLauncher launcher : database.getAnalysisLaunchers()) {
            if (launcher != null) {
                launcher.releaseWithoutAnalysis();
            }
        }
        database.clearAnalysisLaunchers();
    }

    private static void requireSameDatabase(
            String side, IDatabase structural, IDatabase analyzed) {
        if (structural != analyzed) {
            throw new IllegalStateException(
                    side + " loader returned a different database during analysis");
        }
    }

    private static void notifyAnalysisSucceeded(ILoader loader) {
        if (loader instanceof IComparisonAnalysisLifecycle lifecycle) {
            lifecycle.comparisonAnalysisSucceeded();
        }
    }

    private static void checkComparisonCancelled(OperationState state)
            throws InterruptedException {
        if (Thread.currentThread().isInterrupted()
                || state.token.isCancelled()) {
            throw new InterruptedException();
        }
        IMonitor.checkCancelled(state.parentMonitor);
    }

    private static void notifyComparisonSucceeded(ILoader loader) {
        if (loader instanceof IComparisonAnalysisLifecycle lifecycle) {
            lifecycle.comparisonSucceeded();
        }
    }

    private static void notifyComparisonFailed(
            OperationState state, ILoader loader) {
        if (!(loader instanceof IComparisonAnalysisLifecycle lifecycle)) {
            return;
        }
        try {
            lifecycle.comparisonFailed();
        } catch (RuntimeException | Error cleanupFailure) {
            state.addCleanupFailure(cleanupFailure);
        }
    }

    private void shutdownAndJoin(OperationState state)
            throws IOException, InterruptedException {
        long deadline = state.terminationDeadline(
                terminationTimeoutNanos, nanoTime.getAsLong());
        state.executor.shutdown();
        try {
            long remaining = deadline - nanoTime.getAsLong();
            if (remaining <= 0 || !state.executor.awaitTermination(
                    remaining, TimeUnit.NANOSECONDS)) {
                state.beginCancellationDrain();
                state.requestCancellation();
                throw state.terminationTimeoutFailure();
            }
            state.markTerminated();
        } catch (InterruptedException callerInterrupt) {
            state.recordCallerInterrupt(callerInterrupt);
            throw callerInterrupt;
        }
    }

    private void cleanupAfterFailure(OperationState state) {
        state.beginCancellationDrain();
        state.captureCallerInterruptStatus();
        state.requestCancellation();
        cancelExtensionsAndToken(state);
        cancelParentMonitor(state);
        cancelLoader(state, state.oldLoader);
        cancelLoader(state, state.newLoader);
        cancelFuture(state, state.oldFuture, state.oldSubmitted);
        cancelFuture(state, state.newFuture, state.newSubmitted);

        if (state.executor != null) {
            long deadline = state.terminationDeadline(
                    terminationTimeoutNanos, nanoTime.getAsLong());
            shutdownNow(state);
            awaitTermination(state, deadline);
        } else {
            state.markTerminated();
        }

        state.finishCancellationDrain();

        if (!state.anySubmission || state.isTerminated()) {
            state.closeAttempted = true;
            closeExtensionsDuringCleanup(state);
            closeDuringCleanup(state, state.oldLoader);
            if (state.newLoader != state.oldLoader) {
                closeDuringCleanup(state, state.newLoader);
            }
        }
    }

    private static void cancelExtensionsAndToken(OperationState state) {
        state.beginCancellationDrain();
        try {
            state.cancelExtensions();
        } finally {
            state.token.cancel();
        }
    }

    private static void cancelParentMonitor(OperationState state) {
        try {
            state.parentMonitor.setCancelled(true);
        } catch (RuntimeException | Error cleanupFailure) {
            state.addCleanupFailure(cleanupFailure);
        }
    }

    private static void cancelLoader(OperationState state, ILoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.cancel();
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            state.addCleanupFailure(cleanupFailure);
        }
    }

    private static void cancelFuture(
            OperationState state, Future<?> future, boolean submitted) {
        if (!submitted || future == null) {
            return;
        }
        try {
            future.cancel(true);
        } catch (RuntimeException | Error cleanupFailure) {
            state.addCleanupFailure(cleanupFailure);
        }
    }

    private static void shutdownNow(OperationState state) {
        try {
            state.executor.shutdownNow();
        } catch (RuntimeException | Error cleanupFailure) {
            state.addCleanupFailure(cleanupFailure);
        }
    }

    private void awaitTermination(OperationState state, long deadline) {
        boolean terminated = isTerminated(state);
        if (state.terminationProbeFailed) {
            recordTerminationTimeout(state);
            return;
        }
        while (!terminated) {
            long remaining = deadline - nanoTime.getAsLong();
            if (remaining <= 0) {
                break;
            }
            try {
                terminated = state.executor.awaitTermination(
                        remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException callerInterrupt) {
                state.rememberCallerInterrupt();
            } catch (RuntimeException | Error cleanupFailure) {
                state.addCleanupFailure(cleanupFailure);
                terminated = isTerminated(state);
                if (!terminated) {
                    recordTerminationTimeout(state);
                    return;
                }
            }
            terminated = terminated || isTerminated(state);
            if (state.terminationProbeFailed) {
                recordTerminationTimeout(state);
                return;
            }
        }

        terminated = terminated || isTerminated(state);
        if (!terminated) {
            recordTerminationTimeout(state);
        } else {
            state.markTerminated();
        }
    }

    private static void recordTerminationTimeout(OperationState state) {
        state.markTimedOut();
        state.addCleanupFailure(state.terminationTimeoutFailure());
    }

    private static boolean isTerminated(OperationState state) {
        if (state.terminationProbeFailed) {
            return false;
        }
        try {
            return state.executor.isTerminated();
        } catch (RuntimeException | Error cleanupFailure) {
            state.addCleanupFailure(cleanupFailure);
            state.terminationProbeFailed = true;
            return false;
        }
    }

    private static void closeDuringCleanup(OperationState state, ILoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            state.addCleanupFailure(cleanupFailure);
        }
    }

    private static void closeExtensionsDuringCleanup(OperationState state) {
        for (Throwable closeFailure : state.extensions.closeBindings()) {
            state.addCleanupFailure(closeFailure);
        }
    }

    private static void closeResources(ComparisonExtensionSession extensions,
            ILoader oldLoader, ILoader newLoader)
            throws IOException, InterruptedException {
        Throwable failure = null;
        for (Throwable closeFailure : extensions.closeBindings()) {
            failure = combine(failure, closeFailure);
        }
        failure = close(oldLoader, failure);
        if (newLoader != oldLoader) {
            failure = close(newLoader, failure);
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static Throwable combine(Throwable primary, Throwable failure) {
        if (primary == null) {
            return failure;
        }
        addSuppressed(primary, failure);
        return primary;
    }

    private static Throwable close(ILoader loader, Throwable primary) {
        if (loader == null) {
            return primary;
        }
        try {
            loader.close();
        } catch (IOException | RuntimeException | Error closeFailure) {
            if (primary == null) {
                return closeFailure;
            }
            addSuppressed(primary, closeFailure);
        }
        return primary;
    }

    private static void mergeErrors(ISettings oldSettings, ISettings newSettings,
            ISettings common, ISettings caller) {
        List<Object> oldErrors = snapshotErrors(oldSettings);
        List<Object> newErrors = snapshotErrors(newSettings);

        common.clearErrors();
        common.addErrors(oldErrors);
        common.addErrors(newErrors);

        caller.clearErrors();
        caller.addErrors(oldErrors);
        caller.addErrors(newErrors);
    }

    private static List<Object> snapshotErrors(ISettings settings) {
        if (settings == null) {
            return List.of();
        }
        List<Object> errors = settings.getErrors();
        synchronized (errors) {
            return new ArrayList<>(errors);
        }
    }

    private static void addSuppressed(Throwable primary, Throwable candidate) {
        if (primary == null || candidate == null || primary == candidate) {
            return;
        }
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == candidate) {
                return;
            }
        }
        primary.addSuppressed(candidate);
    }

    private static void rethrow(Throwable failure)
            throws IOException, InterruptedException {
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
        throw new IOException(Messages.ComparisonLoaderCoordinator_failed, failure);
    }

    private static boolean isCancellationFailure(Throwable failure) {
        return failure instanceof CancellationException
                || failure instanceof InterruptedException
                || failure instanceof MonitorCancelledRuntimeException;
    }

    private static final class OperationState {

        private final IMonitor parentMonitor;
        private final ComparisonCancellationToken token;
        private final StageTelemetry stageTelemetry;
        private final ComparisonExtensionSession extensions =
                new ComparisonExtensionSession();
        private final Object failureRecordingLock = new Object();
        private final AtomicBoolean extensionCancellationStarted = new AtomicBoolean();
        private final ConcurrentLinkedQueue<Throwable> extensionCancellationFailures =
                new ConcurrentLinkedQueue<>();
        private final AtomicReference<Throwable> primary = new AtomicReference<>();
        private final AtomicReference<SideFailure> oldFailure = new AtomicReference<>();
        private final AtomicReference<SideFailure> newFailure = new AtomicReference<>();
        private final List<Throwable> externalFailures = new ArrayList<>();
        private final List<Throwable> cleanupFailures = new ArrayList<>();
        private final AtomicLong oldLoadNanos = new AtomicLong();
        private final AtomicLong newLoadNanos = new AtomicLong();

        /**
         * Whether an analysis pass still follows the structural one, which is
         * the same as asking which pass is the last one a side will run.
         * Anything a side may only report once all of its own work is done has
         * to know that, and only the operation does - a side sees just the one
         * pass it is currently in.
         */
        private final boolean analysisPlanned;

        private ILoader oldLoader;
        private ILoader newLoader;
        private ExecutorService executor;
        private Future<?> oldFuture;
        private Future<?> newFuture;
        private boolean oldSubmitted;
        private boolean newSubmitted;
        private boolean anySubmission;
        private boolean closeAttempted;
        private boolean restoreInterrupt;
        private boolean terminationDeadlineSet;
        private boolean terminationProbeFailed;
        private boolean cancellationDrainStarted;
        private boolean cancellationDrainPublished;
        private volatile boolean cancellationInitiated;
        private long cancellationDrainStartNanos;
        private long terminationDeadline;
        private IOException terminationTimeoutFailure;
        private IOException unknownCheckedWrapper;
        private TerminationState terminationState = TerminationState.RUNNING;

        private OperationState(
                IMonitor parentMonitor, ComparisonCancellationToken token,
                StageTelemetry stageTelemetry, boolean analysisPlanned) {
            this.parentMonitor = parentMonitor;
            this.token = token;
            this.stageTelemetry = stageTelemetry;
            this.analysisPlanned = analysisPlanned;
        }

        private void beginPhase() {
            oldFuture = null;
            newFuture = null;
            oldSubmitted = false;
            newSubmitted = false;
        }

        private void finishPhase() {
            oldFuture = null;
            newFuture = null;
            oldSubmitted = false;
            newSubmitted = false;
        }

        private long terminationDeadline(long timeoutNanos, long now) {
            if (!terminationDeadlineSet) {
                terminationDeadline = now + timeoutNanos;
                terminationDeadlineSet = true;
            }
            return terminationDeadline;
        }

        private IOException terminationTimeoutFailure() {
            if (terminationTimeoutFailure == null) {
                terminationTimeoutFailure = new IOException(
                        Messages.ComparisonLoaderCoordinator_termination_timeout);
            }
            return terminationTimeoutFailure;
        }

        private void requestCancellation() {
            if (terminationState == TerminationState.RUNNING) {
                terminationState = TerminationState.CANCEL_REQUESTED;
            }
        }

        private void markTerminated() {
            terminationState = TerminationState.TERMINATED;
        }

        private void markTimedOut() {
            if (terminationState != TerminationState.TERMINATED) {
                terminationState = TerminationState.TIMED_OUT;
            }
        }

        private boolean isTerminated() {
            return terminationState == TerminationState.TERMINATED;
        }

        private synchronized void beginCancellationDrain() {
            if (!cancellationDrainStarted && stageTelemetry.isEnabled()) {
                cancellationDrainStartNanos = stageTelemetry.start();
                cancellationDrainStarted = true;
            }
        }

        private void finishCancellationDrain() {
            ComparisonCancellationDrainResult result;
            long startNanos;
            synchronized (this) {
                if (!cancellationDrainStarted || cancellationDrainPublished) {
                    return;
                }
                cancellationDrainPublished = true;
                result = terminationState == TerminationState.TIMED_OUT
                        ? ComparisonCancellationDrainResult.TIMED_OUT
                        : ComparisonCancellationDrainResult.COMPLETED;
                startNanos = cancellationDrainStartNanos;
            }
            stageTelemetry.finishCancellationDrain(result, startNanos);
        }

        private boolean recordWorkerFailure(
                ComparisonSide side, Throwable failure) {
            synchronized (failureRecordingLock) {
                Throwable earlierPrimary = primary.get();
                boolean induced = earlierPrimary != null
                        && cancellationInitiated
                        && isCancellationFailure(failure);
                AtomicReference<SideFailure> sideFailure = side == ComparisonSide.OLD
                        ? oldFailure
                        : newFailure;
                sideFailure.compareAndSet(null, new SideFailure(failure, induced));
                if (!induced && primary.compareAndSet(null, failure)) {
                    cancellationInitiated = true;
                }
                return induced;
            }
        }

        private void cancelExtensions() {
            cancellationInitiated = true;
            if (extensionCancellationStarted.compareAndSet(false, true)) {
                extensions.cancelInto(this::addExtensionCancellationFailure);
            }
        }

        private void addExtensionCancellationFailure(Throwable candidate) {
            for (Throwable failure : extensionCancellationFailures) {
                if (failure == candidate) {
                    return;
                }
            }
            extensionCancellationFailures.add(candidate);
        }

        private void recordCallerInterrupt(InterruptedException failure) {
            rememberCallerInterrupt();
            recordExternalFailure(failure);
        }

        private void captureCallerInterruptStatus() {
            if (Thread.interrupted()) {
                restoreInterrupt = true;
            }
        }

        private void rememberCallerInterrupt() {
            restoreInterrupt = true;
            Thread.interrupted();
        }

        private void recordExternalFailure(Throwable failure) {
            if (primary.compareAndSet(null, failure)) {
                return;
            }
            Throwable existing = primary.get();
            if (isUnknownCheckedObservationWrapper(failure)
                    && isRecordedSideFailure(failure.getCause())) {
                if (failure.getCause() == existing) {
                    unknownCheckedWrapper = (IOException) failure;
                }
                return;
            }
            if (existing != failure && !isRecordedSideFailure(failure)) {
                addIdentity(externalFailures, failure);
            }
        }

        private static boolean isUnknownCheckedObservationWrapper(Throwable failure) {
            return failure instanceof IOException
                    && Objects.equals(Messages.ComparisonLoaderCoordinator_failed,
                            failure.getMessage());
        }

        private boolean isRecordedSideFailure(Throwable failure) {
            SideFailure old = oldFailure.get();
            SideFailure newer = newFailure.get();
            return old != null && old.failure == failure
                    || newer != null && newer.failure == failure;
        }

        private void addCleanupFailure(Throwable failure) {
            addIdentity(cleanupFailures, failure);
        }

        private Throwable attachFailures() {
            Throwable recorded = Objects.requireNonNull(primary.get(), "primary failure");
            Throwable result = outwardPrimary(recorded);
            for (Throwable nested : recorded.getSuppressed()) {
                addSuppressed(result, nested);
            }
            attachSideFailure(result, recorded, oldFailure.get());
            attachSideFailure(result, recorded, newFailure.get());
            for (Throwable failure : externalFailures) {
                addSuppressed(result, failure);
            }
            for (Throwable failure : extensionCancellationFailures) {
                addSuppressed(result, failure);
            }
            for (Throwable failure : cleanupFailures) {
                addSuppressed(result, failure);
            }
            return result;
        }

        private Throwable outwardPrimary(Throwable recorded) {
            if (recorded instanceof IOException
                    || recorded instanceof InterruptedException
                    || recorded instanceof RuntimeException
                    || recorded instanceof Error) {
                return recorded;
            }
            if (unknownCheckedWrapper != null
                    && unknownCheckedWrapper.getCause() == recorded) {
                return unknownCheckedWrapper;
            }
            return new IOException(
                    Messages.ComparisonLoaderCoordinator_failed, recorded);
        }

        private static void attachSideFailure(
                Throwable primary, Throwable recordedPrimary,
                SideFailure sideFailure) {
            if (sideFailure == null) {
                return;
            }
            if (sideFailure.induced) {
                for (Throwable nested : sideFailure.failure.getSuppressed()) {
                    addSuppressed(primary, nested);
                }
            } else if (sideFailure.failure != recordedPrimary) {
                addSuppressed(primary, sideFailure.failure);
            }
        }

        private static void addIdentity(List<Throwable> failures, Throwable candidate) {
            for (Throwable failure : failures) {
                if (failure == candidate) {
                    return;
                }
            }
            failures.add(candidate);
        }
    }

    private record SideFailure(Throwable failure, boolean induced) {
    }

    private enum TerminationState {
        RUNNING,
        CANCEL_REQUESTED,
        TERMINATED,
        TIMED_OUT
    }

    private record SideDatabase(ComparisonSide side, IDatabase database) {
    }

    private record Databases(IDatabase oldDatabase, IDatabase newDatabase) {
    }

    /**
     * One operation-local timing guard. A disabled sink never reads the clock
     * and never allocates a telemetry event.
     */
    private static final class StageTelemetry {

        private final IComparisonTelemetry sink;
        private final LongSupplier nanoTime;
        private final boolean enabled;

        private StageTelemetry(IComparisonTelemetry sink, LongSupplier nanoTime) {
            this.sink = sink;
            this.nanoTime = nanoTime;
            enabled = ComparisonTelemetryPublisher.isEnabled(sink);
        }

        private long start() {
            return enabled ? nanoTime.getAsLong() : 0L;
        }

        private boolean isEnabled() {
            return enabled;
        }

        private long elapsed(long startNanos) {
            return enabled
                    ? Math.max(0L, nanoTime.getAsLong() - startNanos)
                    : 0L;
        }

        private void finish(ComparisonStage stage, long startNanos) {
            if (enabled) {
                publish(stage, elapsed(startNanos), false);
            }
        }

        private void publish(ComparisonStage stage, long elapsedNanos,
                boolean reusableModelCapture) {
            if (enabled) {
                ComparisonTelemetryPublisher.publishComparisonStage(
                        sink, new ComparisonStageTelemetry(stage, elapsedNanos,
                                reusableModelCapture));
            }
        }

        private void finishCancellationDrain(
                ComparisonCancellationDrainResult result, long startNanos) {
            if (enabled) {
                ComparisonTelemetryPublisher.publishCancellationDrain(
                        sink, new ComparisonCancellationDrainTelemetry(
                                result, elapsed(startNanos)));
            }
        }
    }

    private static final class ComparisonThreadFactory implements ThreadFactory {

        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable task) {
            var thread = new Thread(task,
                    "pgck-compare-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
