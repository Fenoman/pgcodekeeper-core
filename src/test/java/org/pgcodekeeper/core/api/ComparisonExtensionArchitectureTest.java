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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ComparisonExtensionArchitectureTest {

    private static final List<String> FORBIDDEN_IMPLEMENTATION_PACKAGES = List.of(
            "org.pgcodekeeper.core.database.pg.",
            "org.postgresql.");

    @Test
    void genericComparisonCoordinationHasNoPostgreSqlImplementationDependencies()
            throws IOException {
        Path sources = Path.of("src/main/java/org/pgcodekeeper/core");
        var guardedFiles = new ArrayList<Path>();
        try (var apiFiles = Files.list(sources.resolve("api"));
                var loaderApiFiles = Files.list(
                        sources.resolve("database/api/loader"))) {
            apiFiles.filter(ComparisonExtensionArchitectureTest::isJava)
                    .forEach(guardedFiles::add);
            loaderApiFiles.filter(ComparisonExtensionArchitectureTest::isJava)
                    .forEach(guardedFiles::add);
        }
        guardedFiles.add(sources.resolve("utils/Utils.java"));

        var violations = new ArrayList<String>();
        for (Path file : guardedFiles) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                for (String forbidden : FORBIDDEN_IMPLEMENTATION_PACKAGES) {
                    if (line.contains(forbidden)) {
                        violations.add(file + ":" + (i + 1) + " " + line);
                    }
                }
            }
        }

        assertEquals(List.of(), violations,
                "generic comparison coordination must remain database-neutral");
    }

    private static boolean isJava(Path path) {
        return path.getFileName().toString().endsWith(".java");
    }
}
