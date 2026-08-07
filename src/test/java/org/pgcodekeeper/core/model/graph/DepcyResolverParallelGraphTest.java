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
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.jgrapht.graph.DefaultEdge;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Verifies that building the OLD and NEW dependency graphs concurrently
 * (see {@link DepcyResolver#buildDependencyGraphs}) produces results identical
 * to a serial build and that repeated parallel resolution is deterministic and
 * never mutates the borrowed input models.
 */
class DepcyResolverParallelGraphTest {

    private static final ObjectReference ALPHA_TABLE =
            new ObjectReference("public", "alpha", DbObjType.TABLE);
    private static final ObjectReference ALPHA_ID =
            new ObjectReference("public", "alpha", "id", DbObjType.COLUMN);
    private static final ObjectReference BETA_TABLE =
            new ObjectReference("public", "beta", DbObjType.TABLE);
    private static final ObjectReference GAMMA_TABLE =
            new ObjectReference("public", "gamma", DbObjType.TABLE);
    private static final ObjectReference CYCLE_FUNCTION =
            new ObjectReference("public", "cycle_fn()", DbObjType.FUNCTION);

    private static final int REPEATS = 200;

    /**
     * The parallel build must yield the same vertices and edges as a serial
     * build for both sides, borrow the exact input models, and still perform
     * the function-column cycle removal on each thread.
     */
    @Test
    void parallelBuildMatchesSerialGraphContent() {
        PgDatabase oldDb = createCycleModel();
        PgDatabase newDb = createCycleModel();

        DepcyGraph serialOld = new DepcyGraph(
                oldDb, false, DepcyGraph.Ownership.BORROW_READ_ONLY);
        DepcyGraph serialNew = new DepcyGraph(
                newDb, false, DepcyGraph.Ownership.BORROW_READ_ONLY);
        List<String> serialOldVertices = vertexTrace(serialOld);
        List<String> serialOldEdges = edgeTrace(serialOld);
        List<String> serialNewVertices = vertexTrace(serialNew);
        List<String> serialNewEdges = edgeTrace(serialNew);

        DepcyResolver.DepcyGraphs parallel =
                DepcyResolver.buildDependencyGraphs(oldDb, newDb);

        assertSame(oldDb, parallel.oldGraph().getDb());
        assertSame(newDb, parallel.newGraph().getDb());

        assertEquals(serialOldVertices, vertexTrace(parallel.oldGraph()));
        assertEquals(serialOldEdges, edgeTrace(parallel.oldGraph()));
        assertEquals(serialNewVertices, vertexTrace(parallel.newGraph()));
        assertEquals(serialNewEdges, edgeTrace(parallel.newGraph()));

        // cycle removal actually happened on both freshly built graphs
        assertFalse(edgeTrace(parallel.oldGraph()).contains(
                "FUNCTION:public.cycle_fn() -> COLUMN:public.alpha.id"));
        assertFalse(edgeTrace(parallel.newGraph()).contains(
                "FUNCTION:public.cycle_fn() -> COLUMN:public.alpha.id"));
    }

    /**
     * Repeated resolution over a rich NEW graph (create direction) must be
     * byte-stable and must not touch either input model.
     */
    @Test
    void parallelCreateResolutionIsDeterministicAndSideEffectFree() {
        PgDatabase oldDb = emptyModel();
        PgDatabase newDb = tablesModel();
        ModelSnapshot oldBefore = snapshot(oldDb);
        ModelSnapshot newBefore = snapshot(newDb);

        List<String> first = actionTrace(resolveCreate(oldDb, newDb));
        assertFalse(first.isEmpty());

        for (int i = 0; i < REPEATS; i++) {
            assertEquals(first, actionTrace(resolveCreate(oldDb, newDb)), "create run " + i);
        }

        assertEquals(oldBefore, snapshot(oldDb));
        assertEquals(newBefore, snapshot(newDb));
    }

    /**
     * Repeated resolution over a rich OLD graph (drop direction) exercises the
     * worker-thread graph and must be byte-stable without mutating either model.
     */
    @Test
    void parallelDropResolutionIsDeterministicAndSideEffectFree() {
        PgDatabase oldDb = tablesModel();
        PgDatabase newDb = emptyModel();
        ModelSnapshot oldBefore = snapshot(oldDb);
        ModelSnapshot newBefore = snapshot(newDb);

        List<String> first = actionTrace(resolveDropAlpha(oldDb, newDb));
        assertFalse(first.isEmpty());

        for (int i = 0; i < REPEATS; i++) {
            assertEquals(first, actionTrace(resolveDropAlpha(oldDb, newDb)), "drop run " + i);
        }

        assertEquals(oldBefore, snapshot(oldDb));
        assertEquals(newBefore, snapshot(newDb));
    }

    private static Set<ActionContainer> resolveCreate(PgDatabase oldDb, PgDatabase newDb) {
        return DepcyResolver.resolve(oldDb, newDb, List.of(), List.of(),
                new LinkedHashSet<>(),
                List.of(new DbObject(null, newDb.getStatement(BETA_TABLE)),
                        new DbObject(null, newDb.getStatement(GAMMA_TABLE))),
                new CoreSettings());
    }

    private static Set<ActionContainer> resolveDropAlpha(PgDatabase oldDb, PgDatabase newDb) {
        return DepcyResolver.resolve(oldDb, newDb, List.of(), List.of(),
                new LinkedHashSet<>(),
                List.of(new DbObject(oldDb.getStatement(ALPHA_TABLE), null)),
                new CoreSettings());
    }

    private static PgDatabase emptyModel() {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");
        return db;
    }

    private static PgDatabase tablesModel() {
        var db = emptyModel();
        var schema = db.getSchema("public");

        schema.addChild(new PgSimpleTable("alpha"));

        var beta = new PgSimpleTable("beta");
        beta.addDependency(ALPHA_TABLE);
        schema.addChild(beta);

        var gamma = new PgSimpleTable("gamma");
        gamma.addDependency(ALPHA_TABLE);
        schema.addChild(gamma);
        return db;
    }

    private static PgDatabase createCycleModel() {
        var db = emptyModel();
        var schema = db.getSchema("public");

        var alpha = new PgSimpleTable("alpha");
        PgColumn id = column("id");
        id.addDependency(CYCLE_FUNCTION);
        alpha.addColumn(id);
        alpha.addColumn(column("code"));
        schema.addChild(alpha);

        var beta = new PgSimpleTable("beta");
        beta.addColumn(column("id"));
        PgColumn alphaId = column("alpha_id");
        alphaId.addDependency(ALPHA_ID);
        beta.addColumn(alphaId);
        schema.addChild(beta);

        var function = new PgFunction("cycle_fn");
        function.setLanguageCost("sql", null);
        function.setReturns("integer");
        function.setBody("$$SELECT 1$$");
        function.addDependency(ALPHA_ID);
        function.addDependency(ALPHA_TABLE);
        schema.addChild(function);
        return db;
    }

    private static PgColumn column(String name) {
        var column = new PgColumn(name);
        column.setType("integer");
        return column;
    }

    private static List<String> actionTrace(Set<ActionContainer> actions) {
        return actions.stream().map(action -> action.getState()
                + "|" + path(action.getOldObj())
                + "|" + path(action.getNewObj())
                + "|" + path(action.getStarter())).toList();
    }

    private static List<String> vertexTrace(DepcyGraph graph) {
        return pathTrace(graph.getGraph().vertexSet());
    }

    private static List<String> edgeTrace(DepcyGraph graph) {
        List<String> trace = new ArrayList<>();
        for (DefaultEdge edge : graph.getGraph().edgeSet()) {
            trace.add(identityPath(graph.getGraph().getEdgeSource(edge)) + " -> "
                    + identityPath(graph.getGraph().getEdgeTarget(edge)));
        }
        return trace;
    }

    private static List<String> pathTrace(Iterable<? extends IStatement> statements) {
        List<String> trace = new ArrayList<>();
        statements.forEach(statement -> trace.add(identityPath(statement)));
        return trace;
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
                locations.stream().map(DepcyResolverParallelGraphTest::referenceTrace).toList()));
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
        return List.copyOf(children.keySet());
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
