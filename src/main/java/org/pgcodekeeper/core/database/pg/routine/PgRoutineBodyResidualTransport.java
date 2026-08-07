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
package org.pgcodekeeper.core.database.pg.routine;

import java.io.IOException;

import org.pgcodekeeper.core.monitor.IMonitor;

/**
 * Internal transport boundary for bounded residual routine bodies. JDBC stays
 * outside the routine package, while rows are consumed synchronously without
 * a body-sized staging collection.
 */
public interface PgRoutineBodyResidualTransport extends AutoCloseable {

    @FunctionalInterface
    interface RowConsumer {
        void accept(long batchOrdinal, long bodyOid, String rawBody)
                throws IOException, InterruptedException;
    }

    void fetch(Long[] orderedOids, RowConsumer rows, IMonitor monitor)
            throws IOException, InterruptedException;

    @Override
    void close() throws IOException;
}
