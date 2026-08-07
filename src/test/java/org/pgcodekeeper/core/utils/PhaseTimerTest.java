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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseTimerTest {

    @Test
    void endEmitsStableGreppableFormat() throws InterruptedException {
        try (LogCapture capture = LogCapture.start()) {
            long start = PhaseTimer.start();
            assertNotEquals(0L, start, "start must measure while a capture is active");
            Thread.sleep(2);
            PhaseTimer.end("phase_timer_test_simple", start);
            PhaseTimer.end("phase_timer_test_detailed", PhaseTimer.start(), "probe_detail");

            List<String> simple = capture.messagesContaining("phase=phase_timer_test_simple");
            assertEquals(1, simple.size());
            assertTrue(simple.get(0).matches("phase=phase_timer_test_simple elapsed_ms=\\d+"),
                    "unexpected line: " + simple.get(0));

            List<String> detailed = capture.messagesContaining("phase=phase_timer_test_detailed");
            assertEquals(1, detailed.size());
            assertTrue(detailed.get(0).matches(
                    "phase=phase_timer_test_detailed elapsed_ms=\\d+ detail=probe_detail"),
                    "unexpected line: " + detailed.get(0));
        }
    }

    @Test
    void accumulatedSegmentsProduceOneTotalLine() throws InterruptedException {
        try (LogCapture capture = LogCapture.start()) {
            long total = 0L;
            long firstSegment = PhaseTimer.start();
            Thread.sleep(2);
            total += PhaseTimer.elapsed(firstSegment);
            long secondSegment = PhaseTimer.start();
            Thread.sleep(2);
            total += PhaseTimer.elapsed(secondSegment);
            PhaseTimer.endAccumulated("phase_timer_test_accumulated", total, "probe_detail");

            List<String> lines = capture.messagesContaining("phase=phase_timer_test_accumulated");
            assertEquals(1, lines.size());
            assertTrue(lines.get(0).matches(
                    "phase=phase_timer_test_accumulated elapsed_ms=\\d+ detail=probe_detail"),
                    "unexpected line: " + lines.get(0));
        }
    }

    @Test
    void suppressedMeasurementsStaySilent() {
        try (LogCapture capture = LogCapture.start()) {
            assertEquals(0L, PhaseTimer.elapsed(0L));
            PhaseTimer.end("phase_timer_test_silent", 0L);
            PhaseTimer.end("phase_timer_test_silent", 0L, "probe_detail");
            PhaseTimer.endAccumulated("phase_timer_test_silent", 0L, "probe_detail");
            assertTrue(capture.messagesContaining("phase_timer_test_silent").isEmpty());
        }
    }
}
