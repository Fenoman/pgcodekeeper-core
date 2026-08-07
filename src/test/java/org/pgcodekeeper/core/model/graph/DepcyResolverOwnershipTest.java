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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.dependencieslist.Dependency;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

class DepcyResolverOwnershipTest {

    private static final ObjectReference ALPHA_TABLE =
            new ObjectReference("public", "alpha", DbObjType.TABLE);
    private static final ObjectReference BETA_TABLE =
            new ObjectReference("public", "beta", DbObjType.TABLE);
    private static final ObjectReference GAMMA_TABLE =
            new ObjectReference("public", "gamma", DbObjType.TABLE);

    @Test
    void resolverGraphsBorrowExactInputDatabases() {
        PgDatabase oldDb = createDatabase(false);
        PgDatabase newDb = createDatabase(true);

        var resolver = new DepcyResolver(
                oldDb, newDb, new CoreSettings(), new LinkedHashSet<>());

        assertSame(oldDb, resolver.getOldGraphSource());
        assertSame(newDb, resolver.getNewGraphSource());
    }

    @Test
    void resolverPreservesActionOrderStarterAndModels() {
        PgDatabase oldDb = createDatabase(false);
        PgDatabase newDb = createDatabase(true);
        ModelSnapshot oldBefore = snapshot(oldDb);
        ModelSnapshot newBefore = snapshot(newDb);
        var settings = new CoreSettings();
        Set<IStatement> toRefresh = new LinkedHashSet<>();

        Set<ActionContainer> actions = DepcyResolver.resolve(
                oldDb,
                newDb,
                List.of(),
                List.of(),
                toRefresh,
                List.of(
                        new DbObject(null, newDb.getStatement(
                                new ObjectReference("public", "beta", DbObjType.TABLE))),
                        new DbObject(null, newDb.getStatement(
                                new ObjectReference("public", "gamma", DbObjType.TABLE)))),
                settings);

        assertEquals(List.of(
                "CREATE|public.alpha|public.alpha|public.beta",
                "CREATE|public.beta|public.beta|-",
                "CREATE|public.gamma|public.gamma|-"), actionTrace(actions));

        Set<ActionContainer> reverseSelectionActions = DepcyResolver.resolve(
                oldDb,
                newDb,
                List.of(),
                List.of(),
                new LinkedHashSet<>(),
                List.of(
                        new DbObject(null, newDb.getStatement(
                                new ObjectReference("public", "gamma", DbObjType.TABLE))),
                        new DbObject(null, newDb.getStatement(
                                new ObjectReference("public", "beta", DbObjType.TABLE)))),
                settings);
        assertEquals(List.of(
                "CREATE|public.alpha|public.alpha|public.gamma",
                "CREATE|public.gamma|public.gamma|-",
                "CREATE|public.beta|public.beta|-"), actionTrace(reverseSelectionActions));
        assertEquals(oldBefore, snapshot(oldDb));
        assertEquals(newBefore, snapshot(newDb));

        var script = new SQLScript(settings, ";");
        ActionsToScriptConverter.fillScript(
                script, actions, toRefresh, oldDb, newDb, List.of());
        assertTrue(script.getFullScript().contains("CREATE TABLE public.alpha"));
        assertEquals(oldBefore, snapshot(oldDb));
        assertEquals(newBefore, snapshot(newDb));
    }

    @Test
    void duplicateCustomDependencyDoesNotDuplicateActionsOrReplaceStarter() {
        PgDatabase oldDb = createDatabase(false);
        PgDatabase newDb = createDatabase(true);
        ModelSnapshot oldBefore = snapshot(oldDb);
        ModelSnapshot newBefore = snapshot(newDb);
        Dependency custom = new Dependency(BETA_TABLE, GAMMA_TABLE);

        Set<ActionContainer> actions = DepcyResolver.resolve(
                oldDb,
                newDb,
                List.of(),
                List.of(custom, custom),
                new LinkedHashSet<>(),
                List.of(new DbObject(null, newDb.getStatement(BETA_TABLE))),
                new CoreSettings());

        assertEquals(List.of(
                "CREATE|public.alpha|public.alpha|public.beta",
                "CREATE|public.gamma|public.gamma|public.beta",
                "CREATE|public.beta|public.beta|-"), actionTrace(actions));
        assertEquals(oldBefore, snapshot(oldDb));
        assertEquals(newBefore, snapshot(newDb));
    }

    @Test
    void graphDerivedAlterResolutionPreservesBorrowedModelsAndHashes() {
        PgDatabase oldDb = createDependentAlterDatabase(true, true, "old_owner");
        PgDatabase newDb = createDependentAlterDatabase(false, false, "new_owner");
        ModelSnapshot oldBefore = snapshot(oldDb);
        ModelSnapshot newBefore = snapshot(newDb);
        var settings = new CoreSettings();
        Set<IStatement> toRefresh = new LinkedHashSet<>();

        DepcyGraph copyControl = new DepcyGraph(oldDb);
        IStatement copiedDependent = GraphUtils.reverse(
                        copyControl, oldDb.getStatement(ALPHA_TABLE)).stream()
                .filter(statement -> "public.beta".equals(statement.getQualifiedName()))
                .findFirst()
                .orElseThrow();
        assertNotSame(oldDb.getStatement(BETA_TABLE), copiedDependent);

        Set<ActionContainer> actions = DepcyResolver.resolve(
                oldDb,
                newDb,
                List.of(),
                List.of(),
                toRefresh,
                List.of(new DbObject(oldDb.getStatement(ALPHA_TABLE), null)),
                settings);

        assertEquals(List.of(
                "ALTER|public.beta|public.beta|public.alpha",
                "DROP|public.alpha|public.alpha|-"), actionTrace(actions));
        ActionContainer graphDerivedAlter = actions.iterator().next();
        assertSame(oldDb.getStatement(BETA_TABLE), graphDerivedAlter.getOldObj());
        assertSame(newDb.getStatement(BETA_TABLE), graphDerivedAlter.getNewObj());
        assertSame(oldDb.getStatement(ALPHA_TABLE), graphDerivedAlter.getStarter());
        assertEquals(oldBefore, snapshot(oldDb));
        assertEquals(newBefore, snapshot(newDb));

        var script = new SQLScript(settings, ";");
        ActionsToScriptConverter.fillScript(
                script, actions, toRefresh, oldDb, newDb, List.of());
        assertTrue(script.getFullScript().contains(
                "ALTER TABLE public.beta OWNER TO new_owner"));
        assertTrue(script.getFullScript().contains("DROP TABLE public.alpha"));
        assertEquals(oldBefore, snapshot(oldDb));
        assertEquals(newBefore, snapshot(newDb));
    }

    private static PgDatabase createDatabase(boolean withTables) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");
        if (!withTables) {
            return db;
        }

        var alpha = new PgSimpleTable("alpha");
        schema.addChild(alpha);

        var beta = new PgSimpleTable("beta");
        beta.addDependency(ALPHA_TABLE);
        schema.addChild(beta);

        var gamma = new PgSimpleTable("gamma");
        gamma.addDependency(ALPHA_TABLE);
        schema.addChild(gamma);
        return db;
    }

    private static PgDatabase createDependentAlterDatabase(boolean withAlpha,
            boolean betaDependsOnAlpha, String betaOwner) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");

        if (withAlpha) {
            schema.addChild(new PgSimpleTable("alpha"));
        }

        var beta = new PgSimpleTable("beta");
        beta.setOwner(betaOwner);
        if (betaDependsOnAlpha) {
            beta.addDependency(ALPHA_TABLE);
        }
        schema.addChild(beta);
        return db;
    }

    private static List<String> actionTrace(Set<ActionContainer> actions) {
        return actions.stream().map(action -> action.getState()
                + "|" + path(action.getOldObj())
                + "|" + path(action.getNewObj())
                + "|" + path(action.getStarter())).toList();
    }

    private static String path(IStatement statement) {
        return statement == null ? "-" : statement.getQualifiedName();
    }

    private static String identityPath(IStatement statement) {
        return statement.getStatementType() + ":"
                + (statement.getStatementType() == DbObjType.DATABASE
                        ? "<database>" : statement.getQualifiedName());
    }

    private static ModelSnapshot snapshot(IDatabase db) {
        List<StatementSnapshot> statements = allStatements(db)
                .map(statement -> new StatementSnapshot(
                        identityPath(statement),
                        statement.getClass().getName(),
                        System.identityHashCode(statement),
                        statement.hashCode(),
                        statement.getParent() == null ? "-" : identityPath(statement.getParent()),
                        childPaths(statement),
                        statement.getDependencies().stream().map(ObjectReference::toString).toList()))
                .toList();

        Map<String, List<String>> references = new TreeMap<>();
        db.getObjReferences().forEach((file, locations) -> references.put(file,
                locations.stream().map(DepcyResolverOwnershipTest::referenceTrace).toList()));
        return new ModelSnapshot(statements, references);
    }

    private static Stream<? extends IStatement> allStatements(IDatabase db) {
        return Stream.concat(Stream.of(db), db.getDescendants().flatMap(ITable::columnAdder));
    }

    private static List<String> childPaths(IStatement statement) {
        Map<String, IStatement> children = new LinkedHashMap<>();
        if (statement instanceof ITable table) {
            table.getColumns().forEach(child -> children.put(identityPath(child), child));
        }
        statement.getChildren().forEach(child -> children.put(identityPath(child), child));
        return new ArrayList<>(children.keySet());
    }

    private static String referenceTrace(ObjectLocation location) {
        return location.getLocationType() + "|" + location.getObjectReference()
                + "|" + location.getFilePath() + "|" + location.getOffset();
    }

    private record ModelSnapshot(List<StatementSnapshot> statements,
            Map<String, List<String>> references) {
    }

    private record StatementSnapshot(String path, String className, int identity,
            int hash, String parentPath, List<String> childPaths,
            List<String> dependencies) {
    }
}
