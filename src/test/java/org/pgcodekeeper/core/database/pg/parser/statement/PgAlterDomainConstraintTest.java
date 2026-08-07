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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgDomain;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The two constraint-shaped alternatives of {@code ALTER DOMAIN} that state
 * something a {@code CREATE DOMAIN} could have stated: {@code DROP CONSTRAINT}
 * and {@code VALIDATE CONSTRAINT}.
 * <p>
 * A third class beside {@code PgTypesReaderDomainTest} and
 * {@link PgAlterDomainNotNullTest} for the same reason those two are apart: the
 * first speaks for the domain's expression-shaped members and their
 * normalization contract, the second for a flag, and these two for the
 * constraint list. What all three share is the writer,
 * {@link PgAlterDomain#parseObject()}, and the failure mode of an alternative
 * the grammar accepts but no writer reads.
 * <p>
 * The two are not damaging in the same way, which is why they are asserted
 * differently. An unread {@code DROP CONSTRAINT} leaves the model holding a
 * constraint the file removed, so the database keeps it and no script mentions
 * it. An unread {@code VALIDATE CONSTRAINT} leaves the model's copy
 * {@code NOT VALID}: against a database that has already validated the
 * constraint that is a difference the diff tree sees while the script stays
 * empty, so the script alone cannot assert it and the comparison is asked too.
 */
class PgAlterDomainConstraintTest {

    private static final String SCHEMA_NAME = "public";
    private static final String DOMAIN_NAME = "positive_amount";
    private static final String DOMAIN_TYPE = "integer";
    private static final String CONSTRAINT_NAME = "dom_check";
    private static final String EXPRESSION = "((VALUE > 0))";

    /**
     * The defect, stated directly: a project file that drops a constraint must
     * have that drop reach the database. With the clause unread the model kept
     * the constraint, the two sides compared equal, and the database went on
     * holding a {@code CHECK} the project had removed.
     */
    @Test
    void aConstraintDroppedInAProjectFileIsDroppedFromTheDatabase() throws Exception {
        String script = pipeline(domainWithConstraint(), constraintDroppedByAlter(""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER DOMAIN %s.%s
                \tDROP CONSTRAINT %s;""".formatted(SCHEMA_NAME, DOMAIN_NAME, CONSTRAINT_NAME), script.trim());
    }

    /**
     * The same for the validation: a file that validates a constraint the
     * database still holds as {@code NOT VALID} must produce that validation.
     * Unread, the model's constraint stayed {@code NOT VALID} too, matched the
     * database exactly, and the file's instruction never left the project.
     */
    @Test
    void aValidatedConstraintIsValidatedInTheDatabase() throws Exception {
        String script = pipeline(notValidConstraintByAlter(), constraintValidatedByAlter());
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER DOMAIN %s.%s
                \tVALIDATE CONSTRAINT %s;""".formatted(SCHEMA_NAME, DOMAIN_NAME, CONSTRAINT_NAME), script.trim());
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
        String script = pipeline(domainWithConstraint(), constraintValidatedByAlter());
        assertEquals("", script.trim(),
                () -> "a validated constraint must read as the database's valid one, got:\n" + script);
        assertTrue(domainOf(domainWithConstraint()).compare(domainOf(constraintValidatedByAlter())),
                "and the two must be one object, which an empty script cannot show by itself");
    }

    /**
     * The modifier, honoured rather than ignored. Without {@code IF EXISTS} a
     * drop names a constraint that has to be there, so an unknown name is the
     * same unresolved reference the parser reports for any other object it was
     * told to alter and cannot find.
     */
    @Test
    void droppingAConstraintTheFileNeverDeclaredIsReported() throws Exception {
        var settings = new CoreSettings();
        load(dropOfAnUnknownConstraint(""), settings);
        assertFalse(settings.getErrors().isEmpty(),
                "dropping a constraint that is not there must be reported");
    }

    /**
     * And the escape hatch the modifier exists for: with {@code IF EXISTS} the
     * same statement is silent, and leaves the constraint the domain does have
     * alone.
     */
    @Test
    void ifExistsMakesTheDropOfAnUnknownConstraintSilent() throws Exception {
        var settings = new CoreSettings();
        PgDatabase db = load(dropOfAnUnknownConstraint("IF EXISTS "), settings);
        assertTrue(settings.getErrors().isEmpty(),
                () -> "IF EXISTS must make the drop silent, got: " + settings.getErrors());
        assertTrue(domainOf(domainWithConstraint()).compare(domainOf(db)),
                "and it must leave the constraint the domain does have alone");
    }

    private static PgDatabase domainWithConstraint() throws Exception {
        return load("CREATE DOMAIN %s.%s AS %s CONSTRAINT %s CHECK %s;"
                .formatted(SCHEMA_NAME, DOMAIN_NAME, DOMAIN_TYPE, CONSTRAINT_NAME, EXPRESSION));
    }

    /** How a project file holds an unvalidated constraint - the way the exporter writes one. */
    private static PgDatabase notValidConstraintByAlter() throws Exception {
        return load("""
                CREATE DOMAIN %1$s.%2$s AS %3$s;
                ALTER DOMAIN %1$s.%2$s ADD CONSTRAINT %4$s CHECK %5$s NOT VALID;
                """.formatted(SCHEMA_NAME, DOMAIN_NAME, DOMAIN_TYPE, CONSTRAINT_NAME, EXPRESSION));
    }

    /**
     * A file that declares the constraint and then drops it. Written that way on
     * purpose: a file that never declared it would compare as changed against a
     * database that has it whether or not the drop was read at all.
     */
    private static PgDatabase constraintDroppedByAlter(String ifExists) throws Exception {
        return load("""
                CREATE DOMAIN %1$s.%2$s AS %3$s CONSTRAINT %4$s CHECK %5$s;
                ALTER DOMAIN %1$s.%2$s DROP CONSTRAINT %6$s%4$s;
                """.formatted(SCHEMA_NAME, DOMAIN_NAME, DOMAIN_TYPE, CONSTRAINT_NAME, EXPRESSION, ifExists));
    }

    /** A file that adds the constraint unvalidated and then validates it. */
    private static PgDatabase constraintValidatedByAlter() throws Exception {
        return load("""
                CREATE DOMAIN %1$s.%2$s AS %3$s;
                ALTER DOMAIN %1$s.%2$s ADD CONSTRAINT %4$s CHECK %5$s NOT VALID;
                ALTER DOMAIN %1$s.%2$s VALIDATE CONSTRAINT %4$s;
                """.formatted(SCHEMA_NAME, DOMAIN_NAME, DOMAIN_TYPE, CONSTRAINT_NAME, EXPRESSION));
    }

    /** A domain that has one constraint and a statement dropping a different one. */
    private static String dropOfAnUnknownConstraint(String ifExists) {
        return """
                CREATE DOMAIN %1$s.%2$s AS %3$s CONSTRAINT %4$s CHECK %5$s;
                ALTER DOMAIN %1$s.%2$s DROP CONSTRAINT %6$snosuch_check;
                """.formatted(SCHEMA_NAME, DOMAIN_NAME, DOMAIN_TYPE, CONSTRAINT_NAME, EXPRESSION, ifExists);
    }

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return load(sql, new CoreSettings());
    }

    private static PgDatabase load(String sql, CoreSettings settings) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "domain constraint test", settings).load();
    }

    private static PgDomain domainOf(PgDatabase db) {
        return db.getChildren()
                .filter(PgSchema.class::isInstance)
                .map(PgSchema.class::cast)
                .map(s -> s.getDomain(DOMAIN_NAME))
                .filter(d -> d != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no domain was parsed"));
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
