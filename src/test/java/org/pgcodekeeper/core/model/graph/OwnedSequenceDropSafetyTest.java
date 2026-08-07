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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgPrivilege;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleForeignTable;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;

class OwnedSequenceDropSafetyTest {

    static {
        Locale.setDefault(Locale.ENGLISH);
    }

    private static final ObjectReference TABLE_A =
            new ObjectReference("public", "a", DbObjType.TABLE);
    private static final ObjectReference TABLE_B =
            new ObjectReference("public", "b", DbObjType.TABLE);
    private static final ObjectReference COLUMN_A =
            new ObjectReference("public", "a", "id", DbObjType.COLUMN);
    private static final ObjectReference COLUMN_A_ALT =
            new ObjectReference("public", "a", "id_alt", DbObjType.COLUMN);
    private static final ObjectReference COLUMN_B =
            new ObjectReference("public", "b", "id", DbObjType.COLUMN);
    private static final ObjectReference SEQUENCE =
            new ObjectReference("public", "s", DbObjType.SEQUENCE);

    @Test
    void ownedToNoneDetachesBeforeOwningColumnDrop() throws Exception {
        Databases databases = databases(COLUMN_A, null, true, true);

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, detach(), dropColumnA());
        assertEquals(1, occurrences(script, detach()), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
    }

    @Test
    void rightOnlySequenceDoesNotEnterRemovedSequenceValidation() throws Exception {
        PgDatabase oldDatabase = new PgDatabase();
        PgDatabase newDatabase = new PgDatabase();
        var schema = new PgSchema("public");
        newDatabase.addChild(schema);
        schema.addChild(new PgSequence("new_sequence"));

        String script = diff(new Databases(oldDatabase, newDatabase),
                new CoreSettings(), null);

        assertTrue(script.contains("CREATE SEQUENCE public.new_sequence"), script);
    }

    @Test
    void emptyActionGuardIgnoresRightOnlySequence() throws Exception {
        PgDatabase oldDatabase = new PgDatabase();
        PgDatabase newDatabase = new PgDatabase();
        var schema = new PgSchema("public");
        newDatabase.addChild(schema);
        var sequence = new PgSequence("new_sequence");
        schema.addChild(sequence);
        TreeElement tree = DiffTree.create(new CoreSettings(),
                oldDatabase, newDatabase, null);

        assertDoesNotThrow(() -> ActionsToScriptConverter.validateEmptyActions(
                oldDatabase, newDatabase, List.of(tree.findElement(sequence))));
    }

    @Test
    void reparentDetachesBeforeDropAndAttachesAfter() throws Exception {
        Databases databases = databases(COLUMN_A, COLUMN_B, true, true);

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, detach(), dropColumnA(), attachB());
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
        assertFalse(script.contains("CREATE SEQUENCE public.s"), script);
    }

    @Test
    void reparentDetachesBeforeOwningTableDropAndAttachesAfter() throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "owner", "owner"),
                databaseWithoutTableA(COLUMN_B, true));

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, detach(), "DROP TABLE public.a;", attachB());
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
        assertFalse(script.contains("CREATE SEQUENCE public.s"), script);
    }

    @Test
    void directReparentDoesNotEmitUnnecessaryDetach() throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "owner", "owner"),
                database(true, true, COLUMN_B, "owner", "owner", "owner"));

        String script = diff(databases, new CoreSettings(), null);

        assertFalse(script.contains("OWNED BY NONE"), script);
        assertTrue(script.contains(attachB()), script);
        assertFalse(script.contains("DROP COLUMN"), script);
    }

    @Test
    void directReparentChangesBothTableOwnersBeforeSequenceOwnerAndAttach()
            throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A,
                        "old_owner", "old_owner", "old_owner"),
                database(true, true, COLUMN_B,
                        "new_owner", "new_owner", "new_owner"));

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, "ALTER TABLE public.a OWNER TO new_owner;",
                "ALTER TABLE public.b OWNER TO new_owner;",
                "ALTER SEQUENCE public.s OWNER TO new_owner;", attachB());
        assertEquals(1, occurrences(script, attachB()), script);
    }

    @Test
    void directReparentToDifferentUnknownOwnerTableFailsClosed() {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, null, null, null),
                database(true, true, COLUMN_B, null, null, null));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, new CoreSettings(), null));

        assertEquals(unknownOwnerMessage("public.b"), failure.getMessage());
    }

    @Test
    void directReparentUsesExistingTargetTableOwnerWhenTargetOmitsOwners()
            throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "owner", "owner"),
                database(true, true, COLUMN_B, null, null, null));

        String script = diff(databases, new CoreSettings(), null);

        assertTrue(script.contains(attachB()), script);
        assertFalse(script.contains("ALTER TABLE public.b OWNER"), script);
        assertFalse(script.contains("ALTER SEQUENCE public.s OWNER"), script);
    }

    @Test
    void directReparentUsesSourceTableOwnerTransferredWhileStillAttached()
            throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A,
                        "old_owner", "old_owner", "old_owner"),
                database(true, true, COLUMN_B,
                        "new_owner", "new_owner", null));

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, "ALTER TABLE public.a OWNER TO new_owner;",
                "ALTER TABLE public.b OWNER TO new_owner;", attachB());
        assertFalse(script.contains("ALTER SEQUENCE public.s OWNER"), script);
    }

    @Test
    void directReparentRejectsOwnerTransferredAwayFromTargetRuntimeOwner() {
        Databases databases = new Databases(
                database(true, true, COLUMN_A,
                        "old_owner", "old_owner", "old_owner"),
                database(true, true, COLUMN_B,
                        "new_owner", "old_owner", null));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, new CoreSettings(), null));

        assertEquals(unknownOwnerMessage("public.b"), failure.getMessage());
    }

    @Test
    void directReparentToNewTableUsesEmittedTargetOwner() throws Exception {
        Databases databases = new Databases(
                databaseWithoutTableB(COLUMN_A, "owner"),
                database(true, true, COLUMN_B, "owner", "owner", null));

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, "CREATE TABLE public.b",
                "ALTER TABLE public.b OWNER TO owner;", attachB());
    }

    @Test
    void directReparentToNewTableRejectsHiddenTableCreation() throws Exception {
        Databases databases = new Databases(
                databaseWithoutTableB(COLUMN_A, "owner"),
                database(true, true, COLUMN_B, "owner", "owner", null));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings,
                        select(databases, settings, SEQUENCE)));

        assertEquals("Owned sequence public.s requires owning column public.b.id to be emitted.",
                failure.getMessage());
    }

    @Test
    void directReparentWithOmittedTargetOwnersRejectsMismatchingRuntimeOwner() {
        Databases databases = new Databases(
                database(true, true, COLUMN_A,
                        "owner", "different_owner", "owner"),
                database(true, true, COLUMN_B, null, null, null));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, new CoreSettings(), null));

        assertEquals(unknownOwnerMessage("public.b"), failure.getMessage());
    }

    @Test
    void detachedSameTableSequenceRejectsOmittedOwnerAfterTableOwnerChange() {
        Databases databases = recreatedOwningColumnDatabases(
                "old_owner", "new_owner", "old_owner", null);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, new CoreSettings(), null));

        assertEquals(unknownOwnerMessage("public.a"), failure.getMessage());
    }

    @Test
    void detachedSameTableSequenceUsesUnchangedRuntimeOwnerWhenTargetOmitsOwners()
            throws Exception {
        Databases databases = recreatedOwningColumnDatabases(
                "owner", null, "owner", null);

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, detach(), dropColumnA(), attachA());
        assertFalse(script.contains("ALTER TABLE public.a OWNER"), script);
        assertFalse(script.contains("ALTER SEQUENCE public.s OWNER"), script);
    }

    @Test
    void inferredSequenceOwnerRequiresEmittedTargetTableOwnerChange() throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "other_owner", null),
                database(true, true, COLUMN_B, "owner", "owner", null));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings,
                        select(databases, settings, SEQUENCE)));

        assertEquals("Owned sequence public.s requires owning table public.b "
                + "to be emitted with owner owner.", failure.getMessage());
    }

    @Test
    void inferredSequenceOwnerUsesEmittedTargetTableOwnerChange() throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "other_owner", null),
                database(true, true, COLUMN_B, "owner", "owner", null));

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, "ALTER TABLE public.b OWNER TO owner;", attachB());
    }

    @Test
    void directReparentWithinSameUnknownOwnerTableIsSafe() throws Exception {
        Databases databases = new Databases(
                databaseWithTwoColumns(COLUMN_A),
                databaseWithTwoColumns(COLUMN_A_ALT));

        String script = diff(databases, new CoreSettings(), null);

        assertTrue(script.contains(attachAAlt()), script);
        assertFalse(script.contains("OWNED BY NONE"), script);
    }

    @Test
    void earlyDetachRemovesCurrentOwningTableOwnerPrerequisite() throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "old_owner", "new_owner", "old_owner"),
                database(false, true, COLUMN_B, "old_owner", "new_owner", "new_owner"));

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, detach(), dropColumnA(),
                "ALTER SEQUENCE public.s OWNER TO new_owner;", attachB());
        assertFalse(script.contains("ALTER TABLE public.a OWNER"), script);
    }

    @Test
    void directReparentStillRequiresCurrentOwningTableOwnerChange() {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "old_owner", "new_owner", "old_owner"),
                database(true, true, COLUMN_B, "old_owner", "new_owner", "new_owner"));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, new CoreSettings(), null));

        assertEquals("Owned sequence public.s requires owning table public.a "
                + "to be emitted with owner new_owner.", failure.getMessage());
    }

    @Test
    void selectedOnlyColumnDropFailsWhenRequiredDetachIsHidden() throws Exception {
        Databases databases = databases(COLUMN_A, null, true, true);
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, select(databases, settings, TABLE_A)));

        assertEquals(detachRequiredMessage(), failure.getMessage());
    }

    @Test
    void allowedTableDropFailsWhenRequiredDetachIsFiltered() {
        Databases databases = databases(COLUMN_A, COLUMN_B, true, true);
        var settings = new CoreSettings();
        settings.setAllowedTypes(List.of(DbObjType.TABLE));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals(detachRequiredMessage(), failure.getMessage());
    }

    @Test
    void selectedOnlyDropAndDetachSucceedTogether() throws Exception {
        Databases databases = databases(COLUMN_A, null, true, true);
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = diff(databases, settings,
                select(databases, settings, TABLE_A, SEQUENCE));

        assertOrdered(script, detach(), dropColumnA());
    }

    @Test
    void selectedOnlyOwningColumnDropKeepsImplicitSequenceCascade() throws Exception {
        Databases databases = databases(COLUMN_A, null, true, false);
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = diff(databases, settings,
                select(databases, settings, TABLE_A));

        assertTrue(script.contains(dropColumnA()), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
    }

    @Test
    void selectedOnlyTableDropFailsWhenRequiredDetachIsHidden() throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "owner", "owner"),
                databaseWithoutTableA(COLUMN_B, true));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, select(databases, settings, TABLE_A)));

        assertEquals(detachRequiredMessage(), failure.getMessage());
    }

    @Test
    void fullDropOfSequenceAndOwningColumnKeepsImplicitCascade() throws Exception {
        Databases databases = databases(COLUMN_A, null, true, false);

        String script = diff(databases, new CoreSettings(), null);

        assertTrue(script.contains(dropColumnA()), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
    }

    @Test
    void selectedOnlyRemovedSequenceFailsWhenCascadeDropIsHidden() throws Exception {
        Databases databases = databases(COLUMN_A, null, true, false);
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, select(databases, settings, SEQUENCE)));

        assertEquals(cascadeRequiredMessage(), failure.getMessage());
    }

    @Test
    void normalModeRemovedSequenceFailsWhenUnselectedCascadeLeavesNoActions() throws Exception {
        Databases databases = databases(COLUMN_A, null, true, false);
        var settings = new CoreSettings();

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, select(databases, settings, SEQUENCE)));

        assertEquals(cascadeRequiredMessage(), failure.getMessage());
    }

    @Test
    void normalModeRemovedSequenceFailsWhenUnselectedCascadeHasUnrelatedAction()
            throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "owner", "owner", true),
                database(false, true, null, "owner", "new_owner", "owner", false));
        var settings = new CoreSettings();

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings,
                        select(databases, settings, SEQUENCE, TABLE_B)));

        assertEquals(cascadeRequiredMessage(), failure.getMessage());
    }

    @Test
    void fullDropOfSequenceAndOwningTableKeepsImplicitCascade() throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "owner", "owner"),
                databaseWithoutTableA(null, false));

        String script = diff(databases, new CoreSettings(), null);

        assertTrue(script.contains("DROP TABLE public.a;"), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
    }

    @Test
    void selectedOnlyRemovedSequenceFailsWhenOwningTableDropIsHidden() throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "owner", "owner"),
                databaseWithoutTableA(null, false));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, select(databases, settings, SEQUENCE)));

        assertEquals(cascadeRequiredMessage(), failure.getMessage());
    }

    @Test
    void allowedSequenceRemovalFailsWhenOwningTableDropIsFiltered() {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "owner", "owner"),
                databaseWithoutTableA(null, false));
        var settings = new CoreSettings();
        settings.setAllowedTypes(List.of(DbObjType.SEQUENCE));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals(cascadeRequiredMessage(), failure.getMessage());
    }

    @Test
    void selectedOnlyOwningTableDropKeepsImplicitCascade() throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "owner", "owner"),
                databaseWithoutTableA(null, false));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        String script = diff(databases, settings,
                select(databases, settings, TABLE_A));

        assertTrue(script.contains("DROP TABLE public.a;"), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
    }

    @Test
    void allowedSequenceRemovalFailsWhenCascadeDropIsFiltered() {
        Databases databases = databases(COLUMN_A, null, true, false);
        var settings = new CoreSettings();
        settings.setAllowedTypes(List.of(DbObjType.SEQUENCE));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals(cascadeRequiredMessage(), failure.getMessage());
    }

    @Test
    void selectedOnlyNullOwnerStillRequiresTargetColumn() throws Exception {
        Databases databases = new Databases(
                database(false, true, null, null, null, null),
                database(true, true, COLUMN_A, null, null, null));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, select(databases, settings, SEQUENCE)));

        assertEquals("Owned sequence public.s requires owning column public.a.id to be emitted.",
                failure.getMessage());
    }

    @Test
    void allowedTypesNullOwnerStillRequiresTargetColumn() {
        Databases databases = new Databases(
                database(false, true, null, null, null, null),
                database(true, true, COLUMN_A, null, null, null));
        var settings = new CoreSettings();
        settings.setAllowedTypes(List.of(DbObjType.SEQUENCE));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals("Owned sequence public.s requires owning column public.a.id to be emitted.",
                failure.getMessage());
    }

    @Test
    void unchangedOwnedByProducesNoDiff() throws Exception {
        Databases databases = new Databases(
                database(true, true, COLUMN_A, "owner", "owner", "owner"),
                database(true, true, COLUMN_A, "owner", "owner", "owner"));

        assertEquals("", diff(databases, new CoreSettings(), null));
    }

    @Test
    void fullTableRecreatePreservesSequenceIdentityAndAppliesPropertyChanges()
            throws Exception {
        Databases databases = reorderedOwningTableDatabases();
        PgSimpleTable targetTable = (PgSimpleTable) databases.newDb().getStatement(TABLE_A);
        targetTable.setOwner("new_owner");
        PgSequence targetSequence = (PgSequence) databases.newDb().getStatement(SEQUENCE);
        targetSequence.setOwner("new_owner");
        targetSequence.setCache("42");
        targetSequence.setCycle(true);
        targetSequence.addPrivilege(new PgPrivilege(
                "GRANT", "USAGE", "SEQUENCE public.s", "reader", false));

        String script = diff(databases, new CoreSettings(), null);

        String bodyAlter = "ALTER SEQUENCE public.s\n\tCACHE 42\n\tCYCLE;";
        assertOrdered(script, detach(), "DROP TABLE public.a;", "CREATE TABLE public.a",
                "ALTER TABLE public.a OWNER TO new_owner;", bodyAlter,
                "ALTER SEQUENCE public.s OWNER TO new_owner;",
                "GRANT USAGE ON SEQUENCE public.s TO reader;", attachA());
        assertEquals(1, occurrences(script, detach()), script);
        assertEquals(1, occurrences(script, attachA()), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
        assertFalse(script.contains("CREATE SEQUENCE public.s"), script);
    }

    @Test
    void normalTableRecreateWithUnknownSequenceOwnerFailsClosed() {
        Databases databases = reorderedOwningTableDatabases();
        ((PgSimpleTable) databases.oldDb().getStatement(TABLE_A)).setOwner(null);
        ((PgSimpleTable) databases.newDb().getStatement(TABLE_A)).setOwner(null);
        ((PgSequence) databases.oldDb().getStatement(SEQUENCE)).setOwner(null);
        ((PgSequence) databases.newDb().getStatement(SEQUENCE)).setOwner(null);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, new CoreSettings(), null));

        assertEquals(unknownOwnerMessage("public.a"), failure.getMessage());
    }

    @Test
    void parsedTableRecreatePreservesUnchangedOwnedSequenceWithoutDependencyEdge()
            throws Exception {
        ParsedDatabases databases = parsedTableRecreateDatabases();

        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                databases.oldDb(), databases.newDb(), new CoreSettings());

        assertOrdered(script, detach(), "DROP TABLE public.a;", "CREATE TABLE public.a",
                "ALTER TABLE public.a OWNER TO owner;", attachA());
        assertEquals(1, occurrences(script, detach()), script);
        assertEquals(1, occurrences(script, attachA()), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
        assertFalse(script.contains("CREATE SEQUENCE public.s"), script);
    }

    @Test
    void parsedTableRecreateFailsWhenImplicitSequencePreservationIsUnselected()
            throws Exception {
        ParsedDatabases databases = parsedTableRecreateDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);
        TreeElement selected = select(databases.oldDb(), databases.newDb(), settings, TABLE_A);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> PgCodeKeeperApi.diff(new PgDatabaseProvider(), databases.oldDb(),
                        databases.newDb(), settings, selected));

        assertEquals(preservationRequiredMessage(), failure.getMessage());
    }

    @Test
    void parsedTableRecreateFailsWhenImplicitSequencePreservationTypeIsFiltered()
            throws Exception {
        ParsedDatabases databases = parsedTableRecreateDatabases();
        var settings = new CoreSettings();
        settings.setAllowedTypes(List.of(DbObjType.TABLE));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> PgCodeKeeperApi.diff(new PgDatabaseProvider(), databases.oldDb(),
                        databases.newDb(), settings));

        assertEquals(preservationRequiredMessage(), failure.getMessage());
    }

    @Test
    void selectedOnlyCreateOwnedSequenceRequiresTargetTableOwnerAction() throws Exception {
        Databases databases = new Databases(
                database(true, true, null, "old_owner", "owner", null, false),
                database(true, true, COLUMN_A, "new_owner", "owner", "new_owner", true));
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, select(databases, settings, SEQUENCE)));

        assertEquals("Owned sequence public.s requires owning table public.a "
                + "to be emitted with owner new_owner.", failure.getMessage());
    }

    @Test
    void allowedCreateOwnedSequenceRequiresTargetColumnAction() {
        Databases databases = new Databases(
                database(false, true, null, "owner", "owner", null, false),
                database(true, true, COLUMN_A, "owner", "owner", "owner", true));
        var settings = new CoreSettings();
        settings.setAllowedTypes(List.of(DbObjType.SEQUENCE));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals("Owned sequence public.s requires owning column public.a.id to be emitted.",
                failure.getMessage());
    }

    @Test
    void createOwnedSequenceUsesExistingCompatibleTableAndColumn() throws Exception {
        Databases databases = new Databases(
                database(true, true, null, "owner", "owner", null, false),
                database(true, true, COLUMN_A, "owner", "owner", "owner", true));

        String script = diff(databases, new CoreSettings(), null);

        assertTrue(script.contains("CREATE SEQUENCE public.s"), script);
        assertEquals(1, occurrences(script, attachA()), script);
    }

    @Test
    void createOwnedSequenceWithUnknownOwnerOnExistingTableFailsClosed() {
        Databases databases = new Databases(
                database(true, true, null, null, null, null, false),
                database(true, true, COLUMN_A, null, null, null, true));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, new CoreSettings(), null));

        assertEquals(unknownOwnerMessage("public.a"), failure.getMessage());
    }

    @Test
    void createOwnedSequenceWithNewUnknownOwnerTableUsesCurrentRole() throws Exception {
        Databases databases = new Databases(
                databaseWithoutTableA(null, false),
                database(true, true, COLUMN_A, null, "owner", null, true));

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, "CREATE TABLE public.a", "CREATE SEQUENCE public.s", attachA());
    }

    @Test
    void fullReparentWithBothTablesRecreatedPreservesSequence() throws Exception {
        Databases databases = new Databases(
                databaseForMovement(List.of("id", "payload"),
                        List.of("id", "payload"), COLUMN_A),
                databaseForMovement(List.of("payload", "id"),
                        List.of("payload", "id"), COLUMN_B));

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, detach(), "DROP TABLE public.a;", "CREATE TABLE public.b",
                attachB());
        assertEquals(1, occurrences(script, attachB()), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
        assertFalse(script.contains("CREATE SEQUENCE public.s"), script);
    }

    @Test
    void recreatedTargetTableCarriesRequiredOwnerForReparent() throws Exception {
        Databases databases = ownerChangingTargetRecreateDatabases();

        String script = diff(databases, new CoreSettings(), null);

        assertOrdered(script, "CREATE TABLE public.b",
                "ALTER TABLE public.b OWNER TO new_owner;", attachB());
    }

    @Test
    void hiddenRecreatedTargetTableCannotSatisfyOwnerPrerequisite() throws Exception {
        Databases databases = ownerChangingTargetRecreateDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, select(databases, settings, SEQUENCE)));

        assertEquals("Owned sequence public.s requires owning table public.b "
                + "to be emitted with owner new_owner.", failure.getMessage());
    }

    @Test
    void allowedTableRecreateFailsWhenSequenceRecreateIsFiltered() {
        Databases databases = reorderedOwningTableDatabases();
        var settings = new CoreSettings();
        settings.setAllowedTypes(List.of(DbObjType.TABLE));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals(preservationRequiredMessage(), failure.getMessage());
    }

    @Test
    void selectedOnlyTableRecreateFailsWhenSequenceRecreateIsHidden() throws Exception {
        Databases databases = reorderedOwningTableDatabases();
        var settings = new CoreSettings();
        settings.setSelectedOnly(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, select(databases, settings, TABLE_A)));

        assertEquals(preservationRequiredMessage(), failure.getMessage());
    }

    @Test
    void dataMovementOwnedToNoneFailsClosedWhenSequenceWouldBeDropped() {
        Databases databases = new Databases(
                databaseForMovement(List.of("id", "payload"), COLUMN_A),
                databaseForMovement(List.of("payload", "id"), null));
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals(preservationRequiredMessage(), failure.getMessage());
    }

    @Test
    void dataMovementReparentFailsClosedWhenOldTableIsRecreated() {
        Databases databases = new Databases(
                databaseForMovement(List.of("id", "payload"), COLUMN_A),
                databaseForMovement(List.of("payload", "id"), COLUMN_B));
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals(preservationRequiredMessage(), failure.getMessage());
    }

    @Test
    void dataMovementReparentFailsClosedWhenBothTablesAreRecreated() {
        Databases databases = new Databases(
                databaseForMovement(List.of("id", "payload"),
                        List.of("id", "payload"), COLUMN_A),
                databaseForMovement(List.of("payload", "id"),
                        List.of("payload", "id"), COLUMN_B));
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals(preservationRequiredMessage(), failure.getMessage());
    }

    @Test
    void parsedDataMovementReparentIgnoresTargetSideDependencyAlter() throws Exception {
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                loadMovementFixture("old", "a", false, false),
                loadMovementFixture("new", "b", true, true), settings);

        assertOrdered(script, detach(), attachB());
        assertEquals(1, occurrences(script, attachB()), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
    }

    @Test
    void dataMovementAlterReparentsToUnchangedTable() throws Exception {
        Databases databases = new Databases(
                databaseForMovementAlter(List.of("id", "payload"), COLUMN_A),
                databaseForMovementAlter(List.of("payload", "id"), COLUMN_B));
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        String script = diff(databases, settings, null);

        assertOrdered(script, detach(), attachB());
        assertEquals(1, occurrences(script, attachB()), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
        assertFalse(script.contains("CREATE SEQUENCE public.s"), script);
    }

    @Test
    void dataMovementOwnerChangeReattachesAfterOwnerWithBothTablesMoved() throws Exception {
        Databases databases = new Databases(
                databaseForMovementOwnerChange(List.of("id", "payload"),
                        List.of("id", "payload"), COLUMN_A, "old_owner"),
                databaseForMovementOwnerChange(List.of("payload", "id"),
                        List.of("payload", "id"), COLUMN_B, "new_owner"));
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        String script = diff(databases, settings, null);

        assertOrdered(script, detach(), "ALTER TABLE public.b OWNER TO new_owner;",
                "ALTER SEQUENCE public.s OWNER TO new_owner;", attachB());
        assertEquals(1, occurrences(script, attachB()), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
    }

    @Test
    void dataMovementTableRecreateWithUnknownSequenceOwnerFailsClosed() {
        PgDatabase oldDatabase = databaseForMovementAlter(
                List.of("id", "payload"), COLUMN_A);
        PgDatabase newDatabase = databaseForMovementAlter(
                List.of("payload", "id"), COLUMN_A);
        ((PgSimpleTable) oldDatabase.getStatement(TABLE_A)).setOwner(null);
        ((PgSimpleTable) newDatabase.getStatement(TABLE_A)).setOwner(null);
        ((PgSequence) oldDatabase.getStatement(SEQUENCE)).setOwner(null);
        ((PgSequence) newDatabase.getStatement(SEQUENCE)).setOwner(null);
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(new Databases(oldDatabase, newDatabase), settings, null));

        assertEquals(unknownOwnerMessage("public.a"), failure.getMessage());
    }

    @Test
    void dataMovementTableRecreateWithUnchangedSequenceOwnerRequiresMatchingTableOwner() {
        PgDatabase oldDatabase = databaseForMovementOwnerChange(
                List.of("id", "payload"), List.of("id", "payload"),
                COLUMN_A, "old_owner");
        PgDatabase newDatabase = databaseForMovementOwnerChange(
                List.of("payload", "id"), List.of("id", "payload"),
                COLUMN_A, "old_owner");
        ((PgSimpleTable) newDatabase.getStatement(TABLE_A)).setOwner("new_owner");
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(new Databases(oldDatabase, newDatabase), settings, null));

        assertEquals("Owned sequence public.s requires owning table public.a "
                + "to be emitted with owner old_owner.", failure.getMessage());
    }

    @Test
    void dataMovementOwnerChangeWithSameOwnedByReattachesAfterOwner() throws Exception {
        Databases databases = sameOwnedByMovementOwnerChangeDatabases();
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        String script = diff(databases, settings, null);

        assertOrdered(script, detach(), "ALTER TABLE public.a OWNER TO new_owner;",
                "ALTER SEQUENCE public.s OWNER TO new_owner;", attachA());
        assertEquals(1, occurrences(script, attachA()), script);
        assertFalse(script.contains("DROP SEQUENCE public.s"), script);
    }

    @Test
    void dataMovementSameOwnedByReattachRequiresTargetTableOwner() {
        Databases valid = sameOwnedByMovementOwnerChangeDatabases();
        PgSimpleTable targetTable = (PgSimpleTable) valid.newDb().getStatement(TABLE_A);
        targetTable.setOwner("different_owner");
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(valid, settings, null));

        assertEquals("Owned sequence public.s requires owning table public.a "
                + "to be emitted with owner new_owner.", failure.getMessage());
    }

    @Test
    void dataMovementSameOwnedByReattachRequiresTargetColumn() {
        Databases databases = new Databases(
                databaseForMovementOwnerChange(List.of("id", "payload"),
                        List.of("id", "payload"), COLUMN_A, "old_owner"),
                databaseForMovementOwnerChange(List.of("payload"),
                        List.of("id", "payload"), COLUMN_A, "new_owner"));
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals("Owned sequence public.s requires owning column public.a.id to be emitted.",
                failure.getMessage());
    }

    @Test
    void dataMovementFilteredSequenceRecreateFailsClosed() {
        Databases databases = reorderedOwningTableDatabases();
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);
        settings.setAllowedTypes(List.of(DbObjType.TABLE));

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals(preservationRequiredMessage(), failure.getMessage());
    }

    @Test
    void dataMovementForeignTableRecreateFailsClosed() {
        Databases databases = new Databases(
                foreignDatabaseWithColumnOrder(List.of("id", "payload")),
                foreignDatabaseWithColumnOrder(List.of("payload", "id")));
        var settings = new CoreSettings();
        settings.setDataMovementMode(true);

        var failure = assertThrows(NotAllowedObjectException.class,
                () -> diff(databases, settings, null));

        assertEquals(preservationRequiredMessage(), failure.getMessage());
    }

    private static Databases databases(ObjectReference oldOwnedBy,
                                        ObjectReference newOwnedBy,
                                        boolean oldSequence, boolean newSequence) {
        return new Databases(
                database(true, true, oldOwnedBy, "owner", "owner", "owner", oldSequence),
                database(false, true, newOwnedBy, "owner", "owner", "owner", newSequence));
    }

    private static Databases reorderedOwningTableDatabases() {
        return new Databases(
                databaseWithColumnOrder(List.of("id", "payload")),
                databaseWithColumnOrder(List.of("payload", "id")));
    }

    private static Databases recreatedOwningColumnDatabases(
            String oldTableOwner, String newTableOwner,
            String oldSequenceOwner, String newSequenceOwner) {
        PgDatabase oldDatabase = database(true, true, COLUMN_A,
                oldTableOwner, oldTableOwner, oldSequenceOwner);
        PgDatabase newDatabase = database(true, true, COLUMN_A,
                newTableOwner, newTableOwner, newSequenceOwner);
        var newColumn = (PgColumn) newDatabase.getStatement(COLUMN_A);
        newColumn.setDefaultValue("1", "1");
        newColumn.setGenerationOption("STORED");
        return new Databases(oldDatabase, newDatabase);
    }

    private static PgDatabase databaseWithColumnOrder(List<String> columns) {
        return databaseWithColumnOrder(columns, COLUMN_A);
    }

    private static PgDatabase databaseWithColumnOrder(List<String> columns,
                                                      ObjectReference ownedBy) {
        return databaseWithColumnOrder(columns, ownedBy, true);
    }

    private static PgDatabase databaseWithColumnOrder(List<String> columns,
                                                      ObjectReference ownedBy,
                                                      boolean addSequenceDependency) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");

        var table = new PgSimpleTable("a");
        table.setOwner("owner");
        schema.addChild(table);
        for (String name : columns) {
            var column = new PgColumn(name);
            column.setType("integer");
            table.addColumn(column);
        }

        var sequence = new PgSequence("s");
        sequence.setOwner("owner");
        sequence.setOwnedBy(ownedBy);
        if (ownedBy != null && addSequenceDependency) {
            sequence.addDependency(ownedBy);
        }
        schema.addChild(sequence);
        return db;
    }

    private static PgDatabase databaseWithTwoColumns(ObjectReference ownedBy) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");

        var table = new PgSimpleTable("a");
        schema.addChild(table);
        for (String name : List.of("id", "id_alt")) {
            var column = new PgColumn(name);
            column.setType("integer");
            table.addColumn(column);
        }

        var sequence = new PgSequence("s");
        sequence.setOwnedBy(ownedBy);
        sequence.addDependency(ownedBy);
        schema.addChild(sequence);
        return db;
    }

    private static Databases ownerChangingTargetRecreateDatabases() {
        return new Databases(
                ownerChangingTargetRecreateDatabase(
                        List.of("id", "payload"), "old_owner", COLUMN_A),
                ownerChangingTargetRecreateDatabase(
                        List.of("payload", "id"), "new_owner", COLUMN_B));
    }

    private static PgDatabase ownerChangingTargetRecreateDatabase(
            List<String> tableBColumns, String tableBOwner, ObjectReference ownedBy) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");
        addTable(schema, "a", true, "new_owner");

        var tableB = new PgSimpleTable("b");
        tableB.setOwner(tableBOwner);
        schema.addChild(tableB);
        for (String name : tableBColumns) {
            var column = new PgColumn(name);
            column.setType("integer");
            tableB.addColumn(column);
        }

        var sequence = new PgSequence("s");
        sequence.setOwner("new_owner");
        sequence.setOwnedBy(ownedBy);
        schema.addChild(sequence);
        return db;
    }

    private static PgDatabase databaseForMovement(List<String> columns,
                                                  ObjectReference ownedBy) {
        PgDatabase db = databaseWithColumnOrder(columns, ownedBy);
        PgSchema schema = db.getSchema("public");
        addTable(schema, "b", true, "owner");
        return db;
    }

    private static PgDatabase databaseForMovement(List<String> columnsA,
                                                  List<String> columnsB,
                                                  ObjectReference ownedBy) {
        PgDatabase db = databaseWithColumnOrder(columnsA, ownedBy);
        PgSchema schema = db.getSchema("public");
        var tableB = new PgSimpleTable("b");
        tableB.setOwner("owner");
        schema.addChild(tableB);
        for (String name : columnsB) {
            var column = new PgColumn(name);
            column.setType("integer");
            tableB.addColumn(column);
        }
        return db;
    }

    private static PgDatabase databaseForMovementAlter(List<String> columns,
                                                       ObjectReference ownedBy) {
        PgDatabase db = databaseWithColumnOrder(columns, ownedBy, false);
        addTable(db.getSchema("public"), "b", true, "owner");
        return db;
    }

    private static IDatabase loadMovementFixture(String name, String owner,
                                                 boolean reverseA, boolean reverseB)
            throws Exception {
        String columnsA = reverseA
                ? "payload integer, id integer DEFAULT nextval('public.s'::regclass)"
                : "id integer DEFAULT nextval('public.s'::regclass), payload integer";
        String columnsB = reverseB
                ? "payload integer, id integer"
                : "id integer, payload integer";
        String sql = """
                CREATE SEQUENCE public.s;
                CREATE TABLE public.a (%s);
                CREATE TABLE public.b (%s);
                ALTER TABLE public.a OWNER TO owner;
                ALTER TABLE public.b OWNER TO owner;
                ALTER SEQUENCE public.s OWNER TO owner;
                ALTER SEQUENCE public.s OWNED BY public.%s.id;
                """.formatted(columnsA, columnsB, owner);
        var settings = new CoreSettings();
        return new PgDatabaseProvider().getDumpLoader(
                () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)),
                name, settings).loadAndAnalyze();
    }

    private static ParsedDatabases parsedTableRecreateDatabases() throws Exception {
        return new ParsedDatabases(
                loadTableRecreateFixture("old", false),
                loadTableRecreateFixture("new", true));
    }

    private static IDatabase loadTableRecreateFixture(String name, boolean reverseColumns)
            throws Exception {
        String columns = reverseColumns
                ? "payload integer, id integer"
                : "id integer, payload integer";
        String sql = """
                CREATE TABLE public.a (%s);
                ALTER TABLE public.a OWNER TO owner;
                CREATE SEQUENCE public.s;
                ALTER SEQUENCE public.s OWNER TO owner;
                ALTER SEQUENCE public.s OWNED BY public.a.id;
                """.formatted(columns);
        var settings = new CoreSettings();
        return new PgDatabaseProvider().getDumpLoader(
                () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)),
                name, settings).loadAndAnalyze();
    }

    private static PgDatabase databaseForMovementOwnerChange(
            List<String> columnsA, List<String> columnsB, ObjectReference ownedBy,
            String owner) {
        PgDatabase db = databaseWithColumnOrder(columnsA, ownedBy, false);
        PgSchema schema = db.getSchema("public");
        PgSimpleTable tableA = (PgSimpleTable) db.getStatement(TABLE_A);
        tableA.setOwner(owner);

        var tableB = new PgSimpleTable("b");
        tableB.setOwner(owner);
        schema.addChild(tableB);
        for (String name : columnsB) {
            var column = new PgColumn(name);
            column.setType("integer");
            tableB.addColumn(column);
        }
        PgSequence sequence = (PgSequence) db.getStatement(SEQUENCE);
        sequence.setOwner(owner);
        return db;
    }

    private static Databases sameOwnedByMovementOwnerChangeDatabases() {
        return new Databases(
                databaseForMovementOwnerChange(List.of("id", "payload"),
                        List.of("id", "payload"), COLUMN_A, "old_owner"),
                databaseForMovementOwnerChange(List.of("payload", "id"),
                        List.of("id", "payload"), COLUMN_A, "new_owner"));
    }

    private static PgDatabase foreignDatabaseWithColumnOrder(List<String> columns) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");

        var table = new PgSimpleForeignTable("a", "srv");
        table.setOwner("owner");
        schema.addChild(table);
        for (String name : columns) {
            var column = new PgColumn(name);
            column.setType("integer");
            table.addColumn(column);
        }

        var sequence = new PgSequence("s");
        sequence.setOwner("owner");
        sequence.setOwnedBy(COLUMN_A);
        sequence.addDependency(COLUMN_A);
        schema.addChild(sequence);
        return db;
    }

    private static PgDatabase database(boolean columnA, boolean columnB,
                                       ObjectReference ownedBy, String tableAOwner,
                                       String tableBOwner, String sequenceOwner) {
        return database(columnA, columnB, ownedBy, tableAOwner,
                tableBOwner, sequenceOwner, true);
    }

    private static PgDatabase database(boolean columnA, boolean columnB,
                                       ObjectReference ownedBy, String tableAOwner,
                                       String tableBOwner, String sequenceOwner,
                                       boolean addSequence) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");

        addTable(schema, "a", columnA, tableAOwner);
        addTable(schema, "b", columnB, tableBOwner);

        if (addSequence) {
            var sequence = new PgSequence("s");
            sequence.setOwner(sequenceOwner);
            sequence.setOwnedBy(ownedBy);
            if (ownedBy != null) {
                sequence.addDependency(ownedBy);
            }
            schema.addChild(sequence);
        }
        return db;
    }

    private static void addTable(PgSchema schema, String name, boolean addColumn, String owner) {
        var table = new PgSimpleTable(name);
        table.setOwner(owner);
        schema.addChild(table);
        if (addColumn) {
            var column = new PgColumn("id");
            column.setType("integer");
            table.addColumn(column);
        }
    }

    private static PgDatabase databaseWithoutTableA(ObjectReference ownedBy,
                                                    boolean addSequence) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");
        addTable(schema, "b", true, "owner");
        if (addSequence) {
            var sequence = new PgSequence("s");
            sequence.setOwner("owner");
            sequence.setOwnedBy(ownedBy);
            if (ownedBy != null) {
                sequence.addDependency(ownedBy);
            }
            schema.addChild(sequence);
        }
        return db;
    }

    private static PgDatabase databaseWithoutTableB(ObjectReference ownedBy,
                                                    String sequenceOwner) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");
        addTable(schema, "a", true, sequenceOwner);
        var sequence = new PgSequence("s");
        sequence.setOwner(sequenceOwner);
        sequence.setOwnedBy(ownedBy);
        sequence.addDependency(ownedBy);
        schema.addChild(sequence);
        return db;
    }

    private static TreeElement select(Databases databases, CoreSettings settings,
                                      ObjectReference... references) throws InterruptedException {
        return select(databases.oldDb(), databases.newDb(), settings, references);
    }

    private static TreeElement select(IDatabase oldDatabase, IDatabase newDatabase,
                                      CoreSettings settings, ObjectReference... references)
            throws InterruptedException {
        var tree = DiffTree.create(settings, oldDatabase, newDatabase, null);
        for (ObjectReference reference : references) {
            var statement = newDatabase.getStatement(reference);
            if (statement == null) {
                statement = oldDatabase.getStatement(reference);
            }
            tree.findElement(statement).setSelected(true);
        }
        return tree;
    }

    private static String diff(Databases databases, CoreSettings settings, TreeElement tree)
            throws Exception {
        return tree == null
                ? PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        databases.oldDb(), databases.newDb(), settings)
                : PgCodeKeeperApi.diff(new PgDatabaseProvider(),
                        databases.oldDb(), databases.newDb(), settings, tree);
    }

    private static void assertOrdered(String script, String... statements) {
        int previous = -1;
        for (String statement : statements) {
            int current = script.indexOf(statement);
            assertTrue(current > previous, script);
            previous = current;
        }
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }

    private static String detach() {
        return "ALTER SEQUENCE public.s\n\tOWNED BY NONE;";
    }

    private static String attachB() {
        return "ALTER SEQUENCE public.s\n\tOWNED BY public.b.id;";
    }

    private static String attachA() {
        return "ALTER SEQUENCE public.s\n\tOWNED BY public.a.id;";
    }

    private static String attachAAlt() {
        return "ALTER SEQUENCE public.s\n\tOWNED BY public.a.id_alt;";
    }

    private static String dropColumnA() {
        return "ALTER TABLE ONLY public.a\n\tDROP COLUMN id;";
    }

    private static String detachRequiredMessage() {
        return "Surviving owned sequence public.s must be detached before dropping "
                + "owning column public.a.id.";
    }

    private static String cascadeRequiredMessage() {
        return "Dropping owned sequence public.s requires dropping owning column public.a.id.";
    }

    private static String preservationRequiredMessage() {
        return "Surviving owned sequence public.s cannot be preserved while dropping "
                + "owning column public.a.id because required sequence operations are not emitted.";
    }

    private static String unknownOwnerMessage(String table) {
        return "Owned sequence public.s cannot be attached to table " + table
                + " because matching owner metadata is unavailable.";
    }

    private record Databases(PgDatabase oldDb, PgDatabase newDb) {
    }

    private record ParsedDatabases(IDatabase oldDb, IDatabase newDb) {
    }
}
