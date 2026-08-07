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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.telemetry.ComparisonStage;
import org.pgcodekeeper.core.telemetry.ComparisonStageTelemetry;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;

class PgCodeKeeperApiStageTelemetryTest {

    @Test
    void factoryCreateTreePublishesOneDatabaseTotalAndOneDiffTree() throws Exception {
        var sink = new RecordingTelemetry();
        var settings = settings(sink);

        PgCodeKeeperApi.createTree(factories(), settings);

        assertEquals(1, sink.count(ComparisonStage.DATABASE_LOAD_TOTAL));
        assertEquals(1, sink.count(ComparisonStage.DIFF_TREE_CREATE));
    }

    @Test
    void factoryDiffDoesNotDuplicateDatabaseTotalOrDiffTree() throws Exception {
        var sink = new RecordingTelemetry();
        var settings = settings(sink);

        PgCodeKeeperApi.diff(new PgDatabaseProvider(), factories(), settings);

        assertEquals(1, sink.count(ComparisonStage.DATABASE_LOAD_TOTAL));
        assertEquals(1, sink.count(ComparisonStage.DIFF_TREE_CREATE));
    }

    @Test
    void directDatabaseDiffPublishesOnlyDiffTree() throws Exception {
        var sink = new RecordingTelemetry();
        var settings = settings(sink);

        PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                new PgDatabase(false), new PgDatabase(false), settings);

        assertEquals(0, sink.count(ComparisonStage.DATABASE_LOAD_TOTAL));
        assertEquals(1, sink.count(ComparisonStage.DIFF_TREE_CREATE));
    }

    @Test
    void legacyLoaderCreateTreePublishesOneDatabaseTotalAndOneDiffTree()
            throws Exception {
        var sink = new RecordingTelemetry();
        var settings = settings(sink);

        PgCodeKeeperApi.createTree(
                new ProbeLoader(settings), new ProbeLoader(settings), settings);

        assertEquals(1, sink.count(ComparisonStage.DATABASE_LOAD_TOTAL));
        assertEquals(1, sink.count(ComparisonStage.DIFF_TREE_CREATE));
    }

    @Test
    void legacyLoaderDiffPublishesOneDatabaseTotalAndOneDiffTree()
            throws Exception {
        var sink = new RecordingTelemetry();
        var settings = settings(sink);

        PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                new ProbeLoader(settings), new ProbeLoader(settings), settings);

        assertEquals(1, sink.count(ComparisonStage.DATABASE_LOAD_TOTAL));
        assertEquals(1, sink.count(ComparisonStage.DIFF_TREE_CREATE));
    }

    @Test
    void loadedComparisonWrapperCompletesFactoryTelemetryWithoutDuplicates()
            throws Exception {
        var sink = new RecordingTelemetry();
        var settings = settings(sink);

        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                factories(), settings);
        PgCodeKeeperApi.createTree(loaded);

        assertEquals(1, sink.count(ComparisonStage.DATABASE_LOAD_TOTAL));
        assertEquals(1, sink.count(ComparisonStage.DIFF_TREE_CREATE));
    }

    @Test
    void databaseTotalUsesInjectedClock() throws Exception {
        var sink = new RecordingTelemetry();
        var settings = settings(sink);
        AtomicLong now = new AtomicLong();

        PgCodeKeeperApi.loadForComparison(
                factories(), settings, () -> now.addAndGet(25));

        assertEquals(25,
                sink.single(ComparisonStage.DATABASE_LOAD_TOTAL).elapsedNanos());
    }

    @Test
    void disabledDatabaseTotalDoesNotReadInjectedClock() throws Exception {
        LongSupplier forbiddenClock = () -> {
            throw new AssertionError("disabled telemetry must not read its clock");
        };

        PgCodeKeeperApi.loadForComparison(
                factories(), new CoreSettings(), forbiddenClock);
    }

    @Test
    void disabledDiffTreeDoesNotReadInjectedClock() throws Exception {
        LongSupplier forbiddenClock = () -> {
            throw new AssertionError("disabled telemetry must not read its clock");
        };
        var loaded = new LoadedComparison(
                new PgDatabase(false), new PgDatabase(false), new CoreSettings(),
                ComparisonDepth.FULL);

        PgCodeKeeperApi.createTree(loaded, forbiddenClock);
    }

    @Test
    void minimumNanoTimeValueIsMeasuredWithoutSentinelCollision() throws Exception {
        var sink = new RecordingTelemetry();
        var loaded = new LoadedComparison(
                new PgDatabase(false), new PgDatabase(false), settings(sink),
                ComparisonDepth.FULL);
        AtomicLong now = new AtomicLong(Long.MIN_VALUE);

        PgCodeKeeperApi.createTree(
                loaded, () -> now.getAndAdd(37));

        assertEquals(37,
                sink.single(ComparisonStage.DIFF_TREE_CREATE).elapsedNanos());
    }

    @Test
    void throwingEnabledProbeCannotReplaceLegacyLoaderIoFailure() {
        var primary = new IOException("primary");
        var settings = settings(new ThrowingEnabledTelemetry());

        IOException actual = assertThrows(IOException.class, () ->
                PgCodeKeeperApi.createTree(
                        new FailingLoader(settings, primary),
                        new ProbeLoader(settings), settings));

        assertSame(primary, actual);
    }

    @Test
    void throwingEnabledProbeCannotReplaceLegacyLoaderCancellation() {
        var primary = new InterruptedException("primary cancellation");
        var settings = settings(new ThrowingEnabledTelemetry());

        InterruptedException actual = assertThrows(InterruptedException.class, () ->
                PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        new FailingLoader(settings, primary),
                        new ProbeLoader(settings), settings));

        assertSame(primary, actual);
    }

    private static CoreSettings settings(IComparisonTelemetry sink) {
        var settings = new CoreSettings();
        settings.setComparisonTelemetry(sink);
        return settings;
    }

    private static ComparisonLoaderFactories factories() {
        return new ComparisonLoaderFactories(ProbeLoader::new, ProbeLoader::new);
    }

    private static class ProbeLoader implements ILoader {

        protected final ISettings settings;
        protected final IDatabase database = new PgDatabase(false);

        protected ProbeLoader(ISettings settings) {
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
            return "api-telemetry-probe";
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

    private static final class FailingLoader extends ProbeLoader {

        private final Throwable failure;

        private FailingLoader(ISettings settings, Throwable failure) {
            super(settings);
            this.failure = failure;
        }

        @Override
        public IDatabase loadAndAnalyze() throws IOException, InterruptedException {
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw (InterruptedException) failure;
        }
    }

    private static final class ThrowingEnabledTelemetry
            implements IComparisonTelemetry {

        @Override
        public boolean isEnabled() {
            throw new IllegalStateException("telemetry probe failure");
        }
    }

    private static final class RecordingTelemetry implements IComparisonTelemetry {

        private final List<ComparisonStageTelemetry> events =
                new CopyOnWriteArrayList<>();

        @Override
        public void comparisonStageFinished(ComparisonStageTelemetry event) {
            events.add(event);
        }

        private long count(ComparisonStage stage) {
            return events.stream().filter(event -> event.stage() == stage).count();
        }

        private ComparisonStageTelemetry single(ComparisonStage stage) {
            return events.stream()
                    .filter(event -> event.stage() == stage)
                    .findFirst()
                    .orElseThrow();
        }
    }
}
