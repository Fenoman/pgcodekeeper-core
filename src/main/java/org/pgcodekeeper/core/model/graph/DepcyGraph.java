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

import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.EdgeReversedGraph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.dependencieslist.Dependency;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Database dependency graph for managing object relationships and dependencies.
 * Builds directed graph of database objects with support for cycle detection and resolution.
 * Handles foreign key relationships, inheritance, and partitioning dependencies.
 */
public final class DepcyGraph {

    enum Ownership {
        COPY,
        BORROW_READ_ONLY
    }

    private static final Logger LOG = LoggerFactory.getLogger(DepcyGraph.class);

    private static final String REMOVE_DEP = Messages.DepcyGraph_log_remove_deps;

    private final Graph<IStatement, DefaultEdge> graph =
            new SimpleDirectedGraph<>(DefaultEdge.class);

    private final EdgeReversedGraph<IStatement, DefaultEdge> reversedGraph =
            new EdgeReversedGraph<>(graph);

    /**
     * Gets the dependency graph.
     * Graph direction: dependent object → dependency (source → target)
     *
     * @return the dependency graph
     */
    public Graph<IStatement, DefaultEdge> getGraph() {
        return graph;
    }

    public EdgeReversedGraph<IStatement, DefaultEdge> getReversedGraph() {
        return reversedGraph;
    }

    private final IDatabase db;

    /**
     * Returns the database used as the graph source. Public constructors own a
     * deep copy. Package-scoped callers may explicitly borrow the source under
     * the {@link Ownership#BORROW_READ_ONLY} contract.<br>
     * <b>Do not modify</b> any graph vertex in either mode: vertices are hash
     * keys, so changing them breaks the graph and resolver sets/maps.
     *
     * @return owned copy or read-only borrowed database, according to ownership
     */
    public IDatabase getDb() {
        return db;
    }

    /**
     * Creates a dependency graph from the database schema.
     *
     * @param graphSrc the source database to build graph from
     */
    public DepcyGraph(IDatabase graphSrc) {
        this(graphSrc, false);
    }

    /**
     * Creates a dependency graph with optional graph reduction.
     *
     * @param graphSrc    the source database to build graph from
     * @param reduceGraph if true, merge column nodes into table nodes
     */
    public DepcyGraph(IDatabase graphSrc, boolean reduceGraph) {
        this(graphSrc, reduceGraph, Ownership.COPY);
    }

    DepcyGraph(IDatabase graphSrc, boolean reduceGraph, Ownership ownership) {
        db = ownership == Ownership.COPY ? (IDatabase) graphSrc.deepCopy() : graphSrc;
        create();
        removeCycles();

        if (reduceGraph) {
            reduce();
        }
    }

    private void create() {
        graph.addVertex(db);

        // first pass: object tree
        db.getDescendants().flatMap(ITable::columnAdder).forEach(st -> {
            graph.addVertex(st);
            graph.addEdge(st, st.getParent());
        });


        // second pass: dependency graph
        db.getDescendants().flatMap(ITable::columnAdder).forEach(st -> {
            processDeps(st);
            if (st instanceof IConstraintFk fk) {
                createFkeyToUnique(fk);
            } else if (st instanceof PgColumn col) {
                AbstractStatement tbl = col.getParent();
                if (st.getParent() instanceof PgPartitionTable) {
                    createChildColToPartTblCol((PgPartitionTable) tbl, col);
                } else {
                    // Creating the connection between the column of a inherit
                    // table and the columns of its child tables.

                    IColumn parentTblCol = col.getParentCol((PgAbstractTable) tbl);
                    if (parentTblCol != null) {
                        graph.addEdge(col, parentTblCol);
                    }
                }
            }
        });
    }

    private void reduce() {
        List<Pair<IStatement, IStatement>> newEdges = new ArrayList<>();
        for (DefaultEdge edge : graph.edgeSet()) {
            var source = graph.getEdgeSource(edge);
            var target = graph.getEdgeTarget(edge);
            boolean changeEdge = false;
            if (source.getStatementType() == DbObjType.COLUMN) {
                changeEdge = true;
                source = source.getParent();
            }
            if (target.getStatementType() == DbObjType.COLUMN) {
                changeEdge = true;
                target = target.getParent();
            }
            if (changeEdge && !source.equals(target)) {
                newEdges.add(new Pair<>(source, target));
            }
        }
        for (var edge : newEdges) {
            graph.addEdge(edge.getFirst(), edge.getSecond());
        }

        List<IStatement> toRemove = new ArrayList<>();
        for (var st : graph.vertexSet()) {
            if (st.getStatementType() == DbObjType.COLUMN) {
                toRemove.add(st);
            }
        }
        graph.removeAllVertices(toRemove);
    }

    /**
     * Breaks a dependency cycle between a routine and a non-routine object by cutting the edge that leaves the
     * routine.
     * <p>
     * The graph mixes two kinds of edge. One kind the server itself records in {@code pg_depend} and enforces: a view
     * over a table, an index over a column, a key over the index backing it. The other kind is read out of routine
     * bodies, which PostgreSQL deliberately does not track - a body may name an object that does not exist yet, and
     * dropping that object leaves the routine in place and broken. Only the second kind can be cut without losing
     * ordering the server actually demands, and an edge out of a routine that closes a cycle with an object of any
     * other kind is of that kind: the cycle means the object already depends on the routine, and no object
     * PostgreSQL pins a routine to can itself be built on that routine.
     * <p>
     * Cutting only the edges to columns, as this did before, left the plain {@code VIEW} case alone: a view that calls
     * a function whose body reads that same view. The script then created the view before the function and dropped the
     * function before the view, and the server rejected both.
     * <p>
     * A cycle whose other end is itself a routine is left standing. There neither direction is enforced - bodies
     * resolve at call time, so mutually recursive routines can be created in either order - and the cycle is worth
     * keeping: it is what {@code DepcyFinder} reports back to a caller asking what an object depends on.
     * <p>
     * The one cycle this cannot rescue is a routine reached through a signature rather than a body - a domain whose
     * CHECK calls a function that takes that same domain as an argument. PostgreSQL cannot build that pair in a single
     * pass either, so no edge choice here produces a working script.
     */
    private void removeCycles() {
        CycleDetector<IStatement, DefaultEdge> detector = new CycleDetector<>(graph);

        for (var st : detector.findCycles()) {
            if (!(st instanceof PgAbstractFunction)) {
                continue;
            }

            for (var vertex : detector.findCyclesContainingVertex(st)) {
                if (vertex.equals(st.getParent()) || vertex instanceof PgAbstractFunction) {
                    // the containment edge to the own schema is structural, never a body reference;
                    // and between two routines neither direction is enforced, so there is nothing to fix
                    continue;
                }

                if (graph.removeEdge(st, vertex) != null) {
                    var msg = REMOVE_DEP.formatted(st.getQualifiedName(), vertex.getQualifiedName());
                    LOG.info(msg);
                }

                if (vertex.getStatementType() == DbObjType.COLUMN) {
                    // a body that reads a column reads its table as well, and that edge is just as weak
                    var table = vertex.getParent();
                    if (graph.removeEdge(st, table) != null) {
                        var msg = REMOVE_DEP.formatted(st.getQualifiedName(), table.getQualifiedName());
                        LOG.info(msg);
                    }
                }
            }
        }
    }

    private void processDeps(IStatement st) {
        for (ObjectReference dep : st.getDependencies()) {
            IStatement depSt = db.getStatement(dep);
            if (depSt != null && !st.equals(depSt)) {
                graph.addEdge(st, depSt);
            }
        }
    }

    /**
     * The only way to find this depcy is to compare refcolumns against all existing unique
     * contraints/keys in reftable.
     * Unfortunately they might not exist at the stage where {@link AbstractStatement#getDependencies()}
     * are populated so we have to defer their lookup until here.
     */
    private void createFkeyToUnique(IConstraintFk con) {
        Collection<String> refs = con.getForeignColumns();
        if (refs.isEmpty()) {
            return;
        }

        IStatement cont = db.getStatement(
                new ObjectReference(con.getForeignSchema(), con.getForeignTable(), DbObjType.TABLE));

        if (cont instanceof IStatementContainer c) {
            for (IStatement refCon : c.getChildrenByType(DbObjType.CONSTRAINT)) {
                if (refCon instanceof IConstraintPk fkCon && canBackForeignKey(refs, fkCon.getColumns())) {
                    graph.addEdge(con, refCon);
                }
            }
            for (IStatement ref : c.getChildrenByType(DbObjType.INDEX)) {
                var refInd = (IIndex) ref;
                if (refInd.isUnique() && refInd.canBackForeignKey(refs)) {
                    graph.addEdge(con, refInd);
                }
            }
        }
    }

    /**
     * Tells whether a primary key or unique constraint over {@code keyColumns} can back a foreign key that references
     * {@code refs}.
     * <p>
     * The server pairs the two column lists up in any order - PostgreSQL does so in {@code transformFkeyCheckAttrs} -
     * so {@code FOREIGN KEY (x, y) REFERENCES p (b, a)} is a legal way to reference a key over {@code (a, b)}.
     * Comparing the lists position by position would declare that pair unrelated, leave the edge out of the graph and
     * cost the migration the DROP/CREATE pair that has to bracket a recreated key.
     *
     * @param refs       the columns named on the REFERENCES side of a foreign key
     * @param keyColumns the columns of the candidate primary key or unique constraint
     * @return true if the constraint is a candidate backing for such a key
     */
    private static boolean canBackForeignKey(Collection<String> refs, Collection<String> keyColumns) {
        if (refs.size() != keyColumns.size()) {
            return false;
        }
        List<String> sortedRefs = new ArrayList<>(refs);
        List<String> sortedKey = new ArrayList<>(keyColumns);
        Collections.sort(sortedRefs);
        Collections.sort(sortedKey);
        return sortedRefs.equals(sortedKey);
    }

    /**
     * Creates the connection between the column of a partitioned table and the
     * columns of its sections (child tables).
     * <br />
     * Partitioned tables cannot use the inheritance mechanism, as in simple tables.
     */
    private void createChildColToPartTblCol(PgPartitionTable tbl, PgColumn col) {
        for (Inherits in : tbl.getInherits()) {
            IStatement parentTbl = db.getStatement(new ObjectReference(in.key(), in.value(), DbObjType.TABLE));
            if (parentTbl == null) {
                var msg = Messages.DepcyGraph_log_no_such_table.formatted(in.getQualifiedName());
                LOG.error(msg);
                continue;
            }

            if (parentTbl instanceof PgPartitionTable partTable) {
                createChildColToPartTblCol(partTable, col);
            } else {
                String colName = col.getName();
                IColumn parentCol = ((ITable) parentTbl).getColumn(colName);
                if (parentCol != null) {
                    graph.addEdge(col, parentCol);
                } else {
                    var msg = Messages.DepcyGraph_log_col_is_missed.formatted(
                            in.getQualifiedName(), colName, col.getSchemaName(), col.getParent().getName(), colName
                    );
                    LOG.error(msg);
                }
            }
        }
    }

    /**
     * Adds custom dependencies to the graph.
     *
     * @param dependencies list of custom dependency pairs to add
     */
    public void addCustomDepcies(Collection<Dependency> dependencies) {
        if (dependencies == null) {
            return;
        }
        for (var dep : dependencies) {
            IStatement source = db.getStatement(dep.source());
            IStatement target = db.getStatement(dep.target());

            if (source != null && target != null) {
                graph.addEdge(source, target);
            }
        }
    }
}
