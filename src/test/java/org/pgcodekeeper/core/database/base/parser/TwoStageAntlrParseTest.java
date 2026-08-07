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
package org.pgcodekeeper.core.database.base.parser;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.ANTLRErrorStrategy;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.monitor.NullMonitor;

/**
 * Verifies the two-stage (SLL with bail, then full LL) parse strategy: valid
 * input must parse in the cheap SLL stage without touching the error list,
 * invalid input must fall back and reproduce the exact single-stage LL
 * diagnostics, and cooperative cancellation must never be swallowed.
 */
@Isolated("asserts on global two-stage parse counters")
class TwoStageAntlrParseTest {

    private static final String VALID_SQL = """
            CREATE TABLE public.t1 (id integer PRIMARY KEY, name text DEFAULT 'x');
            CREATE VIEW public.v1 AS SELECT id, name FROM public.t1 WHERE id > 0;
            CREATE OR REPLACE FUNCTION public.f1() RETURNS integer LANGUAGE sql
                AS $$ SELECT count(*)::integer FROM public.t1 $$;
            """;

    private static final String INVALID_TABLE_SQL = """
            CREATE TABLE public.bad_one (
                id integer,
                value text DEFAULT
            );
            """;

    private static final String INVALID_VIEW_SQL = """
            CREATE VIEW public.bad_view AS
            SELECT * FRUM public.bad_one;
            """;

    private static final String STRAY_PAREN_SQL = "SELECT )";

    private static final String INVALID_PLPGSQL = """
            BEGIN
                SELECT val INTO target FRUM some_table;
            END
            """;

    @Test
    void testValidSqlParsesInSllStageWithoutFallback() {
        long successBefore = TwoStageAntlrParse.getSllSuccessCount();
        long fallbackBefore = TwoStageAntlrParse.getLlFallbackCount();
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(VALID_SQL, "valid.sql", errors);

        var tree = TwoStageAntlrParse.parse(parser, SQLParser::sql);

        Assertions.assertEquals(successBefore + 1, TwoStageAntlrParse.getSllSuccessCount());
        Assertions.assertEquals(fallbackBefore, TwoStageAntlrParse.getLlFallbackCount());
        Assertions.assertTrue(errors.isEmpty(), errors::toString);
        Assertions.assertEquals(singleStageTree(VALID_SQL, SQLParser::sql),
                tree.toStringTree(parser));
    }

    @Test
    void testInvalidSqlFallsBackAndMatchesSingleStageDiagnostics() {
        long fallbackBefore = TwoStageAntlrParse.getLlFallbackCount();
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(INVALID_TABLE_SQL, "bad.sql", errors);

        var tree = TwoStageAntlrParse.parse(parser, SQLParser::sql);

        Assertions.assertEquals(fallbackBefore + 1, TwoStageAntlrParse.getLlFallbackCount());
        // one diagnostic only: the bailing SLL stage must not leak duplicates
        Assertions.assertEquals(1, errors.size(), errors::toString);
        assertError(errors.get(0), 4, 0, 69, 69, ")",
                "mismatched input ')' expecting Operator, Number, Identifier, String");
        Assertions.assertEquals(singleStageErrors(INVALID_TABLE_SQL, SQLParser::sql),
                describeErrors(errors));
        Assertions.assertEquals(singleStageTree(INVALID_TABLE_SQL, SQLParser::sql),
                tree.toStringTree(parser));
    }

    @Test
    void testInvalidViewFallbackMatchesSingleStageDiagnostics() {
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(INVALID_VIEW_SQL, "bad_view.sql", errors);

        TwoStageAntlrParse.parse(parser, SQLParser::sql);

        Assertions.assertEquals(1, errors.size(), errors::toString);
        assertError(errors.get(0), 2, 14, 45, 50, "public",
                "mismatched input 'public' expecting EOF, ';'");
        Assertions.assertEquals(singleStageErrors(INVALID_VIEW_SQL, SQLParser::sql),
                describeErrors(errors));
    }

    @Test
    void testStrayTokenFallbackMatchesSingleStageDiagnostics() {
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(STRAY_PAREN_SQL, "stray.sql", errors);

        TwoStageAntlrParse.parse(parser, SQLParser::sql);

        Assertions.assertEquals(1, errors.size(), errors::toString);
        assertError(errors.get(0), 1, 7, 7, 7, ")",
                "extraneous input ')' expecting EOF, ';'");
        Assertions.assertEquals(singleStageErrors(STRAY_PAREN_SQL, SQLParser::sql),
                describeErrors(errors));
    }

    @Test
    void testPlpgsqlFallbackKeepsIntoTokensHidden() {
        long fallbackBefore = TwoStageAntlrParse.getLlFallbackCount();
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(INVALID_PLPGSQL, "bad_body", errors);
        PgParserUtils.removeIntoStatements(parser);

        var tree = TwoStageAntlrParse.parse(parser, SQLParser::plpgsql_function);

        Assertions.assertEquals(fallbackBefore + 1, TwoStageAntlrParse.getLlFallbackCount());
        Assertions.assertEquals(1, errors.size(), errors::toString);
        // INTO channel modifications from the SLL stage must survive parser.reset()
        assertError(errors.get(0), 2, 32, 38, 47, "some_table",
                "extraneous input 'some_table' expecting ';'");
        Assertions.assertEquals(
                singleStageTree(INVALID_PLPGSQL, p -> {
                    PgParserUtils.removeIntoStatements(p);
                    return p.plpgsql_function();
                }),
                tree.toStringTree(parser));
    }

    @Test
    void testValidPlpgsqlOnIsolatedParserUsesSllStage() {
        long successBefore = TwoStageAntlrParse.getSllSuccessCount();
        long fallbackBefore = TwoStageAntlrParse.getLlFallbackCount();
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createBoundedSqlParser(
                "BEGIN IF true THEN PERFORM 1; END IF; END", "isolated body", errors, 0, 0, 0);

        TwoStageAntlrParse.parse(parser, SQLParser::plpgsql_function);

        Assertions.assertEquals(successBefore + 1, TwoStageAntlrParse.getSllSuccessCount());
        Assertions.assertEquals(fallbackBefore, TwoStageAntlrParse.getLlFallbackCount());
        Assertions.assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void testInvalidSqlOnBoundedParserFallsBackWithSameDiagnostics() {
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createBoundedSqlParser(
                INVALID_TABLE_SQL, "bounded bad body", errors, 0, 0, 0);

        TwoStageAntlrParse.parse(parser, SQLParser::sql);

        Assertions.assertEquals(singleStageErrors(INVALID_TABLE_SQL, SQLParser::sql),
                describeErrors(errors));
    }

    @Test
    void testParserConfigurationRestoredAfterSllSuccess() {
        var parser = PgParserUtils.createSqlParser(VALID_SQL, "valid.sql", new ArrayList<>());
        List<ANTLRErrorListener> listenersBefore = List.copyOf(parser.getErrorListeners());
        ANTLRErrorStrategy handlerBefore = parser.getErrorHandler();

        TwoStageAntlrParse.parse(parser, SQLParser::sql);

        Assertions.assertEquals(listenersBefore, parser.getErrorListeners());
        Assertions.assertSame(handlerBefore, parser.getErrorHandler());
        Assertions.assertEquals(PredictionMode.LL, parser.getInterpreter().getPredictionMode());
    }

    @Test
    void testParserConfigurationRestoredAfterFallback() {
        var parser = PgParserUtils.createSqlParser(STRAY_PAREN_SQL, "stray.sql", new ArrayList<>());
        List<ANTLRErrorListener> listenersBefore = List.copyOf(parser.getErrorListeners());
        ANTLRErrorStrategy handlerBefore = parser.getErrorHandler();

        TwoStageAntlrParse.parse(parser, SQLParser::sql);

        Assertions.assertEquals(listenersBefore, parser.getErrorListeners());
        Assertions.assertSame(handlerBefore, parser.getErrorHandler());
        Assertions.assertEquals(PredictionMode.LL, parser.getInterpreter().getPredictionMode());
    }

    @Test
    void testParseListenersSurviveBothStages() {
        var parser = PgParserUtils.createSqlParser(STRAY_PAREN_SQL, "stray.sql", new ArrayList<>());
        var listener = new CountingParseTreeListener();
        parser.addParseListener(listener);

        TwoStageAntlrParse.parse(parser, SQLParser::sql);

        Assertions.assertTrue(parser.getParseListeners().contains(listener),
                "parse listener detached by the two-stage strategy");
        Assertions.assertTrue(listener.exitedRules > 0);
    }

    @Test
    void testMonitorCancellationDuringSllStagePropagates() {
        long fallbackBefore = TwoStageAntlrParse.getLlFallbackCount();
        var errors = new ArrayList<>();
        var monitor = new NullMonitor();
        monitor.setCancelled(true);
        var parser = PgParserUtils.createBoundedSqlParser(
                VALID_SQL, "cancelled body", errors, 0, 0, 0, monitor);

        Assertions.assertThrows(MonitorCancelledRuntimeException.class,
                () -> TwoStageAntlrParse.parse(parser, SQLParser::sql));

        Assertions.assertEquals(fallbackBefore, TwoStageAntlrParse.getLlFallbackCount());
        Assertions.assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void testWrappedCancellationCauseIsNotTreatedAsSyntaxFallback() {
        long fallbackBefore = TwoStageAntlrParse.getLlFallbackCount();
        var cancellation = new MonitorCancelledRuntimeException();
        var parser = PgParserUtils.createSqlParser(VALID_SQL, "valid.sql", new ArrayList<>());

        RuntimeException thrown = Assertions.assertThrows(
                MonitorCancelledRuntimeException.class,
                () -> TwoStageAntlrParse.parse(parser, p -> {
                    throw new ParseCancellationException(cancellation);
                }));

        Assertions.assertSame(cancellation, thrown);
        Assertions.assertEquals(fallbackBefore, TwoStageAntlrParse.getLlFallbackCount());
    }

    private static String singleStageTree(String sql,
            java.util.function.Function<SQLParser, ParserRuleContext> entryRule) {
        var parser = PgParserUtils.createSqlParser(sql, "single-stage reference", new ArrayList<>());
        return entryRule.apply(parser).toStringTree(parser);
    }

    private static List<String> singleStageErrors(String sql,
            java.util.function.Function<SQLParser, ParserRuleContext> entryRule) {
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(sql, "single-stage reference", errors);
        entryRule.apply(parser);
        return describeErrors(errors);
    }

    private static List<String> describeErrors(List<Object> errors) {
        return errors.stream()
                .map(AntlrError.class::cast)
                .map(error -> error.getLineNumber() + ":" + error.getCharPositionInLine()
                        + ":" + error.getStart() + ":" + error.getStop()
                        + ":" + error.getText() + ":" + error.getMsg())
                .toList();
    }

    private static void assertError(Object error, int line, int charPositionInLine,
            int start, int stop, String text, String msg) {
        AntlrError antlrError = Assertions.assertInstanceOf(AntlrError.class, error);
        Assertions.assertAll(
                () -> Assertions.assertEquals(line, antlrError.getLineNumber(), "line"),
                () -> Assertions.assertEquals(charPositionInLine,
                        antlrError.getCharPositionInLine(), "char position"),
                () -> Assertions.assertEquals(start, antlrError.getStart(), "start"),
                () -> Assertions.assertEquals(stop, antlrError.getStop(), "stop"),
                () -> Assertions.assertEquals(text, antlrError.getText(), "text"),
                () -> Assertions.assertEquals(msg, antlrError.getMsg(), "message"));
    }

    private static final class CountingParseTreeListener implements ParseTreeListener {

        private int exitedRules;

        @Override
        public void visitTerminal(TerminalNode node) {
            // not counted
        }

        @Override
        public void visitErrorNode(ErrorNode node) {
            // not counted
        }

        @Override
        public void enterEveryRule(ParserRuleContext ctx) {
            // not counted
        }

        @Override
        public void exitEveryRule(ParserRuleContext ctx) {
            exitedRules++;
        }
    }
}
