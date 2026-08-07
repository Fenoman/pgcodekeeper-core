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
package org.pgcodekeeper.core.model.difftree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.ch.ChDatabaseProvider;
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * An object whose only differences are children the ignore list hides is not a
 * change: it produces no statement of its own and its differing children never
 * reach the script. Keeping it in the tree makes the UI report a change that
 * the migration script cannot contain - the observed symptom is a table shown
 * as modified while the generated script is empty.
 * <p>
 * The tests below pin both halves of the contract: such a container leaves the
 * tree, and a container that has a change of its own, or a differing child that
 * stays visible, remains. Every case is paired with a byte comparison of the
 * generated script against the script built the old way - from a tree that was
 * never filtered - so no removal is allowed to move the script.
 */
class DiffTreeHiddenChildrenTest {

    private static final String ORIGINAL = "hidden_children_original.sql";
    private static final String NEW = "hidden_children_new.sql";
    private static final String MS_ORIGINAL = "hidden_children_ms_original.sql";
    private static final String MS_NEW = "hidden_children_ms_new.sql";
    private static final String CH_ORIGINAL = "hidden_children_ch_original.sql";
    private static final String CH_NEW = "hidden_children_ch_new.sql";

    private static final String HIDDEN_CHILDREN = "hidden_children.pgcodekeeperignore";
    private static final String HIDDEN_CHILDREN_CH = "hidden_children_ch.pgcodekeeperignore";
    private static final String HIDDEN_CONTAINER = "hidden_container_visible_child.pgcodekeeperignore";

    private final PgDatabaseProvider pg = new PgDatabaseProvider();

    @Test
    void treeKeepsEverythingWithoutAnIgnoreList() throws IOException, InterruptedException {
        LoadedComparison loaded = load(pg, ORIGINAL, NEW);

        assertEquals(List.of(
                "public|SCHEMA|BOTH",
                "public.only_hidden|TABLE|BOTH",
                "public.only_hidden.t_hidden|TRIGGER|LEFT",
                "public.own_change|TABLE|BOTH",
                "public.own_change.t_hidden|TRIGGER|LEFT",
                "public.visible_child|TABLE|BOTH",
                "public.visible_child.i_visible|INDEX|LEFT",
                "public.visible_child.t_hidden|TRIGGER|LEFT",
                "public.reordered|TABLE|BOTH",
                "public.reordered.t_hidden|TRIGGER|LEFT"),
                snapshot(createTree(loaded, loaded.comparisonSettings().copy())),
                "fixture must offer every case the ignore list below acts on");
    }

    /**
     * {@code only_hidden} differs in the hidden trigger alone and must go;
     * {@code own_change} has a new column, {@code visible_child} keeps a visible
     * index, and {@code reordered} differs in column order while this run does
     * not ignore it - all three are real changes and must stay.
     */
    @Test
    void onlyContainersWithoutOwnChangesLeaveTheTree() throws IOException, InterruptedException {
        LoadedComparison loaded = load(pg, ORIGINAL, NEW);

        assertEquals(List.of(
                "public|SCHEMA|BOTH",
                "public.own_change|TABLE|BOTH",
                "public.visible_child|TABLE|BOTH",
                "public.visible_child.i_visible|INDEX|LEFT",
                "public.reordered|TABLE|BOTH"),
                snapshot(createTree(loaded, hiding(loaded, HIDDEN_CHILDREN))));
    }

    /**
     * The reported case: with {@code ignore column order} on, a table whose
     * columns were only reordered has no change of its own either, so hiding its
     * triggers leaves nothing behind.
     */
    @Test
    void reorderedColumnsAreNotAnOwnChangeWhenTheyAreIgnored() throws IOException, InterruptedException {
        LoadedComparison loaded = load(pg, ORIGINAL, NEW);

        assertEquals(List.of(
                "public|SCHEMA|BOTH",
                "public.own_change|TABLE|BOTH",
                "public.visible_child|TABLE|BOTH",
                "public.visible_child.i_visible|INDEX|LEFT"),
                snapshot(createTree(loaded, ignoringColumnOrder(hiding(loaded, HIDDEN_CHILDREN)))));
    }

    /**
     * A container hidden by itself keeps its place while it leads to a visible
     * child, exactly as before: dropping empty containers must not swallow the
     * path to an object that is still shown.
     */
    @Test
    void hiddenContainerOfAVisibleChildStays() throws IOException, InterruptedException {
        LoadedComparison loaded = load(pg, ORIGINAL, NEW);

        assertEquals(List.of(
                "public|SCHEMA|BOTH",
                "public.only_hidden|TABLE|BOTH",
                "public.only_hidden.t_hidden|TRIGGER|LEFT",
                "public.own_change|TABLE|BOTH",
                "public.own_change.t_hidden|TRIGGER|LEFT",
                "public.visible_child|TABLE|BOTH",
                "public.visible_child.i_visible|INDEX|LEFT",
                "public.visible_child.t_hidden|TRIGGER|LEFT",
                "public.reordered|TABLE|BOTH",
                "public.reordered.t_hidden|TRIGGER|LEFT"),
                snapshot(createTree(loaded, hiding(loaded, HIDDEN_CONTAINER))));
    }

    /**
     * The hiding pass runs here - the list has a rule - but the rule matches
     * nothing, so not a single container may be dropped. This is the guard for
     * the empty-list case: without something actually hidden the tree must look
     * exactly as it did before.
     */
    @Test
    void anIgnoreListThatHidesNothingChangesNothing() throws IOException, InterruptedException {
        LoadedComparison loaded = load(pg, ORIGINAL, NEW);
        ISettings harmless = loaded.comparisonSettings().copy();
        harmless.getIgnoreList().add(new IgnoredObject("no_such_object", false, false, false,
                EnumSet.noneOf(DbObjType.class)));

        assertEquals(snapshot(createTree(loaded, loaded.comparisonSettings().copy())),
                snapshot(createTree(loaded, harmless)),
                "rules that match nothing must leave every container in place");
    }

    @Test
    void droppedContainersDoNotChangeTheScript() throws IOException, InterruptedException {
        assertScriptIsByteIdentical(pg, ORIGINAL, NEW, HIDDEN_CHILDREN, false);
        assertScriptIsByteIdentical(pg, ORIGINAL, NEW, HIDDEN_CHILDREN, true);
        assertScriptIsByteIdentical(pg, ORIGINAL, NEW, HIDDEN_CONTAINER, false);
        assertScriptIsByteIdentical(pg, ORIGINAL, NEW, HIDDEN_CONTAINER, true);
    }

    /**
     * The rule is dialect independent: it acts on the diff tree, which every
     * dialect builds through the same comparison. MS Server keeps its table
     * children in triggers, indexes, statistics and constraints, ClickHouse in
     * indexes and constraints, and neither needs its own implementation.
     */
    @Test
    void containerWithoutOwnChangesLeavesTheTreeInMsServer() throws IOException, InterruptedException {
        IDatabaseProvider ms = new MsDatabaseProvider();
        LoadedComparison loaded = load(ms, MS_ORIGINAL, MS_NEW);

        assertEquals(List.of(
                "dbo|SCHEMA|BOTH",
                "dbo.only_hidden|TABLE|BOTH",
                "dbo.only_hidden.t_hidden|TRIGGER|LEFT"),
                snapshot(createTree(loaded, loaded.comparisonSettings().copy())),
                "fixture must offer a table whose only difference is the trigger");

        // the schema itself has no change of its own either, so hiding the
        // trigger empties the whole tree
        assertEquals(List.of(),
                snapshot(createTree(loaded, hiding(loaded, HIDDEN_CHILDREN))));
        assertScriptIsByteIdentical(ms, MS_ORIGINAL, MS_NEW, HIDDEN_CHILDREN, false);
    }

    @Test
    void containerWithoutOwnChangesLeavesTheTreeInClickHouse() throws IOException, InterruptedException {
        IDatabaseProvider ch = new ChDatabaseProvider();
        LoadedComparison loaded = load(ch, CH_ORIGINAL, CH_NEW);

        assertEquals(List.of(
                "default|SCHEMA|BOTH",
                "default.only_hidden|TABLE|BOTH",
                "default.only_hidden.t_hidden|INDEX|LEFT"),
                snapshot(createTree(loaded, loaded.comparisonSettings().copy())),
                "fixture must offer a table whose only difference is the index");

        assertEquals(List.of(),
                snapshot(createTree(loaded, hiding(loaded, HIDDEN_CHILDREN_CH))));
        assertScriptIsByteIdentical(ch, CH_ORIGINAL, CH_NEW, HIDDEN_CHILDREN_CH, false);
    }

    /**
     * Builds the script twice from the same models and the same ignore list:
     * once from a tree that was never filtered, the way script generation worked
     * before the tree learned about the ignore list, and once from the filtered
     * tree. Dropping a container is only allowed when the two agree byte for
     * byte.
     */
    private void assertScriptIsByteIdentical(IDatabaseProvider provider, String original, String updated,
                                             String ignoreFile, boolean ignoreColumnOrder)
            throws IOException, InterruptedException {
        LoadedComparison loaded = load(provider, original, updated);
        ISettings plain = loaded.comparisonSettings().copy();
        ISettings ignoring = hiding(loaded, ignoreFile);
        if (ignoreColumnOrder) {
            ignoringColumnOrder(plain);
            ignoringColumnOrder(ignoring);
        }

        byte[] fromUnfilteredTree = script(loaded, provider, createTree(loaded, plain), ignoring);
        byte[] fromFilteredTree = script(loaded, provider, createTree(loaded, ignoring), ignoring);

        assertArrayEquals(fromUnfilteredTree, fromFilteredTree,
                "dropping a container must not move the script by a single byte");
    }

    private byte[] script(LoadedComparison loaded, IDatabaseProvider provider, TreeElement tree, ISettings settings)
            throws IOException {
        tree.setAllChecked();
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings, tree)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static TreeElement createTree(LoadedComparison loaded, ISettings settings) throws InterruptedException {
        return DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());
    }

    private LoadedComparison load(IDatabaseProvider provider, String original, String updated)
            throws IOException, InterruptedException {
        var settings = new CoreSettings();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> provider.getDumpLoader(path(original), sideSettings),
                        sideSettings -> provider.getDumpLoader(path(updated), sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        return loaded;
    }

    private ISettings hiding(LoadedComparison loaded, String ignoreFile) throws IOException {
        ISettings settings = loaded.comparisonSettings().copy();
        settings.addIgnoreList(path(ignoreFile));
        return settings;
    }

    private static ISettings ignoringColumnOrder(ISettings settings) {
        ((CoreSettings) settings).setIgnoreColumnOrder(true);
        return settings;
    }

    private Path path(String fileName) {
        return TestUtils.getFilePath(fileName, getClass());
    }

    /**
     * Flattens the tree to {@code qualified name|type|side} in depth first order,
     * skipping the artificial database root.
     */
    private static List<String> snapshot(TreeElement root) {
        List<String> names = new ArrayList<>();
        collect(root, names);
        return names;
    }

    private static void collect(TreeElement el, List<String> names) {
        if (el.getType() != DbObjType.DATABASE) {
            names.add(el.getQualifiedName() + '|' + el.getType() + '|' + el.getSide());
        }
        for (TreeElement child : el.getChildren()) {
            collect(child, names);
        }
    }
}
