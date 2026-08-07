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
package org.pgcodekeeper.core.model.graph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.EventType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.SimpleColumn;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgIndex;
import org.pgcodekeeper.core.database.pg.schema.PgRule;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.database.pg.schema.PgView;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Selected-only mode asks of every action whether its object was selected, and answers out of
 * the selection, which holds tree elements. A tree element stands for a place in the
 * comparison, not for an object of either database, so it can name a place that only one of
 * the two databases has - a child of a table, or of a schema, that the other side never had.
 * <p>
 * Asking such an element for its object in the database that does not have it used to abort
 * the whole run. These cases pin the answer it owes instead: an element whose container is not
 * in that database names nothing there, so it is not this object, and the run goes on.
 */
class OneSidedSelectionLookupTest {

    static {
        Locale.setDefault(Locale.ENGLISH);
    }

    private static final String CREATE_INDEX = "CREATE INDEX i_shared ON s.fresh";
    private static final String DROP_TABLE = "DROP TABLE s.gone";
    private static final String CREATE_RULE = "CREATE RULE r_shared";
    private static final String DROP_RULE = "DROP RULE r_shared";

    /**
     * The reported failure in miniature. A month of drift drops one table and adds another, and
     * the two carry same-named indexes - which is ordinary, because an index name only has to be
     * unique within its schema and these two never coexist. Answering "is this new index
     * selected" walks the dropped table's index element, and that element has no object in the
     * new database at all.
     */
    @Test
    void anIndexOfADroppedTableDoesNotAbortTheRunOverItsNamesake() throws Exception {
        PgDatabase oldDb = database(withTable(new PgSchema("s"), indexedTable("gone")));
        PgDatabase newDb = database(withTable(new PgSchema("s"), indexedTable("fresh")));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = diff(settings, oldDb, newDb, allChecked(settings, oldDb, newDb));

        assertTrue(script.contains(DROP_TABLE), script);
        assertTrue(script.contains(CREATE_INDEX), script);
    }

    /**
     * The same drift with only the new table selected. The dropped table's index element is out
     * of the selection now, so nothing walks it - but the answer for the new index must not
     * change, and the drop nobody selected must stay out.
     */
    @Test
    void selectingOnlyTheAddedTableScriptsItAndNothingElse() throws Exception {
        PgDatabase oldDb = database(withTable(new PgSchema("s"), indexedTable("gone")));
        PgDatabase newDb = database(withTable(new PgSchema("s"), indexedTable("fresh")));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = diff(settings, oldDb, newDb, onlyTableChecked(settings, oldDb, newDb, "fresh"));

        assertTrue(script.contains(CREATE_INDEX), script);
        assertFalse(script.contains(DROP_TABLE), script);
    }

    /**
     * Two objects can share a whole qualified name and still be different objects: an object
     * kept as a table on one side and rebuilt as a view on the other, each carrying a rule of
     * its own under the same name. Their two rules have the same name, the same parent name and
     * the same schema, so no key built out of names can tell them apart; only asking each
     * element for its object in this very database can.
     * <p>
     * Here the resolver reaches the view's rule because a selected table depends on it, and
     * nobody selected that rule. The table's rule is selected and shares its whole name path
     * with it, so it is under the same key - and it is not that object. Accepting it would put
     * into the script a statement the selection never asked for.
     */
    @Test
    void aRuleTheResolverPulledInIsNotTheSameNamedRuleOfTheTable() throws Exception {
        PgDatabase oldDb = database(withTable(new PgSchema("s"), ruledTable()));
        PgDatabase newDb = database(withTable(schemaWithRuledView("s"), table("other")));
        newDb.getStatement(new ObjectReference("s", "other", DbObjType.TABLE))
                .addDependency(new ObjectReference("s", "t", "r_shared", DbObjType.RULE));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = diff(settings, oldDb, newDb, everythingButTheViewChecked(settings, oldDb, newDb));

        assertTrue(script.contains("CREATE TABLE s.other"), script);
        assertTrue(script.contains(DROP_RULE), script);
        assertFalse(script.contains(CREATE_RULE), script);
        assertTrue(script.contains("HIDDEN: Object s.t.r_shared of type RULE"), script);
    }

    /**
     * And the same pair with everything selected, so that the rule of the view is scripted.
     * Guards the case above against a lookup that answers "not selected" to everything.
     */
    @Test
    void theRuleOfTheViewIsScriptedOnceItIsSelected() throws Exception {
        PgDatabase oldDb = database(withTable(new PgSchema("s"), ruledTable()));
        PgDatabase newDb = database(schemaWithRuledView("s"));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = diff(settings, oldDb, newDb, allChecked(settings, oldDb, newDb));

        assertTrue(script.contains(CREATE_RULE), script);
    }

    private static PgDatabase database(PgSchema... schemas) {
        PgDatabase db = new PgDatabase();
        db.setDefaultSchema(schemas[0].getName());
        for (PgSchema schema : schemas) {
            db.addChild(schema);
        }
        return db;
    }

    private static PgSchema withTable(PgSchema schema, PgSimpleTable table) {
        schema.addChild(table);
        return schema;
    }

    private static PgSchema schemaWithRuledView(String schemaName) {
        var schema = new PgSchema(schemaName);
        var view = new PgView("t");
        view.setQuery("SELECT 1", "SELECT 1");
        view.addChild(rule());
        schema.addChild(view);
        return schema;
    }

    private static PgSimpleTable ruledTable() {
        PgSimpleTable table = table("t");
        table.addChild(rule());
        return table;
    }

    private static PgRule rule() {
        var rule = new PgRule("r_shared");
        rule.setEvent(EventType.UPDATE);
        rule.setInstead(true);
        return rule;
    }

    private static PgSimpleTable indexedTable(String name) {
        PgSimpleTable table = table(name);
        var index = new PgIndex("i_shared");
        index.addColumn(new SimpleColumn("c1", "c1"));
        table.addChild(index);
        return table;
    }

    private static PgSimpleTable table(String name) {
        var table = new PgSimpleTable(name);
        var column = new PgColumn("c1");
        column.setType("integer");
        table.addColumn(column);
        return table;
    }

    private static TreeElement allChecked(CoreSettings settings, PgDatabase oldDb, PgDatabase newDb)
            throws Exception {
        TreeElement root = DiffTree.create(settings, oldDb, newDb, null);
        root.setAllChecked();
        return root;
    }

    private static TreeElement onlyTableChecked(CoreSettings settings, PgDatabase oldDb,
                                                PgDatabase newDb, String tableName) throws Exception {
        TreeElement root = allChecked(settings, oldDb, newDb);
        for (TreeElement schema : root.getChildren()) {
            for (TreeElement table : schema.getChildren()) {
                if (!tableName.equals(table.getName())) {
                    deselect(table);
                }
            }
        }
        return root;
    }

    private static TreeElement everythingButTheViewChecked(CoreSettings settings, PgDatabase oldDb,
                                                           PgDatabase newDb) throws Exception {
        TreeElement root = allChecked(settings, oldDb, newDb);
        for (TreeElement schema : root.getChildren()) {
            for (TreeElement child : schema.getChildren()) {
                if (child.getType() == DbObjType.VIEW) {
                    deselect(child);
                }
            }
        }
        return root;
    }

    private static void deselect(TreeElement element) {
        element.setSelected(false);
        for (TreeElement child : element.getChildren()) {
            deselect(child);
        }
    }

    private static String diff(CoreSettings settings, PgDatabase oldDb, PgDatabase newDb,
                               TreeElement root) throws Exception {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, settings, root);
    }
}
