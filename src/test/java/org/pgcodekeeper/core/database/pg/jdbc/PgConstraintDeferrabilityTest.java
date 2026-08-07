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
package org.pgcodekeeper.core.database.pg.jdbc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractTable;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgConstraint;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * {@code INITIALLY DEFERRED} implies {@code DEFERRABLE}, and the model has to
 * apply that implication because PostgreSQL applies it before anything is
 * written to the catalog.
 * <p>
 * Measured on 17.10: a constraint declared {@code UNIQUE (a) INITIALLY
 * DEFERRED} - inline in a {@code CREATE TABLE} or added by an {@code ALTER
 * TABLE}, indifferently - lands in {@code pg_constraint} as
 * {@code condeferrable = true, condeferred = true}, and
 * {@code pg_get_constraintdef} renders it {@code UNIQUE (a) DEFERRABLE
 * INITIALLY DEFERRED}. Spelling the two words together is therefore not a
 * second constraint but the same one written out in full; spelling
 * {@code NOT DEFERRABLE INITIALLY DEFERRED} is not a third but an error -
 * {@code constraint declared INITIALLY DEFERRED must be DEFERRABLE}, raised by
 * the grammar itself, for a table constraint and for an {@code ALTER
 * CONSTRAINT} alike.
 * <p>
 * Without the implication the two sides could not converge: the file built
 * {@code (deferrable = false, initially = true)} while
 * {@link PgConstraintsReader} - which re-parses exactly the
 * {@code pg_get_constraintdef} text above - built {@code (true, true)}, and
 * both halves are in {@code compare} and in the hash. The constraint read as
 * changed on every run, and neither run could change it.
 * <p>
 * The database side is driven here by running {@link PgConstraintsReader}
 * itself over a mocked catalog row, rather than by a helper that repeats what
 * the reader does: the reader's own string - {@code ALTER TABLE noname ADD
 * CONSTRAINT noname } plus the definition - is a part of what is under test.
 */
class PgConstraintDeferrabilityTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "t";
    private static final String COLUMN_NAME = "a";
    private static final String CONSTRAINT_NAME = "t_u";

    /**
     * What {@code pg_get_constraintdef} returns for a constraint declared with
     * {@code INITIALLY DEFERRED} alone - measured on PostgreSQL 17.10, the
     * catalog never renders the short spelling back.
     */
    private static final String CATALOG_DEFINITION = "UNIQUE (a) DEFERRABLE INITIALLY DEFERRED";

    /** The short spelling, which is the whole point: a file may write only this. */
    private static final String SHORT = "INITIALLY DEFERRED";

    /** And the long one, which is what the same statement means. */
    private static final String SPELLED_OUT = "DEFERRABLE INITIALLY DEFERRED";

    // ------------------------------------------------------------ both sides

    /**
     * The defect, stated as the two sides it parts. A project file writing the
     * short spelling and the database holding that very constraint have to be
     * one object; until the implication was applied they were two, in both
     * {@code compare} and the hash.
     */
    @Test
    void theCatalogsConstraintAndTheFilesShortSpellingAreOneConstraint() throws Exception {
        PgConstraint fromCatalog = readFromCatalog(CATALOG_DEFINITION);
        PgConstraint fromFile = constraintOf(tableConstraint(SHORT));

        assertConverge(fromCatalog, fromFile);
    }

    /**
     * The same pair through the whole pipeline, because an object comparison and
     * a generated script are two different questions: either one can hold while
     * the other does not.
     */
    @Test
    void theTwoSpellingsOfOneConstraintProduceNoMigration() throws Exception {
        String script = pipeline(tableConstraint(SPELLED_OUT), tableConstraint(SHORT));
        assertEquals("", script.trim(),
                () -> "the two spellings of one constraint must produce no migration, got:\n" + script);

        assertConverge(constraintOf(tableConstraint(SPELLED_OUT)), constraintOf(tableConstraint(SHORT)));
    }

    /**
     * The second route into the same formula. A constraint written beside its
     * column reaches {@code appendConstrCommon} from
     * {@code PgTableAbstract.addColumn} and not from
     * {@code processTableConstraintBlank}, so it is a separate call site that
     * would keep the defect if only the other were fixed.
     */
    @Test
    void aColumnLevelConstraintTakesTheImplicationToo() throws Exception {
        PgDatabase shortSpelling = load("""
                CREATE TABLE %s.%s (
                \t%s integer CONSTRAINT %s UNIQUE %s
                );""".formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, CONSTRAINT_NAME, SHORT));
        PgDatabase spelledOut = load("""
                CREATE TABLE %s.%s (
                \t%s integer CONSTRAINT %s UNIQUE %s
                );""".formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, CONSTRAINT_NAME, SPELLED_OUT));

        assertConverge(constraintOf(shortSpelling), constraintOf(spelledOut));
    }

    /**
     * And the third, which is a formula of its own rather than a caller of the
     * shared one: {@code PgAlterTable.alterConstraint} reads the same two
     * clauses off {@code ALTER CONSTRAINT}. Measured on 17.10 - {@code ALTER
     * TABLE t ALTER CONSTRAINT c INITIALLY DEFERRED} on a key created without
     * either word leaves {@code condeferrable = true} - so the server applies
     * the implication here too, and the two formulas have to move together or
     * the two spellings of one constraint stop meaning one thing again.
     */
    @Test
    void alterConstraintTakesTheImplicationToo() throws Exception {
        PgDatabase inline = foreignKey(" " + SPELLED_OUT, "");
        PgDatabase byAlter = foreignKey("", """

                ALTER TABLE public.t ALTER CONSTRAINT t_fk %s;""".formatted(SHORT));

        String script = pipeline(inline, byAlter);
        assertEquals("", script.trim(),
                () -> "the implication must reach ALTER CONSTRAINT as well, got:\n" + script);
        assertConverge(constraintOf(inline, "t_fk"), constraintOf(byAlter, "t_fk"));
    }

    // ----------------------------------------------------------- the bounds

    /**
     * The implication fires where the server applies it and nowhere else, which
     * a formula reading {@code initially} alone would get wrong in four places.
     * Every case is measured on 17.10 and observed here through the statement
     * the tool would send, because deferrability has no getter and the emitted
     * {@code ADD CONSTRAINT} is what the two halves are ultimately for.
     */
    @Test
    void theImplicationFiresOnlyWhereTheServerAppliesIt() throws Exception {
        // condeferrable = f, condeferred = f
        assertEmitted("", "");
        // NOT DEFERRABLE: condeferrable = f, condeferred = f
        assertEmitted("NOT DEFERRABLE", "");
        // INITIALLY IMMEDIATE alone: condeferrable = f, condeferred = f
        assertEmitted("INITIALLY IMMEDIATE", "");
        // DEFERRABLE alone: condeferrable = t, condeferred = f
        assertEmitted("DEFERRABLE", " DEFERRABLE");
        // and the case this test class exists for
        assertEmitted(SHORT, " DEFERRABLE INITIALLY DEFERRED");
        assertEmitted(SPELLED_OUT, " DEFERRABLE INITIALLY DEFERRED");
    }

    /**
     * A constraint that really is deferrable must still read as different from
     * one that is not, or the implication would have been bought by making the
     * field say nothing.
     */
    @Test
    void aGenuineDifferenceInDeferrabilityStillReadsAsChanged() throws Exception {
        PgConstraint deferred = constraintOf(tableConstraint(SHORT));
        PgConstraint immediate = constraintOf(tableConstraint(""));

        assertFalse(deferred.compare(immediate), "a deferred constraint is not an immediate one");
        assertFalse(immediate.compare(deferred), "compare must be symmetric");
    }

    // -------------------------------------------------------------- fixtures

    /** The table-level form, with whatever deferrability clause the caller wants. */
    private static PgDatabase tableConstraint(String clause) throws Exception {
        return load("""
                CREATE TABLE %s.%s (
                \t%s integer,
                \tCONSTRAINT %s UNIQUE (%s)%s
                );""".formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, CONSTRAINT_NAME, COLUMN_NAME,
                clause.isEmpty() ? "" : " " + clause));
    }

    /** A foreign key, which is the kind {@code ALTER CONSTRAINT} is written for. */
    private static PgDatabase foreignKey(String inline, String trailer) throws Exception {
        return load("""
                CREATE TABLE public.p (
                \tid integer
                );

                ALTER TABLE public.p
                \tADD CONSTRAINT p_pkey PRIMARY KEY (id);

                CREATE TABLE public.t (
                \tpid integer
                );

                ALTER TABLE public.t
                \tADD CONSTRAINT t_fk FOREIGN KEY (pid) REFERENCES public.p(id)%s;%s"""
                .formatted(inline, trailer));
    }

    // --------------------------------------------------------------- helpers

    private static void assertConverge(PgConstraint a, PgConstraint b) {
        assertTrue(a.compare(b), "expected the two constraints to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged constraints must hash the same");
    }

    /**
     * The clause a file writes, and the clause the tool writes back out of the
     * model it built from it.
     */
    private static void assertEmitted(String written, String expected) throws Exception {
        String sql = constraintScript(constraintOf(tableConstraint(written)));
        assertEquals("ALTER TABLE public.t\n\tADD CONSTRAINT t_u UNIQUE (a)%s;".formatted(expected), sql.trim(),
                () -> "written as '" + written + '\'');
    }

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "constraint deferrability test", new CoreSettings()).load();
    }

    private static PgConstraint constraintOf(PgDatabase db) {
        return constraintOf(db, CONSTRAINT_NAME);
    }

    private static PgConstraint constraintOf(PgDatabase db, String name) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA_NAME);
        assertNotNull(schema, "no schema was parsed");
        PgAbstractTable table = schema.getTable(TABLE_NAME);
        assertNotNull(table, "no table was parsed");
        PgConstraint constr = table.getConstraint(name);
        assertNotNull(constr, "no constraint was parsed");
        return constr;
    }

    private static String constraintScript(PgConstraint constr) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, constr.getSeparator());
        constr.getCreationSQL(script);
        return script.getFullScript();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }

    /**
     * The database side, run rather than mirrored: one mocked {@code
     * pg_constraint} row through {@link PgConstraintsReader#processResult}, then
     * the loader's parse queue drained the way a real load drains it, because
     * the constraint is filled by a deferred ANTLR finalizer.
     */
    private static PgConstraint readFromCatalog(String definition) throws Exception {
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(true);

        ResultSet res = mock(ResultSet.class);
        when(res.getString("relname")).thenReturn(TABLE_NAME);
        when(res.getString("conname")).thenReturn(CONSTRAINT_NAME);
        when(res.getString("contype")).thenReturn("u");
        when(res.getString("definition")).thenReturn(definition);

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);
        var column = new PgColumn(COLUMN_NAME);
        column.setType("integer");
        table.addColumn(column);

        try (TestLoader loader = new TestLoader(settings)) {
            new PgConstraintsReader(loader).processResult(res, schema);
            loader.drain();
        }

        assertTrue(settings.getErrors().isEmpty(),
                () -> "the catalog definition must parse, otherwise the finalizer never runs: "
                        + settings.getErrors());

        PgConstraint constr = table.getConstraint(CONSTRAINT_NAME);
        assertNotNull(constr, "the reader added no constraint");
        return constr;
    }

    /**
     * {@code finishLoaders} is protected on {@code AbstractLoader}, so a subclass
     * reaches it without reflection. Nothing queries - the reader is handed its
     * row directly - so the connector exists only to satisfy the constructor.
     */
    private static final class TestLoader extends PgJdbcLoader {

        private TestLoader(CoreSettings settings) {
            super(offlineConnector(), null, settings);
        }

        private void drain() throws InterruptedException, IOException {
            finishLoaders();
        }
    }

    private static IJdbcConnector offlineConnector() {
        return new IJdbcConnector() {

            @Override
            public Connection getConnection() throws IOException {
                throw new AssertionError("this test must not open a connection");
            }

            @Override
            public String getBatchDelimiter() {
                return null;
            }

            @Override
            public String getUrl() {
                return "jdbc:test";
            }

            @Override
            public String getDbName() {
                return "test";
            }
        };
    }
}
