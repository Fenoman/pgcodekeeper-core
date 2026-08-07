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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * The {@code AT LOCAL} operator PostgreSQL 17 added beside {@code AT TIME ZONE}
 * (gram.y: {@code a_expr AT LOCAL}), read from the side that matters here - the
 * database writes it and the project has to be able to read it back.
 * <p>
 * The construct is not merely something a user may type. PG17's
 * {@code ruleutils.c} deparses the one-argument {@code timezone(x)} as
 * {@code (x AT LOCAL)}, so {@code pg_get_viewdef} and {@code pg_get_expr} emit
 * it on their own. Against a server that does - the target here is Tantor SE
 * 17.9 - a view or a default written with {@code timezone(x)}, or with
 * {@code AT LOCAL} itself, comes out of the database in that shape, is exported
 * into a project file verbatim, and is then handed back to this same parser on
 * the next load. A grammar that cannot read it turns the tool's own output into
 * a file the tool rejects: the statement drops out of the model and everything
 * depending on it follows.
 * <p>
 * Hence the tests below do not stop at "it parses". Parsing is only the first
 * half; the second is that the text survives the exit through the script
 * generator and comes back as the same object.
 * <p>
 * {@code AT TIME ZONE} rides along in every case as the control: it shares the
 * {@code AT} token and the same position in {@code vex}, so a change that broke
 * it while fixing {@code AT LOCAL} would be invisible without it.
 */
class PgAtLocalTest {

    private static final PgDatabaseProvider PROVIDER = new PgDatabaseProvider();

    private static final String TABLE = """
            CREATE TABLE public.t (
                id integer,
                ts timestamp with time zone
            );
            """;

    /**
     * The plain shape, as a person writes it.
     */
    @Test
    void aViewBodyWithAtLocalIsParsed() throws Exception {
        var settings = new CoreSettings();
        load(TABLE + "CREATE VIEW public.v AS SELECT t.ts AT LOCAL AS l FROM public.t t;", settings);
        assertNoErrors(settings, "AT LOCAL in a view body");
    }

    /**
     * The shape the database itself produces: {@code ruleutils} parenthesises the
     * operator, and {@code pg_get_viewdef} qualifies the column. This is the
     * literal text that arrives from a PG17 server.
     */
    @Test
    void theShapeThePostgres17DeparserEmitsIsParsed() throws Exception {
        var settings = new CoreSettings();
        load(TABLE + "CREATE VIEW public.v AS SELECT (t.ts AT LOCAL) AS l FROM public.t t;", settings);
        assertNoErrors(settings, "the deparsed (x AT LOCAL) shape");
    }

    /**
     * The same operator on the other channel the reader uses - a column default,
     * which reaches the grammar through {@code vex_eof} rather than through a
     * {@code SELECT}.
     */
    @Test
    void aColumnDefaultWithAtLocalIsParsed() throws Exception {
        var settings = new CoreSettings();
        load("""
                CREATE TABLE public.t2 (
                    id integer,
                    ts timestamp DEFAULT (now() AT LOCAL)
                );
                """, settings);
        assertNoErrors(settings, "AT LOCAL in a column default");
    }

    /**
     * The control. {@code AT TIME ZONE} was always accepted; it must stay so.
     */
    @Test
    void atTimeZoneIsStillParsed() throws Exception {
        var settings = new CoreSettings();
        load(TABLE + "CREATE VIEW public.v AS SELECT (t.ts AT TIME ZONE 'UTC') AS l FROM public.t t;", settings);
        assertNoErrors(settings, "AT TIME ZONE");
    }

    /**
     * The whole loop, which is the actual claim: a view carrying {@code AT LOCAL}
     * goes through the script generator, the generated script is read back, and
     * the model that comes out is the model that went in. A parse that merely
     * did not report an error would still fail here, because a body that never
     * reached the model cannot be written out again.
     */
    @Test
    void atLocalSurvivesTheRoundTrip() throws Exception {
        String viewSql = TABLE + "CREATE VIEW public.v AS SELECT (t.ts AT LOCAL) AS l FROM public.t t;";

        var settings = new CoreSettings();
        PgDatabase declared = load(viewSql, settings);
        assertNoErrors(settings, "the round-trip source");

        String script = PgCodeKeeperApi.diff(PROVIDER, load(TABLE), declared, new CoreSettings());
        assertTrue(script.contains("AT LOCAL"),
                () -> "the generated script must still carry the operator, got:\n" + script);

        var reloadSettings = new CoreSettings();
        PgDatabase reloaded = load(TABLE + script, reloadSettings);
        assertNoErrors(reloadSettings, "the generated script read back");

        String residue = PgCodeKeeperApi.diff(PROVIDER, declared, reloaded, new CoreSettings());
        assertEquals("", residue.trim(),
                () -> "the reloaded view must be the same object, got:\n" + residue);
    }

    /**
     * The second half of "reaches the model": a grammar alternative no analyzer
     * reads is the same defect one step later. {@code PgValueExpr.analyze} ends
     * in a fallthrough that logs {@code no vex alternative} and answers
     * {@code unknown_unknown}, so an unread {@code AT LOCAL} would leave the
     * view's column typed as nothing at all - and a column of unknown type
     * carries no dependency and matches nothing on the other side.
     * <p>
     * The expectation is stated against {@code AT TIME ZONE} rather than against
     * a literal: the two are one alternative pair, this analyzer answers both
     * with the operand's own type, and pinning the literal would only record
     * that approximation twice.
     */
    @Test
    void atLocalIsTypedTheWayAtTimeZoneIs() throws Exception {
        assertEquals(columnTypeOf("(t.ts AT TIME ZONE 'UTC')"), columnTypeOf("(t.ts AT LOCAL)"),
                "AT LOCAL must be analyzed, not fall through to the unknown type");
        assertNotEquals(PgTypesSetManually.UNKNOWN, columnTypeOf("(t.ts AT LOCAL)"),
                "and the shared answer must not itself be the unknown type");
    }

    /**
     * The analyzed type of the single column of a view built over the given
     * expression. Read out of the meta container rather than off the view,
     * because that is where the analysis deposits column types - the same route
     * {@code PgExprTypeTest} takes.
     */
    private static String columnTypeOf(String expression) throws Exception {
        var settings = new CoreSettings();
        PgDatabase db = load(TABLE + "CREATE VIEW public.v AS SELECT " + expression + " AS l FROM public.t t;",
                settings);
        MetaContainer meta = MetaUtils.createTreeFromDb(db, settings.getVersion());
        FullAnalyze.fullAnalyze(db, meta, settings.getErrors());
        assertNoErrors(settings, "the analyzed view over " + expression);

        var columns = meta.getRelations().get("public").get("v").getRelationColumns();
        assertNotNull(columns, () -> "the view over " + expression + " has no analyzed columns");
        return columns
                .filter(column -> "l".equals(column.getFirst()))
                .map(Pair::getSecond)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the view has no analyzed column l"));
    }

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return load(sql, new CoreSettings());
    }

    private static PgDatabase load(String sql, CoreSettings settings)
            throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "at local test", settings).load();
    }

    private static void assertNoErrors(CoreSettings settings, String what) {
        assertEquals(List.of(), settings.getErrors(),
                () -> what + " must parse without errors");
    }
}
