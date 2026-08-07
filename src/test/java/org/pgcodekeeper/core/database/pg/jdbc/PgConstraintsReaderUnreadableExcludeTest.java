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
import java.sql.Connection;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgConstraintExclude;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * What {@code PgConstraintsReader} does with a {@code pg_get_constraintdef()}
 * result for an {@code EXCLUDE} constraint that this grammar cannot read.
 * <p>
 * The constraint reaches its table unconditionally, while the index method, the
 * column list and the predicate are all written in one place -
 * {@code PgTableAbstract.processTableConstraintBlank} - which the reader reaches
 * only through the loader's deferred finalizer, and that finalizer runs only for
 * a definition that parsed ({@code AbstractJdbcLoader:377}). So before this
 * class the reader answered an unreadable definition with the bare word
 *
 * <pre>ALTER TABLE public.orders
 *     ADD CONSTRAINT c_x EXCLUDE;</pre>
 *
 * The loss is total rather than partial, and that is worth stating because it
 * splits this site from the {@code CHECK} one next door. A {@code CHECK} that
 * lost its expression is written out as {@code CHECK (null)}: valid SQL that
 * forbids nothing, and the script applies. {@code EXCLUDE} with no column list
 * is not valid SQL at all - measured on PostgreSQL 17.10, which answers
 * {@code syntax error at or near ";"} - so this half of the defect is loud.
 * <p>
 * The half that is not loud is the comparison. Two exclude constraints whose
 * definitions both failed to parse used to carry, in every field the comparison
 * reads, exactly the same nothing - and a difference the comparison does not see
 * never reaches the script that would have failed. That is the half
 * {@link #anUnreadableExcludeIsNotTheSameAsAnotherOne()} holds.
 */
class PgConstraintsReaderUnreadableExcludeTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "orders";
    private static final String CONSTRAINT_NAME = "c_x";

    /**
     * A clause this grammar does not read, inside the statement the server
     * writes around it.
     * <p>
     * The expression is the SQL-standard {@code UNIQUE} predicate. PostgreSQL's
     * {@code gram.y} carries the production - {@code UNIQUE
     * opt_unique_null_treatment select_with_parens} - and its action is nothing
     * but {@code ereport(ERROR, ERRCODE_FEATURE_NOT_SUPPORTED, "UNIQUE predicate
     * is not yet implemented")}, unchanged at least as far back as 9.6. A server
     * therefore cannot store it, no catalog can hold it and {@code ruleutils.c}
     * can never print it - so unlike the {@code IS NORMALIZED} anchor it
     * replaces, it can never join the set of text this grammar is obliged to
     * read, and can never be fixed out from under this class.
     * <p>
     * What is given up by not drawing the expression from a live server is
     * stated plainly: the {@code EXCLUDE USING gist (r WITH &&) WHERE ((...))}
     * around it is still the shape {@code pg_get_constraintdef()} writes, doubled
     * parentheses included, and that shape is what the reader has to cope with;
     * only the expression inside is one no server would hand over. The reader
     * cannot tell the two apart - it sees a non-empty error list either way.
     * <p>
     * {@link #theFixtureIsOneThisGrammarCannotRead()} holds the unreadability
     * itself rather than assuming it: the day the grammar grows the alternative,
     * that test goes red and says so, instead of this whole class quietly
     * ceasing to test anything.
     */
    private static final String UNREADABLE =
            "EXCLUDE USING gist (r WITH &&) WHERE ((UNIQUE (SELECT n FROM public.orders)))";

    /**
     * A second unreadable clause, differing from the first in the rows it lets
     * through. The reverse half of the defect was total: with nothing kept,
     * these two carried byte-identical values in every field the comparison
     * reads.
     */
    private static final String UNREADABLE_OTHER =
            "EXCLUDE USING gist (r WITH &&) WHERE ((UNIQUE NULLS NOT DISTINCT (SELECT m FROM public.orders)))";

    /** The same shape, in a spelling the grammar does read. */
    private static final String READABLE = "EXCLUDE USING gist (r WITH &&) WHERE ((n IS NOT NULL))";

    /**
     * The same constraint the readable fixture describes, spelled with the
     * whitespace a hand-written catalog would never produce. The parse
     * normalizes it away, so the two converge - but only once the kept clause
     * has been released, because the raw texts differ. See
     * {@link #aReadableClauseIsStillWrittenOutOfTheModel()}.
     */
    private static final String READABLE_RESPACED = "EXCLUDE USING gist (r WITH &&) WHERE ((n   IS   NOT   NULL))";

    /** An exclude constraint that genuinely restricts no rows. */
    private static final String NO_PREDICATE = "EXCLUDE USING gist (r WITH &&)";

    /**
     * The one tail {@code pg_get_constraintdef()} appends to an exclude clause.
     * Measured on PostgreSQL 17.10, which also refuses the other candidate
     * outright: {@code EXCLUDE ... NOT VALID} is answered with
     * {@code EXCLUDE constraints cannot be marked NOT VALID}, so that word can
     * never appear inside a kept exclude clause and there is nothing to split
     * off for it.
     */
    private static final String DEFERRED = UNREADABLE + " DEFERRABLE INITIALLY DEFERRED";

    @Test
    void theFixtureIsOneThisGrammarCannotRead() throws Exception {
        var settings = new CoreSettings();
        read(UNREADABLE, settings);
        assertFalse(settings.getErrors().isEmpty(),
                "this definition is supposed to fail the grammar, otherwise this whole class is decorative");

        var otherSettings = new CoreSettings();
        read(UNREADABLE_OTHER, otherSettings);
        assertFalse(otherSettings.getErrors().isEmpty(), "and so is the second one");

        var readableSettings = new CoreSettings();
        read(READABLE, readableSettings);
        assertTrue(readableSettings.getErrors().isEmpty(),
                () -> "and the counterpart is supposed to parse: " + readableSettings.getErrors());
    }

    /**
     * The output side. Nothing here reconstructs a clause out of fields that
     * were never filled: the script carries the definition it was given, whole.
     * Whole is the point - the bare {@code EXCLUDE} it replaces is a syntax
     * error no server accepts, while what the reader keeps is a complete
     * {@code EXCLUDE ... WHERE (...)} clause. The anchor expression inside it is
     * one PostgreSQL declines to implement, so this test cannot also claim the
     * result applies; {@code aDeferrableTailIsWrittenOnceAndFromTheKeptText}
     * asserts the structural half that matters here, which is that the clause
     * arrives in one piece.
     */
    @Test
    void anUnreadableExcludeReachesTheScriptAsTheServerWroteIt() throws Exception {
        String ddl = creationScript(read(UNREADABLE, new CoreSettings()));

        assertTrue(ddl.contains(UNREADABLE),
                () -> "the clause it was given must reach the script, got:\n" + ddl);
        assertFalse(ddl.contains("EXCLUDE;"),
                () -> "a constraint whose clause could not be read must never be written out as the bare word "
                        + "EXCLUDE, which no server accepts, got:\n" + ddl);
    }

    /**
     * The comparison side, and the half the loud one does not cover: a
     * difference the comparison misses never reaches the script at all, so it
     * never gets the chance to fail out loud.
     */
    @Test
    void anUnreadableExcludeIsNotTheSameAsAnotherOne() throws Exception {
        assertDiverge(read(UNREADABLE, new CoreSettings()), read(UNREADABLE_OTHER, new CoreSettings()));
    }

    /**
     * The other reverse pairing, pinned rather than claimed. Unlike the
     * {@code CHECK} site, this one was never in danger: an unreadable exclude
     * constraint loses its column list and its index method along with its
     * predicate, so it never looked like a constraint that genuinely has no
     * {@code WHERE} - that one still carries both. The test states which of the
     * two reasons holds today, and would notice if the kept clause were ever the
     * only thing keeping them apart.
     */
    @Test
    void anUnreadableExcludeIsNotTheSameAsOneWithNoPredicate() throws Exception {
        assertDiverge(read(UNREADABLE, new CoreSettings()), read(NO_PREDICATE, new CoreSettings()));
    }

    /**
     * The successful path is untouched: once the model carries the parsed
     * fields, the generator builds the clause from them and the kept definition
     * is gone. Observed from outside through two spellings of one constraint,
     * which is the only way - the kept definition deliberately has no getter,
     * and the comparison reads the normalized halves the parse writes, not the
     * raw text. Two spellings converge only if the raw clause was released;
     * held, they would differ by their own whitespace.
     */
    @Test
    void aReadableClauseIsStillWrittenOutOfTheModel() throws Exception {
        PgConstraintExclude loaded = read(READABLE, new CoreSettings());
        assertConverge(loaded, read(READABLE_RESPACED, new CoreSettings()));

        String ddl = creationScript(loaded);
        assertTrue(ddl.contains(READABLE),
                () -> "the generator's own clause must reach the script, got:\n" + ddl);
    }

    /**
     * The copy trap. {@code PgConstraintExclude.getConstraintCopy} moves fields
     * one at a time, so a forgotten definition is lost in silence - and a copy
     * that lost it is a constraint that says the bare {@code EXCLUDE} again,
     * both in the script and to the comparison.
     */
    @Test
    void copyingCarriesTheKeptClause() throws Exception {
        PgConstraintExclude original = read(UNREADABLE, new CoreSettings());
        var copy = (PgConstraintExclude) original.deepCopy();
        // a copy comes out of deepCopy unparented, and a statement's hash covers
        // the names of its parents (AbstractStatement.computeNamesHash)
        copy.setParent(original.getParent());

        assertConverge(copy, original);
        String ddl = creationScript(copy);
        assertTrue(ddl.contains(UNREADABLE),
                () -> "a copy must not turn an unreadable constraint back into the bare word EXCLUDE, got:\n" + ddl);
    }

    /**
     * The tail that stays inside the kept text, and why that is not an
     * oversight. {@code PgConstraint.getCreationSQL} appends
     * {@code DEFERRABLE} and {@code INITIALLY DEFERRED} of its own after the
     * definition, but from flags that {@code PgTableAbstract:642} writes - in
     * the same finalizer that did not run. They are false on this path, so the
     * words reach the script exactly once, from the kept text.
     */
    @Test
    void aDeferrableTailIsWrittenOnceAndFromTheKeptText() throws Exception {
        String ddl = creationScript(read(DEFERRED, new CoreSettings()));

        assertTrue(ddl.contains(DEFERRED),
                () -> "the whole clause it was given must reach the script, got:\n" + ddl);
        assertFalse(ddl.contains("DEFERRABLE INITIALLY DEFERRED DEFERRABLE"),
                () -> "the generator's own deferrability must not land on top of the kept one, got:\n" + ddl);
        assertEquals(ddl.indexOf("DEFERRABLE"), ddl.lastIndexOf("DEFERRABLE"),
                () -> "the word must appear exactly once, got:\n" + ddl);
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
    private static PgConstraintExclude read(String definition, CoreSettings settings) throws Exception {
        ResultSet res = mock(ResultSet.class);
        when(res.getString("relname")).thenReturn(TABLE_NAME);
        when(res.getString("conname")).thenReturn(CONSTRAINT_NAME);
        when(res.getString("contype")).thenReturn("x");
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
        assertTrue(constraint instanceof PgConstraintExclude, "the reader added no EXCLUDE constraint");
        return (PgConstraintExclude) constraint;
    }

    /**
     * {@code finishLoaders} is protected on {@code AbstractLoader}, so a subclass
     * reaches it without reflection. Nothing here queries - the reader is handed
     * its row directly - so the connector exists only to satisfy the constructor.
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

    private static void assertConverge(PgConstraintExclude a, PgConstraintExclude b) {
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
    private static void assertDiverge(PgConstraintExclude a, PgConstraintExclude b) {
        assertFalse(a.compare(b), "an unreadable constraint must not compare equal to a different one");
        assertFalse(b.compare(a), "compare must be symmetric");
        assertFalse(Comparison.compare(new CoreSettings(), a, b),
                "and the entry point the diff tree uses must see the difference too");
        assertNotEquals(a.hashCode(), b.hashCode(), "and the hash the tree consults on its own must differ");
    }

    private static String creationScript(PgConstraintExclude constraint) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, constraint.getSeparator());
        constraint.getCreationSQL(script);
        return script.getFullScript();
    }
}
