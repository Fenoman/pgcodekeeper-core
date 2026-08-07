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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgConstraintCheck;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgDomain;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * What the two readers that build a {@link PgConstraintCheck} do with a
 * {@code pg_get_constraintdef()} result this grammar cannot read.
 * <p>
 * The constraint reaches its container unconditionally -
 * {@code PgConstraintsReader} hands it to the table, {@code PgTypesReader} hands
 * it to the domain - while its expression is written in one place, the parse
 * finalizer, which the loader runs only for a definition that parsed
 * ({@code AbstractJdbcLoader:377}). {@code PgConstraintCheck.getDefinition()}
 * spells out {@code CHECK (} + the expression + {@code )} either way, so before
 * this class both readers answered an unreadable definition with
 *
 * <pre>CHECK (null)</pre>
 *
 * a constraint the server accepts and which forbids nothing at all, because
 * {@code null} is not false. Both readers keep the server's own clause instead
 * and the generator emits it verbatim - on the successful path that clause is
 * what the generator assembles out of its own sub-spans anyway, so the failing
 * path now reaches the same text by a shorter route.
 * <p>
 * Both readers are driven here rather than one, because they are two call sites
 * of one class: the field, the generator, the hash, the comparison and the copy
 * are shared, and only the line that fills the field is written twice.
 */
class PgConstraintsReaderUnreadableDefinitionTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "orders";
    private static final String CONSTRAINT_NAME = "c_norm";
    private static final String DOMAIN_NAME = "norm_text";
    private static final String DOMAIN_CONSTRAINT_NAME = "d_norm";

    /**
     * A clause this grammar does not read, inside the statement the server
     * writes around it.
     * <p>
     * The expression is the SQL-standard {@code UNIQUE} predicate. PostgreSQL's
     * {@code gram.y} carries the production - {@code UNIQUE
     * opt_unique_null_treatment select_with_parens} - and its action is nothing
     * but {@code ereport(ERROR, ERRCODE_FEATURE_NOT_SUPPORTED, "UNIQUE predicate
     * is not yet implemented")}, unchanged at least as far back as 9.6. That is
     * what makes it the right anchor rather than merely a convenient one: a
     * server cannot store the predicate, so no catalog can hold it and
     * {@code ruleutils.c} can never print it, so it can never join the set of
     * text this grammar is obliged to read. An anchor drawn from that set is a
     * defect waiting to be fixed, and fixing it silently retires the class that
     * leans on it - which is exactly what happened to the {@code IS NORMALIZED}
     * anchor this one replaces.
     * <p>
     * What is given up by not drawing the expression from a live server is
     * stated plainly: {@code CHECK ((...))} around it is still the shape
     * {@code pg_get_constraintdef()} writes, parentheses included, and that shape
     * is what the reader has to cope with; only the expression inside is one no
     * server would hand over. The reader cannot tell the two apart - it sees a
     * non-empty error list either way - so the path under test is the same one.
     * <p>
     * {@link #theFixtureIsOneThisGrammarCannotRead()} holds the unreadability
     * itself rather than assuming it: the day the grammar grows the alternative,
     * that test goes red and says so, instead of this whole class quietly
     * ceasing to test anything.
     */
    private static final String UNREADABLE = "CHECK ((UNIQUE (SELECT n FROM public.orders)))";

    /**
     * A second unreadable clause, differing from the first in what it checks.
     * The reverse half of the defect is total: with nothing kept, these two
     * constraints carry byte-identical values in every field the comparison
     * reads.
     */
    private static final String UNREADABLE_OTHER =
            "CHECK ((UNIQUE NULLS NOT DISTINCT (SELECT m FROM public.orders)))";

    /** The same shape, in a spelling the grammar does read. */
    private static final String READABLE = "CHECK ((n IS NOT NULL))";

    // ------------------------------------------------------------------
    // the table constraint
    // ------------------------------------------------------------------

    @Test
    void theFixtureIsOneThisGrammarCannotRead() throws Exception {
        var settings = new CoreSettings();
        readTableConstraint(UNREADABLE, settings);
        assertFalse(settings.getErrors().isEmpty(),
                "this definition is supposed to fail the grammar, otherwise this whole class is decorative");

        var otherSettings = new CoreSettings();
        readTableConstraint(UNREADABLE_OTHER, otherSettings);
        assertFalse(otherSettings.getErrors().isEmpty(), "and so is the second one");

        var readableSettings = new CoreSettings();
        readTableConstraint(READABLE, readableSettings);
        assertTrue(readableSettings.getErrors().isEmpty(),
                () -> "and the counterpart is supposed to parse: " + readableSettings.getErrors());
    }

    /**
     * The output side. Nothing here reconstructs a clause out of an expression
     * that was never filled: the script carries the server's own definition.
     */
    @Test
    void anUnreadableCheckReachesTheScriptAsTheServerWroteIt() throws Exception {
        String ddl = creationScript(readTableConstraint(UNREADABLE, new CoreSettings()));

        assertTrue(ddl.contains(UNREADABLE),
                () -> "the clause it was given must reach the script, got:\n" + ddl);
        assertFalse(ddl.contains("CHECK (null)"),
                () -> "a constraint whose expression could not be read must never be written out as "
                        + "CHECK (null), which forbids nothing, got:\n" + ddl);
    }

    /**
     * The comparison side, and the sharper half of the same trap. Two things
     * must not be mistaken for this constraint: a second unreadable constraint
     * that checks something else, and a project file that genuinely says
     * {@code CHECK (null)} - which is exactly what an unreadable one used to
     * look like from every field the comparison reads.
     */
    @Test
    void anUnreadableCheckIsNotTheSameAsAnotherOneOrAsCheckingNothing() throws Exception {
        PgConstraintCheck unreadable = readTableConstraint(UNREADABLE, new CoreSettings());
        PgConstraintCheck otherUnreadable = readTableConstraint(UNREADABLE_OTHER, new CoreSettings());
        assertDiverge(unreadable, otherUnreadable);

        var checksNothing = new PgConstraintCheck(CONSTRAINT_NAME);
        checksNothing.setExpression("null", "null");
        assertDiverge(unreadable, parented(checksNothing));
    }

    /**
     * The successful path is untouched: once the model carries the expression,
     * the generator builds the clause from it and the kept definition is gone.
     * Observed from outside through a constraint built by hand, which is the
     * only way - the kept definition deliberately has no getter.
     */
    @Test
    void aReadableDefinitionIsStillWrittenOutOfTheModel() throws Exception {
        PgConstraintCheck loaded = readTableConstraint(READABLE, new CoreSettings());

        var handBuilt = new PgConstraintCheck(CONSTRAINT_NAME);
        handBuilt.setExpression("(n IS NOT NULL)", "(n IS NOT NULL)");
        assertConverge(parented(handBuilt), loaded);

        String ddl = creationScript(loaded);
        assertTrue(ddl.contains(READABLE),
                () -> "the generator's own clause must reach the script, got:\n" + ddl);
    }

    /**
     * The copy trap. {@code PgConstraintCheck.getConstraintCopy} moves fields
     * one at a time, so a forgotten definition is lost in silence - and a copy
     * that lost it is a constraint that says {@code CHECK (null)} again, both in
     * the script and to the comparison.
     */
    @Test
    void copyingCarriesTheKeptDefinition() throws Exception {
        PgConstraintCheck original = readTableConstraint(UNREADABLE, new CoreSettings());
        var copy = (PgConstraintCheck) original.deepCopy();
        // a copy comes out of deepCopy unparented, and a statement's hash covers
        // the names of its parents (AbstractStatement.computeNamesHash)
        copy.setParent(original.getParent());

        assertConverge(copy, original);
        String ddl = creationScript(copy);
        assertTrue(ddl.contains(UNREADABLE),
                () -> "a copy must not turn an unreadable constraint into CHECK (null), got:\n" + ddl);
    }

    /**
     * The one modifier that may not stay inside the kept text.
     * {@code pg_get_constraintdef()} appends {@code NOT VALID} last, after the
     * expression and after any {@code NO INHERIT} - measured on PostgreSQL
     * 17.10, where {@code CHECK ((k > 0)) NOT VALID} is the literal result - and
     * the two generators that write this definition put that word in two
     * different places. {@code PgConstraint.getCreationSQL} appends it after the
     * definition it just wrote, so a kept copy would be doubled; {@code CREATE
     * DOMAIN} has no syntax for it at all, and {@code PgDomain} answers that by
     * routing a constraint whose {@code isNotValid()} is set into an
     * {@code ALTER DOMAIN} statement of its own, which a kept copy would never
     * reach. So the tail is split off the text and into the flag, and both
     * generators write it exactly once, in their own place.
     */
    @Test
    void aNotValidTailIsWrittenOnceAndInTheGeneratorsOwnPlace() throws Exception {
        PgConstraintCheck notValid = readTableConstraint(UNREADABLE + " NO INHERIT NOT VALID", new CoreSettings());

        String ddl = creationScript(notValid);
        assertTrue(ddl.contains(UNREADABLE + " NO INHERIT NOT VALID"),
                () -> "the whole clause it was given must reach the script, got:\n" + ddl);

        // the doubling only becomes visible with the setting that makes the
        // generator write NOT VALID of its own accord, so the setting is turned
        // on here rather than assumed away: with the tail left inside the kept
        // text the two land side by side and a VALIDATE CONSTRAINT follows for a
        // constraint the database never validated
        var generateNotValid = new CoreSettings();
        generateNotValid.setGenerateConstraintNotValid(true);
        String forced = creationScript(notValid, generateNotValid);
        assertFalse(forced.contains("NOT VALID NOT VALID"),
                () -> "the generator's own NOT VALID must not land on top of the kept one, got:\n" + forced);
        assertFalse(forced.contains("VALIDATE CONSTRAINT"),
                () -> "and a constraint the server holds as unvalidated must not be validated behind the "
                        + "author's back, got:\n" + forced);
    }

    // ------------------------------------------------------------------
    // the domain constraint
    // ------------------------------------------------------------------

    /**
     * The second call site, driven the same way. The domain generator writes the
     * constraint inline into {@code CREATE DOMAIN}, so an unreadable expression
     * used to become a {@code CHECK (null)} clause of the domain itself.
     */
    @Test
    void anUnreadableDomainCheckReachesTheScriptAsTheServerWroteIt() throws Exception {
        var settings = new CoreSettings();
        PgDomain domain = readDomain(UNREADABLE, settings);

        assertFalse(settings.getErrors().isEmpty(),
                "the domain fixture is supposed to fail the grammar too, otherwise this test proves nothing");

        String ddl = creationScript(domain);
        assertTrue(ddl.contains("CONSTRAINT " + DOMAIN_CONSTRAINT_NAME + ' ' + UNREADABLE),
                () -> "the clause it was given must reach the domain, got:\n" + ddl);
        assertFalse(ddl.contains("CHECK (null)"),
                () -> "a domain constraint whose expression could not be read must never be written out as "
                        + "CHECK (null), got:\n" + ddl);
    }

    /**
     * The same reverse half on the domain side: an unreadable domain constraint
     * must not compare equal to one that genuinely checks nothing, or the domain
     * carrying it leaves the diff tree and the script together.
     */
    @Test
    void anUnreadableDomainCheckIsNotTheSameAsCheckingNothing() throws Exception {
        PgConstraintCheck unreadable = constraintOf(readDomain(UNREADABLE, new CoreSettings()));

        PgDomain checksNothingDomain = readDomain(READABLE, new CoreSettings());
        PgConstraintCheck checksNothing = constraintOf(checksNothingDomain);
        checksNothing.setExpression("null", "null");

        assertDiverge(unreadable, checksNothing);
    }

    /**
     * And the domain's successful path, untouched: the kept clause is dropped as
     * soon as the parse writes the expression, so the domain is written out of
     * the model exactly as before.
     */
    @Test
    void aReadableDomainCheckIsStillWrittenOutOfTheModel() throws Exception {
        var settings = new CoreSettings();
        PgDomain domain = readDomain(READABLE, settings);
        assertTrue(settings.getErrors().isEmpty(),
                () -> "the readable domain fixture must parse: " + settings.getErrors());

        PgConstraintCheck loaded = constraintOf(domain);
        var handBuilt = new PgConstraintCheck(DOMAIN_CONSTRAINT_NAME);
        handBuilt.setExpression("(n IS NOT NULL)", "(n IS NOT NULL)");
        handBuilt.setParent(loaded.getParent());
        assertConverge(handBuilt, loaded);
    }

    /**
     * The one place the splitting of the {@code NOT VALID} tail is visible on a
     * path that parsed, pinned here because it is a change and not an accident.
     * <p>
     * {@code PgCreateDomain.parseDomainConstraint} is the single point that
     * fills a domain constraint from a parse, and it reads the expression and
     * nothing else - the {@code (NOT VALID)?} the grammar carries at
     * {@code SQLParser.g4:677} reaches no field on this route. So a domain
     * constraint the database holds as unvalidated used to arrive validated and
     * be written inline into {@code CREATE DOMAIN}, while the same constraint in
     * a project file arrives unvalidated, because {@code PgAlterDomain} does
     * read that clause ({@code PgAlterDomain:78}). The tail now reaches the flag
     * from either side, so the two stop differing over a word both of them were
     * given.
     */
    @Test
    void aNotValidDomainCheckIsCarriedAsNotValid() throws Exception {
        String ddl = creationScript(readDomain(READABLE + " NOT VALID", new CoreSettings()));

        assertTrue(ddl.contains("ALTER DOMAIN " + SCHEMA_NAME + '.' + DOMAIN_NAME),
                () -> "an unvalidated domain constraint has no place inside CREATE DOMAIN and belongs to a "
                        + "statement of its own, got:\n" + ddl);
        assertTrue(ddl.contains(READABLE + " NOT VALID"),
                () -> "and it must be written as unvalidated, got:\n" + ddl);
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    /**
     * Runs {@link PgConstraintsReader#processResult} over one mocked catalog row
     * and finishes the loader's parse queue, which runs the deferred finalizer
     * exactly as a real load does. Returns the constraint the reader hung off
     * the table.
     */
    private static PgConstraintCheck readTableConstraint(String definition, CoreSettings settings) throws Exception {
        ResultSet res = mock(ResultSet.class);
        when(res.getString("relname")).thenReturn(TABLE_NAME);
        when(res.getString("conname")).thenReturn(CONSTRAINT_NAME);
        when(res.getString("contype")).thenReturn("c");
        when(res.getString("definition")).thenReturn(definition);
        // spcname, reloptions and description stay null, which is a constraint
        // with no tablespace, no index parameters and no comment

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);

        try (TestLoader loader = new TestLoader(settings)) {
            new PgConstraintsReader(loader).processResult(res, schema);
            loader.drain();
        }

        var constraint = table.getConstraint(CONSTRAINT_NAME);
        assertTrue(constraint instanceof PgConstraintCheck, "the reader added no CHECK constraint");
        return (PgConstraintCheck) constraint;
    }

    /**
     * The same, for {@link PgTypesReader#processResult} and the domain constraint
     * arrays it reads. The domain itself carries nothing else - no default, no
     * collation - so the only thing under test is the constraint.
     */
    private static PgDomain readDomain(String definition, CoreSettings settings) throws Exception {
        settings.setIgnorePrivileges(true);

        // built before the stubbing starts: a mock created inside a when()
        // argument leaves Mockito with an unfinished stubbing and the failure
        // surfaces on the next, unrelated line
        Array names = stringArray(DOMAIN_CONSTRAINT_NAME);
        Array definitions = stringArray(definition);
        Array comments = stringArray((String) null);

        ResultSet res = mock(ResultSet.class);
        when(res.getString("typtype")).thenReturn("d");
        when(res.getString("typname")).thenReturn(DOMAIN_NAME);
        when(res.getString("dom_basetypefmt")).thenReturn("text");
        when(res.getLong("dom_basetype")).thenReturn(SYSTEM_TYPE_OID);
        when(res.getArray("dom_connames")).thenReturn(names);
        when(res.getArray("dom_condefs")).thenReturn(definitions);
        when(res.getArray("dom_concomments")).thenReturn(comments);

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);

        try (TestLoader loader = new TestLoader(settings)) {
            new PgTypesReader(loader).processResult(res, schema);
            loader.drain();
        }

        PgDomain domain = schema.getDomain(DOMAIN_NAME);
        assertTrue(domain != null, "the reader added no domain");
        return domain;
    }

    private static PgConstraintCheck constraintOf(PgDomain domain) {
        var constraint = domain.getConstraint(DOMAIN_CONSTRAINT_NAME);
        assertTrue(constraint instanceof PgConstraintCheck, "the reader added no domain CHECK constraint");
        return (PgConstraintCheck) constraint;
    }

    private static Array stringArray(String... values) throws Exception {
        Array array = mock(Array.class);
        when(array.getArray()).thenReturn(values);
        return array;
    }

    private static final long SYSTEM_TYPE_OID = 25L;
    private static final long LAST_SYS_OID = 10000L;

    /**
     * {@code finishLoaders} is protected on {@code AbstractLoader}, so a subclass
     * reaches it without reflection, and the type cache is normally filled by a
     * query this test never runs, so the one lookup the domain reader makes is
     * answered here. Nothing queries - each reader is handed its row directly -
     * so the connector exists only to satisfy the constructor.
     */
    private static final class TestLoader extends PgJdbcLoader {

        private TestLoader(CoreSettings settings) {
            super(offlineConnector(), null, settings);
        }

        @Override
        public PgJdbcType getCachedTypeByOid(Long oid) {
            return new PgJdbcType(oid, "text", 0L, 1231L, "pg_catalog", null, LAST_SYS_OID, "m", 0L);
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

    private static void assertConverge(PgConstraintCheck a, PgConstraintCheck b) {
        assertTrue(a.compare(b), "expected the two constraints to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged constraints must hash the same");
    }

    /**
     * {@link Comparison#compare} is asked alongside {@code compare} because it is
     * what the diff tree calls.
     * <p>
     * The hash is asked separately, and not as a proxy for either of them: the
     * two do not always cover the same fields, and the tree consults the hash on
     * its own - {@code Comparison.compare} of a table ends in
     * {@code hashChildren() == hashChildren()}, and a constraint is a table's
     * child. Without that line a mutation that drops the kept definition from
     * {@code computeHash} alone leaves every other assertion here holding.
     */
    private static void assertDiverge(PgConstraintCheck a, PgConstraintCheck b) {
        assertFalse(a.compare(b), "an unreadable constraint must not compare equal to a different one");
        assertFalse(b.compare(a), "compare must be symmetric");
        assertFalse(Comparison.compare(new CoreSettings(), a, b),
                "and the entry point the diff tree uses must see the difference too");
        assertNotEquals(a.hashCode(), b.hashCode(), "and the hash the tree consults on its own must differ");
    }

    /**
     * Mirrors the reader's own last line: the constraint hangs off the table it
     * is declared on. Both sides of a comparison must be parented the same way,
     * because a statement's hash covers the names of everything above it.
     */
    private static PgConstraintCheck parented(PgConstraintCheck constraint) {
        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);
        table.addChild(constraint);
        return constraint;
    }

    private static String creationScript(AbstractStatement statement) {
        return creationScript(statement, new CoreSettings());
    }

    private static String creationScript(AbstractStatement statement, CoreSettings settings) {
        var script = new SQLScript(settings, statement.getSeparator());
        statement.getCreationSQL(script);
        return script.getFullScript();
    }
}
