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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Pins the answers selected-only mode gives when several selected elements share a simple
 * name, which is what a lookup index over the selection has to keep right.
 * <p>
 * Two tables called {@code t} in two schemas are one name and two objects, and the answer for
 * one of them is not the answer for the other. Whether the lookup tells them apart by keying
 * the whole path of names or by going on to ask each element whether it really is this object,
 * these cases stay the measure of it. The case where the two cannot be told apart by their
 * names at all is in {@link OneSidedSelectionLookupTest}.
 */
class SelectedOnlyIndexTest {

    static {
        Locale.setDefault(Locale.ENGLISH);
    }

    private static final String ALTER_A = "ALTER TABLE a.t";
    private static final String ALTER_B = "ALTER TABLE b.t";

    /**
     * Both same-named tables are selected and both must be scripted. A lookup that stopped at
     * the first element sharing type and name would script only the one that sorts first.
     */
    @Test
    void bothSameNamedTablesOfTheSelectionAreScripted() throws Exception {
        PgDatabase oldDb = twoSchemasWithTableT(false, false);
        PgDatabase newDb = twoSchemasWithTableT(true, true);
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = diff(settings, oldDb, newDb, allChecked(settings, oldDb, newDb));

        assertTrue(script.contains(ALTER_A), script);
        assertTrue(script.contains(ALTER_B), script);
    }

    /**
     * Only one of the two same-named tables is selected. Sharing a simple name with a selected
     * element must not carry the unselected one into the script: the lookup has to go on to ask
     * each element under that name whether it is this very object.
     */
    @Test
    void sharingASimpleNameWithASelectedTableDoesNotSelectTheOtherOne() throws Exception {
        PgDatabase oldDb = twoSchemasWithTableT(false, false);
        PgDatabase newDb = twoSchemasWithTableT(true, true);
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = diff(settings, oldDb, newDb, onlySchemaChecked(settings, oldDb, newDb, "a"));

        assertTrue(script.contains(ALTER_A), script);
        assertFalse(script.contains(ALTER_B), script);
    }

    /**
     * The same, the other way round, so that the surviving table is the one that sorts last.
     */
    @Test
    void selectingOnlyTheLastOfTheSameNamedTablesScriptsThatOne() throws Exception {
        PgDatabase oldDb = twoSchemasWithTableT(false, false);
        PgDatabase newDb = twoSchemasWithTableT(true, true);
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = diff(settings, oldDb, newDb, onlySchemaChecked(settings, oldDb, newDb, "b"));

        assertFalse(script.contains(ALTER_A), script);
        assertTrue(script.contains(ALTER_B), script);
    }

    /**
     * The one case where the question has teeth: the resolver pulls in an object nobody
     * selected, because a selected object depends on it. That object shares its type and its
     * simple name with a selected one, so it lands in the same bucket of any index - and it
     * still has to stay out of the script. Finding something under the name is not the answer;
     * the answer is whether that something is this very object.
     */
    @Test
    void anUnselectedDependencySharingASimpleNameStaysOutOfTheScript() throws Exception {
        PgDatabase oldDb = new PgDatabase();
        PgDatabase newDb = twoSchemasWithTableT(false, false);
        newDb.getStatement(new ObjectReference("a", "t", DbObjType.TABLE))
                .addDependency(new ObjectReference("b", "t", DbObjType.TABLE));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = diff(settings, oldDb, newDb, onlySchemaChecked(settings, oldDb, newDb, "a"));

        assertTrue(script.contains("CREATE TABLE a.t"), script);
        assertFalse(script.contains("CREATE TABLE b.t"), script);
    }

    /**
     * Without the flag the question is never asked, and both tables are scripted whatever the
     * selection holds. Guards against an index that leaks into the default path.
     */
    @Test
    void unselectedModeScriptsBothSameNamedTables() throws Exception {
        PgDatabase oldDb = twoSchemasWithTableT(false, false);
        PgDatabase newDb = twoSchemasWithTableT(true, true);

        String script = diff(new CoreSettings(), oldDb, newDb, null);

        assertTrue(script.contains(ALTER_A), script);
        assertTrue(script.contains(ALTER_B), script);
        assertEquals(2, script.split("ALTER TABLE ", -1).length - 1, script);
    }

    private static PgDatabase twoSchemasWithTableT(boolean extraColumnInA, boolean extraColumnInB) {
        PgDatabase db = new PgDatabase();
        db.setDefaultSchema("a");
        db.addChild(schemaWithTableT("a", extraColumnInA));
        db.addChild(schemaWithTableT("b", extraColumnInB));
        return db;
    }

    private static PgSchema schemaWithTableT(String schemaName, boolean extraColumn) {
        var schema = new PgSchema(schemaName);
        var table = new PgSimpleTable("t");
        schema.addChild(table);
        table.addColumn(column("c1"));
        if (extraColumn) {
            table.addColumn(column("c2"));
        }
        return schema;
    }

    private static PgColumn column(String name) {
        var column = new PgColumn(name);
        column.setType("integer");
        return column;
    }

    private static TreeElement allChecked(CoreSettings settings, PgDatabase oldDb, PgDatabase newDb)
            throws Exception {
        TreeElement root = DiffTree.create(settings, oldDb, newDb, null);
        root.setAllChecked();
        return root;
    }

    private static TreeElement onlySchemaChecked(CoreSettings settings, PgDatabase oldDb,
                                                 PgDatabase newDb, String schemaName) throws Exception {
        TreeElement root = DiffTree.create(settings, oldDb, newDb, null);
        root.setAllChecked();
        for (TreeElement schema : root.getChildren()) {
            if (!schemaName.equals(schema.getName())) {
                deselect(schema);
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
        return root == null
                ? PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, settings)
                : PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, settings, root);
    }
}
