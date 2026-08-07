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
package org.pgcodekeeper.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug-level elapsed-time instrumentation for the main comparison pipeline phases.
 * Produces stable, greppable log lines of the form
 * {@code phase=<name> elapsed_ms=<millis>} with an optional
 * {@code detail=<detail>} suffix.
 * <p>
 * Zero overhead when debug logging is disabled: {@link #start()} returns
 * {@code 0} without reading the clock and {@link #end(String, long)} is a no-op.
 */
public final class PhaseTimer {

    private static final Logger LOG = LoggerFactory.getLogger(PhaseTimer.class);

    /**
     * Returns whether phase timing is active, for callers that maintain
     * per-item accumulators of their own.
     *
     * @return {@code true} when debug logging is enabled
     */
    public static boolean isEnabled() {
        return LOG.isDebugEnabled();
    }

    /**
     * Starts a phase measurement.
     *
     * @return current {@link System#nanoTime()}, or {@code 0} when debug logging is disabled
     */
    public static long start() {
        return LOG.isDebugEnabled() ? System.nanoTime() : 0L;
    }

    /**
     * Logs the elapsed time of a phase started via {@link #start()}.
     *
     * @param phase      stable phase identifier
     * @param startNanos value returned by {@link #start()}
     */
    public static void end(String phase, long startNanos) {
        if (startNanos != 0L) {
            LOG.debug("phase={} elapsed_ms={}", phase, elapsedMillis(startNanos));
        }
    }

    /**
     * Logs the elapsed time of a phase started via {@link #start()} with extra context.
     *
     * @param phase      stable phase identifier
     * @param startNanos value returned by {@link #start()}
     * @param detail     extra context appended as {@code detail=<detail>}
     */
    public static void end(String phase, long startNanos, String detail) {
        if (startNanos != 0L) {
            LOG.debug("phase={} elapsed_ms={} detail={}", phase, elapsedMillis(startNanos), detail);
        }
    }

    /**
     * Returns the elapsed nanos of a measurement started via {@link #start()}
     * without logging, for phases accumulated across multiple segments.
     *
     * @param startNanos value returned by {@link #start()}
     * @return elapsed nanos, or {@code 0} when the start was suppressed
     *         because debug logging was disabled
     */
    public static long elapsed(long startNanos) {
        return startNanos == 0L ? 0L : System.nanoTime() - startNanos;
    }

    /**
     * Logs a phase whose elapsed time was accumulated externally from one or
     * more segments measured via {@link #elapsed(long)}.
     *
     * @param phase            stable phase identifier
     * @param accumulatedNanos sum of {@link #elapsed(long)} results
     * @param detail           extra context appended as {@code detail=<detail>}
     */
    public static void endAccumulated(String phase, long accumulatedNanos, String detail) {
        if (accumulatedNanos != 0L) {
            LOG.debug("phase={} elapsed_ms={} detail={}", phase,
                    accumulatedNanos / 1_000_000L, detail);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private PhaseTimer() {
        // only statics
    }
}
