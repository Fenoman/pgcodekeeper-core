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
import org.pgcodekeeper.core.database.pg.schema.PgAbstractView;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * What {@code PgViewsReader} does with a {@code pg_get_viewdef()} result this
 * grammar cannot read.
 * <p>
 * The view reaches its schema unconditionally, while both halves of its query
 * are written in one place - {@code PgAbstractView.setQuery} inside the parse
 * finalizer - which the loader runs only for a definition that parsed
 * ({@code AbstractJdbcLoader:377}). A view is its query and nothing else, so
 * neither half being written left the object with no query at all, and the
 * places that read it are not defensive about it:
 * {@code PgAbstractView.getCreationSQL} sizes its builder from
 * {@code query.length()} before writing a character,
 * {@code PgView.appendOptions} appends the query itself, and
 * {@code PgAbstractView.needDrop} calls {@code equals} on the normalized half.
 * Measured, the first one reached first:
 *
 * <pre>NullPointerException: Cannot invoke "String.length()" because "this.query" is null</pre>
 *
 * That is loud - it ends the load rather than lying about it - but the raw query
 * was already sitting in a local variable of the same method, unused on this
 * path. The reader now writes both halves from it before submitting the parse,
 * exactly as {@code PgPoliciesReader} and the column {@code DEFAULT} of
 * {@code PgTablesReader} already do, and the finalizer overwrites the
 * normalized half when the parse succeeds.
 * <p>
 * The quieter half this also closes: with the normalized query left null, two
 * views whose queries both failed to parse compared equal and hashed equal, so
 * a difference between two databases left the diff tree and the script
 * together - before either of them could crash. Measured, not assumed: dropping
 * the pre-fill turns {@link #anUnreadableViewIsNotTheSameAsAnotherOne()} red on
 * {@code compare}, not only the script test on its exception.
 */
class PgViewsReaderUnreadableQueryTest {

    private static final String SCHEMA_NAME = "public";
    private static final String VIEW_NAME = "v_norm";

    /**
     * A query this grammar does not read, laid out the way the server lays one
     * out.
     * <p>
     * The unreadable part is the SQL-standard {@code UNIQUE} predicate.
     * PostgreSQL's {@code gram.y} carries the production - {@code UNIQUE
     * opt_unique_null_treatment select_with_parens} - and its action is nothing
     * but {@code ereport(ERROR, ERRCODE_FEATURE_NOT_SUPPORTED, "UNIQUE predicate
     * is not yet implemented")}, unchanged at least as far back as 9.6. A server
     * therefore cannot store it, no catalog can hold it and {@code ruleutils.c}
     * can never print it - so unlike the {@code IS NORMALIZED} anchor it
     * replaces, it can never join the set of text this grammar is obliged to
     * read, and can never be fixed out from under this class.
     * <p>
     * What is given up by not drawing the expression from a live server is
     * stated plainly, and here it is the smallest loss of the four: the
     * indentation, the leading space and the trailing semicolon are still
     * exactly what {@code pg_get_viewdef()} writes, and those three are what the
     * reader handles before the parse - a fixture without them would exercise a
     * different method. Only the expression inside is one no server would hand
     * over, and the reader cannot tell the two apart: it sees a non-empty error
     * list either way.
     */
    private static final String UNREADABLE_EXPRESSION = "(UNIQUE (SELECT n FROM public.orders))";

    private static final String UNREADABLE =
            " SELECT id,\n    " + UNREADABLE_EXPRESSION + " AS ok\n   FROM public.orders;";

    /** A second unreadable query, differing from the first in what it selects. */
    private static final String UNREADABLE_OTHER =
            " SELECT id,\n    (UNIQUE NULLS NOT DISTINCT (SELECT m FROM public.orders)) AS ok"
                    + "\n   FROM public.orders;";

    /** The same shape, in a spelling the grammar does read. */
    private static final String READABLE = " SELECT id,\n    (n IS NOT NULL) AS ok\n   FROM public.orders;";

    /**
     * The readable query respaced. The parse normalizes the difference away, so
     * the two converge - but only because the finalizer overwrote the raw text
     * the reader put in the normalized half. Left there, they would differ by
     * their own whitespace.
     */
    private static final String READABLE_RESPACED =
            " SELECT id,\n    (n   IS   NOT   NULL) AS ok\n   FROM public.orders;";

    @Test
    void theFixtureIsOneThisGrammarCannotRead() throws Exception {
        var settings = new CoreSettings();
        read(UNREADABLE, settings);
        assertFalse(settings.getErrors().isEmpty(),
                "this query is supposed to fail the grammar, otherwise this whole class is decorative");

        var otherSettings = new CoreSettings();
        read(UNREADABLE_OTHER, otherSettings);
        assertFalse(otherSettings.getErrors().isEmpty(), "and so is the second one");

        var readableSettings = new CoreSettings();
        read(READABLE, readableSettings);
        assertTrue(readableSettings.getErrors().isEmpty(),
                () -> "and the counterpart is supposed to parse: " + readableSettings.getErrors());
    }

    /**
     * The output side, and the crash it replaces. Writing the script at all is
     * the assertion; that it carries the query it was given, verbatim, is the
     * second one - the reader must not rebuild it out of a model that was never
     * filled.
     */
    @Test
    void anUnreadableViewReachesTheScriptAsTheServerWroteIt() throws Exception {
        String ddl = creationScript(read(UNREADABLE, new CoreSettings()));

        assertTrue(ddl.contains("CREATE VIEW " + SCHEMA_NAME + '.' + VIEW_NAME),
                () -> "the view must reach the script, got:\n" + ddl);
        assertTrue(ddl.contains(UNREADABLE_EXPRESSION + " AS ok"),
                () -> "and it must carry the query it was given, got:\n" + ddl);
    }

    /**
     * The comparison side. Two views whose queries could not be read must not be
     * mistaken for one another - a difference the comparison misses never
     * reaches the script that would have crashed.
     */
    @Test
    void anUnreadableViewIsNotTheSameAsAnotherOne() throws Exception {
        assertDiverge(read(UNREADABLE, new CoreSettings()), read(UNREADABLE_OTHER, new CoreSettings()));
    }

    /**
     * The successful path is untouched: the finalizer overwrites the raw text
     * the reader left in the normalized half with the real normalization, so two
     * spellings of one query still converge. Held instead of overwritten, they
     * would differ by their own whitespace - which is what makes this the test
     * that notices if the finalizer stops writing.
     */
    @Test
    void aReadableQueryIsStillComparedByItsNormalizedForm() throws Exception {
        PgAbstractView loaded = read(READABLE, new CoreSettings());
        assertConverge(loaded, read(READABLE_RESPACED, new CoreSettings()));

        String ddl = creationScript(loaded);
        assertTrue(ddl.contains("(n IS NOT NULL) AS ok"),
                () -> "and the raw query is still what the script is written from, got:\n" + ddl);
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    /**
     * Runs {@link PgViewsReader#processResult} over one mocked catalog row and
     * finishes the loader's parse queue, which runs the deferred finalizer
     * exactly as a real load does. The row carries no column names, so the
     * per-column loop the reader runs afterwards is skipped and the only thing
     * under test is the query.
     */
    private static PgAbstractView read(String definition, CoreSettings settings) throws Exception {
        settings.setIgnorePrivileges(true);

        ResultSet res = mock(ResultSet.class);
        when(res.getString("relname")).thenReturn(VIEW_NAME);
        when(res.getString("kind")).thenReturn("v");
        when(res.getString("definition")).thenReturn(definition);

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);

        try (TestLoader loader = new TestLoader(settings)) {
            new PgViewsReader(loader).processResult(res, schema);
            loader.drain();
        }

        var view = schema.getView(VIEW_NAME);
        assertTrue(view instanceof PgAbstractView, "the reader added no view");
        return (PgAbstractView) view;
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

    private static void assertConverge(PgAbstractView a, PgAbstractView b) {
        assertTrue(a.compare(b), "expected the two views to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged views must hash the same");
    }

    /**
     * {@link Comparison#compare} is asked alongside {@code compare} because it is
     * what the diff tree calls, and the hash separately because the tree also
     * consults it on its own - a schema's comparison ends in
     * {@code hashChildren() == hashChildren()}, and a view is a schema's child.
     */
    private static void assertDiverge(PgAbstractView a, PgAbstractView b) {
        assertFalse(a.compare(b), "an unreadable view must not compare equal to a different one");
        assertFalse(b.compare(a), "compare must be symmetric");
        assertFalse(Comparison.compare(new CoreSettings(), a, b),
                "and the entry point the diff tree uses must see the difference too");
        assertNotEquals(a.hashCode(), b.hashCode(), "and the hash the tree consults on its own must differ");
    }

    private static String creationScript(PgAbstractView view) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, view.getSeparator());
        view.getCreationSQL(script);
        return script.getFullScript();
    }
}
