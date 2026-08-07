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
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.FILES_POSTFIX;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * {@link StructuralComparisonParityTest} proved the "structural only" claim on
 * one hand-written fixture: a view over a table, one schema, nothing else.
 * That fixture cannot exercise every {@code compare()}/{@code hashCode()}
 * override in the model - the codebase itself documents at least one type
 * with a subtle one (see the {@code Comparison.java} javadoc on {@code
 * PgConstraintFk} and {@code DEFERRABLE}) - so the claim that structural and
 * full loads always build the same diff tree is only as trustworthy as the
 * variety of fixtures it has been checked against.
 * <p>
 * This test re-runs the exact same parity check - load twice, once per
 * {@link ComparisonDepth}, build a tree both times, compare the flattened
 * node lists - against every fixture pair {@code
 * org.pgcodekeeper.core.it.diff.pg.PgDiffTest#diffTest(String)} already
 * exercises. Between them those fixtures cover table partitioning, table
 * inheritance, foreign keys, generated columns, privileges and multi-schema
 * projects, which is exactly the variety the single hand-written fixture
 * could not provide.
 * <p>
 * The scenario list is obtained by reflection off {@code PgDiffTest}'s own
 * {@code @ValueSource} rather than retyped into a second literal here: a hand
 * copy would need to be kept in sync by hand forever and could drift silently
 * out of date, while reflection turns that same drift risk (someone renames
 * or removes {@code diffTest}) into this test's own {@code @MethodSource}
 * failing loudly instead of quietly covering less than it claims to.
 * <p>
 * A scenario whose fixture will not load at all - a missing pair file, a
 * syntax neither loader can parse - says nothing about structural parity
 * either way, so it is skipped rather than failed, but only when the failure
 * is depth-independent: same fixture files, same settings, only {@link
 * ComparisonDepth} differs, so a load that fails at {@link
 * ComparisonDepth#FULL} would fail identically at {@link
 * ComparisonDepth#STRUCTURAL_ONLY}. A load that succeeds at {@code FULL} but
 * fails at {@code STRUCTURAL_ONLY} is depth-dependent by construction and is
 * exactly the regression this test exists to catch, so that case fails the
 * test instead of skipping it.
 * <p>
 * {@code assertEquals(fullNodes, structuralNodes)} alone would still pass if
 * the whole corpus quietly eroded into no-op pairs - say, someone regenerates
 * a fixture and {@code _original.sql}/{@code _new.sql} end up identical -
 * because two empty trees are trivially equal to each other; nothing would
 * turn red, and the parity claim this test exists to hold would stop being
 * exercised without anyone noticing. Each scenario's own checked-in {@code
 * _diff.sql} is the corpus's own claim about which pairs are no-ops: {@code
 * PgDiffTest} already asserts that file equals the generated script for this
 * exact pair, so a non-empty {@code _diff.sql} is that fixture asserting "a
 * real difference exists here", and an empty one is asserting the opposite.
 * Checking the {@code FULL}-depth tree against that claim on every run - a
 * non-empty {@code _diff.sql} must yield more than just the root node - turns
 * corpus erosion into a failure by itself, without a hand-kept list of which
 * scenarios are expected to be trivial today. The claim is only asserted in
 * that one direction, and the corpus-wide half of the same guard lives in
 * {@link #theCorpusStillClaimsRealDifferences()}; both are explained where
 * they stand.
 */
class StructuralComparisonFixtureParityTest {

    private static final String FIXTURE_RESOURCE_ROOT = "/org/pgcodekeeper/core/it/diff/pg/";
    private static final String PG_DIFF_TEST_CLASS_NAME = "org.pgcodekeeper.core.it.diff.pg.PgDiffTest";

    /**
     * How few scenarios may still claim a real difference before the corpus is
     * treated as having eroded. A tripwire, not an expectation: 295 of the 305
     * scenarios claim one today, so this leaves ample room for fixtures to come
     * and go one at a time while still refusing a wholesale collapse.
     */
    private static final int MIN_SCENARIOS_CLAIMING_A_DIFFERENCE = 250;

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioNames")
    void structuralLoadMatchesFullLoad(String scenario) throws Exception {
        ComparisonLoaderFactories factories = fixtureFactories(scenario);

        List<String> fullNodes;
        try {
            LoadedComparison full = PgCodeKeeperApi.loadForComparison(
                    factories, new CoreSettings(), ComparisonDepth.FULL);
            fullNodes = flatten(PgCodeKeeperApi.createTree(full));
        } catch (Exception e) {
            restoreInterruptStatus(e);
            Assumptions.abort("scenario " + scenario + " could not be loaded and diffed at all"
                    + " at FULL depth (fixture/pipeline issue unrelated to structural depth),"
                    + " skipping: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        assertMatchesOwnDiffExpectation(scenario, fullNodes);

        List<String> structuralNodes;
        try {
            LoadedComparison structural = PgCodeKeeperApi.loadForComparison(
                    factories, new CoreSettings(), ComparisonDepth.STRUCTURAL_ONLY);
            structuralNodes = flatten(PgCodeKeeperApi.createTree(structural));
        } catch (Exception e) {
            restoreInterruptStatus(e);
            fail("scenario " + scenario + ": FULL depth loaded and diffed this fixture fine, but"
                    + " STRUCTURAL_ONLY threw " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " - a depth-dependent failure is exactly the divergence this test guards against");
            return;
        }

        assertEquals(fullNodes, structuralNodes, () -> "scenario " + scenario
                + ": a structural load must not change what the comparison sees");
    }

    /**
     * Guards against the parity check above passing vacuously: holds the
     * {@code FULL}-depth tree to what the scenario's own checked-in {@code
     * _diff.sql} already claims about it, so a fixture pair that erodes into a
     * no-op fails here on every run, on its own, before the {@code
     * assertEquals} above ever gets a chance to compare two trees that both
     * went quietly empty together.
     * <p>
     * Only that one direction is asserted. Its mirror image - an empty {@code
     * _diff.sql} means an empty tree - looks like the same claim read
     * backwards, and is not one: an empty script does not imply an empty diff
     * tree. A tree may perfectly well carry a node the script builder prints
     * nothing for, and the two coincide across the whole of today's corpus
     * only by accident of which fixtures happen to exist. Asserting it would
     * arm this test to go red the day someone adds a "there is a difference,
     * the script for it is empty" fixture - a failure with nothing whatsoever
     * to do with comparison depth, in the one test whose entire subject is
     * depth. The half of the erosion guard that direction was carrying is in
     * {@link #theCorpusStillClaimsRealDifferences()} instead, where a shrunken
     * corpus cannot be mistaken for a depth regression.
     *
     * @param scenario  the scenario name, for the failure message only
     * @param fullNodes the flattened {@code FULL}-depth tree for {@code
     *                  scenario}
     * @throws IOException if {@code <scenario>_diff.sql} cannot be read - this
     *                      is a hard failure, not a skip, because every one of
     *                      these scenarios already has that resource today
     *                      (it backs {@code PgDiffTest} itself); its absence
     *                      would mean something more broken than a depth
     *                      difference
     */
    private static void assertMatchesOwnDiffExpectation(String scenario, List<String> fullNodes)
            throws IOException {
        if (!expectsNonEmptyDiff(scenario)) {
            return;
        }
        assertTrue(fullNodes.size() > 1, () -> "scenario " + scenario + ": its own "
                + scenario + FILES_POSTFIX.DIFF_SQL + " is non-empty, so a real difference is"
                + " expected, but the FULL-depth tree has nothing beyond the root - this fixture"
                + " pair has eroded into a no-op and no longer exercises the parity claim this"
                + " test exists to guard");
    }

    /**
     * The half of the anti-erosion guard no per-scenario assertion can carry:
     * {@link #assertMatchesOwnDiffExpectation} only ever speaks for a scenario
     * that still claims a difference. Regenerate a fixture pair into a no-op
     * <em>and</em> its {@code _diff.sql} along with it, and every per-scenario
     * check simply goes quiet about it - the scenario stops being asserted at
     * all, and the corpus this whole test draws its authority from shrinks
     * without one line turning red anywhere.
     */
    @Test
    void theCorpusStillClaimsRealDifferences() throws Exception {
        List<String> claiming = new ArrayList<>();
        for (String scenario : scenarioNames().toList()) {
            if (expectsNonEmptyDiff(scenario)) {
                claiming.add(scenario);
            }
        }

        assertTrue(claiming.size() >= MIN_SCENARIOS_CLAIMING_A_DIFFERENCE,
                () -> "only " + claiming.size() + " fixture scenarios still claim a real difference"
                        + " in their own " + FILES_POSTFIX.DIFF_SQL + " - the parity claim this test"
                        + " holds is worth exactly as much as the corpus behind it, and that corpus"
                        + " has fallen below the " + MIN_SCENARIOS_CLAIMING_A_DIFFERENCE
                        + " this guard tolerates");
    }

    /**
     * Whether {@code scenario} itself claims a real difference exists, read
     * from the same {@code _diff.sql} resource {@code PgDiffTest} already
     * asserts the generated script against - reused here instead of a hand-kept
     * list of "these scenarios happen to be no-ops today", so this check tracks
     * the corpus automatically as fixtures are added, removed or edited.
     */
    private static boolean expectsNonEmptyDiff(String scenario) throws IOException {
        String diffResource = scenario + FILES_POSTFIX.DIFF_SQL;
        try (InputStream stream = openFixture(diffResource)) {
            if (stream == null) {
                throw new IOException(
                        "no " + diffResource + " on the classpath next to this fixture pair");
            }
            return !new String(stream.readAllBytes(), StandardCharsets.UTF_8).isBlank();
        }
    }

    /**
     * Reuses {@code PgDiffTest.diffTest}'s own {@code @ValueSource} instead of a
     * hand copy - see the class javadoc for why. A duplicate name in that source
     * list (a couple of scenarios are listed there twice) is collapsed with
     * {@code distinct()}: reasserting the identical parity claim twice for the
     * same fixture pair costs runtime without adding coverage.
     *
     * @return every distinct scenario name {@code PgDiffTest.diffTest} runs
     * @throws ReflectiveOperationException if {@code PgDiffTest.diffTest(String)}
     *                                       has been renamed, removed or stopped
     *                                       being annotated with
     *                                       {@code @ValueSource} - this test
     *                                       cannot silently cover less than it
     *                                       claims to, so it fails loudly instead
     */
    private static Stream<String> scenarioNames() throws ReflectiveOperationException {
        Method diffTest = Class.forName(PG_DIFF_TEST_CLASS_NAME)
                .getDeclaredMethod("diffTest", String.class);
        ValueSource source = diffTest.getAnnotation(ValueSource.class);
        return Arrays.stream(source.strings()).distinct();
    }

    /**
     * Wires a dump loader factory pair the same way {@code PgDiffTest} loads
     * these very fixtures via {@code IntegrationTestUtils.loadTestDump()}: same
     * provider, same {@code <scenario>_original.sql} / {@code
     * <scenario>_new.sql} naming, same plain {@link CoreSettings}. Each factory
     * lambda re-opens its resource stream on every call, so the same {@link
     * ComparisonLoaderFactories} instance can back two independent {@code
     * loadForComparison} runs, one per depth.
     */
    private ComparisonLoaderFactories fixtureFactories(String scenario) {
        String originalResource = scenario + FILES_POSTFIX.ORIGINAL_SQL;
        String newResource = scenario + FILES_POSTFIX.NEW_SQL;
        return new ComparisonLoaderFactories(
                sideSettings -> provider.getDumpLoader(
                        () -> openFixture(originalResource), originalResource, sideSettings),
                sideSettings -> provider.getDumpLoader(
                        () -> openFixture(newResource), newResource, sideSettings));
    }

    private static InputStream openFixture(String resourceFileName) {
        return StructuralComparisonFixtureParityTest.class
                .getResourceAsStream(FIXTURE_RESOURCE_ROOT + resourceFileName);
    }

    private static void restoreInterruptStatus(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Every node as its qualified name, type and side, in document order -
     * deliberately identical to {@link StructuralComparisonParityTest}'s own
     * {@code flatten}, so the two tests assert the same claim with the same key
     * instead of two keys that merely look similar.
     */
    private static List<String> flatten(TreeElement root) {
        List<String> nodes = new ArrayList<>();
        collect(root, nodes);
        return nodes;
    }

    private static void collect(TreeElement element, List<String> into) {
        into.add(element.getQualifiedName() + '|' + element.getType() + '|' + element.getSide());
        element.getChildren().forEach(child -> collect(child, into));
    }
}
