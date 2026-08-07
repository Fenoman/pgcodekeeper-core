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

/**
 * Optional, caller-owned sink for aggregate comparison telemetry.
 * Implementations must not retain sensitive comparison inputs or publish SQL,
 * targets, credentials, object names, paths, or exceptions. Callbacks are
 * synchronous and may be invoked concurrently from multiple comparison worker
 * lanes. Implementations must therefore be thread-safe, non-blocking, and must
 * not throw exceptions.
 */
public interface IComparisonTelemetry {

    /** Disabled default sink preserving the existing comparison behavior. */
    IComparisonTelemetry NO_OP = new IComparisonTelemetry() {
        @Override
        public boolean isEnabled() {
            return false;
        }
    };

    /**
     * Returns whether this sink currently accepts callback events.
     *
     * @return true to invoke event callbacks
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Receives aggregate telemetry for one PostgreSQL catalog reader.
     *
     * @param event reader result
     */
    default void pgCatalogReaderFinished(PgCatalogReaderCacheTelemetry event) {
        // no-op
    }

    /**
     * Receives aggregate telemetry for an entire PostgreSQL catalog cache run.
     *
     * @param event cache run result
     */
    default void pgCatalogCacheFinished(PgCatalogCacheRunTelemetry event) {
        // no-op
    }

    /**
     * Receives aggregate telemetry for PostgreSQL routine body cache activity.
     *
     * @param event routine body cache result
     */
    default void pgRoutineBodyCacheFinished(PgRoutineBodyCacheTelemetry event) {
        // no-op
    }

    /**
     * Receives the reason the PostgreSQL lane-parallel catalog readers fell
     * back to the sequential flow. Published once per load, before any reader
     * has run.
     *
     * @param reason closed-enum fallback reason
     */
    default void pgParallelCatalogFallback(
            PgParallelCatalogFallbackReason reason) {
        // no-op
    }

    /**
     * Receives a lifecycle marker for one PostgreSQL JDBC connection.
     *
     * @param event connection lifecycle marker
     */
    default void pgConnectionLifecycle(
            PgConnectionLifecycleTelemetry event) {
        // no-op
    }

    /**
     * Receives elapsed time for one completed comparison pipeline stage.
     *
     * @param event completed stage result
     */
    default void comparisonStageFinished(ComparisonStageTelemetry event) {
        // no-op
    }

    /**
     * Receives aggregate telemetry after cancelled comparison work has drained.
     *
     * @param event cancellation drain result
     */
    default void comparisonCancellationDrainFinished(
            ComparisonCancellationDrainTelemetry event) {
        // no-op
    }
}
