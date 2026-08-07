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
import java.sql.*;

import org.pgcodekeeper.core.database.api.jdbc.IJdbcReader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.base.loader.AbstractJdbcLoader;
import org.pgcodekeeper.core.exception.XmlReaderException;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.utils.PhaseTimer;

/**
 * Abstract base class for JDBC statement readers that process database metadata.
 * Provides common functionality for building SQL queries with extension and description support,
 * and processing database objects from ResultSets.
 */
public abstract class AbstractJdbcReader<T extends AbstractJdbcLoader<? extends IDatabase>> implements IJdbcReader {

    protected final T loader;

    protected AbstractJdbcReader(T loader) {
        this.loader = loader;
    }

    public void read() throws SQLException, InterruptedException, XmlReaderException {
        loader.checkCatalogReaderCancellation();
        loader.setCurrentOperation(Messages.AbstractStatementReader_start + getClass().getSimpleName());
        QueryBuilder builder = makeQuery();
        if (builder == null) {
            loader.checkCatalogReaderCancellation();
            return;
        }
        long start = PhaseTimer.start();
        String query = builder.build();

        ICatalogRowCache rowCache = loader.getCatalogRowCache();
        if (rowCache != null && readThroughRowCache(rowCache, query,
                builder.hasExplicitOrder())) {
            loader.checkCatalogReaderCancellation();
            PhaseTimer.end("jdbc_reader", start, getClass().getSimpleName());
            return;
        }

        PreparedStatement statement = null;
        ResultSet result = null;
        Throwable failure = null;
        try {
            statement = loader.prepareCatalogStatement(query);
            loader.registerCatalogStatement(statement);
            setQueryParams(statement);
            result = loader.getRunner().runScript(statement);
            IMonitor monitor = loader.getMonitor();
            while (result.next()) {
                IMonitor.checkCancelled(monitor);
                processResult(result);
                try {
                    loader.tryFinishAntlrTask();
                } catch (IOException ex) {
                    throw new XmlReaderException(ex.getLocalizedMessage(), ex);
                }
            }
        } catch (SQLException | InterruptedException | XmlReaderException | RuntimeException | Error ex) {
            failure = ex;
        }
        finishCatalogRead(loader, result, statement, failure);
        loader.checkCatalogReaderCancellation();
        PhaseTimer.end("jdbc_reader", start, getClass().getSimpleName());
    }

    /**
     * Consumes the reader's rows through the row-level cache pipeline with
     * the exact per-row loop body of the plain path. Failures classify
     * through the shared catalog-read finisher, so cancellation identity is
     * preserved. A {@code false} return means the cache disengaged before
     * any row was consumed and the plain path must run.
     */
    private boolean readThroughRowCache(ICatalogRowCache rowCache, String query,
            boolean explicitOrder)
            throws SQLException, InterruptedException, XmlReaderException {
        boolean handled = false;
        Throwable failure = null;
        try {
            IMonitor monitor = loader.getMonitor();
            handled = rowCache.read(getClass().getSimpleName(), query,
                    explicitOrder
                            ? ICatalogRowCache.CatalogQueryOrder.EXPLICIT_ORDER_BY
                            : ICatalogRowCache.CatalogQueryOrder.UNSPECIFIED,
                    row -> {
                        IMonitor.checkCancelled(monitor);
                        processResult(row);
                        try {
                            loader.tryFinishAntlrTask();
                        } catch (IOException ex) {
                            throw new XmlReaderException(
                                    ex.getLocalizedMessage(), ex);
                        }
                    }, query.indexOf('?') < 0 ? null : this::setQueryParams);
        } catch (SQLException | InterruptedException | XmlReaderException | RuntimeException | Error ex) {
            failure = ex;
        }
        finishCatalogRead(loader, null, null, failure);
        return handled;
    }

    /**
     * Completes a catalog read without Java TWR self-suppression. Every acquired resource is
     * attempted in cancellation-safe order and failures are merged by identity.
     * <p>
     * Internal bridge for core catalog readers and the row-level catalog
     * cache. This method is not a supported extension API.
     */
    public static void finishCatalogRead(AbstractJdbcLoader<?> loader, ResultSet result,
            PreparedStatement statement, Throwable failure)
            throws SQLException, InterruptedException, XmlReaderException {
        if (result != null) {
            try {
                result.close();
            } catch (SQLException | RuntimeException | Error ex) {
                failure = addFailure(failure, ex);
            }
        }
        if (statement != null) {
            try {
                loader.clearCatalogStatement(statement);
            } catch (RuntimeException | Error ex) {
                failure = addFailure(failure, ex);
            }
            try {
                statement.close();
            } catch (SQLException | RuntimeException | Error ex) {
                failure = addFailure(failure, ex);
            }
        }
        if (failure == null) {
            return;
        }

        InterruptedException cancellation = loader.classifyCatalogReaderCancellation(failure);
        if (cancellation != null) {
            throw cancellation;
        }
        rethrowCatalogFailure(failure);
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

    private static void rethrowCatalogFailure(Throwable failure)
            throws SQLException, InterruptedException, XmlReaderException {
        if (failure instanceof SQLException sql) {
            throw sql;
        }
        if (failure instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        if (failure instanceof XmlReaderException xml) {
            throw xml;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected checked catalog reader failure", failure);
    }

    protected QueryBuilder makeQuery() {
        QueryBuilder builder = new QueryBuilder();
        fillQueryBuilder(builder);
        return builder;
    }

    protected abstract void fillQueryBuilder(QueryBuilder builder);

    /**
     * Processing {@link ResultSet} from implementation of database and create correct model
     *
     * @param result query result
     * @throws SQLException         if database access fails
     * @throws XmlReaderException   if XML processing fails
     */
    protected abstract void processResult(ResultSet result) throws SQLException, XmlReaderException;

    /**
     * Setter for specific parameters for implementation of Jdbc Reader
     *
     * @param statement instance of {@link PreparedStatement}
     * @throws SQLException if parameter is set incorrectly
     */
    protected void setQueryParams(PreparedStatement statement) throws SQLException {
        // do nothing by default
    }
}
