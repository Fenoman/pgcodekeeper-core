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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractTable;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Three breakages of {@link PgAlterTable} on {@code ALTER TABLE} statements
 * PostgreSQL and Greenplum both accept. They share a subject and nothing else,
 * which is why they sit in one class: none of the three is a gap in the design
 * the way a swallowed alternative is - each is a plain defect in a branch that
 * was meant to work.
 * <p>
 * They also damage differently, so they are asserted differently. The first
 * loses model content silently. The other two throw: one out of the loader
 * altogether, one into the settings' error list. For those two an assertion
 * that no exception escapes would pin the symptom and not the cause, so each
 * asserts that the statement's own content reaches the model.
 */
class PgAlterTableLegalDdlTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "t";

    /**
     * {@code parseObject} walked the table actions in a loop and left it with
     * {@code return} once it had built a table-level {@code NOT NULL}
     * constraint, so every action written after that one in the same statement
     * reached no writer at all. A statement is one list of actions and all of
     * them describe the same table, so the loop has to carry on.
     */
    @Test
    void everyActionOfAMultiActionStatementReachesTheModel() throws Exception {
        PgDatabase oneStatement = load("""
                CREATE TABLE %1$s.%2$s (c1 integer);
                ALTER TABLE %1$s.%2$s ADD CONSTRAINT t_c1_nn NOT NULL c1, ADD COLUMN c9 integer;
                """.formatted(SCHEMA_NAME, TABLE_NAME));

        assertNotNull(tableOf(oneStatement).getColumn("c9"),
                "the action after the NOT NULL constraint must reach the model");

        // and the actions must land exactly as the same list written apart does
        PgDatabase twoStatements = load("""
                CREATE TABLE %1$s.%2$s (c1 integer);
                ALTER TABLE %1$s.%2$s ADD CONSTRAINT t_c1_nn NOT NULL c1;
                ALTER TABLE %1$s.%2$s ADD COLUMN c9 integer;
                """.formatted(SCHEMA_NAME, TABLE_NAME));
        assertTrue(tableOf(twoStatements).compare(tableOf(oneStatement)),
                "one statement of two actions must build what two statements of one action build");
        assertEquals("", diff(twoStatements, oneStatement).trim());
    }

    /**
     * {@code ALTER TABLE ... ALTER COLUMN ... ADD GENERATED ... AS IDENTITY}
     * names its sequence only when the author spells {@code SEQUENCE NAME}; the
     * implicit name is the one {@code CREATE TABLE} already computes for the
     * inline form ({@code PgTableAbstract.addTableConstraint}). The alter path
     * started from {@code null} instead.
     * <p>
     * The load itself stayed silent - no error, no throw - and the
     * {@link NullPointerException} fell out later, when a script had to write
     * the column ({@code PgAbstractTable.writeSequences} through
     * {@code PgDiffUtils.getQuotedName}). So the file read clean and the
     * migration it fed blew up.
     * <p>
     * Asserted against the inline form rather than against the absence of the
     * throw: the two spellings describe the same column, so any name but the
     * implicit one leaves them comparing unequal.
     */
    @Test
    void anIdentityAddedByAlterTakesTheSameImplicitSequenceNameAsAnInlineOne() throws Exception {
        var settings = new CoreSettings();
        PgDatabase byAlter = load("""
                CREATE TABLE %1$s.%2$s (c1 integer);
                ALTER TABLE %1$s.%2$s ALTER COLUMN c1 ADD GENERATED ALWAYS AS IDENTITY;
                """.formatted(SCHEMA_NAME, TABLE_NAME), settings);
        assertTrue(settings.getErrors().isEmpty(),
                () -> "legal DDL must load without errors, got: " + settings.getErrors());

        PgDatabase inline = load("CREATE TABLE %s.%s (c1 integer GENERATED ALWAYS AS IDENTITY);"
                .formatted(SCHEMA_NAME, TABLE_NAME));
        assertTrue(tableOf(inline).compare(tableOf(byAlter)),
                "the identity added by ALTER must carry the implicit sequence name the inline form gets");
        assertEquals("", diff(inline, byAlter).trim());
    }

    /**
     * {@code parseGpPartitionTemplate} read {@code template_spec().part_element()}
     * unconditionally, but {@code partition_gp_action} has fifteen alternatives
     * and only three can carry a {@code template_spec} - and even
     * {@code SET SUBPARTITION TEMPLATE ()} carries none. Every other Greenplum
     * partition action therefore raised a reported error.
     * <p>
     * Both halves are asserted: the actions that hold no template must be
     * silent, and the one that does must still reach the model. The second half
     * is what keeps the guard from being written as "ignore the clause".
     */
    @Test
    void aGreenplumPartitionActionWithoutATemplateIsSilentAndTheOneWithATemplateStillLands()
            throws Exception {
        var settings = new CoreSettings();
        PgDatabase withTemplate = load((GP_TABLE + """
                ALTER TABLE %1$s.%2$s ADD PARTITION p9 START (20) END (30);
                ALTER TABLE %1$s.%2$s SET SUBPARTITION TEMPLATE ();
                ALTER TABLE %1$s.%2$s SET SUBPARTITION TEMPLATE (START (1) END (5));
                """).formatted(SCHEMA_NAME, TABLE_NAME), settings);
        assertTrue(settings.getErrors().isEmpty(),
                () -> "a partition action carrying no template must be silent, got: " + settings.getErrors());

        PgDatabase noTemplate = load(GP_TABLE.formatted(SCHEMA_NAME, TABLE_NAME));
        assertTrue(diff(noTemplate, withTemplate).contains("SET SUBPARTITION TEMPLATE"),
                "the template of the last statement must still reach the model");
    }

    /**
     * The fourth of the same family, found while pinning the third and left out
     * of it: the Greenplum partition clause is handed to
     * {@code parseGpPartitionTemplate} through a cast to
     * {@link org.pgcodekeeper.core.database.pg.schema.GpPartitionTable} that
     * nothing checks, so any table the {@code CREATE} did not build as one
     * raised a {@link ClassCastException} into the settings' error list -
     * measured, {@code PgSimpleTable cannot be cast to GpPartitionTable}. It
     * fires on the whole of {@code alter_partition_gp}, the actions that carry
     * no template included, so the third defect's fix did not reach it.
     * <p>
     * A table that is not a Greenplum partitioned one is left alone, which is
     * the reading every other clause of this class gets for a table it cannot
     * state anything about - {@code SET LOGGED} on a foreign table, {@code SET
     * EXPRESSION} on a plain column. What the branch must not do is report an
     * error where it can do nothing.
     * <p>
     * Both halves again: the plain table must be silent and must be left as its
     * {@code CREATE} declared, and the Greenplum table beside it must still take
     * its template - a guard written as "ignore the clause" would pass the first
     * half alone.
     */
    @Test
    void aGreenplumPartitionActionOnATableThatIsNotOneIsSilent() throws Exception {
        var settings = new CoreSettings();
        PgDatabase byAlter = load("""
                CREATE TABLE %1$s.%2$s (c1 integer);
                ALTER TABLE %1$s.%2$s SET SUBPARTITION TEMPLATE (START (1) END (5));
                ALTER TABLE %1$s.%2$s DROP PARTITION p9;
                """.formatted(SCHEMA_NAME, TABLE_NAME), settings);
        assertTrue(settings.getErrors().isEmpty(),
                () -> "a partition action on a table that is not partitioned must be silent, got: "
                        + settings.getErrors());

        PgDatabase plain = load("CREATE TABLE %s.%s (c1 integer);".formatted(SCHEMA_NAME, TABLE_NAME));
        assertEquals("", diff(plain, byAlter).trim(),
                "and must leave the table exactly as its CREATE declared it");
    }

    private static final String GP_TABLE = """
            CREATE TABLE %1$s.%2$s (
                c1 integer
            )
            DISTRIBUTED BY (c1)
            PARTITION BY RANGE (c1)
                      (
                      START (1) END (10) EVERY (1)
                      );
            """;

    private static PgAbstractTable tableOf(PgDatabase db) {
        return db.getChildren()
                .filter(PgSchema.class::isInstance)
                .map(PgSchema.class::cast)
                .map(s -> s.getTable(TABLE_NAME))
                .filter(t -> t != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no table was parsed"));
    }

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return load(sql, new CoreSettings());
    }

    private static PgDatabase load(String sql, CoreSettings settings) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "alter table legal ddl test", settings).load();
    }

    private static String diff(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
