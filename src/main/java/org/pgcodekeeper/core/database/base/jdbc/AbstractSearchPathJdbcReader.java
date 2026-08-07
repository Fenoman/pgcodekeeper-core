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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.pgcodekeeper.core.database.api.jdbc.IJdbcReader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ISchema;
import org.pgcodekeeper.core.database.base.loader.AbstractJdbcLoader;
import org.pgcodekeeper.core.exception.ConcurrentModificationException;
import org.pgcodekeeper.core.exception.XmlReaderException;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.utils.PhaseTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractSearchPathJdbcReader<T extends AbstractJdbcLoader<? extends IDatabase>> implements IJdbcReader {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractSearchPathJdbcReader.class);

    protected final T loader;

    protected AbstractSearchPathJdbcReader(T loader) {
        this.loader = loader;
    }

    @Override
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
                monitor.worked(1);
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
        AbstractJdbcReader.finishCatalogRead(loader, result, statement, failure);
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
                        monitor.worked(1);
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
        AbstractJdbcReader.finishCatalogRead(loader, null, null, failure);
        return handled;
    }

    private void processResult(ResultSet result) throws SQLException, XmlReaderException {
        String schemaColumn = getSchemaColumn();
        var schemaId = result.getObject(schemaColumn.substring(schemaColumn.indexOf('.') + 1));
        ISchema schema = loader.getSchema(schemaId);
        if (schema == null) {
            var msg = Messages.AbstractSearchPathJdbcReader_no_schema_found.formatted(schemaId);
            LOG.warn(msg);
            return;
        }

        try {
            processResult(result, schema);
        } catch (ConcurrentModificationException ex) {
            if (!loader.getSettings().isIgnoreConcurrentModification()) {
                throw ex;
            }
            LOG.error(ex.getLocalizedMessage(), ex);
        }
    }

    protected QueryBuilder makeQuery() {
        String schemas = loader.getSchemas();
        if (schemas.isBlank()) {
            return null;
        }
        QueryBuilder builder = new QueryBuilder();
        fillQueryBuilder(builder);
        builder.column(getSchemaColumn());
        builder.where(getSchemaColumn() + " IN (" + schemas + ')');
        return builder;
    }

    protected abstract void fillQueryBuilder(QueryBuilder builder);
    protected abstract String getSchemaColumn();
    protected abstract void processResult(ResultSet result, ISchema schema)
            throws ConcurrentModificationException, SQLException, XmlReaderException;

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
