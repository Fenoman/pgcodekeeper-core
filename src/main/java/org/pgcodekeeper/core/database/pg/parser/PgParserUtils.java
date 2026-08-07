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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.atomic.*;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNSimulator;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.Trees;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.base.parser.*;
import org.pgcodekeeper.core.database.base.parser.statement.ParserAbstract;
import org.pgcodekeeper.core.database.pg.parser.generated.*;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Data_typeContext;
import org.pgcodekeeper.core.database.pg.parser.statement.PgParserAbstract;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.exception.UnresolvedReferenceException;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;
import org.pgcodekeeper.core.utils.Pair;

/**
 * Utility methods for PostgreSQL parsing with ANTLR.
 * <p>
 * Provides helper functions for working with PostgreSQL syntax
 * during ANTLR-based parsing
 * </p>
 */
public final class PgParserUtils {

    private static final int DEFAULT_BODY_CACHE_MAX_STATES = 2048;
    private static final int DEFAULT_BODY_CACHE_MAX_CONFIGS = 262144;
    private static final int DEFAULT_BODY_CACHE_MAX_CONTEXTS = 16384;

    private static final BoundedParserCacheManager BODY_PARSER_CACHE = new BoundedParserCacheManager(
            Integer.getInteger(Consts.BODY_CACHE_MAX_STATES, DEFAULT_BODY_CACHE_MAX_STATES),
            Integer.getInteger(Consts.BODY_CACHE_MAX_CONFIGS, DEFAULT_BODY_CACHE_MAX_CONFIGS),
            Integer.getInteger(Consts.BODY_CACHE_MAX_CONTEXTS, DEFAULT_BODY_CACHE_MAX_CONTEXTS));

    private static volatile long pgParserLastStart;

    private record ParsedSqlTask(SQLParser.SqlContext context, CommonTokenStream tokens,
                                 List<Object> errors) {
    }

    /**
     * Creates a parser for PostgreSQL privilege strings.
     *
     * @param aclArrayAsString privilege string to parse
     * @return configured PrivilegesParser instance
     */
    public static PrivilegesParser createPrivilegesParser(String aclArrayAsString) {
        var stream = CharStreams.fromString(aclArrayAsString);
        Lexer lexer = new PrivilegesLexer(stream);
        PrivilegesParser parser = new PrivilegesParser(new CommonTokenStream(lexer));
        ParserUtils.addErrorListener(lexer, parser, "jdbc privileges", null, 0, 0, 0);
        return parser;
    }

    /**
     * Creates a PostgreSQL SQL parser from string input.
     *
     * @param sql              SQL string to parse
     * @param parsedObjectName name of the object being parsed (for error reporting)
     * @param errors           list to collect parsing errors
     * @return configured SQLParser instance
     */
    public static SQLParser createSqlParser(String sql, String parsedObjectName, List<Object> errors) {
        var stream = CharStreams.fromString(sql);
        return createSqlParser(stream, parsedObjectName, errors, 0, 0, 0);
    }

    /**
     * Creates a PostgreSQL SQL parser from string input with position offset.
     *
     * @param sql              SQL string to parse
     * @param parsedObjectName name of the object being parsed (for error reporting)
     * @param errors           list to collect parsing errors
     * @param start            token providing position offset information
     * @return configured SQLParser instance
     */
    public static SQLParser createSqlParser(String sql, String parsedObjectName, List<Object> errors, Token start) {
        CodeUnitToken cuCodeStart = (CodeUnitToken) start;
        int offset = cuCodeStart.getCodeUnitStart();
        int lineOffset = cuCodeStart.getLine() - 1;
        int inLineOffset = cuCodeStart.getCodeUnitPositionInLine();
        return createSqlParser(sql, parsedObjectName, errors, offset, lineOffset, inLineOffset);
    }

    /**
     * Creates a PostgreSQL SQL parser from string input with explicit source
     * position offsets. This form lets deferred parsers preserve diagnostics
     * without retaining the original token and its complete input stream.
     *
     * @param sql                SQL string to parse
     * @param parsedObjectName   name of the object being parsed
     * @param errors             list to collect parsing errors
     * @param offset             code-unit offset in the source file
     * @param lineOffset         zero-based line offset in the source file
     * @param inLineOffset       code-unit offset within the first source line
     * @return configured SQL parser
     */
    public static SQLParser createSqlParser(String sql, String parsedObjectName, List<Object> errors,
                                            int offset, int lineOffset, int inLineOffset) {
        var stream = CharStreams.fromString(sql);
        return createSqlParser(stream, parsedObjectName, errors, offset, lineOffset, inLineOffset);
    }

    /**
     * Creates a parser whose adaptive prediction caches are owned only by this
     * parser instance. This is intended for deferred function bodies: their
     * grammar paths vary heavily and would otherwise grow the generated
     * parser's static DFA and prediction-context cache for the lifetime of the
     * JVM. The local caches become collectible together with the body parser.
     *
     * @param sql              SQL string to parse
     * @param parsedObjectName name of the object being parsed
     * @param errors           list to collect parsing errors
     * @param offset           code-unit offset in the source file
     * @param lineOffset       zero-based line offset in the source file
     * @param inLineOffset     code-unit offset within the first source line
     * @return configured parser with private adaptive prediction caches
     */
    static SQLParser createIsolatedSqlParser(String sql, String parsedObjectName, List<Object> errors,
                                             int offset, int lineOffset, int inLineOffset) {
        var parser = createSqlParser(sql, parsedObjectName, errors, offset, lineOffset, inLineOffset);
        ATN atn = parser.getATN();
        parser.setInterpreter(new LazyParserATNSimulator(
                parser, atn, new DFA[atn.getNumberOfDecisions()], new PredictionContextCache()));
        return parser;
    }

    /**
     * Creates a parser backed by a size-limited shared cache generation. A
     * complete generation is atomically replaced once any configured limit
     * is exceeded. Parsers already using the old generation remain valid and
     * release it normally, so no cache is cleared while another thread reads
     * or updates it. This preserves cross-worker reuse without the generated
     * parser's process-wide unbounded lifetime.
     *
     * @param sql              SQL string to parse
     * @param parsedObjectName name of the object being parsed
     * @param errors           list to collect parsing errors
     * @param offset           code-unit offset in the source file
     * @param lineOffset       zero-based line offset in the source file
     * @param inLineOffset     code-unit offset within the first source line
     * @return configured parser with a bounded shared cache generation
     */
    public static SQLParser createBoundedSqlParser(String sql, String parsedObjectName, List<Object> errors,
                                                   int offset, int lineOffset, int inLineOffset) {
        return createBoundedSqlParser(sql, parsedObjectName, errors,
                offset, lineOffset, inLineOffset, new NullMonitor());
    }

    /**
     * Creates a bounded function-body parser that cooperatively observes the
     * supplied operation monitor during adaptive prediction.
     *
     * @param sql              SQL string to parse
     * @param parsedObjectName name of the object being parsed
     * @param errors           list to collect parsing errors
     * @param offset           code-unit offset in the source file
     * @param lineOffset       zero-based line offset in the source file
     * @param inLineOffset     code-unit offset within the first source line
     * @param monitor          operation monitor
     * @return configured parser with a bounded shared cache generation
     */
    public static SQLParser createBoundedSqlParser(
            String sql, String parsedObjectName, List<Object> errors,
            int offset, int lineOffset, int inLineOffset, IMonitor monitor) {
        var parser = createSqlParser(sql, parsedObjectName, errors, offset, lineOffset, inLineOffset);
        BODY_PARSER_CACHE.configureParser(
                parser, monitor == null ? new NullMonitor() : monitor);
        return parser;
    }

    /**
     * Checks cancellation at parser boundaries without creating a textual
     * parser diagnostic.
     *
     * @param monitor operation monitor
     * @throws MonitorCancelledRuntimeException if cancellation was requested
     */
    public static void checkParserCancellation(IMonitor monitor) {
        if (Thread.currentThread().isInterrupted()) {
            throw new MonitorCancelledRuntimeException();
        }
        if (monitor == null) {
            return;
        }

        if (monitor.isCancelled()) {
            throw new MonitorCancelledRuntimeException();
        }
    }

    /**
     * Releases the current function-body cache generation without mutating it.
     * Parsers that are still finishing with that generation retain a valid
     * reference; subsequent parsers create a fresh generation.
     */
    public static void releaseBodyParserCache() {
        BODY_PARSER_CACHE.release();
    }

    static final class BoundedParserCacheManager {

        private static final Runnable NO_OP_ROTATION_HOOK = () -> { };

        private final int maxDfaStates;
        private final int maxAtnConfigs;
        private final int maxPredictionContexts;
        private final AtomicReference<ParserCacheGeneration> current = new AtomicReference<>();
        private final AtomicLong sharedGenerationCreationCount = new AtomicLong();
        private final Runnable rotationAttemptHook;
        private final Object generationChangeLock = new Object();

        BoundedParserCacheManager(int maxDfaStates, int maxAtnConfigs, int maxPredictionContexts) {
            this(maxDfaStates, maxAtnConfigs, maxPredictionContexts, NO_OP_ROTATION_HOOK);
        }

        BoundedParserCacheManager(int maxDfaStates, int maxAtnConfigs, int maxPredictionContexts,
                                  Runnable rotationAttemptHook) {
            this.maxDfaStates = maxDfaStates;
            this.maxAtnConfigs = maxAtnConfigs;
            this.maxPredictionContexts = maxPredictionContexts;
            this.rotationAttemptHook = Objects.requireNonNull(rotationAttemptHook);
        }

        long getSharedGenerationCreationCount() {
            return sharedGenerationCreationCount.get();
        }

        void configureParser(SQLParser parser) {
            configureParser(parser, new NullMonitor());
        }

        void configureParser(SQLParser parser, IMonitor monitor) {
            if (maxDfaStates <= 0 || maxAtnConfigs <= 0 || maxPredictionContexts <= 0) {
                new ParserCacheGeneration(parser.getATN()).configureParser(parser, null, monitor);
                return;
            }

            while (true) {
                ParserCacheGeneration generation = current.get();
                if (generation == null || generation.exceedsLimit(
                        maxDfaStates, maxAtnConfigs, maxPredictionContexts)) {
                    replaceGeneration(generation, parser.getATN());
                    continue;
                }

                generation.configureParser(parser, this, monitor);
                return;
            }
        }

        private ParserCacheGeneration createGeneration(ATN atn) {
            sharedGenerationCreationCount.incrementAndGet();
            return new ParserCacheGeneration(atn);
        }

        private void replaceGeneration(ParserCacheGeneration expected, ATN atn) {
            rotationAttemptHook.run();
            synchronized (generationChangeLock) {
                if (current.get() != expected) {
                    if (expected != null) {
                        expected.retire();
                    }
                    return;
                }

                ParserCacheGeneration replacement = createGeneration(atn);
                current.set(replacement);
                if (expected != null) {
                    expected.retire();
                }
            }
        }

        private void rotate(ParserCacheGeneration generation) {
            if (generation.isRetired()) {
                return;
            }

            if (current.get() != generation) {
                generation.retire();
                return;
            }

            replaceGeneration(generation, generation.getAtn());
        }

        void release() {
            synchronized (generationChangeLock) {
                ParserCacheGeneration generation = current.getAndSet(null);
                if (generation != null) {
                    generation.retire();
                }
            }
        }
    }

    private static final class ParserCacheGeneration {

        private final ATN atn;
        private final DFA[] decisionToDfa;
        private final ConcurrentPredictionContextCache predictionContexts =
                new ConcurrentPredictionContextCache();
        private final AtomicLong dfaStateCount = new AtomicLong();
        private final AtomicLong atnConfigCount = new AtomicLong();
        private final AtomicBoolean retired = new AtomicBoolean();

        private ParserCacheGeneration(ATN atn) {
            this.atn = atn;
            decisionToDfa = new DFA[atn.getNumberOfDecisions()];
            for (int i = 0; i < decisionToDfa.length; i++) {
                decisionToDfa[i] = new DFA(atn.getDecisionState(i), i);
            }
        }

        private ATN getAtn() {
            return atn;
        }

        private boolean isRetired() {
            return retired.get();
        }

        private void retire() {
            retired.set(true);
        }

        private void configureParser(SQLParser parser, BoundedParserCacheManager manager,
                                     IMonitor monitor) {
            parser.setInterpreter(new BoundedParserATNSimulator(
                    parser, atn, decisionToDfa, predictionContexts, this, manager, monitor));
        }

        private boolean exceedsLimit(int maxDfaStates, int maxAtnConfigs,
                                     int maxPredictionContexts) {
            return predictionContexts.size() > maxPredictionContexts
                    || dfaStateCount.get() > maxDfaStates
                    || atnConfigCount.get() > maxAtnConfigs;
        }

        private void recordNewState(DFAState state, BoundedParserCacheManager manager) {
            long states = dfaStateCount.incrementAndGet();
            long configs = atnConfigCount.addAndGet(state.configs == null ? 0 : state.configs.size());
            boolean contextsExceeded = manager != null
                    && predictionContexts.size() > manager.maxPredictionContexts;
            if (manager != null && !isRetired() && (states > manager.maxDfaStates
                    || configs > manager.maxAtnConfigs || contextsExceeded)) {
                manager.rotate(this);
            }
        }
    }

    private static final class BoundedParserATNSimulator extends ParserATNSimulator {

        private final PredictionContextCanonicalizer contextCanonicalizer =
                new PredictionContextCanonicalizer();
        private final ParserCacheGeneration generation;
        private final BoundedParserCacheManager manager;
        private final IMonitor monitor;
        private int predictionCount;

        private BoundedParserATNSimulator(Parser parser, ATN atn, DFA[] decisionToDfa,
                                          PredictionContextCache sharedContextCache,
                                          ParserCacheGeneration generation,
                                          BoundedParserCacheManager manager,
                                          IMonitor monitor) {
            super(parser, atn, decisionToDfa, sharedContextCache);
            this.generation = generation;
            this.manager = manager;
            this.monitor = monitor;
        }

        @Override
        public int adaptivePredict(TokenStream input, int decision,
                                   ParserRuleContext outerContext) {
            if (predictionCount++ == 0 || (predictionCount & 0xFF) == 0) {
                checkParserCancellation(monitor);
            }
            return super.adaptivePredict(input, decision, outerContext);
        }

        @Override
        public PredictionContext getCachedContext(PredictionContext context) {
            return contextCanonicalizer.getCachedContext(context, sharedContextCache);
        }

        @Override
        protected DFAState addDFAState(DFA dfa, DFAState candidate) {
            if (candidate == ATNSimulator.ERROR) {
                return candidate;
            }

            synchronized (dfa.states) {
                DFAState existing = dfa.states.get(candidate);
                if (existing != null) {
                    if (trace_atn_sim) {
                        System.out.println("addDFAState " + candidate + " exists");
                    }
                    return existing;
                }
            }

            if (!candidate.configs.isReadonly()) {
                candidate.configs.optimizeConfigs(this);
                candidate.configs.setReadonly(true);
            }

            DFAState state;
            boolean inserted = false;
            synchronized (dfa.states) {
                state = dfa.states.get(candidate);
                if (state == null) {
                    candidate.stateNumber = dfa.states.size();
                    if (trace_atn_sim) {
                        System.out.println("addDFAState new " + candidate);
                    }
                    dfa.states.put(candidate, candidate);
                    state = candidate;
                    inserted = true;
                } else if (trace_atn_sim) {
                    System.out.println("addDFAState " + candidate + " exists");
                }
            }

            if (inserted) {
                generation.recordNewState(candidate, manager);
            }
            return state;
        }
    }

    /**
     * Parser simulator that avoids allocating a DFA and its state map for
     * grammar decisions a short-lived function-body parser never reaches.
     * Instances are parser-local, so no synchronization is required.
     */
    private static final class LazyParserATNSimulator extends ParserATNSimulator {

        private LazyParserATNSimulator(Parser parser, ATN atn, DFA[] decisionToDfa,
                                       PredictionContextCache sharedContextCache) {
            super(parser, atn, decisionToDfa, sharedContextCache);
        }

        @Override
        public int adaptivePredict(TokenStream input, int decision, ParserRuleContext outerContext) {
            if (decisionToDFA[decision] == null) {
                decisionToDFA[decision] = new DFA(atn.getDecisionState(decision), decision);
            }
            return super.adaptivePredict(input, decision, outerContext);
        }
    }

    /**
     * Creates a PostgreSQL SQL parser from input stream.
     *
     * @param is               input stream containing SQL
     * @param charset          character encoding of the stream
     * @param parsedObjectName name of the object being parsed (for error reporting)
     * @param errors           list to collect parsing errors
     * @return configured SQLParser instance
     * @throws IOException if there's an error reading the stream
     */
    public static SQLParser createSqlParser(InputStream is, String charset, String parsedObjectName,
                                            List<Object> errors) throws IOException {
        var stream = CharStreams.fromStream(is, Charset.forName(charset));
        return createSqlParser(stream, parsedObjectName, errors, 0, 0, 0);
    }

    private static SQLParser createSqlParser(CharStream stream, String parsedObjectName, List<Object> errors,
                                             int offset, int lineOffset, int inLineOffset) {
        SQLLexer lexer = new SQLLexer(stream);
        SQLParser parser = new SQLParser(new CommonTokenStream(lexer));
        ParserUtils.addErrorListener(lexer, parser, parsedObjectName, errors, offset, lineOffset, inLineOffset);
        parser.setErrorHandler(new PgCustomAntlrErrorStrategy());
        pgParserLastStart = System.currentTimeMillis();
        return parser;
    }

    /**
     * Parses PostgreSQL SQL stream asynchronously.
     *
     * @param inputStream      provider of the input stream
     * @param parsedObjectName name of the object being parsed
     * @param settings     configuration settings
     * @param monitoringLevel  level of parse tree monitoring
     * @param listener         processor for the parsed content
     * @param antlrTasks       queue for parser tasks
     */
    public static void parseSqlStream(InputStreamProvider inputStream, String parsedObjectName,
                                      ISettings settings, int monitoringLevel,
                                      IPgContextProcessor listener, Queue<AntlrTask<?>> antlrTasks) {
        List<Object> errors = settings.getErrors();
        IMonitor mon = settings.getMonitor();
        String charsetName = settings.getInCharsetName();
        AntlrTaskManager.submit(antlrTasks, () -> {
            IMonitor.checkCancelled(mon);
            List<Object> taskErrors = new ArrayList<>();
            try (InputStream stream = inputStream.getStream()) {
                var parser = createSqlParser(stream, charsetName, parsedObjectName, taskErrors);
                parser.addParseListener(new CustomParseTreeListener(
                        monitoringLevel, mon == null ? new NullMonitor() : mon));
                return new ParsedSqlTask(TwoStageAntlrParse.parse(parser, SQLParser::sql),
                        (CommonTokenStream) parser.getTokenStream(), taskErrors);
            } catch (MonitorCancelledRuntimeException mcre) {
                throw new InterruptedException();
            } catch (IOException | RuntimeException | Error ex) {
                // Preserve diagnostics already produced before a real parser or
                // stream failure. Cancellation is handled above and deliberately
                // does not publish partial diagnostics.
                errors.addAll(taskErrors);
                throw ex;
            }
        }, parsed -> {
            // Parser workers finish out of order. Publish their diagnostics only
            // when the task itself is finalized by the FIFO pipeline.
            errors.addAll(parsed.errors());
            try {
                listener.process(parsed.context(), parsed.tokens());
            } catch (UnresolvedReferenceException ex) {
                errors.add(CustomParserListener.handleUnresolvedReference(ex, parsedObjectName));
            }
        });
    }

    /**
     * Checks if parser caches need cleaning based on last usage time.
     *
     * @param cleaningInterval time interval in milliseconds after which cache should be cleaned
     */
    public static void checkToClean(long cleaningInterval) {
        checkToClean(cleaningInterval, pgParserLastStart);
    }

    private static void checkToClean(long cleaningInterval, long parserLastStart) {
        if (parserLastStart != 0 && (cleaningInterval < System.currentTimeMillis() - parserLastStart)) {
            cleanParserCache();
        }
    }

    /**
     * Clears the PostgreSQL parser cache.
     */
    // new method for cleanCacheOfAllParsers()
    public static void cleanCachePgParser() {
        if (pgParserLastStart != 0) {
            cleanParserCache();
        }
    }

    protected static void cleanParserCache() {
        Parser parser = createSqlParser(ParserUtils.SQL, ParserUtils.PARSED_OBJ_NAME, null);
        pgParserLastStart = 0;
        parser.getInterpreter().clearDFA();
    }

    public static boolean isSpecialChar(int type, int previous) {
            return previous == SQLLexer.DOT
                    || previous == SQLLexer.LEFT_PAREN
                    || previous == SQLLexer.Text_between_Dollar
                    || previous == SQLLexer.BeginDollarStringConstant
                    || type == SQLLexer.DOT
                    || type == SQLLexer.RIGHT_PAREN
                    || type == SQLLexer.Text_between_Dollar
                    || type == SQLLexer.EndDollarStringConstant
                    || type == SQLLexer.COMMA;
    }

    public static String normalizeWhitespaceUnquoted(ParserRuleContext ctx, CommonTokenStream stream) {
        return normalizeWhitespaceUnquoted(stream.getTokens(),
                ctx.getStart().getTokenIndex(), ctx.getStop().getTokenIndex());
    }

    /**
     * Normalizes a VIEW query for comparison while preserving the original
     * query text used for DDL output.
     *
     * @param ctx    parsed VIEW query context
     * @param stream token stream that produced {@code ctx}
     * @return normalized comparison text
     */
    public static String normalizeViewQueryForComparison(
            ParserRuleContext ctx, CommonTokenStream stream) {
        List<Data_typeContext> dataTypes = Trees.findAllRuleNodes(ctx, SQLParser.RULE_data_type).stream()
                .map(Data_typeContext.class::cast)
                .sorted(Comparator
                        .comparingInt((Data_typeContext type) -> type.getStart().getTokenIndex())
                        .thenComparing(Comparator.comparingInt(
                                (Data_typeContext type) -> type.getStop().getTokenIndex()).reversed()))
                .toList();

        var rewriter = new TokenStreamRewriter(stream);
        boolean changed = false;
        int previousStop = -1;
        for (Data_typeContext dataType : dataTypes) {
            int start = dataType.getStart().getTokenIndex();
            int stop = dataType.getStop().getTokenIndex();
            if (start <= previousStop) {
                continue;
            }
            previousStop = stop;

            String raw = ParserAbstract.getFullCtxText(dataType);
            String canonical = PgParserAbstract.getTypeName(dataType);
            if (!Objects.equals(raw, canonical)) {
                rewriter.replace(start, stop, canonical);
                changed = true;
            }
        }

        if (!changed) {
            return normalizeWhitespaceUnquoted(ctx, stream);
        }

        String rewritten = rewriter.getText(ctx.getSourceInterval());
        var rewrittenTokens = new CommonTokenStream(
                new SQLLexer(CharStreams.fromString(rewritten)));
        rewrittenTokens.fill();
        return normalizeWhitespaceUnquoted(
                rewrittenTokens.getTokens(), 0, rewrittenTokens.size() - 2);
    }

    /**
     * A partition bound as the comparison sees it: the same tokens with
     * canonical spacing, and every word that is not a quoted identifier or a
     * string raised to upper case.
     * <p>
     * The two sides of the comparison state the same bound in different words.
     * A project file states it as its author typed it - and the examples in the
     * PostgreSQL manual type {@code FOR VALUES WITH (MODULUS 4, REMAINDER 0)} -
     * while {@code pg_get_expr} renders the parsed node in the server's own
     * hand: {@code modulus}/{@code remainder} in lower case, {@code ", "}
     * between list items. Compared as written, the two never met, and every run
     * detached the partition and attached it again - a window with no partition
     * and a validating scan, on every deployment.
     * <p>
     * Both sides pass through this method, so the folding cannot favour either.
     * Only the case of unquoted words moves, and an unquoted word in a bound is
     * a keyword, a type name or a boolean - all of them case-insensitive to the
     * server. Quoted identifiers and string constants are left exactly as they
     * are, so {@code IN ('a')} and {@code IN ('A')} stay the different bounds
     * they are.
     * <p>
     * This closes the spelling of the bound and not its values. The server
     * renders a constant from the parsed datum, and that rendering is not
     * reachable from the text: measured on 17.10, {@code FROM (1.5) TO (2)} of a
     * {@code numeric} column comes back as {@code FROM (1.5) TO ('2')}, and a
     * {@code timestamptz} bound written {@code '2020-01-01 00:00'} comes back
     * {@code '2020-01-01 00:00:00+03'}. A bound whose value is written in a form
     * the server re-renders still compares unequal; the honest fix for that one
     * needs the server's own reading of the datum, which the model does not
     * have.
     *
     * @param bound the bound as written, or as {@code pg_get_expr} rendered it
     * @return the comparison text, or {@code null} for a null bound
     */
    public static String normalizePartitionBound(String bound) {
        if (bound == null) {
            return null;
        }
        var tokens = new CommonTokenStream(new SQLLexer(CharStreams.fromString(bound)));
        tokens.fill();
        // the last token is EOF, which carries no text of its own
        return normalizeTokens(tokens.getTokens(), 0, tokens.size() - 2, true);
    }

    private static String normalizeWhitespaceUnquoted(List<Token> tokens, int start, int stop) {
        return normalizeTokens(tokens, start, stop, false);
    }

    private static String normalizeTokens(List<Token> tokens, int start, int stop, boolean upperUnquoted) {
        StringBuilder sb = new StringBuilder();

        // skip space before first token
        int previous = SQLLexer.DOT;

        for (int i = start; i <= stop; i++) {
            Token token  = tokens.get(i);
            // skip tokens from non default channel
            if (token.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            int type = token.getType();

            // remove whitespace after and before some special characters for PG and CH
            if (!isSpecialChar(type, previous)) {
                sb.append(' ');
            }
            sb.append(upperUnquoted ? getUpperTokenText(type, token) : getTokenText(type, token));
            previous = type;
        }
        return sb.toString();
    }

    private static String getUpperTokenText(int type, Token token) {
        String text = getTokenText(type, token);
        return isQuotedToken(type) ? text : text.toUpperCase(Locale.ROOT);
    }

    private static boolean isQuotedToken(int type) {
        return type == SQLLexer.QuotedIdentifier
                || type == SQLLexer.UnicodeQuotedIdentifier
                || type == SQLLexer.StringConstant
                || type == SQLLexer.UnicodeEscapeStringConstant;
    }

    public static String getTokenText(int type, Token token) {
        if (type == SQLLexer.QuotedIdentifier
                // FIXME test this
                // || type == SQLLexer.UnicodeQuotedIdentifier
                // || type == SQLLexer.UnicodeEscapeStringConstant
                || type == SQLLexer.StringConstant) {
            // get text with quotes
            return token.getInputStream().getText(Interval.of(token.getStartIndex(), token.getStopIndex()));
        }

        if (SQLLexer.ALL <= type && type <= SQLLexer.WITH) {
            // upper case reserved keywords
            return token.getText().toUpperCase(Locale.ROOT);
        }

        return token.getText();
    }

    /**
     * Removes INTO statements from SQL tokens that aren't part of PL/pgSQL INTO clauses.
     * Handles special cases for INSERT INTO and IMPORT FOREIGN SCHEMA INTO.
     * <p>
     * Because INTO is sometimes used in the main SQL grammar, we have to be
     * careful not to take any such usage of INTO as a PL/pgSQL INTO clause.
     * There are currently three such cases:
     * <p>
     * 1. SELECT ... INTO.  We don't care, we just override that with the
     * PL/pgSQL definition.
     * <p>
     * 2. INSERT INTO.  This is relatively easy to recognize since the words
     * must appear adjacently; but we can't assume INSERT starts the command,
     * because it can appear in CREATE RULE or WITH.  Unfortunately, INSERT is
     * *not* fully reserved, so that means there is a chance of a false match,
     * but it's not very likely.
     * <p>
     * 3. IMPORT FOREIGN SCHEMA ... INTO.  This is not allowed in CREATE RULE
     * or WITH, so we just check for IMPORT as the command's first token.
     * (If IMPORT FOREIGN SCHEMA returned data someone might wish to capture
     * with an INTO-variables clause, we'd have to work much harder here.)
     * <p>
     * See <a href="https://github.com/postgres/postgres/blob/master/src/pl/plpgsql/src/pl_gram.y">pl_gram.y</a>
     */
    public static void removeIntoStatements(Parser parser) {
        removeIntoStatements(parser, new NullMonitor());
    }

    /**
     * Removes PL/pgSQL INTO clauses while periodically observing cancellation
     * during the token-drain pass.
     *
     * @param parser  parser whose token stream is adjusted
     * @param monitor operation monitor
     */
    public static void removeIntoStatements(Parser parser, IMonitor monitor) {
        CommonTokenStream stream = (CommonTokenStream) parser.getTokenStream();

        boolean isImport = false;
        int i = 0;

        while (true) {
            if ((i & 0xFF) == 0) {
                checkParserCancellation(monitor);
            }
            stream.seek(i++);
            int type = stream.LA(1);

            switch (type) {
            case Recognizer.EOF:
                stream.seek(0);
                parser.setInputStream(stream);
                checkParserCancellation(monitor);
                return;
            case SQLLexer.SEMI_COLON:
                isImport = false;
                break;
            case SQLLexer.IMPORT:
                if (stream.LA(2) == SQLLexer.FOREIGN && stream.LA(3) == SQLLexer.SCHEMA) {
                    isImport = true;
                }
                break;
            case SQLLexer.INTO:
                if (isImport || stream.LA(-1) == SQLLexer.INSERT
                || stream.LA(-1) == SQLLexer.MERGE) {
                    break;
                }
                hideIntoTokens(stream, monitor);
                break;
            default:
                break;
            }
        }
    }

    private static void hideIntoTokens(CommonTokenStream stream, IMonitor monitor) {
        checkParserCancellation(monitor);
        int i = 1;
        int nextType = stream.LA(++i); // into

        if (nextType == SQLLexer.STRICT) {
            nextType = stream.LA(++i); // strict
        }

        if (isIdentifier(nextType)) {
            nextType = stream.LA(++i); // identifier
            int scanSteps = 0;

            while ((nextType == SQLLexer.DOT || nextType == SQLLexer.COMMA)
                    && isIdentifier(stream.LA(i + 1))) {
                i += 2; // comma or dot + identifier
                nextType = stream.LA(i);
                if ((++scanSteps & 0x7F) == 0) {
                    checkParserCancellation(monitor);
                }
            }
            checkParserCancellation(monitor);

            // hide from end, because LT(p) skips hidden tokens
            int hidden = 0;
            for (int p = i - 1; p > 0; p--) {
                ((CommonToken) stream.LT(p)).setChannel(Token.HIDDEN_CHANNEL);
                if ((++hidden & 0xFF) == 0) {
                    checkParserCancellation(monitor);
                }
            }
        }
        checkParserCancellation(monitor);
    }

    private static boolean isIdentifier(int type) {
        return SQLLexer.ABORT <= type && type <= SQLLexer.WHILE
                || type == SQLLexer.Identifier || type == SQLLexer.QuotedIdentifier;
    }

    /**
     * Parses a PostgreSQL qualified name into its components.
     *
     * @param schemaQualifiedName the qualified name string to parse
     * @return QNameParser instance containing parsed components
     */
    public static QNameParser<ParserRuleContext> parseQName(String schemaQualifiedName) {
        List<Object> errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(schemaQualifiedName, "qname: " + schemaQualifiedName, errors);
        var parts = PgParserAbstract.getIdentifiers(parser.qname_parser().schema_qualified_name());
        return new QNameParser<>(parts, errors);
    }

    /**
     * Parses a PostgreSQL data type, including multiword names, type modifiers,
     * and array decorations.
     *
     * @param dataType the data type string to parse
     * @return parsed data type context, or {@code null} if parsing failed
     */
    public static Data_typeContext parseDataType(String dataType) {
        List<Object> errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(dataType, "data type: " + dataType, errors);
        var parsed = parser.data_type_eof();
        return errors.isEmpty() && parsed != null ? parsed.data_type() : null;
    }

    /**
     * Parses a PostgreSQL operator name into its components.
     *
     * @param schemaQualifiedName the operator name string to parse
     * @return QNameParser instance containing parsed components
     */
    public static QNameParser<ParserRuleContext> parsePgOperator(String schemaQualifiedName) {
        List<Object> errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(schemaQualifiedName, "qname: " + schemaQualifiedName, errors);
        var parts = PgParserAbstract.getIdentifiers(parser.operator_args_parser().operator_name());
        return new QNameParser<>(parts, errors);
    }

    /**
     * Creates a wrapper for parsing PostgreSQL qualified names.
     *
     * @param fullName the qualified name string to parse (e.g. "schema.table")
     * @return wrapper containing parsed name components
     */
    public static QNameParserWrapper wrapParsedQName(String fullName) {
        return new QNameParserWrapper(parseQName(fullName));
    }

    /**
     * Creates a wrapper for parsing PostgreSQL operator names.
     *
     * @param fullName the operator name string to parse
     * @return wrapper containing parsed name components
     */
    public static QNameParserWrapper wrapParsedPgOperator(String fullName) {
        return new QNameParserWrapper(parsePgOperator(fullName));
    }

    private PgParserUtils() {
    }
}
