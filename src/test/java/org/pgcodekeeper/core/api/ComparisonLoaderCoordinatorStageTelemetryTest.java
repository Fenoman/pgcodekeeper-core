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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.telemetry.ComparisonStage;
import org.pgcodekeeper.core.telemetry.ComparisonStageTelemetry;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;

class ComparisonLoaderCoordinatorStageTelemetryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void successfulLoadPublishesEveryCoordinatorStageInLogicalOrder() throws Exception {
        var sink = new RecordingTelemetry();
        var settings = settings(sink);
        var clock = new StepClock();

        new ComparisonLoaderCoordinator(
                Executors::newSingleThreadExecutor, TIMEOUT,
                System::nanoTime, clock).load(factories(ProbeLoader::new), settings, ComparisonDepth.FULL);

        assertEquals(List.of(
                ComparisonStage.PREPARE,
                ComparisonStage.OLD_STRUCTURAL_LOAD,
                ComparisonStage.NEW_STRUCTURAL_LOAD,
                ComparisonStage.STRUCTURAL_BARRIER,
                ComparisonStage.OLD_FULL_ANALYZE,
                ComparisonStage.NEW_FULL_ANALYZE,
                ComparisonStage.ANALYSIS_BARRIER,
                ComparisonStage.LOADERS_CLOSE),
                sink.stages());
        assertTrue(sink.events.stream().allMatch(event -> event.elapsedNanos() > 0));
    }

    @Test
    void barrierElapsedIsWallTimeContainingBothConcurrentSideLoads() throws Exception {
        var sink = new RecordingTelemetry();
        var settings = settings(sink);
        var structuralGate = new CountDownLatch(2);
        var analysisGate = new CountDownLatch(2);

        new ComparisonLoaderCoordinator(
                () -> Executors.newFixedThreadPool(2), TIMEOUT,
                System::nanoTime, System::nanoTime).load(
                        factories(sideSettings -> new OverlapLoader(
                                sideSettings, structuralGate, analysisGate)),
                        settings, ComparisonDepth.FULL);

        assertBarrierContainsSides(sink, ComparisonStage.STRUCTURAL_BARRIER,
                ComparisonStage.OLD_STRUCTURAL_LOAD,
                ComparisonStage.NEW_STRUCTURAL_LOAD);
        assertBarrierContainsSides(sink, ComparisonStage.ANALYSIS_BARRIER,
                ComparisonStage.OLD_FULL_ANALYZE,
                ComparisonStage.NEW_FULL_ANALYZE);
        assertOrderedAfterBoth(sink, ComparisonStage.STRUCTURAL_BARRIER,
                ComparisonStage.OLD_STRUCTURAL_LOAD,
                ComparisonStage.NEW_STRUCTURAL_LOAD);
        assertOrderedAfterBoth(sink, ComparisonStage.ANALYSIS_BARRIER,
                ComparisonStage.OLD_FULL_ANALYZE,
                ComparisonStage.NEW_FULL_ANALYZE);
    }

    @Test
    void disabledTelemetryDoesNotReadTelemetryClock() throws Exception {
        var settings = new CoreSettings();
        LongSupplier forbiddenClock = () -> {
            throw new AssertionError("disabled telemetry must not read its clock");
        };

        new ComparisonLoaderCoordinator(
                Executors::newSingleThreadExecutor, TIMEOUT,
                System::nanoTime, forbiddenClock).load(
                        factories(ProbeLoader::new), settings, ComparisonDepth.FULL);
    }

    @Test
    void telemetryFailureCannotReplaceLoaderFailure() {
        var primary = new IOException("primary");
        var settings = settings(new ThrowingTelemetry());

        IOException actual = assertThrows(IOException.class, () ->
                new ComparisonLoaderCoordinator(
                        Executors::newSingleThreadExecutor, TIMEOUT,
                        System::nanoTime, System::nanoTime).load(
                                factories(sideSettings ->
                                        new FailingLoader(sideSettings, primary)),
                                settings, ComparisonDepth.FULL));

        assertSame(primary, actual);
    }

    @Test
    void telemetryFailureCannotReplaceCancellation() {
        var primary = new InterruptedException("primary cancellation");
        var settings = settings(new ThrowingTelemetry());

        InterruptedException actual = assertThrows(InterruptedException.class, () ->
                new ComparisonLoaderCoordinator(
                        Executors::newSingleThreadExecutor, TIMEOUT,
                        System::nanoTime, System::nanoTime).load(
                                factories(sideSettings ->
                                        new FailingLoader(sideSettings, primary)),
                                settings, ComparisonDepth.FULL));

        assertSame(primary, actual);
        // The coordinator deliberately restores the interrupt flag after cleanup.
        Thread.interrupted();
    }

    @Test
    void failedPhasePublishesOnlyStagesThatCompleted() {
        var primary = new IOException("primary");
        var sink = new RecordingTelemetry();
        var settings = settings(sink);

        assertThrows(IOException.class, () ->
                new ComparisonLoaderCoordinator(
                        Executors::newSingleThreadExecutor, TIMEOUT,
                        System::nanoTime, System::nanoTime).load(
                                factories(sideSettings ->
                                        new FailingLoader(sideSettings, primary)),
                                settings, ComparisonDepth.FULL));

        assertEquals(List.of(ComparisonStage.PREPARE), sink.stages());
    }

    @Test
    void closeStageIsPublishedOnlyAfterSuccessfulGracefulClose() {
        var primary = new IOException("close failure");
        var sink = new RecordingTelemetry();
        var settings = settings(sink);

        IOException actual = assertThrows(IOException.class, () ->
                new ComparisonLoaderCoordinator(
                        Executors::newSingleThreadExecutor, TIMEOUT,
                        System::nanoTime, System::nanoTime).load(
                                factories(sideSettings ->
                                        new CloseFailingLoader(sideSettings, primary)),
                                settings, ComparisonDepth.FULL));

        assertSame(primary, actual);
        assertEquals(0, sink.stages().stream()
                .filter(stage -> stage == ComparisonStage.LOADERS_CLOSE)
                .count());
        assertTrue(sink.stages().contains(ComparisonStage.ANALYSIS_BARRIER));
    }

    private static void assertBarrierContainsSides(RecordingTelemetry sink,
            ComparisonStage barrier, ComparisonStage oldSide, ComparisonStage newSide) {
        long barrierNanos = sink.single(barrier).elapsedNanos();
        assertTrue(barrierNanos >= sink.single(oldSide).elapsedNanos());
        assertTrue(barrierNanos >= sink.single(newSide).elapsedNanos());
    }

    private static void assertOrderedAfterBoth(RecordingTelemetry sink,
            ComparisonStage barrier, ComparisonStage oldSide, ComparisonStage newSide) {
        List<ComparisonStage> stages = sink.stages();
        int barrierIndex = stages.indexOf(barrier);
        assertTrue(barrierIndex > stages.indexOf(oldSide));
        assertTrue(barrierIndex > stages.indexOf(newSide));
    }

    private static CoreSettings settings(IComparisonTelemetry sink) {
        var settings = new CoreSettings();
        settings.setComparisonTelemetry(sink);
        return settings;
    }

    private static ComparisonLoaderFactories factories(LoaderFactory factory) {
        return new ComparisonLoaderFactories(factory::create, factory::create);
    }

    @FunctionalInterface
    private interface LoaderFactory {
        ILoader create(ISettings settings);
    }

    private static class ProbeLoader implements ILoader {

        protected final ISettings settings;
        protected final IDatabase database = new PgDatabase(false);

        private ProbeLoader(ISettings settings) {
            this.settings = settings;
        }

        @Override
        public IDatabase load() throws IOException, InterruptedException {
            return database;
        }

        @Override
        public IDatabase loadAndAnalyze() throws IOException, InterruptedException {
            return database;
        }

        @Override
        public IDatabase getDatabase() {
            return database;
        }

        @Override
        public String getDatabaseName() {
            return "telemetry-probe";
        }

        @Override
        public ISettings getSettings() {
            return settings;
        }

        @Override
        public List<Object> getErrors() {
            return List.of();
        }
    }

    private static final class OverlapLoader extends ProbeLoader {

        private final CountDownLatch structuralGate;
        private final CountDownLatch analysisGate;

        private OverlapLoader(ISettings settings, CountDownLatch structuralGate,
                CountDownLatch analysisGate) {
            super(settings);
            this.structuralGate = structuralGate;
            this.analysisGate = analysisGate;
        }

        @Override
        public IDatabase load() throws InterruptedException {
            awaitBoth(structuralGate);
            return database;
        }

        @Override
        public IDatabase loadAndAnalyze() throws InterruptedException {
            awaitBoth(analysisGate);
            return database;
        }

        private static void awaitBoth(CountDownLatch gate) throws InterruptedException {
            gate.countDown();
            assertTrue(gate.await(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private static final class FailingLoader extends ProbeLoader {

        private final Throwable failure;

        private FailingLoader(ISettings settings, Throwable failure) {
            super(settings);
            this.failure = failure;
        }

        @Override
        public IDatabase load() throws IOException, InterruptedException {
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw (InterruptedException) failure;
        }
    }

    private static final class CloseFailingLoader extends ProbeLoader {

        private final IOException failure;

        private CloseFailingLoader(ISettings settings, IOException failure) {
            super(settings);
            this.failure = failure;
        }

        @Override
        public void close() throws IOException {
            throw failure;
        }
    }

    private static final class RecordingTelemetry implements IComparisonTelemetry {

        private final List<ComparisonStageTelemetry> events =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void comparisonStageFinished(ComparisonStageTelemetry event) {
            events.add(event);
        }

        private List<ComparisonStage> stages() {
            synchronized (events) {
                return events.stream().map(ComparisonStageTelemetry::stage).toList();
            }
        }

        private ComparisonStageTelemetry single(ComparisonStage stage) {
            synchronized (events) {
                return events.stream()
                        .filter(event -> event.stage() == stage)
                        .findFirst()
                        .orElseThrow();
            }
        }
    }

    private static final class ThrowingTelemetry implements IComparisonTelemetry {

        @Override
        public void comparisonStageFinished(ComparisonStageTelemetry event) {
            throw new IllegalStateException("telemetry failure");
        }
    }

    private static final class StepClock implements LongSupplier {

        private final AtomicLong now = new AtomicLong();

        @Override
        public long getAsLong() {
            return now.addAndGet(10);
        }
    }
}
