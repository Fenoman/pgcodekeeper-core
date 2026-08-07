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
package org.pgcodekeeper.core.database.pg.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.ISchema;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.routine.PgJdbcRoutineBodyCatalogMode;
import org.pgcodekeeper.core.settings.CoreSettings;

class PgFunctionsReaderFingerprintRowTest {

    @Test
    void partialFingerprintFailsBeforeAnyModelMutation() throws Exception {
        PgJdbcLoader loader = mock(PgJdbcLoader.class);
        when(loader.getSettings()).thenReturn(new CoreSettings());
        var reader = new PgFunctionsReader(
                loader, PgJdbcRoutineBodyCatalogMode.FINGERPRINT_UTF8);
        clearInvocations(loader);

        ResultSet result = mock(ResultSet.class);
        when(result.getObject("body_oid", Long.class)).thenReturn(42L);
        ISchema schema = mock(ISchema.class);

        SQLException failure = assertThrows(
                SQLException.class, () -> reader.processResult(result, schema));

        assertEquals("Partial PostgreSQL routine fingerprint tuple", failure.getMessage());
        verifyNoInteractions(schema, loader);
    }

    @Test
    void aggregateFingerprintFailsBeforeAnyModelMutation() throws Exception {
        PgJdbcLoader loader = mock(PgJdbcLoader.class);
        when(loader.getSettings()).thenReturn(new CoreSettings());
        var reader = new PgFunctionsReader(
                loader, PgJdbcRoutineBodyCatalogMode.FINGERPRINT_UTF8);
        clearInvocations(loader);

        ResultSet result = mock(ResultSet.class);
        when(result.getObject("body_oid", Long.class)).thenReturn(42L);
        when(result.getObject("body_utf8_length", Long.class)).thenReturn(0L);
        when(result.getBytes("body_sha256")).thenReturn(new byte[32]);
        when(result.getBoolean("proisagg")).thenReturn(true);
        ISchema schema = mock(ISchema.class);

        SQLException failure = assertThrows(
                SQLException.class, () -> reader.processResult(result, schema));

        assertEquals("Aggregate PostgreSQL routine contains fingerprint metadata",
                failure.getMessage());
        verifyNoInteractions(schema, loader);
    }
}
