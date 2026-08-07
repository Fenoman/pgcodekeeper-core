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

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Safely delivers optional comparison telemetry without affecting comparison work. */
public final class ComparisonTelemetryPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(ComparisonTelemetryPublisher.class);

    private ComparisonTelemetryPublisher() {
    }

    /** Publishes one PostgreSQL catalog reader event. */
    public static void publishCatalogReader(IComparisonTelemetry telemetry,
            PgCatalogReaderCacheTelemetry event) {
        publish(telemetry, telemetrySink -> telemetrySink.pgCatalogReaderFinished(event));
    }

    /** Publishes one PostgreSQL catalog cache run event. */
    public static void publishCatalogRun(IComparisonTelemetry telemetry,
            PgCatalogCacheRunTelemetry event) {
        publish(telemetry, telemetrySink -> telemetrySink.pgCatalogCacheFinished(event));
    }

    /** Publishes one PostgreSQL routine body cache event. */
    public static void publishRoutineBody(IComparisonTelemetry telemetry,
            PgRoutineBodyCacheTelemetry event) {
        publish(telemetry, telemetrySink -> telemetrySink.pgRoutineBodyCacheFinished(event));
    }

    /** Publishes one PostgreSQL parallel catalog reader fallback reason. */
    public static void publishPgParallelCatalogFallback(
            IComparisonTelemetry telemetry,
            PgParallelCatalogFallbackReason reason) {
        publish(telemetry,
                telemetrySink -> telemetrySink.pgParallelCatalogFallback(reason));
    }

    /** Publishes one PostgreSQL connection lifecycle marker. */
    public static void publishPgConnection(IComparisonTelemetry telemetry,
            PgConnectionLifecycleTelemetry event) {
        publish(telemetry,
                telemetrySink -> telemetrySink.pgConnectionLifecycle(event));
    }

    /**
     * Publishes one completed comparison stage. This method is public so callers
     * that own a stage outside Core, such as an IDE UI handoff, can use the same
     * exception-isolated sink.
     */
    public static void publishComparisonStage(IComparisonTelemetry telemetry,
            ComparisonStageTelemetry event) {
        publish(telemetry, telemetrySink -> telemetrySink.comparisonStageFinished(event));
    }

    /** Publishes one completed cancellation drain. */
    public static void publishCancellationDrain(IComparisonTelemetry telemetry,
            ComparisonCancellationDrainTelemetry event) {
        publish(telemetry,
                telemetrySink -> telemetrySink.comparisonCancellationDrainFinished(event));
    }

    /**
     * Safely checks whether telemetry is enabled. Callers may use this as a
     * cheap guard before reading a clock or allocating an event.
     *
     * @param telemetry optional telemetry sink
     * @return true only when the sink accepts events
     */
    public static boolean isEnabled(IComparisonTelemetry telemetry) {
        if (telemetry == null) {
            return false;
        }
        try {
            return telemetry.isEnabled();
        } catch (RuntimeException ex) {
            LOG.debug("Comparison telemetry enabled probe failed");
            return false;
        }
    }

    private static void publish(IComparisonTelemetry telemetry,
            Consumer<IComparisonTelemetry> callback) {
        try {
            if (isEnabled(telemetry)) {
                callback.accept(telemetry);
            }
        } catch (RuntimeException ex) {
            LOG.debug("Comparison telemetry callback failed");
        }
    }
}
