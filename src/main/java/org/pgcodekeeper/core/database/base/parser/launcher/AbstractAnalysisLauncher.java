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
package org.pgcodekeeper.core.database.base.parser.launcher;

import java.util.*;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.base.parser.*;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.exception.*;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.MonitorInvocationException;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class and all child classes contains statement, its contexts and
 * implementation of logic for launch the analysis of statement's contexts.
 */
public abstract class AbstractAnalysisLauncher implements IAnalysisLauncher {

    private static final int ANALYSIS_PUBLICATION_BATCH_SIZE = 256;

    private static final Logger LOG = LoggerFactory.getLogger(AbstractAnalysisLauncher.class);
    private static final ClassValue<Boolean> LEGACY_ANALYZE_OVERRIDE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("launchAnalyze", List.class, IMetaContainer.class)
                        .getDeclaringClass() != AbstractAnalysisLauncher.class;
            } catch (NoSuchMethodException ex) {
                throw new AssertionError("IAnalysisLauncher compatibility method is missing", ex);
            }
        }
    };
    private static final ClassValue<Boolean> MONITORED_ANALYZE_OVERRIDE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("launchAnalyze", List.class, IMetaContainer.class, IMonitor.class)
                        .getDeclaringClass() != AbstractAnalysisLauncher.class;
            } catch (NoSuchMethodException ex) {
                throw new AssertionError("IAnalysisLauncher monitored method is missing", ex);
            }
        }
    };

    private final List<ObjectLocation> references = new ArrayList<>();
    private boolean collectReferences = true;

    protected IStatement stmt;
    private ParserRuleContext ctx;
    private final String location;

    private int offset;
    private int lineOffset;
    private int inLineOffset;

    protected AbstractAnalysisLauncher(AbstractStatement stmt,
                                       ParserRuleContext ctx, String location) {
        this.stmt = stmt;
        this.ctx = ctx;
        if (ctx != null) {
            ctx.setParent(null);
        }
        this.location = location;
    }

    /**
     * Creates a launcher whose parser context will be materialized locally when
     * analysis starts.
     *
     * @param stmt     statement that owns the analyzed expression
     * @param location source location identifier
     */
    protected AbstractAnalysisLauncher(AbstractStatement stmt, String location) {
        this.stmt = stmt;
        this.location = location;
    }

    @Override
    public IStatement getStmt() {
        return stmt;
    }

    /**
     * Gets the schema name for the statement if available.
     *
     * @return schema name or null if not applicable
     */
    @Override
    public String getSchemaName() {
        if (stmt instanceof ISearchPath path) {
            return path.getSchemaName();
        }

        return null;
    }

    /**
     * Gets the list of object references founded during analysis.
     *
     * @return unmodifiable list of references
     */
    @Override
    public List<ObjectLocation> getReferences() {
        return Collections.unmodifiableList(references);
    }

    @Override
    public void setCollectReferences(boolean collectReferences) {
        this.collectReferences = collectReferences;
    }

    public void setOffset(Token codeStart) {
        CodeUnitToken cuToken = (CodeUnitToken) codeStart;
        offset = cuToken.getCodeUnitStart();
        lineOffset = cuToken.getLine() - 1;
        inLineOffset = cuToken.getCodeUnitPositionInLine();
    }

    protected final int getOffset() {
        return offset;
    }

    protected final int getLineOffset() {
        return lineOffset;
    }

    protected final int getInLineOffset() {
        return inLineOffset;
    }

    /**
     * Updates the saved statement to the twin found in the given db
     *
     * @param db
     *            database
     */
    @Override
    public void updateStmt(IDatabase db) {
        if (stmt.getDatabase() != db) {
            // statement came from another DB object, probably a library
            // for proper depcy processing, find its twin in the final DB object

            // twin implies the exact same object type, hence this is safe
            stmt = stmt.getTwin(db);
        }
    }

    /**
     * Launches the analysis of the statement.
     *
     * @param errors list to collect analysis errors
     * @param meta   metadata container for dependency resolution
     * @return set of dependencies found
     */
    @Override
    public Set<ObjectReference> launchAnalyze(List<Object> errors, IMetaContainer meta) {
        Throwable failure = null;
        try {
            return launchAnalyzeInternal(errors, meta, new NullMonitor());
        } catch (MonitorInvocationException ex) {
            RuntimeException monitorFailure = ex.getFailure();
            failure = monitorFailure;
            throw monitorFailure;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            var cancellation = new MonitorCancelledRuntimeException(ex);
            failure = cancellation;
            throw cancellation;
        } catch (RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            releaseDeferredAnalysisState(failure);
        }
    }

    /**
     * Launches analysis with cooperative cancellation while preserving the
     * diagnostic behavior for genuine analysis failures.
     *
     * @param errors  list to collect analysis errors
     * @param meta    metadata container for dependency resolution
     * @param monitor operation monitor
     * @return set of dependencies found
     * @throws InterruptedException if analysis is cancelled
     */
    @Override
    public Set<ObjectReference> launchAnalyze(
            List<Object> errors, IMetaContainer meta, IMonitor monitor)
            throws InterruptedException {
        Throwable failure = null;
        try {
            return launchAnalyzeMonitored(errors, meta, monitor);
        } catch (MonitorInvocationException ex) {
            RuntimeException monitorFailure = ex.getFailure();
            failure = monitorFailure;
            throw monitorFailure;
        } catch (InterruptedException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            releaseDeferredAnalysisState(failure);
        }
    }

    /**
     * Runs one monitored analysis inside an asynchronous parser task without
     * letting the private monitor-failure carrier cross the {@code Future}
     * boundary. Genuine launcher failures still escape normally; a monitor
     * implementation failure from the built-in path is returned for
     * publication on the owner thread. Subclasses overriding the monitored
     * launch method retain their dynamic-dispatch and exception semantics.
     *
     * @param errors  list to collect analysis errors
     * @param meta    metadata container for dependency resolution
     * @param monitor operation monitor
     * @return dependencies or the exact monitor implementation failure
     * @throws InterruptedException if analysis is cancelled
     */
    public final AnalysisTaskResult launchAnalyzeTask(
            List<Object> errors, IMetaContainer meta, IMonitor monitor)
            throws InterruptedException {
        Throwable failure = null;
        try {
            if (MONITORED_ANALYZE_OVERRIDE.get(getClass())) {
                return new AnalysisTaskResult(
                        launchAnalyze(errors, meta, monitor), null);
            }
            try {
                return new AnalysisTaskResult(
                        launchAnalyzeMonitored(errors, meta, monitor), null);
            } catch (MonitorInvocationException ex) {
                RuntimeException monitorFailure = ex.getFailure();
                failure = monitorFailure;
                return new AnalysisTaskResult(null, monitorFailure);
            }
        } catch (InterruptedException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            releaseDeferredAnalysisState(failure);
        }
    }

    /**
     * Runs subclass cleanup without allowing a secondary cleanup failure to
     * replace the analysis/cancellation failure already escaping this method.
     */
    private void releaseDeferredAnalysisState(Throwable primary) {
        try {
            releaseDeferredAnalysisState();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primary == null) {
                throw cleanupFailure;
            }
            if (primary != cleanupFailure) {
                primary.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * Result transported from an asynchronous analysis worker to its owner.
     * Exactly one component is non-null.
     *
     * @param dependencies dependencies produced by a successful analysis
     * @param monitorFailure exact failure thrown by the monitor implementation
     */
    public record AnalysisTaskResult(
            Set<ObjectReference> dependencies, RuntimeException monitorFailure) {
    }

    private Set<ObjectReference> launchAnalyzeMonitored(
            List<Object> errors, IMetaContainer meta, IMonitor monitor)
            throws InterruptedException {
        IMonitor effectiveMonitor = monitor == null ? new NullMonitor() : monitor;
        checkMonitorCancelled(effectiveMonitor);
        if (LEGACY_ANALYZE_OVERRIDE.get(getClass())) {
            Set<ObjectReference> dependencies = launchAnalyze(errors, meta);
            checkMonitorCancelled(effectiveMonitor);
            return dependencies;
        }
        return launchAnalyzeInternal(errors, meta, effectiveMonitor);
    }

    private Set<ObjectReference> launchAnalyzeInternal(
            List<Object> errors, IMetaContainer meta, IMonitor monitor)
            throws InterruptedException {
        // Duplicated objects don't have parent, skip them
        if (stmt.getParent() == null) {
            ctx = null;
            checkMonitorCancelled(monitor);
            return Collections.emptySet();
        }

        try {
            ParserRuleContext analysisContext = takeAnalysisContext(errors, monitor);
            checkMonitorCancelled(monitor);
            Set<ObjectLocation> locs = analyze(analysisContext, meta);
            checkMonitorCancelled(monitor);
            Set<ObjectReference> depcies = new LinkedHashSet<>();
            EnumSet<DbObjType> disabledDepcies = getDisabledDepcies();
            int published = 0;
            for (ObjectLocation loc : locs) {
                if (!disabledDepcies.contains(loc.getType())) {
                    depcies.add(loc.getObjectReference());
                }

                if (collectReferences && loc.getLineNumber() != 0) {
                    references.add(loc.copyWithOffset(offset, lineOffset, inLineOffset, location));
                }
                if ((++published & (ANALYSIS_PUBLICATION_BATCH_SIZE - 1)) == 0) {
                    checkMonitorCancelled(monitor);
                }
            }
            if ((published & (ANALYSIS_PUBLICATION_BATCH_SIZE - 1)) != 0) {
                checkMonitorCancelled(monitor);
            }
            return depcies;
        } catch (MonitorInvocationException ex) {
            throw ex;
        } catch (MonitorCancelledRuntimeException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            throw ex;
        } catch (DeferredAnalysisStateException ex) {
            throw ex;
        } catch (UnresolvedReferenceException ex) {
            Token t = ex.getErrorToken();
            if (t != null) {
                ErrorTypes errorType = ex instanceof MisplacedObjectException ? ErrorTypes.MISPLACEERROR : ErrorTypes.OTHER;
                AntlrError err = new AntlrError(t, location, t.getLine(),
                        ((CodeUnitToken) t).getCodeUnitPositionInLine(), ex.getMessage(), errorType)
                        .copyWithOffset(offset, lineOffset, inLineOffset);
                LOG.warn(err.toString(), ex);
                errors.add(err);
            } else {
                var errorMsg = Messages.AbstractAnalysisLauncher_error_prefix.formatted(location, ex);
                LOG.warn(errorMsg, ex);
                errors.add(errorMsg);
            }
        } catch (Exception ex) {
            var errorMsg = Messages.AbstractAnalysisLauncher_error_prefix.formatted(location, ex);
            LOG.error(errorMsg, ex);
            errors.add(errorMsg);
        }

        return Collections.emptySet();
    }

    /**
     * Returns the parser context for one analysis and releases the launcher's
     * retained reference before traversal starts. Lazy launchers may override
     * this method to parse a compact descriptor locally.
     *
     * @param errors list to collect parser errors
     * @return context to analyze
     */
    protected ParserRuleContext takeAnalysisContext(List<Object> errors) {
        ParserRuleContext current = ctx;
        ctx = null;
        return current;
    }

    /**
     * Releases state retained only until one analysis attempt. Subclasses with
     * compact lazy descriptors extend this hook so cancellation at the public
     * entry gate cannot retain their raw parser input.
     */
    protected void releaseDeferredAnalysisState() {
        ctx = null;
    }

    @Override
    public void releaseWithoutAnalysis() {
        releaseDeferredAnalysisState();
    }

    /**
     * Returns one analysis context with cancellation checks around context
     * materialization. Lazy launchers override this form to pass the same
     * monitor into their local parser.
     *
     * @param errors  list to collect parser errors
     * @param monitor operation monitor
     * @return context to analyze
     * @throws InterruptedException if context materialization is cancelled
     */
    protected ParserRuleContext takeAnalysisContext(List<Object> errors, IMonitor monitor)
            throws InterruptedException {
        checkMonitorCancelled(monitor);
        ParserRuleContext current = takeAnalysisContext(errors);
        checkMonitorCancelled(monitor);
        return current;
    }

    /**
     * Appends parser diagnostics without retaining an additional full-size
     * publication batch after cancellation.
     *
     * @param target  destination analysis-error list
     * @param source  parser diagnostics to append
     * @param monitor operation monitor
     * @throws InterruptedException if analysis is cancelled
     */
    protected final void appendErrorsWithCancellation(
            List<Object> target, List<Object> source, IMonitor monitor)
            throws InterruptedException {
        checkMonitorCancelled(monitor);
        int published = 0;
        for (int i = 0; i < source.size(); i++) {
            target.add(source.get(i));
            if ((++published & (ANALYSIS_PUBLICATION_BATCH_SIZE - 1)) == 0) {
                checkMonitorCancelled(monitor);
            }
        }
        if ((published & (ANALYSIS_PUBLICATION_BATCH_SIZE - 1)) != 0) {
            checkMonitorCancelled(monitor);
        }
    }

    private static void checkMonitorCancelled(IMonitor monitor)
            throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        try {
            IMonitor.checkCancelled(monitor);
        } catch (MonitorInvocationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new MonitorInvocationException(ex);
        }
    }

    /**
     * Wraps monitor invocation failures only while they cross generic parser
     * and analysis diagnostic handlers. The caller-facing launch methods
     * always unwrap this internal transport before returning control.
     *
     * @param monitor operation monitor
     * @return monitor suitable for a lazy parser invocation
     */
    protected final IMonitor createMonitorInvocationTransport(IMonitor monitor) {
        IMonitor delegate = monitor == null ? new NullMonitor() : monitor;
        return new IMonitor() {
            @Override
            public void setCancelled(boolean cancelled) {
                delegate.setCancelled(cancelled);
            }

            @Override
            public boolean isCancelled() {
                try {
                    return delegate.isCancelled();
                } catch (MonitorInvocationException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    throw new MonitorInvocationException(ex);
                }
            }

            @Override
            public void worked(int work) {
                delegate.worked(work);
            }

            @Override
            public IMonitor createSubMonitor() {
                return createMonitorInvocationTransport(delegate.createSubMonitor());
            }

            @Override
            public void setWorkRemaining(int size) {
                delegate.setWorkRemaining(size);
            }

            @Override
            public void setTaskName(String name) {
                delegate.setTaskName(name);
            }
        };
    }

    /**
     * Gets the set of database object types that should be excluded from dependency analysis.
     * Can be overridden by subclasses to customize which dependency types are ignored.
     * By default, returns an empty set (no types are disabled).
     *
     * @return EnumSet of {@link DbObjType} that should be excluded from dependency collection
     */
    protected EnumSet<DbObjType> getDisabledDepcies() {
        return EnumSet.noneOf(DbObjType.class);
    }

    /**
     * Performs analysis of the given parser context to extract object dependencies.
     * Must be implemented by concrete subclasses to provide specific analysis logic.
     *
     * @param ctx  the parser rule context to analyze
     * @param meta the metadata container providing schema information
     * @return set of object locations representing dependencies found in the context
     * @throws UnresolvedReferenceException if references cannot be resolved
     */
    protected abstract Set<ObjectLocation> analyze(ParserRuleContext ctx, IMetaContainer meta);
}
