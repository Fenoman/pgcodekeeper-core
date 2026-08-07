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
package org.pgcodekeeper.core.database.pg.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNConfig;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ATNSimulator;
import org.antlr.v4.runtime.atn.EmptyPredictionContext;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.atn.SingletonPredictionContext;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.base.parser.AntlrError;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;
import org.pgcodekeeper.core.database.base.parser.FullAnalyze;
import org.pgcodekeeper.core.database.base.schema.meta.MetaContainer;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLLexer;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * parseQName method test
 */
@Isolated("mutates the parser max-pending system property")
class PgParserUtilsTest {

    private static final String SCHEMA = "schema";
    private static final String TABLE = "table";
    private static final Object COLUMN = "column";

    @Test
    void canonicalizesIntInJsonRecordColumnDefinition() {
        Assertions.assertEquals(
                normalizeViewQuery("SELECT * FROM jsonb_to_record('{}') AS x(f_division integer)"),
                normalizeViewQuery("SELECT * FROM jsonb_to_record('{}') AS x(f_division int)"));
    }

    @Test
    void canonicalizesAliasesInCasts() {
        Assertions.assertEquals(
                normalizeViewQuery("SELECT CAST(1 AS integer), NULL::bigint"),
                normalizeViewQuery("SELECT CAST(1 AS int), NULL::int8"));
    }

    @Test
    void canonicalizesAliasArrays() {
        Assertions.assertEquals(
                normalizeViewQuery("SELECT NULL::integer[]"),
                normalizeViewQuery("SELECT NULL::int[]"));
    }

    @Test
    void canonicalizesMultipleTypeContextsInOneSelect() {
        Assertions.assertEquals(
                normalizeViewQuery("SELECT NULL::integer, NULL::bigint, NULL::smallint, "
                        + "NULL::boolean, NULL::real, NULL::double precision"),
                normalizeViewQuery("SELECT NULL::int4, NULL::int8, NULL::int2, "
                        + "NULL::bool, NULL::float4, NULL::float8"));
    }

    @Test
    void canonicalizesVarcharTypmodAndArray() {
        Assertions.assertEquals(
                normalizeViewQuery("SELECT NULL::character varying(12)[]"),
                normalizeViewQuery("SELECT NULL::varchar(12)[]"));
    }

    @Test
    void preservesUnmappedNumericTypmod() {
        String sql = "SELECT NULL::numeric(10,2)";
        String normalized = normalizeViewQuery(sql);

        Assertions.assertAll(
                () -> Assertions.assertEquals(normalizeViewWhitespaceOnly(sql), normalized),
                () -> Assertions.assertTrue(normalized.toLowerCase(Locale.ROOT)
                        .contains("numeric (10, 2)"), normalized));
    }

    @Test
    void doesNotRewriteOrdinaryStringLiteral() {
        String sql = "SELECT 'int' AS value";
        String normalized = normalizeViewQuery(sql);

        Assertions.assertAll(
                () -> Assertions.assertEquals(normalizeViewWhitespaceOnly(sql), normalized),
                () -> Assertions.assertTrue(normalized.contains("'int'"), normalized));
    }

    @Test
    void doesNotRewriteQuotedIdentifier() {
        String sql = "SELECT \"int\" FROM test_table";
        String normalized = normalizeViewQuery(sql);

        Assertions.assertAll(
                () -> Assertions.assertEquals(normalizeViewWhitespaceOnly(sql), normalized),
                () -> Assertions.assertTrue(normalized.contains("\"int\""), normalized));
    }

    @Test
    void doesNotRewriteFunctionOrColumnName() {
        String sql = "SELECT int4(value), test_table.int4 FROM test_table";

        Assertions.assertEquals(normalizeViewWhitespaceOnly(sql), normalizeViewQuery(sql));
    }

    @Test
    void doesNotRewriteQuotedCustomType() {
        String sql = "SELECT NULL::\"int\"[]";
        String normalized = normalizeViewQuery(sql);

        Assertions.assertAll(
                () -> Assertions.assertEquals(normalizeViewWhitespaceOnly(sql), normalized),
                () -> Assertions.assertTrue(normalized.contains("\"int\""), normalized),
                () -> Assertions.assertFalse(normalized.contains("integer"), normalized));
    }

    @Test
    void doesNotRewriteSchemaQualifiedCustomType() {
        String sql = "SELECT NULL::custom.int4";

        Assertions.assertEquals(normalizeViewWhitespaceOnly(sql), normalizeViewQuery(sql));
    }

    @Test
    void leavesUnmappedDecDistinctFromDecimal() {
        String decSql = "SELECT CAST(1 AS DEC)";
        String dec = normalizeViewQuery(decSql);
        String decimal = normalizeViewQuery("SELECT CAST(1 AS decimal)");

        Assertions.assertAll(
                () -> Assertions.assertEquals(normalizeViewWhitespaceOnly(decSql), dec),
                () -> Assertions.assertNotEquals(decimal, dec));
    }

    @Test
    void keepsExistingWhitespaceAndKeywordNormalizationOutsideTypes() {
        String sql = "select  1,  'int'  from  test_table  "
                + "where  test_table.\"int\" = 'a  b'";

        Assertions.assertEquals("SELECT 1, 'int' FROM test_table WHERE test_table.\"int\" = 'a  b'",
                normalizeViewQuery(sql));
    }

    @Test
    void testIsolatedSqlParsersDoNotSharePredictionCaches() {
        var firstErrors = new ArrayList<>();
        var secondErrors = new ArrayList<>();
        var shared = PgParserUtils.createSqlParser("SELECT 1", "shared parser", new ArrayList<>());
        var first = PgParserUtils.createIsolatedSqlParser(
                "SELECT CASE WHEN true THEN 1 ELSE 2 END;", "first body", firstErrors, 0, 0, 0);
        var second = PgParserUtils.createIsolatedSqlParser(
                "BEGIN IF true THEN PERFORM 1; END IF; END", "second body", secondErrors, 0, 0, 0);

        Assertions.assertNotSame(first.getInterpreter().decisionToDFA,
                second.getInterpreter().decisionToDFA);
        Assertions.assertNotSame(first.getInterpreter().getSharedContextCache(),
                second.getInterpreter().getSharedContextCache());
        Assertions.assertNotSame(shared.getInterpreter().decisionToDFA,
                first.getInterpreter().decisionToDFA);
        Assertions.assertNotSame(shared.getInterpreter().getSharedContextCache(),
                first.getInterpreter().getSharedContextCache());
        Assertions.assertEquals(0, getInitializedDfaCount(first));
        Assertions.assertEquals(0, getInitializedDfaCount(second));

        first.sql();
        int firstCacheSize = first.getInterpreter().getSharedContextCache().size();
        second.plpgsql_function();

        Assertions.assertTrue(getInitializedDfaCount(first) > 0);
        Assertions.assertTrue(getInitializedDfaCount(first) < first.getInterpreter().decisionToDFA.length);
        Assertions.assertTrue(getInitializedDfaCount(second) > 0);
        Assertions.assertTrue(getInitializedDfaCount(second) < second.getInterpreter().decisionToDFA.length);
        for (int i = 0; i < first.getInterpreter().decisionToDFA.length; i++) {
            var firstDfa = first.getInterpreter().decisionToDFA[i];
            var secondDfa = second.getInterpreter().decisionToDFA[i];
            if (firstDfa != null || secondDfa != null) {
                Assertions.assertNotSame(firstDfa, secondDfa);
            }
        }
        Assertions.assertTrue(firstCacheSize > 0);
        Assertions.assertTrue(second.getInterpreter().getSharedContextCache().size() > 0);
        Assertions.assertTrue(getDfaStateCount(first) > 0);
        Assertions.assertTrue(getDfaStateCount(second) > 0);
        Assertions.assertEquals(firstCacheSize, first.getInterpreter().getSharedContextCache().size());
        Assertions.assertTrue(firstErrors.isEmpty(), firstErrors::toString);
        Assertions.assertTrue(secondErrors.isEmpty(), secondErrors::toString);
    }

    @Test
    void testOnlyBoundedParserUsesConcurrentPredictionContextCache() {
        var manager = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        var bounded = PgParserUtils.createSqlParser("SELECT 1", "bounded", new ArrayList<>());
        manager.configureParser(bounded);
        var regular = PgParserUtils.createSqlParser("SELECT 1", "regular", new ArrayList<>());
        var isolated = PgParserUtils.createIsolatedSqlParser(
                "SELECT 1", "isolated", new ArrayList<>(), 0, 0, 0);

        Assertions.assertInstanceOf(ConcurrentPredictionContextCache.class,
                bounded.getInterpreter().getSharedContextCache());
        Assertions.assertEquals(PredictionContextCache.class,
                regular.getInterpreter().getSharedContextCache().getClass());
        Assertions.assertEquals(PredictionContextCache.class,
                isolated.getInterpreter().getSharedContextCache().getClass());
    }

    @Test
    void testBoundedParserCacheReusesPredictionStateWithinLimit() {
        var cache = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        var first = PgParserUtils.createSqlParser("SELECT CASE WHEN true THEN 1 ELSE 2 END", "first", new ArrayList<>());
        cache.configureParser(first);
        first.sql();

        var second = PgParserUtils.createSqlParser("SELECT CASE WHEN false THEN 3 ELSE 4 END", "second", new ArrayList<>());
        cache.configureParser(second);

        Assertions.assertSame(first.getInterpreter().decisionToDFA,
                second.getInterpreter().decisionToDFA);
        Assertions.assertSame(first.getInterpreter().getSharedContextCache(),
                second.getInterpreter().getSharedContextCache());
    }

    @Test
    void testBoundedParserStopsOnMonitorCancellationWithoutDiagnostic() {
        var errors = new ArrayList<>();
        var monitor = new NullMonitor();
        monitor.setCancelled(true);
        var parser = PgParserUtils.createBoundedSqlParser(
                "SELECT CASE WHEN true THEN 1 ELSE 2 END",
                "cancelled body", errors, 0, 0, 0, monitor);

        Assertions.assertThrows(MonitorCancelledRuntimeException.class, parser::sql);

        Assertions.assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void testParserCancellationCheckPreservesMonitorFailureIdentity() {
        RuntimeException monitorFailure = new IllegalStateException(
                "controlled parser monitor failure");
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                throw monitorFailure;
            }
        };

        RuntimeException thrown = Assertions.assertThrows(
                RuntimeException.class,
                () -> PgParserUtils.checkParserCancellation(monitor));

        Assertions.assertSame(monitorFailure, thrown);
    }

    @Test
    void testBoundedParserPublicApiPreservesMonitorFailureIdentity() {
        RuntimeException monitorFailure = new IllegalStateException(
                "controlled adaptive prediction monitor failure");
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                throw monitorFailure;
            }
        };
        var parser = PgParserUtils.createBoundedSqlParser(
                "SELECT CASE WHEN true THEN 1 ELSE 2 END",
                "failing body", new ArrayList<>(), 0, 0, 0, monitor);

        RuntimeException thrown = Assertions.assertThrows(
                RuntimeException.class, parser::sql);

        Assertions.assertSame(monitorFailure, thrown);
    }

    @Test
    void testRemoveIntoChecksCancellationDuringForwardScan() {
        CountingTokenStream stream = createLongIntoTokenStream();
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return stream.getTokenReads() >= 3000;
            }
        };

        Assertions.assertThrows(MonitorCancelledRuntimeException.class,
                () -> PgParserUtils.removeIntoStatements(
                        new SQLParser(stream), monitor));

        Assertions.assertAll(
                () -> Assertions.assertTrue(stream.getTokenReads() >= 3000),
                () -> Assertions.assertTrue(stream.getTokenReads() < 8000,
                        () -> "forward INTO scan ignored cancellation for "
                                + stream.getTokenReads() + " token reads"));
    }

    @Test
    void testRemoveIntoChecksCancellationDuringReverseHide() {
        CountingTokenStream stream = createLongIntoTokenStream();
        stream.fill();
        int initiallyHidden = stream.getHiddenTokenCount();
        int hideableTokens = stream.size() - initiallyHidden - 3;
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return stream.getHiddenTokenCount() > initiallyHidden;
            }
        };

        Assertions.assertThrows(MonitorCancelledRuntimeException.class,
                () -> PgParserUtils.removeIntoStatements(
                        new SQLParser(stream), monitor));

        int hidden = stream.getHiddenTokenCount() - initiallyHidden;
        Assertions.assertAll(
                () -> Assertions.assertTrue(hidden > 0),
                () -> Assertions.assertTrue(hidden < hideableTokens,
                        "reverse INTO hide completed before observing cancellation"));
    }

    @Test
    void testRemoveIntoPublicApiPreservesMonitorFailureIdentity() {
        CountingTokenStream stream = createLongIntoTokenStream();
        RuntimeException monitorFailure = new IllegalStateException(
                "controlled remove-into monitor failure");
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                throw monitorFailure;
            }
        };

        RuntimeException thrown = Assertions.assertThrows(RuntimeException.class,
                () -> PgParserUtils.removeIntoStatements(
                        new SQLParser(stream), monitor));

        Assertions.assertSame(monitorFailure, thrown);
    }

    @Test
    void testBoundedParserCacheResetsAfterLimit() {
        var cache = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, 1, Integer.MAX_VALUE);
        var first = PgParserUtils.createSqlParser("SELECT CASE WHEN true THEN 1 ELSE 2 END", "first", new ArrayList<>());
        cache.configureParser(first);
        first.sql();
        var firstDfas = first.getInterpreter().decisionToDFA;
        var firstContexts = first.getInterpreter().getSharedContextCache();

        var second = PgParserUtils.createSqlParser("SELECT 1", "second", new ArrayList<>());
        cache.configureParser(second);

        Assertions.assertNotSame(firstDfas, second.getInterpreter().decisionToDFA);
        Assertions.assertNotSame(firstContexts, second.getInterpreter().getSharedContextCache());
        Assertions.assertEquals(second.getInterpreter().decisionToDFA.length,
                getInitializedDfaCount(second));
        Assertions.assertEquals(0, second.getInterpreter().getSharedContextCache().size());
    }

    @Test
    void testPredictionContextLimitRotatesGenerationImmediately() throws ReflectiveOperationException {
        var cache = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, 1);
        var parser = PgParserUtils.createSqlParser(
                "SELECT CASE WHEN true THEN 1 ELSE 2 END", "context-heavy", new ArrayList<>());
        cache.configureParser(parser);
        Object parserGeneration = getParserCacheGeneration(parser);

        parser.sql();

        Assertions.assertTrue(parser.getInterpreter().getSharedContextCache().size() > 1);
        Assertions.assertNotSame(parserGeneration, getCurrentCacheGeneration(cache));
    }

    @Test
    void testDfaAccountingExcludesUncachedErrorState() throws ReflectiveOperationException {
        var cache = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        var parser = PgParserUtils.createSqlParser("SELECT 1", "body", new ArrayList<>());
        cache.configureParser(parser);
        var interpreter = parser.getInterpreter();
        DFA dfa = interpreter.decisionToDFA[0];
        int realCount = dfa.states.size();
        long recordedCount = getRecordedDfaStateCount(parser);
        Method addDfaState = interpreter.getClass().getDeclaredMethod(
                "addDFAState", DFA.class, DFAState.class);
        addDfaState.setAccessible(true);

        addDfaState.invoke(interpreter, dfa, ATNSimulator.ERROR);

        Assertions.assertEquals(realCount, dfa.states.size());
        Assertions.assertEquals(recordedCount, getRecordedDfaStateCount(parser));
    }

    @Test
    void testDfaAccountingCountsRepeatedCandidateOnlyOnce() throws ReflectiveOperationException {
        var cache = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        var parser = PgParserUtils.createSqlParser("SELECT 1", "body", new ArrayList<>());
        cache.configureParser(parser);
        var interpreter = parser.getInterpreter();
        DFA dfa = new DFA(parser.getATN().getDecisionState(0), 0);
        DFAState candidate = createDfaState(dfa, EmptyPredictionContext.Instance);
        long recordedCount = getRecordedDfaStateCount(parser);
        Method addDfaState = interpreter.getClass().getDeclaredMethod(
                "addDFAState", DFA.class, DFAState.class);
        addDfaState.setAccessible(true);

        DFAState first = (DFAState) addDfaState.invoke(interpreter, dfa, candidate);
        DFAState second = (DFAState) addDfaState.invoke(interpreter, dfa, candidate);

        Assertions.assertSame(candidate, first);
        Assertions.assertSame(first, second);
        Assertions.assertEquals(1, dfa.states.size());
        Assertions.assertEquals(recordedCount + 1, getRecordedDfaStateCount(parser));
    }

    @Test
    void testDfaCanonicalizationDoesNotHoldStateMapMonitor() throws Exception {
        var cache = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        var parser = PgParserUtils.createSqlParser("SELECT 1", "body", new ArrayList<>());
        cache.configureParser(parser);
        var interpreter = parser.getInterpreter();
        DFA dfa = new DFA(parser.getATN().getDecisionState(0), 0);
        var canonicalizationStarted = new CountDownLatch(1);
        var allowCanonicalization = new CountDownLatch(1);
        DFAState candidate = createDfaState(dfa,
                new BlockingPredictionContext(canonicalizationStarted, allowCanonicalization));
        Method addDfaState = interpreter.getClass().getDeclaredMethod(
                "addDFAState", DFA.class, DFAState.class);
        addDfaState.setAccessible(true);
        var executor = Executors.newFixedThreadPool(2);
        var publication = executor.submit(() -> addDfaState.invoke(interpreter, dfa, candidate));
        java.util.concurrent.Future<?> monitorProbe = null;
        boolean acquiredWhileCanonicalizing = false;

        try {
            Assertions.assertTrue(canonicalizationStarted.await(5, TimeUnit.SECONDS),
                    "DFA state canonicalization did not start");
            var monitorAcquired = new CountDownLatch(1);
            monitorProbe = executor.submit(() -> {
                synchronized (dfa.states) {
                    monitorAcquired.countDown();
                }
            });
            acquiredWhileCanonicalizing = monitorAcquired.await(2, TimeUnit.SECONDS);
        } finally {
            allowCanonicalization.countDown();
            publication.get(5, TimeUnit.SECONDS);
            if (monitorProbe != null) {
                monitorProbe.get(5, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
        }

        Assertions.assertTrue(acquiredWhileCanonicalizing,
                "DFA states monitor was held while prediction contexts were canonicalized");
    }

    @Test
    void testDfaStateHashAndEqualitySurviveContextCanonicalization() {
        var cache = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        var parser = PgParserUtils.createSqlParser("SELECT 1", "body", new ArrayList<>());
        cache.configureParser(parser);
        var interpreter = parser.getInterpreter();
        var contexts = (ConcurrentPredictionContextCache) interpreter.getSharedContextCache();
        PredictionContext canonicalParent = SingletonPredictionContext.create(
                EmptyPredictionContext.Instance, 7);
        contexts.add(canonicalParent);
        PredictionContext equalParent = SingletonPredictionContext.create(
                EmptyPredictionContext.Instance, 7);
        PredictionContext originalContext = SingletonPredictionContext.create(equalParent, 11);
        PredictionContext equalContext = SingletonPredictionContext.create(
                SingletonPredictionContext.create(EmptyPredictionContext.Instance, 7), 11);
        DFA dfa = new DFA(parser.getATN().getDecisionState(0), 0);
        DFAState candidate = createDfaState(dfa, originalContext);
        DFAState structurallyEqual = createDfaState(dfa, equalContext);
        int hashBefore = candidate.hashCode();

        candidate.configs.optimizeConfigs(interpreter);
        candidate.configs.setReadonly(true);
        structurallyEqual.configs.setReadonly(true);

        PredictionContext canonicalContext = candidate.configs.get(0).context;
        Assertions.assertNotSame(originalContext, canonicalContext);
        Assertions.assertSame(canonicalParent, canonicalContext.getParent(0));
        Assertions.assertEquals(hashBefore, candidate.hashCode());
        Assertions.assertEquals(structurallyEqual, candidate);
        dfa.states.put(structurallyEqual, structurallyEqual);
        Assertions.assertSame(structurallyEqual, dfa.states.get(candidate));
    }

    @Test
    void testConcurrentDfaPublicationReturnsOneWinnerAndCountsItOnce() throws Exception {
        int workerCount = 8;
        var cache = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        var parsers = new ArrayList<SQLParser>(workerCount);
        for (int i = 0; i < workerCount; i++) {
            var parser = PgParserUtils.createSqlParser("SELECT 1", "body " + i, new ArrayList<>());
            cache.configureParser(parser);
            parsers.add(parser);
        }
        DFA dfa = new DFA(parsers.get(0).getATN().getDecisionState(0), 0);
        long recordedCount = getRecordedDfaStateCount(parsers.get(0));
        Method addDfaState = parsers.get(0).getInterpreter().getClass().getDeclaredMethod(
                "addDFAState", DFA.class, DFAState.class);
        addDfaState.setAccessible(true);
        var ready = new CountDownLatch(workerCount);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workerCount);
        var publications = new ArrayList<java.util.concurrent.Future<DFAState>>(workerCount);

        try {
            for (var parser : parsers) {
                DFAState candidate = createDfaState(dfa, EmptyPredictionContext.Instance);
                publications.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return (DFAState) addDfaState.invoke(parser.getInterpreter(), dfa, candidate);
                }));
            }
            Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            DFAState winner = publications.get(0).get(5, TimeUnit.SECONDS);
            for (var publication : publications) {
                Assertions.assertSame(winner, publication.get(5, TimeUnit.SECONDS));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        Assertions.assertEquals(1, dfa.states.size());
        Assertions.assertEquals(recordedCount + 1, getRecordedDfaStateCount(parsers.get(0)));
    }

    @Test
    void testRetiredGenerationAcceptsPublicationAlreadyInFlight() throws Exception {
        var cache = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        var parser = PgParserUtils.createSqlParser("SELECT 1", "body", new ArrayList<>());
        cache.configureParser(parser);
        Object generation = getParserCacheGeneration(parser);
        long recordedCount = getRecordedDfaStateCount(parser);
        var interpreter = parser.getInterpreter();
        DFA dfa = new DFA(parser.getATN().getDecisionState(0), 0);
        var canonicalizationStarted = new CountDownLatch(1);
        var allowCanonicalization = new CountDownLatch(1);
        DFAState candidate = createDfaState(dfa,
                new BlockingPredictionContext(canonicalizationStarted, allowCanonicalization));
        Method addDfaState = interpreter.getClass().getDeclaredMethod(
                "addDFAState", DFA.class, DFAState.class);
        addDfaState.setAccessible(true);
        var executor = Executors.newSingleThreadExecutor();
        var publication = executor.submit(() -> addDfaState.invoke(interpreter, dfa, candidate));

        try {
            Assertions.assertTrue(canonicalizationStarted.await(5, TimeUnit.SECONDS));
            cache.release();
            allowCanonicalization.countDown();
            Assertions.assertSame(candidate, publication.get(5, TimeUnit.SECONDS));
        } finally {
            allowCanonicalization.countDown();
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        Assertions.assertEquals(1, dfa.states.size());
        Assertions.assertEquals(recordedCount + 1, getRecordedDfaStateCount(parser));
        Assertions.assertNull(getCurrentCacheGeneration(cache));
        var next = PgParserUtils.createSqlParser("SELECT 2", "next body", new ArrayList<>());
        cache.configureParser(next);
        Assertions.assertNotSame(generation, getParserCacheGeneration(next));
    }

    @Test
    void testParseSqlStreamMergesWorkerErrorsInSubmissionOrder() throws Exception {
        Assumptions.assumeTrue(AntlrTaskManager.getPoolSize() >= 2,
                "requires two parser workers to force reverse completion order");
        String originalMaxPending = System.getProperty(Consts.MAX_PENDING_TASKS);
        System.setProperty(Consts.MAX_PENDING_TASKS, "2");
        var settings = new CoreSettings();
        Queue<AntlrTask<?>> tasks = AntlrTaskManager.createTaskQueue();
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        byte[] firstSql = """
                CREATE TABLE public.bad_one (
                    id integer,
                    value text DEFAULT
                );
                """.getBytes(StandardCharsets.UTF_8);
        byte[] secondSql = """
                CREATE VIEW public.bad_view AS
                SELECT * FRUM public.bad_one;
                """.getBytes(StandardCharsets.UTF_8);

        try {
            PgParserUtils.parseSqlStream(() -> {
                firstStarted.countDown();
                await(releaseFirst);
                return new ByteArrayInputStream(firstSql);
            }, "first.sql", settings, 0, (ctx, tokens) -> { }, tasks);
            await(firstStarted);

            PgParserUtils.parseSqlStream(() -> new ByteArrayInputStream(secondSql),
                    "second.sql", settings, 0, (ctx, tokens) -> { }, tasks);
            AntlrTask<?> secondTask = tasks.stream().skip(1).findFirst().orElseThrow();
            getTaskFuture(secondTask).get(5, TimeUnit.SECONDS);
            releaseFirst.countDown();
            AntlrTaskManager.finish(tasks);
        } finally {
            releaseFirst.countDown();
            AntlrTaskManager.abort(tasks);
            if (originalMaxPending == null) {
                System.clearProperty(Consts.MAX_PENDING_TASKS);
            } else {
                System.setProperty(Consts.MAX_PENDING_TASKS, originalMaxPending);
            }
        }

        List<String> errorFiles = settings.getErrors().stream()
                .filter(AntlrError.class::isInstance)
                .map(AntlrError.class::cast)
                .map(AntlrError::getFilePath)
                .toList();
        Assertions.assertEquals(List.of("first.sql", "second.sql"), errorFiles);
    }

    @Test
    void testParseSqlStreamPreservesWorkerErrorsWhenStreamCleanupFails() {
        var settings = new CoreSettings();
        Queue<AntlrTask<?>> tasks = AntlrTaskManager.createTaskQueue();
        byte[] invalidSql = "SELECT )".getBytes(StandardCharsets.UTF_8);
        var closeCount = new AtomicInteger();

        PgParserUtils.parseSqlStream(() -> new ByteArrayInputStream(invalidSql) {
            @Override
            public void close() throws IOException {
                super.close();
                if (closeCount.incrementAndGet() == 2) {
                    throw new IOException("synthetic cleanup failure");
                }
            }
        }, "cleanup.sql", settings, 0, (ctx, tokens) -> { }, tasks);

        try {
            Assertions.assertThrows(IOException.class, () -> AntlrTaskManager.finish(tasks));
        } finally {
            AntlrTaskManager.abort(tasks);
        }

        List<String> errorFiles = settings.getErrors().stream()
                .filter(AntlrError.class::isInstance)
                .map(AntlrError.class::cast)
                .map(AntlrError::getFilePath)
                .toList();
        Assertions.assertEquals(List.of("cleanup.sql"), errorFiles);
    }

    @Test
    void testBoundedParserCacheSupportsConcurrentParsers() throws Exception {
        var cache = new PgParserUtils.BoundedParserCacheManager(
                8, 128, 8);
        var ready = new CountDownLatch(4);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(4);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();

        try {
            for (int worker = 0; worker < 4; worker++) {
                int workerId = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int i = 0; i < 16; i++) {
                        var errors = new ArrayList<>();
                        boolean sqlBody = (workerId + i) % 2 == 0;
                        String body = sqlBody
                                ? "SELECT CASE WHEN true THEN " + i + " ELSE " + workerId + " END"
                                : "BEGIN IF true THEN PERFORM " + i + "; END IF; END";
                        var parser = PgParserUtils.createSqlParser(body, "concurrent body", errors);
                        cache.configureParser(parser);
                        if (sqlBody) {
                            parser.sql();
                        } else {
                            parser.plpgsql_function();
                        }
                        Assertions.assertTrue(errors.isEmpty(), errors::toString);
                    }
                    return null;
                }));
            }

            Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testReleasedGenerationRemainsValidForActiveParser() {
        var cache = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        var firstErrors = new ArrayList<>();
        var first = PgParserUtils.createSqlParser("SELECT CASE WHEN true THEN 1 ELSE 2 END", "first", firstErrors);
        cache.configureParser(first);
        var firstDfas = first.getInterpreter().decisionToDFA;

        cache.release();
        first.sql();

        var secondErrors = new ArrayList<>();
        var second = PgParserUtils.createSqlParser("SELECT 2", "second", secondErrors);
        cache.configureParser(second);
        second.sql();

        Assertions.assertNotSame(firstDfas, second.getInterpreter().decisionToDFA);
        Assertions.assertTrue(firstErrors.isEmpty(), firstErrors::toString);
        Assertions.assertTrue(secondErrors.isEmpty(), secondErrors::toString);
    }

    @Test
    void testFullAnalyzeReleasesBodyParserCacheAfterSuccess() throws Exception {
        var first = PgParserUtils.createBoundedSqlParser("SELECT 1", "first", new ArrayList<>(), 0, 0, 0);
        var firstDfas = first.getInterpreter().decisionToDFA;

        try {
            FullAnalyze.fullAnalyze(new PgDatabase(), new MetaContainer(), new ArrayList<>());
            var second = PgParserUtils.createBoundedSqlParser("SELECT 2", "second", new ArrayList<>(), 0, 0, 0);

            Assertions.assertNotSame(firstDfas, second.getInterpreter().decisionToDFA);
        } finally {
            PgParserUtils.releaseBodyParserCache();
        }
    }

    @Test
    void testFullAnalyzeReleasesBodyParserCacheAfterFailure() {
        var first = PgParserUtils.createBoundedSqlParser("SELECT 1", "first", new ArrayList<>(), 0, 0, 0);
        var firstDfas = first.getInterpreter().decisionToDFA;
        var db = new PgDatabase();
        db.addAnalysisLauncher(new IAnalysisLauncher() {
            @Override
            public IStatement getStmt() {
                return new PgFunction("failing_function");
            }

            @Override
            public void updateStmt(IDatabase database) {
                // no-op test launcher
            }

            @Override
            public Set<ObjectReference> launchAnalyze(List<Object> errors, IMetaContainer meta) {
                throw new AssertionError("controlled failure");
            }

            @Override
            public List<ObjectLocation> getReferences() {
                return List.of();
            }

            @Override
            public String getSchemaName() {
                return null;
            }
        });

        try {
            Assertions.assertThrows(IllegalStateException.class,
                    () -> FullAnalyze.fullAnalyze(db, new MetaContainer(), new ArrayList<>()));
            Assertions.assertTrue(db.getAnalysisLaunchers().isEmpty());
            var second = PgParserUtils.createBoundedSqlParser("SELECT 2", "second", new ArrayList<>(), 0, 0, 0);

            Assertions.assertNotSame(firstDfas, second.getInterpreter().decisionToDFA);
        } finally {
            PgParserUtils.releaseBodyParserCache();
        }
    }

    @Test
    void testFullAnalyzeMergesConcurrentErrorsInLauncherOrder() throws Exception {
        Assumptions.assumeTrue(org.pgcodekeeper.core.database.base.parser.AntlrTaskManager.getPoolSize() >= 2);
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var db = new PgDatabase();
        db.addAnalysisLauncher(errorLauncher("first", errors -> {
            firstStarted.countDown();
            await(releaseFirst);
            errors.add("first");
        }));
        db.addAnalysisLauncher(errorLauncher("second", errors -> {
            await(firstStarted);
            errors.add("second");
            releaseFirst.countDown();
        }));
        var errors = new ArrayList<>();

        FullAnalyze.fullAnalyze(db, new MetaContainer(), errors);

        Assertions.assertEquals(List.of("first", "second"), errors);
    }

    private static int getDfaStateCount(SQLParser parser) {
        int count = 0;
        for (var dfa : parser.getInterpreter().decisionToDFA) {
            if (dfa != null) {
                count += dfa.states.size();
            }
        }
        return count;
    }

    private static String normalizeViewQuery(String sql) {
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(sql, "view normalization test", errors);
        var select = parser.sql().statement(0).data_statement().select_stmt();
        Assertions.assertTrue(errors.isEmpty(), errors::toString);
        return PgParserUtils.normalizeViewQueryForComparison(
                select, (CommonTokenStream) parser.getTokenStream());
    }

    private static String normalizeViewWhitespaceOnly(String sql) {
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(sql, "view whitespace test", errors);
        var select = parser.sql().statement(0).data_statement().select_stmt();
        Assertions.assertTrue(errors.isEmpty(), errors::toString);
        return PgParserUtils.normalizeWhitespaceUnquoted(
                select, (CommonTokenStream) parser.getTokenStream());
    }

    private static Object getParserCacheGeneration(SQLParser parser) throws ReflectiveOperationException {
        Field generation = parser.getInterpreter().getClass().getDeclaredField("generation");
        generation.setAccessible(true);
        return generation.get(parser.getInterpreter());
    }

    private static long getRecordedDfaStateCount(SQLParser parser) throws ReflectiveOperationException {
        Object generation = getParserCacheGeneration(parser);
        Field count = generation.getClass().getDeclaredField("dfaStateCount");
        count.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicLong) count.get(generation)).get();
    }

    private static DFAState createDfaState(DFA dfa, PredictionContext context) {
        var configs = new ATNConfigSet();
        configs.add(new ATNConfig(dfa.atnStartState, 1, context));
        return new DFAState(configs);
    }

    private static CountingTokenStream createLongIntoTokenStream() {
        StringBuilder sql = new StringBuilder("SELECT 1 INTO ");
        for (int i = 0; i < 4096; i++) {
            if (i != 0) {
                sql.append(',');
            }
            sql.append("target_").append(i);
        }
        return new CountingTokenStream(
                new SQLLexer(CharStreams.fromString(sql.toString())));
    }

    private static final class CountingTokenStream extends CommonTokenStream {

        private int tokenReads;

        private CountingTokenStream(SQLLexer lexer) {
            super(lexer);
        }

        @Override
        public Token LT(int k) {
            tokenReads++;
            return super.LT(k);
        }

        private int getTokenReads() {
            return tokenReads;
        }

        private int getHiddenTokenCount() {
            int hidden = 0;
            for (Token token : getTokens()) {
                if (token.getChannel() == Token.HIDDEN_CHANNEL) {
                    hidden++;
                }
            }
            return hidden;
        }
    }

    private static final class BlockingPredictionContext extends PredictionContext {

        private static final int RETURN_STATE = 1;

        private final CountDownLatch canonicalizationStarted;
        private final CountDownLatch allowCanonicalization;

        private BlockingPredictionContext(CountDownLatch canonicalizationStarted,
                                          CountDownLatch allowCanonicalization) {
            super(calculateHashCode(EmptyPredictionContext.Instance, RETURN_STATE));
            this.canonicalizationStarted = canonicalizationStarted;
            this.allowCanonicalization = allowCanonicalization;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public PredictionContext getParent(int index) {
            if (index != 0) {
                throw new IndexOutOfBoundsException(index);
            }
            canonicalizationStarted.countDown();
            await(allowCanonicalization);
            return EmptyPredictionContext.Instance;
        }

        @Override
        public int getReturnState(int index) {
            if (index != 0) {
                throw new IndexOutOfBoundsException(index);
            }
            return RETURN_STATE;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }
    }

    private static Object getCurrentCacheGeneration(PgParserUtils.BoundedParserCacheManager manager)
            throws ReflectiveOperationException {
        Field current = PgParserUtils.BoundedParserCacheManager.class.getDeclaredField("current");
        current.setAccessible(true);
        return ((AtomicReference<?>) current.get(manager)).get();
    }

    private static java.util.concurrent.Future<?> getTaskFuture(AntlrTask<?> task)
            throws ReflectiveOperationException {
        Field future = AntlrTask.class.getDeclaredField("future");
        future.setAccessible(true);
        return (java.util.concurrent.Future<?>) future.get(task);
    }

    private static IAnalysisLauncher errorLauncher(String name, Consumer<List<Object>> action) {
        return new IAnalysisLauncher() {
            private final PgFunction function = new PgFunction(name);

            @Override
            public IStatement getStmt() {
                return function;
            }

            @Override
            public void updateStmt(IDatabase database) {
                // no-op test launcher
            }

            @Override
            public Set<ObjectReference> launchAnalyze(List<Object> errors, IMetaContainer meta) {
                action.accept(errors);
                return Set.of();
            }

            @Override
            public List<ObjectLocation> getReferences() {
                return List.of();
            }

            @Override
            public String getSchemaName() {
                return null;
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static int getInitializedDfaCount(SQLParser parser) {
        int count = 0;
        for (var dfa : parser.getInterpreter().decisionToDFA) {
            if (dfa != null) {
                count++;
            }
        }
        return count;
    }

    @Test
    void testParseSchemaBothQuoted() {
        Assertions.assertEquals(SCHEMA, PgParserUtils.parseQName("\"schema\".\"table\"").getSchemaName());
    }

    @Test
    void testParseSchemaFirstQuoted() {
        Assertions.assertEquals(SCHEMA, PgParserUtils.parseQName("\"schema\".table").getSchemaName());
    }

    @Test
    void testParseSchemaSecondQuoted() {
        Assertions.assertEquals(SCHEMA, PgParserUtils.parseQName("schema.\"table\"").getSchemaName());
    }

    @Test
    void testParseSchemaNoneQuoted() {
        Assertions.assertEquals(SCHEMA, PgParserUtils.parseQName("schema.table").getSchemaName());
    }

    @Test
    void testParseSchemaThreeQuoted() {
        Assertions.assertEquals(SCHEMA, PgParserUtils.parseQName("\"schema\".\"table\".\"column\"").getSchemaName());
    }

    @Test
    void testParseObjectBothQuoted() {
        Assertions.assertEquals(TABLE, PgParserUtils.parseQName("\"schema\".\"table\"").getFirstName());
    }

    void testParseObjectFirstQuoted() {
        Assertions.assertEquals(TABLE, PgParserUtils.parseQName("\"schema\".table").getFirstName());
    }

    @Test
    void testParseObjectSecondQuoted() {
        Assertions.assertEquals(TABLE, PgParserUtils.parseQName("schema.\"table\"").getFirstName());
    }

    @Test
    void testParseObjectNoneQuoted() {
        Assertions.assertEquals(TABLE, PgParserUtils.parseQName("schema.table").getFirstName());
    }

    @Test
    void testParseObjectThreeQuoted() {
        Assertions.assertEquals(COLUMN, PgParserUtils.parseQName("\"schema\".\"table\".\"column\"").getFirstName());
    }
}
