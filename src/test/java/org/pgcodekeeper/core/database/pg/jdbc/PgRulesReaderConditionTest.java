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
import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.schema.EventType;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Create_rewrite_statementContext;
import org.pgcodekeeper.core.database.pg.parser.statement.PgCreateRule;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgRule;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.Pair;

/**
 * A rule condition reaches {@link PgCreateRule#setConditionAndAddCommands} from
 * two independent call sites: the project-side {@code CREATE RULE} parser
 * ({@link PgCreateRule#parseObject()}, which already holds the context and its
 * stream as fields), and {@link PgRulesReader}, which re-parses a
 * {@code pg_get_ruledef()} result and bundles the resulting context and stream
 * into a {@link Pair} because {@code submitAntlrTask} extracts the context in
 * one call and consumes it in another, on the deferred task queue.
 * <p>
 * Most of the tests below drive both shapes directly through the shared parsing
 * method. That covers the shared method, and nothing else: a mirrored call site
 * is a copy of the reader, not the reader, so it says nothing about the reader's
 * own line - measured, not assumed, with the stream there replaced by
 * {@code null} the mirrored tests stayed green. Only
 * {@link #theReaderItselfNormalizesTheConditionItReparses()} runs
 * {@link PgRulesReader#processResult} over a catalog row and finishes the
 * loader's queue, which is the only way to reach that line and the deferred
 * finalizer behind it.
 * <p>
 * Every condition below is written so that it <i>differs from its own
 * normalized form</i>. A fixture already spelled canonically would make the raw
 * and the normalized halves byte-identical, every assertion would pass whether
 * or not normalization happened at all, and no mutation of the production code
 * could redden the test. {@link #theFixturesDifferFromTheirOwnNormalizedForm()}
 * asserts that property instead of assuming it.
 */
class PgRulesReaderConditionTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "orders";
    private static final String RULE_NAME = "r_skip";

    /**
     * How a project file might hold the condition: tight around {@code >} and
     * lower case throughout.
     */
    private static final String TIGHT = "(old.id>0 and new.status is not null)";

    /**
     * The same condition as another branch - or a {@code pg_get_ruledef()}
     * result - might spell it. {@code is} keeps its case in both spellings on
     * purpose: {@code IS} (token 404) sits outside the folded range
     * {@code SQLLexer.ALL..WITH}, so unlike {@code AND}/{@code NOT}/{@code NULL}
     * it is not case-normalized, and holding it constant isolates this test from
     * that unrelated, pre-existing limit of the fold.
     */
    private static final String SPACED = "( old.id > 0 AND new.status is NOT NULL )";

    /**
     * What the normalizer makes of both of the above. Pinned as a literal
     * because the normalized field deliberately has no getter: a rule built by
     * hand with this text is the only way to observe from outside that the
     * parser stored exactly it.
     */
    private static final String NORMALIZED = "(old.id > 0 AND new.status is NOT NULL)";

    @Test
    void theFixturesDifferFromTheirOwnNormalizedForm() {
        assertNotEquals(TIGHT, NORMALIZED,
                "a canonically written fixture would make this whole test decorative");
        assertNotEquals(SPACED, NORMALIZED, "same for the second spelling");
        assertEquals(NORMALIZED, normalize(TIGHT),
                "the pinned normalized text must be what the normalizer actually produces");
        assertEquals(NORMALIZED, normalize(SPACED), "and both spellings must reach it");
    }

    @Test
    void projectFileAndCatalogReparseAgreeOnARespacedCondition() {
        PgRule fromProjectFile = parseAsProjectFile(rule(TIGHT));
        PgRule fromCatalogReparse = parseAsCatalogReparse(rule(SPACED));

        assertConverge(fromProjectFile, fromCatalogReparse);
    }

    /**
     * The normalized text the parser stored, observed without a getter: a rule
     * carrying the pinned normalized form compares equal to a parsed one. Were
     * the parser to hand the raw text over as the normalized one, this rule
     * would hold {@code NORMALIZED} while the parsed one held {@code TIGHT}, and
     * the two would part.
     */
    @Test
    void theParserStoresTheNormalizedFormAndNotTheRawOne() {
        PgRule handBuilt = new PgRule(RULE_NAME);
        handBuilt.setCondition(TIGHT, NORMALIZED);

        assertConverge(handBuilt, parseAsProjectFile(rule(TIGHT)));
        assertConverge(handBuilt, parseAsCatalogReparse(rule(SPACED)));
    }

    @Test
    void aGenuinelyDifferentConditionStillComparesAsChanged() {
        PgRule original = parseAsProjectFile(rule("(new.status='active')"));
        PgRule changed = parseAsProjectFile(rule("(new.status='inactive')"));

        assertFalse(original.compare(changed),
                "a genuinely different condition must still compare as changed");
        assertFalse(changed.compare(original), "and the other way round too");
    }

    /**
     * The copy trap. {@link PgRule#getCopy()} moves fields one at a time, so a
     * forgotten normalized half is lost in silence and nothing else in the tree
     * notices - a copy would simply start reading as changed against its own
     * original.
     */
    @Test
    void copyingCarriesTheNormalizedCondition() {
        PgRule original = parented(parseAsProjectFile(rule(TIGHT)));
        PgRule copy = (PgRule) original.deepCopy();
        // a copy comes out of deepCopy unparented, and a statement's hash covers
        // the names of its parents (AbstractStatement.computeNamesHash)
        copy.setParent(original.getParent());

        assertConverge(copy, original);
        // and the copy must still converge with a differently spelled parse,
        // which a copy that silently dropped the normalized text could not do
        assertConverge(copy, parented(parseAsCatalogReparse(rule(SPACED))));
    }

    /**
     * The output side: the script carries the author's own spelling, never the
     * normalized one. The normalized text exists for comparison only; letting it
     * reach the DDL would rewrite user expressions into migrations, and the
     * fixture corpus does not catch that - a compare fixture is asserted empty,
     * so a rewritten {@code WHERE} inside a script that is never generated stays
     * invisible.
     */
    @Test
    void theAuthorsOwnSpellingIsWhatReachesTheScript() {
        String ddl = creationScript(parented(parseAsProjectFile(rule(TIGHT))));

        assertTrue(ddl.contains("WHERE " + TIGHT), () -> "the author's spelling must reach the script, got:\n" + ddl);
        assertFalse(ddl.contains(NORMALIZED), () -> "the normalized form must never reach the script, got:\n" + ddl);
    }

    /**
     * The reader itself, over a catalog row - the one test here that names
     * {@link PgRulesReader} by running it rather than by mirroring it.
     * <p>
     * The stream the reader hands to the shared method is reachable from nowhere
     * else: {@code getRule} is not exercised by any other test, and replacing
     * that stream with {@code null} left every mirrored test green. In a running
     * program that replacement is not a quiet defect but a failed database read
     * - {@code normalizeWhitespaceUnquoted} dereferences the stream, and the NPE
     * comes out of the finalizer - while the quiet variant, a parse written into
     * a discarded object, was equally uncovered. Both die here: this drives
     * {@code processResult} over one mocked row, finishes the loader's parse
     * queue exactly as a real load does, and compares what came out with a rule
     * built by hand.
     */
    @Test
    void theReaderItselfNormalizesTheConditionItReparses() throws Exception {
        var settings = new CoreSettings();
        PgRule loaded = read(rule(TIGHT), settings);

        assertTrue(settings.getErrors().isEmpty(),
                () -> "the catalog definition must parse, otherwise the finalizer never runs: " + settings.getErrors());

        PgRule handBuilt = new PgRule(RULE_NAME);
        // event and instead come from the catalog columns, not from the parse,
        // so the twin has to carry the values those columns stand for
        handBuilt.setEvent(EventType.UPDATE);
        handBuilt.setInstead(true);
        handBuilt.setCondition(TIGHT, NORMALIZED);
        // the reader hangs the rule off the table, and a statement's hash covers
        // the names of its parents (AbstractStatement.computeNamesHash), so the
        // twin has to hang off the same chain
        assertConverge(parented(handBuilt), loaded);

        // and the raw half survives the reader path too
        String ddl = creationScript(loaded);
        assertTrue(ddl.contains("WHERE " + TIGHT), () -> "the catalog's own spelling must reach the script, got:\n" + ddl);
    }

    /**
     * Runs {@link PgRulesReader#processResult} over one mocked catalog row and
     * finishes the loader's parse queue, which runs the deferred finalizer
     * exactly as a real load does. Returns the rule the reader hung off the
     * table.
     */
    private static PgRule read(String ruleString, CoreSettings settings) throws Exception {
        ResultSet res = mock(ResultSet.class);
        when(res.getString("relname")).thenReturn(TABLE_NAME);
        when(res.getString("rulename")).thenReturn(RULE_NAME);
        when(res.getString("ev_type")).thenReturn("2");
        when(res.getBoolean("is_instead")).thenReturn(true);
        when(res.getString("ev_enabled")).thenReturn("O");
        when(res.getString("rule_string")).thenReturn(ruleString);

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);

        try (TestLoader loader = new TestLoader(settings)) {
            new PgRulesReader(loader).processResult(res, schema);
            loader.drain();
        }

        return table.getChildren()
                .filter(PgRule.class::isInstance)
                .map(PgRule.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reader added no rule"));
    }

    /**
     * {@code finishLoaders} is protected on {@code AbstractLoader}, so a subclass
     * reaches it without reflection. Nothing here queries - the reader is handed
     * its row directly - so the connector exists only to satisfy the
     * constructor.
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

    private static void assertConverge(PgRule a, PgRule b) {
        assertTrue(a.compare(b), "expected the two rules to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged rules must hash the same");
    }

    private static String rule(String condition) {
        // DO INSTEAD NOTHING on purpose: PgRule.commands is out of this scope
        // and is still compared raw, so any action body would have to be held
        // byte-identical between the two spellings anyway.
        return "CREATE RULE " + RULE_NAME + " AS ON UPDATE TO " + SCHEMA_NAME + '.' + TABLE_NAME
                + " WHERE " + condition + " DO INSTEAD NOTHING;";
    }

    /**
     * Mirrors the parser's own last line: {@code PgCreateRule.parseObject} hands
     * the rule to the table it is declared on. Both sides of a comparison must
     * be parented the same way, because a statement's hash covers the names of
     * everything above it.
     */
    private static PgRule parented(PgRule rule) {
        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);
        table.addChild(rule);
        return rule;
    }

    private static String creationScript(PgRule rule) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, rule.getSeparator());
        rule.getCreationSQL(script);
        return script.getFullScript();
    }

    private static String normalize(String condition) {
        List<Object> errors = new ArrayList<>();
        SQLParser parser = PgParserUtils.createSqlParser(rule(condition), "rule condition normalizer probe", errors);
        Create_rewrite_statementContext ctx = statement(parser);
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        return PgParserUtils.normalizeWhitespaceUnquoted(ctx.vex(), (CommonTokenStream) parser.getTokenStream());
    }

    /**
     * Mirrors {@link PgCreateRule#parseObject()}: the context and its stream are
     * both available directly, no intermediate {@link Pair} is needed to carry
     * them across a deferred task boundary.
     */
    private static PgRule parseAsProjectFile(String sql) {
        List<Object> errors = new ArrayList<>();
        SQLParser parser = PgParserUtils.createSqlParser(sql, "rule condition project-file test", errors);
        Create_rewrite_statementContext ctx = statement(parser);
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        var rule = new PgRule(RULE_NAME);
        PgCreateRule.setConditionAndAddCommands(ctx, rule, new PgDatabase(), "test",
                (CommonTokenStream) parser.getTokenStream(), new CoreSettings());
        return rule;
    }

    /**
     * Mirrors {@link PgRulesReader}'s {@code submitAntlrTask} call: the
     * context-extraction function and the processing callback run apart, so the
     * context and the stream that produced it travel together in a
     * {@link Pair}.
     */
    private static PgRule parseAsCatalogReparse(String sql) {
        List<Object> errors = new ArrayList<>();
        SQLParser parser = PgParserUtils.createSqlParser(sql, "rule condition catalog-reparse test", errors);
        Pair<Create_rewrite_statementContext, CommonTokenStream> pair = new Pair<>(
                statement(parser), (CommonTokenStream) parser.getTokenStream());
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        var rule = new PgRule(RULE_NAME);
        PgCreateRule.setConditionAndAddCommands(pair.getFirst(), rule, new PgDatabase(), "test",
                pair.getSecond(), new CoreSettings());
        return rule;
    }

    private static Create_rewrite_statementContext statement(SQLParser parser) {
        return parser.sql().statement(0).schema_statement().schema_create().create_rewrite_statement();
    }
}
