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
package org.pgcodekeeper.core.database.pg.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.LogCapture;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the project loader debug phase-timing lines: directory walk plus
 * parser-task enqueue versus the ANTLR drain, and the surrounding
 * structural-load / full-analyze split.
 */
class ProjectLoaderPhaseTimingTest {

    @TempDir
    private Path projectDir;

    @Test
    void projectLoaderEmitsWalkAndDrainPhases() throws Exception {
        try (LogCapture capture = LogCapture.start();
                var loader = new PhaseProbeProjectLoader(projectDir, new CoreSettings())) {
            assertNotNull(loader.loadAndAnalyze());

            assertSingleLine(capture,
                    "phase=project_walk elapsed_ms=\\d+ detail=PhaseProbeProjectLoader");
            assertSingleLine(capture,
                    "phase=project_drain elapsed_ms=\\d+ detail=PhaseProbeProjectLoader");
            assertSingleLine(capture,
                    "phase=structural_load elapsed_ms=\\d+ detail=PhaseProbeProjectLoader");
            assertSingleLine(capture,
                    "phase=full_analyze elapsed_ms=\\d+ detail=PhaseProbeProjectLoader");
        }
    }

    private static void assertSingleLine(LogCapture capture, String pattern) {
        List<String> matches = capture.messages().stream()
                .filter(message -> message.matches(pattern))
                .toList();
        assertEquals(1, matches.size(),
                () -> "expected one line matching <" + pattern
                        + ">, captured: " + capture.messages());
        assertTrue(matches.get(0).matches(pattern));
    }

    private static final class PhaseProbeProjectLoader extends PgProjectLoader {

        private PhaseProbeProjectLoader(Path dirPath, ISettings settings) {
            super(dirPath, settings);
        }
    }
}
