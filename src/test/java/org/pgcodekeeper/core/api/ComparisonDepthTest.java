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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.schema.IStatement;

/**
 * A structural load stops after the first phase: the model is complete, and
 * nothing in it carries a dependency, because dependencies are what the second
 * phase produces.
 */
class ComparisonDepthTest {

    @Test
    void aStructuralLoadLeavesEveryStatementWithoutDependencies(
            @TempDir Path dir) throws Exception {
        var fixture = ComparisonDepthTestSupport.projectWithAViewOverATable(dir);

        var full = PgCodeKeeperApi.loadForComparison(
                fixture.factories(), fixture.settings(), ComparisonDepth.FULL);
        var structural = PgCodeKeeperApi.loadForComparison(
                fixture.factories(), fixture.freshSettings(),
                ComparisonDepth.STRUCTURAL_ONLY);

        assertEquals(ComparisonDepth.FULL, full.depth());
        assertEquals(ComparisonDepth.STRUCTURAL_ONLY, structural.depth());
        assertTrue(anyStatementHasDependencies(full),
                "a full load resolves the view onto its table");
        assertFalse(anyStatementHasDependencies(structural),
                "a structural load must not have run the analysis");
    }

    private static boolean anyStatementHasDependencies(LoadedComparison loaded) {
        return loaded.oldDatabase().getDescendants()
                .anyMatch(statement -> !((IStatement) statement)
                        .getDependencies().isEmpty());
    }
}
