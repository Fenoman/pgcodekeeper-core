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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.ANTLRErrorStrategy;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the classic ANTLR two-stage parse strategy.
 * <p>
 * The first stage runs the entry rule with {@link PredictionMode#SLL} and a
 * {@link BailErrorStrategy}: SLL prediction is dramatically cheaper than full
 * LL simulation and, per the documented ANTLR guarantee, produces the exact
 * same parse tree whenever it completes without a syntax error. Almost all
 * valid SQL therefore never pays the full LL price.
 * <p>
 * When the SLL stage bails out, the input is re-parsed from the same token
 * stream with the parser's original configuration (full LL prediction, the
 * original error strategy and error listeners), so diagnostics for invalid
 * input are identical to a single-stage parse. Parser error listeners are
 * detached during the SLL stage so the bailing pass never publishes partial
 * diagnostics; lexer listeners stay attached because tokens are produced only
 * once and their diagnostics would otherwise be lost.
 */
public final class TwoStageAntlrParse {

    private static final Logger LOG = LoggerFactory.getLogger(TwoStageAntlrParse.class);

    private static final AtomicLong SLL_SUCCESS_COUNT = new AtomicLong();
    private static final AtomicLong LL_FALLBACK_COUNT = new AtomicLong();
    // Test-only process-wide override. Volatile publication is required because
    // project parsing continues on ANTLR worker threads.
    private static volatile Strategy strategy = Strategy.TWO_STAGE;

    enum Strategy {
        LL_ONLY,
        TWO_STAGE
    }

    /**
     * Parses the entry rule with SLL prediction first and re-parses with the
     * parser's original configuration on an SLL syntax bailout.
     * <p>
     * The prediction mode is applied to the parser's current interpreter, so
     * the strategy composes with custom {@code ParserATNSimulator}
     * replacements installed after parser creation. Token channel adjustments
     * (for example hidden PL/pgSQL INTO clauses) persist across the internal
     * {@link Parser#reset()} because tokens are lexed only once.
     *
     * @param <P>       parser type
     * @param <R>       parse result type
     * @param parser    fully configured parser positioned at the stream start
     * @param entryRule entry rule invocation, optionally with pure tree
     *                  navigation of the returned context
     * @return parse result of the entry rule
     */
    public static <P extends Parser, R> R parse(P parser, Function<P, R> entryRule) {
        if (strategy == Strategy.LL_ONLY) {
            return entryRule.apply(parser);
        }

        SavedConfiguration saved = SavedConfiguration.capture(parser);
        parser.removeErrorListeners();
        parser.setErrorHandler(new BailErrorStrategy());
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
        try {
            R result = entryRule.apply(parser);
            SLL_SUCCESS_COUNT.incrementAndGet();
            return result;
        } catch (ParseCancellationException ex) {
            // cancellation must never be downgraded to a syntax fallback;
            // everything else (monitor and interrupt failures included)
            // propagates on its own because only the bail strategy wraps
            // syntax errors into ParseCancellationException
            rethrowCancellationCause(ex);
            LL_FALLBACK_COUNT.incrementAndGet();
            logFallback(parser);
            parser.reset();
        } finally {
            saved.restore(parser);
        }

        return entryRule.apply(parser);
    }

    /**
     * Returns how many entry rules completed in the cheap SLL stage.
     *
     * @return total SLL-stage successes in this JVM
     */
    public static long getSllSuccessCount() {
        return SLL_SUCCESS_COUNT.get();
    }

    /**
     * Returns how many entry rules required the full LL re-parse.
     *
     * @return total LL fallbacks in this JVM
     */
    public static long getLlFallbackCount() {
        return LL_FALLBACK_COUNT.get();
    }

    /**
     * Selects the parser strategy for isolated parity tests. There is no
     * production setting for this process-wide override.
     */
    static void setStrategyForTests(Strategy testStrategy) {
        strategy = Objects.requireNonNull(testStrategy);
    }

    private static void rethrowCancellationCause(ParseCancellationException ex) {
        if (ex.getCause() instanceof MonitorCancelledRuntimeException cancellation) {
            throw cancellation;
        }
    }

    private static void logFallback(Parser parser) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("SLL prediction failed for {}, re-parsing with full LL (fallbacks so far: {})",
                    parser.getInputStream().getSourceName(), LL_FALLBACK_COUNT.get());
        }
    }

    /**
     * Original parser configuration replaced for the duration of the SLL
     * stage. Parse listeners are intentionally not captured: they survive
     * both stages (including {@link Parser#reset()}) and keep providing
     * progress reporting and cooperative cancellation.
     */
    private record SavedConfiguration(List<ANTLRErrorListener> errorListeners,
                                      ANTLRErrorStrategy errorHandler,
                                      PredictionMode predictionMode) {

        private static SavedConfiguration capture(Parser parser) {
            return new SavedConfiguration(List.copyOf(parser.getErrorListeners()),
                    parser.getErrorHandler(),
                    parser.getInterpreter().getPredictionMode());
        }

        private void restore(Parser parser) {
            parser.setErrorHandler(errorHandler);
            for (ANTLRErrorListener listener : errorListeners) {
                parser.addErrorListener(listener);
            }
            parser.getInterpreter().setPredictionMode(predictionMode);
        }
    }

    private TwoStageAntlrParse() {
    }
}
