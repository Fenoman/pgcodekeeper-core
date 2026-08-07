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
package org.pgcodekeeper.core.database.pg.parser.launcher;

import java.util.*;

import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.base.parser.TwoStageAntlrParse;
import org.pgcodekeeper.core.database.base.parser.launcher.DeferredAnalysisStateException;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.expr.*;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.*;
import org.pgcodekeeper.core.database.pg.routine.DeferredRoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.OwnedRoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyAnalysisStats;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCandidate;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.RoutineIdentity;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.MonitorInvocationException;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.Pair;

/**
 * Launcher for analyzing PostgreSQL function and procedure bodies.
 * Handles SQL, function body and PL/pgSQL function contexts with argument namespace support.
 */
public final class PgFuncProcAnalysisLauncher extends PgAbstractAnalysisLauncher {

    /** Grammar entry used to materialize a deferred function body. */
    public enum BodyType {
        SQL,
        PLPGSQL,
        FUNCTION_BODY
    }

    /** Controls whether a deferred body reparse contributes parser diagnostics. */
    public enum ParseDiagnosticPolicy {
        REPORT,
        SUPPRESS_DUPLICATE
    }

    /** Result of a deferred-body skip attempt. */
    public enum BodySkipOutcome {
        /** The launcher stays runnable and its body must be analyzed. */
        ANALYZED,
        /** Skipped because hash-first proved the body byte-identical. */
        SKIPPED_MATCHED,
        /** Skipped because the body belongs to the old comparison side. */
        SKIPPED_OLD_SIDE
    }

    /**
     * Contains pairs, each of which contains the name of the function argument
     * and its full type name in ObjectReference object
     * Used to set up namespace for function body analysis.
     */
    private final List<Pair<String, ObjectReference>> funcArgs;
    private final boolean isEnableFunctionBodiesDependencies;
    private final BodyType bodyType;
    private final ParseDiagnosticPolicy diagnosticPolicy;
    private final String parsedObjectName;
    private final long estimatedParseBytes;
    private boolean statementRetargeted;
    private boolean skipMatchedBodyAnalysisEnabled;
    private boolean skipOldSideBodyAnalysisEnabled;
    private RoutineBodySource bodySource;

    /**
     * Creates a function/procedure analyzer for SQL context.
     *
     * @param stmt                               the function/procedure statement
     * @param ctx                                the SQL context to analyze
     * @param location                           the source location identifier
     * @param funcArgs                           list of function arguments
     * @param isEnableFunctionBodiesDependencies flag to control function body dependency collection
     */
    public PgFuncProcAnalysisLauncher(PgAbstractFunction stmt, SqlContext ctx,
                                      String location, List<Pair<String, ObjectReference>> funcArgs, boolean isEnableFunctionBodiesDependencies) {
        super(stmt, ctx, location);
        this.funcArgs = List.copyOf(funcArgs);
        this.isEnableFunctionBodiesDependencies = isEnableFunctionBodiesDependencies;
        this.bodyType = null;
        this.diagnosticPolicy = null;
        this.parsedObjectName = null;
        this.estimatedParseBytes = 0;
        this.bodySource = null;
    }

    /**
     * Creates a function/procedure analyzer for function body context.
     *
     * @param stmt                               the function/procedure statement
     * @param ctx                                the function body context to analyze
     * @param location                           the source location identifier
     * @param funcArgs                           list of function arguments
     * @param isEnableFunctionBodiesDependencies flag to control function body dependency collection
     */
    public PgFuncProcAnalysisLauncher(PgAbstractFunction stmt, Function_bodyContext ctx,
                                      String location, List<Pair<String, ObjectReference>> funcArgs, boolean isEnableFunctionBodiesDependencies) {
        super(stmt, ctx, location);
        this.funcArgs = List.copyOf(funcArgs);
        this.isEnableFunctionBodiesDependencies = isEnableFunctionBodiesDependencies;
        this.bodyType = null;
        this.diagnosticPolicy = null;
        this.parsedObjectName = null;
        this.estimatedParseBytes = 0;
        this.bodySource = null;
    }

    /**
     * Creates a function/procedure analyzer for PL/pgSQL context.
     *
     * @param stmt                               the function/procedure statement
     * @param ctx                                the PL/pgSQL function context to analyze
     * @param location                           the source location identifier
     * @param funcArgs                           list of function arguments
     * @param isEnableFunctionBodiesDependencies flag to control function body dependency collection
     */
    public PgFuncProcAnalysisLauncher(PgAbstractFunction stmt, Plpgsql_functionContext ctx,
                                      String location, List<Pair<String, ObjectReference>> funcArgs, boolean isEnableFunctionBodiesDependencies) {
        super(stmt, ctx, location);
        this.funcArgs = List.copyOf(funcArgs);
        this.isEnableFunctionBodiesDependencies = isEnableFunctionBodiesDependencies;
        this.bodyType = null;
        this.diagnosticPolicy = null;
        this.parsedObjectName = null;
        this.estimatedParseBytes = 0;
        this.bodySource = null;
    }

    /**
     * Creates a compact descriptor that parses its body immediately before
     * dependency analysis, after the database metadata has been built.
     *
     * @param stmt                               function/procedure statement
     * @param definition                         unquoted body text
     * @param bodyType                           grammar entry used for the body
     * @param parsedObjectName                   source address reported as the
     *                                           file path of every deferred parse
     *                                           error; must address the same
     *                                           source as {@code location}
     * @param location                           source location identifier
     * @param funcArgs                           function argument namespace
     * @param isEnableFunctionBodiesDependencies function dependency flag
     */
    public PgFuncProcAnalysisLauncher(PgAbstractFunction stmt, String definition, BodyType bodyType,
                                      String parsedObjectName, String location,
                                      List<Pair<String, ObjectReference>> funcArgs,
                                      boolean isEnableFunctionBodiesDependencies) {
        this(stmt, OwnedRoutineBodySource.analysisOnly(definition, definition),
                bodyType, parsedObjectName, location, funcArgs,
                isEnableFunctionBodiesDependencies,
                bodyType == BodyType.FUNCTION_BODY
                        ? ParseDiagnosticPolicy.SUPPRESS_DUPLICATE
                        : ParseDiagnosticPolicy.REPORT);
    }

    /**
     * Creates a compact descriptor backed by a one-shot body source.
     * Ownership transfers to the launcher only after successful construction.
     */
    public PgFuncProcAnalysisLauncher(PgAbstractFunction stmt, RoutineBodySource bodySource,
                                      BodyType bodyType, String parsedObjectName, String location,
                                      List<Pair<String, ObjectReference>> funcArgs,
                                      boolean isEnableFunctionBodiesDependencies) {
        this(stmt, bodySource, bodyType, parsedObjectName, location, funcArgs,
                isEnableFunctionBodiesDependencies,
                bodyType == BodyType.FUNCTION_BODY
                        ? ParseDiagnosticPolicy.SUPPRESS_DUPLICATE
                        : ParseDiagnosticPolicy.REPORT);
    }

    /**
     * Creates a compact descriptor with an explicit parser-diagnostic policy.
     * JDBC catalog bodies have no enclosing parser and therefore report their
     * own diagnostics, while project statement bodies suppress a duplicate
     * diagnostic already emitted by the enclosing CREATE statement.
     *
     * @param stmt                               function/procedure statement
     * @param definition                         unquoted body text
     * @param bodyType                           grammar entry used for the body
     * @param parsedObjectName                   source address reported as the
     *                                           file path of every deferred parse
     *                                           error; must address the same
     *                                           source as {@code location}
     * @param location                           source location identifier
     * @param funcArgs                           function argument namespace
     * @param isEnableFunctionBodiesDependencies function dependency flag
     * @param diagnosticPolicy                   deferred parser diagnostic policy
     */
    public PgFuncProcAnalysisLauncher(PgAbstractFunction stmt, String definition, BodyType bodyType,
                                      String parsedObjectName, String location,
                                      List<Pair<String, ObjectReference>> funcArgs,
                                      boolean isEnableFunctionBodiesDependencies,
                                      ParseDiagnosticPolicy diagnosticPolicy) {
        this(stmt, OwnedRoutineBodySource.analysisOnly(definition, definition),
                bodyType, parsedObjectName, location, funcArgs,
                isEnableFunctionBodiesDependencies, diagnosticPolicy);
    }

    /**
     * Creates a compact descriptor with an explicit one-shot source and parser
     * diagnostic policy. The caller retains source ownership if validation
     * fails before this constructor returns.
     */
    public PgFuncProcAnalysisLauncher(PgAbstractFunction stmt, RoutineBodySource bodySource,
                                      BodyType bodyType, String parsedObjectName, String location,
                                      List<Pair<String, ObjectReference>> funcArgs,
                                      boolean isEnableFunctionBodiesDependencies,
                                      ParseDiagnosticPolicy diagnosticPolicy) {
        super(stmt, location);
        this.bodyType = Objects.requireNonNull(bodyType);
        this.diagnosticPolicy = Objects.requireNonNull(diagnosticPolicy);
        this.parsedObjectName = Objects.requireNonNull(parsedObjectName);
        this.funcArgs = List.copyOf(funcArgs);
        this.isEnableFunctionBodiesDependencies = isEnableFunctionBodiesDependencies;
        RoutineBodySource source = Objects.requireNonNull(bodySource, "bodySource");
        long estimate = source.estimatedUtf8Bytes();
        if (estimate < 0) {
            throw new IllegalArgumentException("Routine body estimate must be nonnegative");
        }
        this.estimatedParseBytes = estimate;
        this.bodySource = source;
    }

    @Override
    public long getEstimatedParseBytes() {
        return estimatedParseBytes;
    }

    @Override
    protected ParserRuleContext takeAnalysisContext(List<Object> errors, IMonitor monitor)
            throws InterruptedException {
        if (bodyType == null) {
            return super.takeAnalysisContext(errors, monitor);
        }

        RoutineBodySource source = detachBodySource();
        if (source == null) {
            throw new DeferredAnalysisStateException("Routine body source is unavailable");
        }

        Throwable failure = null;
        try {
            String currentDefinition = source.take().raw();
            PgRoutineBodyAnalysisStats.recordParsed();
            IMonitor parserMonitor = createMonitorInvocationTransport(monitor);
            PgParserUtils.checkParserCancellation(parserMonitor);
            List<Object> parseErrors = new ArrayList<>();
            var parser = PgParserUtils.createBoundedSqlParser(
                    currentDefinition, parsedObjectName, parseErrors,
                    getOffset(), getLineOffset(), getInLineOffset(), parserMonitor);
            if (bodyType == BodyType.FUNCTION_BODY
                    && diagnosticPolicy == ParseDiagnosticPolicy.SUPPRESS_DUPLICATE) {
                // The containing CREATE statement already reported syntax errors for
                // an in-statement body. This local reparse exists only to build the
                // short-lived analysis tree, so reporting it again would duplicate
                // diagnostics under a synthetic location.
                parser.removeErrorListeners();
                ((Lexer) parser.getTokenStream().getTokenSource()).removeErrorListeners();
            }
            PgParserUtils.checkParserCancellation(parserMonitor);
            ParserRuleContext context = switch (bodyType) {
                case SQL -> TwoStageAntlrParse.parse(parser, SQLParser::sql);
                case PLPGSQL -> {
                    PgParserUtils.removeIntoStatements(parser, parserMonitor);
                    yield TwoStageAntlrParse.parse(parser, SQLParser::plpgsql_function);
                }
                case FUNCTION_BODY -> TwoStageAntlrParse.parse(parser, SQLParser::function_body);
            };
            PgParserUtils.checkParserCancellation(parserMonitor);
            appendErrorsWithCancellation(errors, parseErrors, parserMonitor);
            return context;
        } catch (InterruptedException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            closeBodySource(source, failure);
        }
    }

    @Override
    protected void releaseDeferredAnalysisState() {
        RoutineBodySource source = detachBodySource();
        try {
            if (source != null) {
                closeBodySource(source, null);
            }
        } finally {
            super.releaseDeferredAnalysisState();
        }
    }

    private synchronized RoutineBodySource detachBodySource() {
        RoutineBodySource source = bodySource;
        bodySource = null;
        return source;
    }

    /**
     * Decides whether a body of the given grammar entry may skip its deferred
     * parse and dependency analysis under the supplied settings. The same
     * eligibility matrix gates the matched skip on both comparison sides and
     * the unconditional old-side skip of database bodies. Only late-bound
     * bodies qualify: the server stores them opaquely and derives no
     * dependencies from their text, so PL/pgSQL always qualifies while a
     * quoted SQL body qualifies only when function body checks are disabled
     * for the generated script. {@code BEGIN ATOMIC} bodies are stored parsed
     * with real server-side dependencies and never qualify. Explicit
     * function-body dependency collection also disables the skip.
     *
     * @param settings effective load settings
     * @param bodyType grammar entry used for the body
     * @return true when a body of this type may skip analysis
     */
    public static boolean isSkipMatchedBodyAnalysisEligible(
            ISettings settings, BodyType bodyType) {
        if (!settings.isPgRoutineBodySkipMatchedAnalysis()
                || settings.isEnableFunctionBodiesDependencies()) {
            return false;
        }
        return switch (bodyType) {
            case PLPGSQL -> true;
            case SQL -> settings.isDisableCheckFunctionBodies();
            case FUNCTION_BODY -> false;
        };
    }

    /**
     * Arms the launcher to skip analysis when its body turns out to be
     * byte-identical between the project and the compared database. The
     * caller must have validated eligibility via
     * {@link #isSkipMatchedBodyAnalysisEligible}.
     */
    public synchronized void enableSkipMatchedBodyAnalysis() {
        if (bodyType != BodyType.SQL && bodyType != BodyType.PLPGSQL) {
            throw new IllegalStateException(
                    "Only deferred SQL and PL/pgSQL bodies may skip matched analysis");
        }
        skipMatchedBodyAnalysisEnabled = true;
    }

    /**
     * Keeps a captured project model complete even when its body was shared
     * with a matching JDBC consumer.
     */
    public synchronized void disableSkipMatchedBodyAnalysis() {
        skipMatchedBodyAnalysisEnabled = false;
    }

    /**
     * Arms the launcher to skip analysis unconditionally because its body was
     * loaded from the database model that acts as the OLD side of a
     * comparison. The server already accepted the body, so a syntax reparse
     * is pointless, and late-bound old-side bodies contribute no needed
     * dependencies. The caller must have validated eligibility via
     * {@link #isSkipMatchedBodyAnalysisEligible} and the old-side role of the
     * loaded model.
     */
    public synchronized void enableSkipOldSideBodyAnalysis() {
        if (bodyType != BodyType.SQL && bodyType != BodyType.PLPGSQL) {
            throw new IllegalStateException(
                    "Only deferred SQL and PL/pgSQL bodies may skip old-side analysis");
        }
        skipOldSideBodyAnalysisEnabled = true;
    }

    /**
     * Skips the deferred parse and dependency analysis when armed and the
     * body is proven byte-identical by the hash-first exchange. A successful
     * skip consumes and releases the body source, so the launcher must not be
     * run afterwards. Every non-matched, non-armed or already-released state
     * answers {@code false} and leaves the launcher runnable (fail-open).
     *
     * @return true when this launcher's analysis was skipped
     */
    public synchronized boolean skipMatchedBodyAnalysis() {
        if (!skipMatchedBodyAnalysisEnabled || statementRetargeted
                || (bodyType != BodyType.SQL && bodyType != BodyType.PLPGSQL)
                || !isBodySourceMatched()) {
            return false;
        }
        consumeSkippedBodySource();
        PgRoutineBodyAnalysisStats.recordSkipped(estimatedParseBytes);
        return true;
    }

    /**
     * Skips the deferred parse and dependency analysis when any armed skip
     * applies: a hash-first match skips on both comparison sides, and an
     * old-side arming skips regardless of the match verdict. A successful
     * skip consumes and releases the body source and suppresses the
     * dependency-driven alter ceremony of the statement, because its
     * dependency set deliberately excludes body-derived entries. Every
     * non-armed, non-applicable or already-released state answers
     * {@link BodySkipOutcome#ANALYZED} and leaves the launcher runnable
     * (fail-open).
     *
     * @return classification of the performed skip for accounting
     */
    public synchronized BodySkipOutcome skipBodyAnalysis() {
        if (statementRetargeted
                || (bodyType != BodyType.SQL && bodyType != BodyType.PLPGSQL)
                || bodySource == null) {
            return BodySkipOutcome.ANALYZED;
        }
        boolean matched = isBodySourceMatched();
        if (matched && (skipMatchedBodyAnalysisEnabled || skipOldSideBodyAnalysisEnabled)) {
            consumeSkippedBodySource();
            PgRoutineBodyAnalysisStats.recordSkipped(estimatedParseBytes);
            return BodySkipOutcome.SKIPPED_MATCHED;
        }
        if (skipOldSideBodyAnalysisEnabled) {
            consumeSkippedBodySource();
            PgRoutineBodyAnalysisStats.recordSkippedOldSide(estimatedParseBytes);
            return BodySkipOutcome.SKIPPED_OLD_SIDE;
        }
        return BodySkipOutcome.ANALYZED;
    }

    private boolean isBodySourceMatched() {
        if (bodySource instanceof DeferredRoutineBodySource deferred) {
            return deferred.isResolvedByProjectMatch();
        }
        if (bodySource instanceof OwnedRoutineBodySource owned) {
            return owned.isSharedWithMatchedConsumer();
        }
        return false;
    }

    private void consumeSkippedBodySource() {
        RoutineBodySource source = detachBodySource();
        closeBodySource(source, null);
        if (getStmt() instanceof PgAbstractFunction function) {
            function.suppressBodyDependencyState();
        }
    }

    /**
     * Returns whether this launcher defers a routine-body parse to analysis
     * time, i.e. participates in the skipped/parsed body accounting.
     */
    public boolean isDeferredBodyAnalysis() {
        return bodyType != null;
    }

    /**
     * Returns a no-allocation upper-bound predicate used to size the final
     * project catalog without counting stale library or analysis-only bodies.
     */
    public synchronized boolean isPotentialProjectRoutineBodyCandidate() {
        return isPotentialProjectRoutineBodyCandidateUnsafe();
    }

    /**
     * Returns a zero-copy catalog entry only when this launcher's statement and
     * canonical body are still the exact objects in the final project model.
     */
    public synchronized ProjectRoutineBodyCandidate projectRoutineBodyCandidate(
            org.pgcodekeeper.core.database.pg.schema.PgDatabase finalDatabase) {
        Objects.requireNonNull(finalDatabase, "finalDatabase");
        if (!isPotentialProjectRoutineBodyCandidateUnsafe()) {
            return null;
        }
        OwnedRoutineBodySource source = (OwnedRoutineBodySource) bodySource;

        PgAbstractFunction routine = (PgAbstractFunction) getStmt();
        RoutineIdentity identity;
        try {
            identity = RoutineIdentity.from(routine);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        PgSchema finalSchema = finalDatabase.getSchema(identity.schemaName());
        if (finalSchema == null
                || finalSchema.getFunction(identity.signature()) != routine) {
            return null;
        }
        return source.projectCandidate(identity, routine);
    }

    private boolean isPotentialProjectRoutineBodyCandidateUnsafe() {
        return !statementRetargeted
                && (bodyType == BodyType.SQL || bodyType == BodyType.PLPGSQL)
                && bodySource instanceof OwnedRoutineBodySource source
                && source.isProjectCandidateAvailable();
    }

    /**
     * Records whether a library merge replaced this launcher's parser
     * statement with a final-model twin, without retaining the library origin
     * and its parent object graph.
     */
    @Override
    public synchronized void updateStmt(IDatabase database) {
        IStatement before = getStmt();
        super.updateStmt(database);
        statementRetargeted |= getStmt() != before;
    }

    private static void closeBodySource(RoutineBodySource source, Throwable primary) {
        try {
            source.close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primary == null) {
                if (cleanupFailure instanceof Error error) {
                    throw error;
                }
                throw new DeferredAnalysisStateException(
                        "Failed to release routine body source", cleanupFailure);
            }

            Throwable effectivePrimary = primary instanceof MonitorInvocationException carrier
                    ? carrier.getFailure()
                    : primary;
            if (effectivePrimary != cleanupFailure) {
                effectivePrimary.addSuppressed(new DeferredAnalysisStateException(
                        "Failed to release routine body source", cleanupFailure));
            }
            if (!isHardAnalysisFailure(primary)) {
                throw new DeferredAnalysisStateException(
                        "Routine body source cleanup failed while handling analysis failure",
                        primary);
            }
        }
    }

    private static boolean isHardAnalysisFailure(Throwable failure) {
        return failure instanceof InterruptedException
                || failure instanceof DeferredAnalysisStateException
                || failure instanceof MonitorInvocationException
                || failure instanceof MonitorCancelledRuntimeException
                || failure instanceof Error;
    }

    @Override
    public Set<ObjectLocation> analyze(ParserRuleContext ctx, IMetaContainer meta) {
        if (ctx instanceof SqlContext sqlCtx) {
            PgSql sql = new PgSql(meta);
            declareAnalyzerArgs(sql);
            return analyze(sqlCtx, sql);
        }

        if (ctx instanceof Function_bodyContext bodyCtx) {
            PgSqlFunctionBody body = new PgSqlFunctionBody(meta);
            declareAnalyzerArgs(body);
            return analyze(bodyCtx, body);
        }

        PgFunctionExp function = new PgFunctionExp(meta);
        declareAnalyzerArgs(function);
        return analyze((Plpgsql_functionContext) ctx, function);
    }

    private void declareAnalyzerArgs(PgAbstractExprWithNmspc<? extends ParserRuleContext> analyzer) {
        for (int i = 0; i < funcArgs.size(); i++) {
            Pair<String, ObjectReference> arg = funcArgs.get(i);
            analyzer.declareNamespaceVar("$" + (i + 1), arg.getFirst(), arg.getSecond());
        }
    }

    @Override
    protected EnumSet<DbObjType> getDisabledDepcies() {
        if (!isEnableFunctionBodiesDependencies) {
            return EnumSet.of(DbObjType.FUNCTION, DbObjType.PROCEDURE);
        }

        return super.getDisabledDepcies();
    }
}
