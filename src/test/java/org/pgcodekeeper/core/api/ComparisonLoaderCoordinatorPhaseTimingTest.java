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

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.base.loader.AbstractLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.LogCapture;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the debug phase-timing lines of the parallel coordinator load path:
 * per-side totals plus the structural-load / full-analyze split coming from
 * {@link AbstractLoader}.
 */
class ComparisonLoaderCoordinatorPhaseTimingTest {

    @Test
    void coordinatorEmitsPerSideTotalsAndPhaseSplit() throws Exception {
        try (LogCapture capture = LogCapture.start()) {
            LoadedComparison loaded = new ComparisonLoaderCoordinator().load(
                    new ComparisonLoaderFactories(PhaseProbeLoader::new, PhaseProbeLoader::new),
                    new CoreSettings(), ComparisonDepth.FULL);

            assertNotNull(loaded.oldDatabase());
            assertNotSame(loaded.oldDatabase(), loaded.newDatabase());

            assertSingleLine(capture,
                    "phase=load_old_db elapsed_ms=\\d+ detail=PhaseProbeLoader");
            assertSingleLine(capture,
                    "phase=load_new_db elapsed_ms=\\d+ detail=PhaseProbeLoader");
            // one structural load and one full analysis per side
            assertLineCount(capture, 2,
                    "phase=structural_load elapsed_ms=\\d+ detail=PhaseProbeLoader");
            assertLineCount(capture, 2,
                    "phase=full_analyze elapsed_ms=\\d+ detail=PhaseProbeLoader");
        }
    }

    /**
     * The per-side totals belong to the operation, not to the analysis pass.
     * A structural load never analyzes, so keying the totals on "this side
     * analyzed" silently dropped {@code load_old_db} / {@code load_new_db}
     * from the one mode whose entire reason to exist is speed - the mode whose
     * timings anyone tuning it would want to read first. The accumulator kept
     * filling up either way; nothing ever emptied it.
     */
    @Test
    void aStructuralLoadStillEmitsPerSideTotals() throws Exception {
        try (LogCapture capture = LogCapture.start()) {
            LoadedComparison loaded = new ComparisonLoaderCoordinator().load(
                    new ComparisonLoaderFactories(PhaseProbeLoader::new, PhaseProbeLoader::new),
                    new CoreSettings(), ComparisonDepth.STRUCTURAL_ONLY);

            assertNotNull(loaded.oldDatabase());
            assertNotSame(loaded.oldDatabase(), loaded.newDatabase());

            assertSingleLine(capture,
                    "phase=load_old_db elapsed_ms=\\d+ detail=PhaseProbeLoader");
            assertSingleLine(capture,
                    "phase=load_new_db elapsed_ms=\\d+ detail=PhaseProbeLoader");
            // one structural load per side and, this being the whole point of
            // the depth, no analysis at all
            assertLineCount(capture, 2,
                    "phase=structural_load elapsed_ms=\\d+ detail=PhaseProbeLoader");
            assertLineCount(capture, 0,
                    "phase=full_analyze elapsed_ms=\\d+ detail=PhaseProbeLoader");
        }
    }

    private static void assertSingleLine(LogCapture capture, String pattern) {
        assertLineCount(capture, 1, pattern);
    }

    private static void assertLineCount(LogCapture capture, int expected, String pattern) {
        List<String> matches = capture.messages().stream()
                .filter(message -> message.matches(pattern))
                .toList();
        assertEquals(expected, matches.size(),
                () -> "expected " + expected + " lines matching <" + pattern
                        + ">, captured: " + capture.messages());
        assertTrue(matches.stream().allMatch(message -> message.matches(pattern)));
    }

    private static final class PhaseProbeLoader extends AbstractLoader<PgDatabase> {

        private PhaseProbeLoader(ISettings settings) {
            super(settings, "phase-probe");
        }

        @Override
        protected PgDatabase loadInternal() {
            return createDatabase();
        }

        @Override
        protected PgDatabase createDatabase() {
            return new PgDatabase(false);
        }
    }
}
