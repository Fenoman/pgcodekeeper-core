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
package org.pgcodekeeper.core.database.api.loader;

/**
 * Optional loader lifecycle notified only by the coordinated comparison path.
 * Implementations may prepare a reusable result after both sides complete
 * analysis, but must not expose it before the whole comparison succeeds.
 */
public interface IComparisonAnalysisLifecycle {

    /**
     * Called after both comparison sides returned their analyzed models. This
     * is a fallible preparation step; reusable results must remain private.
     */
    void comparisonAnalysisSucceeded();

    /**
     * Commits prepared results after worker shutdown, resource close and final
     * error propagation completed successfully.
     * <p>
     * Implementations must not throw from this terminal callback.
     */
    default void comparisonSucceeded() {
        // Optional terminal commit.
    }

    /**
     * Called when any later comparison, shutdown, or close step fails.
     */
    void comparisonFailed();

    /**
     * Reports whether this side is preparing a reusable result for the current
     * comparison. Capturing costs extra work on the cold run, so the flag is
     * published with the side's stage telemetry to keep that cost visible.
     *
     * @return true while a reusable result is being prepared
     */
    default boolean isReusableModelCaptureEnabled() {
        return false;
    }
}
