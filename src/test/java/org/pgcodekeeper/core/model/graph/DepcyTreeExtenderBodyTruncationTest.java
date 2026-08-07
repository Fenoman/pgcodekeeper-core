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

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;

/**
 * Verifies that a dependency closure collected through a routine whose body was
 * not analyzed is reported as truncated: such a routine contributes no
 * body-derived edges, so nothing reachable only through its body is collected.
 */
class DepcyTreeExtenderBodyTruncationTest {

    private static final String SCHEMA = "public";
    private static final String ROUTINE = "matched_fn()";
    private static final String TABLE = "alpha";

    private static final ObjectReference ALPHA_TABLE =
            new ObjectReference(SCHEMA, TABLE, DbObjType.TABLE);
    private static final ObjectReference MATCHED_FUNCTION =
            new ObjectReference(SCHEMA, ROUTINE, DbObjType.FUNCTION);

    @Test
    void suppressedStateIsReadableAndNotCarriedByCopies() {
        PgAbstractFunction routine = routine(createModel("app"));
        assertFalse(routine.isBodyDependencyStateSuppressed(), "analyzed routine must not report a skipped body");

        routine.suppressBodyDependencyState();
        assertTrue(routine.isBodyDependencyStateSuppressed(), "skipped body must be reported");
        assertFalse(((PgAbstractFunction) routine.shallowCopy()).isBodyDependencyStateSuppressed(),
                "copies must not carry the state, the resolver walks a copied database");
    }

    @Test
    void dropClosureThroughUnanalyzedRoutineIsTruncated() {
        IDatabase source = createModel("app");
        IDatabase target = createModel("other");
        routine(source).suppressBodyDependencyState();

        TreeElement root = createDropSelection();
        var dte = new DepcyTreeExtender(source, target, root, List.of());
        Set<TreeElement> depcies = dte.getDepcies();

        assertTrue(depcies.contains(root.findElement(target.getStatement(MATCHED_FUNCTION))),
                "the routine differs on both sides, so it is a dependency of the dropped table");
        assertTrue(dte.isBodyDependencyTruncated(),
                "the drop closure stops at the routine with the unanalyzed body");
    }

    @Test
    void dropClosureThroughAnalyzedRoutineIsNotTruncated() {
        IDatabase source = createModel("app");
        IDatabase target = createModel("other");

        TreeElement root = createDropSelection();
        var dte = new DepcyTreeExtender(source, target, root, List.of());
        Set<TreeElement> depcies = dte.getDepcies();

        assertTrue(depcies.contains(root.findElement(target.getStatement(MATCHED_FUNCTION))),
                "an analyzed routine is collected exactly like the truncating one");
        assertFalse(dte.isBodyDependencyTruncated(), "a fully analyzed closure is complete");
    }

    @Test
    void truncationIsDetectedBeforeTheEqualityFilterHidesTheRoutine() {
        IDatabase source = createModel("app");
        IDatabase target = createModel("app");
        routine(source).suppressBodyDependencyState();

        TreeElement root = createDropSelection();
        var dte = new DepcyTreeExtender(source, target, root, List.of());
        Set<TreeElement> depcies = dte.getDepcies();

        assertFalse(depcies.contains(root.findElement(target.getStatement(MATCHED_FUNCTION))),
                "the routine is equal on both sides, so the tree filter drops it");
        assertTrue(dte.isBodyDependencyTruncated(),
                "the flag is taken from the raw closure, which the tree filter no longer shows");
    }

    @Test
    void createClosureReadsTheStateOfTheTargetSide() {
        IDatabase source = createModel("app");
        IDatabase target = createModel("app");
        routine(source).suppressBodyDependencyState();

        TreeElement root = createCreateSelection();
        var dte = new DepcyTreeExtender(source, target, root, List.of());
        dte.getDepcies();

        assertFalse(dte.isBodyDependencyTruncated(),
                "the create closure is collected from the target, which was analyzed");

        routine(target).suppressBodyDependencyState();
        var truncated = new DepcyTreeExtender(source, target, createCreateSelection(), List.of());
        truncated.getDepcies();

        assertTrue(truncated.isBodyDependencyTruncated(),
                "the routine itself is in the create closure and its body was not analyzed");
    }

    /**
     * User drops the table the routine depends on, so the reverse closure
     * reaches the routine.
     */
    private static TreeElement createDropSelection() {
        TreeElement root = new TreeElement("Database", DbObjType.DATABASE, DiffSide.BOTH);
        TreeElement schema = new TreeElement(SCHEMA, DbObjType.SCHEMA, DiffSide.BOTH);
        root.addChild(schema);

        TreeElement table = new TreeElement(TABLE, DbObjType.TABLE, DiffSide.LEFT);
        table.setSelected(true);
        schema.addChild(table);

        schema.addChild(new TreeElement(ROUTINE, DbObjType.FUNCTION, DiffSide.BOTH));
        return root;
    }

    /**
     * User applies the routine itself, which is an edit element whenever
     * anything outside the body differs, an owner or a privilege for instance.
     */
    private static TreeElement createCreateSelection() {
        TreeElement root = new TreeElement("Database", DbObjType.DATABASE, DiffSide.BOTH);
        TreeElement schema = new TreeElement(SCHEMA, DbObjType.SCHEMA, DiffSide.BOTH);
        root.addChild(schema);

        schema.addChild(new TreeElement(TABLE, DbObjType.TABLE, DiffSide.BOTH));

        TreeElement function = new TreeElement(ROUTINE, DbObjType.FUNCTION, DiffSide.BOTH);
        function.setSelected(true);
        schema.addChild(function);
        return root;
    }

    private static PgAbstractFunction routine(IDatabase db) {
        return (PgAbstractFunction) db.getStatement(MATCHED_FUNCTION);
    }

    private static PgDatabase createModel(String routineOwner) {
        var db = new PgDatabase();
        var schema = new PgSchema(SCHEMA);
        db.addChild(schema);
        db.setDefaultSchema(SCHEMA);

        var table = new PgSimpleTable(TABLE);
        var id = new PgColumn("id");
        id.setType("integer");
        table.addColumn(id);
        schema.addChild(table);

        var function = new PgFunction("matched_fn");
        function.setLanguageCost("plpgsql", null);
        function.setReturns("integer");
        function.setBody("$$BEGIN RETURN (SELECT count(*) FROM public.alpha); END$$");
        function.setOwner(routineOwner);
        function.addDependency(ALPHA_TABLE);
        schema.addChild(function);

        return db;
    }
}
