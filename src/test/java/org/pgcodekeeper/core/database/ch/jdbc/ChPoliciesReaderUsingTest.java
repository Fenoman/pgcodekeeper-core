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
package org.pgcodekeeper.core.database.ch.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.base.loader.AbstractLoader;
import org.pgcodekeeper.core.database.ch.loader.ChJdbcLoader;
import org.pgcodekeeper.core.database.ch.parser.ChParserUtils;
import org.pgcodekeeper.core.database.ch.parser.generated.CHParser;
import org.pgcodekeeper.core.database.ch.parser.statement.ChCreatePolicy;
import org.pgcodekeeper.core.database.ch.schema.ChDatabase;
import org.pgcodekeeper.core.database.ch.schema.ChPolicy;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The database side of a policy filter, driven through the reader itself.
 * <p>
 * Every other normalized ClickHouse expression is produced by one parser class
 * on both sides, so a test that runs that class covers both. The
 * policy is the exception: {@code system.row_policies} hands over the filter as
 * bare text and {@link ChPoliciesReader} re-parses it on its own. A test that
 * only mirrored those lines would have proven nothing about them - measured, not
 * assumed: with the reader made to skip normalization entirely, the whole suite
 * of 6147 tests stayed green.
 * <p>
 * So this drives {@code processResult} over a mocked catalog row and finishes
 * the loader's ANTLR queue, which is the only way to reach the deferred
 * finalizer where the normalization happens - and the only way to reach the case
 * where it does not happen at all.
 */
class ChPoliciesReaderUsingTest {

    private static final String POLICY_NAME = "pol1 ON default.orders";

    /**
     * A filter the ClickHouse grammar cannot read as an expression. The trailing
     * {@code SETTINGS} clause is what a server accepts inside a row policy and
     * {@code expr_eof} does not; the test asserts the parse failed rather than
     * trusting that claim.
     */
    private static final String UNPARSABLE_FILTER = "status = 'x' SETTINGS max_threads = 1";

    /**
     * The invariant the reader must not trade away: a filter that fails to parse
     * still reaches the model, because the emitted DDL is what restricts the
     * rows. Losing it writes a {@code CREATE POLICY} with no {@code USING},
     * which silently lifts the restriction on every row of the table.
     * <p>
     * The loader finalizes a parse task only when it reported no errors
     * ({@code AbstractJdbcLoader.submitAntlrTask}), so the raw half cannot be
     * assigned there.
     */
    @Test
    void aFilterThatFailsToParseStillReachesTheModel() throws Exception {
        CoreSettings settings = new CoreSettings();
        ChPolicy policy = read(UNPARSABLE_FILTER, settings);

        assertFalse(settings.getErrors().isEmpty(),
                "this filter is supposed to fail the expression grammar, otherwise the test proves nothing");
        assertTrue(creationScript(policy).contains("USING " + UNPARSABLE_FILTER),
                () -> "the filter must survive a failed parse, got:\n" + creationScript(policy));
    }

    /**
     * The sharper half of the same invariant: an unreadable filter must not be
     * mistaken for no filter at all.
     * <p>
     * {@code ChPolicy.compare} and {@code computeHash} read only the normalized
     * half, and an empty normalized half is exactly what a policy without a
     * {@code USING} carries. Leaving that half empty on a failed parse therefore
     * makes a policy the database holds compare <i>equal</i> to a project file
     * whose policy has no filter: no node in the diff tree, no line in the
     * script, and a row restriction left standing in the database with nothing
     * in the project answering for it. So the reader fills both halves with the
     * catalog's own text before it submits the parse, and
     * {@code ChCreatePolicy.setUsingWithAnalyze} overwrites the normalized one
     * when the parse succeeds.
     * <p>
     * {@link Comparison#compare} is asked alongside {@code compare} because it
     * is what the diff tree calls, and it gates on the hash before it looks at
     * anything else.
     */
    @Test
    void anUnreadableFilterIsNotTheSameAsNoFilterAtAll() throws Exception {
        CoreSettings settings = new CoreSettings();
        ChPolicy withFilter = read(UNPARSABLE_FILTER, settings);

        assertFalse(settings.getErrors().isEmpty(),
                "this filter is supposed to fail the expression grammar, otherwise the test proves nothing");

        ChPolicy withoutFilter = read(null, new CoreSettings());
        assertFalse(withFilter.compare(withoutFilter),
                "an unreadable filter must not compare equal to no filter at all");
        assertFalse(withoutFilter.compare(withFilter), "compare must be symmetric");
        assertFalse(Comparison.compare(new CoreSettings(), withFilter, withoutFilter),
                "and the entry point the diff tree uses must see the difference too");
    }

    /**
     * The normalization itself, on the side that has no parser class in common
     * with the project file: a filter the catalog spells one way must compare
     * equal to the same filter a project file spells another way.
     * <p>
     * The catalog spelling below is deliberately not the canonical one - it
     * holds {@code amount>100} tight while the normalizer spaces that operator
     * out. A canonical catalog spelling would make the raw and the normalized
     * text byte-identical, and a reader that skipped normalizing would pass this
     * test unnoticed; measured, it did.
     */
    @Test
    void theReaderNormalizesTheFilterItReparses() throws Exception {
        ChPolicy fromCatalog = read("(status IS NOT NULL AND amount>100)", new CoreSettings());
        ChPolicy fromProjectFile = parseProjectFile("""
                CREATE POLICY pol1 ON default.orders
                  USING (status is not null and amount>100)
                  TO ALL
                """);

        assertTrue(fromCatalog.compare(fromProjectFile),
                "the catalog spelling and the project spelling must read as one policy");
        assertTrue(fromProjectFile.compare(fromCatalog), "compare must be symmetric");
        assertEquals(fromCatalog.hashCode(), fromProjectFile.hashCode(),
                "unchanged policies must hash the same");
    }

    /**
     * And the raw half still wins the DDL on the database side too: what the
     * catalog wrote is what a script carries.
     */
    @Test
    void theCatalogTextIsWhatReachesTheScript() throws Exception {
        String spelling = "(status  IS  NOT  NULL)";
        assertTrue(creationScript(read(spelling, new CoreSettings())).contains("USING " + spelling),
                "the catalog's own spelling must reach the script");
    }

    /**
     * Runs {@link ChPoliciesReader#processResult} over one mocked catalog row
     * and finishes the loader's parse queue, which runs the deferred finalizer
     * exactly as a real load does.
     */
    private static ChPolicy read(String selectFilter, CoreSettings settings) throws Exception {
        // the two arrays are built before the stubbing below, not inside it:
        // stubbing a mock while another stubbing is unfinished is what Mockito
        // reports as UnfinishedStubbingException
        Array noRoles = emptyArray();
        Array noExcepts = emptyArray();

        ResultSet res = mock(ResultSet.class);
        when(res.getString("name")).thenReturn(POLICY_NAME);
        when(res.getBoolean("is_restrictive")).thenReturn(false);
        when(res.getString("select_filter")).thenReturn(selectFilter);
        when(res.getArray("apply_to_list")).thenReturn(noRoles);
        when(res.getArray("apply_to_except")).thenReturn(noExcepts);

        ChDatabase db = new ChDatabase();
        try (ChJdbcLoader loader = new ChJdbcLoader(offlineConnector(), settings)) {
            new ChPoliciesReader(loader, db).processResult(res);
            drain(loader);
        }

        return db.getChildren()
                .filter(ChPolicy.class::isInstance)
                .map(ChPolicy.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reader added no policy"));
    }

    /**
     * The project-file side, for the comparison: {@code ChCreatePolicy} over a
     * whole {@code CREATE POLICY}, parented to a database of its own so that the
     * parent names both hashes cover are the same
     * ({@code AbstractStatement.computeNamesHash}).
     */
    private static ChPolicy parseProjectFile(String sql) {
        List<Object> errors = new ArrayList<>();
        CHParser parser = ChParserUtils.createParser(sql, "ch policy reader test", errors);
        var ctx = parser.ch_file().query(0).stmt().ddl_stmt().create_stmt().create_policy_stmt();
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        ChDatabase db = new ChDatabase();
        new ChCreatePolicy(ctx, db, (CommonTokenStream) parser.getTokenStream(), new CoreSettings())
                .parseObject();
        return db.getChildren()
                .filter(ChPolicy.class::isInstance)
                .map(ChPolicy.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no policy was parsed"));
    }

    private static String creationScript(ChPolicy policy) {
        SQLScript script = new SQLScript(new CoreSettings(), ";");
        policy.getCreationSQL(script);
        return script.getFullScript();
    }

    private static Array emptyArray() throws SQLException {
        Array array = mock(Array.class);
        when(array.getArray()).thenReturn(new Object[0]);
        return array;
    }

    /**
     * Runs the queued parse tasks and their finalizers, which is what
     * {@code ChJdbcLoader.load} does at the end of a real load.
     * {@code finishLoaders} is protected and {@link ChJdbcLoader} is final, so
     * it is reached by reflection - the same way
     * {@code ChJdbcLoaderOwnedResourcesTest} reaches the loader's own lifecycle
     * fields.
     */
    private static void drain(ChJdbcLoader loader) throws ReflectiveOperationException {
        Method finishLoaders = AbstractLoader.class.getDeclaredMethod("finishLoaders");
        finishLoaders.setAccessible(true);
        finishLoaders.invoke(loader);
    }

    /**
     * Nothing here queries - the reader is handed its row directly - so the
     * connector exists only to satisfy the constructor.
     */
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
