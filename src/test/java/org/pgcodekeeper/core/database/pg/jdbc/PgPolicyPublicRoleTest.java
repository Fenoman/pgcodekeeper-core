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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.schema.IStatementContainer;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgPolicy;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The role list of a policy, and the one member of it the catalog cannot name:
 * {@code PUBLIC}.
 * <p>
 * {@code pg_policy.polroles} holds {@code {0}} for a policy that applies to
 * everyone, and {@code 0} is not a row of {@code pg_roles}, so the reader's
 * subquery resolves it to nothing at all - the database side of such a policy
 * carries an empty role set. A project file, meanwhile, spells the word out,
 * and the grammar has no token for it: {@code PUBLIC} arrives as a plain
 * identifier and used to be stored as if it were the name of a role. The two
 * sides then described one and the same policy in different words, and since
 * an {@code ALTER POLICY ... TO PUBLIC} writes {@code {0}} back into the
 * catalog, no migration could ever close the gap.
 * <p>
 * So the empty role set is the canonical spelling of {@code PUBLIC} on both
 * sides, which is what {@link PgPolicy#getCreationSQL} and
 * {@link PgPolicy#appendAlterSQL} already assumed - both write {@code PUBLIC}
 * out of an empty set.
 * <p>
 * Which spellings fold into it is the server's rule, not this test's guess.
 * Measured on PostgreSQL 17.10:
 * <ul>
 * <li>{@code TO PUBLIC}, {@code TO Public}, {@code TO "public"} all store
 * {@code polroles = {0}} - an unquoted word is folded to lower case before the
 * comparison, and the quoted lower-case one matches it as it stands;</li>
 * <li>{@code TO PUBLIC, r1} and {@code TO r1, PUBLIC} also store {@code {0}},
 * with a warning: <i>ignoring specified roles other than PUBLIC</i>. The word
 * swallows the whole list;</li>
 * <li>{@code CREATE ROLE "PUBLIC"} succeeds, and {@code TO "PUBLIC"} then
 * points at that role - a quoted upper-case spelling is a role name like any
 * other and must keep comparing as one.</li>
 * </ul>
 * <p>
 * The database side is driven by running {@link PgPoliciesReader#processResult}
 * over a mocked catalog row rather than by a helper that mirrors it, and the
 * project side by {@link PgDumpLoader} over the file text, for the same reasons
 * {@code PgPolicyNormalizationTest} gives at length.
 */
class PgPolicyPublicRoleTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "t1";
    private static final String POLICY_NAME = "p_visible";
    /** The filter, as a project file spells it. */
    private static final String USING = "(t1.id > 0)";

    /**
     * The same filter as {@code pg_get_expr} hands it over, i.e. without the
     * parentheses {@link PgPoliciesReader} adds itself. Both sides must carry
     * the same expression, or a convergence that fails here would be about the
     * filter rather than about the roles.
     */
    private static final String CATALOG_USING = "t1.id > 0";

    /**
     * The harness itself, before anything is asked of the production code: the
     * reader over a {@code TO PUBLIC} row must produce a policy with no roles.
     * A mock that fed the reader a role would make every convergence below hold
     * for the wrong reason.
     */
    @Test
    void theCatalogSideOfAPublicPolicyCarriesNoRolesAtAll() throws Exception {
        String ddl = creationScript(read());
        assertFalse(ddl.contains("TO "),
                () -> "a policy read from a TO PUBLIC row must carry no role list, got:\n" + ddl);
    }

    @Test
    void aProjectPolicyWrittenToPublicConvergesWithTheDatabase() throws Exception {
        assertConverge(read(), policyOf(policyFile("PUBLIC")));
    }

    /**
     * Every spelling the server folds into the pseudo-role, and the one it does
     * not. The last line is the guard: were the match written as a bare
     * case-insensitive comparison of the text, a genuine role named
     * {@code "PUBLIC"} would vanish from the model.
     */
    @Test
    void theSpellingsTheServerFoldsAreTheOnesThatFold() throws Exception {
        assertConverge(read(), policyOf(policyFile("public")));
        assertConverge(read(), policyOf(policyFile("Public")));
        assertConverge(read(), policyOf(policyFile("\"public\"")));

        assertDiverge(read(), policyOf(policyFile("\"PUBLIC\"")));
    }

    /**
     * {@code PUBLIC} beside other roles, in either order. The server drops the
     * others and says so; a model that kept them would go on to write an
     * {@code ALTER POLICY ... TO r1} that narrows a policy the project declares
     * open to everyone.
     */
    @Test
    void publicSwallowsTheRolesListedBesideIt() throws Exception {
        assertConverge(read(), policyOf(policyFile("PUBLIC, test_user")));
        assertConverge(read(), policyOf(policyFile("test_user, PUBLIC")));
    }

    /**
     * The mutation guard: a named role must still read as a difference against
     * a database policy that names none, or the fix would have been "stop
     * comparing roles".
     */
    @Test
    void aNamedRoleIsStillADifference() throws Exception {
        assertDiverge(read(), policyOf(policyFile("test_user")));
        assertDiverge(read("test_user"), policyOf(policyFile((String) null)));
    }

    /**
     * The whole pipeline, in both directions. A file spelling {@code TO PUBLIC}
     * and a file spelling nothing at all describe the same policy - the state
     * the reader hands over for either of them - so neither direction may
     * produce a statement.
     */
    @Test
    void aPublicPolicyAndAnUnqualifiedOneProduceNoScriptEitherWay() throws Exception {
        String forward = pipeline(policyFile((String) null), policyFile("PUBLIC"));
        assertEquals("", forward.trim(),
                () -> "TO PUBLIC must not read as a change against a policy with no TO clause, got:\n" + forward);

        String backward = pipeline(policyFile("PUBLIC"), policyFile((String) null));
        assertEquals("", backward.trim(), () -> "and neither must the other direction, got:\n" + backward);
    }

    /**
     * The other end of the same fix: a policy that really does move to
     * {@code PUBLIC} must still get its {@code ALTER}. The empty set is a
     * value, not an absence.
     */
    @Test
    void aPolicyMovingToPublicStillGetsItsAlter() throws Exception {
        String script = alterScript(read("test_user"), policyOf(policyFile("PUBLIC")));
        assertTrue(script.contains("TO PUBLIC"),
                () -> "a narrowed policy opened up to everyone must still be altered, got:\n" + script);
    }

    private static void assertConverge(PgPolicy a, PgPolicy b) {
        assertTrue(a.compare(b), "expected the two policies to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged policies must hash the same");
        assertTrue(Comparison.compare(new CoreSettings(), a, b),
                "and the entry point the diff tree uses must call them unchanged too");
    }

    private static void assertDiverge(PgPolicy a, PgPolicy b) {
        assertFalse(a.compare(b), "expected the two policies to compare as changed");
        assertFalse(b.compare(a), "compare must be symmetric");
        assertFalse(Comparison.compare(new CoreSettings(), a, b),
                "and the entry point the diff tree uses must see the difference too");
    }

    private static PgDatabase policyFile(String roles) throws Exception {
        return loadProjectFile("""
                CREATE TABLE %1$s.%2$s (id integer);

                CREATE POLICY %3$s ON %1$s.%2$s%4$s
                  USING %5$s;
                """.formatted(SCHEMA_NAME, TABLE_NAME, POLICY_NAME,
                roles == null ? "" : "\n  TO " + roles, USING));
    }

    private static PgDatabase loadProjectFile(String sql) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "policy public role test", new CoreSettings()).load();
    }

    private static PgPolicy policyOf(PgDatabase db) {
        return db.getChildren()
                .filter(PgSchema.class::isInstance)
                .map(PgSchema.class::cast)
                .map(s -> s.getStatementContainer(TABLE_NAME))
                .filter(c -> c != null)
                .flatMap(IStatementContainer::getChildren)
                .filter(PgPolicy.class::isInstance)
                .map(PgPolicy.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no policy was parsed"));
    }

    private static String creationScript(PgPolicy policy) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, policy.getSeparator());
        policy.getCreationSQL(script);
        return script.getFullScript();
    }

    private static String alterScript(PgPolicy oldPolicy, PgPolicy newPolicy) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, oldPolicy.getSeparator());
        oldPolicy.appendAlterSQL(newPolicy, script);
        return script.getFullScript();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }

    /**
     * Runs {@link PgPoliciesReader#processResult} over one mocked catalog row.
     * With no arguments the row is the one a {@code TO PUBLIC} policy produces:
     * {@code polroles = {0}} resolves through the reader's subquery to an empty
     * array, which is what the column hands over here.
     */
    private static PgPolicy read(String... roles) throws Exception {
        ResultSet res = mock(ResultSet.class);
        when(res.getString("relname")).thenReturn(TABLE_NAME);
        when(res.getString("polname")).thenReturn(POLICY_NAME);
        when(res.getString("polcmd")).thenReturn("*");
        when(res.getString("polqual")).thenReturn(CATALOG_USING);
        when(res.getString("polwithcheck")).thenReturn(null);
        Array array = mock(Array.class);
        when(array.getArray()).thenReturn(roles);
        when(res.getArray("polroles")).thenReturn(array);

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);

        var settings = new CoreSettings();
        try (var loader = new TestLoader(settings)) {
            new PgPoliciesReader(loader).processResult(res, schema);
            // the reader defers the normalization of the filter to a finalizer
            // the loader runs when its parse queue drains
            loader.drain();
        }
        assertTrue(settings.getErrors().isEmpty(),
                () -> "the catalog filter must parse, otherwise the finalizer never runs: " + settings.getErrors());

        return table.getChildren()
                .filter(PgPolicy.class::isInstance)
                .map(PgPolicy.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reader added no policy"));
    }

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
