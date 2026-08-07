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

import java.util.Objects;

/**
 * Elapsed time of one completed comparison stage.
 *
 * @param stage completed stage
 * @param elapsedNanos monotonic elapsed time in nanoseconds
 * @param reusableModelCapture true when this side was preparing a reusable
 *                             model, which deliberately trades cold-run time
 *                             for a warm reuse of the analyzed project model
 */
public record ComparisonStageTelemetry(
        ComparisonStage stage,
        long elapsedNanos,
        boolean reusableModelCapture) {

    public ComparisonStageTelemetry {
        Objects.requireNonNull(stage, "stage");
        if (elapsedNanos < 0) {
            throw new IllegalArgumentException("elapsedNanos must be non-negative");
        }
    }

    /**
     * Creates an event for a stage that captures no reusable model.
     *
     * @param stage completed stage
     * @param elapsedNanos monotonic elapsed time in nanoseconds
     */
    public ComparisonStageTelemetry(ComparisonStage stage, long elapsedNanos) {
        this(stage, elapsedNanos, false);
    }
}
