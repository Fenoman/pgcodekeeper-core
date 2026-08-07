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

import org.antlr.v4.runtime.Parser;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.launcher.AnalysisLauncherRedirect;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.loader.IJdbcLoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ISchema;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.jdbc.ICatalogRowCache;
import org.pgcodekeeper.core.database.base.jdbc.JdbcCancellation;
import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;
import org.pgcodekeeper.core.database.base.parser.TwoStageAntlrParse;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.Utils;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Base JDBC database loader
 */
public abstract class AbstractJdbcLoader<T extends IDatabase> extends AbstractLoader<T> implements IJdbcLoader {

    protected final JdbcRunner runner;
    protected final IJdbcConnector connector;
    protected final Map<Object, ISchema> schemaIds = new HashMap<>();

    private final JdbcCancellation jdbcCancellation = new JdbcCancellation();
    private final Object preloadPublicationMonitor = new Object();
    private final ThreadLocal<JdbcCatalogLane> activeLane = new ThreadLocal<>();
    private ObjectReference currentObject;

    protected Map<Long, String> cachedRolesNamesByOid;
    protected Connection connection;
    protected Statement statement;

    protected AbstractJdbcLoader(IJdbcConnector connector, ISettings settings) {
        super(settings, connector.getDbName());
        this.connector = connector;
        IMonitor monitor = getMonitor();
        this.runner = new JdbcRunner(monitor == null ? new NullMonitor() : monitor, jdbcCancellation);
    }

    @Override
    public void cancel() throws IOException {
        Throwable failure = null;
        try {
            synchronized (preloadPublicationMonitor) {
                super.cancel();
            }
        } catch (IOException | RuntimeException | Error ex) {
            failure = ex;
        }

        if (isCancellationRequested()) {
            try {
                jdbcCancellation.cancelActive();
            } catch (IOException | RuntimeException | Error ex) {
                failure = addFailure(failure, ex);
            }
        }
        rethrowCancellationFailure(failure);
    }

    protected final void registerActiveConnection(Connection activeConnection)
            throws IOException, InterruptedException {
        requireNotClosedForRegistration();
        jdbcCancellation.registerConnection(activeConnection);
        try {
            requireOpenForLoad();
        } catch (InterruptedException ex) {
            throw drainJdbcCancellation(ex);
        } catch (RuntimeException | Error ex) {
            jdbcCancellation.clearConnection(activeConnection);
            throw ex;
        }
    }

    protected final void clearActiveConnection(Connection activeConnection) {
        jdbcCancellation.clearConnection(activeConnection);
    }

    protected final void registerActiveStatement(Statement activeStatement)
            throws IOException, InterruptedException {
        requireNotClosedForRegistration();
        jdbcCancellation.registerStatement(activeStatement);
        try {
            requireOpenForLoad();
        } catch (InterruptedException ex) {
            throw drainJdbcCancellation(ex);
        } catch (RuntimeException | Error ex) {
            jdbcCancellation.clearStatement(activeStatement);
            throw ex;
        }
    }

    protected final void clearActiveStatement(Statement activeStatement) {
        jdbcCancellation.clearStatement(activeStatement);
    }

    /**
     * Clears cancellation ownership and physically closes resources owned by one JDBC load
     * operation. Every cleanup step is attempted in the strict order statement barrier,
     * connection barrier, statement close, connection close. Retained fields are cleared only
     * when they still contain the exact owned identities.
     * <p>
     * Cancellation classification and the final publication gate run only after both physical
     * close attempts. The returned failure keeps the first failure as primary and suppresses
     * later distinct identities at most once.
     *
     * @param ownedConnection connection returned to the operation, or {@code null}
     * @param ownedStatement statement created by the operation, or {@code null}
     * @param failure operation failure before cleanup, or {@code null}
     * @return primary operation, cleanup, or cancellation failure; {@code null} on success
     */
    protected final Throwable finishOwnedJdbcResources(Connection ownedConnection,
            Statement ownedStatement, Throwable failure) {
        Throwable result = failure;

        if (ownedStatement != null) {
            try {
                clearActiveStatement(ownedStatement);
            } catch (RuntimeException | Error ex) {
                result = addFailure(result, ex);
            } finally {
                if (statement == ownedStatement) {
                    statement = null;
                }
            }
        }
        if (ownedConnection != null) {
            try {
                clearActiveConnection(ownedConnection);
            } catch (RuntimeException | Error ex) {
                result = addFailure(result, ex);
            } finally {
                if (connection == ownedConnection) {
                    connection = null;
                }
            }
        }
        if (ownedStatement != null) {
            try {
                ownedStatement.close();
            } catch (SQLException | RuntimeException | Error ex) {
                result = addFailure(result, ex);
            }
        }
        if (ownedConnection != null) {
            try {
                ownedConnection.close();
            } catch (SQLException | RuntimeException | Error ex) {
                result = addFailure(result, ex);
            }
        }

        if (result != null) {
            try {
                InterruptedException cancellation = classifyCatalogReaderCancellation(result);
                return cancellation == null ? result : cancellation;
            } catch (RuntimeException | Error ex) {
                return mergeFinalGateFailure(result, ex);
            }
        }

        try {
            checkCatalogReaderCancellation();
            return null;
        } catch (InterruptedException | RuntimeException | Error ex) {
            return ex;
        }
    }

    /**
     * Publishes successful preload only while loader cancellation is excluded by the same
     * monitor used for the lifecycle transition in {@link #cancel()}.
     */
    protected final void publishPreloaded() throws InterruptedException {
        checkCatalogReaderCancellation();
        synchronized (preloadPublicationMonitor) {
            checkPreloadPublicationCancellation();
            isPreloaded = true;
        }
    }

    /**
     * Rethrows an owned JDBC operation failure with cancellation identity preserved.
     */
    protected final void throwOwnedJdbcFailure(Throwable failure)
            throws IOException, InterruptedException {
        if (failure == null) {
            return;
        }
        if (failure instanceof InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof Exception exception) {
            throw new IOException(Messages.Connection_DatabaseJdbcAccessError.formatted(
                    getCurrentLocation(), exception.getLocalizedMessage()), exception);
        }
        throw new IOException(failure);
    }

    /**
     * Internal bridge for core catalog readers. This method is not a supported extension API.
     *
     * @param activeStatement statement to publish before reader configuration or execution
     * @throws InterruptedException if cancellation wins registration
     */
    public final void registerCatalogStatement(Statement activeStatement) throws InterruptedException {
        try {
            registerActiveStatement(activeStatement);
        } catch (IOException ex) {
            throw drainJdbcCancellation(neutralCancellation(ex));
        }
    }

    /**
     * Internal bridge for core catalog readers. This method is not a supported extension API.
     * Clearing performs the shared cancellation drain barrier before it returns.
     *
     * @param activeStatement exact statement instance previously published by the reader
     */
    public final void clearCatalogStatement(Statement activeStatement) {
        clearActiveStatement(activeStatement);
    }

    /**
     * Internal bridge for core catalog readers. This method is not a supported extension API.
     *
     * @param failure raw reader failure, or {@code null} after successful cleanup
     * @return neutral interruption with a checked raw failure suppressed, or {@code null} when
     *         cancellation was not requested or an unchecked raw failure must remain primary
     */
    public final InterruptedException classifyCatalogReaderCancellation(Throwable failure) {
        if (failure instanceof RuntimeException || failure instanceof Error) {
            preserveUncheckedCatalogReaderFailure(failure);
            return null;
        }

        try {
            requireOpenForLoad();
        } catch (InterruptedException ex) {
            return drainJdbcCancellation(neutralCancellation(failure));
        } catch (IllegalStateException ex) {
            addFailure(ex, failure);
            throw ex;
        }

        boolean monitorCancellation;
        try {
            IMonitor monitor = getMonitor();
            monitorCancellation = monitor != null && monitor.isCancelled();
        } catch (RuntimeException | Error ex) {
            addFailure(ex, failure);
            drainCatalogReaderCancellationIfRequested(ex);
            throw ex;
        }

        if (!isCancellationRequested() && !isJdbcCancellationRequested()
                && !monitorCancellation && !Thread.currentThread().isInterrupted()) {
            return null;
        }
        return drainJdbcCancellation(neutralCancellation(failure));
    }

    /**
     * Internal final-publication gate for core catalog readers. This method is not a supported
     * extension API and must be called after the reader's physical statement close.
     *
     * @throws InterruptedException if loader or JDBC cancellation prevents successful publication
     */
    public final void checkCatalogReaderCancellation() throws InterruptedException {
        try {
            requireOpenForLoad();
            checkThreadAndMonitorCancellation();
            checkJdbcCancellationRequested();
            requireOpenForLoad();
            checkThreadAndMonitorCancellation();
        } catch (InterruptedException ex) {
            throw drainJdbcCancellation(ex);
        } catch (RuntimeException | Error ex) {
            drainCatalogReaderCancellationIfRequested(ex);
            throw ex;
        }
    }

    protected final boolean isJdbcCancellationRequested() {
        return jdbcCancellation.isCancellationRequested();
    }

    protected final void checkJdbcCancellationRequested() throws InterruptedException {
        if (isJdbcCancellationRequested()) {
            throw new InterruptedException(Messages.JdbcRunner_script_execution);
        }
    }

    protected final InterruptedException interruptedByJdbcCancellation(Throwable failure) {
        if (!isJdbcCancellationRequested()) {
            throw new IllegalStateException("JDBC cancellation was not requested");
        }
        if (failure instanceof InterruptedException interrupted) {
            return interrupted;
        }

        var interrupted = new InterruptedException(Messages.JdbcRunner_script_execution);
        if (failure != null) {
            interrupted.addSuppressed(failure);
        }
        return interrupted;
    }

    protected <P extends Parser, R> void submitAntlrTask(BiFunction<List<Object>, String, P> parserCreateFunction,
                                                         Function<P, R> parserCtxReader, Consumer<R> finalizer) {
        String location = getCurrentLocation();
        ObjectReference object = getCurrentObject();
        List<IAnalysisLauncher> sink = getCurrentLauncherSink();
        List<Object> list = new ArrayList<>();
        AntlrTaskManager.submit(getActiveAntlrTasks(), () -> {
            IMonitor.checkCancelled(getMonitor());
            P p = parserCreateFunction.apply(list, location);
            // reader lambdas invoke the entry rule and then only navigate the
            // returned tree, so the whole reader is safe inside the two-stage
            // strategy: navigation never throws ParseCancellationException
            return TwoStageAntlrParse.parse(p, parserCtxReader);
        }, r -> {
            settings.addErrors(list);
            if (getMonitor().isCancelled()) {
                throw new MonitorCancelledRuntimeException();
            }
            setCurrentObject(object);
            if (list.isEmpty()) {
                AnalysisLauncherRedirect.run(sink, () -> finalizer.accept(r));
            }
        });
    }

    public void setOwner(AbstractStatement st, String owner) {
        if (!getSettings().isIgnorePrivileges()) {
            st.setOwner(owner);
        }
    }

    public void setCurrentOperation(String operation) {
        JdbcCatalogLane lane = activeLane.get();
        if (lane != null) {
            lane.setCurrentObject(null);
            lane.setCurrentOperation(operation);
        } else {
            currentObject = null;
            currentOperation = operation;
        }
        debug("%s", operation);
    }

    public void setCurrentObject(ObjectReference currentObject) {
        JdbcCatalogLane lane = activeLane.get();
        if (lane != null) {
            lane.setCurrentObject(currentObject);
        } else {
            this.currentObject = currentObject;
        }
        debug(Messages.JdbcLoaderBase_log_current_obj, currentObject);
    }

    /**
     * Binds a catalog lane to the current worker thread. Internal bridge for
     * the lane-parallel catalog orchestrator; not a supported extension API.
     *
     * @param lane lane whose state must route loader calls of this thread
     */
    public final void bindCatalogLane(JdbcCatalogLane lane) {
        if (activeLane.get() != null) {
            throw new IllegalStateException("A catalog lane is already bound to this thread");
        }
        activeLane.set(Objects.requireNonNull(lane, "lane"));
    }

    /**
     * Unbinds the catalog lane of the current worker thread. Internal bridge
     * for the lane-parallel catalog orchestrator; not a supported extension API.
     */
    public final void unbindCatalogLane() {
        activeLane.remove();
    }

    @Override
    protected final Queue<AntlrTask<?>> getActiveAntlrTasks() {
        JdbcCatalogLane lane = activeLane.get();
        return lane != null ? lane.getAntlrTasks() : antlrTasks;
    }

    private ObjectReference getCurrentObject() {
        JdbcCatalogLane lane = activeLane.get();
        return lane != null ? lane.getCurrentObject() : currentObject;
    }

    private String getCurrentOperation() {
        JdbcCatalogLane lane = activeLane.get();
        return lane != null ? lane.getCurrentOperation() : currentOperation;
    }

    /**
     * Returns the launcher buffer of the reader running on the current lane
     * thread, an active finalizer-installed redirect, or {@code null} on the
     * sequential path.
     */
    protected final List<IAnalysisLauncher> getCurrentLauncherSink() {
        List<IAnalysisLauncher> redirected = AnalysisLauncherRedirect.active();
        if (redirected != null) {
            return redirected;
        }
        JdbcCatalogLane lane = activeLane.get();
        return lane != null ? lane.getLauncherSink() : null;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    /**
     * Associates a schema ID with a schema object.
     *
     * @param schemaId the schema identifier
     * @param schema   the schema object to associate
     */
    public void putSchema(Object schemaId, ISchema schema) {
        schemaIds.put(schemaId, schema);
    }

    public final void setComment(AbstractStatement f, ResultSet res) throws SQLException {
        String comment = res.getString("description");
        if (comment != null && !comment.isEmpty()) {
            f.setComment(Utils.checkNewLines(Utils.quoteString(comment), getSettings().isKeepNewlines()));
        }
    }

    public int getVersion() {
        return version;
    }

    public JdbcRunner getRunner() {
        return runner;
    }

    /**
     * Row-level catalog cache strategy offered to the base catalog readers.
     * The default loader has none; a subclass returns a non-null strategy
     * only while an active load supports row-level caching.
     *
     * @return active row cache strategy, or {@code null} when disabled
     */
    public ICatalogRowCache getCatalogRowCache() {
        return null;
    }

    public Connection getConnection() {
        return connection;
    }

    /**
     * Returns the connection that owns catalog statements on the current
     * thread. Parallel readers use their bound lane; sequential readers use
     * the primary loader connection.
     */
    public final Connection getCatalogConnection() {
        JdbcCatalogLane lane = activeLane.get();
        return lane != null ? lane.getConnection() : connection;
    }

    @Override
    public final boolean tryFinishAntlrTask() throws InterruptedException, IOException {
        return super.tryFinishAntlrTask();
    }

    /**
     * Creates a statement for reading database catalogs and applies the configured fetch size.
     *
     * @param connection connection that creates the statement
     * @return configured statement
     * @throws SQLException if creating or configuring the statement fails
     */
    public final Statement createCatalogStatement(Connection connection) throws SQLException {
        return configureFetchSize(connection.createStatement());
    }

    /**
     * Configures a statement that has already been published to the cancellation registry.
     * Ownership and cleanup remain with the caller.
     */
    protected final void configureOwnedCatalogStatement(Statement statement) throws SQLException {
        int fetchSize = settings.getJdbcFetchSize();
        if (fetchSize != 0) {
            statement.setFetchSize(fetchSize);
        }
    }

    /**
     * Creates a prepared statement for reading database catalogs and applies the configured fetch size.
     *
     * @param sql catalog query
     * @return configured prepared statement
     * @throws SQLException if creating or configuring the statement fails
     */
    public final PreparedStatement prepareCatalogStatement(String sql) throws SQLException {
        return configureFetchSize(getCatalogConnection().prepareStatement(sql));
    }

    private <S extends Statement> S configureFetchSize(S statement) throws SQLException {
        try {
            int fetchSize = settings.getJdbcFetchSize();
            if (fetchSize == 0) {
                return statement;
            }
            statement.setFetchSize(fetchSize);
            return statement;
        } catch (SQLException | RuntimeException | Error ex) {
            try {
                statement.close();
            } catch (SQLException | RuntimeException | Error closeEx) {
                if (closeEx != ex) {
                    ex.addSuppressed(closeEx);
                }
            }
            throw ex;
        }
    }

    public String getCurrentLocation() {
        StringBuilder sb = new StringBuilder("jdbc:");
        ObjectReference object = getCurrentObject();
        if (object == null) {
            return sb.append(getCurrentOperation()).toString();
        }
        if (object.schema() != null) {
            sb.append('/').append(object.schema());
        }
        if (object.table() != null) {
            sb.append('/').append(object.table());
        }
        if (object.column() != null) {
            sb.append('/').append(object.column());
        }
        return sb.toString();
    }

    public Statement getStatement() {
        return statement;
    }

    /**
     * Returns a string representation of loaded schemas.
     *
     * @return string containing schema information
     */
    public String getSchemas() {
        return schemaIds.keySet().stream().map(Object::toString).collect(Collectors.joining(", "));
    }

    /**
     * Returning {@link ISchema} of some id
     *
     * @param schemaId the schema identifier
     * @return {@link ISchema} - the schema object to associate
     */
    public ISchema getSchema(Object schemaId) {
        return schemaIds.get(schemaId);
    }

    @Override
    protected void releaseLoadResources() {
        try {
            jdbcCancellation.clearReferences();
        } finally {
            connection = null;
            statement = null;
            schemaIds.clear();
            if (cachedRolesNamesByOid != null) {
                cachedRolesNamesByOid.clear();
                cachedRolesNamesByOid = null;
            }
            currentObject = null;
            currentOperation = null;
        }
    }

    private static Throwable addFailure(Throwable primary, Throwable secondary) {
        if (secondary == null) {
            return primary;
        }
        if (primary == null) {
            return secondary;
        }
        if (primary == secondary) {
            return primary;
        }
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == secondary) {
                return primary;
            }
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    private static Throwable mergeFinalGateFailure(Throwable failure, Throwable gateFailure) {
        if (failure instanceof RuntimeException || failure instanceof Error
                || failure instanceof InterruptedException) {
            return addFailure(failure, gateFailure);
        }
        return addFailure(gateFailure, failure);
    }

    /**
     * Checks only loader-owned atomic state while holding the preload publication monitor.
     * Arbitrary delegate monitor callbacks must remain outside that critical section.
     */
    private void checkPreloadPublicationCancellation() throws InterruptedException {
        requireOpenForLoad();
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException(Messages.JdbcRunner_script_execution);
        }
        checkJdbcCancellationRequested();
    }

    private InterruptedException drainJdbcCancellation(InterruptedException failure) {
        try {
            jdbcCancellation.cancelActive();
        } catch (IOException | RuntimeException | Error ex) {
            addFailure(failure, ex);
        }
        return failure;
    }

    private static InterruptedException neutralCancellation(Throwable failure) {
        if (failure instanceof InterruptedException interrupted) {
            return interrupted;
        }
        var interrupted = new InterruptedException(Messages.JdbcRunner_script_execution);
        if (failure != null) {
            addFailure(interrupted, failure);
        }
        return interrupted;
    }

    private void preserveUncheckedCatalogReaderFailure(Throwable failure) {
        drainCatalogReaderCancellationIfRequested(failure);

        try {
            InterruptedException cancellation = classifyCatalogReaderCancellation(null);
            if (cancellation != null) {
                for (Throwable suppressed : cancellation.getSuppressed()) {
                    addFailure(failure, suppressed);
                }
            }
        } catch (RuntimeException | Error ex) {
            addFailure(failure, ex);
        } finally {
            drainCatalogReaderCancellationIfRequested(failure);
        }
    }

    private void drainCatalogReaderCancellationIfRequested(Throwable failure) {
        if (!isCancellationRequested() && !isJdbcCancellationRequested()
                && !Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            jdbcCancellation.cancelActive();
        } catch (IOException | RuntimeException | Error ex) {
            addFailure(failure, ex);
        }
    }

    private void checkThreadAndMonitorCancellation() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException(Messages.JdbcRunner_script_execution);
        }
        IMonitor.checkCancelled(getMonitor());
    }

    private static void rethrowCancellationFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
