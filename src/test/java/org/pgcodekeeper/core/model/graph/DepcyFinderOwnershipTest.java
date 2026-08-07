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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.dependencieslist.Dependency;

/**
 * {@link DepcyFinder} builds a graph over a database it does not own and must
 * not copy it.
 * <p>
 * The public {@code DepcyGraph} constructor deep-copies its argument, and
 * {@code DepcyFinder} kept the original too, so printing a dependency graph -
 * {@code --mode GRAPH}, and the plugin's per-statement view - held two whole
 * models at once for a walk that writes to neither. The same package already
 * offers {@code Ownership.BORROW_READ_ONLY} and {@code DepcyResolver} already
 * takes it.
 * <p>
 * The read-only claim is what makes the borrow legal, so it is asserted here
 * rather than assumed: the source model is compared against a snapshot of
 * itself taken before the walk, and the copy is caught at its only entry -
 * {@code getCopy}, which every {@code deepCopy} goes through.
 * <p>
 * Output parity is held elsewhere and deliberately: {@code PgDepcyFinderTest},
 * {@code MsDepcyFinderTest} and {@code ChDepcyFinderTest} pin the printed graph
 * of real fixtures line by line, and they are what would go red if borrowing
 * changed a single edge.
 */
class DepcyFinderOwnershipTest {

    private static final ObjectReference ALPHA_TABLE =
            new ObjectReference("public", "alpha", DbObjType.TABLE);
    private static final ObjectReference BETA_TABLE =
            new ObjectReference("public", "beta", DbObjType.TABLE);

    @Test
    void printingAGraphNeverCopiesTheModel() {
        var copies = new AtomicInteger();
        PgDatabase source = createModel(copies);

        List<String> result = DepcyFinder.byPatterns(10, false, Collections.emptyList(), false,
                source, List.of("public.beta"));

        assertFalse(result.isEmpty(), "the walk must actually have produced a graph");
        assertEquals(0, copies.get(),
                "a read-only walk must not deep-copy the database it walks");
    }

    /**
     * The same for the per-statement entry point, which the plugin's dependency
     * view uses.
     */
    @Test
    void printingTheGraphOfOneStatementNeverCopiesTheModel() {
        var copies = new AtomicInteger();
        PgDatabase source = createModel(copies);
        IStatement beta = source.getStatement(BETA_TABLE);

        List<String> result = DepcyFinder.byStatement(10, false, Collections.emptyList(), beta);

        assertFalse(result.isEmpty(), "the walk must actually have produced a graph");
        assertEquals(0, copies.get(),
                "a read-only walk must not deep-copy the database it walks");
    }

    /**
     * The condition the borrow rests on. A borrowed model is the caller's live
     * model, so anything the walk wrote into it would now be written into the
     * caller's - and the vertices are hash keys, so a changed one breaks the
     * graph it is a key of.
     */
    @Test
    void theWalkLeavesTheBorrowedModelExactlyAsItFoundIt() {
        var copies = new AtomicInteger();
        PgDatabase source = createModel(copies);
        String before = snapshot(source);

        DepcyFinder.byPatterns(10, true, Collections.emptyList(), false, source, List.of("public.alpha"),
                List.of(new Dependency(BETA_TABLE, ALPHA_TABLE)));

        assertEquals(before, snapshot(source),
                "neither the graph build nor the walk may change the model they borrow");
    }

    /**
     * Holds the probe honest. A counter that never counts would let the two
     * tests above pass against a reader that copies everything, so the same
     * model is deep-copied here on purpose and the count has to move.
     */
    @Test
    void theCopyProbeCountsWhatItClaimsTo() {
        var copies = new AtomicInteger();
        PgDatabase source = createModel(copies);

        source.deepCopy();

        assertEquals(1, copies.get(), "deepCopy of the database must reach the probe exactly once");
    }

    /**
     * A model small enough to read and large enough to have edges worth
     * walking: {@code beta.alpha_id} points at {@code alpha.id}, so a forward
     * walk from beta reaches alpha and a reverse walk from alpha reaches beta.
     */
    private static PgDatabase createModel(AtomicInteger copies) {
        PgDatabase db = new CountingDatabase(copies);
        PgSchema schema = new PgSchema("public");
        db.addChild(schema);
        db.setDefaultSchema("public");

        PgSimpleTable alpha = new PgSimpleTable("alpha");
        alpha.addColumn(column("id"));
        schema.addChild(alpha);

        PgSimpleTable beta = new PgSimpleTable("beta");
        PgColumn alphaId = column("alpha_id");
        alphaId.addDependency(new ObjectReference("public", "alpha", "id", DbObjType.COLUMN));
        beta.addColumn(alphaId);
        schema.addChild(beta);

        return db;
    }

    private static PgColumn column(String name) {
        PgColumn column = new PgColumn(name);
        column.setType("integer");
        return column;
    }

    /**
     * Every statement of the model with the state the comparison reads, in
     * traversal order. A field the walk changed shows up as a changed hash.
     */
    private static String snapshot(PgDatabase db) {
        return db.getDescendants().flatMap(ITable::columnAdder)
                .map(st -> st.getStatementType() + ":" + st.getQualifiedName() + '#' + st.hashCode())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Counts the copies of itself. {@code deepCopy} and {@code shallowCopy} are
     * final on {@code AbstractStatement}, and both funnel through
     * {@code getCopy}, so this is the one place a copy of this database can be
     * born.
     */
    private static final class CountingDatabase extends PgDatabase {

        private final transient AtomicInteger copies;

        private CountingDatabase(AtomicInteger copies) {
            this.copies = copies;
        }

        @Override
        protected PgDatabase getCopy() {
            copies.incrementAndGet();
            return super.getCopy();
        }
    }
}
