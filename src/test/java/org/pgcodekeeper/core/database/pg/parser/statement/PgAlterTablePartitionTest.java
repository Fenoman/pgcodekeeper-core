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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractTable;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgPartitionTable;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * {@code ALTER TABLE ... ATTACH PARTITION} and {@code DETACH PARTITION}, the
 * {@code alter_partition} alternative of {@code alter_table_statement}
 * ({@code SQLParser.g4:407-410}).
 *
 * <p>
 * It parsed and reached no writer: {@code parseObject} asked for
 * {@code table_action()} and for the Greenplum {@code alter_partition_gp()},
 * and nothing anywhere asked for {@code alter_partition()}. The comparable
 * clause of {@code ALTER INDEX} had a writer all along, which is what made the
 * gap visible.
 *
 * <p>
 * The tool writes both statements itself - {@code PgPartitionTable.convertTable}
 * emits the {@code ATTACH} and {@code compareTableTypes} the {@code DETACH}, and
 * {@code chg_reg_table_to_partition_diff.sql} carries the first as expected
 * output. So unread they were migrations pgcodekeeper generated and could not
 * read back, and the two spellings of one state - the {@code pg_dump} pair
 * {@code CREATE TABLE child} plus {@code ATTACH}, and the declarative
 * {@code CREATE TABLE child PARTITION OF parent} - built different models.
 * Measured before the fix: comparing them gave a false {@code DETACH} of a live
 * partition in one direction and a false {@code ATTACH} in the other.
 *
 * <p>
 * Whether a table is a partition is its class in this model, not a field, so
 * reading the statement means replacing the object in the schema. What the
 * replacement has to carry is the subject of
 * {@code aTableKeepsEverythingItHasWhenItBecomesAPartition} below: the children
 * are moved rather than copied, because the analysis launchers a
 * {@code CREATE TABLE} registers hold the column and the constraint objects
 * themselves, and copies would leave those launchers pointing at a table the
 * schema no longer has.
 */
class PgAlterTablePartitionTest {

    private static final String SCHEMA = "public";

    private static final String PARENT = """
            CREATE TABLE public.parent (
            \tid integer
            )
            PARTITION BY RANGE (id);""";

    // -------------------------------------------------------------- ATTACH

    /**
     * The defect, stated directly. {@code pg_dump} writes a partition as this
     * pair, so loading its own output has to build the model the declarative
     * form builds.
     */
    @Test
    void anAttachedTableIsThePartitionTheDeclarativeFormDefines() throws Exception {
        PgDatabase byAlter = load(attached());
        assertInstanceOf(PgPartitionTable.class, tableOf(byAlter, "child"),
                "the attached table has to be a partition");

        assertEquals("", pipeline(load(declarative()), byAlter).trim(),
                "the pg_dump pair must build what the declarative CREATE builds");
        assertEquals("", pipeline(byAlter, load(declarative())).trim(),
                "and the same the other way round");
    }

    /**
     * The bound is part of it: an attached table whose bound differs from the
     * declared one is a different partition, and the comparison says so.
     */
    @Test
    void theBoundOfTheAttachReachesTheModel() throws Exception {
        assertEquals("FOR VALUES FROM (1) TO (10)",
                ((PgPartitionTable) tableOf(load(attached()), "child")).getPartitionBounds(),
                "the bound is what the statement said");

        assertFalse(pipeline(load(PARENT + """


                CREATE TABLE public.child PARTITION OF public.parent
                FOR VALUES FROM (20) TO (30);"""), load(attached())).trim().isEmpty(),
                "a different bound is a different partition");
    }

    /**
     * A default partition, the other alternative of {@code for_values_bound}.
     */
    @Test
    void aDefaultPartitionIsAttachedToo() throws Exception {
        PgDatabase byAlter = load(PARENT + """


                CREATE TABLE public.child (
                \tid integer
                );

                ALTER TABLE public.parent ATTACH PARTITION public.child DEFAULT;""");

        assertEquals("", pipeline(load(PARENT + """


                CREATE TABLE public.child PARTITION OF public.parent
                DEFAULT;"""), byAlter).trim(),
                "DEFAULT is a bound like any other");
    }

    /**
     * What the replacement has to carry. The table is a different object after
     * the statement, so everything hanging off the old one has to arrive on the
     * new one: the columns with their defaults, the constraints, the indexes,
     * the triggers, the comment and the owner.
     *
     * <p>
     * The children are moved and not copied. The launchers registered while the
     * {@code CREATE TABLE} was read hold the column and constraint objects
     * themselves - {@code PgVexAnalysisLauncher(col, ...)} and
     * {@code PgConstraintAnalysisLauncher(constr, ...)} - so a copy would leave
     * every one of them analysing an object whose parent chain no longer
     * reaches the database, and its dependencies would go nowhere without a
     * word.
     */
    @Test
    void aTableKeepsEverythingItHasWhenItBecomesAPartition() throws Exception {
        String body = "\tCONSTRAINT child_id_check CHECK ((id > 0))";

        PgDatabase byAlter = load(PARENT + """


                CREATE TABLE public.child (
                \tid integer,
                %s
                );

                ALTER TABLE public.child OWNER TO owner;

                COMMENT ON TABLE public.child IS 'kept';

                CREATE INDEX child_id_idx ON public.child USING btree (id);

                ALTER TABLE public.parent ATTACH PARTITION public.child FOR VALUES FROM (1) TO (10);"""
                .formatted(body));

        String script = pipeline(load(PARENT + """


                CREATE TABLE public.child PARTITION OF public.parent (
                %s
                )
                FOR VALUES FROM (1) TO (10);

                ALTER TABLE public.child OWNER TO owner;

                COMMENT ON TABLE public.child IS 'kept';

                CREATE INDEX child_id_idx ON public.child USING btree (id);"""
                .formatted(body)), byAlter);

        assertEquals("", script.trim(),
                () -> "nothing the table had may be lost when its class changes, got:\n" + script);
    }

    /**
     * The round trip. The tool writes the {@code ATTACH} itself when a plain
     * table becomes a partition, so its own migration, appended to the project
     * it was written against, has to reach the project it was written for.
     */
    @Test
    void theAttachTheToolWritesIsReadBack() throws Exception {
        String plain = PARENT + """


                CREATE TABLE public.child (
                \tid integer
                );""";
        PgDatabase target = load(declarative());

        String migration = pipeline(load(plain), target);
        assertTrue(migration.contains("ATTACH PARTITION"),
                () -> "the tool writes ATTACH PARTITION to make a table a partition, got:\n" + migration);

        String script = pipeline(target, load(plain + "\n\n" + migration));
        assertEquals("", script.trim(),
                () -> "applying the tool's own migration must reach the project it was written for, got:\n"
                        + script);
    }

    // -------------------------------------------------------------- DETACH

    /**
     * The other half, and the one whose absence was destructive: a project file
     * detaching a partition kept a partition in the model, so against a
     * database where that very statement had been applied the tool wrote the
     * {@code ATTACH} back.
     */
    @Test
    void aDetachedPartitionIsThePlainTableItBecomes() throws Exception {
        PgDatabase byAlter = load(declarative() + """


                ALTER TABLE public.parent DETACH PARTITION public.child;""");
        assertInstanceOf(PgSimpleTable.class, tableOf(byAlter, "child"),
                "a detached partition is a table of its own");

        assertEquals("", pipeline(load(PARENT + """


                CREATE TABLE public.child (
                \tid integer
                );"""), byAlter).trim(),
                "a detached partition must be the table the plain CREATE defines");
    }

    /**
     * {@code CONCURRENTLY} and {@code FINALIZE} say how the database should
     * carry the detach out, while the file states the shape the table ends up
     * in - the reading {@code CASCADE} and {@code NOWAIT} already get.
     */
    @Test
    void theWordsThatSayHowTheServerDetachesStateNothing() throws Exception {
        for (String trailer : new String[] {"", " CONCURRENTLY", " FINALIZE"}) {
            PgDatabase byAlter = load(declarative() + """


                    ALTER TABLE public.parent DETACH PARTITION public.child%s;"""
                    .formatted(trailer));
            assertEquals("", pipeline(load(PARENT + """


                    CREATE TABLE public.child (
                    \tid integer
                    );"""), byAlter).trim(),
                    () -> "the trailer states nothing, failed on:" + trailer);
        }
    }

    // ------------------------------------------------------------- guards

    /**
     * The child has to resolve - it is the table this statement is about, and
     * the clause carries no word with which to say it may not be there. Before
     * the fix a name that matched nothing was as silent as a name that did.
     */
    @Test
    void attachingATableTheSchemaHasNotIsReported() throws Exception {
        var settings = new CoreSettings();
        load(PARENT + """


                ALTER TABLE public.parent ATTACH PARTITION public.nosuch FOR VALUES FROM (1) TO (10);""",
                settings);
        assertFalse(settings.getErrors().isEmpty(), "an unknown child has to be reported");
    }

    /**
     * The parent has to resolve too. It is where the child's columns come from
     * and go back to, and its name is what a partition's {@code CREATE} writes
     * in the {@code PARTITION OF} clause - unchecked, that clause would name a
     * table the project does not have.
     */
    @Test
    void attachingToATableTheSchemaHasNotIsReported() throws Exception {
        var settings = new CoreSettings();
        load("""
                CREATE TABLE public.child (
                \tid integer
                );

                ALTER TABLE public.nosuch ATTACH PARTITION public.child FOR VALUES FROM (1) TO (10);""",
                settings);
        assertFalse(settings.getErrors().isEmpty(), "an unknown parent has to be reported");
    }

    /**
     * A column of an attached partition keeps what it states of its own.
     *
     * <p>
     * The columns themselves become the parent's - the model does not hold them
     * twice, on the reading {@code PgTablesReader} gives a partition read from
     * the database - but a column that states a default, a {@code NOT NULL},
     * statistics, a collation, a comment or a storage states something the
     * parent does not, and dropping it would drop that with it.
     */
    @Test
    void aColumnThatStatesSomethingOfItsOwnSurvivesTheAttach() throws Exception {
        PgDatabase byAlter = load("""
                CREATE TABLE public.parent (
                \tid integer,
                \tnote text
                )
                PARTITION BY RANGE (id);

                CREATE TABLE public.child (
                \tid integer,
                \tnote text DEFAULT 'x'::text
                );

                ALTER TABLE public.parent ATTACH PARTITION public.child FOR VALUES FROM (1) TO (10);""");

        var child = tableOf(byAlter, "child");
        assertNotNull(child.getColumn("note"), "a column with a default of its own has to stay");
        assertEquals("'x'::text", child.getColumn("note").getDefaultValue(),
                "and it has to keep the default it stated");
        assertNull(child.getColumn("note").getType(),
                "but not the type, which is the parent's - the answer PgTablesReader"
                        + " gives a partition read from the database");
        assertNull(child.getColumn("id"), "a column that states nothing of its own is the parent's");
    }

    /** And so does the partition named by a detach. */
    @Test
    void detachingATableTheSchemaHasNotIsReported() throws Exception {
        var settings = new CoreSettings();
        load(PARENT + """


                ALTER TABLE public.parent DETACH PARTITION public.nosuch;""", settings);
        assertFalse(settings.getErrors().isEmpty(), "an unknown partition has to be reported");
    }

    /**
     * A table that is already a partition of the same parent is left as it is,
     * and one that is not a partition at all is left alone by a detach: both
     * are statements the server refuses, and neither is a reason to build a
     * model no {@code CREATE} could produce.
     */
    @Test
    void detachingATableThatIsNotAPartitionLeavesItAlone() throws Exception {
        String plain = PARENT + """


                CREATE TABLE public.child (
                \tid integer
                );""";
        PgDatabase byAlter = load(plain + """


                ALTER TABLE public.parent DETACH PARTITION public.child;""");

        assertEquals("", pipeline(load(plain), byAlter).trim(),
                "a table that is not a partition is already what a detach would make it");
    }

    // ---------------------------------------------------------- fixtures

    /** The pg_dump spelling: a plain table plus the statement that attaches it. */
    private static String attached() {
        return PARENT + """


                CREATE TABLE public.child (
                \tid integer
                );

                ALTER TABLE public.parent ATTACH PARTITION public.child FOR VALUES FROM (1) TO (10);""";
    }

    /** The declarative spelling of the same state. */
    private static String declarative() {
        return PARENT + """


                CREATE TABLE public.child PARTITION OF public.parent
                FOR VALUES FROM (1) TO (10);""";
    }

    // ----------------------------------------------------------- helpers

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return load(sql, new CoreSettings());
    }

    private static PgDatabase load(String sql, CoreSettings settings) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "alter table partition test", settings).load();
    }

    private static PgAbstractTable tableOf(PgDatabase db, String name) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA);
        PgAbstractTable table = schema == null ? null : schema.getTable(name);
        assertNotNull(table, "no table " + name + " was parsed");
        return table;
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
