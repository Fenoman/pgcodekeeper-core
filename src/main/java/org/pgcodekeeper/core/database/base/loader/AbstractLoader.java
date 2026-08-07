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
package org.pgcodekeeper.core.database.base.loader;

import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;
import org.pgcodekeeper.core.database.base.parser.FullAnalyze;
import org.pgcodekeeper.core.database.base.parser.ParserExecutionPolicy;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.PhaseTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base database loader
 */
public abstract class AbstractLoader<T extends IDatabase> implements ILoader {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractLoader.class);

    private enum LifecycleState {
        OPEN,
        CANCELLED,
        CLOSED
    }

    protected Queue<AntlrTask<?>> antlrTasks;
    protected final ISettings settings;
    protected String currentOperation;
    protected int version;
    protected String databaseName;
    private final AtomicReference<LifecycleState> lifecycle =
            new AtomicReference<>(LifecycleState.OPEN);
    private final AtomicBoolean loadResourcesReleased = new AtomicBoolean(true);
    private boolean ownsParserTasks;
    private T loadedDb;

    protected boolean isPreloaded = false;

    protected AbstractLoader(ISettings settings, String databaseName) {
        this(settings, databaseName, null);
    }

    protected AbstractLoader(ISettings settings, String databaseName,
            Queue<AntlrTask<?>> inheritedTasks) {
        this.settings = settings;
        this.databaseName = databaseName;
        Queue<AntlrTask<?>> effectiveTasks = inheritedTasks != null
                ? inheritedTasks
                : settings instanceof ParserTaskQueueProvider provider
                        ? provider.getParserTaskQueue()
                        : null;
        if (effectiveTasks != null) {
            this.antlrTasks = effectiveTasks;
            this.ownsParserTasks = false;
        } else {
            ParserExecutionPolicy parserPolicy = settings.getParserExecutionPolicy();
            this.antlrTasks = AntlrTaskManager.createTaskQueue(parserPolicy == null
                    ? ParserExecutionPolicy.SHARED
                    : parserPolicy);
            this.ownsParserTasks = true;
        }
    }

    @Override
    public T load() throws IOException, InterruptedException {
        requireOpenForLoad();
        if (loadedDb != null) {
            return loadedDb;
        }
        loadResourcesReleased.set(false);
        Throwable failure = null;
        try {
            long start = PhaseTimer.start();
            preLoad();
            T database = loadInternal();
            requireOpenForLoad();
            loadedDb = database;
            PhaseTimer.end("structural_load", start, getClass().getSimpleName());
            return loadedDb;
        } catch (IOException | InterruptedException | RuntimeException | Error ex) {
            failure = ex;
            abortTasks(ex);
            throw ex;
        } finally {
            try {
                releaseLoadResourcesOnce();
            } catch (RuntimeException | Error ex) {
                if (failure == null) {
                    throw ex;
                }
                if (failure != ex) {
                    failure.addSuppressed(ex);
                }
            }
        }
    }

    @Override
    public void cancel() throws IOException {
        if (lifecycle.compareAndSet(LifecycleState.OPEN, LifecycleState.CANCELLED)) {
            if (ownsParserTasks) {
                AntlrTaskManager.requestAbort(antlrTasks);
            }
        }
    }

    /**
     * Performs terminal owner-side cleanup. This method must only be called
     * after the active load owner has terminated, or before work is submitted;
     * {@link #cancel()} is the cross-thread operation.
     */
    @Override
    public void close() throws IOException {
        if (lifecycle.getAndSet(LifecycleState.CLOSED) == LifecycleState.CLOSED) {
            return;
        }

        Throwable failure = null;
        if (ownsParserTasks) {
            try {
                AntlrTaskManager.abort(antlrTasks);
            } catch (RuntimeException | Error ex) {
                failure = ex;
            }

            try {
                AntlrTaskManager.close(antlrTasks);
            } catch (RuntimeException | Error ex) {
                if (failure == null) {
                    failure = ex;
                } else if (failure != ex) {
                    failure.addSuppressed(ex);
                }
            }
        }

        try {
            releaseLoadResourcesOnce();
        } catch (RuntimeException | Error ex) {
            if (failure == null) {
                failure = ex;
            } else if (failure != ex) {
                failure.addSuppressed(ex);
            }
        }
        rethrowCloseFailure(failure);
    }

    protected final boolean isCancellationRequested() {
        return lifecycle.get() == LifecycleState.CANCELLED;
    }

    @Override
    public T getDatabase() {
        return loadedDb;
    }

    @Override
    public List<Object> getErrors() {
        return Collections.unmodifiableList(settings.getErrors());
    }

    public void addError(Object error) {
        settings.addError(error);
    }

    /**
     * Loads the database and performs full expression analysis.
     *
     * @return fully loaded and analyzed database
     * @throws IOException          if database loading fails
     * @throws InterruptedException if the loading process is interrupted
     */
    @Override
    public T loadAndAnalyze() throws IOException, InterruptedException {
        T db = load();
        IMonitor monitor = lifecycleAwareMonitor(getMonitor());
        IMonitor.checkCancelled(monitor);
        long start = PhaseTimer.start();
        if (replayAnalysis(db, monitor)) {
            PhaseTimer.end("replay_analyze", start, getClass().getSimpleName());
        } else {
            FullAnalyze.fullAnalyze(
                    db, settings.getErrors(), settings.getVersion(), monitor, antlrTasks);
            PhaseTimer.end("full_analyze", start, getClass().getSimpleName());
        }
        requireOpenForLoad();
        return db;
    }

    /**
     * Replays a cached analysis result instead of analyzing this model.
     * <p>
     * The default implementation always analyzes. A subclass that was given a
     * matching cached result overrides this hook and returns {@code true} only
     * when it applied that result in full; anything else must leave the model
     * untouched so this loader still runs the ordinary analysis.
     *
     * @param db      structurally loaded model
     * @param monitor cancellation monitor of this load
     * @return true when the analysis result was replayed and must not be redone
     * @throws InterruptedException if the load was cancelled
     */
    protected boolean replayAnalysis(T db, IMonitor monitor) throws InterruptedException {
        return false;
    }

    @Override
    public T loadAndAnalyze(IMetaContainer metadata)
            throws IOException, InterruptedException {
        T db = load();
        IMonitor monitor = lifecycleAwareMonitor(getMonitor());
        IMonitor.checkCancelled(monitor);
        long start = PhaseTimer.start();
        FullAnalyze.fullAnalyze(db, metadata, settings.getErrors(), monitor,
                antlrTasks);
        PhaseTimer.end("full_analyze", start, getClass().getSimpleName());
        requireOpenForLoad();
        return db;
    }

    private IMonitor lifecycleAwareMonitor(IMonitor delegate) {
        IMonitor effectiveDelegate = delegate == null ? new NullMonitor() : delegate;
        return new IMonitor() {
            @Override
            public void setCancelled(boolean cancelled) {
                effectiveDelegate.setCancelled(cancelled);
            }

            @Override
            public boolean isCancelled() {
                return isCancellationRequested() || effectiveDelegate.isCancelled();
            }

            @Override
            public void worked(int work) {
                effectiveDelegate.worked(work);
            }

            @Override
            public IMonitor createSubMonitor() {
                return lifecycleAwareMonitor(effectiveDelegate.createSubMonitor());
            }

            @Override
            public void setWorkRemaining(int size) {
                effectiveDelegate.setWorkRemaining(size);
            }

            @Override
            public void setTaskName(String name) {
                effectiveDelegate.setTaskName(name);
            }
        };
    }

    @Override
    public String getDatabaseName() {
        return databaseName;
    }

    protected void finishLoaders() throws InterruptedException, IOException {
        try {
            AntlrTaskManager.finish(antlrTasks);
        } catch (MonitorCancelledRuntimeException ex) {
            throw classifyMonitorCancellation(ex);
        }
    }

    protected boolean tryFinishAntlrTask() throws InterruptedException, IOException {
        try {
            return AntlrTaskManager.tryFinishNext(getActiveAntlrTasks());
        } catch (MonitorCancelledRuntimeException ex) {
            throw classifyMonitorCancellation(ex);
        }
    }

    /**
     * Returns the ANTLR task queue receiving submissions of the current
     * thread. The default is the single loader-owned queue; a JDBC loader
     * running lane-parallel catalog readers returns the lane-owned queue of a
     * bound worker thread instead.
     *
     * @return active ANTLR task queue for the current thread
     */
    protected Queue<AntlrTask<?>> getActiveAntlrTasks() {
        return antlrTasks;
    }

    /**
     * Makes a newly created nested loader borrow this operation's root parser
     * queue. Must be called before the nested loader starts loading.
     *
     * @param root loader owning the surrounding loading operation
     */
    protected final void borrowParserExecution(AbstractLoader<?> root) {
        if (this == root) {
            throw new IllegalArgumentException(
                    "A loader cannot borrow parser execution from itself");
        }
        Queue<AntlrTask<?>> rootTasks = root.antlrTasks;
        if (antlrTasks == rootTasks) {
            ownsParserTasks = false;
            return;
        }
        if (!ownsParserTasks) {
            throw new IllegalStateException(
                    "Nested loader already borrows another parser operation");
        }
        AntlrTaskManager.requireDrained(antlrTasks);
        AntlrTaskManager.close(antlrTasks);
        antlrTasks = rootTasks;
        ownsParserTasks = false;
    }

    /**
     * Creates a thread-confined parser queue for a parallel JDBC reader lane.
     * The queue shares this loader's root executor and is closed with it.
     *
     * @return parser queue for one JDBC lane
     */
    public final Queue<AntlrTask<?>> createSiblingParserTaskQueue() {
        return AntlrTaskManager.createSiblingTaskQueue(antlrTasks);
    }

    protected void debug(String message, Object... args) {
        if (LOG.isDebugEnabled()) {
            var msg = message.formatted(args);
            LOG.debug(msg);
        }
    }

    protected void info(String message, Object... args) {
        var msg = message.formatted(args);
        LOG.info(msg);
    }

    protected abstract T loadInternal() throws IOException, InterruptedException;

    /**
     * Creates a new database instance.
     *
     * @return new database instance of the appropriate type
     */
    protected abstract T createDatabase();

    /**
     * Releases references retained only while loading. Resource ownership and
     * closing remain with the concrete loading operation.
     */
    protected void releaseLoadResources() {
        // no-op by default
    }

    private void releaseLoadResourcesOnce() {
        if (loadResourcesReleased.compareAndSet(false, true)) {
            releaseLoadResources();
        }
    }

    protected final void requireOpenForLoad() throws InterruptedException {
        switch (lifecycle.get()) {
        case CLOSED -> throw new IllegalStateException("Loader is closed");
        case CANCELLED -> throw new InterruptedException();
        case OPEN -> {
            // continue loading
        }
        }
    }

    /**
     * Rejects owner resource publication after terminal close while allowing a
     * concurrent cancellation to self-claim the late resource.
     */
    protected final void requireNotClosedForRegistration() {
        if (lifecycle.get() == LifecycleState.CLOSED) {
            throw new IllegalStateException("Loader is closed");
        }
    }

    private static void rethrowCloseFailure(Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException(failure);
    }

    @Override
    public ISettings getSettings() {
        return settings;
    }

    public IMonitor getMonitor() {
        return settings.getMonitor();
    }

    public boolean isAllowedSchema(String schemaName) {
        return settings.isAllowedSchema(schemaName);
    }

    protected final void abortTasks(Throwable failure) {
        try {
            AntlrTaskManager.abort(antlrTasks);
        } catch (RuntimeException | Error ex) {
            if (failure != ex) {
                failure.addSuppressed(ex);
            }
        }
    }

    private InterruptedException classifyMonitorCancellation(
            MonitorCancelledRuntimeException cancellation) {
        IMonitor monitor = getMonitor();
        boolean active = Thread.currentThread().isInterrupted()
                || isCancellationRequested()
                || monitor != null && monitor.isCancelled();
        if (!active) {
            throw cancellation;
        }

        InterruptedException interrupted = new InterruptedException();
        AntlrTaskManager.mergeSuppressed(interrupted, cancellation);
        return interrupted;
    }
}
