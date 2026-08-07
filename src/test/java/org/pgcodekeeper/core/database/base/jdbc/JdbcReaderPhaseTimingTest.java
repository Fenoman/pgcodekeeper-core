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

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.base.loader.AbstractJdbcLoader;
import org.pgcodekeeper.core.utils.LogCapture;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the per-reader {@code phase=jdbc_reader} debug timing line emitted
 * by the catalog reader base class.
 */
class JdbcReaderPhaseTimingTest {

    @Test
    void readerEmitsTimingLineOnCompletion() throws Exception {
        AbstractJdbcLoader<IDatabase> loader = mockLoader();
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        JdbcRunner runner = mock(JdbcRunner.class);
        when(loader.prepareCatalogStatement(anyString())).thenReturn(statement);
        when(loader.getRunner()).thenReturn(runner);
        when(runner.runScript(statement)).thenReturn(result);
        when(result.next()).thenReturn(false);

        try (LogCapture capture = LogCapture.start()) {
            new PhaseProbeCatalogReader(loader, false).read();

            List<String> lines = capture.messagesContaining("detail=PhaseProbeCatalogReader");
            assertEquals(1, lines.size(), () -> "captured: " + capture.messages());
            assertTrue(lines.get(0).matches(
                    "phase=jdbc_reader elapsed_ms=\\d+ detail=PhaseProbeCatalogReader"),
                    "unexpected line: " + lines.get(0));
        }
    }

    @Test
    void readerWithoutQueryStaysSilent() throws Exception {
        AbstractJdbcLoader<IDatabase> loader = mockLoader();

        try (LogCapture capture = LogCapture.start()) {
            new PhaseProbeCatalogReader(loader, true).read();
            assertTrue(capture.messagesContaining("detail=PhaseProbeCatalogReader").isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private static AbstractJdbcLoader<IDatabase> mockLoader() {
        return mock(AbstractJdbcLoader.class);
    }

    private static final class PhaseProbeCatalogReader
            extends AbstractJdbcReader<AbstractJdbcLoader<IDatabase>> {

        private final boolean skipQuery;

        private PhaseProbeCatalogReader(AbstractJdbcLoader<IDatabase> loader, boolean skipQuery) {
            super(loader);
            this.skipQuery = skipQuery;
        }

        @Override
        protected QueryBuilder makeQuery() {
            return skipQuery ? null : super.makeQuery();
        }

        @Override
        protected void fillQueryBuilder(QueryBuilder builder) {
            builder.column("1");
        }

        @Override
        protected void processResult(ResultSet result) {
            // nothing to process
        }
    }
}
