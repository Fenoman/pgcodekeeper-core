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
package org.pgcodekeeper.core.it.difftree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.ch.ChDatabaseProvider;
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.it.IntegrationTestUtils;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Proves on the whole diff fixture corpus that hiding objects while the diff
 * tree is built produces exactly the same migration script as the previous
 * behaviour, where the tree kept everything and only script generation applied
 * the ignore list.
 * <p>
 * For every fixture pair an ignore list is derived from the objects the fixture
 * actually produces, so the rules always bite, and the script is generated twice
 * from the same models: once from an unfiltered tree and once from a tree built
 * with the same ignore list. The two outputs are compared as bytes. Fixtures
 * that make script generation fail are compared by the failure they raise.
 */
class DiffTreeIgnoreListParityTest {

    private static final String ORIGINAL = "_original.sql";
    private static final String NEW = "_new.sql";

    private static final AtomicInteger FIXTURES_MEASURED = new AtomicInteger();
    private static final AtomicInteger CONTAINERS_MEASURED = new AtomicInteger();

    static Stream<Arguments> fixtures() {
        return Stream.of(
                        fixturesOf("pg", new PgDatabaseProvider()),
                        fixturesOf("ms", new MsDatabaseProvider()),
                        fixturesOf("ch", new ChDatabaseProvider()))
                .flatMap(stream -> stream);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void treeFilteringKeepsTheScriptByteIdentical(String name, IDatabaseProvider provider, Path original, Path updated)
            throws IOException, InterruptedException {
        LoadedComparison loaded = load(provider, original, updated);

        ISettings plain = loaded.comparisonSettings().copy();
        TreeElement unfiltered = DiffTree.create(plain, loaded.oldDatabase(), loaded.newDatabase());

        List<TreeElement> objects = flatten(unfiltered);
        if (objects.isEmpty()) {
            return;
        }

        assertScriptIsByteIdentical(loaded, provider, objects, false);
        assertScriptIsByteIdentical(loaded, provider, objects, true);
    }

    /**
     * The mechanism behind the byte parity above: script generation flattens the
     * tree with the same ignore list, and that flattened selection may only lose
     * the objects that produce nothing - an object with no change of its own
     * whose every visible descendant is gone. Everything else, and the order of
     * what is left, must stay exactly as it was.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void treeFilteringOnlyDropsObjectsWithoutOwnChanges(String name, IDatabaseProvider provider,
                                                        Path original, Path updated)
            throws IOException, InterruptedException {
        LoadedComparison loaded = load(provider, original, updated);

        ISettings plain = loaded.comparisonSettings().copy();
        List<TreeElement> objects = flatten(DiffTree.create(plain, loaded.oldDatabase(), loaded.newDatabase()));
        if (objects.isEmpty()) {
            return;
        }

        for (boolean whitelist : new boolean[] { false, true }) {
            ISettings ignoring = settingsHiding(loaded, objects, whitelist);
            String mode = whitelist ? "whitelist" : "blacklist";
            List<TreeElement> unfiltered = flattenSelection(ignoring,
                    createTree(loaded, loaded.comparisonSettings().copy()));

            assertEquals(keys(withoutEmptyUnchanged(loaded, ignoring, unfiltered)),
                    keys(flattenSelection(ignoring, createTree(loaded, ignoring))),
                    "flattened selection must only lose objects that produce nothing, " + mode);
        }
    }

    /**
     * The scenario this filtering exists for, played on the whole corpus: every
     * container that has no change of its own gets all of its children hidden,
     * which leaves it without a single difference that can reach a script. It
     * must be gone from the tree, and the script must not move by a byte.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void containersLeftWithOnlyHiddenChildrenAreDropped(String name, IDatabaseProvider provider,
                                                        Path original, Path updated)
            throws IOException, InterruptedException {
        LoadedComparison loaded = load(provider, original, updated);
        FIXTURES_MEASURED.incrementAndGet();

        ISettings plain = loaded.comparisonSettings().copy();
        TreeElement unfiltered = DiffTree.create(plain, loaded.oldDatabase(), loaded.newDatabase());
        List<TreeElement> containers = containersWithoutOwnChanges(loaded, plain, unfiltered);
        if (containers.isEmpty()) {
            return;
        }
        CONTAINERS_MEASURED.addAndGet(containers.size());

        ISettings ignoring = settingsHidingChildrenOf(loaded, containers);
        List<String> filtered = keys(flatten(createTree(loaded, ignoring)));
        for (TreeElement container : containers) {
            assertFalse(filtered.contains(key(container)),
                    "a container whose every child is hidden has no change left: " + key(container));
        }

        Object fromUnfilteredTree = script(loaded, provider, ignoring, createTree(loaded, plain));
        Object fromFilteredTree = script(loaded, provider, ignoring, createTree(loaded, ignoring));
        if (fromUnfilteredTree instanceof byte[] expected && fromFilteredTree instanceof byte[] actual) {
            assertArrayEquals(expected, actual, "dropping the container must not move the script");
        } else {
            assertEquals(fromUnfilteredTree, fromFilteredTree, "both paths must fail the same way");
        }
    }

    /**
     * The corpus must really contain the scenario above, otherwise the test
     * proves nothing. Only checked after a full run, so that running a single
     * fixture stays possible.
     */
    @AfterAll
    static void theCorpusExercisesTheDroppedContainers() {
        if (FIXTURES_MEASURED.get() < fixtures().count()) {
            return;
        }
        assertTrue(CONTAINERS_MEASURED.get() > 100,
                "the corpus must offer containers without own changes, found " + CONTAINERS_MEASURED.get());
    }

    /**
     * The derived rules must really hide something, otherwise the parity above
     * would be vacuous.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void derivedRulesActuallyHideObjects(String name, IDatabaseProvider provider, Path original, Path updated)
            throws IOException, InterruptedException {
        LoadedComparison loaded = load(provider, original, updated);

        ISettings plain = loaded.comparisonSettings().copy();
        List<TreeElement> objects = flatten(DiffTree.create(plain, loaded.oldDatabase(), loaded.newDatabase()));
        if (objects.isEmpty()) {
            return;
        }

        ISettings hiding = settingsHiding(loaded, objects, false);
        List<TreeElement> kept = flatten(
                DiffTree.create(hiding, loaded.oldDatabase(), loaded.newDatabase()));

        assertTrue(kept.size() < objects.size(),
                "derived blacklist must remove objects from the tree: " + objects.size() + " kept " + kept.size());
    }

    private static List<TreeElement> flattenSelection(ISettings settings, TreeElement tree) {
        tree.setAllChecked();
        return new TreeFlattener()
                .onlySelected()
                .useIgnoreList(settings.getIgnoreList())
                .onlyTypes(settings.getAllowedTypes())
                .flatten(tree);
    }

    /**
     * Removes from a selection built out of an unfiltered tree exactly what
     * filtering the tree is allowed to remove: an object with no change of its
     * own and no descendant left in the selection. The flattener returns children
     * before their parents, so one pass in that order decides every element.
     */
    private static List<TreeElement> withoutEmptyUnchanged(LoadedComparison loaded, ISettings settings,
                                                           List<TreeElement> selection) {
        Set<TreeElement> hasLiveDescendant = Collections.newSetFromMap(new IdentityHashMap<>());
        List<TreeElement> kept = new ArrayList<>();
        for (TreeElement el : selection) {
            if (!hasLiveDescendant.contains(el) && hasNoOwnChange(loaded, settings, el)) {
                continue;
            }
            kept.add(el);
            for (TreeElement parent = el.getParent(); parent != null; parent = parent.getParent()) {
                hasLiveDescendant.add(parent);
            }
        }
        return kept;
    }

    private static boolean hasNoOwnChange(LoadedComparison loaded, ISettings settings, TreeElement el) {
        if (el.getSide() != DiffSide.BOTH) {
            return false;
        }
        IStatement oldStatement = el.getStatement(loaded.oldDatabase());
        IStatement newStatement = el.getStatement(loaded.newDatabase());
        return oldStatement != null && newStatement != null
                && Comparison.differsInChildrenOnly(settings, oldStatement, newStatement);
    }

    /**
     * @return every container of the tree that is there only because some of its
     * children differ
     */
    private static List<TreeElement> containersWithoutOwnChanges(LoadedComparison loaded, ISettings settings,
                                                                 TreeElement root) {
        return flatten(root).stream()
                .filter(TreeElement::hasChildren)
                .filter(el -> hasNoOwnChange(loaded, settings, el))
                .toList();
    }

    /**
     * Hides the whole subtree of every child of the given containers, so that
     * nothing of them can reach a script.
     */
    private static ISettings settingsHidingChildrenOf(LoadedComparison loaded, List<TreeElement> containers) {
        ISettings settings = loaded.comparisonSettings().copy();
        IgnoreList rules = settings.getIgnoreList();
        for (TreeElement container : containers) {
            for (TreeElement child : container.getChildren()) {
                rules.add(new IgnoredObject(child.getQualifiedName(), null, false, false, true, true,
                        EnumSet.of(child.getType())));
            }
        }
        return settings;
    }

    private static List<String> keys(List<TreeElement> elements) {
        return elements.stream().map(DiffTreeIgnoreListParityTest::key).toList();
    }

    private static String key(TreeElement el) {
        return el.getQualifiedName() + '|' + el.getType() + '|' + el.getSide();
    }

    private void assertScriptIsByteIdentical(LoadedComparison loaded, IDatabaseProvider provider,
                                             List<TreeElement> objects, boolean whitelist) throws IOException {
        ISettings ignoring = settingsHiding(loaded, objects, whitelist);
        ISettings plain = loaded.comparisonSettings().copy();

        String mode = whitelist ? "whitelist" : "blacklist";
        Object fromUnfilteredTree = script(loaded, provider, ignoring,
                createTree(loaded, plain));
        Object fromFilteredTree = script(loaded, provider, ignoring,
                createTree(loaded, ignoring));

        if (fromUnfilteredTree instanceof byte[] expected && fromFilteredTree instanceof byte[] actual) {
            assertArrayEquals(expected, actual, "script must not move by a single byte, " + mode);
        } else {
            assertEquals(fromUnfilteredTree, fromFilteredTree, "both paths must fail the same way, " + mode);
        }
    }

    private static TreeElement createTree(LoadedComparison loaded, ISettings settings) {
        try {
            return DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return the script bytes, or the description of the failure both paths must
     * share
     */
    private Object script(LoadedComparison loaded, IDatabaseProvider provider, ISettings settings, TreeElement tree)
            throws IOException {
        tree.setAllChecked();
        try {
            return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings, tree)
                    .getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    /**
     * Derives an ignore list from the objects a fixture produces: every third
     * object is named by a rule, cycling through plain, content wide and qualified
     * rules so that all four add statuses are exercised.
     *
     * @param whitelist when true the list hides everything and the derived rules
     *                  show objects instead of hiding them
     */
    private static ISettings settingsHiding(LoadedComparison loaded, List<TreeElement> objects, boolean whitelist) {
        ISettings settings = loaded.comparisonSettings().copy();
        IgnoreList rules = settings.getIgnoreList();
        rules.setShow(!whitelist);

        for (int i = 0; i < objects.size(); i++) {
            if (i % 3 != 0) {
                continue;
            }
            TreeElement el = objects.get(i);
            boolean content = i % 2 == 0;
            boolean qualified = i % 6 == 0;
            rules.add(new IgnoredObject(qualified ? el.getQualifiedName() : el.getName(), null,
                    whitelist, false, content, qualified, EnumSet.of(el.getType())));
        }
        return settings;
    }

    private LoadedComparison load(IDatabaseProvider provider, Path original, Path updated)
            throws IOException, InterruptedException {
        return PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        settings -> provider.getDumpLoader(original, settings),
                        settings -> provider.getDumpLoader(updated, settings)),
                new CoreSettings());
    }

    private static List<TreeElement> flatten(TreeElement root) {
        List<TreeElement> objects = new ArrayList<>();
        collect(root, objects);
        return objects;
    }

    private static void collect(TreeElement el, List<TreeElement> objects) {
        if (el.getType() != DbObjType.DATABASE) {
            objects.add(el);
        }
        for (TreeElement child : el.getChildren()) {
            collect(child, objects);
        }
    }

    private static Stream<Arguments> fixturesOf(String dialect, IDatabaseProvider provider) {
        Path dir = TestUtils.getFilePath(IntegrationTestUtils.RESOURCE_DUMP, IntegrationTestUtils.class)
                .getParent().resolve("diff").resolve(dialect);
        Assertions.assertTrue(Files.isDirectory(dir), "fixture directory not found: " + dir);

        try (Stream<Path> files = Files.list(dir)) {
            return files.map(Path::getFileName)
                    .map(Path::toString)
                    .filter(fileName -> fileName.endsWith(ORIGINAL))
                    .map(fileName -> fileName.substring(0, fileName.length() - ORIGINAL.length()))
                    .sorted()
                    .filter(name -> Files.exists(dir.resolve(name + NEW)))
                    .map(name -> Arguments.of(dialect + '/' + name, provider,
                            dir.resolve(name + ORIGINAL), dir.resolve(name + NEW)))
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
