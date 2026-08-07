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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
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
import org.pgcodekeeper.core.dependencieslist.Dependency;

class DepcyGraphOwnershipTest {

    private static final ObjectReference ALPHA_TABLE =
            new ObjectReference("public", "alpha", DbObjType.TABLE);
    private static final ObjectReference ALPHA_ID =
            new ObjectReference("public", "alpha", "id", DbObjType.COLUMN);
    private static final ObjectReference BETA_TABLE =
            new ObjectReference("public", "beta", DbObjType.TABLE);
    private static final ObjectReference BETA_ALPHA_ID =
            new ObjectReference("public", "beta", "alpha_id", DbObjType.COLUMN);
    private static final ObjectReference CYCLE_FUNCTION =
            new ObjectReference("public", "cycle_fn()", DbObjType.FUNCTION);

    private static final List<String> EXPECTED_VERTICES = List.of(
            "DATABASE:<database>",
            "SCHEMA:public",
            "TABLE:public.alpha",
            "COLUMN:public.alpha.id",
            "COLUMN:public.alpha.code",
            "TABLE:public.beta",
            "COLUMN:public.beta.id",
            "COLUMN:public.beta.alpha_id");

    private static final List<String> EXPECTED_EDGES = List.of(
            "SCHEMA:public -> DATABASE:<database>",
            "TABLE:public.alpha -> SCHEMA:public",
            "COLUMN:public.alpha.id -> TABLE:public.alpha",
            "COLUMN:public.alpha.code -> TABLE:public.alpha",
            "TABLE:public.beta -> SCHEMA:public",
            "COLUMN:public.beta.id -> TABLE:public.beta",
            "COLUMN:public.beta.alpha_id -> TABLE:public.beta",
            "COLUMN:public.beta.alpha_id -> COLUMN:public.alpha.id",
            "TABLE:public.beta -> TABLE:public.alpha");

    @Test
    void publicConstructorCopiesAndPreservesOrderedGraphBehavior() {
        PgDatabase source = createModel();
        ModelSnapshot before = snapshot(source);

        DepcyGraph graph = new DepcyGraph(source);
        Dependency custom = new Dependency(BETA_TABLE, ALPHA_TABLE);
        graph.addCustomDepcies(List.of(custom, custom));

        assertNotSame(source, graph.getDb());
        assertEquals(EXPECTED_VERTICES, vertexTrace(graph));
        assertEquals(EXPECTED_EDGES, edgeTrace(graph));
        assertEquals(List.of(
                "SCHEMA:public",
                "TABLE:public.alpha",
                "COLUMN:public.alpha.id",
                "TABLE:public.beta"),
                pathTrace(GraphUtils.forward(graph, graph.getDb().getStatement(BETA_ALPHA_ID))));
        assertEquals(List.of(
                "COLUMN:public.beta.alpha_id",
                "COLUMN:public.beta.id",
                "TABLE:public.beta",
                "COLUMN:public.alpha.code",
                "COLUMN:public.alpha.id"),
                pathTrace(GraphUtils.reverse(graph, graph.getDb().getStatement(ALPHA_TABLE))));

        assertEquals(before, snapshot(source));
    }

    @Test
    void borrowedGraphUsesSourceIdentitiesWithoutChangingGraphBehavior() {
        PgDatabase source = createModel();
        ModelSnapshot before = snapshot(source);
        Dependency custom = new Dependency(BETA_TABLE, ALPHA_TABLE);

        DepcyGraph copy = new DepcyGraph(source);
        copy.addCustomDepcies(List.of(custom, custom));
        DepcyGraph borrowed = new DepcyGraph(
                source, false, DepcyGraph.Ownership.BORROW_READ_ONLY);
        borrowed.addCustomDepcies(List.of(custom, custom));

        assertSame(source, borrowed.getDb());
        Map<String, IStatement> sourceByPath = allStatements(source)
                .collect(Collectors.toMap(DepcyGraphOwnershipTest::path,
                        Function.identity(), (left, right) -> left, LinkedHashMap::new));
        borrowed.getGraph().vertexSet().forEach(vertex ->
                assertSame(sourceByPath.get(path(vertex)), vertex, path(vertex)));

        assertEquals(vertexTrace(copy), vertexTrace(borrowed));
        assertEquals(edgeTrace(copy), edgeTrace(borrowed));
        assertEquals(
                pathTrace(GraphUtils.forward(copy, copy.getDb().getStatement(BETA_ALPHA_ID))),
                pathTrace(GraphUtils.forward(borrowed, source.getStatement(BETA_ALPHA_ID))));
        assertEquals(
                pathTrace(GraphUtils.reverse(copy, copy.getDb().getStatement(ALPHA_TABLE))),
                pathTrace(GraphUtils.reverse(borrowed, source.getStatement(ALPHA_TABLE))));
        assertEquals(before, snapshot(source));
    }

    @Test
    void borrowedGraphPreservesFunctionColumnCycleRemovalWithoutMutation() {
        PgDatabase source = createCycleModel();
        ModelSnapshot before = snapshot(source);

        DepcyGraph copy = new DepcyGraph(source);
        DepcyGraph borrowed = new DepcyGraph(
                source, false, DepcyGraph.Ownership.BORROW_READ_ONLY);

        assertEquals(vertexTrace(copy), vertexTrace(borrowed));
        assertEquals(edgeTrace(copy), edgeTrace(borrowed));

        assertFunctionColumnCycleWasRemoved(copy);
        assertFunctionColumnCycleWasRemoved(borrowed);
        assertEquals(
                pathTrace(GraphUtils.forward(copy, copy.getDb().getStatement(ALPHA_ID))),
                pathTrace(GraphUtils.forward(borrowed, source.getStatement(ALPHA_ID))));
        assertEquals(
                pathTrace(GraphUtils.reverse(copy, copy.getDb().getStatement(CYCLE_FUNCTION))),
                pathTrace(GraphUtils.reverse(borrowed, source.getStatement(CYCLE_FUNCTION))));
        assertEquals(before, snapshot(source));
    }

    private static PgDatabase createModel() {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");

        var alpha = new PgSimpleTable("alpha");
        alpha.addColumn(column("id"));
        alpha.addColumn(column("code"));
        schema.addChild(alpha);

        var beta = new PgSimpleTable("beta");
        beta.addColumn(column("id"));
        PgColumn alphaId = column("alpha_id");
        alphaId.addDependency(ALPHA_ID);
        beta.addColumn(alphaId);
        schema.addChild(beta);

        return db;
    }

    private static PgDatabase createCycleModel() {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");

        var table = new PgSimpleTable("alpha");
        PgColumn id = column("id");
        id.addDependency(CYCLE_FUNCTION);
        table.addColumn(id);
        schema.addChild(table);

        var function = new PgFunction("cycle_fn");
        function.setLanguageCost("sql", null);
        function.setReturns("integer");
        function.setBody("$$SELECT 1$$");
        function.addDependency(ALPHA_ID);
        function.addDependency(ALPHA_TABLE);
        schema.addChild(function);
        return db;
    }

    private static void assertFunctionColumnCycleWasRemoved(DepcyGraph graph) {
        IStatement function = graph.getDb().getStatement(CYCLE_FUNCTION);
        IStatement table = graph.getDb().getStatement(ALPHA_TABLE);
        IStatement column = graph.getDb().getStatement(ALPHA_ID);

        assertEquals(Set.of(ALPHA_ID, ALPHA_TABLE), function.getDependencies());
        assertEquals(Set.of(CYCLE_FUNCTION), column.getDependencies());
        assertFalse(graph.getGraph().containsEdge(function, column));
        assertFalse(graph.getGraph().containsEdge(function, table));
        assertTrue(graph.getGraph().containsEdge(column, function));
    }

    private static PgColumn column(String name) {
        var column = new PgColumn(name);
        column.setType("integer");
        return column;
    }

    private static List<String> vertexTrace(DepcyGraph graph) {
        return pathTrace(graph.getGraph().vertexSet());
    }

    private static List<String> edgeTrace(DepcyGraph graph) {
        List<String> trace = new ArrayList<>();
        for (DefaultEdge edge : graph.getGraph().edgeSet()) {
            trace.add(path(graph.getGraph().getEdgeSource(edge)) + " -> "
                    + path(graph.getGraph().getEdgeTarget(edge)));
        }
        return trace;
    }

    private static List<String> pathTrace(Iterable<? extends IStatement> statements) {
        List<String> trace = new ArrayList<>();
        statements.forEach(statement -> trace.add(path(statement)));
        return trace;
    }

    private static String path(IStatement statement) {
        return statement.getStatementType() + ":"
                + (statement.getStatementType() == DbObjType.DATABASE
                        ? "<database>" : statement.getQualifiedName());
    }

    private static ModelSnapshot snapshot(IDatabase db) {
        List<StatementSnapshot> statements = allStatements(db)
                .map(statement -> new StatementSnapshot(
                        path(statement),
                        statement.getClass().getName(),
                        System.identityHashCode(statement),
                        statement.hashCode(),
                        statement.getParent() == null ? "-" : path(statement.getParent()),
                        childPaths(statement),
                        statement.getDependencies().stream().map(ObjectReference::toString).toList()))
                .toList();

        Map<String, List<String>> references = new TreeMap<>();
        db.getObjReferences().forEach((file, locations) -> references.put(file,
                locations.stream().map(DepcyGraphOwnershipTest::referenceTrace).toList()));
        return new ModelSnapshot(statements, references);
    }

    private static Stream<? extends IStatement> allStatements(IDatabase db) {
        return Stream.concat(Stream.of(db), db.getDescendants().flatMap(ITable::columnAdder));
    }

    private static List<String> childPaths(IStatement statement) {
        Map<String, IStatement> children = new LinkedHashMap<>();
        if (statement instanceof ITable table) {
            table.getColumns().forEach(child -> children.put(path(child), child));
        }
        statement.getChildren().forEach(child -> children.put(path(child), child));
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
