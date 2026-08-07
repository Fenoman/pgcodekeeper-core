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
import org.pgcodekeeper.core.database.pg.schema.PgConstraint;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The four alternatives of {@code ALTER TABLE} that state something about a
 * constraint a {@code CREATE TABLE} could have stated: {@code DROP CONSTRAINT},
 * {@code VALIDATE CONSTRAINT}, {@code ALTER CONSTRAINT} and - closed after
 * them - {@code RENAME CONSTRAINT}. All four parsed and reached no writer.
 *
 * <p>
 * The table's counterpart to {@link PgAlterDomainConstraintTest}, and the same
 * failure mode - an alternative the grammar accepts but no writer reads - on a
 * wider surface: a table constraint may be a primary key, a foreign key, unique,
 * check, exclude, or a named {@code NOT NULL}, and the last of those does not
 * live in the table's constraint map at all.
 *
 * <p>
 * The three damage differently and are asserted differently. An unread
 * {@code DROP CONSTRAINT} leaves the model holding a constraint the file
 * removed, so the database keeps it and no script mentions it. An unread
 * {@code VALIDATE} or {@code ALTER CONSTRAINT} may instead leave a difference
 * the diff tree sees while the script stays empty -
 * {@code PgConstraint.appendAlterSQL:138} only writes a {@code VALIDATE} in the
 * other direction - so where that is possible the comparison is asked alongside
 * the script, because an empty-script assertion cannot see it by construction.
 */
class PgAlterTableConstraintTest {

    private static final String SCHEMA = "public";
    private static final String TABLE = "t";

    // ---------------------------------------------------------------- DROP

    /**
     * The defect, stated directly: a project file that drops a constraint must
     * have that drop reach the database. With the clause unread the model kept
     * the constraint, the two sides compared equal, and the database went on
     * holding a {@code CHECK} the project had removed.
     */
    @Test
    void aConstraintDroppedInAProjectFileIsDroppedFromTheDatabase() throws Exception {
        String script = pipeline(tableWithCheck(""), tableWithCheck("""

                ALTER TABLE public.t DROP CONSTRAINT t_chk;"""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t
                \tDROP CONSTRAINT t_chk;""", script.trim());
    }

    /**
     * The sixth kind of constraint, and the reason the removal has to look in
     * two places. A named {@code NOT NULL} is not in the table's constraint map:
     * it hangs off {@code PgColumn.notNullConstraint}, so
     * {@code getConstraints()} of a table whose only constraint is this one
     * returns an empty collection. Dropping it makes the column nullable, which
     * is what PostgreSQL itself does - measured on 18.4, {@code attnotnull}
     * goes to false.
     */
    @Test
    void aNamedNotNullDroppedInAProjectFileReachesTheColumn() throws Exception {
        assertTrue(tableOf(tableWithNamedNotNull("")).getConstraints().isEmpty(),
                "a named NOT NULL is not in the constraint map - that is what makes this case its own");

        String script = pipeline(tableWithNamedNotNull(""), tableWithNamedNotNull("""

                ALTER TABLE public.t DROP CONSTRAINT t_c1_nn;"""));
        // ONLY is the column path's own doing (PgColumn.compareNotNull), not
        // this statement's: a NOT NULL is dropped on the named table alone
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE ONLY public.t
                \tALTER COLUMN c1 DROP NOT NULL;""", script.trim());
    }

    // ------------------------------------------------------------ VALIDATE

    /**
     * A file that validates a constraint the database still holds as
     * {@code NOT VALID} must produce that validation. Unread, the model's
     * constraint stayed {@code NOT VALID} too, matched the database exactly, and
     * the file's instruction never left the project.
     */
    @Test
    void aValidatedConstraintIsValidatedInTheDatabase() throws Exception {
        String script = pipeline(tableWithNotValidCheck(""), tableWithNotValidCheck("""

                ALTER TABLE public.t VALIDATE CONSTRAINT t_chk;"""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t
                \tVALIDATE CONSTRAINT t_chk;""", script.trim());
    }

    /**
     * The other direction of the validation, and the one no script can speak
     * for: against a database whose constraint is already valid, a file that
     * adds it {@code NOT VALID} and then validates it describes the very same
     * constraint. Unread, the model keeps {@code NOT VALID} and the two part -
     * but {@code PgConstraint.appendAlterSQL} only ever writes a
     * {@code VALIDATE} in the opposite direction, so the script stays empty
     * either way and the comparison is what has to be asked.
     */
    @Test
    void validatingWhatTheDatabaseAlreadyValidatedProducesNothing() throws Exception {
        PgDatabase valid = tableWithCheck("");
        PgDatabase validatedByAlter = tableWithNotValidCheck("""

                ALTER TABLE public.t VALIDATE CONSTRAINT t_chk;""");

        String script = pipeline(valid, validatedByAlter);
        assertEquals("", script.trim(),
                () -> "a validated constraint must read as the database's valid one, got:\n" + script);
        assertTrue(constraintOf(valid, "t_chk").compare(constraintOf(validatedByAlter, "t_chk")),
                "and the two must be one object, which an empty script cannot show by itself");
    }

    // ------------------------------------------------------- ALTER CONSTRAINT

    /**
     * {@code ALTER CONSTRAINT} states deferrability. Measured on PostgreSQL
     * 17.10 and 18.4 alike, the two words are read as a pair - a lone
     * {@code DEFERRABLE} leaves the constraint {@code INITIALLY IMMEDIATE} - so
     * the clause describes the same end state the inline form of an
     * {@code ADD CONSTRAINT} would.
     *
     * <p>
     * This is the round trip that does not close, and the sharpest form of the
     * defect: {@code PgConstraintFk.compareExtraOptions:95} emits
     * {@code ALTER TABLE ... ALTER CONSTRAINT} into its own migrations - a
     * foreign key deliberately keeps deferrability out of
     * {@code compareUnalterable} so that it can be changed in place instead of
     * revalidating every row - and the parser could not read that statement
     * back. Unread, the clause therefore shows up as the tool undoing itself:
     * {@code ALTER CONSTRAINT t_fk NOT DEFERRABLE}, measured.
     */
    @Test
    void deferrabilityStatedByAlterConstraintReachesTheModel() throws Exception {
        PgDatabase inline = tableWithFk(" DEFERRABLE INITIALLY DEFERRED", "");
        PgDatabase byAlter = tableWithFk("", """

                ALTER TABLE public.t ALTER CONSTRAINT t_fk DEFERRABLE INITIALLY DEFERRED;""");

        String script = pipeline(inline, byAlter);
        assertEquals("", script.trim(),
                () -> "deferrability written by ALTER CONSTRAINT must build the same constraint, got:\n" + script);
        assertTrue(constraintOf(inline, "t_fk").compare(constraintOf(byAlter, "t_fk")),
                "and the two must be one object");
    }

    /**
     * The other half of the clause. Measured on 18.4: {@code ALTER CONSTRAINT
     * ... NO INHERIT} is accepted for a {@code NOT NULL} constraint and rejected
     * for anything else, so this is the kind the option belongs to - and it is
     * also the kind that lives outside the constraint map.
     *
     * <p>
     * The same round trip as the deferrability above:
     * {@code PgConstraintNotNull.compareExtraOptions:90} writes this exact
     * statement, and unread it comes back as the reverse -
     * {@code ALTER CONSTRAINT t_c1_nn INHERIT}, measured.
     */
    @Test
    void inheritabilityStatedByAlterConstraintReachesTheModel() throws Exception {
        PgDatabase inline = tableWithNamedNotNull("", " NO INHERIT");
        PgDatabase byAlter = tableWithNamedNotNull("""

                ALTER TABLE public.t ALTER CONSTRAINT t_c1_nn NO INHERIT;""", "");

        String script = pipeline(inline, byAlter);
        assertEquals("", script.trim(),
                () -> "NO INHERIT written by ALTER CONSTRAINT must build the same constraint, got:\n" + script);
        assertTrue(constraintOf(inline, "t_c1_nn").compare(constraintOf(byAlter, "t_c1_nn")),
                "and the two must be one object");
    }

    /**
     * One change, one statement. A foreign key keeps deferrability and
     * enforcement out of {@code compareUnalterable} so that both can be altered
     * in place, and {@code PgConstraintFk.compareExtraOptions} builds the single
     * {@code ALTER CONSTRAINT} that states them - but it added that one builder
     * to the script twice, once inside the {@code NOT DEFERRABLE} branch and
     * once at the end, with the enforcement clause appended in between.
     *
     * <p>
     * When only deferrability moves, the two strings are equal and
     * {@code SQLScript} keeps statements in a {@code Set}, so the duplicate
     * collapses and nothing shows - which is why the corpus fixture
     * {@code alter_foreign_constraint} holds one line and stayed green
     * throughout. It takes a key changing both at once to pull the two strings
     * apart, and then both reach the migration.
     *
     * <p>
     * Measured on PostgreSQL 18.4: the combined
     * {@code ALTER CONSTRAINT ... NOT DEFERRABLE NOT ENFORCED} is one legal
     * statement, and a bare {@code NOT DEFERRABLE} leaves enforcement as it
     * found it - so the pair does land on the right state, in either order. The
     * redundant statement is the whole of the damage, and it is the migration
     * that carries it.
     */
    @Test
    void aForeignKeyChangingBothDeferrabilityAndEnforcementIsAlteredByOneStatement() throws Exception {
        String script = pipeline(tableWithFk(" DEFERRABLE INITIALLY DEFERRED", ""),
                tableWithFk(" NOT ENFORCED", ""));

        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t
                \tALTER CONSTRAINT t_fk NOT DEFERRABLE NOT ENFORCED;""", script.trim(),
                () -> "one ALTER CONSTRAINT states both clauses, got:\n" + script);
        assertEquals(1, countOf(script, "ALTER CONSTRAINT"),
                () -> "and the migration must carry it once, got:\n" + script);
    }

    /**
     * The other spelling of the same branch, so the fix cannot be one that only
     * happens to suit the word {@code NOT}. Measured on 18.4 too:
     * {@code ALTER CONSTRAINT ... NOT DEFERRABLE ENFORCED} is accepted and
     * leaves the key enforced and not deferrable.
     */
    @Test
    void theSameHoldsWhenEnforcementIsTurnedBackOn() throws Exception {
        String script = pipeline(tableWithFk(" DEFERRABLE INITIALLY DEFERRED NOT ENFORCED", ""),
                tableWithFk("", ""));

        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t
                \tALTER CONSTRAINT t_fk NOT DEFERRABLE ENFORCED;""", script.trim(),
                () -> "one ALTER CONSTRAINT states both clauses, got:\n" + script);
        assertEquals(1, countOf(script, "ALTER CONSTRAINT"),
                () -> "and the migration must carry it once, got:\n" + script);
    }

    /**
     * The branch the duplicate add lived in, on its own. Deferrability moves and
     * enforcement does not, so this is the case the corpus fixture
     * {@code alter_foreign_constraint} has always held - and the case a
     * {@code Set} of statements cannot speak for, because there the two adds
     * produced the same string and one of them vanished on the way in. Asserted
     * here as a count so that dropping an add too many is a failure and not a
     * silent loss.
     */
    @Test
    void aForeignKeyChangingOnlyDeferrabilityIsStillAlteredExactlyOnce() throws Exception {
        String script = pipeline(tableWithFk(" DEFERRABLE INITIALLY DEFERRED", ""), tableWithFk("", ""));

        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t
                \tALTER CONSTRAINT t_fk NOT DEFERRABLE;""", script.trim(),
                () -> "the deferrability alone is one statement, got:\n" + script);
        assertEquals(1, countOf(script, "ALTER CONSTRAINT"),
                () -> "and it must be there once, neither twice nor not at all, got:\n" + script);
    }

    private static int countOf(String script, String needle) {
        int count = 0;
        for (int i = script.indexOf(needle); i >= 0; i = script.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    // --------------------------------------------------------- the modifier

    /**
     * The modifier, honoured rather than ignored. Without {@code IF EXISTS} a
     * drop names a constraint that has to be there, so an unknown name is the
     * same unresolved reference this very parser already reports for the table
     * it was told to alter, for a column it was told to change, and for a rule
     * it was told to enable. A table constraint was the one name in the
     * statement that resolved to nothing and said nothing.
     */
    @Test
    void droppingAConstraintTheFileNeverDeclaredIsReported() throws Exception {
        var settings = new CoreSettings();
        load(checkTable("") + "\nALTER TABLE public.t DROP CONSTRAINT nosuch_chk;\n", settings);
        assertFalse(settings.getErrors().isEmpty(),
                "dropping a constraint that is not there must be reported");
    }

    /**
     * And the escape hatch the modifier exists for: with {@code IF EXISTS} the
     * same statement is silent, and leaves the constraint the table does have
     * alone.
     */
    @Test
    void ifExistsMakesTheDropOfAnUnknownConstraintSilent() throws Exception {
        var settings = new CoreSettings();
        PgDatabase db = load(checkTable("") + "\nALTER TABLE public.t DROP CONSTRAINT IF EXISTS nosuch_chk;\n",
                settings);
        assertTrue(settings.getErrors().isEmpty(),
                () -> "IF EXISTS must make the drop silent, got: " + settings.getErrors());
        assertNotNull(constraintOf(db, "t_chk"),
                "and it must leave the constraint the table does have alone");
        assertEquals("", pipeline(tableWithCheck(""), db).trim(),
                "so the table is the one the file declared");
    }

    // ------------------------------------------------------------- fixtures

    private static String checkTable(String trailer) {
        return """
                CREATE TABLE public.t (
                \tc1 integer,
                \tCONSTRAINT t_chk CHECK ((c1 > 0))
                );%s""".formatted(trailer);
    }

    /** A table declaring a CHECK, plus whatever the caller appends. */
    private static PgDatabase tableWithCheck(String trailer) throws Exception {
        return load(checkTable(trailer));
    }

    /**
     * A table whose CHECK is added unvalidated. Written as a separate
     * {@code ALTER} because that is how an export spells an unvalidated
     * constraint.
     */
    private static PgDatabase tableWithNotValidCheck(String trailer) throws Exception {
        return load("""
                CREATE TABLE public.t (
                \tc1 integer
                );

                ALTER TABLE public.t
                \tADD CONSTRAINT t_chk CHECK ((c1 > 0)) NOT VALID;%s""".formatted(trailer));
    }

    private static PgDatabase tableWithNamedNotNull(String trailer) throws Exception {
        return tableWithNamedNotNull(trailer, "");
    }

    /**
     * A table whose only constraint is a named {@code NOT NULL} - the kind that
     * does not reach {@code getConstraints()}.
     */
    private static PgDatabase tableWithNamedNotNull(String trailer, String inherit) throws Exception {
        return load("""
                CREATE TABLE public.t (
                \tc1 integer CONSTRAINT t_c1_nn NOT NULL%s
                );%s""".formatted(inherit, trailer));
    }

    /** A foreign key, the kind deferrability belongs to. */
    private static PgDatabase tableWithFk(String inline, String trailer) throws Exception {
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

    // -------------------------------------------------------- RENAME

    /**
     * The fourth alternative, closed after the other three. It states content
     * and not identity, which is what puts it here rather than beside
     * {@code RENAME TO} and {@code SET SCHEMA}: renaming a child leaves the
     * table's own identity untouched, while the {@code CREATE} goes on writing
     * the old constraint name. The same reading {@code RENAME COLUMN} already
     * gets.
     *
     * <p>
     * Unread it was the worst of the four, because the model then held a
     * constraint the database did not: against a database already carrying the
     * new name the tool emitted the pair {@code DROP CONSTRAINT c_new} /
     * {@code ADD CONSTRAINT c_old}, measured - dropping the constraint and not
     * even arriving where the file said.
     */
    @Test
    void aRenamedConstraintReachesTheModel() throws Exception {
        PgDatabase byAlter = load(check("c_old") + """

                ALTER TABLE public.t RENAME CONSTRAINT c_old TO c_new;""");
        assertNull(constraintOf(byAlter, "c_old"), "the old name must leave the model");
        assertNotNull(constraintOf(byAlter, "c_new"), "and the new one must be there");

        assertEquals("", pipeline(load(check("c_new")), byAlter).trim(),
                "a constraint renamed by ALTER must build what the CREATE of that name builds");
    }

    /** The other direction: against a database still carrying the old name. */
    @Test
    void aRenamedConstraintReachesTheDatabase() throws Exception {
        String script = pipeline(load(check("c_old")), load(check("c_old") + """

                ALTER TABLE public.t RENAME CONSTRAINT c_old TO c_new;"""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t
                \tDROP CONSTRAINT c_old;

                ALTER TABLE public.t
                \tADD CONSTRAINT c_new CHECK ((c1 > 0));""", script.trim());
    }

    /**
     * The name of a statement is final, so a rename is a new object and every
     * field of the old one has to be carried into it - the same reason
     * {@code PgColumn.renamedCopy} exists. A copy that dropped a field would
     * leave the constraint comparing unequal to the identical one written under
     * that name from the start, which is what this asserts across all five
     * kinds that live in the constraint map.
     *
     * <p>
     * The fixtures carry a value in every field that kind has, deferrability
     * and {@code NOT VALID} included, so a forgotten line in any of the five
     * copy methods shows up here rather than in a later migration.
     */
    @Test
    void everyKindCarriesItsWholeSelfIntoTheNewName() throws Exception {
        for (String body : new String[] {
                "CONSTRAINT %s CHECK ((c1 > 0)) NO INHERIT",
                "CONSTRAINT %s PRIMARY KEY (c1)",
                "CONSTRAINT %s UNIQUE (c1)",
                "CONSTRAINT %s EXCLUDE USING gist (c1 WITH =)",
                "CONSTRAINT %s FOREIGN KEY (c1) REFERENCES public.p(id) ON DELETE CASCADE"
                        + " DEFERRABLE INITIALLY DEFERRED",
        }) {
            String renamed = renameable(body.formatted("c_old")) + """

                    ALTER TABLE public.t RENAME CONSTRAINT c_old TO c_new;""";
            assertEquals("", pipeline(load(renameable(body.formatted("c_new"))), load(renamed)).trim(),
                    () -> "a renamed constraint must carry its whole self, failed on: " + body);
        }
    }

    /**
     * The sixth kind, which is not in the constraint map at all - a named
     * {@code NOT NULL} hangs off {@code PgColumn.notNullConstraint}, so a
     * rename that searched only the map would silently do nothing.
     *
     * <p>
     * It is also the one kind whose old state the tool could already write out,
     * through {@code getRenameCommand}: before the fix the migration read
     * {@code RENAME CONSTRAINT nn_new TO nn_old}, measured - the file's own
     * rename, run backwards.
     *
     * <p>
     * The copy is re-parented to the column, as {@code PgColumn.renamedCopy}
     * re-parents its own: {@code getDefinition} writes
     * {@code getParent().getQuotedName()}, and {@code computeNamesHash} walks
     * the same chain.
     */
    @Test
    void aNamedNotNullIsRenamedWhereItActuallyLives() throws Exception {
        PgDatabase byAlter = load(notNull("nn_old") + """

                ALTER TABLE public.t RENAME CONSTRAINT nn_old TO nn_new;""");
        assertNull(constraintOf(byAlter, "nn_old"), "the old name must leave the model");
        assertNotNull(constraintOf(byAlter, "nn_new"), "and the new one must be findable where it lives");

        assertEquals("", pipeline(load(notNull("nn_new")), byAlter).trim(),
                "a renamed NOT NULL must build what the CREATE of that name builds");
    }

    /**
     * A name that matches nothing is reported, the way the table's own name is
     * and a dropped column's is - a rename is not an {@code IF EXISTS} clause
     * and has no word with which to say the constraint may not be there.
     */
    @Test
    void renamingAConstraintTheTableHasNotIsReported() throws Exception {
        var settings = new CoreSettings();
        load(check("c_old") + """

                ALTER TABLE public.t RENAME CONSTRAINT nosuch TO c_new;""", settings);
        assertFalse(settings.getErrors().isEmpty(),
                "a rename has no way to say the constraint may not be there");
    }

    /**
     * And a name the table already uses raises, as it does on the server - the
     * same answer {@code addConstraint} gives a duplicate declared outright,
     * through the very same check.
     */
    @Test
    void renamingAConstraintOntoANameTheTableAlreadyUsesIsReported() throws Exception {
        var settings = new CoreSettings();
        load("""
                CREATE TABLE public.t (
                \tc1 integer,
                \tCONSTRAINT c_old CHECK ((c1 > 0)),
                \tCONSTRAINT c_new CHECK ((c1 < 9))
                );

                ALTER TABLE public.t RENAME CONSTRAINT c_old TO c_new;""", settings);
        assertFalse(settings.getErrors().isEmpty(),
                "two constraints of one table cannot share a name");
    }

    // ------------------------------------------------------------ fixtures

    private static String check(String name) {
        return """
                CREATE TABLE public.t (
                \tc1 integer,
                \tCONSTRAINT %s CHECK ((c1 > 0))
                );""".formatted(name);
    }

    private static String notNull(String name) {
        return """
                CREATE TABLE public.t (
                \tc1 integer CONSTRAINT %s NOT NULL
                );""".formatted(name);
    }

    /** A table with a parent to point a foreign key at, and a body to fill. */
    private static String renameable(String body) {
        return """
                CREATE TABLE public.p (
                \tid integer NOT NULL,
                \tCONSTRAINT p_pkey PRIMARY KEY (id)
                );

                CREATE TABLE public.t (
                \tc1 integer NOT NULL,
                \t%s
                );""".formatted(body);
    }

    // -------------------------------------------------------------- helpers

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return load(sql, new CoreSettings());
    }

    private static PgDatabase load(String sql, CoreSettings settings) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "table constraint test", settings).load();
    }

    private static PgAbstractTable tableOf(PgDatabase db) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA);
        PgAbstractTable table = schema == null ? null : schema.getTable(TABLE);
        assertNotNull(table, "no table was parsed");
        return table;
    }

    /**
     * The table's constraint by name. Goes through the table's own lookup on
     * purpose: that one already searches the constraint map and the columns'
     * {@code NOT NULL} constraints both, which is what makes the sixth kind
     * findable at all.
     */
    private static PgConstraint constraintOf(PgDatabase db, String name) {
        return tableOf(db).getConstraint(name);
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
