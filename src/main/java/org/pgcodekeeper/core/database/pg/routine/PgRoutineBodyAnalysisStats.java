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
package org.pgcodekeeper.core.database.pg.routine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters for deferred routine-body analysis. A body is counted
 * as parsed when its deferred launcher materializes a parse tree, as skipped
 * when a hash-first match allowed the launcher to drop the parse and
 * dependency analysis entirely, and as skipped old-side when the launcher of
 * an eligible old-side database body dropped the analysis regardless of the
 * match verdict. Divergent counters track unmatched old-side bodies whose
 * text was additionally never fetched from the server.
 */
public final class PgRoutineBodyAnalysisStats {

    private static final AtomicLong PARSED_BODIES = new AtomicLong();
    private static final AtomicLong SKIPPED_BODIES = new AtomicLong();
    private static final AtomicLong SKIPPED_BYTES = new AtomicLong();
    private static final AtomicLong SKIPPED_OLD_SIDE_BODIES = new AtomicLong();
    private static final AtomicLong SKIPPED_OLD_SIDE_BYTES = new AtomicLong();
    private static final AtomicLong DIVERGENT_UNFETCHED_BODIES = new AtomicLong();
    private static final AtomicLong DIVERGENT_UNFETCHED_BYTES = new AtomicLong();

    private PgRoutineBodyAnalysisStats() {
        // only statics
    }

    /** Records one deferred routine body parsed for analysis. */
    public static void recordParsed() {
        PARSED_BODIES.incrementAndGet();
    }

    /**
     * Records one matched routine body whose analysis was skipped.
     *
     * @param estimatedUtf8Bytes predicted UTF-8 size of the skipped parse input
     */
    public static void recordSkipped(long estimatedUtf8Bytes) {
        SKIPPED_BODIES.incrementAndGet();
        SKIPPED_BYTES.addAndGet(estimatedUtf8Bytes);
    }

    /**
     * Records one unmatched old-side routine body whose analysis was skipped.
     *
     * @param estimatedUtf8Bytes predicted UTF-8 size of the skipped parse input
     */
    public static void recordSkippedOldSide(long estimatedUtf8Bytes) {
        SKIPPED_OLD_SIDE_BODIES.incrementAndGet();
        SKIPPED_OLD_SIDE_BYTES.addAndGet(estimatedUtf8Bytes);
    }

    /**
     * Records one unmatched old-side routine body whose text was never
     * fetched and resolved to the divergent sentinel instead.
     *
     * @param predictedUtf8Bytes exact UTF-8 size of the unfetched body
     */
    public static void recordDivergentUnfetched(long predictedUtf8Bytes) {
        DIVERGENT_UNFETCHED_BODIES.incrementAndGet();
        DIVERGENT_UNFETCHED_BYTES.addAndGet(predictedUtf8Bytes);
    }

    /** Returns the total deferred routine bodies parsed in this process. */
    public static long getParsedBodies() {
        return PARSED_BODIES.get();
    }

    /** Returns the total matched routine bodies skipped in this process. */
    public static long getSkippedBodies() {
        return SKIPPED_BODIES.get();
    }

    /** Returns the total predicted UTF-8 bytes of matched skipped parse inputs. */
    public static long getSkippedBytes() {
        return SKIPPED_BYTES.get();
    }

    /** Returns the total unmatched old-side routine bodies skipped in this process. */
    public static long getSkippedOldSideBodies() {
        return SKIPPED_OLD_SIDE_BODIES.get();
    }

    /** Returns the total predicted UTF-8 bytes of old-side skipped parse inputs. */
    public static long getSkippedOldSideBytes() {
        return SKIPPED_OLD_SIDE_BYTES.get();
    }

    /** Returns the total divergent old-side bodies never fetched in this process. */
    public static long getDivergentUnfetchedBodies() {
        return DIVERGENT_UNFETCHED_BODIES.get();
    }

    /** Returns the total UTF-8 bytes of divergent bodies never fetched. */
    public static long getDivergentUnfetchedBytes() {
        return DIVERGENT_UNFETCHED_BYTES.get();
    }

    /** Resets all counters; intended for tests. */
    public static void reset() {
        PARSED_BODIES.set(0);
        SKIPPED_BODIES.set(0);
        SKIPPED_BYTES.set(0);
        SKIPPED_OLD_SIDE_BODIES.set(0);
        SKIPPED_OLD_SIDE_BYTES.set(0);
        DIVERGENT_UNFETCHED_BODIES.set(0);
        DIVERGENT_UNFETCHED_BYTES.set(0);
    }
}
