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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.pgcodekeeper.core.exception.XmlReaderException;

/**
 * Row-level cache strategy for catalog reader queries. When a loader exposes
 * an implementation, the base catalog readers offer it the built query before
 * running the plain path. The implementation feeds every result row to the
 * consumer in exact server order through a {@link ResultSet} view, so reader
 * behavior stays identical whether rows arrive from the server or from the
 * local store. One load-scoped instance may receive concurrent
 * {@link #read(String, String, CatalogRowConsumer, CatalogQueryParameterSetter)}
 * calls from snapshot-sharing catalog lanes; implementations must keep each
 * reader run isolated and aggregate load-wide state without lost updates.
 */
public interface ICatalogRowCache {

    /**
     * Attempts to satisfy one catalog reader query through the cache.
     * Implementations are fail-open: if the cache cannot engage (or
     * disengages before the first row was consumed), they return
     * {@code false} and the caller runs the original query untouched.
     * Calls may run concurrently on different JDBC lane connections.
     *
     * @param readerName  simple name of the catalog reader, for diagnostics
     * @param query       the exact query the plain path would execute
     * @param consumer    per-row consumer replicating the reader's loop body
     * @param paramSetter the reader's statement parameter setter; applied to
     *                    every derived statement that embeds the query
     * @return {@code true} when all rows were consumed through the cache
     *         pipeline, {@code false} when the caller must run the plain path
     */
    boolean read(String readerName, String query, CatalogRowConsumer consumer,
            CatalogQueryParameterSetter paramSetter)
            throws SQLException, InterruptedException, XmlReaderException;

    /**
     * Attempts a cached read with explicit row-order metadata. The default
     * bridge preserves compatibility for implementations that do not use the
     * optional exact-snapshot replay optimization.
     */
    default boolean read(String readerName, String query,
            CatalogQueryOrder queryOrder, CatalogRowConsumer consumer,
            CatalogQueryParameterSetter paramSetter)
            throws SQLException, InterruptedException, XmlReaderException {
        return read(readerName, query, consumer, paramSetter);
    }

    /** Proven ordering of the outermost result rows. */
    enum CatalogQueryOrder {
        UNSPECIFIED,
        EXPLICIT_ORDER_BY
    }

    /** Per-row consumer mirroring the body of a catalog reader's result loop. */
    @FunctionalInterface
    interface CatalogRowConsumer {

        void accept(ResultSet row) throws SQLException, InterruptedException, XmlReaderException;
    }

    /** Statement parameter setter of the originating catalog reader. */
    @FunctionalInterface
    interface CatalogQueryParameterSetter {

        void setParameters(PreparedStatement statement) throws SQLException;
    }
}
