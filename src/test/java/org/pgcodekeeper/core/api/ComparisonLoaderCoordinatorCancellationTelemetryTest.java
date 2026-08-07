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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.telemetry.ComparisonCancellationDrainResult;
import org.pgcodekeeper.core.telemetry.ComparisonCancellationDrainTelemetry;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;

class ComparisonLoaderCoordinatorCancellationTelemetryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long COMPLETED_ELAPSED_NANOS = 37;
    private static final long TIMED_OUT_ELAPSED_NANOS = 23;

    @Test
    void peerCancellationPublishesOneCompletedDrainWithExactElapsed()
            throws Exception {
        var telemetryClock = new MutableNanoClock();
        var sink = new RecordingTelemetry();
        var primary = new IOException("primary");
        var advanceOnce = new AtomicBoolean();
        Runnable onCancel = () -> {
            if (advanceOnce.compareAndSet(false, true)) {
                telemetryClock.advance(COMPLETED_ELAPSED_NANOS);
            }
        };
        var settings = settings(sink);

        IOException actual = assertThrows(IOException.class, () ->
                coordinator(Executors::newCachedThreadPool,
                        System::nanoTime, telemetryClock)
                        .load(failingFactories(primary, onCancel), settings, ComparisonDepth.FULL));

        assertSame(primary, actual);
        assertEquals(List.of(new ComparisonCancellationDrainTelemetry(
                ComparisonCancellationDrainResult.COMPLETED,
                COMPLETED_ELAPSED_NANOS)), sink.events());
    }

    @Test
    void unconfirmedTerminationPublishesOneTimedOutDrainWithExactElapsed() {
        var telemetryClock = new MutableNanoClock();
        var rejection = new RejectedExecutionException("primary");
        var executor = new NeverTerminatingExecutor(rejection);
        var timeoutClock = new TimeoutNanoClock(telemetryClock);
        var sink = new RecordingTelemetry();
        var settings = settings(sink);
        var coordinator = new ComparisonLoaderCoordinator(
                () -> executor, Duration.ofNanos(1),
                timeoutClock, telemetryClock);

        RejectedExecutionException actual = assertThrows(
                RejectedExecutionException.class,
                () -> coordinator.load(successFactories(), settings, ComparisonDepth.FULL));

        assertSame(rejection, actual);
        assertEquals(List.of(new ComparisonCancellationDrainTelemetry(
                ComparisonCancellationDrainResult.TIMED_OUT,
                TIMED_OUT_ELAPSED_NANOS)), sink.events());
    }

    @Test
    void throwingCancellationCallbackCannotReplacePrimaryFailure()
            throws Exception {
        var primary = new IOException("primary");
        var settings = settings(new IComparisonTelemetry() {
            @Override
            public void comparisonCancellationDrainFinished(
                    ComparisonCancellationDrainTelemetry event) {
                throw new IllegalStateException("telemetry callback failure");
            }
        });

        IOException actual = assertThrows(IOException.class, () ->
                coordinator(Executors::newCachedThreadPool,
                        System::nanoTime, System::nanoTime)
                        .load(failingFactories(primary, () -> { }), settings, ComparisonDepth.FULL));

        assertSame(primary, actual);
    }

    @Test
    void successfulComparisonDoesNotPublishCancellationDrain()
            throws Exception {
        var sink = new RecordingTelemetry();
        var settings = settings(sink);

        coordinator(Executors::newCachedThreadPool,
                System::nanoTime, System::nanoTime)
                .load(successFactories(), settings, ComparisonDepth.FULL);

        assertEquals(List.of(), sink.events());
    }

    @Test
    void disabledTelemetryDoesNotReadClockDuringFailureCleanup()
            throws Exception {
        var primary = new IOException("primary");
        LongSupplier forbiddenClock = () -> {
            throw new AssertionError(
                    "disabled cancellation telemetry must not read its clock");
        };

        IOException actual = assertThrows(IOException.class, () ->
                coordinator(Executors::newCachedThreadPool,
                        System::nanoTime, forbiddenClock)
                        .load(failingFactories(primary, () -> { }),
                                new CoreSettings(), ComparisonDepth.FULL));

        assertSame(primary, actual);
    }

    private static ComparisonLoaderCoordinator coordinator(
            java.util.function.Supplier<ExecutorService> executorFactory,
            LongSupplier terminationClock, LongSupplier telemetryClock) {
        return new ComparisonLoaderCoordinator(
                executorFactory, TIMEOUT, terminationClock, telemetryClock);
    }

    private static CoreSettings settings(IComparisonTelemetry sink) {
        var settings = new CoreSettings();
        settings.setComparisonTelemetry(sink);
        return settings;
    }

    private static ComparisonLoaderFactories failingFactories(
            IOException primary, Runnable onCancel) {
        var bothStarted = new CountDownLatch(2);
        var peerRelease = new CountDownLatch(1);
        var nextSide = new AtomicInteger();
        return factories(settings -> new ProbeLoader(
                settings,
                nextSide.getAndIncrement() == 0 ? primary : null,
                bothStarted, peerRelease, onCancel));
    }

    private static ComparisonLoaderFactories successFactories() {
        return factories(settings -> new ProbeLoader(
                settings, null, new CountDownLatch(0),
                new CountDownLatch(0), () -> { }));
    }

    private static ComparisonLoaderFactories factories(LoaderFactory factory) {
        return new ComparisonLoaderFactories(factory::create, factory::create);
    }

    @FunctionalInterface
    private interface LoaderFactory {
        ILoader create(ISettings settings);
    }

    private static final class ProbeLoader implements ILoader {

        private final ISettings settings;
        private final IOException failure;
        private final CountDownLatch bothStarted;
        private final CountDownLatch peerRelease;
        private final Runnable onCancel;
        private final IDatabase database = new PgDatabase(false);

        private ProbeLoader(ISettings settings, IOException failure,
                CountDownLatch bothStarted, CountDownLatch peerRelease,
                Runnable onCancel) {
            this.settings = settings;
            this.failure = failure;
            this.bothStarted = bothStarted;
            this.peerRelease = peerRelease;
            this.onCancel = onCancel;
        }

        @Override
        public IDatabase load() throws IOException, InterruptedException {
            bothStarted.countDown();
            if (!bothStarted.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("comparison sides did not start");
            }
            if (failure != null) {
                throw failure;
            }
            peerRelease.await();
            return database;
        }

        @Override
        public IDatabase loadAndAnalyze() {
            return database;
        }

        @Override
        public void cancel() {
            onCancel.run();
            peerRelease.countDown();
        }

        @Override
        public IDatabase getDatabase() {
            return database;
        }

        @Override
        public String getDatabaseName() {
            return "not-for-telemetry";
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

    private static final class RecordingTelemetry
            implements IComparisonTelemetry {

        private final List<ComparisonCancellationDrainTelemetry> events =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void comparisonCancellationDrainFinished(
                ComparisonCancellationDrainTelemetry event) {
            events.add(event);
        }

        private List<ComparisonCancellationDrainTelemetry> events() {
            synchronized (events) {
                return List.copyOf(events);
            }
        }
    }

    private static final class MutableNanoClock implements LongSupplier {

        private long now;

        @Override
        public synchronized long getAsLong() {
            return now;
        }

        private synchronized void advance(long nanos) {
            now += nanos;
        }
    }

    private static final class TimeoutNanoClock implements LongSupplier {

        private final MutableNanoClock telemetryClock;
        private final AtomicInteger reads = new AtomicInteger();

        private TimeoutNanoClock(MutableNanoClock telemetryClock) {
            this.telemetryClock = telemetryClock;
        }

        @Override
        public long getAsLong() {
            if (reads.incrementAndGet() == 1) {
                return 0;
            }
            telemetryClock.advance(TIMED_OUT_ELAPSED_NANOS);
            return 2;
        }
    }

    private static final class NeverTerminatingExecutor
            extends AbstractExecutorService {

        private final RejectedExecutionException rejection;
        private volatile boolean shutdown;

        private NeverTerminatingExecutor(
                RejectedExecutionException rejection) {
            this.rejection = rejection;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public void execute(Runnable command) {
            throw rejection;
        }
    }
}
