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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.model.difftree.TreeElement;

/**
 * The claim the whole "structural only" mode rests on: a diff tree built from
 * structurally loaded models equals the one built from analyzed models, down
 * to every node, its side and its state.
 * <p>
 * This is deliberately not a unit test of one function - it drives the public
 * {@code loadForComparison}/{@code createTree} pipeline twice, once per depth,
 * over the very same on-disk projects, and diffs the two resulting trees node
 * by node. If a structural load ever changed what the comparison sees, this
 * is where it would show up.
 */
class StructuralComparisonParityTest {

    @Test
    void theDiffTreeIsTheSameWhicheverDepthLoadedIt(@TempDir Path dir)
            throws Exception {
        var fixture = ComparisonDepthTestSupport.projectAgainstChangedProject(dir);

        TreeElement analyzed = PgCodeKeeperApi.createTree(
                PgCodeKeeperApi.loadForComparison(fixture.factories(),
                        fixture.settings(), ComparisonDepth.FULL));
        TreeElement structural = PgCodeKeeperApi.createTree(
                PgCodeKeeperApi.loadForComparison(fixture.factories(),
                        fixture.freshSettings(), ComparisonDepth.STRUCTURAL_ONLY));

        List<String> analyzedNodes = flatten(analyzed);

        // Guards the fixture itself, not the claim under test: if it stopped
        // producing one of the four node states, the parity check below could
        // still pass vacuously - both loads would simply agree on an emptier
        // tree - and the whole test would silently stop meaning anything.
        assertTrue(analyzedNodes.stream().anyMatch(n -> n.endsWith("|TABLE|LEFT")),
                "fixture must remove a table");
        assertTrue(analyzedNodes.stream().anyMatch(n -> n.endsWith("|FUNCTION|RIGHT")),
                "fixture must add a function");
        assertTrue(analyzedNodes.stream().anyMatch(n -> n.endsWith("|TABLE|BOTH")),
                "fixture must change a table");
        assertTrue(analyzedNodes.stream().anyMatch(n -> n.endsWith("|VIEW|BOTH")),
                "fixture must change a view");
        assertTrue(analyzedNodes.contains("app|SCHEMA|BOTH"),
                "schema must be unchanged in itself yet present for its changed children");

        assertEquals(analyzedNodes, flatten(structural),
                "a structural load must not change what the comparison sees");
    }

    /**
     * Every node as its qualified name, type and side, in document order.
     * <p>
     * {@link TreeElement} has no separate "state" field: {@link
     * TreeElement#getSide()} already tells added ({@code RIGHT}) apart from
     * removed ({@code LEFT}) and from either changed or an unchanged
     * container shown for its changed children (both {@code BOTH}). Paired
     * with the qualified name and the type, it differentiates every node in
     * the fixture content-fully, so no fourth field is needed for the key.
     */
    private static List<String> flatten(TreeElement root) {
        List<String> nodes = new ArrayList<>();
        collect(root, nodes);
        return nodes;
    }

    private static void collect(TreeElement element, List<String> into) {
        into.add(element.getQualifiedName() + '|' + element.getType() + '|'
                + element.getSide());
        element.getChildren().forEach(child -> collect(child, into));
    }
}
