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
package org.pgcodekeeper.core.database.ch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.ObjectState;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.ch.parser.ChParserUtils;
import org.pgcodekeeper.core.database.ch.parser.generated.CHParser;
import org.pgcodekeeper.core.database.ch.parser.generated.CHParser.Create_function_stmtContext;
import org.pgcodekeeper.core.database.ch.parser.generated.CHParser.Expr_eofContext;
import org.pgcodekeeper.core.database.ch.parser.statement.ChCreateDictionary;
import org.pgcodekeeper.core.database.ch.parser.statement.ChCreateFunction;
import org.pgcodekeeper.core.database.ch.parser.statement.ChCreatePolicy;
import org.pgcodekeeper.core.database.ch.parser.statement.ChCreateTable;
import org.pgcodekeeper.core.database.ch.parser.statement.ChCreateView;
import org.pgcodekeeper.core.database.ch.schema.ChColumn;
import org.pgcodekeeper.core.database.ch.schema.ChConstraint;
import org.pgcodekeeper.core.database.ch.schema.ChDatabase;
import org.pgcodekeeper.core.database.ch.schema.ChDictionary;
import org.pgcodekeeper.core.database.ch.schema.ChEngine;
import org.pgcodekeeper.core.database.ch.schema.ChFunction;
import org.pgcodekeeper.core.database.ch.schema.ChIndex;
import org.pgcodekeeper.core.database.ch.schema.ChPolicy;
import org.pgcodekeeper.core.database.ch.schema.ChTable;
import org.pgcodekeeper.core.database.ch.schema.ChView;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.Pair;

/**
 * The ClickHouse expressions kept as text and compared as text: a column
 * {@code DEFAULT}, a column {@code TTL}, a {@code CONSTRAINT} body, an
 * {@code INDEX} expression, the six engine clauses, a table projection, the
 * four dictionary clauses, a policy filter, a function body and a view query.
 * Re-spacing one of them, or re-casing a reserved word inside it, must not read
 * as a changed object.
 * <p>
 * Every one of them but the policy is built by one parser class on both sides
 * of a comparison: {@code ChCustomParserListener} runs {@link ChCreateTable},
 * {@link ChCreateDictionary}, {@link ChCreateFunction} and
 * {@link ChCreateView} over a project file, while {@code ChRelationsReader}
 * runs the first, the second and the fourth over the text of
 * {@code SHOW CREATE TABLE} and {@code ChFunctionsReader} runs the third over
 * {@code system.functions.create_query}. The policy is the exception: its
 * database side holds nothing but the filter text of
 * {@code system.row_policies} and re-parses that alone in
 * {@code ChPoliciesReader}, so the two sides of a policy meet in the normalized
 * text and nowhere else.
 * <p>
 * Every reserved word varied below sits inside the folded token range
 * {@code CHLexer.ALL..WITH} (449..508): {@code AND} (451), {@code CASE} (458),
 * {@code ELSE} (463), {@code END} (464), {@code GROUP} (472), {@code IS} (481),
 * {@code NOT} (486), {@code NULL} (487), {@code SELECT} (496), {@code THEN}
 * (499), {@code WHEN} (505). A word outside that range is compared as written,
 * so it is held in one case wherever it occurs here, and so are identifiers and
 * function names - ClickHouse is case-sensitive about those on the server,
 * which is why {@code ChParserUtils.getTokenText} folds that range and nothing
 * else.
 */
class ChExpressionNormalizationTest {

    /**
     * How a hand-written project file spells the table: reserved words in lower
     * case, operators pressed against their operands.
     */
    private static final String TIGHT_LOWER_CASE = """
            CREATE TABLE default.orders
            (
                id UInt64,
                status String,
                created DateTime,
                is_active UInt8 DEFAULT (id>0 and status is not null),
                payload String TTL created+toIntervalDay(case when id>100 then 7 else 30 end),
                CONSTRAINT c_status_known CHECK (status is not null and status!=''),
                INDEX ix_status (status,id>0 and id<100) TYPE minmax GRANULARITY 1
            )
            ENGINE = MergeTree
            ORDER BY id
            """;

    /**
     * The same table as {@code SHOW CREATE TABLE} renders it: reserved words in
     * upper case, everything spaced out. Nothing but whitespace and reserved-word
     * case differs from {@link #TIGHT_LOWER_CASE}.
     */
    private static final String SPACED_UPPER_CASE = """
            CREATE TABLE default.orders
            (
                id UInt64,
                status String,
                created DateTime,
                is_active UInt8 DEFAULT (id > 0 AND status IS NOT NULL),
                payload String TTL created + toIntervalDay(CASE WHEN id > 100 THEN 7 ELSE 30 END),
                CONSTRAINT c_status_known CHECK (status IS NOT NULL AND status != ''),
                INDEX ix_status (status, id > 0 AND id < 100) TYPE minmax GRANULARITY 1
            )
            ENGINE = MergeTree
            ORDER BY id
            """;

    /**
     * {@link #TIGHT_LOWER_CASE} with a genuinely different {@code DEFAULT} and
     * {@code TTL}, still written tightly. Migrating to this table is what puts
     * the new expressions into a script, which is where the raw text - and only
     * the raw text - has to surface.
     */
    private static final String TIGHT_LOWER_CASE_CHANGED = """
            CREATE TABLE default.orders
            (
                id UInt64,
                status String,
                created DateTime,
                is_active UInt8 DEFAULT (id>5 and status is not null),
                payload String TTL created+toIntervalDay(case when id>200 then 3 else 45 end),
                CONSTRAINT c_status_known CHECK (status is not null and status!=''),
                INDEX ix_status (status,id>0 and id<100) TYPE minmax GRANULARITY 1
            )
            ENGINE = MergeTree
            ORDER BY id
            """;

    /**
     * Index of the engine body inside {@link #ENGINE_TIGHT} and its siblings,
     * one less than the argument number of its specifier in
     * {@link #ENGINE_TEMPLATE}.
     */
    private static final int ENGINE_BODY = 0;
    private static final int PARTITION_BY = 1;
    private static final int PRIMARY_KEY = 2;
    private static final int ORDER_BY = 3;
    private static final int SAMPLE_BY = 4;
    private static final int ENGINE_TTL = 5;
    private static final int PROJECTION = 6;

    /**
     * One table whose six engine clauses and whose projection body are all
     * filled in from a list, so that a test can vary exactly one of them and
     * hold the other six fixed. The list indices are the constants above.
     */
    private static final String ENGINE_TEMPLATE = """
            CREATE TABLE default.events
            (
                id UInt64,
                status String,
                created DateTime,
                amount UInt32,
                ver UInt64,
                PROJECTION p_by_status (%7$s)
            )
            ENGINE = ReplacingMergeTree(%1$s)
            PARTITION BY %2$s
            PRIMARY KEY %3$s
            ORDER BY %4$s
            SAMPLE BY %5$s
            TTL %6$s
            SETTINGS index_granularity = 8192
            """;

    /**
     * Every clause of {@link #ENGINE_TEMPLATE} written the way a hand-kept
     * project file tends to look: reserved words lower case, operators and
     * commas pressed against their operands. {@code GROUP BY} in the projection
     * is the one exception - it is upper case here as well, and
     * {@link #ENGINE_SPACED} says why it has to be.
     */
    private static final List<String> ENGINE_TIGHT = List.of(
            "ver,toUInt8(status is not null)",
            "(toYYYYMM(created),status is not null)",
            "(id,status)",
            "(id,created)",
            "intHash32(id+0)",
            "created+toIntervalDay(case when amount>1000 then 7 else 90 end)",
            "select status,count() GROUP BY status");

    /**
     * The same seven clauses as {@code SHOW CREATE TABLE} renders them. Only
     * whitespace and the case of reserved words differ from
     * {@link #ENGINE_TIGHT}.
     * <p>
     * {@code BY} is held upper case on both sides on purpose: its token number
     * is 67, outside the folded range {@code CHLexer.ALL..WITH} (449..508), so
     * the normalizer treats it exactly like an identifier and re-casing it is a
     * real difference. {@code SELECT} (496), {@code GROUP} (472), {@code IS}
     * (481), {@code NOT} (486), {@code NULL} (487), {@code CASE} (458),
     * {@code WHEN} (505), {@code THEN} (499), {@code ELSE} (463) and
     * {@code END} (464) are all inside it.
     */
    private static final List<String> ENGINE_SPACED = List.of(
            "ver, toUInt8(status IS NOT NULL)",
            "(toYYYYMM(created), status IS NOT NULL)",
            "(id, status)",
            "(id, created)",
            "intHash32(id + 0)",
            "created + toIntervalDay(CASE WHEN amount > 1000 THEN 7 ELSE 90 END)",
            "SELECT status, count() GROUP BY status");

    /**
     * A table whose {@code PRIMARY KEY} sits among the table elements rather
     * than among the engine options. Both spellings land in the same engine
     * field but by different routes - {@code ChTable.setPkExpr} against
     * {@code ChParserAbstract.parseEngineOption} - so normalizing one route and
     * not the other would make the two read as different tables.
     */
    private static final String PRIMARY_KEY_IN_TABLE_BODY = """
            CREATE TABLE default.events
            (
                id UInt64,
                status String,
                PRIMARY KEY (id,status is not null)
            )
            ENGINE = MergeTree
            ORDER BY id
            """;

    /**
     * The same key written after the engine, spelled exactly as the table body
     * above spells it. This is the pair that carries the regression: the two are
     * one key, so a normalization that reached only the engine-option route
     * would pull them apart.
     * <p>
     * Written after the engine is also the shape
     * {@code ChEngine.appendCreationSQL} emits, so it is what a project file
     * this tool exported carries - a body-declared key comes back out here.
     */
    private static final String PRIMARY_KEY_IN_ENGINE_SAME_SPELLING = """
            CREATE TABLE default.events
            (
                id UInt64,
                status String
            )
            ENGINE = MergeTree
            PRIMARY KEY (id,status is not null)
            ORDER BY id
            """;

    /**
     * The same key after the engine and spaced out. This pair is the gain rather
     * than the regression guard: without normalization it reads as a changed
     * table.
     */
    private static final String PRIMARY_KEY_IN_ENGINE_RESPACED = """
            CREATE TABLE default.events
            (
                id UInt64,
                status String
            )
            ENGINE = MergeTree
            PRIMARY KEY (id, status IS NOT NULL)
            ORDER BY id
            """;

    /**
     * Index of a dictionary clause inside {@link #DICTIONARY_TIGHT} and its
     * sibling, one less than the argument number of its specifier in
     * {@link #DICTIONARY_TEMPLATE}.
     */
    private static final int DICTIONARY_PK = 0;
    private static final int DICTIONARY_LIFETIME = 1;
    private static final int DICTIONARY_LAYOUT = 2;
    private static final int DICTIONARY_RANGE = 3;

    /**
     * One dictionary whose four stored clauses are filled in from a list, so
     * that a test can vary exactly one of them and hold the other three fixed.
     * The list indices are the constants above.
     */
    private static final String DICTIONARY_TEMPLATE = """
            CREATE DICTIONARY default.zones
            (
                id UInt64,
                status String,
                range_start Date,
                range_end Date
            )
            PRIMARY KEY %1$s
            SOURCE(CLICKHOUSE(HOST 'localhost' PORT 9000 TABLE 'zones_source'))
            LIFETIME(%2$s)
            LAYOUT(%3$s)
            RANGE(%4$s)
            """;

    /**
     * The four clauses written the way a hand-kept project file tends to look:
     * operators and commas pressed against their operands, reserved words lower
     * case.
     * <p>
     * Only the key can hold a reserved word at all. The grammar takes an
     * {@code expr_list} there ({@code CHParser.g4:718}), while
     * {@code life_time_expr} is {@code MIN NUMBER MAX NUMBER},
     * {@code range_expr} is {@code MIN identifier MAX identifier} and
     * {@code layout_expr} is {@code identifier LPAREN args RPAREN}
     * ({@code CHParser.g4:741-753}), so in those three only whitespace can
     * differ between two spellings of one clause. A production dictionary key
     * names attributes rather than computing them; it is written as an
     * expression here because that is what makes the fold visible on this
     * object.
     */
    private static final List<String> DICTIONARY_TIGHT = List.of(
            "id,toUInt8(status is not null)",
            "MIN 0 MAX 100",
            "RANGE_HASHED(range_lookup_strategy 'max')",
            "MIN range_start MAX range_end");

    /**
     * The same four clauses spaced out, and the key's reserved words raised.
     * <p>
     * {@code MIN} (246) and {@code MAX} (234) are held in one case on both
     * sides: they sit outside the folded range {@code CHLexer.ALL..WITH}
     * (449..508), so the normalizer treats them exactly like the identifiers
     * beside them. The key varies {@code IS} (481), {@code NOT} (486) and
     * {@code NULL} (487), which are inside it.
     */
    private static final List<String> DICTIONARY_SPACED = List.of(
            "id, toUInt8(status IS NOT NULL)",
            "MIN  0  MAX  100",
            "RANGE_HASHED( range_lookup_strategy 'max' )",
            "MIN  range_start  MAX  range_end");

    /**
     * A row policy as a hand-kept project file spells it.
     */
    private static final String POLICY_TIGHT = """
            CREATE POLICY pol1 ON default.orders
              USING (status is not null and amount>100)
              AS RESTRICTIVE
              TO ALL
            """;

    /**
     * The same policy spaced out, with the reserved words of the folded range
     * raised: {@code IS} (481), {@code NOT} (486), {@code NULL} (487) and
     * {@code AND} (451).
     */
    private static final String POLICY_SPACED = """
            CREATE POLICY pol1 ON default.orders
              USING (status IS NOT NULL AND amount > 100)
              AS RESTRICTIVE
              TO ALL
            """;

    /**
     * {@link #POLICY_TIGHT} with a genuinely different filter, still written
     * tightly. Migrating to this policy is what puts the new expression into a
     * script, which is where the raw text - and only the raw text - has to
     * surface.
     */
    private static final String POLICY_TIGHT_CHANGED = """
            CREATE POLICY pol1 ON default.orders
              USING (status is not null and amount>200)
              AS RESTRICTIVE
              TO ALL
            """;

    /**
     * A user-defined function as a hand-kept project file spells it.
     */
    private static final String FUNCTION_TIGHT = """
            CREATE FUNCTION is_open AS (status,amount) -> (status is not null and amount>100)
            """;

    /**
     * The same function spaced out, with the same four reserved words raised as
     * in {@link #POLICY_SPACED}. The argument names and the function name are
     * held in one case - ClickHouse is case-sensitive about them on the server.
     */
    private static final String FUNCTION_SPACED = """
            CREATE FUNCTION is_open AS (status, amount) -> (status IS NOT NULL AND amount > 100)
            """;

    /**
     * A materialized view whose query is filled in from a constant, so that one
     * spelling can be swapped for another with everything else held fixed.
     * <p>
     * Materialized is not decoration. {@code ChView.isViewModified} answers
     * {@code true} for a changed query on any other kind of view, which makes
     * {@code appendAlterSQL} return {@link ObjectState#RECREATE} before it
     * writes anything; {@code MODIFY QUERY} is reachable on a materialized view
     * and on nothing else.
     */
    private static final String VIEW_TEMPLATE = """
            CREATE MATERIALIZED VIEW default.mv
            (
                `col1` String,
                `col2` UInt64
            )
            ENGINE = MergeTree
            ORDER BY col1
            AS %s
            """;

    /**
     * The query as a hand-kept project file spells it: laid out over four lines,
     * operators and commas pressed against their operands, and the reserved
     * words of the folded range {@code CHLexer.ALL..WITH} (449..508) in lower
     * case - {@code AS} (455), {@code FROM} (469), {@code GROUP} (472),
     * {@code SELECT} (496) and {@code WHERE} (506). {@code BY} (67) is outside
     * the range and is held upper case on both sides, as everywhere else in this
     * class.
     */
    private static final String VIEW_QUERY_TIGHT = """
            select status as col1,count() as col2
              from default.orders
             where amount>100
             GROUP BY status""";

    /**
     * What the normalizer makes of {@link #VIEW_QUERY_TIGHT}, character for
     * character - measured by running it, not derived from the rules - and
     * roughly what {@code SHOW CREATE VIEW} returns for the same view.
     */
    private static final String VIEW_QUERY_SPACED =
            "SELECT status AS col1, count() AS col2 FROM default.orders WHERE amount > 100 GROUP BY status";

    /**
     * A genuinely different query, still spelled the way the project file spells
     * it. Migrating to this view is what puts a query into a
     * {@code MODIFY QUERY}, which is where the raw text - and only the raw text
     * - has to surface.
     */
    private static final String VIEW_QUERY_TIGHT_CHANGED = """
            select status as col1,count() as col2
              from default.orders
             where amount>200
             GROUP BY status""";

    @Test
    void aRespacedColumnDefaultReadsAsUnchanged() {
        assertConverge(column(parse(TIGHT_LOWER_CASE), "is_active"),
                column(parse(SPACED_UPPER_CASE), "is_active"));
    }

    @Test
    void aRespacedColumnTtlReadsAsUnchanged() {
        assertConverge(column(parse(TIGHT_LOWER_CASE), "payload"),
                column(parse(SPACED_UPPER_CASE), "payload"));
    }

    @Test
    void aRespacedConstraintExpressionReadsAsUnchanged() {
        assertConverge(constraint(parse(TIGHT_LOWER_CASE), "c_status_known"),
                constraint(parse(SPACED_UPPER_CASE), "c_status_known"));
    }

    @Test
    void aRespacedIndexExpressionReadsAsUnchanged() {
        assertConverge(index(parse(TIGHT_LOWER_CASE), "ix_status"),
                index(parse(SPACED_UPPER_CASE), "ix_status"));
    }

    @Test
    void theWholeTableReadsAsUnchanged() {
        assertConverge(parse(TIGHT_LOWER_CASE), parse(SPACED_UPPER_CASE));
    }

    /**
     * The raw text is what the DDL is written from, so it must survive parsing
     * exactly as its author spelled it - normalization may not leak into output.
     * All four sites are covered: the two column definitions, the constraint
     * definition and the index creation statement.
     */
    @Test
    void theRawTextStillDrivesDdlGeneration() {
        ChTable table = parse(TIGHT_LOWER_CASE);
        assertEquals("`id` UInt64", column(table, "id").getFullDefinition());
        assertContains(column(table, "is_active").getFullDefinition(),
                "DEFAULT (id>0 and status is not null)", "the column default");
        assertContains(column(table, "payload").getFullDefinition(),
                "TTL created+toIntervalDay(case when id>100 then 7 else 30 end)", "the column TTL");
        assertEquals("CHECK (status is not null and status!='')",
                constraint(table, "c_status_known").getDefinition());
        assertContains(creationScript(index(table, "ix_status")),
                "INDEX ix_status (status,id>0 and id<100) TYPE minmax", "the index expression");
    }

    /**
     * The invariant the comparison exists to serve: what lands in the migration
     * is the new object's raw text. Nothing in the type system stops a later
     * refactor from writing the normalized twin here instead, so the script a
     * real ALTER produces is asserted character for character, not just the
     * definition strings above.
     */
    @Test
    void theRawTextIsWhatReachesTheAlterScript() {
        ChTable old = parse(TIGHT_LOWER_CASE);
        ChTable changed = parse(TIGHT_LOWER_CASE_CHANGED);

        String defaultScript = alterScript(column(old, "is_active"), column(changed, "is_active"));
        assertContains(defaultScript, "DEFAULT (id>5 and status is not null)",
                "the column default reaching the script");

        String ttlScript = alterScript(column(old, "payload"), column(changed, "payload"));
        assertContains(ttlScript, "TTL created+toIntervalDay(case when id>200 then 3 else 45 end)",
                "the column TTL reaching the script");
    }

    @Test
    void aRespacedEngineBodyReadsAsUnchanged() {
        assertClauseConverges(ENGINE_BODY);
    }

    @Test
    void aRespacedPartitionByReadsAsUnchanged() {
        assertClauseConverges(PARTITION_BY);
    }

    @Test
    void aRespacedPrimaryKeyReadsAsUnchanged() {
        assertClauseConverges(PRIMARY_KEY);
    }

    @Test
    void aRespacedOrderByReadsAsUnchanged() {
        assertClauseConverges(ORDER_BY);
    }

    @Test
    void aRespacedSampleByReadsAsUnchanged() {
        assertClauseConverges(SAMPLE_BY);
    }

    @Test
    void aRespacedEngineTtlReadsAsUnchanged() {
        assertClauseConverges(ENGINE_TTL);
    }

    @Test
    void aRespacedProjectionReadsAsUnchanged() {
        assertClauseConverges(PROJECTION);
    }

    @Test
    void theWholeRespacedEngineReadsAsUnchanged() {
        assertConverge(parse(render(ENGINE_TIGHT)), parse(render(ENGINE_SPACED)));
    }

    /**
     * A {@code PRIMARY KEY} among the table elements and the same key among the
     * engine options are the same key and reach the same engine field, by
     * {@code ChTable.setPkExpr} and {@code ChParserAbstract.parseEngineOption}
     * respectively.
     * <p>
     * The first assertion is the regression guard and the reason
     * {@code setPkExpr} has to be normalized too: spelled identically, the two
     * must compare equal, and normalizing only the engine-option route would
     * make them compare unequal - a loss, not a missing gain. The second
     * assertion is the gain: the same key spaced out must compare equal as well.
     */
    @Test
    void aPrimaryKeyInTheTableBodyMatchesOneAmongTheEngineOptions() {
        assertConverge(parse(PRIMARY_KEY_IN_TABLE_BODY), parse(PRIMARY_KEY_IN_ENGINE_SAME_SPELLING));
        assertConverge(parse(PRIMARY_KEY_IN_TABLE_BODY), parse(PRIMARY_KEY_IN_ENGINE_RESPACED));
    }

    @Test
    void aGenuinelyChangedEngineClauseStillReadsAsChanged() {
        assertClauseDiverges(ENGINE_BODY, "ver,toUInt8(status is null)");
        assertClauseDiverges(PARTITION_BY, "(toYYYYMM(created),amount is not null)");
        assertClauseDiverges(PRIMARY_KEY, "(id,created)");
        assertClauseDiverges(ORDER_BY, "(id,status)");
        assertClauseDiverges(SAMPLE_BY, "intHash32(id+1)");
        assertClauseDiverges(ENGINE_TTL,
                "created+toIntervalDay(case when amount>1000 then 7 else 91 end)");
        assertClauseDiverges(PROJECTION, "select status,sum(amount) GROUP BY status");
    }

    /**
     * The output side of the engine and the projection. Everything the engine
     * puts into a {@code CREATE} is written from the raw text, and so is the
     * projection line of the table body.
     */
    @Test
    void theRawEngineTextStillDrivesDdlGeneration() {
        String created = creationScript(parse(render(ENGINE_TIGHT)));
        assertContains(created, "ENGINE = ReplacingMergeTree (ver,toUInt8(status is not null))",
                "the engine body");
        assertContains(created, "PARTITION BY (toYYYYMM(created),status is not null)",
                "the PARTITION BY");
        assertContains(created, "PRIMARY KEY (id,status)", "the PRIMARY KEY");
        assertContains(created, "ORDER BY (id,created)", "the ORDER BY");
        assertContains(created, "SAMPLE BY intHash32(id+0)", "the SAMPLE BY");
        assertContains(created, "TTL created+toIntervalDay(case when amount>1000 then 7 else 90 end)",
                "the engine TTL");
        assertContains(created, "PROJECTION p_by_status (select status,count() GROUP BY status)",
                "the projection");
    }

    /**
     * The output side again, this time through a real migration.
     * <p>
     * Of the seven clauses varied elsewhere in this class, only three carry an
     * expression into an {@code ALTER}: the engine {@code SAMPLE BY}, the engine
     * {@code TTL} and a projection. Changing the engine body, the
     * {@code PARTITION BY}, the {@code PRIMARY KEY} or the {@code ORDER BY}
     * returns {@link ObjectState#RECREATE} with an empty script, which would
     * assert nothing about spelling - hence only three are varied here.
     * <p>
     * Two other things do reach an {@code ALTER} - a settings value produces
     * {@code MODIFY SETTING} and the table comment produces
     * {@code MODIFY COMMENT} - so this is not the whole of what
     * {@code ChTable.appendAlterSQL} can emit. Neither is a normalized
     * expression.
     */
    @Test
    void theRawEngineTextIsWhatReachesTheAlterScript() {
        List<String> changed = new ArrayList<>(ENGINE_TIGHT);
        changed.set(SAMPLE_BY, "intHash32(id+1)");
        changed.set(ENGINE_TTL, "created+toIntervalDay(case when amount>2000 then 3 else 45 end)");
        changed.set(PROJECTION, "select status,sum(amount) GROUP BY status");

        String script = alterScript(parse(render(ENGINE_TIGHT)), parse(render(changed)));
        assertContains(script, "MODIFY SAMPLE BY intHash32(id+1)",
                "the SAMPLE BY reaching the script");
        assertContains(script,
                "MODIFY TTL created+toIntervalDay(case when amount>2000 then 3 else 45 end)",
                "the engine TTL reaching the script");
        assertContains(script, "ADD PROJECTION p_by_status (select status,sum(amount) GROUP BY status)",
                "the projection reaching the script");
    }

    /**
     * The copy trap for the projections. They are a map, so the copy carries
     * them with a {@code putAll} of its own that a parallel map of normalized
     * bodies has to be added to by hand.
     * <p>
     * The engine has a trap of its own, of a different kind - the copy has to be
     * handed an engine of its own rather than the original's instance - and the
     * assertions below could not detect it, because an engine shared between two
     * objects compares equal to itself. It is
     * {@link #copyingGivesTheTableItsOwnEngine} instead.
     */
    @Test
    void copyingCarriesTheNormalizedProjections() {
        ChTable original = parse(render(ENGINE_TIGHT));
        ChTable copy = (ChTable) original.deepCopy();

        assertConverge(copy, original);
        assertConverge(copy, parse(render(ENGINE_SPACED)));
    }

    @Test
    void aRespacedDictionaryPrimaryKeyReadsAsUnchanged() {
        assertDictionaryClauseConverges(DICTIONARY_PK);
    }

    @Test
    void aRespacedDictionaryLifetimeReadsAsUnchanged() {
        assertDictionaryClauseConverges(DICTIONARY_LIFETIME);
    }

    @Test
    void aRespacedDictionaryLayoutReadsAsUnchanged() {
        assertDictionaryClauseConverges(DICTIONARY_LAYOUT);
    }

    @Test
    void aRespacedDictionaryRangeReadsAsUnchanged() {
        assertDictionaryClauseConverges(DICTIONARY_RANGE);
    }

    @Test
    void theWholeRespacedDictionaryReadsAsUnchanged() {
        assertConverge(parseDictionary(renderDictionary(DICTIONARY_TIGHT)),
                parseDictionary(renderDictionary(DICTIONARY_SPACED)));
    }

    @Test
    void aGenuinelyChangedDictionaryClauseStillReadsAsChanged() {
        assertDictionaryClauseDiverges(DICTIONARY_PK, "id,toUInt8(status is null)");
        assertDictionaryClauseDiverges(DICTIONARY_LIFETIME, "MIN 0 MAX 200");
        assertDictionaryClauseDiverges(DICTIONARY_LAYOUT, "RANGE_HASHED(range_lookup_strategy 'min')");
        assertDictionaryClauseDiverges(DICTIONARY_RANGE, "MIN range_end MAX range_start");
    }

    /**
     * The output side of the dictionary. All four clauses are written into the
     * {@code CREATE} from the raw text, and that is the whole of the output
     * side here: {@code ChDictionary.appendAlterSQL} answers
     * {@link ObjectState#RECREATE} or {@link ObjectState#NOTHING} and never
     * writes a statement of its own.
     * <p>
     * Both scripts are asserted because each catches a different half of the
     * normalizer. The tight parse differs from its normalized twin in the case
     * of the key's reserved words; the spaced parse differs from it in
     * whitespace, which is the only difference the other three clauses can
     * carry.
     */
    @Test
    void theRawDictionaryTextStillDrivesDdlGeneration() {
        String tight = creationScript(parseDictionary(renderDictionary(DICTIONARY_TIGHT)));
        assertContains(tight, "PRIMARY KEY id,toUInt8(status is not null)",
                "the dictionary primary key");

        String spaced = creationScript(parseDictionary(renderDictionary(DICTIONARY_SPACED)));
        assertContains(spaced, "PRIMARY KEY id, toUInt8(status IS NOT NULL)",
                "the spaced dictionary primary key");
        assertContains(spaced, "LIFETIME(MIN  0  MAX  100)", "the LIFETIME");
        assertContains(spaced, "LAYOUT(RANGE_HASHED( range_lookup_strategy 'max' ))", "the LAYOUT");
        assertContains(spaced, "RANGE(MIN  range_start  MAX  range_end)", "the RANGE");
    }

    /**
     * The copy trap for the dictionary: {@code ChDictionary.getCopy} moves the
     * four clauses one at a time.
     */
    @Test
    void copyingCarriesTheNormalizedDictionaryClauses() {
        ChDictionary original = parseDictionary(renderDictionary(DICTIONARY_TIGHT));
        ChDictionary copy = (ChDictionary) original.deepCopy();

        assertConverge(copy, original);
        // and the copy must still converge with a differently spelled parse,
        // which a copy that silently dropped the normalized text could not do
        assertConverge(copy, parseDictionary(renderDictionary(DICTIONARY_SPACED)));
    }

    @Test
    void aRespacedPolicyUsingReadsAsUnchanged() {
        assertConverge(parsePolicy(POLICY_TIGHT), parsePolicy(POLICY_SPACED));
    }

    /**
     * The two sides of a policy comparison, which - unlike every other object
     * in this class - are not built by one parser class. The project side runs
     * {@code ChCreatePolicy} over a whole {@code CREATE POLICY}; the database
     * side has only the filter text of {@code system.row_policies} and
     * re-parses that alone. They agree only if both normalize, and only if a
     * bare expression normalizes the same way as the same expression inside a
     * statement.
     */
    @Test
    void theProjectFileAndTheCatalogReparseAgreeOnAPolicyUsing() {
        assertConverge(parsePolicy(POLICY_TIGHT),
                policyFromCatalog("pol1 ON default.orders", "(status IS NOT NULL AND amount > 100)"));
    }

    @Test
    void aGenuinelyChangedPolicyUsingStillReadsAsChanged() {
        assertDiverge(parsePolicy(POLICY_TIGHT), parsePolicy(POLICY_TIGHT_CHANGED));
    }

    /**
     * The output side of the policy, on both routes that write one:
     * {@code getCreationSQL} and the {@code ALTER POLICY} that
     * {@code appendAlterSQL} emits. Each carries the raw text, and all four of
     * its reserved words are lower case there while the normalized twin holds
     * them raised.
     */
    @Test
    void theRawPolicyTextIsWhatReachesTheScript() {
        assertContains(creationScript(parsePolicy(POLICY_TIGHT)),
                "USING (status is not null and amount>100)", "the policy filter");

        String script = alterScript(parsePolicy(POLICY_TIGHT), parsePolicy(POLICY_TIGHT_CHANGED));
        assertContains(script, "USING (status is not null and amount>200)",
                "the policy filter reaching the script");
    }

    /**
     * The copy trap for the policy: {@code ChPolicy.getCopy} moves the filter
     * across on its own line.
     */
    @Test
    void copyingCarriesTheNormalizedPolicyUsing() {
        ChPolicy original = parsePolicy(POLICY_TIGHT);
        ChPolicy copy = (ChPolicy) original.deepCopy();
        // a copy comes out of deepCopy unparented, and a statement's hash covers
        // the names of its parents (AbstractStatement.computeNamesHash)
        copy.setParent(original.getParent());

        assertConverge(copy, original);
        assertConverge(copy, parsePolicy(POLICY_SPACED));
    }

    @Test
    void aRespacedFunctionBodyReadsAsUnchanged() {
        assertConverge(parseFunction(FUNCTION_TIGHT), parseFunction(FUNCTION_SPACED));
    }

    /**
     * Both sides of a function comparison run {@code ChCreateFunction} over a
     * whole {@code CREATE FUNCTION} - the listener over a project file, and
     * {@code ChFunctionsReader} over {@code system.functions.create_query} -
     * but they hand the context and its stream over differently: the listener
     * has both directly, the reader has to bundle them into a {@link Pair}
     * because {@code submitChAntlrTask} extracts the context in one call and
     * consumes it in another. Both shapes are driven here.
     */
    @Test
    void theProjectFileAndTheCatalogReparseAgreeOnAFunctionBody() {
        assertConverge(parseFunction(FUNCTION_TIGHT), functionFromCatalogReparse(FUNCTION_SPACED));
    }

    @Test
    void aGenuinelyChangedFunctionBodyStillReadsAsChanged() {
        assertDiverge(parseFunction(FUNCTION_TIGHT),
                parseFunction(FUNCTION_TIGHT.replace("amount>100", "amount>200")));
    }

    /**
     * The output side of the function body. The {@code CREATE} is the only
     * route: {@code ChFunction.appendAlterSQL} answers
     * {@link ObjectState#RECREATE} or {@link ObjectState#NOTHING} and never
     * writes a statement of its own.
     */
    @Test
    void theRawFunctionBodyStillDrivesDdlGeneration() {
        assertContains(creationScript(parseFunction(FUNCTION_TIGHT)),
                "-> (status is not null and amount>100)", "the function body");
    }

    /**
     * The copy trap for the function: {@code ChFunction.getCopy} moves the body
     * across on its own line.
     */
    @Test
    void copyingCarriesTheNormalizedFunctionBody() {
        ChFunction original = parseFunction(FUNCTION_TIGHT);
        ChFunction copy = (ChFunction) original.deepCopy();

        assertConverge(copy, original);
        assertConverge(copy, parseFunction(FUNCTION_SPACED));
    }

    @Test
    void aRespacedViewQueryReadsAsUnchanged() {
        assertConverge(parseView(renderView(VIEW_QUERY_TIGHT)),
                parseView(renderView(VIEW_QUERY_SPACED)));
    }

    @Test
    void aGenuinelyChangedViewQueryStillReadsAsChanged() {
        assertDiverge(parseView(renderView(VIEW_QUERY_TIGHT)),
                parseView(renderView(VIEW_QUERY_TIGHT_CHANGED)));
    }

    @Test
    void theRawViewQueryStillDrivesDdlGeneration() {
        assertContains(creationScript(parseView(renderView(VIEW_QUERY_TIGHT))),
                "AS " + VIEW_QUERY_TIGHT, "the view query");
    }

    /**
     * The output side of the view, and the one place in this dialect where the
     * normalized text used to reach a script: {@code ChView.compareSql} wrote
     * the new view's normalized query into {@code MODIFY QUERY} while
     * {@code getCreationSQL} wrote its raw one, so one view carried the author's
     * text in a {@code CREATE} and a rewritten one in an {@code ALTER}.
     * <p>
     * The expected text is four lines with the author's own indentation, so the
     * normalized twin - one line, spaced, reserved words raised - cannot satisfy
     * this assertion by accident.
     */
    @Test
    void theRawViewQueryIsWhatReachesTheModifyQuery() {
        String script = alterScript(parseView(renderView(VIEW_QUERY_TIGHT)),
                parseView(renderView(VIEW_QUERY_TIGHT_CHANGED)));

        assertContains(script, "MODIFY QUERY " + VIEW_QUERY_TIGHT_CHANGED,
                "the view query reaching the script");
    }

    /**
     * The copy trap for the engine, on the object that offers a public route to
     * write through one: {@code ChTable.setPkExpr} hands its key straight to
     * {@link ChEngine#setPrimaryKey}. A copy that took the original's engine
     * instance rather than a copy of it would let this rewrite the key of the
     * model the copy was made from.
     * <p>
     * The original is asserted twice on purpose. Its {@code CREATE} covers the
     * raw half of the clause and is rebuilt from the fields on every call, while
     * {@link #assertConverge} against a fresh parse covers the normalized half.
     */
    @Test
    void copyingGivesTheTableItsOwnEngine() {
        ChTable original = parse(render(ENGINE_TIGHT));
        ChTable copy = (ChTable) original.deepCopy();

        copy.setPkExpr("(id)", "(id)");

        assertContains(creationScript(original), "PRIMARY KEY (id,status)",
                "the original's primary key after the copy's was rewritten");
        assertConverge(original, parse(render(ENGINE_TIGHT)));
    }

    /**
     * The same trap on the view, met from the other end: a view has no public
     * method that writes through to its engine, so what is mutated here is the
     * engine instance the original was handed - which is exactly what a caller
     * that built the view still holds - and what is asserted is the copy.
     * <p>
     * The options are asserted beside the {@code ORDER BY} because they are the
     * one mutable field nested inside an engine: the six clauses are strings,
     * and a copy that carried them all but shared the map would still let an
     * {@code addOption} on one side show up on the other.
     */
    @Test
    void copyingGivesTheViewItsOwnEngine() {
        ChEngine engine = new ChEngine("MergeTree");
        engine.setOrderBy("(id,created)", "(id, created)");
        engine.addOption("index_granularity", "8192");

        ChView original = parseView(renderView(VIEW_QUERY_TIGHT));
        original.setEngine(engine);
        ChView copy = (ChView) original.deepCopy();

        engine.setOrderBy("(id)", "(id)");
        engine.addOption("index_granularity", "4096");

        String created = creationScript(copy);
        assertContains(created, "ORDER BY (id,created)", "the copy's own ORDER BY");
        assertContains(created, "index_granularity = 8192", "the copy's own options map");
    }

    /**
     * The same trap one line above the engine: {@code ChView.getCopy} used to
     * move its column list with {@code addAll}, so a copy and its original held
     * the same {@link ChColumn} instances, while {@code ChTable.getCopy} deep
     * copies each of them.
     * <p>
     * Mutated here is the column instance the original was handed - a view
     * exposes no route to a column it holds, so the copy cannot be reached to be
     * mutated, and what a caller that built the view still holds is exactly that
     * instance. {@link #assertConverge} before the mutation is what a shared
     * instance passes trivially, which is why the two assertions that matter
     * come after one: the copy renders the column as it was, and the two views
     * have parted.
     */
    @Test
    void copyingGivesTheViewItsOwnColumns() {
        ChColumn held = new ChColumn("col3");
        held.setType("String");
        held.setDefaultType("DEFAULT");
        held.setDefaultValue("(status)", "(status)");

        ChView original = parseView(renderView(VIEW_QUERY_TIGHT));
        original.addColumn(held);
        ChView copy = (ChView) original.deepCopy();

        assertConverge(copy, original);

        held.setType("UInt64");
        held.setDefaultValue("(id)", "(id)");

        assertContains(creationScript(copy), "`col3` String DEFAULT (status)",
                "the copy's own column");
        assertDiverge(copy, original);
    }

    /**
     * A view's engine is read by both {@code ChView.computeHash} and
     * {@code ChView.compare}, so writing one has to invalidate the cached hash
     * the way every other setter of the class does.
     * {@code ChTable.setEngine} has done it since it was written;
     * {@code ChView.setEngine} did not.
     * <p>
     * Latent until now - an engine is only ever written by a parser, during
     * load, before anything asks for a hash - but a hash is cached on first use
     * and {@code resetHash} is the only thing that clears it, so a later caller
     * would have had no way to make the view answer for its own state.
     */
    @Test
    void replacingTheEngineOfAViewInvalidatesItsHash() {
        ChView view = parseView(renderView(VIEW_QUERY_TIGHT));
        ChEngine first = new ChEngine("MergeTree");
        first.setOrderBy("(id)", "(id)");
        view.setEngine(first);
        int before = view.hashCode();

        ChEngine second = new ChEngine("MergeTree");
        second.setOrderBy("(created)", "(created)");
        view.setEngine(second);

        assertNotEquals(before, view.hashCode(),
                "a view whose engine has been replaced must not answer its old hash");
    }

    private static void assertDictionaryClauseConverges(int clause) {
        assertConverge(parseDictionary(renderDictionary(DICTIONARY_TIGHT)),
                parseDictionary(renderDictionary(
                        dictionaryClauseChangedTo(clause, DICTIONARY_SPACED.get(clause)))));
    }

    private static void assertDictionaryClauseDiverges(int clause, String changed) {
        assertDiverge(parseDictionary(renderDictionary(DICTIONARY_TIGHT)),
                parseDictionary(renderDictionary(dictionaryClauseChangedTo(clause, changed))));
    }

    private static List<String> dictionaryClauseChangedTo(int clause, String text) {
        List<String> clauses = new ArrayList<>(DICTIONARY_TIGHT);
        clauses.set(clause, text);
        return clauses;
    }

    private static String renderDictionary(List<String> clauses) {
        return DICTIONARY_TEMPLATE.formatted(clauses.toArray());
    }

    private static void assertClauseConverges(int clause) {
        assertConverge(parse(render(ENGINE_TIGHT)),
                parse(render(clauseChangedTo(clause, ENGINE_SPACED.get(clause)))));
    }

    private static void assertClauseDiverges(int clause, String changed) {
        assertDiverge(parse(render(ENGINE_TIGHT)), parse(render(clauseChangedTo(clause, changed))));
    }

    private static List<String> clauseChangedTo(int clause, String text) {
        List<String> clauses = new ArrayList<>(ENGINE_TIGHT);
        clauses.set(clause, text);
        return clauses;
    }

    private static String render(List<String> clauses) {
        return ENGINE_TEMPLATE.formatted(clauses.toArray());
    }

    private static String renderView(String query) {
        return VIEW_TEMPLATE.formatted(query);
    }

    private static String alterScript(ChView old, ChView changed) {
        SQLScript script = new SQLScript(new CoreSettings(), ";");
        ObjectState state = old.appendAlterSQL(changed, script);
        assertEquals(ObjectState.ALTER, state,
                () -> "the two views must differ only in alterable parts, got " + state);
        return script.getFullScript();
    }

    private static String alterScript(ChTable old, ChTable changed) {
        SQLScript script = new SQLScript(new CoreSettings(), ";");
        ObjectState state = old.appendAlterSQL(changed, script);
        assertEquals(ObjectState.ALTER, state,
                () -> "the two tables must differ only in alterable parts, got " + state);
        return script.getFullScript();
    }

    private static String alterScript(ChPolicy old, ChPolicy changed) {
        SQLScript script = new SQLScript(new CoreSettings(), ";");
        ObjectState state = old.appendAlterSQL(changed, script);
        assertEquals(ObjectState.ALTER, state,
                () -> "the two policies must differ only in alterable parts, got " + state);
        return script.getFullScript();
    }

    private static String alterScript(ChColumn old, ChColumn changed) {
        SQLScript script = new SQLScript(new CoreSettings(), ";");
        old.appendAlterSQL(changed, script);
        return script.getFullScript();
    }

    private static String creationScript(AbstractStatement statement) {
        SQLScript script = new SQLScript(new CoreSettings(), ";");
        statement.getCreationSQL(script);
        return script.getFullScript();
    }

    private static void assertContains(String actual, String expected, String what) {
        assertTrue(actual.contains(expected),
                () -> what + " must be written as authored, expected to find:\n" + expected
                        + "\nin:\n" + actual);
    }

    /**
     * The copy trap. Every one of these three classes has a copy method that
     * moves fields one at a time, so a forgotten normalized field is lost on
     * copy with no other test noticing.
     */
    @Test
    void copyingCarriesTheNormalizedExpressions() {
        ChTable original = parse(TIGHT_LOWER_CASE);
        ChTable copy = (ChTable) original.deepCopy();

        assertConverge(column(copy, "is_active"), column(original, "is_active"));
        assertConverge(column(copy, "payload"), column(original, "payload"));
        assertConverge(constraint(copy, "c_status_known"), constraint(original, "c_status_known"));
        assertConverge(index(copy, "ix_status"), index(original, "ix_status"));

        // and the copy must still converge with a differently spelled parse,
        // which a copy that silently dropped the normalized text could not do
        ChTable respaced = parse(SPACED_UPPER_CASE);
        assertConverge(column(copy, "is_active"), column(respaced, "is_active"));
        assertConverge(column(copy, "payload"), column(respaced, "payload"));
        assertConverge(constraint(copy, "c_status_known"), constraint(respaced, "c_status_known"));
        assertConverge(index(copy, "ix_status"), index(respaced, "ix_status"));
    }

    @Test
    void aGenuinelyChangedColumnDefaultStillReadsAsChanged() {
        assertDiverge(column(parse(TIGHT_LOWER_CASE), "is_active"),
                column(parse(TIGHT_LOWER_CASE.replace("id>0 and status is not null",
                        "id>1 and status is not null")), "is_active"));
    }

    @Test
    void aGenuinelyChangedColumnTtlStillReadsAsChanged() {
        assertDiverge(column(parse(TIGHT_LOWER_CASE), "payload"),
                column(parse(TIGHT_LOWER_CASE.replace("else 30 end", "else 31 end")), "payload"));
    }

    @Test
    void aGenuinelyChangedConstraintExpressionStillReadsAsChanged() {
        assertDiverge(constraint(parse(TIGHT_LOWER_CASE), "c_status_known"),
                constraint(parse(TIGHT_LOWER_CASE.replace("status!=''", "status!='x'")),
                        "c_status_known"));
    }

    @Test
    void aGenuinelyChangedIndexExpressionStillReadsAsChanged() {
        assertDiverge(index(parse(TIGHT_LOWER_CASE), "ix_status"),
                index(parse(TIGHT_LOWER_CASE.replace("id>0 and id<100", "id>0 and id<200")),
                        "ix_status"));
    }

    private static void assertConverge(AbstractStatement a, AbstractStatement b) {
        assertTrue(a.compare(b), () -> "expected the two parses of " + a.getName()
                + " to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged objects must hash the same");
    }

    private static void assertDiverge(AbstractStatement a, AbstractStatement b) {
        assertFalse(a.compare(b), () -> "a genuinely different expression in " + a.getName()
                + " must still compare as changed");
        assertFalse(b.compare(a), "and the other way round too");
    }

    private static ChColumn column(ChTable table, String name) {
        ChColumn column = table.getColumn(name);
        assertTrue(column != null, () -> "no column " + name + " was parsed");
        return column;
    }

    private static ChConstraint constraint(ChTable table, String name) {
        return child(table, name, ChConstraint.class);
    }

    private static ChIndex index(ChTable table, String name) {
        return child(table, name, ChIndex.class);
    }

    private static <T extends AbstractStatement> T child(ChTable table, String name, Class<T> type) {
        return table.getChildren()
                .filter(type::isInstance)
                .filter(st -> name.equals(st.getName()))
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + ' ' + name
                        + " was parsed"));
    }

    /**
     * Mirrors both call sites at once: {@code ChCustomParserListener.create} and
     * {@code ChRelationsReader.getTable} each hand {@link ChCreateTable} a
     * context together with the token stream that produced it.
     */
    private static ChTable parse(String sql) {
        List<Object> errors = new ArrayList<>();
        CHParser parser = ChParserUtils.createParser(sql, "ch expression normalization test", errors);
        var ctx = parser.ch_file().query(0).stmt().ddl_stmt().create_stmt().create_table_stmt();
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        var stream = (CommonTokenStream) parser.getTokenStream();
        var table = new ChTable("orders");
        new ChCreateTable(ctx, new ChDatabase(), stream, new CoreSettings()).parseObject(table);
        return table;
    }

    /**
     * Mirrors both call sites at once, as {@link #parse} does for a table:
     * {@code ChCustomParserListener.create} and
     * {@code ChRelationsReader.getDictionary} each hand
     * {@link ChCreateDictionary} a context together with the token stream that
     * produced it.
     */
    private static ChDictionary parseDictionary(String sql) {
        List<Object> errors = new ArrayList<>();
        CHParser parser = ChParserUtils.createParser(sql, "ch dictionary normalization test", errors);
        var ctx = parser.ch_file().query(0).stmt().ddl_stmt().create_stmt().create_dictinary_stmt();
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        var stream = (CommonTokenStream) parser.getTokenStream();
        var dictionary = new ChDictionary("zones");
        new ChCreateDictionary(ctx, new ChDatabase(), stream, new CoreSettings()).parseObject(dictionary);
        return dictionary;
    }

    /**
     * Mirrors {@code ChCustomParserListener.create}, the project-file side of a
     * policy. {@link ChCreatePolicy} builds the policy itself and adds it to the
     * database, so it is fished back out of there.
     */
    private static ChPolicy parsePolicy(String sql) {
        List<Object> errors = new ArrayList<>();
        CHParser parser = ChParserUtils.createParser(sql, "ch policy normalization test", errors);
        var ctx = parser.ch_file().query(0).stmt().ddl_stmt().create_stmt().create_policy_stmt();
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        var stream = (CommonTokenStream) parser.getTokenStream();
        var db = new ChDatabase();
        new ChCreatePolicy(ctx, db, stream, new CoreSettings()).parseObject();
        return db.getChildren()
                .filter(ChPolicy.class::isInstance)
                .map(ChPolicy.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no policy was parsed"));
    }

    /**
     * Takes the shape {@code ChPoliciesReader.processResult} takes - the catalog
     * hands over the filter text of {@code system.row_policies} on its own, so
     * it is re-parsed as a bare expression, {@code CHParser::expr_eof} rather
     * than a whole {@code CREATE POLICY}, and the stream travels with the
     * context in a {@link Pair} because {@code submitChAntlrTask} extracts the
     * context in one call and consumes it in another - and then calls the same
     * {@link ChCreatePolicy#setUsingWithAnalyze} the reader calls.
     * <p>
     * What this proves is that a bare expression normalizes to what the same
     * expression inside a statement normalizes to. That the reader reaches this
     * method at all, and what happens when its parse fails, is
     * {@code ChPoliciesReaderUsingTest} - it drives the reader itself.
     */
    private static ChPolicy policyFromCatalog(String name, String using) {
        List<Object> errors = new ArrayList<>();
        CHParser parser = ChParserUtils.createParser(using, "ch policy catalog-reparse test", errors);
        Pair<Expr_eofContext, CommonTokenStream> pair = new Pair<>(
                parser.expr_eof(), (CommonTokenStream) parser.getTokenStream());
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        ChPolicy policy = new ChPolicy(name);
        policy.setPermissive(false);
        // the last line of the reader, and not decoration: a statement's hash
        // covers the names of its parents (AbstractStatement.computeNamesHash),
        // so an unparented policy could not hash equal to a parsed one
        ChDatabase db = new ChDatabase();
        db.addChild(policy);
        ChCreatePolicy.setUsingWithAnalyze(policy, using, pair.getFirst().expr(), pair.getSecond(),
                db, "ch policy catalog-reparse test");
        return policy;
    }

    /**
     * Mirrors {@code ChCustomParserListener.create}, the project-file side of a
     * view.
     * <p>
     * The two sides of a view are the one pair in this class that do not run the
     * same call: {@code ChRelationsReader.getView} passes {@code true} for
     * {@code needFormatSql} and the listener passes {@code false}, so the
     * database side re-lays-out its own raw text through {@code ChFormatter}
     * before storing it. The normalized half is taken from the parse tree either
     * way and is unaffected. The project-file side is the one driven here,
     * because it is the side whose spelling a migration has to carry.
     */
    private static ChView parseView(String sql) {
        List<Object> errors = new ArrayList<>();
        CHParser parser = ChParserUtils.createParser(sql, "ch view normalization test", errors);
        var ctx = parser.ch_file().query(0).stmt().ddl_stmt().create_stmt().create_view_stmt();
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        var stream = (CommonTokenStream) parser.getTokenStream();
        var view = new ChView("mv");
        new ChCreateView(ctx, new ChDatabase(), stream, new CoreSettings()).parseObject(view, false);
        return view;
    }

    /**
     * Mirrors {@code ChCustomParserListener.create}, the project-file side of a
     * function: the context and its stream are both at hand.
     */
    private static ChFunction parseFunction(String sql) {
        List<Object> errors = new ArrayList<>();
        CHParser parser = ChParserUtils.createParser(sql, "ch function normalization test", errors);
        var ctx = parser.ch_file().query(0).stmt().ddl_stmt().create_stmt().create_function_stmt();
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        var stream = (CommonTokenStream) parser.getTokenStream();
        var function = new ChFunction("is_open");
        new ChCreateFunction(ctx, new ChDatabase(), stream, new CoreSettings()).parseObject(function);
        return function;
    }

    /**
     * Mirrors {@code ChFunctionsReader.processResult}: the same
     * {@link ChCreateFunction#parseObject(ChFunction)} the project side runs,
     * reached with the context and the stream bundled into a {@link Pair}.
     */
    private static ChFunction functionFromCatalogReparse(String sql) {
        List<Object> errors = new ArrayList<>();
        CHParser parser = ChParserUtils.createParser(sql, "ch function catalog-reparse test", errors);
        Pair<Create_function_stmtContext, CommonTokenStream> pair = new Pair<>(
                parser.ch_file().query(0).stmt().ddl_stmt().create_stmt().create_function_stmt(),
                (CommonTokenStream) parser.getTokenStream());
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        var function = new ChFunction("is_open");
        new ChCreateFunction(pair.getFirst(), new ChDatabase(), pair.getSecond(), new CoreSettings())
                .parseObject(function);
        return function;
    }
}
