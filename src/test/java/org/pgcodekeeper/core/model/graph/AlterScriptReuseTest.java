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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgPrivilege;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Verifies that ALTER scripts built by {@link DepcyResolver} during object
 * state evaluation are memoized and reused by
 * {@link ActionsToScriptConverter}, producing byte-identical output.
 */
class AlterScriptReuseTest {

    static {
        // explicit locale for localized Messages bound at class init;
        // prevents this class from racing TestUtils/IntegrationTestUtils
        Locale.setDefault(Locale.ENGLISH);
    }

    private static final ObjectReference ALTERED_TABLE =
            new ObjectReference("public", "altered", DbObjType.TABLE);
    private static final ObjectReference RECREATED_TABLE =
            new ObjectReference("public", "recreated", DbObjType.TABLE);
    private static final ObjectReference ALTERED_SEQUENCE =
            new ObjectReference("public", "altered_seq", DbObjType.SEQUENCE);
    private static final ObjectReference ALTERED_SEQUENCE_TABLE =
            new ObjectReference("public", "sequence_table", DbObjType.TABLE);
    private static final ObjectReference ALTERED_SEQUENCE_COLUMN =
            new ObjectReference("public", "sequence_table", "id", DbObjType.COLUMN);

    @Test
    void resolveActionsRetainsAlterScriptsOnlyForAlteredObjects() {
        PgDatabase oldDb = createDatabase("old_owner", true);
        PgDatabase newDb = createDatabase("new_owner", false);

        var resolved = resolveBoth(oldDb, newDb, new LinkedHashSet<>());

        assertEquals(List.of(
                "ALTER|public.altered",
                "DROP|public.recreated",
                "CREATE|public.recreated"), actionTrace(resolved.actions()));

        IStatement alteredOld = oldDb.getStatement(ALTERED_TABLE);
        assertEquals(1, resolved.alterScripts().size());
        SQLScript cached = resolved.alterScripts().get(alteredOld);
        assertTrue(cached.getFullScript().contains(
                "ALTER TABLE public.altered OWNER TO new_owner"));
    }

    @Test
    void resolveActionsRetainsNoScriptsForUnchangedObjects() {
        PgDatabase oldDb = createDatabase("same_owner", false);
        PgDatabase newDb = createDatabase("same_owner", false);

        var resolved = resolveBoth(oldDb, newDb, new LinkedHashSet<>());

        assertTrue(resolved.actions().isEmpty());
        assertTrue(resolved.alterScripts().isEmpty());
    }

    @Test
    void cachedAlterScriptsProduceByteIdenticalOutput() {
        PgDatabase oldDb = createDatabase("old_owner", true);
        PgDatabase newDb = createDatabase("new_owner", false);
        var settings = new CoreSettings();
        Set<IStatement> toRefresh = new LinkedHashSet<>();

        var resolved = DepcyResolver.resolveActions(
                oldDb, newDb, List.of(), List.of(), toRefresh,
                bothSideObjects(oldDb, newDb), settings);

        var uncachedScript = new SQLScript(settings, ";");
        ActionsToScriptConverter.fillScript(
                uncachedScript, resolved.actions(), toRefresh, oldDb, newDb, List.of());

        var cachedScript = new SQLScript(settings, ";");
        ActionsToScriptConverter.fillScript(
                cachedScript, resolved, toRefresh, oldDb, newDb, List.of());

        assertEquals(uncachedScript.getFullScript(), cachedScript.getFullScript());
        assertTrue(cachedScript.getFullScript().contains(
                "ALTER TABLE public.altered OWNER TO new_owner"));
    }

    @Test
    void cachedSequenceAlterPreservesOwnerBeforeAclOrdering() {
        PgDatabase oldDb = createSequenceDatabase("old_owner", false);
        PgDatabase newDb = createSequenceDatabase("new_owner", true);
        var settings = new CoreSettings();
        Set<IStatement> toRefresh = new LinkedHashSet<>();
        var objects = List.of(
                new DbObject(oldDb.getStatement(ALTERED_SEQUENCE), newDb.getStatement(ALTERED_SEQUENCE)),
                new DbObject(oldDb.getStatement(ALTERED_SEQUENCE_TABLE),
                        newDb.getStatement(ALTERED_SEQUENCE_TABLE)));

        var resolved = DepcyResolver.resolveActions(
                oldDb, newDb, List.of(), List.of(), toRefresh, objects, settings);

        var uncachedScript = new SQLScript(settings, ";");
        ActionsToScriptConverter.fillScript(
                uncachedScript, resolved.actions(), toRefresh, oldDb, newDb, List.of());

        var cachedScript = new SQLScript(settings, ";");
        ActionsToScriptConverter.fillScript(
                cachedScript, resolved, toRefresh, oldDb, newDb, List.of());

        String uncached = uncachedScript.getFullScript();
        String cached = cachedScript.getFullScript();
        int tableOwner = cached.indexOf("ALTER TABLE public.sequence_table OWNER TO new_owner;");
        int owner = cached.indexOf("ALTER SEQUENCE public.altered_seq OWNER TO new_owner;");
        int grant = cached.indexOf("GRANT USAGE ON SEQUENCE public.altered_seq TO old_owner;");
        assertAll(
                () -> assertEquals(uncached, cached),
                () -> assertTrue(tableOwner >= 0 && owner > tableOwner && grant > owner, cached));
    }

    @Test
    void alterScriptsAreDeterministicAcrossRunsAndUnmodifiable() {
        PgDatabase oldDb = createDatabase("old_owner", true);
        PgDatabase newDb = createDatabase("new_owner", false);

        var resolvedFirst = resolveBoth(oldDb, newDb, new LinkedHashSet<>());
        var resolvedSecond = resolveBoth(oldDb, newDb, new LinkedHashSet<>());

        IStatement alteredOld = oldDb.getStatement(ALTERED_TABLE);
        SQLScript first = resolvedFirst.alterScripts().get(alteredOld);
        SQLScript second = resolvedSecond.alterScripts().get(alteredOld);
        assertEquals(first.getFullScript(), second.getFullScript());
        assertThrows(UnsupportedOperationException.class,
                () -> resolvedFirst.alterScripts().clear());
    }

    private static DepcyResolver.ResolvedActions resolveBoth(PgDatabase oldDb, PgDatabase newDb,
            Set<IStatement> toRefresh) {
        return DepcyResolver.resolveActions(
                oldDb, newDb, List.of(), List.of(), toRefresh,
                bothSideObjects(oldDb, newDb), new CoreSettings());
    }

    private static List<DbObject> bothSideObjects(PgDatabase oldDb, PgDatabase newDb) {
        return List.of(
                new DbObject(oldDb.getStatement(ALTERED_TABLE), newDb.getStatement(ALTERED_TABLE)),
                new DbObject(oldDb.getStatement(RECREATED_TABLE), newDb.getStatement(RECREATED_TABLE)));
    }

    private static PgDatabase createDatabase(String alteredOwner, boolean recreatedAppendOnly) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");

        var altered = new PgSimpleTable("altered");
        altered.setOwner(alteredOwner);
        schema.addChild(altered);

        var recreated = new PgSimpleTable("recreated");
        if (recreatedAppendOnly) {
            recreated.addOption("appendonly", "true");
        }
        schema.addChild(recreated);
        return db;
    }

    private static PgDatabase createSequenceDatabase(String owner, boolean grantOldOwner) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");

        var table = new PgSimpleTable("sequence_table");
        table.setOwner(owner);
        schema.addChild(table);

        var column = new PgColumn("id");
        column.setType("integer");
        table.addColumn(column);

        var sequence = new PgSequence("altered_seq");
        sequence.setOwner(owner);
        sequence.setOwnedBy(ALTERED_SEQUENCE_COLUMN);
        if (grantOldOwner) {
            sequence.addPrivilege(new PgPrivilege(
                    "GRANT", "USAGE", "SEQUENCE public.altered_seq", "old_owner", false));
        }
        schema.addChild(sequence);
        return db;
    }

    private static List<String> actionTrace(Set<ActionContainer> actions) {
        return actions.stream()
                .map(action -> action.getState() + "|" + action.getOldObj().getQualifiedName())
                .toList();
    }
}
