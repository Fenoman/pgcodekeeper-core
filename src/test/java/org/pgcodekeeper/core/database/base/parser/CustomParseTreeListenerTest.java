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

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;

/**
 * The listener reports one unit of work per rule no deeper than its monitoring
 * level, which is what {@code ctx.depth() <= monitoringLevel} used to ask by
 * walking every parent up to the root on every single rule exit.
 * <p>
 * The walk was replaced by one that stops as soon as the answer is settled, so
 * the tests below pin the answer itself rather than the way it is reached: for
 * every level the count must be the one {@link ParserRuleContext#depth()} would
 * have produced, and the oracle below is that very expression.
 */
final class CustomParseTreeListenerTest {

    private static final String SQL = """
            CREATE TABLE public.t (id integer, payload text);
            ALTER TABLE public.t ADD CONSTRAINT t_pk PRIMARY KEY (id);
            CREATE INDEX t_payload ON public.t USING btree (payload);
            CREATE VIEW public.v AS SELECT id, payload FROM public.t WHERE id > 0;
            """;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 5, 40})
    void countsExactlyWhatTheDepthWalkCounted(int monitoringLevel) {
        CountingMonitor counting = new CountingMonitor();
        parseWith(new CustomParseTreeListener(monitoringLevel, counting));

        CountingMonitor reference = new CountingMonitor();
        parseWith(new DepthWalkingListener(monitoringLevel, reference));

        Assertions.assertEquals(reference.worked, counting.worked,
                "monitoring level " + monitoringLevel);
    }

    @Test
    void theDefaultLevelReportsOnceForTheWholeParse() {
        CountingMonitor counting = new CountingMonitor();
        parseWith(new CustomParseTreeListener(1, counting));

        Assertions.assertEquals(1, counting.worked,
                "at level one only the root rule is within the monitored depth");
    }

    @Test
    void aLevelOfZeroReportsNothing() {
        CountingMonitor counting = new CountingMonitor();
        parseWith(new CustomParseTreeListener(0, counting));

        Assertions.assertEquals(0, counting.worked,
                "a depth is never below one, so a level of zero can never be reached");
    }

    @Test
    void deeperLevelsReportMore() {
        CountingMonitor one = new CountingMonitor();
        parseWith(new CustomParseTreeListener(1, one));
        CountingMonitor three = new CountingMonitor();
        parseWith(new CustomParseTreeListener(3, three));

        Assertions.assertTrue(three.worked > one.worked,
                "level three covers the root and two more layers below it, "
                        + "got " + three.worked + " against " + one.worked);
    }

    @Test
    void cancellationIsStillNoticedAtTheMonitoredDepth() {
        Assertions.assertThrows(MonitorCancelledRuntimeException.class,
                () -> parseWith(new CustomParseTreeListener(1, new CancelledMonitor())));
    }

    private static void parseWith(ParseTreeListener listener) {
        List<Object> errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(SQL, "listener.sql", errors);
        parser.addParseListener(listener);
        parser.sql();
        Assertions.assertTrue(errors.isEmpty(), () -> "fixture must parse cleanly, got " + errors);
    }

    /**
     * The check exactly as it was written before, kept as the oracle the new
     * one is measured against.
     */
    private record DepthWalkingListener(int monitoringLevel, IMonitor monitor) implements ParseTreeListener {

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
            if (ctx.depth() <= monitoringLevel) {
                monitor.worked(1);
            }
        }
    }

    private static final class CountingMonitor extends NullMonitor {

        private int worked;

        @Override
        public void worked(int i) {
            worked += i;
        }
    }

    private static final class CancelledMonitor extends NullMonitor {

        @Override
        public boolean isCancelled() {
            return true;
        }
    }
}
