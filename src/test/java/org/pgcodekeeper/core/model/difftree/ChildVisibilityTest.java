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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Whoever renders the children of a container reads them from the loaded model,
 * where a hidden child is still present and has no tree node to be recognised
 * by. {@link ChildVisibility} is the only thing standing between such a reader
 * and the ignore list, so it must answer exactly like the tree passes do -
 * proven here against {@link TreeFlattener}, the pass that decides which objects
 * are offered for selection.
 */
class ChildVisibilityTest {

    private static final String ORIGINAL = "hidden_trigger_original.sql";
    private static final String NEW = "hidden_trigger_new.sql";

    private static final String HIDDEN_TRIGGERS = "hidden_trigger.pgcodekeeperignore";
    private static final String QUALIFIED_TRIGGER = "qualified_trigger.pgcodekeeperignore";
    private static final String REGEX_TRIGGERS = "regex_triggers.pgcodekeeperignore";
    private static final String HIDDEN_SCHEMA_CONTENT = "hidden_schema_content.pgcodekeeperignore";
    private static final String VISIBLE_TABLE_CONTENT = "visible_table_content.pgcodekeeperignore";
    private static final String WHITELIST = "whitelist.pgcodekeeperignore";
    private static final String DB_SCOPED = "db_scoped.pgcodekeeperignore";
    private static final String HIDE_EVERYTHING = "hide_everything.pgcodekeeperignore";

    private static final List<String> BOTH_TRIGGERS = List.of("t_audit_insert", "t_audit_update");
    private static final String PRODUCTION = "production";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    private TreeElement root;
    private TreeElement container;
    private IStatement table;

    /**
     * The tree as it looks before anything is hidden: the container of a rendered
     * object is always a node that survived hiding, and its children are the
     * question.
     */
    @BeforeEach
    void loadFixture() throws IOException, InterruptedException {
        LoadedComparison loaded = load();
        root = DiffTree.create(loaded.comparisonSettings().copy(), loaded.oldDatabase(), loaded.newDatabase());

        TreeElement schema = root.getChild("public", DbObjType.SCHEMA);
        assertNotNull(schema, "fixture must offer the schema");
        container = schema.getChild("audited", DbObjType.TABLE);
        assertNotNull(container, "fixture must offer the table the hidden children hang on");

        table = loaded.oldDatabase().getStatement(new ObjectReference("public", "audited", DbObjType.TABLE));
        assertNotNull(table, "fixture must offer the table on the old side");
        assertEquals(BOTH_TRIGGERS, childNames(table.getChildren().map(IStatement::getName).toList()),
                "fixture must offer both children on the old side");
    }

    @Test
    void withoutAnIgnoreListEveryChildIsVisible() {
        assertEquals(BOTH_TRIGGERS, visibleChildren(null));
        assertEquals(BOTH_TRIGGERS, visibleChildren(new IgnoreList()));
    }

    @Test
    void hiddenChildrenAreInvisible() throws IOException {
        assertEquals(List.of(), visibleChildren(list(HIDDEN_TRIGGERS)));
    }

    @Test
    void aRuleForOneChildLeavesTheOtherOneVisible() throws IOException {
        assertEquals(List.of("t_audit_update"), visibleChildren(list(QUALIFIED_TRIGGER)));
    }

    /**
     * A rendered child has no tree node to take a qualified name from, so the
     * name is built for it. A {@code QUALIFIED} rule proves it is built exactly
     * as {@link TreeElement#getQualifiedName()} builds it: the rule addresses the
     * trigger through its table and its schema.
     */
    @Test
    void qualifiedRulesMatchTheNameTheTreeWouldBuild() throws IOException {
        IgnoreList list = list(QUALIFIED_TRIGGER);

        IgnoredObject rule = list.getList().get(0);
        assertTrue(rule.isQualified(), "the fixture rule must be a qualified one");
        assertEquals("public.audited.t_audit_insert", rule.getName(),
                "the fixture rule must address the child through its container");

        assertFalse(visibleChildren(list).contains("t_audit_insert"));
    }

    @Test
    void regularExpressionRulesHideEveryChildTheyMatch() throws IOException {
        assertEquals(List.of(), visibleChildren(list(REGEX_TRIGGERS)));
    }

    /**
     * A {@code CONTENT} rule on an ancestor of the container decides the whole
     * subtree, so the path from the root down to the container has to be replayed
     * before a single child can be answered for.
     */
    @Test
    void contentRuleOnAnAncestorHidesEveryChild() throws IOException {
        assertEquals(List.of(), visibleChildren(list(HIDDEN_SCHEMA_CONTENT)));
    }

    /**
     * The same replay the other way round: inside a white list nothing is shown
     * by default, and a {@code SHOW CONTENT} rule on the container is the only
     * reason its children are visible at all.
     */
    @Test
    void contentRuleOnTheContainerShowsItsChildrenInsideAWhiteList() throws IOException {
        assertEquals(BOTH_TRIGGERS, visibleChildren(list(VISIBLE_TABLE_CONTENT)));
    }

    @Test
    void whiteListHidesEveryChildItDoesNotShow() throws IOException {
        assertEquals(List.of(), visibleChildren(list(WHITELIST)));
        assertEquals(List.of(), visibleChildren(list(HIDE_EVERYTHING)));
    }

    @Test
    void databaseScopedRulesAreDecidedByTheGivenNames() throws IOException {
        IgnoreList list = list(DB_SCOPED);

        assertEquals(List.of("t_audit_update"), visibleChildren(list, PRODUCTION));
        assertEquals(BOTH_TRIGGERS, visibleChildren(list, "staging"),
                "a rule scoped to another database must hide nothing");
        assertEquals(BOTH_TRIGGERS, visibleChildren(list),
                "with no database name a scoped rule matches nothing");
    }

    /**
     * The whole point of the class: one reading of the rules. Whatever the list
     * says, a child is visible exactly when the pass that offers objects for
     * selection keeps it.
     */
    @Test
    void theAnswerIsTheOneTheFlattenerGives() throws IOException {
        for (String ignoreFile : List.of(HIDDEN_TRIGGERS, QUALIFIED_TRIGGER, REGEX_TRIGGERS, HIDDEN_SCHEMA_CONTENT,
                VISIBLE_TABLE_CONTENT, WHITELIST, DB_SCOPED, HIDE_EVERYTHING)) {
            IgnoreList list = list(ignoreFile);
            assertEquals(flattenedChildren(list), visibleChildren(list, PRODUCTION), ignoreFile);
        }
    }

    /**
     * A child that does have a tree node must be answered for identically,
     * whether it is offered as that node or as the loaded object behind it.
     */
    @Test
    void treeNodesAndLoadedChildrenGetTheSameAnswer() throws IOException {
        for (String ignoreFile : List.of(HIDDEN_TRIGGERS, QUALIFIED_TRIGGER, VISIBLE_TABLE_CONTENT, DB_SCOPED)) {
            ChildVisibility visibility = ChildVisibility.of(list(ignoreFile), container, PRODUCTION);
            for (TreeElement child : container.getChildren()) {
                assertEquals(visibility.isVisible(child), visibility.isVisible(statementOf(child)),
                        ignoreFile + ' ' + child.getName());
            }
        }
    }

    /**
     * Without a container there is no path to replay and no qualified name to
     * build, so the rules cannot be resolved at all and nothing may be hidden.
     */
    @Test
    void withoutAContainerNothingIsHidden() throws IOException {
        ChildVisibility visibility = ChildVisibility.of(list(HIDDEN_TRIGGERS), null);

        assertTrue(visibility.isVisible(container.getChild("t_audit_insert", DbObjType.TRIGGER)));
        assertTrue(visibility.isVisible(statementOf(container.getChild("t_audit_insert", DbObjType.TRIGGER))));
    }

    /**
     * Names of the children of {@code public.audited} the list keeps.
     */
    private List<String> visibleChildren(IgnoreList ignoreList, String... dbNames) {
        ChildVisibility visibility = ChildVisibility.of(ignoreList, container, dbNames);
        return childNames(table.getChildren()
                .filter(visibility::isVisible)
                .map(IStatement::getName)
                .toList());
    }

    /**
     * The same names, taken from the pass that decides which objects are offered
     * for selection.
     */
    private List<String> flattenedChildren(IgnoreList ignoreList) {
        return childNames(new TreeFlattener().useIgnoreList(ignoreList, PRODUCTION).flatten(root).stream()
                .filter(el -> el.getParent() == container)
                .map(TreeElement::getName)
                .toList());
    }

    private IStatement statementOf(TreeElement child) {
        IStatement statement = child.getStatement(table.getDatabase());
        assertNotNull(statement, "fixture must offer the loaded object behind " + child.getName());
        return statement;
    }

    /** Neither side of the comparison promises an order, so neither is asserted. */
    private static List<String> childNames(List<String> names) {
        return names.stream().sorted().toList();
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

    private IgnoreList list(String ignoreFile) throws IOException {
        ISettings settings = new CoreSettings();
        settings.addIgnoreList(path(ignoreFile));
        return settings.getIgnoreList();
    }

    private Path path(String fileName) {
        return TestUtils.getFilePath(fileName, getClass());
    }
}
