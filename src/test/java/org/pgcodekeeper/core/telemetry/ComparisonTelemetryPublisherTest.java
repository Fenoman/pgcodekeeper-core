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
package org.pgcodekeeper.core.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ComparisonTelemetryPublisherTest {

    @Test
    void noOpTelemetryIsDisabled() {
        assertFalse(IComparisonTelemetry.NO_OP.isEnabled());
        assertDoesNotThrow(() -> ComparisonTelemetryPublisher.publishCatalogReader(
                IComparisonTelemetry.NO_OP, readerEvent()));
    }

    @Test
    void publisherDoesNotLetThrowingCallbackBreakComparison() {
        IComparisonTelemetry sink = new IComparisonTelemetry() {
            @Override
            public void pgCatalogReaderFinished(PgCatalogReaderCacheTelemetry event) {
                throw new IllegalStateException("callback failure");
            }
        };

        assertDoesNotThrow(() -> ComparisonTelemetryPublisher.publishCatalogReader(sink,
                readerEvent()));
    }

    @Test
    void comparisonStagePublisherIsPublicAndIsolatesSinkFailures() {
        var event = new ComparisonStageTelemetry(
                ComparisonStage.DIFF_TREE_CREATE, 42);
        IComparisonTelemetry sink = new IComparisonTelemetry() {
            @Override
            public void comparisonStageFinished(ComparisonStageTelemetry ignored) {
                throw new IllegalStateException("callback failure");
            }
        };

        assertTrue(ComparisonTelemetryPublisher.isEnabled(sink));
        assertDoesNotThrow(() ->
                ComparisonTelemetryPublisher.publishComparisonStage(sink, event));
        assertFalse(ComparisonTelemetryPublisher.isEnabled(
                IComparisonTelemetry.NO_OP));
    }

    @Test
    void cancellationDrainPublisherIsolatesSinkFailures() {
        var event = new ComparisonCancellationDrainTelemetry(
                ComparisonCancellationDrainResult.COMPLETED, 42);
        IComparisonTelemetry sink = new IComparisonTelemetry() {
            @Override
            public void comparisonCancellationDrainFinished(
                    ComparisonCancellationDrainTelemetry ignored) {
                throw new IllegalStateException("callback failure");
            }
        };

        assertDoesNotThrow(() ->
                ComparisonTelemetryPublisher.publishCancellationDrain(
                        sink, event));
    }

    @Test
    void throwingEnabledProbeDisablesTelemetryWithoutEscaping() {
        IComparisonTelemetry sink = new IComparisonTelemetry() {
            @Override
            public boolean isEnabled() {
                throw new IllegalStateException("probe failure");
            }
        };

        assertFalse(ComparisonTelemetryPublisher.isEnabled(sink));
        assertDoesNotThrow(() -> ComparisonTelemetryPublisher.publishComparisonStage(
                sink, new ComparisonStageTelemetry(ComparisonStage.PREPARE, 0)));
    }

    @Test
    void recordsRejectInvalidValues() {
        assertThrows(NullPointerException.class, () -> new PgCatalogReaderCacheTelemetry(
                null, PgCatalogCacheMode.COLD, PgCatalogCacheBypassReason.NONE,
                0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new PgCatalogReaderCacheTelemetry(
                "reader", PgCatalogCacheMode.COLD, PgCatalogCacheBypassReason.NONE,
                -1, 0, 0, 0, 0, 0, 0, 0, 0, false, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new PgCatalogReaderCacheTelemetry(
                "reader", PgCatalogCacheMode.WARM_EXACT, PgCatalogCacheBypassReason.NONE,
                0, 0, 0, 0, 0, 0, 0, 0, 0, false, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new PgCatalogCacheRunTelemetry(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PgRoutineBodyCacheTelemetry(
                0, 0, 0, 0, 0, -1));
        assertThrows(NullPointerException.class,
                () -> new ComparisonStageTelemetry(null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonStageTelemetry(ComparisonStage.PREPARE, -1));
        assertThrows(NullPointerException.class,
                () -> new ComparisonCancellationDrainTelemetry(null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ComparisonCancellationDrainTelemetry(
                        ComparisonCancellationDrainResult.COMPLETED, -1));
    }

    @Test
    void publisherSupportsConcurrentCallbacks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        IComparisonTelemetry sink = new IComparisonTelemetry() {
            @Override
            public void pgCatalogReaderFinished(PgCatalogReaderCacheTelemetry event) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
                calls.incrementAndGet();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 8; i++) {
                executor.submit(() -> ComparisonTelemetryPublisher.publishCatalogReader(sink,
                        readerEvent()));
            }
            assertEquals(0, calls.get());
            assertTrue(started.await(10, TimeUnit.SECONDS));
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(8, calls.get());
    }

    private static PgCatalogReaderCacheTelemetry readerEvent() {
        return new PgCatalogReaderCacheTelemetry("reader", PgCatalogCacheMode.COLD,
                PgCatalogCacheBypassReason.NONE, 1, 0, 1, 1, 1, 10, 20, 30, 40,
                false, false, 50);
    }
}
