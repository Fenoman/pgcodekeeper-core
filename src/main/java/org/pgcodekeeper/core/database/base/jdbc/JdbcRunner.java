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
package org.pgcodekeeper.core.database.base.jdbc;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.pgcodekeeper.core.callable.QueriesBatchCallable;
import org.pgcodekeeper.core.callable.QueryCallable;
import org.pgcodekeeper.core.callable.ResultSetCallable;
import org.pgcodekeeper.core.callable.StatementCallable;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.reporter.IProgressReporter;
import org.pgcodekeeper.core.utils.DaemonThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC statement execution runner with progress monitoring and cancellation support.
 * Provides methods for executing SQL statements, prepared statements, and batch operations
 * with progress tracking and interrupt handling.
 */
public class JdbcRunner {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcRunner.class);

    private static final ExecutorService THREAD_POOL = new ThreadPoolExecutor(1,
            Integer.MAX_VALUE, 2, TimeUnit.SECONDS, new SynchronousQueue<>(),
            new DaemonThreadFactory());

    private static final int SLEEP_TIME = 20;

    private final IMonitor monitor;
    private final JdbcCancellation cancellation;
    private final ExecutorService executor;
    private final ThreadLocal<Throwable> activeFailureDrain = new ThreadLocal<>();

    /**
     * Creates a new JDBC runner with a null progress monitor.
     */
    public JdbcRunner() {
        this(new NullMonitor());
    }

    /**
     * Creates a new JDBC runner with the specified progress monitor.
     *
     * @param monitor the progress monitor for tracking execution and handling cancellation
     */
    public JdbcRunner(IMonitor monitor) {
        this(monitor, new JdbcCancellation());
    }

    /**
     * Creates a new JDBC runner with shared cancellation state.
     *
     * @param monitor      progress monitor for execution cancellation
     * @param cancellation internal active-operation cancellation state
     */
    public JdbcRunner(IMonitor monitor, JdbcCancellation cancellation) {
        this(monitor, cancellation, THREAD_POOL);
    }

    JdbcRunner(IMonitor monitor, JdbcCancellation cancellation, ExecutorService executor) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Executes a prepared statement with no return value.
     *
     * @param st the prepared statement to execute
     * @throws SQLException         if a database access error occurs
     * @throws InterruptedException if execution is interrupted
     */
    public void run(PreparedStatement st) throws SQLException, InterruptedException {
        runScript(new QueryCallable(st), false, false);
    }

    /**
     * Executes a statement using the given script with no return value.
     *
     * @param st     the statement to execute
     * @param script the SQL script to execute
     * @throws SQLException         if a database access error occurs
     * @throws InterruptedException if execution is interrupted
     */
    public void run(Statement st, String script) throws SQLException, InterruptedException {
        runScript(new QueryCallable(st, script), false, false);
    }

    /**
     * Executes a script using a new connection from the connector.
     *
     * @param connector the JDBC connector for database connection
     * @param script    the SQL script to execute
     * @throws SQLException         if a database access error occurs
     * @throws IOException          if connection creation fails
     * @throws InterruptedException if execution is interrupted
     */
    public void run(IJdbcConnector connector, String script)
            throws SQLException, IOException, InterruptedException {
        runOwnedConnection(connector, (connection, statement) ->
                runScript(new QueryCallable(statement, script), false, true));
    }

    /**
     * Executes statement batches with no return value.
     *
     * @param connector database connection information
     * @param batches   list of query batches to execute
     * @param reporter  progress and result reporter
     * @throws SQLException         if a database access error occurs
     * @throws IOException          if connection creation fails
     * @throws InterruptedException if execution is interrupted
     */
    public void runBatches(IJdbcConnector connector, List<ObjectLocation> batches,
                           IProgressReporter reporter) throws SQLException, IOException, InterruptedException {
        runOwnedConnection(connector, (connection, statement) ->
                runScript(new QueriesBatchCallable(statement, batches, monitor, reporter,
                        connection, connector.getBatchDelimiter()), false, true));
    }

    /**
     * Executes a prepared statement and returns the result set.
     *
     * @param st the prepared statement to execute
     * @return the result set from the query
     * @throws SQLException         if a database access error occurs
     * @throws InterruptedException if execution is interrupted
     */
    public ResultSet runScript(PreparedStatement st) throws InterruptedException, SQLException {
        return runScript(new ResultSetCallable(st), true, false);
    }

    /**
     * Executes a statement using the given script and returns the result set.
     *
     * @param st     the statement to execute
     * @param script the SQL script to execute
     * @return the result set from the query
     * @throws SQLException         if a database access error occurs
     * @throws InterruptedException if execution is interrupted
     */
    public ResultSet runScript(Statement st, String script) throws InterruptedException, SQLException {
        return runScript(new ResultSetCallable(st, script), true, false);
    }

    private <T> T runScript(StatementCallable<T> callable, boolean retainStatement,
            boolean ownedConnection)
            throws InterruptedException, SQLException {
        checkCancellationBeforeSubmit();
        Statement activeStatement = callable.getStatement();
        try {
            registerStatement(activeStatement);
            checkCancellationBeforeSubmit();
        } catch (InterruptedException | RuntimeException | Error e) {
            cancellation.clearStatement(activeStatement);
            throw e;
        }

        Future<T> queryFuture;
        try {
            queryFuture = cancellation.submitFuture(executor, callable);
        } catch (IOException e) {
            cancellation.clearStatement(activeStatement);
            throw interruptedWith(e);
        } catch (InterruptedException | RuntimeException | Error e) {
            cancellation.clearStatement(activeStatement);
            throw e;
        }

        boolean successful = false;
        try {
            T result = waitFor(queryFuture);
            if (!cancellation.completeSuccess(queryFuture, activeStatement,
                    retainStatement, isCancellationRequestedDuringActiveOperation())) {
                throw requestCancellation(new InterruptedException(Messages.JdbcRunner_script_execution));
            }
            successful = true;
            return result;
        } catch (CancellationException e) {
            // waitFor only lets an independent Future cancellation escape
            // without first draining the active JDBC operation.
            throw e;
        } catch (RuntimeException e) {
            throw drainActiveFailure(e);
        } catch (Error e) {
            throw drainActiveFailure(e);
        } finally {
            if (!successful) {
                cancellation.clearFuture(queryFuture);
                if (!cancellation.isCancellationRequested()) {
                    cancellation.clearStatement(activeStatement);
                }
            }
            if (!ownedConnection) {
                activeFailureDrain.remove();
            }
        }
    }

    private void runOwnedConnection(IJdbcConnector connector, OwnedConnectionAction action)
            throws IOException, SQLException, InterruptedException {
        checkCancellationBeforeSubmit();

        Connection connection = null;
        Statement statement = null;
        Throwable failure = null;
        try {
            // Connection acquisition is an explicit uncancellable boundary:
            // there is no resource to publish until the connector returns.
            connection = connector.getConnection();
            cancellation.registerConnection(connection);
            checkCancellationBeforeSubmit();
            statement = connection.createStatement();
            action.run(connection, statement);
        } catch (IOException | SQLException | InterruptedException | RuntimeException | Error e) {
            failure = e;
        }

        boolean preserveFailureIdentity = failure != null && activeFailureDrain.get() == failure;
        activeFailureDrain.remove();
        Statement ownedStatement = statement;
        Connection ownedConnection = connection;
        var cleanup = new CleanupState(failure);
        observeCancellation(cleanup);

        if (ownedStatement != null) {
            cleanup.run(() -> cancellation.clearStatement(ownedStatement));
        }
        if (ownedConnection != null) {
            cleanup.run(() -> cancellation.clearConnection(ownedConnection));
        }

        observeCancellation(cleanup);
        if (cleanup.cancellationObserved) {
            cleanup.run(cancellation::cancelActive);
        }
        if (ownedStatement != null) {
            cleanup.run(ownedStatement::close);
        }
        if (ownedConnection != null) {
            cleanup.run(ownedConnection::close);
        }

        observeCancellation(cleanup);
        if (cleanup.cancellationObserved) {
            cleanup.run(cancellation::cancelActive);
        }

        if (cleanup.cancellationObserved && !preserveFailureIdentity
                && !(cleanup.failure instanceof InterruptedException)) {
            InterruptedException interrupted = new InterruptedException(Messages.JdbcRunner_script_execution);
            if (cleanup.failure != null) {
                addSuppressed(interrupted, cleanup.failure);
            }
            cleanup.failure = interrupted;
        }
        throwFailure(cleanup.failure);
    }

    private void observeCancellation(CleanupState cleanup) {
        try {
            cleanup.cancellationObserved |= isCancellationRequested();
        } catch (RuntimeException | Error e) {
            cleanup.add(e);
            cleanup.cancellationObserved |= cancellation.isCancellationRequested()
                    || Thread.currentThread().isInterrupted();
        }
    }

    private static void throwFailure(Throwable failure)
            throws IOException, SQLException, InterruptedException {
        if (failure instanceof IOException e) {
            throw e;
        }
        if (failure instanceof SQLException e) {
            throw e;
        }
        if (failure instanceof InterruptedException e) {
            throw e;
        }
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
    }

    private void checkCancellationBeforeSubmit() throws InterruptedException {
        if (isCancellationRequested()) {
            LOG.info(Messages.JdbcRunner_script_execution);
            throw requestCancellation(new InterruptedException(Messages.JdbcRunner_script_execution));
        }
    }

    private <T> T waitFor(Future<T> queryFuture) throws InterruptedException, SQLException {
        while (true) {
            if (isCancellationRequestedDuringActiveOperation()) {
                LOG.info(Messages.JdbcRunner_script_execution);
                throw requestCancellation(new InterruptedException(Messages.JdbcRunner_script_execution));
            }
            try {
                T result = queryFuture.get(SLEEP_TIME, TimeUnit.MILLISECONDS);
                if (isCancellationRequestedDuringActiveOperation()) {
                    throw requestCancellation(new InterruptedException(Messages.JdbcRunner_script_execution));
                }
                return result;
            } catch (InterruptedException e) {
                throw requestCancellation(e);
            } catch (CancellationException e) {
                if (activeFailureDrain.get() == e) {
                    throw e;
                }
                if (isCancellationRequestedDuringActiveOperation()) {
                    throw requestCancellation(new InterruptedException(Messages.JdbcRunner_script_execution));
                }
                throw e;
            } catch (ExecutionException e) {
                if (isCancellationRequestedDuringActiveOperation()) {
                    InterruptedException failure = new InterruptedException(Messages.JdbcRunner_script_execution);
                    Throwable workerFailure = e.getCause();
                    addSuppressed(failure, workerFailure == null ? e : workerFailure);
                    throw requestCancellation(failure);
                }
                Throwable t = e.getCause();
                throw new SQLException(t.getLocalizedMessage(), e);
            } catch (TimeoutException e) {
                // no action: check cancellation and try again
            }
        }
    }

    private void registerStatement(Statement activeStatement) throws InterruptedException {
        try {
            cancellation.registerStatement(activeStatement);
        } catch (IOException e) {
            throw interruptedWith(e);
        }
    }

    private boolean isCancellationRequested() {
        return cancellation.isCancellationRequested()
                || monitor.isCancelled()
                || Thread.currentThread().isInterrupted();
    }

    private boolean isCancellationRequestedDuringActiveOperation() {
        try {
            return isCancellationRequested();
        } catch (RuntimeException e) {
            throw drainActiveFailure(e);
        } catch (Error e) {
            throw drainActiveFailure(e);
        }
    }

    private <T extends Throwable> T drainActiveFailure(T failure) {
        if (activeFailureDrain.get() != failure) {
            requestCancellation(failure);
            activeFailureDrain.set(failure);
        }
        return failure;
    }

    private <T extends Throwable> T requestCancellation(T failure) {
        try {
            cancellation.cancelActive();
        } catch (IOException | RuntimeException | Error e) {
            addSuppressed(failure, e);
        }
        return failure;
    }

    private static InterruptedException interruptedWith(Throwable failure) {
        InterruptedException interrupted = new InterruptedException(Messages.JdbcRunner_script_execution);
        addSuppressed(interrupted, failure);
        return interrupted;
    }

    private static void addSuppressed(Throwable primary, Throwable secondary) {
        if (primary == secondary) {
            return;
        }
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == secondary) {
                return;
            }
        }
        primary.addSuppressed(secondary);
    }

    @FunctionalInterface
    private interface OwnedConnectionAction {

        void run(Connection connection, Statement statement)
                throws SQLException, InterruptedException;
    }

    @FunctionalInterface
    private interface CleanupAction {

        void run() throws IOException, SQLException;
    }

    private static final class CleanupState {

        private Throwable failure;
        private boolean cancellationObserved;

        private CleanupState(Throwable failure) {
            this.failure = failure;
        }

        private void run(CleanupAction action) {
            try {
                action.run();
            } catch (IOException | SQLException | RuntimeException | Error e) {
                add(e);
            }
        }

        private void add(Throwable secondary) {
            if (failure == null) {
                failure = secondary;
            } else {
                addSuppressed(failure, secondary);
            }
        }
    }
}
