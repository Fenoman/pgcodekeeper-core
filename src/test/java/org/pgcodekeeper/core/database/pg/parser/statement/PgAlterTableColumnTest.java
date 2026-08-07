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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractTable;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The five alternatives of {@code ALTER TABLE} that state something about a
 * column a {@code CREATE TABLE} could have stated: {@code ALTER COLUMN ... TYPE},
 * {@code DROP COLUMN}, {@code DROP DEFAULT}, {@code DROP NOT NULL} and
 * {@code RENAME COLUMN}. All five parsed and reached no writer.
 *
 * <p>
 * The column's counterpart to {@link PgAlterTableConstraintTest}, and the
 * heavier half of the same failure mode: a constraint that fails to reach the
 * model leaves the model holding one object too many, while a column that fails
 * to reach it changes the table's own shape - its set of children, their names
 * and their types.
 *
 * <p>
 * {@code RENAME COLUMN} is content and not identity, which is why it is here
 * rather than with {@code RENAME TO} and {@code SET SCHEMA}: renaming a child
 * leaves the table's own identity untouched while the {@code CREATE} goes on
 * writing the old child name. Unread it was the worst of the five - against a
 * database that already holds the new name the tool emitted the pair
 * {@code DROP COLUMN c3} / {@code ADD COLUMN c2}, measured, which destroys the
 * column's data and does not even reach the state the file describes.
 */
class PgAlterTableColumnTest {

    private static final String SCHEMA = "public";
    private static final String TABLE = "t";

    // ---------------------------------------------------------------- TYPE

    /**
     * The defect, stated directly: a project file that changes a column's type
     * must have that type reach the database. Unread, the model kept the type
     * the {@code CREATE} declared, so the tool proposed changing the database
     * back to it.
     */
    @Test
    void aTypeStatedByAlterReachesTheModel() throws Exception {
        PgDatabase inline = load(table("c1 bigint"));
        PgDatabase byAlter = load(table("c1 integer") + """

                ALTER TABLE public.t ALTER COLUMN c1 TYPE bigint;""");

        assertEquals("bigint", columnOf(byAlter, "c1").getType(),
                "the type the file states must be the type the model carries");
        String script = pipeline(inline, byAlter);
        assertEquals("", script.trim(),
                () -> "a type written by ALTER must build the same column, got:\n" + script);
    }

    /**
     * The clause the same alternative may carry alongside the type. Written as
     * its own case because the collation reaches a different field and a
     * different dependency.
     */
    @Test
    void aCollationStatedByAlterReachesTheModel() throws Exception {
        PgDatabase inline = load(table("c1 character varying(20) COLLATE \"C\""));
        PgDatabase byAlter = load(table("c1 text") + """

                ALTER TABLE public.t ALTER COLUMN c1 TYPE character varying(20) COLLATE "C";""");

        assertEquals("\"C\"", columnOf(byAlter, "c1").getCollation(),
                "the collation the file states must be the collation the model carries");
        String script = pipeline(inline, byAlter);
        assertEquals("", script.trim(),
                () -> "a collation written by ALTER must build the same column, got:\n" + script);
    }

    /**
     * The identity sequence carries the column's type, and the server retypes it
     * with the column: measured on PostgreSQL 18.4,
     * {@code ALTER COLUMN id TYPE bigint} on a column
     * {@code GENERATED ALWAYS AS IDENTITY} leaves {@code pg_sequence.seqtypid}
     * at {@code bigint} and its maximum at the bigint one.
     *
     * <p>
     * So this is not an extra the type branch may skip: skipping it would build
     * a model no database can be in - a bigint column owning an integer
     * sequence - which is a state the reader never returns and the
     * {@code CREATE} of the two spellings never shares.
     */
    @Test
    void theIdentitySequenceFollowsTheColumnType() throws Exception {
        PgDatabase inline = load(table("c1 bigint GENERATED ALWAYS AS IDENTITY"));
        PgDatabase byAlter = load(table("c1 integer GENERATED ALWAYS AS IDENTITY") + """

                ALTER TABLE public.t ALTER COLUMN c1 TYPE bigint;""");

        assertEquals("bigint", columnOf(byAlter, "c1").getSequence().getDataType(),
                "the identity sequence must carry the type the column ends up with");
        String script = pipeline(inline, byAlter);
        assertEquals("", script.trim(),
                () -> "an identity column retyped by ALTER must build the same column, got:\n" + script);
    }

    // ----------------------------------------------------------------- DROP

    /**
     * A project file that drops a column must have that drop reach the database.
     * Unread, the model kept the column, the two sides compared equal, and the
     * database went on holding a column the project had removed.
     */
    @Test
    void aColumnDroppedInAProjectFileIsDroppedFromTheDatabase() throws Exception {
        PgDatabase db = load(table("c1 integer,\n\tc2 integer") + """

                ALTER TABLE public.t DROP COLUMN c2;""");
        assertNull(columnOf(db, "c2"), "the dropped column must leave the model");
        assertEquals(1, tableOf(db).getColumns().size(), "and leave nothing behind in the column list");

        String script = pipeline(load(table("c1 integer,\n\tc2 integer")), db);
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE ONLY public.t
                \tDROP COLUMN c2;""", script.trim());
    }

    /**
     * The modifier, honoured the way {@code DROP CONSTRAINT} honours it: without
     * {@code IF EXISTS} the statement names a column that has to be there, so an
     * unknown name is the unresolved reference this parser already reports for
     * the table it was told to alter and for a column it was told to change.
     */
    @Test
    void droppingAColumnTheFileNeverDeclaredIsReported() throws Exception {
        var settings = new CoreSettings();
        load(table("c1 integer") + "\n\nALTER TABLE public.t DROP COLUMN nosuch;", settings);
        assertFalse(settings.getErrors().isEmpty(),
                "dropping a column that is not there must be reported");
    }

    /** And the escape hatch the modifier exists for. */
    @Test
    void ifExistsMakesTheDropOfAnUnknownColumnSilent() throws Exception {
        var settings = new CoreSettings();
        PgDatabase db = load(table("c1 integer") + "\n\nALTER TABLE public.t DROP COLUMN IF EXISTS nosuch;",
                settings);
        assertTrue(settings.getErrors().isEmpty(),
                () -> "IF EXISTS must make the drop silent, got: " + settings.getErrors());
        assertNotNull(columnOf(db, "c1"), "and it must leave the column the table does have alone");
    }

    /**
     * An inheriting table need not declare the column it inherits, so a drop
     * naming one is not an unresolved reference - the same exemption the
     * {@code ALTER COLUMN} path already makes, where it is what lets a child
     * state a default of its own for an inherited column (accepted on 18.4).
     * For the drop the exemption gives up nothing, because the server refuses
     * the statement anyway: {@code cannot drop inherited column "c1"}, measured.
     */
    @Test
    void droppingAnInheritedColumnIsNotReported() throws Exception {
        var settings = new CoreSettings();
        load(inheritingTable("DROP COLUMN c1"), settings);
        assertTrue(settings.getErrors().isEmpty(),
                () -> "a column of the parent is not an unknown name, got: " + settings.getErrors());
    }

    // -------------------------------------------------------- DROP DEFAULT

    /**
     * A dropped default must leave the model, or the tool proposes putting the
     * database's own default back.
     */
    @Test
    void aDroppedDefaultLeavesTheColumn() throws Exception {
        PgDatabase byAlter = load(table("c1 integer DEFAULT 1") + """

                ALTER TABLE public.t ALTER COLUMN c1 DROP DEFAULT;""");
        assertNull(columnOf(byAlter, "c1").getDefaultValue(), "the dropped default must leave the model");

        String script = pipeline(load(table("c1 integer")), byAlter);
        assertEquals("", script.trim(),
                () -> "a column whose default the file drops must equal one that never had it, got:\n" + script);
    }

    /**
     * The other direction, and the one the empty script cannot speak for on its
     * own: against a database that has the default, the file that drops it must
     * produce exactly the drop.
     */
    @Test
    void aDroppedDefaultIsDroppedFromTheDatabase() throws Exception {
        String script = pipeline(load(table("c1 integer DEFAULT 1")),
                load(table("c1 integer DEFAULT 1") + """

                        ALTER TABLE public.t ALTER COLUMN c1 DROP DEFAULT;"""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE ONLY public.t
                \tALTER COLUMN c1 DROP DEFAULT;""", script.trim());
    }

    /**
     * The one column the statement does not speak for. A generation expression
     * lives in the same field as a default, and PostgreSQL refuses to drop it
     * this way - measured on 17.10 and 18.4 alike, {@code ALTER COLUMN b DROP
     * DEFAULT} on a generated column raises {@code column "b" of relation "g" is
     * a generated column}. The file is illegal DDL, and the model's answer to it
     * is the server's: nothing happens.
     */
    @Test
    void aDroppedDefaultLeavesAGeneratedColumnAlone() throws Exception {
        PgDatabase db = load(table("c1 integer,\n\tc2 integer GENERATED ALWAYS AS ((c1 * 2)) STORED") + """

                ALTER TABLE public.t ALTER COLUMN c2 DROP DEFAULT;""");
        assertEquals("(c1 * 2)", columnOf(db, "c2").getDefaultValue(),
                "a generation expression is not a default the server would let this statement drop");
    }

    // ------------------------------------------------------- DROP NOT NULL

    /**
     * A dropped {@code NOT NULL} must leave the model. Half of the alternative
     * was already read - {@code SET NOT NULL} has a writer and {@code DROP NOT
     * NULL} had none - so the file could add the constraint and never remove it.
     */
    @Test
    void aDroppedNotNullLeavesTheColumn() throws Exception {
        PgDatabase byAlter = load(table("c1 integer NOT NULL") + """

                ALTER TABLE public.t ALTER COLUMN c1 DROP NOT NULL;""");
        assertNull(columnOf(byAlter, "c1").getNotNullConstraint(),
                "the dropped NOT NULL must leave the model");

        String script = pipeline(load(table("c1 integer")), byAlter);
        assertEquals("", script.trim(),
                () -> "a column whose NOT NULL the file drops must equal one that never had it, got:\n" + script);
    }

    /**
     * And a named one, which is the same statement on the server - measured on
     * 18.4, {@code DROP NOT NULL} removes the {@code pg_constraint} row whether
     * the constraint was named or not.
     */
    @Test
    void aDroppedNamedNotNullIsDroppedFromTheDatabase() throws Exception {
        String script = pipeline(load(table("c1 integer CONSTRAINT t_c1_nn NOT NULL")),
                load(table("c1 integer CONSTRAINT t_c1_nn NOT NULL") + """

                        ALTER TABLE public.t ALTER COLUMN c1 DROP NOT NULL;"""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE ONLY public.t
                \tALTER COLUMN c1 DROP NOT NULL;""", script.trim());
    }

    // ------------------------------------------------------ RENAME COLUMN

    /**
     * The rename, stated directly. Against a database that already carries the
     * new name, an unread rename made the tool emit
     * {@code DROP COLUMN c3; ADD COLUMN c2;} - it destroyed the column and did
     * not even arrive where the file said.
     */
    @Test
    void aRenamedColumnReachesTheModelUnderItsNewName() throws Exception {
        PgDatabase byAlter = load(table("c1 integer,\n\tc2 integer") + """

                ALTER TABLE public.t RENAME COLUMN c2 TO c3;""");
        assertNull(columnOf(byAlter, "c2"), "the old name must leave the model");
        assertNotNull(columnOf(byAlter, "c3"), "and the new one must be there");

        String script = pipeline(load(table("c1 integer,\n\tc3 integer")), byAlter);
        assertEquals("", script.trim(),
                () -> "a renamed column must equal one written with the new name, got:\n" + script);
    }

    /**
     * The rename keeps the column where it was. Column order is part of the
     * table - {@code PgAbstractTable.computeHash} hashes the columns in order
     * and {@code StatementUtils.isColumnsOrderChanged} reports on it - and the
     * server keeps the renamed column at its {@code attnum} too.
     */
    @Test
    void aRenamedColumnKeepsItsPlace() throws Exception {
        PgDatabase byAlter = load(table("c1 integer,\n\tc2 integer,\n\tc3 integer") + """

                ALTER TABLE public.t RENAME COLUMN c2 TO r2;""");
        assertEquals("[c1, r2, c3]",
                tableOf(byAlter).getColumns().stream().map(c -> c.getName()).toList().toString());
    }

    /**
     * The copy trap. A renamed column is a new object - the name of a statement
     * is final - so every field the old one carried has to be carried over, and
     * a forgotten one is lost in silence. Everything declarable about a column
     * is written here in one table and asked for through the comparison.
     */
    @Test
    void aRenamedColumnKeepsWhatWasDeclaredWithIt() throws Exception {
        String declaration = "c1 bigint DEFAULT 5 CONSTRAINT t_c1_nn NOT NULL";
        PgDatabase byAlter = load(table(declaration) + """

                COMMENT ON COLUMN public.t.c1 IS 'the column';

                ALTER TABLE public.t ALTER COLUMN c1 SET STATISTICS 100;

                ALTER TABLE public.t ALTER COLUMN c1 SET STORAGE PLAIN;

                ALTER TABLE public.t RENAME COLUMN c1 TO r1;""");
        PgDatabase inline = load(table("r1 bigint DEFAULT 5 CONSTRAINT t_c1_nn NOT NULL") + """

                COMMENT ON COLUMN public.t.r1 IS 'the column';

                ALTER TABLE public.t ALTER COLUMN r1 SET STATISTICS 100;

                ALTER TABLE public.t ALTER COLUMN r1 SET STORAGE PLAIN;""");

        String script = pipeline(inline, byAlter);
        assertEquals("", script.trim(),
                () -> "a renamed column must carry everything it was declared with, got:\n" + script);
        assertTrue(columnOf(inline, "r1").compare(columnOf(byAlter, "r1")),
                "and the two must be one column, which an empty script cannot show by itself");
    }

    /**
     * The parent-chain trap, in the one place a column has a child. A
     * {@code NOT NULL} constraint reads the column it belongs to through its
     * parent - {@code PgConstraintNotNull.getDefinition} writes
     * {@code getParent().getQuotedName()} - so a renamed column that shared its
     * old constraint would go on describing the old name.
     */
    @Test
    void theNotNullOfARenamedColumnFollowsIt() throws Exception {
        PgDatabase db = load(table("c1 integer CONSTRAINT t_c1_nn NOT NULL") + """

                ALTER TABLE public.t RENAME COLUMN c1 TO r1;""");
        assertEquals("NOT NULL r1", tableOf(db).getConstraint("t_c1_nn").getDefinition(),
                "the constraint must describe the column it now hangs from");
    }

    /**
     * And the name that constraint keeps. Measured on PostgreSQL 18.4: after
     * {@code RENAME COLUMN c1 TO r1} the automatically named constraint is still
     * {@code t_c1_not_null} while its definition reads {@code NOT NULL r1}, so
     * the model must keep the old name too - the derived name is only ever
     * chosen when the constraint is created.
     *
     * <p>
     * That makes it a custom name from then on, which is what the reader would
     * build from the catalog, and what the {@code CREATE} has to write out.
     */
    @Test
    void theAutomaticNameOfANotNullSurvivesTheRename() throws Exception {
        PgDatabase db = load(table("c1 integer NOT NULL") + """

                ALTER TABLE public.t RENAME COLUMN c1 TO r1;""");
        assertNotNull(tableOf(db).getConstraint("t_c1_not_null"),
                "the server keeps the name the constraint was created with");
        assertEquals("""
                CREATE TABLE public.t (
                \tr1 integer CONSTRAINT t_c1_not_null NOT NULL
                )""", creationSql(db).trim());
    }

    /**
     * The rename resolves its old name too, and for the same reason: there is no
     * {@code IF EXISTS} to say otherwise, and every other name in this statement
     * already has to be there.
     */
    @Test
    void renamingAColumnTheFileNeverDeclaredIsReported() throws Exception {
        var settings = new CoreSettings();
        load(table("c1 integer") + "\n\nALTER TABLE public.t RENAME COLUMN nosuch TO r1;", settings);
        assertFalse(settings.getErrors().isEmpty(),
                "renaming a column that is not there must be reported");
    }

    /** And the inheriting table is exempt from that too. */
    @Test
    void renamingAnInheritedColumnIsNotReported() throws Exception {
        var settings = new CoreSettings();
        load(inheritingTable("RENAME COLUMN c1 TO r1"), settings);
        assertTrue(settings.getErrors().isEmpty(),
                () -> "a column of the parent is not an unknown name, got: " + settings.getErrors());
    }

    // ------------------------------------------------------------- fixtures

    /** A table whose only column comes from its parent, plus one action on it. */
    private static String inheritingTable(String action) {
        return """
                CREATE TABLE public.p (
                \tc1 integer
                );

                CREATE TABLE public.t (
                ) INHERITS (public.p);

                ALTER TABLE public.t %s;""".formatted(action);
    }

    private static String table(String body) {
        return """
                CREATE TABLE public.t (
                \t%s
                );""".formatted(body);
    }

    // -------------------------------------------------------------- helpers

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return load(sql, new CoreSettings());
    }

    private static PgDatabase load(String sql, CoreSettings settings) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "table column test", settings).load();
    }

    private static PgAbstractTable tableOf(PgDatabase db) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA);
        PgAbstractTable table = schema == null ? null : schema.getTable(TABLE);
        assertNotNull(table, "no table was parsed");
        return table;
    }

    private static PgColumn columnOf(PgDatabase db, String name) {
        return tableOf(db).getColumn(name);
    }

    private static String creationSql(PgDatabase db) {
        var settings = new CoreSettings();
        var script = new org.pgcodekeeper.core.script.SQLScript(settings, "\n");
        tableOf(db).getCreationSQL(script);
        return script.getFullScript();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
