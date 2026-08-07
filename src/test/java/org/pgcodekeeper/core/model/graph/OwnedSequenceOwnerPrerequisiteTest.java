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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

class OwnedSequenceOwnerPrerequisiteTest {

    static {
        Locale.setDefault(Locale.ENGLISH);
    }

    private static final String EXPECTED_MESSAGE =
            "Owned sequence public.test_id_seq requires owning table public.test_table "
                    + "to be emitted with owner new_owner.";
    private static final String EXPECTED_COLUMN_MESSAGE =
            "Owned sequence public.test_id_seq requires owning column public.test_table.id "
                    + "to be emitted.";
    private static final ObjectReference SEQUENCE =
            new ObjectReference("public", "test_id_seq", DbObjType.SEQUENCE);
    private static final ObjectReference TABLE =
            new ObjectReference("public", "test_table", DbObjType.TABLE);
    private static final ObjectReference COLUMN =
            new ObjectReference("public", "test_table", "id", DbObjType.COLUMN);
    private static final ObjectReference TABLE_A =
            new ObjectReference("public", "table_a", DbObjType.TABLE);
    private static final ObjectReference TABLE_B =
            new ObjectReference("public", "table_b", DbObjType.TABLE);
    private static final ObjectReference COLUMN_A =
            new ObjectReference("public", "table_a", "id", DbObjType.COLUMN);
    private static final ObjectReference COLUMN_B =
            new ObjectReference("public", "table_b", "id", DbObjType.COLUMN);

    @Test
    void selectedOnlyOwnerChangeRejectsOwnedSequenceWithoutOwningTable() throws Exception {
        Databases databases = createDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);
        var tree = selectOnlySequence(databases, settings);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        databases.oldDb(), databases.newDb(), settings, tree));

        assertEquals(EXPECTED_MESSAGE, failure.getMessage());
    }

    @Test
    void allowedTypesRejectsOwnedSequenceWhenTableOwnerActionIsFiltered() throws Exception {
        Databases databases = createDatabases();
        var settings = new CoreSettings();
        settings.setAllowedTypes(List.of(DbObjType.SEQUENCE));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        databases.oldDb(), databases.newDb(), settings));

        assertEquals(EXPECTED_MESSAGE, failure.getMessage());
    }

    @Test
    void selectedOnlyOwnerChangeSucceedsWhenOwningTableIsEmitted() throws Exception {
        Databases databases = createDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                databases.oldDb(), databases.newDb(), settings,
                selectObjects(databases, settings, SEQUENCE, TABLE));

        int tableOwner = script.indexOf("ALTER TABLE public.test_table OWNER TO new_owner;");
        int sequenceOwner = script.indexOf(
                "ALTER SEQUENCE public.test_id_seq OWNER TO new_owner;");
        assertTrue(tableOwner >= 0 && sequenceOwner > tableOwner, script);
    }

    @Test
    void selectedOnlyRejectsNewOwnedByWhenTargetTableOwnerActionIsFiltered() throws Exception {
        var databases = new Databases(
                createDatabase("old_owner", "old_owner", null),
                createDatabase("new_owner", "new_owner", COLUMN));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);
        var tree = selectOnlySequence(databases, settings);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        databases.oldDb(), databases.newDb(), settings, tree));

        assertEquals(EXPECTED_MESSAGE, failure.getMessage());
    }

    @Test
    void selectedOnlyRejectsNewOwnedByWhenTargetColumnActionIsFiltered() throws Exception {
        Databases databases = createNewOwnedColumnDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        databases.oldDb(), databases.newDb(), settings,
                        selectOnlySequence(databases, settings)));

        assertEquals(EXPECTED_COLUMN_MESSAGE, failure.getMessage());
    }

    @Test
    void selectedOnlyRejectsNewOwnedByColumnWithoutSequenceOwnerChange() throws Exception {
        Databases databases = createNewOwnedColumnDatabases("new_owner");
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        databases.oldDb(), databases.newDb(), settings,
                        selectOnlySequence(databases, settings)));

        assertEquals(EXPECTED_COLUMN_MESSAGE, failure.getMessage());
    }

    @Test
    void selectedOnlyRejectsTargetTableOwnerChangeWithoutSequenceOwnerChange() throws Exception {
        Databases databases = createNewOwnedByWithTableOwnerChangeDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        databases.oldDb(), databases.newDb(), settings,
                        selectOnlySequence(databases, settings)));

        assertEquals(EXPECTED_MESSAGE, failure.getMessage());
    }

    @Test
    void allowedTypesRejectsNewOwnedByWhenTargetColumnActionIsFiltered() throws Exception {
        Databases databases = createNewOwnedColumnDatabases();
        var settings = new CoreSettings();
        settings.setAllowedTypes(List.of(DbObjType.SEQUENCE));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        databases.oldDb(), databases.newDb(), settings));

        assertEquals(EXPECTED_COLUMN_MESSAGE, failure.getMessage());
    }

    @Test
    void refreshedTargetColumnActionCannotSatisfyOwnedByPrerequisite() {
        Databases databases = createNewOwnedColumnDatabases();
        var settings = new CoreSettings();
        Set<IStatement> toRefresh = new LinkedHashSet<>();
        IStatement newColumn = databases.newDb().getStatement(COLUMN);
        var objects = List.of(
                new DbObject(databases.oldDb().getStatement(SEQUENCE),
                        databases.newDb().getStatement(SEQUENCE)),
                new DbObject(null, newColumn));
        var resolved = DepcyResolver.resolveActions(
                databases.oldDb(), databases.newDb(), List.of(), List.of(),
                toRefresh, objects, settings);
        toRefresh.add(newColumn);
        var script = new SQLScript(settings, ";");

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> ActionsToScriptConverter.fillScript(script, resolved, toRefresh,
                        databases.oldDb(), databases.newDb(), List.of()));

        assertEquals(EXPECTED_COLUMN_MESSAGE, failure.getMessage());
        assertTrue(script.isEmpty(), script.getFullScript());
    }

    @Test
    void selectedOnlyNewOwnedBySucceedsWhenTargetColumnIsEmitted() throws Exception {
        Databases databases = createNewOwnedColumnDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                databases.oldDb(), databases.newDb(), settings,
                selectObjects(databases, settings, SEQUENCE, TABLE));

        assertOwnedByAfterColumnCreation(script);
    }

    @Test
    void selectedOnlyNewOwnedByColumnSucceedsWithoutSequenceOwnerChange() throws Exception {
        Databases databases = createNewOwnedColumnDatabases("new_owner");
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                databases.oldDb(), databases.newDb(), settings,
                selectObjects(databases, settings, SEQUENCE, TABLE));

        assertOwnedByAfterColumnCreation(script);
    }

    @Test
    void selectedOnlyTargetTableOwnerChangeSucceedsWithoutSequenceOwnerChange() throws Exception {
        Databases databases = createNewOwnedByWithTableOwnerChangeDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                databases.oldDb(), databases.newDb(), settings,
                selectObjects(databases, settings, SEQUENCE, TABLE));

        int tableOwner = script.indexOf("ALTER TABLE public.test_table OWNER TO new_owner;");
        int ownedBy = script.indexOf(
                "ALTER SEQUENCE public.test_id_seq\n\tOWNED BY public.test_table.id;");
        assertTrue(tableOwner >= 0 && ownedBy > tableOwner, script);
    }

    @Test
    void allowedTypesNewOwnedBySucceedsWhenTableChangesAreAllowed() throws Exception {
        Databases databases = createNewOwnedColumnDatabases();
        var settings = new CoreSettings();
        settings.setAllowedTypes(List.of(DbObjType.SEQUENCE, DbObjType.TABLE));

        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                databases.oldDb(), databases.newDb(), settings);

        assertOwnedByAfterColumnCreation(script);
    }

    @Test
    void existingTargetColumnAllowsNewOwnedByWhenTableIsMarkedForRefresh() {
        var databases = new Databases(
                createDatabase("new_owner", "old_owner", null),
                createDatabase("new_owner", "new_owner", COLUMN));
        var settings = new CoreSettings();
        Set<IStatement> toRefresh = new LinkedHashSet<>();
        IStatement oldTable = databases.oldDb().getStatement(TABLE);
        var objects = List.of(new DbObject(
                databases.oldDb().getStatement(SEQUENCE),
                databases.newDb().getStatement(SEQUENCE)));
        var resolved = DepcyResolver.resolveActions(
                databases.oldDb(), databases.newDb(), List.of(), List.of(),
                toRefresh, objects, settings);
        toRefresh.add(oldTable);
        var script = new SQLScript(settings, ";");

        ActionsToScriptConverter.fillScript(script, resolved, toRefresh,
                databases.oldDb(), databases.newDb(), List.of());

        assertTrue(script.getFullScript().contains(
                "ALTER SEQUENCE public.test_id_seq\n\tOWNED BY public.test_table.id;"),
                script.getFullScript());
    }

    @Test
    void refreshedOwningTableActionCannotSatisfyOwnerPrerequisite() {
        Databases databases = createDatabases();
        var settings = new CoreSettings();
        Set<IStatement> toRefresh = new LinkedHashSet<>();
        IStatement oldTable = databases.oldDb().getStatement(TABLE);
        var objects = List.of(
                new DbObject(databases.oldDb().getStatement(SEQUENCE),
                        databases.newDb().getStatement(SEQUENCE)),
                new DbObject(oldTable, databases.newDb().getStatement(TABLE)));
        var resolved = DepcyResolver.resolveActions(
                databases.oldDb(), databases.newDb(), List.of(), List.of(),
                toRefresh, objects, settings);
        toRefresh.add(oldTable);
        var script = new SQLScript(settings, ";");

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> ActionsToScriptConverter.fillScript(script, resolved, toRefresh,
                        databases.oldDb(), databases.newDb(), List.of()));

        assertEquals(EXPECTED_MESSAGE, failure.getMessage());
        assertTrue(script.isEmpty(), script.getFullScript());
    }

    @Test
    void ownedByMoveRejectsWhenCurrentTableOwnerActionIsFiltered() throws Exception {
        Databases databases = createOwnedByMoveDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        databases.oldDb(), databases.newDb(), settings,
                        selectObjects(databases, settings, SEQUENCE, TABLE_B)));

        assertEquals(expectedMessage("table_a"), failure.getMessage());
    }

    @Test
    void ownedByMoveRejectsWhenTargetTableOwnerActionIsFiltered() throws Exception {
        Databases databases = createOwnedByMoveDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        databases.oldDb(), databases.newDb(), settings,
                        selectObjects(databases, settings, SEQUENCE, TABLE_A)));

        assertEquals(expectedMessage("table_b"), failure.getMessage());
    }

    @Test
    void ownedByMoveSucceedsWhenBothTableOwnerActionsAreEmitted() throws Exception {
        Databases databases = createOwnedByMoveDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                databases.oldDb(), databases.newDb(), settings,
                selectObjects(databases, settings, SEQUENCE, TABLE_A, TABLE_B));

        int tableAOwner = script.indexOf("ALTER TABLE public.table_a OWNER TO new_owner;");
        int tableBOwner = script.indexOf("ALTER TABLE public.table_b OWNER TO new_owner;");
        int sequenceOwner = script.indexOf(
                "ALTER SEQUENCE public.test_id_seq OWNER TO new_owner;");
        assertTrue(tableAOwner >= 0 && tableBOwner >= 0
                && sequenceOwner > tableAOwner && sequenceOwner > tableBOwner, script);
    }

    @Test
    void standaloneSequenceOwnerChangeDoesNotRequireTableAction() throws Exception {
        var databases = new Databases(
                createDatabase("old_owner", "old_owner", null),
                createDatabase("old_owner", "new_owner", null));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                databases.oldDb(), databases.newDb(), settings,
                selectOnlySequence(databases, settings));

        assertTrue(script.contains("ALTER SEQUENCE public.test_id_seq OWNER TO new_owner;"), script);
    }

    @Test
    void ownedByNoneDetachesBeforeOwnerWithoutTableAction() throws Exception {
        var databases = new Databases(
                createDatabase("old_owner", "old_owner", COLUMN),
                createDatabase("old_owner", "new_owner", null));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                databases.oldDb(), databases.newDb(), settings,
                selectOnlySequence(databases, settings));

        int detach = script.indexOf("ALTER SEQUENCE public.test_id_seq\n\tOWNED BY NONE;");
        int owner = script.indexOf("ALTER SEQUENCE public.test_id_seq OWNER TO new_owner;");
        assertTrue(detach >= 0 && owner > detach, script);
    }

    private static Databases createDatabases() {
        return new Databases(
                createDatabase("old_owner", "old_owner", COLUMN),
                createDatabase("new_owner", "new_owner", COLUMN));
    }

    private static Databases createOwnedByMoveDatabases() {
        return new Databases(
                createDatabase("old_owner", "old_owner", COLUMN_A,
                        List.of("table_a", "table_b")),
                createDatabase("new_owner", "new_owner", COLUMN_B,
                        List.of("table_a", "table_b")));
    }

    private static Databases createNewOwnedColumnDatabases() {
        return createNewOwnedColumnDatabases("old_owner");
    }

    private static Databases createNewOwnedColumnDatabases(String oldSequenceOwner) {
        return new Databases(
                createDatabase("new_owner", oldSequenceOwner, null, false),
                createDatabase("new_owner", "new_owner", COLUMN, true));
    }

    private static Databases createNewOwnedByWithTableOwnerChangeDatabases() {
        return new Databases(
                createDatabase("old_owner", "new_owner", null),
                createDatabase("new_owner", "new_owner", COLUMN));
    }

    private static PgDatabase createDatabase(String tableOwner, String sequenceOwner,
                                             ObjectReference ownedBy) {
        return createDatabase(tableOwner, sequenceOwner, ownedBy, true);
    }

    private static PgDatabase createDatabase(String tableOwner, String sequenceOwner,
                                             ObjectReference ownedBy, boolean addColumn) {
        return createDatabase(tableOwner, sequenceOwner, ownedBy,
                List.of("test_table"), addColumn);
    }

    private static PgDatabase createDatabase(String tableOwner, String sequenceOwner,
                                             ObjectReference ownedBy, List<String> tableNames) {
        return createDatabase(tableOwner, sequenceOwner, ownedBy, tableNames, true);
    }

    private static PgDatabase createDatabase(String tableOwner, String sequenceOwner,
                                             ObjectReference ownedBy, List<String> tableNames,
                                             boolean addColumn) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");

        for (String tableName : tableNames) {
            var table = new PgSimpleTable(tableName);
            table.setOwner(tableOwner);
            schema.addChild(table);

            if (addColumn) {
                var column = new PgColumn("id");
                column.setType("integer");
                table.addColumn(column);
            }
        }

        var sequence = new PgSequence("test_id_seq");
        sequence.setOwner(sequenceOwner);
        sequence.setOwnedBy(ownedBy);
        schema.addChild(sequence);
        return db;
    }

    private static TreeElement selectOnlySequence(Databases databases, CoreSettings settings)
            throws InterruptedException {
        return selectObjects(databases, settings, SEQUENCE);
    }

    private static TreeElement selectObjects(Databases databases, CoreSettings settings,
                                             ObjectReference... selectedObjects)
            throws InterruptedException {
        var tree = DiffTree.create(settings, databases.oldDb(), databases.newDb(), null);
        for (ObjectReference selectedObject : selectedObjects) {
            tree.findElement(databases.newDb().getStatement(selectedObject)).setSelected(true);
        }
        return tree;
    }

    private static String expectedMessage(String tableName) {
        return "Owned sequence public.test_id_seq requires owning table public." + tableName
                + " to be emitted with owner new_owner.";
    }

    private static void assertOwnedByAfterColumnCreation(String script) {
        int addColumn = script.indexOf("ALTER TABLE public.test_table\n\tADD COLUMN id integer;");
        int ownedBy = script.indexOf(
                "ALTER SEQUENCE public.test_id_seq\n\tOWNED BY public.test_table.id;");
        assertTrue(addColumn >= 0 && ownedBy > addColumn, script);
    }

    private record Databases(PgDatabase oldDb, PgDatabase newDb) {
    }
}
