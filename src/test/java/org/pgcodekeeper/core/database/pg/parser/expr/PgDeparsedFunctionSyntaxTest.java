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
package org.pgcodekeeper.core.database.pg.parser.expr;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.base.parser.FullAnalyze;
import org.pgcodekeeper.core.database.base.schema.meta.MetaContainer;
import org.pgcodekeeper.core.database.base.schema.meta.MetaUtils;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.Pair;

/**
 * The three remaining calls PostgreSQL deparses into a dedicated SQL syntax
 * instead of a function call, and which this grammar could not read back:
 * {@code x IS [NOT] [form] NORMALIZED} and {@code NORMALIZE(x[, form])} (both
 * PostgreSQL 13) and {@code SYSTEM_USER} (PostgreSQL 16).
 * <p>
 * They are the same defect as {@code AT LOCAL}, found by the same sweep and
 * arriving by the same road. {@code ruleutils.c} keys its
 * {@code get_func_sql_syntax} switch on the function's oid, not on how the
 * author spelled the call, so an innocent {@code is_normalized(n)},
 * {@code normalize(n)} or {@code system_user} written as a plain function comes
 * back out of {@code pg_get_viewdef} / {@code pg_get_constraintdef} /
 * {@code pg_get_expr} as {@code (n IS NORMALIZED)}, {@code NORMALIZE(n)} and
 * {@code SYSTEM_USER}. The tool exports that into a project file and then
 * cannot read its own output.
 * <p>
 * As with {@code AT LOCAL}, parsing is only the first half: each construct also
 * has to reach the model, come back out of the script generator unchanged, and
 * be analyzed rather than fall through to {@code unknown_unknown}. Their
 * neighbours in the same grammar rules ride along as controls -
 * {@code IS NOT NULL} for the predicate, {@code TRIM} for the string function,
 * {@code CURRENT_USER} for the system function.
 */
class PgDeparsedFunctionSyntaxTest {

    private static final PgDatabaseProvider PROVIDER = new PgDatabaseProvider();

    private static final String TABLE = """
            CREATE TABLE public.t (
                id integer,
                n text
            );
            """;

    // ------------------------------------------------------------------
    // the grammar half
    // ------------------------------------------------------------------

    /**
     * Every spelling of the predicate, in the parenthesised shape the deparser
     * produces. The four normal forms are one rule, so one of them stands for
     * the set; the {@code NOT} and the form are independent options and are
     * therefore crossed.
     */
    @Test
    void isNormalizedIsParsedInEverySpelling() throws Exception {
        assertParses("(t.n IS NORMALIZED)");
        assertParses("(t.n IS NFC NORMALIZED)");
        assertParses("(t.n IS NOT NORMALIZED)");
        assertParses("(t.n IS NOT NFKD NORMALIZED)");
    }

    /** The function form, with and without the optional normal form. */
    @Test
    void normalizeIsParsedWithAndWithoutTheForm() throws Exception {
        assertParses("NORMALIZE(t.n)");
        assertParses("NORMALIZE(t.n, NFC)");
    }

    /** The keyword form, which takes no parentheses. */
    @Test
    void systemUserIsParsed() throws Exception {
        assertParses("SYSTEM_USER");
    }

    /**
     * The controls: the neighbour of each new alternative, in the same rule.
     * A change that bought one of the three by breaking the alternative next to
     * it would be invisible without them.
     */
    @Test
    void theNeighbouringAlternativesStillParse() throws Exception {
        assertParses("(t.n IS NOT NULL)");
        assertParses("TRIM(BOTH ' ' FROM t.n)");
        assertParses("CURRENT_USER");
    }

    // ------------------------------------------------------------------
    // the analyzer half
    // ------------------------------------------------------------------

    /**
     * A grammar alternative no analyzer reads answers {@code unknown_unknown},
     * and a column of unknown type carries no dependency and matches nothing on
     * the other side. The types are pinned against what PostgreSQL itself
     * declares in {@code pg_proc.dat}: {@code is_normalized} returns
     * {@code bool}, {@code normalize} and {@code system_user} return
     * {@code text}.
     */
    @Test
    void allThreeAreAnalyzedWithTheTypePostgresDeclares() throws Exception {
        assertEquals(PgTypesSetManually.BOOLEAN, columnTypeOf("(t.n IS NORMALIZED)"),
                "is_normalized returns bool");
        assertEquals(PgTypesSetManually.BOOLEAN, columnTypeOf("(t.n IS NOT NFC NORMALIZED)"),
                "and so does every other spelling of it");
        assertEquals(PgTypesSetManually.TEXT, columnTypeOf("NORMALIZE(t.n)"),
                "normalize returns text");
        assertEquals(PgTypesSetManually.TEXT, columnTypeOf("NORMALIZE(t.n, NFC)"),
                "with the form too");
        assertEquals(PgTypesSetManually.TEXT, columnTypeOf("SYSTEM_USER"),
                "system_user returns text, unlike the name-typed CURRENT_USER beside it");
    }

    /**
     * And the neighbour that shares the analyzer branch with {@code SYSTEM_USER}
     * keeps its own answer: {@code current_user} is {@code name}, not
     * {@code text}. Stated because the two are one branch away from each other
     * and the cheapest wrong fix is to give both the same type.
     */
    @Test
    void currentUserKeepsItsOwnType() throws Exception {
        assertEquals(PgTypesSetManually.NAME, columnTypeOf("CURRENT_USER"),
                "current_user is name-typed and must stay that way");
    }

    // ------------------------------------------------------------------
    // the round trip
    // ------------------------------------------------------------------

    /**
     * The whole loop for each of the three: model, script, and back. A parse
     * that merely reported no error would still fail here, because a body that
     * never reached the model cannot be written out again.
     */
    @Test
    void allThreeSurviveTheRoundTrip() throws Exception {
        assertRoundTrips("(t.n IS NORMALIZED)");
        assertRoundTrips("(t.n IS NOT NFC NORMALIZED)");
        assertRoundTrips("NORMALIZE(t.n)");
        assertRoundTrips("NORMALIZE(t.n, NFC)");
        assertRoundTrips("SYSTEM_USER");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void assertParses(String expression) throws Exception {
        var settings = new CoreSettings();
        load(viewOver(expression), settings);
        assertEquals(List.of(), settings.getErrors(),
                () -> expression + " must parse without errors");
    }

    private static void assertRoundTrips(String expression) throws Exception {
        var settings = new CoreSettings();
        PgDatabase declared = load(viewOver(expression), settings);
        assertEquals(List.of(), settings.getErrors(),
                () -> "the round-trip source over " + expression + " must parse without errors");

        String script = PgCodeKeeperApi.diff(PROVIDER, load(TABLE, new CoreSettings()), declared,
                new CoreSettings());
        assertTrue(script.contains(expression),
                () -> "the generated script must still carry " + expression + ", got:\n" + script);

        var reloadSettings = new CoreSettings();
        PgDatabase reloaded = load(TABLE + script, reloadSettings);
        assertEquals(List.of(), reloadSettings.getErrors(),
                () -> "the script generated for " + expression + " must read back without errors");

        String residue = PgCodeKeeperApi.diff(PROVIDER, declared, reloaded, new CoreSettings());
        assertEquals("", residue.trim(),
                () -> "the reloaded view over " + expression + " must be the same object, got:\n" + residue);
    }

    /**
     * The analyzed type of the single column of a view built over the given
     * expression, read out of the meta container - the route the analysis
     * deposits column types on.
     */
    private static String columnTypeOf(String expression) throws Exception {
        var settings = new CoreSettings();
        PgDatabase db = load(viewOver(expression), settings);
        MetaContainer meta = MetaUtils.createTreeFromDb(db, settings.getVersion());
        FullAnalyze.fullAnalyze(db, meta, settings.getErrors());
        assertEquals(List.of(), settings.getErrors(),
                () -> "the analyzed view over " + expression + " must parse without errors");

        var columns = meta.getRelations().get("public").get("v").getRelationColumns();
        assertNotNull(columns, () -> "the view over " + expression + " has no analyzed columns");
        return columns
                .filter(column -> "c".equals(column.getFirst()))
                .map(Pair::getSecond)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the view has no analyzed column c"));
    }

    private static String viewOver(String expression) {
        return TABLE + "CREATE VIEW public.v AS SELECT " + expression + " AS c FROM public.t t;";
    }

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return load(sql, new CoreSettings());
    }

    private static PgDatabase load(String sql, CoreSettings settings)
            throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "deparsed function syntax test", settings).load();
    }
}
