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
package org.pgcodekeeper.core.database.pg.parser.statement;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractView;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgIndex;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The alternatives of {@code ALTER INDEX}, {@code ALTER VIEW} and
 * {@code ALTER MATERIALIZED VIEW} that state a property the matching
 * {@code CREATE} could have stated. All of them parsed and reached no writer:
 * of {@code index_def_action} only {@code ATTACH PARTITION} was read, of
 * {@code alter_view_action} only the column default, and of
 * {@code materialized_view_action} only {@code CLUSTER ON}.
 *
 * <p>
 * The three belong to the same class of defect as {@code ALTER TABLE},
 * {@code ALTER DOMAIN} and {@code ALTER SEQUENCE}: an {@code ALTER} that
 * reaches no writer leaves the model holding the state the file has just left,
 * so against a database where the file's own statement was applied the tool
 * writes it back the other way round. Left unread, a project saying
 * {@code SET (fillfactor=70)} produces {@code RESET (fillfactor)} and one saying
 * {@code SET (security_invoker=true)} produces {@code RESET (security_invoker)}
 * - the latter a property of who the view's query runs as.
 *
 * <p>
 * The criterion: the model an {@code ALTER} builds must be the model the
 * equivalent {@code CREATE} builds, because the database side has one answer for
 * both spellings. So each case loads two project files and asks for an empty
 * script.
 *
 * <p>
 * The tool writes these statements itself - {@code compare_indices_diff.sql}
 * carries {@code ALTER INDEX ... SET (...)} and {@code add_view_option_diff.sql}
 * carries {@code ALTER VIEW ... SET (...)} as expected output - so left unread
 * they are migrations pgcodekeeper generates and cannot read back.
 */
class PgAlterRelationPropertyTest {

    private static final String SCHEMA = "public";

    /** A table for the indexes below to hang off. */
    private static final String TABLE = """
            CREATE TABLE public.t (
            \tc1 integer
            );
            """;

    /** A view body, so that only the option under test differs. */
    private static final String QUERY = "SELECT t.c1 FROM public.t";

    // ------------------------------------------------------- ALTER INDEX

    /**
     * The defect, stated directly. A project file that sets a storage parameter
     * on an index must have that reach the database; unread, the model kept the
     * index without it and the tool wrote {@code RESET (fillfactor)} against a
     * database where the file's own statement had been applied.
     */
    @Test
    void anIndexOptionStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load(TABLE + """

                CREATE INDEX idx ON public.t USING btree (c1);

                ALTER INDEX public.idx SET (fillfactor=70);""");
        PgDatabase inline = load(TABLE + """

                CREATE INDEX idx ON public.t USING btree (c1) WITH (fillfactor=70);""");
        // asserted against the CREATE's own answer rather than a literal: the
        // spelling a storage parameter is held under belongs to the routine both
        // statements read it with, and this case is about the ALTER reaching the
        // model at all
        assertEquals(indexOf(inline).getOptions(), indexOf(byAlter).getOptions(),
                "SET (...) must reach the index");

        assertEquals("", pipeline(inline, byAlter).trim(),
                "an index option set by ALTER must be the option the CREATE states inline");
    }

    /**
     * The other half of the pair that writes one field. {@code RESET} takes an
     * option away, so the file describes the index the {@code CREATE} without it
     * describes.
     */
    @Test
    void anIndexOptionResetByAlterLeavesTheModel() throws Exception {
        PgDatabase byAlter = load(TABLE + """

                CREATE INDEX idx ON public.t USING btree (c1) WITH (fillfactor=70);

                ALTER INDEX public.idx RESET (fillfactor);""");
        assertTrue(indexOf(byAlter).getOptions().isEmpty(), "RESET (...) must take the option away");

        assertEquals("", pipeline(load(TABLE + """

                CREATE INDEX idx ON public.t USING btree (c1);"""), byAlter).trim(),
                "an index option reset by ALTER must be the index the CREATE states without it");
    }

    /**
     * {@code SET TABLESPACE} states where the index ends up, which is a property
     * the {@code CREATE} writes too. {@code NOWAIT} is deliberately not read, on
     * the same reading {@code ALTER TABLE} gives it: it says how the database
     * should carry the move out.
     */
    @Test
    void anIndexTablespaceStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load(TABLE + """

                CREATE INDEX idx ON public.t USING btree (c1);

                ALTER INDEX public.idx SET TABLESPACE fast;""");

        assertEquals("", pipeline(load(TABLE + """

                CREATE INDEX idx ON public.t USING btree (c1) TABLESPACE fast;"""), byAlter).trim(),
                "an index moved by ALTER must be the index the CREATE places there");
    }

    /**
     * The two halves in one statement list. Measured on PostgreSQL 17.10, the
     * actions of one {@code ALTER INDEX} are not a list at all - the grammar
     * gives the statement a single {@code index_def_action} - so the pair is
     * two statements, applied in order.
     */
    @Test
    void theLastWordOnAnIndexOptionWins() throws Exception {
        PgDatabase byAlter = load(TABLE + """

                CREATE INDEX idx ON public.t USING btree (c1);

                ALTER INDEX public.idx SET (fillfactor=70);

                ALTER INDEX public.idx SET (fillfactor=90);""");

        assertEquals("", pipeline(load(TABLE + """

                CREATE INDEX idx ON public.t USING btree (c1) WITH (fillfactor=90);"""), byAlter).trim(),
                "the second statement states the index's final state");
    }

    // -------------------------------------------------------- ALTER VIEW

    /**
     * {@code security_invoker} decides whose privileges the view's query runs
     * with, so writing it back the other way round is a change of who may read
     * what. Measured before the fix: a project file setting it produced
     * {@code ALTER VIEW public.v RESET (security_invoker)}.
     */
    @Test
    void aViewOptionStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load(TABLE + """

                CREATE VIEW public.v AS
                \t%s;

                ALTER VIEW public.v SET (security_invoker=true);""".formatted(QUERY));
        PgDatabase inline = load(TABLE + """

                CREATE VIEW public.v WITH (security_invoker=true) AS
                \t%s;""".formatted(QUERY));
        assertEquals(inline == null ? null : viewOf(inline).getOptions(), viewOf(byAlter).getOptions(),
                "SET (...) must reach the view");

        assertEquals("", pipeline(inline, byAlter).trim(),
                "a view option set by ALTER must be the option the CREATE states inline");
    }

    /** The {@code RESET} half, as for the index above. */
    @Test
    void aViewOptionResetByAlterLeavesTheModel() throws Exception {
        PgDatabase byAlter = load(TABLE + """

                CREATE VIEW public.v WITH (security_barrier=true) AS
                \t%s;

                ALTER VIEW public.v RESET (security_barrier);""".formatted(QUERY));
        assertTrue(viewOf(byAlter).getOptions().isEmpty(), "RESET (...) must take the option away");

        assertEquals("", pipeline(load(TABLE + """

                CREATE VIEW public.v AS
                \t%s;""".formatted(QUERY)), byAlter).trim(),
                "a view option reset by ALTER must be the view the CREATE states without it");
    }

    // -------------------------------------- ALTER MATERIALIZED VIEW

    /** The materialized view's own {@code SET (...)}. */
    @Test
    void aMatViewOptionStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load(TABLE + """

                CREATE MATERIALIZED VIEW public.mv AS
                \t%s
                WITH DATA;

                ALTER MATERIALIZED VIEW public.mv SET (fillfactor=70);""".formatted(QUERY));

        assertEquals("", pipeline(load(TABLE + """

                CREATE MATERIALIZED VIEW public.mv
                WITH (fillfactor=70) AS
                \t%s
                WITH DATA;""".formatted(QUERY)), byAlter).trim(),
                "a matview option set by ALTER must be the option the CREATE states inline");
    }

    /** And its {@code RESET (...)}. */
    @Test
    void aMatViewOptionResetByAlterLeavesTheModel() throws Exception {
        PgDatabase byAlter = load(TABLE + """

                CREATE MATERIALIZED VIEW public.mv
                WITH (fillfactor=70) AS
                \t%s
                WITH DATA;

                ALTER MATERIALIZED VIEW public.mv RESET (fillfactor);""".formatted(QUERY));

        assertEquals("", pipeline(load(TABLE + """

                CREATE MATERIALIZED VIEW public.mv AS
                \t%s
                WITH DATA;""".formatted(QUERY)), byAlter).trim(),
                "a matview option reset by ALTER must be the matview the CREATE states without it");
    }

    /**
     * The worst of the matview alternatives, because the access method is one of
     * the fields {@code needDrop} reads: left unread, the model kept the default
     * method and the tool answered a file stating another one with
     * {@code DROP MATERIALIZED VIEW} plus {@code CREATE}, measured - the whole
     * object rebuilt to undo what the file had asked for.
     */
    @Test
    void aMatViewAccessMethodStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load(TABLE + """

                CREATE MATERIALIZED VIEW public.mv AS
                \t%s
                WITH DATA;

                ALTER MATERIALIZED VIEW public.mv SET ACCESS METHOD heap2;""".formatted(QUERY));

        assertEquals("", pipeline(load(TABLE + """

                CREATE MATERIALIZED VIEW public.mv
                USING heap2 AS
                \t%s
                WITH DATA;""".formatted(QUERY)), byAlter).trim(),
                "a matview method set by ALTER must be the method the CREATE states inline");
    }

    /**
     * {@code SET WITHOUT CLUSTER} is the half of the pair whose other half -
     * {@code CLUSTER ON} - already had a writer. The clustered index is a
     * property of the index rather than of the view, which is why it is read
     * through the view's own index lookup.
     */
    @Test
    void aMatViewLosesItsClusterWhenTheFileSaysSo() throws Exception {
        String create = TABLE + """

                CREATE MATERIALIZED VIEW public.mv AS
                \t%s
                WITH DATA;

                CREATE INDEX mv_idx ON public.mv USING btree (c1);""".formatted(QUERY);

        PgDatabase byAlter = load(create + """


                ALTER MATERIALIZED VIEW public.mv CLUSTER ON mv_idx;

                ALTER MATERIALIZED VIEW public.mv SET WITHOUT CLUSTER;""");

        assertEquals("", pipeline(load(create), byAlter).trim(),
                "a cluster taken away by ALTER must be the matview that never had one");
    }

    /**
     * The pair spelled as one statement, since {@code alter_materialized_view_action}
     * does take a comma-separated list. Applied left to right, as the server
     * applies them.
     */
    @Test
    void aMatViewReadsEveryActionOfOneStatement() throws Exception {
        PgDatabase byAlter = load(TABLE + """

                CREATE MATERIALIZED VIEW public.mv AS
                \t%s
                WITH DATA;

                ALTER MATERIALIZED VIEW public.mv SET (fillfactor=70), SET ACCESS METHOD heap2;"""
                .formatted(QUERY));

        assertEquals("", pipeline(load(TABLE + """

                CREATE MATERIALIZED VIEW public.mv
                USING heap2
                WITH (fillfactor=70) AS
                \t%s
                WITH DATA;""".formatted(QUERY)), byAlter).trim(),
                "every action of one statement must reach the model");
    }

    /**
     * The four column alternatives state something the model holds nowhere - a
     * view's columns are name and type pairs derived from its query - so they
     * are dropped. What this pins is that they are dropped rather than mistaken
     * for the view's own {@code SET (...)}: the two alternatives carry the same
     * {@code storage_parameters} child, and a branch keyed on that alone writes
     * a column's parameter into the view's option map, where the next
     * comparison emits it as an option of the view.
     */
    @Test
    void aMatViewColumnActionIsNotMistakenForTheViewsOwn() throws Exception {
        String create = TABLE + """

                CREATE MATERIALIZED VIEW public.mv AS
                \t%s
                WITH DATA;""".formatted(QUERY);

        PgDatabase byAlter = load(create + """


                ALTER MATERIALIZED VIEW public.mv ALTER COLUMN c1 SET (n_distinct=1);""");

        assertEquals("", pipeline(load(create), byAlter).trim(),
                "a column's parameter is not the view's own");
    }

    // ------------------------------------------------------------- guards

    /**
     * A name that matches nothing is reported, the way every other
     * {@code ALTER} reports one - these statements carry an {@code IF EXISTS}
     * of their own with which to say the object may not be there, and none of
     * these fixtures uses it.
     */
    @Test
    void alteringAnIndexTheSchemaHasNotIsReported() throws Exception {
        var settings = new CoreSettings();
        load(TABLE + """

                ALTER INDEX public.nosuch SET (fillfactor=70);""", settings);
        assertFalse(settings.getErrors().isEmpty(), "an unknown index has to be reported");
    }


    // ------------------------------------------------------------ helpers

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return load(sql, new CoreSettings());
    }

    private static PgDatabase load(String sql, CoreSettings settings) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "relation property test", settings).load();
    }

    private static PgIndex indexOf(PgDatabase db) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA);
        PgIndex index = schema == null ? null : schema.getIndexByName("idx");
        assertNotNull(index, "no index was parsed");
        return index;
    }

    private static PgAbstractView viewOf(PgDatabase db) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA);
        PgAbstractView view = schema == null ? null : schema.getView("v");
        assertNotNull(view, "no view was parsed");
        return view;
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
