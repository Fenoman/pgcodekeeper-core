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
package org.pgcodekeeper.core.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.dependencieslist.Dependency;
import org.pgcodekeeper.core.model.graph.DepcyResolver.DepcyGraphs;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Pins what a caller may assume when it hands the same pair of dependency graphs to several
 * diffs of one loaded comparison - which is what a batch run does, once per output.
 * <p>
 * Two properties carry the whole optimisation. The script must not notice: a graph is derived
 * from its model alone, so reusing one has to read exactly like rebuilding it. And the pair
 * must be built when it is needed and not before, because a comparison that comes out empty
 * never reaches a graph at all and must not be charged for one.
 */
class SharedDependencyGraphsTest {

    static {
        Locale.setDefault(Locale.ENGLISH);
    }

    @Test
    void aSharedPairScriptsExactlyLikeAFreshOne() throws Exception {
        PgDatabase oldDb = database(false);
        PgDatabase newDb = database(true);

        String fresh = PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());

        Supplier<DepcyGraphs> shared = PgCodeKeeperApi.sharedGraphs(oldDb, newDb);
        String first = PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb,
                new CoreSettings(), shared);
        String second = PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb,
                new CoreSettings(), shared);

        assertTrue(fresh.contains("ADD COLUMN c2"), fresh);
        assertEquals(fresh, first);
        assertEquals(fresh, second);
    }

    /**
     * The point of the whole change: the second output does not build its own pair.
     */
    @Test
    void theSharedPairIsBuiltOnceAndHandedOutAgain() {
        PgDatabase oldDb = database(false);
        PgDatabase newDb = database(true);

        Supplier<DepcyGraphs> shared = PgCodeKeeperApi.sharedGraphs(oldDb, newDb);
        DepcyGraphs first = shared.get();
        DepcyGraphs second = shared.get();

        assertSame(first, second);
        assertSame(first.oldGraph(), second.oldGraph());
        assertSame(first.newGraph(), second.newGraph());
    }

    /**
     * Custom dependencies are applied to the shared graphs on every run. Applying the same
     * ones twice must leave the second script reading like the first.
     */
    @Test
    void repeatedCustomDependenciesLeaveTheSharedGraphsAlone() throws Exception {
        PgDatabase oldDb = database(false);
        PgDatabase newDb = database(true);
        List<Dependency> extra = List.of(new Dependency(
                new ObjectReference("a", "t", DbObjType.TABLE),
                new ObjectReference("b", "t", DbObjType.TABLE)));

        Supplier<DepcyGraphs> shared = PgCodeKeeperApi.sharedGraphs(oldDb, newDb);
        String first = PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb,
                settingsWith(extra), shared);
        String second = PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb,
                settingsWith(extra), shared);
        String third = PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb,
                settingsWith(extra), shared);

        assertEquals(first, second);
        assertEquals(first, third);
    }

    /**
     * A comparison of a model against itself yields an empty script without ever reaching the
     * resolver. Nobody may build a graph for it - the reason the pair arrives behind a
     * supplier rather than as a value.
     */
    @Test
    void anEmptyComparisonNeverAsksForTheGraphs() throws Exception {
        PgDatabase db = database(false);
        AtomicInteger asked = new AtomicInteger();
        Supplier<DepcyGraphs> counting = () -> {
            asked.incrementAndGet();
            return PgCodeKeeperApi.sharedGraphs(db, db).get();
        };

        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(), db, db,
                new CoreSettings(), counting);

        assertEquals("", script);
        assertEquals(0, asked.get(), "an empty comparison asked for dependency graphs");
    }

    /**
     * A comparison that does produce a script asks for the graphs, and asks once. Without the
     * first half the offer is being ignored and every output still pays; without the second
     * half the offer is not being held on to.
     */
    @Test
    void aScriptedComparisonAsksForTheGraphsOncePerDiff() throws Exception {
        PgDatabase oldDb = database(false);
        PgDatabase newDb = database(true);
        AtomicInteger asked = new AtomicInteger();
        Supplier<DepcyGraphs> built = PgCodeKeeperApi.sharedGraphs(oldDb, newDb);
        Supplier<DepcyGraphs> counting = () -> {
            asked.incrementAndGet();
            return built.get();
        };

        String first = PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb,
                new CoreSettings(), counting);
        assertEquals(1, asked.get(), "the offered graphs were ignored: " + first);

        PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings(), counting);
        assertEquals(2, asked.get(), "one ask per diff, and the pair behind it is the same one");
    }

    /**
     * Taking the source builds nothing yet. Pinned by taking one over models no graph could be
     * built from: a source that walked them on the spot would blow up here, a source that waits
     * to be asked hands back quietly.
     */
    @Test
    void takingTheSourceBuildsNothingYet() {
        assertDoesNotThrow(() -> PgCodeKeeperApi.sharedGraphs(null, null),
                "the graph source built its graphs before anyone asked for them");
    }

    private static CoreSettings settingsWith(List<Dependency> additional) {
        var settings = new CoreSettings();
        settings.addAdditionalDependencies(additional);
        return settings;
    }

    private static PgDatabase database(boolean extraColumn) {
        PgDatabase db = new PgDatabase();
        db.setDefaultSchema("a");
        db.addChild(schema("a", extraColumn));
        db.addChild(schema("b", false));
        return db;
    }

    private static PgSchema schema(String name, boolean extraColumn) {
        var schema = new PgSchema(name);
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
}
