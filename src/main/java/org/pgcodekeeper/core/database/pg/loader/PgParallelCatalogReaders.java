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
package org.pgcodekeeper.core.database.pg.loader;

import org.pgcodekeeper.core.database.api.launcher.AnalysisLauncherRedirect;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.base.loader.JdbcCatalogLane;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;
import org.pgcodekeeper.core.database.pg.jdbc.*;
import org.pgcodekeeper.core.database.pg.routine.PgJdbcRoutineBodyCatalogMode;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.exception.XmlReaderException;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.telemetry.ComparisonTelemetryPublisher;
import org.pgcodekeeper.core.telemetry.PgParallelCatalogFallbackReason;
import org.pgcodekeeper.core.utils.DaemonThreadFactory;
import org.pgcodekeeper.core.utils.PhaseTimer;
import org.pgcodekeeper.core.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Reads the PostgreSQL catalog with lane-parallel readers on worker
 * connections sharing the primary connection's repeatable-read snapshot.
 * <p>
 * The flow preserves the sequential loader's observable results exactly:
 * <ul>
 * <li>every model map keeps a single writer (readers of one object type never
 * split across lanes), so map insertion order equals the serial row order;</li>
 * <li>each reader's analysis launchers are buffered on its lane and published
 * on the coordinator in the canonical serial reader order, keeping
 * {@code FullAnalyze} launcher iteration byte-identical to a serial load;</li>
 * <li>stage-2 readers that resolve table/view containers run only after the
 * tables and views readers have completed (full stage barrier), so no
 * container lookup can silently miss.</li>
 * </ul>
 * Any failure to establish the shared snapshot or the worker connections
 * falls back to the sequential flow before any reader has run.
 */
final class PgParallelCatalogReaders {

    private static final Logger LOG = LoggerFactory.getLogger(PgParallelCatalogReaders.class);

    private static final String QUERY_EXPORT_SNAPSHOT =
            "SELECT pg_catalog.pg_export_snapshot()";

    /**
     * Snapshot-export capability probe executed on a worker connection in a
     * throwaway transaction. A savepoint on the primary cannot serve as the
     * guard: PostgreSQL refuses to export a snapshot from a subtransaction.
     */
    private static final String PROBE_SCRIPT =
            "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY; "
                    + QUERY_EXPORT_SNAPSHOT;

    /**
     * Stage-1 units outnumber stage-2 units; more lanes would only idle while
     * holding server connections.
     */
    private static final int MAX_LANES = 7;

    private final PgJdbcLoader loader;
    private final PgDatabase db;
    private final PgJdbcRoutineBodyCatalogMode bodyCatalogMode;
    private final PgSequencesReader sequencesReader;
    private final Statement primaryStatement;
    private final int laneCount;

    private final List<List<IAnalysisLauncher>> canonicalBuffers = new ArrayList<>();
    private final List<JdbcCatalogLane> lanes = new ArrayList<>();

    // stage-2 steps are created together with the stage-1 steps so their
    // launcher buffers land in the canonical slot order
    private CatalogStep rules;
    private CatalogStep policies;
    private CatalogStep triggers;
    private CatalogStep indices;
    private CatalogStep constraints;
    private CatalogStep sequences;

    PgParallelCatalogReaders(PgJdbcLoader loader, PgDatabase db,
            PgJdbcRoutineBodyCatalogMode bodyCatalogMode, PgSequencesReader sequencesReader,
            Statement primaryStatement, int workerCount) {
        this.loader = loader;
        this.db = db;
        this.bodyCatalogMode = bodyCatalogMode;
        this.sequencesReader = sequencesReader;
        this.primaryStatement = primaryStatement;
        this.laneCount = Math.min(workerCount, MAX_LANES);
    }

    /**
     * Runs the parallel catalog read.
     *
     * @return {@code true} when the catalog was fully read in parallel,
     *         {@code false} when the caller must run the sequential flow
     */
    boolean read() throws SQLException, InterruptedException, XmlReaderException, IOException {
        long setupStart = PhaseTimer.start();
        Throwable failure = null;
        ExecutorService executor = null;
        boolean completed = false;
        try {
            Connection probeConnection = openProbeConnection();
            if (probeConnection != null) {
                String snapshotId = exportPrimarySnapshot();
                if (snapshotId != null) {
                    // runs at the start instead of before PgUserMappingsReader;
                    // the result is snapshot-stable, so the answer is identical
                    boolean userMappingsAllowed =
                            loader.queryUserMappingsAllowed(primaryStatement);
                    List<CatalogUnit> stage1 =
                            buildStage1Units(userMappingsAllowed);
                    List<CatalogUnit> stage2 = buildStage2Units();

                    if (openLanes(snapshotId)) {
                        PhaseTimer.end("parallel_catalog_setup", setupStart,
                                "lanes=" + lanes.size());
                        executor = Executors.newFixedThreadPool(
                                lanes.size(), new DaemonThreadFactory());

                        long stage1Start = PhaseTimer.start();
                        runStage(executor, stage1);
                        PhaseTimer.end("parallel_catalog_stage1", stage1Start);
                        IMonitor.checkCancelled(loader.getMonitor());

                        long stage2Start = PhaseTimer.start();
                        runStage(executor, stage2);
                        PhaseTimer.end("parallel_catalog_stage2", stage2Start);
                        IMonitor.checkCancelled(loader.getMonitor());

                        publishLaunchers();
                        completed = true;
                    }
                }
            }
        } catch (SQLException | InterruptedException | XmlReaderException | IOException
                | RuntimeException | Error ex) {
            failure = ex;
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
            failure = closeLanes(failure);
        }
        rethrow(failure);
        return completed;
    }

    /**
     * Opens the first worker connection and proves in a throwaway
     * repeatable-read transaction that this server accepts
     * {@code pg_export_snapshot()}. A failed probe never touches the primary
     * transaction, so the sequential fallback stays fully usable. The probed
     * connection is retained as the first lane connection.
     *
     * @return probed worker connection, or {@code null} when the sequential
     *         flow must be used
     */
    private Connection openProbeConnection() throws InterruptedException, IOException {
        Connection probeConnection = null;
        try {
            probeConnection = loader.getConnector().getConnection();
            lanes.add(new JdbcCatalogLane(probeConnection,
                    loader.createSiblingParserTaskQueue()));
            loader.publishWorkerConnectionOpened(
                    probeConnection, lanes.size());
            loader.registerWorkerConnection(probeConnection);
            probeConnection.setAutoCommit(false);
            try (Statement probeStatement = probeConnection.createStatement()) {
                loader.getRunner().run(probeStatement, PROBE_SCRIPT);
            }
            // release the throwaway probe snapshot; the lane transaction
            // starts fresh with the primary snapshot installed
            probeConnection.rollback();
            return probeConnection;
        } catch (SQLException | IOException ex) {
            LOG.debug("Parallel catalog readers disabled: snapshot export probe failed", ex);
            publishFallback(PgParallelCatalogFallbackReason.SNAPSHOT_PROBE_FAILED);
            Throwable cleanupFailure = closeLanes(null);
            if (cleanupFailure != null && cleanupFailure != ex) {
                ex.addSuppressed(cleanupFailure);
            }
            return null;
        }
    }

    /**
     * Exports the primary snapshot. Runs only after the worker-connection
     * probe succeeded, so a failure here is a genuine load failure: the
     * export error aborts the primary transaction, which makes a sequential
     * fallback impossible anyway.
     *
     * @return snapshot identifier, or {@code null} on an empty probe result
     */
    private String exportPrimarySnapshot() throws SQLException, InterruptedException {
        // Close the shared catalog probe boundary first: the exported
        // snapshot must survive every later catalog read, so no rollback may
        // ever be able to reach past the export.
        loader.releaseCatalogProbeSavepoint(loader.getConnection());
        try (ResultSet res = loader.getRunner().runScript(
                primaryStatement, QUERY_EXPORT_SNAPSHOT)) {
            return res.next() ? res.getString(1) : null;
        }
    }

    /**
     * Opens the remaining worker connections and synchronizes every lane with
     * the exported snapshot. Runs strictly before any reader is dispatched,
     * so a failure here can still fall back to the untouched sequential flow.
     *
     * @return {@code true} when every lane is ready
     */
    private boolean openLanes(String snapshotId) throws InterruptedException, IOException {
        String setupScript = "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY; "
                + "SET TRANSACTION SNAPSHOT " + Utils.quoteString(snapshotId) + "; "
                + "SET search_path TO pg_catalog; "
                + "SET timezone = " + Utils.quoteString(loader.getTimezone());
        try {
            while (lanes.size() < laneCount) {
                Connection workerConnection = loader.getConnector().getConnection();
                lanes.add(new JdbcCatalogLane(workerConnection,
                        loader.createSiblingParserTaskQueue()));
                loader.publishWorkerConnectionOpened(
                        workerConnection, lanes.size());
                loader.registerWorkerConnection(workerConnection);
                workerConnection.setAutoCommit(false);
            }
            for (JdbcCatalogLane lane : lanes) {
                try (Statement setupStatement = lane.getConnection().createStatement()) {
                    loader.getRunner().run(setupStatement, setupScript);
                }
            }
            return true;
        } catch (SQLException | IOException ex) {
            LOG.debug("Parallel catalog readers disabled: worker connection setup failed", ex);
            publishFallback(PgParallelCatalogFallbackReason.LANE_SETUP_FAILED);
            Throwable cleanupFailure = closeLanes(null);
            if (cleanupFailure != null && cleanupFailure != ex) {
                ex.addSuppressed(cleanupFailure);
            }
            return false;
        }
    }

    /**
     * Publishes the sequential-fallback signal. The reason is a closed enum:
     * the failure itself stays in the debug log, out of the telemetry contract.
     */
    private void publishFallback(PgParallelCatalogFallbackReason reason) {
        ComparisonTelemetryPublisher.publishPgParallelCatalogFallback(
                loader.getSettings().getComparisonTelemetry(), reason);
    }

    private Throwable closeLanes(Throwable failure) {
        Throwable result = failure;
        for (int i = 0; i < lanes.size(); i++) {
            JdbcCatalogLane lane = lanes.get(i);
            try {
                AntlrTaskManager.close(lane.getAntlrTasks());
            } catch (RuntimeException | Error ex) {
                result = addFailure(result, ex);
            }
            try {
                loader.clearWorkerConnection(lane.getConnection());
            } catch (RuntimeException | Error ex) {
                result = addFailure(result, ex);
            }
            loader.publishWorkerConnectionCloseRequested(
                    lane.getConnection(), i + 1);
            try {
                lane.getConnection().close();
            } catch (SQLException | RuntimeException | Error ex) {
                result = addFailure(result, ex);
            }
        }
        lanes.clear();
        return result;
    }

    private void runStage(ExecutorService executor, List<CatalogUnit> units)
            throws SQLException, InterruptedException, XmlReaderException, IOException {
        Queue<CatalogUnit> workQueue = new ConcurrentLinkedQueue<>(units);
        List<Future<Void>> laneRuns = new ArrayList<>(lanes.size());
        for (JdbcCatalogLane lane : lanes) {
            laneRuns.add(executor.submit(() -> runLane(lane, workQueue)));
        }
        joinLanes(laneRuns);
    }

    /**
     * One lane worker: pulls whole units off the shared queue, runs their
     * reader steps with the per-reader launcher buffer installed, then drains
     * the lane-owned ANTLR pipeline so every finalizer of this stage has run
     * before the stage barrier is crossed.
     */
    private Void runLane(JdbcCatalogLane lane, Queue<CatalogUnit> workQueue) throws Exception {
        PgParallelCatalogReadersObserver observer =
                PgParallelCatalogReadersObserver.Installation.current();
        loader.bindCatalogLane(lane);
        try {
            if (observer != null) {
                observer.laneStarted(lane);
            }
            CatalogUnit unit;
            while ((unit = workQueue.poll()) != null) {
                for (CatalogStep step : unit.steps()) {
                    runStep(lane, step);
                }
            }
            if (observer != null) {
                observer.laneDrainStarted(lane);
            }
            try {
                AntlrTaskManager.finish(lane.getAntlrTasks());
            } catch (MonitorCancelledRuntimeException ex) {
                throw classifyMonitorCancellation(ex);
            }
            return null;
        } catch (Exception | Error ex) {
            try {
                AntlrTaskManager.abort(lane.getAntlrTasks());
            } catch (RuntimeException | Error abortEx) {
                if (abortEx != ex) {
                    ex.addSuppressed(abortEx);
                }
            }
            throw ex;
        } finally {
            lane.setLauncherSink(null);
            loader.unbindCatalogLane();
        }
    }

    private void runStep(JdbcCatalogLane lane, CatalogStep step)
            throws SQLException, InterruptedException, XmlReaderException {
        lane.setLauncherSink(step.buffer());
        List<IAnalysisLauncher> previousRedirect = AnalysisLauncherRedirect.install(step.buffer());
        try {
            step.action().read();
        } finally {
            AnalysisLauncherRedirect.restore(previousRedirect);
        }
    }

    /**
     * Joins every lane of a stage. Lanes are never interrupted because a peer
     * failed: interruption would trip the terminal JDBC cancellation drain
     * and misclassify a genuine failure as a cancellation. A failed lane's
     * peers finish their current units and the first failure (in lane order)
     * is rethrown with the others suppressed.
     */
    private void joinLanes(List<Future<Void>> laneRuns)
            throws SQLException, InterruptedException, XmlReaderException, IOException {
        Throwable failure = null;
        for (Future<Void> laneRun : laneRuns) {
            try {
                laneRun.get();
            } catch (ExecutionException ex) {
                failure = addFailure(failure, ex.getCause() == null ? ex : ex.getCause());
            } catch (CancellationException ex) {
                failure = addFailure(failure, ex);
            } catch (InterruptedException ex) {
                // owner-side cancellation: release every lane and stop joining
                for (Future<Void> pending : laneRuns) {
                    pending.cancel(true);
                }
                Thread.currentThread().interrupt();
                throw drainedInterruption(ex, failure);
            }
        }
        rethrow(failure);
    }

    /**
     * Mirrors the serial {@code finishLoaders} classification: an active
     * cancellation surfaces as an {@link InterruptedException}, anything else
     * keeps the original runtime failure.
     */
    private Exception classifyMonitorCancellation(MonitorCancelledRuntimeException cancellation) {
        IMonitor monitor = loader.getMonitor();
        boolean active = Thread.currentThread().isInterrupted()
                || monitor != null && monitor.isCancelled();
        if (!active) {
            return cancellation;
        }
        InterruptedException interrupted = new InterruptedException();
        AntlrTaskManager.mergeSuppressed(interrupted, cancellation);
        return interrupted;
    }

    private static InterruptedException drainedInterruption(
            InterruptedException interruption, Throwable failure) {
        if (failure != null && failure != interruption) {
            interruption.addSuppressed(failure);
        }
        return interruption;
    }

    /**
     * Publishes the buffered launchers in the canonical serial reader order,
     * which makes the database launcher list byte-identical to a serial load.
     */
    private void publishLaunchers() throws InterruptedException {
        IMonitor.checkCancelled(loader.getMonitor());
        for (List<IAnalysisLauncher> buffer : canonicalBuffers) {
            for (IAnalysisLauncher launcher : buffer) {
                db.addAnalysisLauncher(launcher);
            }
        }
    }

    /**
     * Builds the stage-1 units: readers that never resolve table/view
     * containers. Units are dispatched heaviest-first; readers writing the
     * same model map share one unit and therefore one lane.
     * <p>
     * Buffer slots are allocated in the canonical serial reader order, which
     * is independent of the unit dispatch order.
     */
    private List<CatalogUnit> buildStage1Units(boolean userMappingsAllowed) {
        boolean gp7Plus = PgSupportedVersion.GP_VERSION_7.isLE(loader.getVersion());

        // canonical order: functions, aggregates, views, tables, rules,
        // policies, triggers, indices, constraints, types, statistics,
        // sequences, fts parsers/templates/dictionaries/configurations,
        // operators, extensions, event triggers, casts, fdws, servers,
        // user mappings, collations
        CatalogStep functions = step(() -> new PgFunctionsReader(loader, bodyCatalogMode).read());
        CatalogStep aggregates = step(() -> new PgAggregatesReader(loader).read());
        CatalogStep views = step(() -> new PgViewsReader(loader).read());
        CatalogStep tables = step(() -> new PgTablesReader(loader).read());
        this.rules = step(() -> new PgRulesReader(loader).read());
        this.policies = gp7Plus ? step(() -> new PgPoliciesReader(loader).read()) : null;
        this.triggers = step(() -> new PgTriggersReader(loader).read());
        this.indices = step(() -> new PgIndicesReader(loader).read());
        this.constraints = step(() -> new PgConstraintsReader(loader).read());
        CatalogStep types = step(() -> new PgTypesReader(loader).read());
        CatalogStep statistics = gp7Plus ? step(() -> new PgStatisticsReader(loader).read()) : null;
        this.sequences = step(sequencesReader::read);
        CatalogStep ftsParsers = step(() -> new PgFtsParsersReader(loader).read());
        CatalogStep ftsTemplates = step(() -> new PgFtsTemplatesReader(loader).read());
        CatalogStep ftsDictionaries = step(() -> new PgFtsDictionariesReader(loader).read());
        CatalogStep ftsConfigurations = step(() -> new PgFtsConfigurationsReader(loader).read());
        CatalogStep operators = step(() -> new PgOperatorsReader(loader).read());
        CatalogStep extensions = step(() -> new PgExtensionsReader(loader, db).read());
        CatalogStep eventTriggers = step(() -> new PgEventTriggersReader(loader, db).read());
        CatalogStep casts = step(() -> new PgCastsReader(loader, db).read());
        CatalogStep fdws = step(() -> new PgForeignDataWrappersReader(loader, db).read());
        CatalogStep servers = step(() -> new PgServersReader(loader, db).read());
        CatalogStep userMappings = userMappingsAllowed
                ? step(() -> new PgUserMappingsReader(loader, db).read())
                : null;
        CatalogStep collations = step(() -> new PgCollationsReader(loader).read());

        List<CatalogUnit> stage1 = new ArrayList<>();
        stage1.add(new CatalogUnit(steps(tables)));
        stage1.add(new CatalogUnit(steps(views)));
        // functions and aggregates write the same schema functions map
        stage1.add(new CatalogUnit(steps(functions, aggregates)));
        stage1.add(new CatalogUnit(steps(types)));
        stage1.add(new CatalogUnit(steps(
                ftsParsers, ftsTemplates, ftsDictionaries, ftsConfigurations, operators)));
        // database-level child readers keep a single writer thread
        stage1.add(new CatalogUnit(steps(
                extensions, eventTriggers, casts, fdws, servers, userMappings, collations)));
        if (statistics != null) {
            stage1.add(new CatalogUnit(steps(statistics)));
        }
        return stage1;
    }

    /**
     * Builds the stage-2 units: readers resolving containers through
     * {@code schema.getStatementContainer} (plus the sequences reader, which
     * attaches identity sequences to table columns). Each reader writes a
     * distinct container map, so they parallelize across lanes freely.
     */
    private List<CatalogUnit> buildStage2Units() {
        List<CatalogUnit> stage2 = new ArrayList<>();
        stage2.add(new CatalogUnit(steps(triggers)));
        stage2.add(new CatalogUnit(steps(indices)));
        stage2.add(new CatalogUnit(steps(constraints)));
        stage2.add(new CatalogUnit(steps(rules)));
        if (policies != null) {
            stage2.add(new CatalogUnit(steps(policies)));
        }
        stage2.add(new CatalogUnit(steps(sequences)));
        return stage2;
    }

    private CatalogStep step(CatalogRead action) {
        List<IAnalysisLauncher> buffer = new ArrayList<>();
        canonicalBuffers.add(buffer);
        return new CatalogStep(buffer, action);
    }

    private static List<CatalogStep> steps(CatalogStep... steps) {
        List<CatalogStep> result = new ArrayList<>(steps.length);
        for (CatalogStep step : steps) {
            if (step != null) {
                result.add(step);
            }
        }
        return result;
    }

    private static Throwable addFailure(Throwable primary, Throwable secondary) {
        if (secondary == null) {
            return primary;
        }
        if (primary == null) {
            return secondary;
        }
        if (primary != secondary) {
            for (Throwable suppressed : primary.getSuppressed()) {
                if (suppressed == secondary) {
                    return primary;
                }
            }
            primary.addSuppressed(secondary);
        }
        return primary;
    }

    private static void rethrow(Throwable failure)
            throws SQLException, InterruptedException, XmlReaderException, IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof SQLException sql) {
            throw sql;
        }
        if (failure instanceof InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (failure instanceof XmlReaderException xml) {
            throw xml;
        }
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected parallel catalog reader failure", failure);
    }

    /** One reader invocation running on a lane. */
    @FunctionalInterface
    private interface CatalogRead {

        void read() throws SQLException, InterruptedException, XmlReaderException;
    }

    /** One reader with its canonical-slot launcher buffer. */
    private record CatalogStep(List<IAnalysisLauncher> buffer, CatalogRead action) {
    }

    /** Steps that must run sequentially on one lane. */
    private record CatalogUnit(List<CatalogStep> steps) {
    }
}
