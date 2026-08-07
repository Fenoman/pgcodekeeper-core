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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * The ignore list must hide objects in the diff tree itself, not only in the
 * flattened selection script generation works from. Otherwise the tree offers
 * objects that can never reach a migration script - the fixture here is a table
 * whose only differences are triggers the project hides on purpose.
 * <p>
 * Every hiding test is paired with a byte comparison of the generated script:
 * filtering the tree is only allowed to remove what script generation discards
 * anyway, so the script must not move by a single byte.
 */
class DiffTreeIgnoreListTest {

    private static final String ORIGINAL = "hidden_trigger_original.sql";
    private static final String NEW = "hidden_trigger_new.sql";

    private static final String HIDDEN_TRIGGERS = "hidden_trigger.pgcodekeeperignore";
    private static final String HIDDEN_SCHEMA_CONTENT = "hidden_schema_content.pgcodekeeperignore";
    private static final String WHITELIST = "whitelist.pgcodekeeperignore";
    private static final String DB_SCOPED = "db_scoped.pgcodekeeperignore";
    private static final String HIDE_EVERYTHING = "hide_everything.pgcodekeeperignore";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @Test
    void treeKeepsHiddenObjectsWithoutAnIgnoreList() throws IOException, InterruptedException {
        LoadedComparison loaded = load();

        assertEquals(List.of(
                "public|SCHEMA|BOTH",
                "public.audited|TABLE|BOTH",
                "public.audited.t_audit_insert|TRIGGER|LEFT",
                "public.audited.t_audit_update|TRIGGER|LEFT",
                "public.plain|TABLE|BOTH"),
                snapshot(createTree(loaded, plainSettings(loaded))),
                "fixture must offer the objects the ignore lists below hide");
    }

    @Test
    void hiddenObjectsAreAbsentFromTheTree() throws IOException, InterruptedException {
        LoadedComparison loaded = load();

        TreeElement tree = createTree(loaded, ignoringSettings(loaded, HIDDEN_TRIGGERS));

        // "audited" is unchanged apart from the two hidden triggers, so hiding
        // them leaves no change on it at all, see DiffTreeHiddenChildrenTest
        assertEquals(List.of(
                "public|SCHEMA|BOTH",
                "public.plain|TABLE|BOTH"),
                snapshot(tree));
    }

    @Test
    void hiddenObjectsCannotBeSelected() throws IOException, InterruptedException {
        LoadedComparison loaded = load();
        IStatement trigger = loaded.oldDatabase().getStatement(
                new ObjectReference("public", "audited", "t_audit_insert", DbObjType.TRIGGER));
        assertNotNull(trigger, "fixture must contain the trigger on the old side");

        assertNotNull(createTree(loaded, plainSettings(loaded)).findElement(trigger),
                "without an ignore list the trigger is selectable");
        assertNull(createTree(loaded, ignoringSettings(loaded, HIDDEN_TRIGGERS)).findElement(trigger),
                "a hidden object must not be reachable for selection");
    }

    @Test
    void hiddenObjectsDoNotChangeTheScript() throws IOException, InterruptedException {
        assertScriptIsByteIdentical(HIDDEN_TRIGGERS);
    }

    @Test
    void hiddenContentRemovesTheWholeSubtree() throws IOException, InterruptedException {
        LoadedComparison loaded = load();

        // HIDE CONTENT on the schema drops the schema and everything below it
        assertEquals(List.of(), snapshot(createTree(loaded, ignoringSettings(loaded, HIDDEN_SCHEMA_CONTENT))));
        assertScriptIsByteIdentical(HIDDEN_SCHEMA_CONTENT);
    }

    @Test
    void hiddenContainerOfAVisibleObjectIsKept() throws IOException, InterruptedException {
        LoadedComparison loaded = load();

        // HIDE ALL hides the schema too, but it must stay as the path to the shown table
        assertEquals(List.of(
                "public|SCHEMA|BOTH",
                "public.plain|TABLE|BOTH"),
                snapshot(createTree(loaded, ignoringSettings(loaded, WHITELIST))));
        assertScriptIsByteIdentical(WHITELIST);
    }

    @Test
    void whitelistWithoutRulesEmptiesTheTree() throws IOException, InterruptedException {
        LoadedComparison loaded = load();

        assertEquals(List.of(), snapshot(createTree(loaded, ignoringSettings(loaded, HIDE_EVERYTHING))));
        assertScriptIsByteIdentical(HIDE_EVERYTHING);
    }

    /**
     * A {@code db=} rule is decided by a database name the tree does not know, so
     * the tree must keep the object and leave the decision to the pass that knows
     * the name.
     */
    @Test
    void databaseScopedRulesHideNothingInTheTree() throws IOException, InterruptedException {
        LoadedComparison loaded = load();

        assertTrue(snapshot(createTree(loaded, ignoringSettings(loaded, DB_SCOPED)))
                .contains("public.audited.t_audit_insert|TRIGGER|LEFT"),
                "a database scoped rule must not hide the object from the tree");
        assertScriptIsByteIdentical(DB_SCOPED);
    }

    @Test
    void emptyIgnoreListLeavesTheTreeUntouched() throws IOException, InterruptedException {
        LoadedComparison loaded = load();

        assertEquals(snapshot(createTree(loaded, plainSettings(loaded))),
                snapshot(createTree(loaded, loaded.comparisonSettings().copy())));
    }

    /**
     * Compares the script built from a tree that was filtered while it was created
     * against the script built the old way: an unfiltered tree, with the very same
     * ignore list applied by the script builder alone.
     */
    private void assertScriptIsByteIdentical(String ignoreFile) throws IOException, InterruptedException {
        LoadedComparison loaded = load();
        ISettings ignoring = ignoringSettings(loaded, ignoreFile);

        byte[] fromUnfilteredTree = script(loaded, createTree(loaded, plainSettings(loaded)), ignoring);
        byte[] fromFilteredTree = script(loaded, createTree(loaded, ignoring), ignoring);

        assertArrayEquals(fromUnfilteredTree, fromFilteredTree,
                "filtering the tree must not move the script by a single byte");
    }

    private byte[] script(LoadedComparison loaded, TreeElement tree, ISettings settings) throws IOException {
        tree.setAllChecked();
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings, tree)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static TreeElement createTree(LoadedComparison loaded, ISettings settings) throws InterruptedException {
        return DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());
    }

    private LoadedComparison load() throws IOException, InterruptedException {
        var settings = new CoreSettings();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> provider.getDumpLoader(path(ORIGINAL), sideSettings),
                        sideSettings -> provider.getDumpLoader(path(NEW), sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        return loaded;
    }

    private static ISettings plainSettings(LoadedComparison loaded) {
        return loaded.comparisonSettings().copy();
    }

    private ISettings ignoringSettings(LoadedComparison loaded, String ignoreFile) throws IOException {
        ISettings settings = loaded.comparisonSettings().copy();
        settings.addIgnoreList(path(ignoreFile));
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
