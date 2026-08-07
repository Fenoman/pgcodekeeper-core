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

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.utils.LogCapture;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the hash-first sub-step debug timing lines of the JDBC residual
 * routine-body resolver. Lines use fixed detail values, so concurrent test
 * classes could add more matches; only presence and format are asserted.
 */
class RoutineResolverPhaseTimingTest {

    @Test
    void resolutionEmitsMatchAndResidualPhases() throws Exception {
        var resolver = new PgJdbcRoutineResidualResolver(
                new NoopTransport(), new PgRoutineBodyBatchLimits(10, 1024L));

        try (LogCapture capture = LogCapture.start()) {
            resolver.resolve(List.of(), new NullMonitor());

            assertLinePresent(capture,
                    "phase=jdbc_reader elapsed_ms=\\d+ detail=routine_match");
            assertLinePresent(capture,
                    "phase=jdbc_reader elapsed_ms=\\d+ detail=routine_residuals");
        }
    }

    private static void assertLinePresent(LogCapture capture, String pattern) {
        List<String> matches = capture.messages().stream()
                .filter(message -> message.matches(pattern))
                .toList();
        assertFalse(matches.isEmpty(),
                () -> "expected a line matching <" + pattern
                        + ">, captured: " + capture.messages());
        assertTrue(matches.stream().allMatch(message -> message.matches(pattern)));
    }

    private static final class NoopTransport implements PgRoutineBodyResidualTransport {

        @Override
        public void fetch(Long[] orderedOids, RowConsumer rows, IMonitor monitor) {
            // nothing to fetch for an empty slot batch
        }

        @Override
        public void close() {
            // nothing to close
        }
    }
}
