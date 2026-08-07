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

import java.io.IOException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyResidualTransport;
import org.pgcodekeeper.core.monitor.IMonitor;

/**
 * Same-connection JDBC transport for bounded residual routine bodies. One
 * registered prepared statement is reused for every batch in the loader's
 * existing read-only repeatable-read transaction.
 */
final class PgRoutineBodyResidualJdbcTransport
        implements PgRoutineBodyResidualTransport {

    private static final String QUERY = """
            SELECT
              requested.ordinality::bigint AS body_ordinal,
              requested.oid::bigint AS body_oid,
              proc.prosrc AS body_prosrc
            FROM pg_catalog.unnest(?::oid[]) WITH ORDINALITY
              AS requested(oid, ordinality)
            JOIN pg_catalog.pg_proc proc ON proc.oid = requested.oid
            ORDER BY requested.ordinality""";

    private final PgJdbcLoader loader;
    private final Connection connection;

    private PreparedStatement statement;
    private boolean closed;

    PgRoutineBodyResidualJdbcTransport(PgJdbcLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
        connection = Objects.requireNonNull(loader.getConnection(), "loader connection");
    }

    @Override
    public void fetch(Long[] orderedOids, RowConsumer rows, IMonitor monitor)
            throws IOException, InterruptedException {
        Objects.requireNonNull(orderedOids, "orderedOids");
        Objects.requireNonNull(rows, "rows");
        if (orderedOids.length == 0) {
            throw new IllegalArgumentException("Residual body batch must not be empty");
        }
        if (closed) {
            throw new IllegalStateException("Residual body transport is closed");
        }

        loader.checkCatalogReaderCancellation();
        PreparedStatement activeStatement = ensureStatement();
        Array sqlArray = null;
        ResultSet result = null;
        Throwable failure = null;
        try {
            sqlArray = connection.createArrayOf("oid", orderedOids);
            activeStatement.setArray(1, sqlArray);
            result = loader.getRunner().runScript(activeStatement);
            while (result.next()) {
                checkCancelled(monitor);
                long ordinal = result.getLong("body_ordinal");
                if (result.wasNull()) {
                    throw new IOException("NULL residual PostgreSQL routine body ordinal");
                }
                long oid = result.getLong("body_oid");
                if (result.wasNull()) {
                    throw new IOException("NULL residual PostgreSQL routine body OID");
                }
                String raw = result.getString("body_prosrc");
                if (raw == null || result.wasNull()) {
                    throw new IOException("NULL residual PostgreSQL routine body payload");
                }
                rows.accept(ordinal, oid, raw);
            }
        } catch (SQLException | IOException | InterruptedException | RuntimeException | Error ex) {
            failure = ex;
        }

        failure = closeResult(result, failure);
        failure = freeArray(sqlArray, failure);
        failure = clearParameters(activeStatement, failure);
        finishOperation(failure);
        loader.checkCatalogReaderCancellation();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        PreparedStatement ownedStatement = statement;
        statement = null;
        if (ownedStatement == null) {
            return;
        }

        Throwable failure = null;
        try {
            loader.clearCatalogStatement(ownedStatement);
        } catch (RuntimeException | Error ex) {
            failure = ex;
        }
        try {
            ownedStatement.close();
        } catch (SQLException | RuntimeException | Error ex) {
            failure = addFailure(failure, ex);
        }
        rethrowCloseFailure(failure);
    }

    private PreparedStatement ensureStatement() throws IOException, InterruptedException {
        if (statement != null) {
            return statement;
        }

        PreparedStatement candidate = null;
        Throwable failure = null;
        try {
            candidate = loader.prepareCatalogStatement(QUERY);
            loader.registerCatalogStatement(candidate);
        } catch (SQLException | InterruptedException | RuntimeException | Error ex) {
            failure = ex;
        }
        if (failure != null) {
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (SQLException | RuntimeException | Error ex) {
                    failure = addFailure(failure, ex);
                }
            }
            finishOperation(failure);
            throw new IllegalStateException("Unreachable residual statement failure");
        }
        statement = candidate;
        return candidate;
    }

    private void finishOperation(Throwable failure)
            throws IOException, InterruptedException {
        if (failure == null) {
            return;
        }
        InterruptedException cancellation = loader.classifyCatalogReaderCancellation(failure);
        if (cancellation != null) {
            throw cancellation;
        }
        if (failure instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof SQLException sql) {
            throw new IOException("Failed to fetch residual PostgreSQL routine bodies", sql);
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected residual body transport failure", failure);
    }

    private static Throwable closeResult(ResultSet result, Throwable failure) {
        if (result == null) {
            return failure;
        }
        try {
            result.close();
        } catch (SQLException | RuntimeException | Error ex) {
            failure = addFailure(failure, ex);
        }
        return failure;
    }

    private static Throwable freeArray(Array array, Throwable failure) {
        if (array == null) {
            return failure;
        }
        try {
            array.free();
        } catch (SQLException | RuntimeException | Error ex) {
            failure = addFailure(failure, ex);
        }
        return failure;
    }

    private static Throwable clearParameters(
            PreparedStatement statement, Throwable failure) {
        try {
            statement.clearParameters();
        } catch (SQLException | RuntimeException | Error ex) {
            failure = addFailure(failure, ex);
        }
        return failure;
    }

    private static Throwable addFailure(Throwable primary, Throwable secondary) {
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

    private static void rethrowCloseFailure(Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof SQLException sql) {
            throw new IOException("Failed to close residual PostgreSQL routine transport", sql);
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected residual body close failure", failure);
    }

    private static void checkCancelled(IMonitor monitor) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        IMonitor.checkCancelled(monitor);
    }
}
