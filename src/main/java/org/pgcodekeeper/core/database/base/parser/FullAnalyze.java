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

import org.pgcodekeeper.core.database.api.jdbc.ISupportedVersion;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IRelation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.base.parser.launcher.AbstractAnalysisLauncher;
import org.pgcodekeeper.core.database.base.schema.meta.MetaContainer;
import org.pgcodekeeper.core.database.base.schema.meta.MetaUtils;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgAggregateAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgOperatorAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgViewAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.utils.PhaseTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.RandomAccess;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Performs full analysis of database objects including operators, aggregates, views, and other database elements.
 * Manages ANTLR tasks for concurrent analysis and collects references and dependencies.
 */
public final class FullAnalyze {

    private static final Logger LOG = LoggerFactory.getLogger(FullAnalyze.class);

    private static final int PUBLICATION_BATCH_SIZE = 256;

    private final List<Object> errors;
    private final List<ObjectLocation> refs = new ArrayList<>();
    private final Queue<AntlrTask<?>> antlrTasks;
    private final IDatabase db;
    private final IMetaContainer meta;
    private final IMonitor monitor;
    /**
     * Whether anything downstream will read the references this analysis
     * finds. False on the CLI diff path, where {@code addReference} is a no-op:
     * there the copies are built, held in {@link #refs} until the whole
     * analysis ends, and dropped unread.
     */
    private final boolean collectReferences;

    private final boolean launcherStatsEnabled = PhaseTimer.isEnabled();
    private final ConcurrentMap<String, LauncherClassStats> launcherStats =
            new ConcurrentHashMap<>();

    private FullAnalyze(IDatabase db, IMetaContainer meta, List<Object> errors,
                        IMonitor monitor, Queue<AntlrTask<?>> antlrTasks) {
        this.db = db;
        this.meta = meta;
        this.errors = errors;
        this.monitor = monitor;
        this.antlrTasks = antlrTasks;
        this.collectReferences = db.isCollectObjectReferences();
    }

    /**
     * Performs full analysis of the database using metadata created from the database.
     *
     * @param db     the database to analyze
     * @param errors list to collect analysis errors
     * @param version version of database
     * @throws InterruptedException if analysis is interrupted
     * @throws IOException          if analysis fails
     */
    public static void fullAnalyze(IDatabase db, List<Object> errors, ISupportedVersion version)
            throws InterruptedException, IOException {
        fullAnalyze(db, errors, version, new NullMonitor());
    }

    /**
     * Performs full analysis of the database using metadata created from the
     * database and cooperatively observes the supplied monitor.
     *
     * @param db      the database to analyze
     * @param errors  list to collect analysis errors
     * @param version version of database
     * @param monitor operation monitor
     * @throws InterruptedException if analysis is cancelled
     * @throws IOException          if analysis fails
     */
    public static void fullAnalyze(IDatabase db, List<Object> errors,
                                   ISupportedVersion version, IMonitor monitor)
            throws InterruptedException, IOException {
        Queue<AntlrTask<?>> antlrTasks = createOwnedTaskQueue(db);
        fullAnalyze(db, errors, version, monitor, antlrTasks);
    }

    /**
     * Performs full analysis using a loader-owned task queue. The queue must be
     * fully drained after structural loading so cancellation can retain one
     * owner and one set of worker futures across both phases.
     *
     * @param db         the database to analyze
     * @param errors     list to collect analysis errors
     * @param version    version of database
     * @param monitor    operation monitor
     * @param antlrTasks loader-owned task queue
     * @throws InterruptedException if analysis is cancelled
     * @throws IOException          if analysis fails
     */
    public static void fullAnalyze(IDatabase db, List<Object> errors,
                                   ISupportedVersion version, IMonitor monitor,
                                   Queue<AntlrTask<?>> antlrTasks)
            throws InterruptedException, IOException {
        IMonitor effectiveMonitor = monitor == null ? new NullMonitor() : monitor;
        MetaContainer metaDb;
        try {
            requireReadyQueue(antlrTasks, effectiveMonitor);
            metaDb = MetaUtils.createTreeFromDb(db, version, effectiveMonitor);
            checkCancelled(effectiveMonitor);
        } catch (InterruptedException | RuntimeException | Error ex) {
            abortAndReleaseDeferredState(db, antlrTasks, ex);
            throw ex;
        }
        fullAnalyze(db, metaDb, errors, effectiveMonitor, antlrTasks);
    }

    /**
     * Performs full analysis of the database using the provided metadata container.
     *
     * @param db     the database to analyze
     * @param metaDb metadata container for analysis context
     * @param errors list to collect analysis errors
     * @throws InterruptedException if analysis is interrupted
     * @throws IOException          if analysis fails
     */
    public static void fullAnalyze(IDatabase db, MetaContainer metaDb, List<Object> errors)
            throws InterruptedException, IOException {
        fullAnalyze(db, (IMetaContainer) metaDb, errors, new NullMonitor());
    }

    public static void fullAnalyze(IDatabase db, IMetaContainer metaDb,
                                   List<Object> errors)
            throws InterruptedException, IOException {
        fullAnalyze(db, metaDb, errors, new NullMonitor());
    }

    /**
     * Performs full analysis with prebuilt metadata and cooperative
     * cancellation.
     *
     * @param db      the database to analyze
     * @param metaDb  metadata container for analysis context
     * @param errors  list to collect analysis errors
     * @param monitor operation monitor
     * @throws InterruptedException if analysis is cancelled
     * @throws IOException          if analysis fails
     */
    public static void fullAnalyze(IDatabase db, MetaContainer metaDb,
                                   List<Object> errors, IMonitor monitor)
            throws InterruptedException, IOException {
        fullAnalyze(db, (IMetaContainer) metaDb, errors, monitor);
    }

    public static void fullAnalyze(IDatabase db, IMetaContainer metaDb,
                                   List<Object> errors, IMonitor monitor)
            throws InterruptedException, IOException {
        Queue<AntlrTask<?>> antlrTasks = createOwnedTaskQueue(db);
        fullAnalyze(db, metaDb, errors, monitor, antlrTasks);
    }

    public static void fullAnalyze(IDatabase db, MetaContainer metaDb,
                                   List<Object> errors, IMonitor monitor,
                                   Queue<AntlrTask<?>> antlrTasks)
            throws InterruptedException, IOException {
        fullAnalyze(db, (IMetaContainer) metaDb, errors, monitor, antlrTasks);
    }

    public static void fullAnalyze(IDatabase db, IMetaContainer metaDb,
                                   List<Object> errors, IMonitor monitor,
                                   Queue<AntlrTask<?>> antlrTasks)
            throws InterruptedException, IOException {
        IMonitor effectiveMonitor = monitor == null ? new NullMonitor() : monitor;
        FullAnalyze analyze;
        try {
            requireReadyQueue(antlrTasks, effectiveMonitor);
            analyze = new FullAnalyze(db, metaDb, errors,
                    effectiveMonitor, antlrTasks);
        } catch (InterruptedException | RuntimeException | Error ex) {
            abortAndReleaseDeferredState(db, antlrTasks, ex);
            throw ex;
        }
        analyze.fullAnalyze();
    }

    private void fullAnalyze() throws InterruptedException, IOException {
        Throwable failure = null;
        try {
            checkCancelled();
            disableReferenceCollection();
            checkCancelled();
            analyzeOperators();
            checkCancelled();
            analyzeAggregate();
            checkCancelled();
            long sequentialViewsStart = PhaseTimer.start();
            analyzeViews(null);
            PhaseTimer.end("analyze_views_sequential", sequentialViewsStart);
            checkCancelled();

            long routineBodyPhaseStart = PhaseTimer.start();
            long skippedMatchedBodies = 0;
            long skippedOldSideBodies = 0;
            long skippedBodyBytes = 0;
            long parsedBodies = 0;
            List<IAnalysisLauncher> launchers = db.getAnalysisLaunchers();
            // Analyzing the launchers is the bulk of a project analysis and the
            // structural load reports nothing about it, so a caller that only
            // watched the loader would see the operation stop responding here.
            // The sub-monitor counts finished launchers, which is the only unit
            // of this phase the caller can also name.
            IMonitor analysisProgress = monitor.createSubMonitor();
            analysisProgress.setWorkRemaining(Math.max(1, launchers.size()));
            for (IAnalysisLauncher l : launchers) {
                checkCancelled();
                if (l == null) {
                    analysisProgress.worked(1);
                } else {
                    if (l instanceof PgFuncProcAnalysisLauncher routineLauncher) {
                        var skipOutcome = routineLauncher.skipBodyAnalysis();
                        if (skipOutcome != PgFuncProcAnalysisLauncher.BodySkipOutcome.ANALYZED) {
                            // matched: hash-first proved the late-bound body
                            // byte-identical to the peer side; old-side: the
                            // server already accepted the body and its deps
                            // are unnecessary; the launcher released its source
                            if (skipOutcome
                                    == PgFuncProcAnalysisLauncher.BodySkipOutcome.SKIPPED_MATCHED) {
                                skippedMatchedBodies++;
                            } else {
                                skippedOldSideBodies++;
                            }
                            skippedBodyBytes += routineLauncher.getEstimatedParseBytes();
                            analysisProgress.worked(1);
                            continue;
                        }
                        if (routineLauncher.isDeferredBodyAnalysis()) {
                            parsedBodies++;
                        }
                    }
                    long estimatedParseBytes = l.getEstimatedParseBytes();
                    if (launcherStatsEnabled) {
                        LauncherClassStats stats = statsFor(l);
                        stats.count.increment();
                        stats.bytes.add(estimatedParseBytes);
                    }
                    checkCancelled();
                    AntlrTaskManager.submit(antlrTasks, estimatedParseBytes,
                            () -> runAnalysisLauncher(l),
                            result -> {
                                if (result.monitorFailure() != null) {
                                    throw result.monitorFailure();
                                }
                                appendListWithCancellation(errors, result.errors());
                                var st = l.getStmt();
                                publishWithCancellation(
                                        result.dependencies(), st::addDependency);
                                if (collectReferences) {
                                    appendListWithCancellation(refs, l.getReferences());
                                }
                                analysisProgress.worked(1);
                            });
                }
            }
            checkCancelled();
            db.clearAnalysisLaunchers();
            AntlrTaskManager.finish(antlrTasks);
            logLauncherStats();
            long skippedBodies = skippedMatchedBodies + skippedOldSideBodies;
            if (skippedBodies != 0 || parsedBodies != 0) {
                LOG.debug("routine_body_analysis skipped={} parsed={} skipped_bytes={}"
                        + " skipped_matched={} skipped_oldside={}",
                        skippedBodies, parsedBodies, skippedBodyBytes,
                        skippedMatchedBodies, skippedOldSideBodies);
                PhaseTimer.end("routine_body_analysis", routineBodyPhaseStart,
                        "skipped=" + skippedBodies + " parsed=" + parsedBodies
                                + " skipped_bytes=" + skippedBodyBytes
                                + " skipped_matched=" + skippedMatchedBodies
                                + " skipped_oldside=" + skippedOldSideBodies);
            }

            checkCancelled();
            int publishedReferences = 0;
            for (ObjectLocation ref : refs) {
                if ((publishedReferences++ & 0xFF) == 0) {
                    checkCancelled();
                }
                db.addReference(ref.getFilePath(), ref);
            }
            checkCancelled();
        } catch (MonitorCancelledRuntimeException ex) {
            boolean cancellationActive;
            try {
                cancellationActive = isCancellationActive();
            } catch (RuntimeException | Error classificationFailure) {
                failure = classificationFailure;
                abortTasks(classificationFailure);
                throw classificationFailure;
            }
            if (!cancellationActive) {
                failure = ex;
                abortTasks(ex);
                throw ex;
            }
            InterruptedException interrupted = new InterruptedException();
            AntlrTaskManager.mergeSuppressed(interrupted, ex);
            failure = interrupted;
            abortTasks(interrupted);
            throw interrupted;
        } catch (IOException | InterruptedException | RuntimeException | Error ex) {
            failure = ex;
            abortTasks(ex);
            throw ex;
        } finally {
            releaseDeferredState(db, failure);
        }
        checkCancelled();
    }

    private LauncherResult runAnalysisLauncher(IAnalysisLauncher launcher)
            throws InterruptedException {
        if (!launcherStatsEnabled) {
            return runAnalysisLauncherUntimed(launcher);
        }
        long start = System.nanoTime();
        try {
            return runAnalysisLauncherUntimed(launcher);
        } finally {
            statsFor(launcher).nanos.add(System.nanoTime() - start);
        }
    }

    private LauncherResult runAnalysisLauncherUntimed(IAnalysisLauncher launcher)
            throws InterruptedException {
        try {
            checkCancelled();
        } catch (RuntimeException ex) {
            return LauncherResult.monitorFailure(ex);
        }

        var launcherErrors = new ArrayList<>();
        Set<ObjectReference> dependencies;
        if (launcher instanceof AbstractAnalysisLauncher abstractLauncher) {
            var result = abstractLauncher.launchAnalyzeTask(
                    launcherErrors, meta, monitor);
            if (result.monitorFailure() != null) {
                return LauncherResult.monitorFailure(result.monitorFailure());
            }
            dependencies = result.dependencies();
        } else {
            dependencies = launcher.launchAnalyze(
                    launcherErrors, meta, monitor);
        }

        try {
            checkCancelled();
        } catch (RuntimeException ex) {
            return LauncherResult.monitorFailure(ex);
        }
        return new LauncherResult(dependencies, launcherErrors, null);
    }

    private static void releaseDeferredState(IDatabase db, Throwable failure) {
        Throwable cleanupFailure = null;
        try {
            db.clearAnalysisLaunchers();
        } catch (RuntimeException | Error ex) {
            cleanupFailure = ex;
        }

        if (db instanceof PgDatabase) {
            try {
                PgParserUtils.releaseBodyParserCache();
            } catch (RuntimeException | Error ex) {
                if (cleanupFailure == null) {
                    cleanupFailure = ex;
                } else if (cleanupFailure != ex && failure != ex) {
                    cleanupFailure.addSuppressed(ex);
                }
            }
        }

        if (cleanupFailure == null) {
            return;
        }
        if (failure != null) {
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            return;
        }
        if (cleanupFailure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw (Error) cleanupFailure;
    }

    private static Queue<AntlrTask<?>> createOwnedTaskQueue(IDatabase db) {
        try {
            return AntlrTaskManager.createTaskQueue();
        } catch (RuntimeException | Error ex) {
            releaseDeferredState(db, ex);
            throw ex;
        }
    }

    private static void requireReadyQueue(Queue<AntlrTask<?>> antlrTasks,
                                          IMonitor monitor)
            throws InterruptedException {
        Objects.requireNonNull(antlrTasks, "antlrTasks");
        checkCancelled(monitor);
        if (!AntlrTaskManager.isDrained(antlrTasks)) {
            // A concurrent requestAbort also makes the pipeline non-drained.
            // Prefer the cancellation contract over an invariant failure.
            checkCancelled(monitor);
            AntlrTaskManager.requireDrained(antlrTasks);
        }
    }

    private static void abortAndReleaseDeferredState(
            IDatabase db, Queue<AntlrTask<?>> antlrTasks, Throwable failure) {
        if (antlrTasks != null) {
            try {
                AntlrTaskManager.abort(antlrTasks);
            } catch (RuntimeException | Error ex) {
                if (failure != ex) {
                    failure.addSuppressed(ex);
                }
            }
        }
        releaseDeferredState(db, failure);
    }

    private void abortTasks(Throwable failure) {
        try {
            AntlrTaskManager.abort(antlrTasks);
        } catch (RuntimeException | Error ex) {
            if (failure != ex) {
                failure.addSuppressed(ex);
            }
        }
    }

    private record LauncherResult(Set<ObjectReference> dependencies,
                                  List<Object> errors,
                                  RuntimeException monitorFailure) {

        private static LauncherResult monitorFailure(RuntimeException failure) {
            return new LauncherResult(null, null, failure);
        }
    }

    private LauncherClassStats statsFor(IAnalysisLauncher launcher) {
        return launcherStats.computeIfAbsent(
                launcher.getClass().getSimpleName(), key -> new LauncherClassStats());
    }

    /**
     * Emits one aggregated line per launcher class of the pooled analysis
     * phase. The elapsed time is the sum over concurrent workers, so it may
     * exceed the wall time of the phase. Launchers of the sequential
     * operator/aggregate/view phases are not included.
     */
    private void logLauncherStats() {
        if (!launcherStatsEnabled || launcherStats.isEmpty()) {
            return;
        }
        launcherStats.entrySet().stream()
                .sorted((left, right) -> Long.compare(
                        right.getValue().nanos.sum(), left.getValue().nanos.sum()))
                .forEach(entry -> {
                    LauncherClassStats stats = entry.getValue();
                    long nanos = stats.nanos.sum();
                    PhaseTimer.endAccumulated("launcher_class", nanos == 0 ? 1 : nanos,
                            entry.getKey() + " count=" + stats.count.sum()
                                    + " bytes=" + stats.bytes.sum());
                });
    }

    private static final class LauncherClassStats {

        private final LongAdder count = new LongAdder();
        private final LongAdder bytes = new LongAdder();
        private final LongAdder nanos = new LongAdder();
    }

    /**
     * Analyzes views in the database, optionally focusing on a specific relation.
     *
     * @param rel the specific relation to analyze, or null to analyze all views
     */
    public void analyzeView(IRelation rel) {
        try {
            analyzeViews(rel);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MonitorCancelledRuntimeException(ex);
        }
    }

    private void analyzeViews(IRelation rel) throws InterruptedException {
        List<IAnalysisLauncher> launchers = db.getAnalysisLaunchers();
        for (int i = 0; i < launchers.size(); ++i) {
            checkCancelled();
            var l = launchers.get(i);
            if (rel == null && l instanceof PgViewAnalysisLauncher
                    && l.getStmt() instanceof IRelation viewRelation
                    && viewRelation.getRelationColumns() != null) {
                // a JDBC-loaded view carries its catalog columns, so meta was
                // initialized by createTreeFromDb and star expansion never
                // recurses into it: analyze it in the shared pool instead of
                // this sequential phase
                continue;
            }
            if (l instanceof PgViewAnalysisLauncher v
                    && (rel == null
                    || (rel.getSchemaName().equals(l.getSchemaName())
                    && rel.getName().equals(l.getStmt().getName())))) {
                // allow GC to reclaim context memory immediately
                // and protects from infinite recursion
                launchers.set(i, null);
                v.setFullAnalyze(this);
                var st = l.getStmt();
                var dependencies = l.launchAnalyze(errors, meta, monitor);
                checkCancelled();
                publishWithCancellation(dependencies, st::addDependency);
                if (collectReferences) {
                    appendListWithCancellation(refs, l.getReferences());
                }
            }
        }
    }

    /**
     * Tells every launcher up front that its references will not be read, so
     * the offset-corrected copies are never built. One pass before the first
     * analysis phase covers all four of them, because they all walk the same
     * launcher list of the database.
     */
    private void disableReferenceCollection() throws InterruptedException {
        if (collectReferences) {
            return;
        }
        List<IAnalysisLauncher> launchers = db.getAnalysisLaunchers();
        for (int i = 0; i < launchers.size(); ++i) {
            if ((i & 0xFF) == 0) {
                checkCancelled();
            }
            var l = launchers.get(i);
            if (l != null) {
                l.setCollectReferences(false);
            }
        }
    }

    private void analyzeOperators() throws InterruptedException {
        List<IAnalysisLauncher> launchers = db.getAnalysisLaunchers();
        for (int i = 0; i < launchers.size(); ++i) {
            checkCancelled();
            var l = launchers.get(i);
            if (l instanceof PgOperatorAnalysisLauncher) {
                // allow GC to reclaim context memory immediately
                launchers.set(i, null);
                l.launchAnalyze(errors, meta, monitor);
                checkCancelled();
            }
        }
    }

    private void analyzeAggregate() throws InterruptedException {
        List<IAnalysisLauncher> launchers = db.getAnalysisLaunchers();
        for (int i = 0; i < launchers.size(); ++i) {
            checkCancelled();
            var l = launchers.get(i);
            if (l instanceof PgAggregateAnalysisLauncher) {
                // allow GC to reclaim context memory immediately
                launchers.set(i, null);
                l.launchAnalyze(errors, meta, monitor);
                checkCancelled();
            }
        }
    }

    private void checkCancelled() throws InterruptedException {
        checkCancelled(monitor);
    }

    private void checkCancelledRuntime() {
        if (Thread.currentThread().isInterrupted() || monitor.isCancelled()) {
            throw new MonitorCancelledRuntimeException();
        }
    }

    private <T> void appendListWithCancellation(
            List<T> target, List<? extends T> source) {
        checkCancelledRuntime();
        int published = 0;
        if (source instanceof RandomAccess) {
            int size = source.size();
            for (int i = 0; i < size; i++) {
                target.add(source.get(i));
                if ((++published & (PUBLICATION_BATCH_SIZE - 1)) == 0) {
                    checkCancelledRuntime();
                }
            }
        } else {
            for (T item : source) {
                target.add(item);
                if ((++published & (PUBLICATION_BATCH_SIZE - 1)) == 0) {
                    checkCancelledRuntime();
                }
            }
        }
        if ((published & (PUBLICATION_BATCH_SIZE - 1)) != 0) {
            checkCancelledRuntime();
        }
    }

    private <T> void publishWithCancellation(
            Iterable<? extends T> source, Consumer<T> publisher) {
        checkCancelledRuntime();
        int published = 0;
        for (T item : source) {
            publisher.accept(item);
            if ((++published & (PUBLICATION_BATCH_SIZE - 1)) == 0) {
                checkCancelledRuntime();
            }
        }
        if ((published & (PUBLICATION_BATCH_SIZE - 1)) != 0) {
            checkCancelledRuntime();
        }
    }

    private boolean isCancellationActive() {
        return Thread.currentThread().isInterrupted() || monitor.isCancelled();
    }

    private static void checkCancelled(IMonitor monitor) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        IMonitor.checkCancelled(monitor);
    }
}
