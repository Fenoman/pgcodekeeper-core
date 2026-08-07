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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.routine.PgJdbcRoutineBodyCatalogMode;

class PgRoutineBodyFingerprintCapabilityTest {

    @Test
    void deterministicGuardsNeverProbeTheServer() throws Exception {
        PgJdbcLoader loader = loader(PgSupportedVersion.VERSION_16, false);
        JdbcRunner runner = loader.getRunner();
        Statement statement = mock(Statement.class);

        assertEquals(PgJdbcRoutineBodyCatalogMode.FULL_BODY,
                PgRoutineBodyFingerprintCapability.detect(false, loader, statement));

        PgJdbcLoader oldLoader = loader(PgSupportedVersion.GP_VERSION_7, false);
        assertEquals(PgJdbcRoutineBodyCatalogMode.FULL_BODY,
                PgRoutineBodyFingerprintCapability.detect(true, oldLoader, statement));

        PgJdbcLoader greenplumLoader = loader(PgSupportedVersion.VERSION_16, true);
        assertEquals(PgJdbcRoutineBodyCatalogMode.FULL_BODY,
                PgRoutineBodyFingerprintCapability.detect(true, greenplumLoader, statement));

        verify(runner, never()).runScript(eq(statement), anyString());
        verify(oldLoader.getRunner(), never()).runScript(eq(statement), anyString());
        verify(greenplumLoader.getRunner(), never()).runScript(eq(statement), anyString());
    }

    @Test
    void missingFunctionAndPermissionUseKnownFullBodyFallbacks() throws Exception {
        PgJdbcLoader loader = loader(PgSupportedVersion.VERSION_16, false);
        Statement statement = mock(Statement.class);
        ResultSet missing = capabilityRow(false, false, true);
        when(loader.getRunner().runScript(eq(statement), anyString())).thenReturn(missing);

        assertEquals(PgJdbcRoutineBodyCatalogMode.FULL_BODY,
                PgRoutineBodyFingerprintCapability.detect(true, loader, statement));
        verify(loader.getRunner()).runScript(eq(statement), anyString());

        PgJdbcLoader deniedLoader = loader(PgSupportedVersion.VERSION_16, false);
        ResultSet denied = capabilityRow(true, false, true);
        when(deniedLoader.getRunner().runScript(eq(statement), anyString())).thenReturn(denied);

        assertEquals(PgJdbcRoutineBodyCatalogMode.FULL_BODY,
                PgRoutineBodyFingerprintCapability.detect(true, deniedLoader, statement));
        verify(deniedLoader.getRunner()).runScript(eq(statement), anyString());
    }

    @Test
    void exactVectorSelectsEncodingSpecificFingerprintMode() throws Exception {
        Statement statement = mock(Statement.class);
        PgJdbcLoader utf8Loader = loader(PgSupportedVersion.VERSION_15, false);
        ResultSet utf8Capability = capabilityRow(true, true, true);
        ResultSet utf8Vector = vectorRow(true);
        when(utf8Loader.getRunner().runScript(eq(statement), anyString()))
                .thenReturn(utf8Capability, utf8Vector);

        assertEquals(PgJdbcRoutineBodyCatalogMode.FINGERPRINT_UTF8,
                PgRoutineBodyFingerprintCapability.detect(true, utf8Loader, statement));

        PgJdbcLoader convertedLoader = loader(PgSupportedVersion.VERSION_16, false);
        ResultSet convertedCapability = capabilityRow(true, true, false);
        ResultSet convertedVector = vectorRow(true);
        when(convertedLoader.getRunner().runScript(eq(statement), anyString()))
                .thenReturn(convertedCapability, convertedVector);

        assertEquals(PgJdbcRoutineBodyCatalogMode.FINGERPRINT_CONVERTED,
                PgRoutineBodyFingerprintCapability.detect(true, convertedLoader, statement));
    }

    @Test
    void incompatibleVectorUsesKnownFullBodyFallback() throws Exception {
        PgJdbcLoader loader = loader(PgSupportedVersion.VERSION_16, false);
        Statement statement = mock(Statement.class);
        ResultSet capability = capabilityRow(true, true, true);
        ResultSet vector = vectorRow(false);
        when(loader.getRunner().runScript(eq(statement), anyString()))
                .thenReturn(capability, vector);

        assertEquals(PgJdbcRoutineBodyCatalogMode.FULL_BODY,
                PgRoutineBodyFingerprintCapability.detect(true, loader, statement));
    }

    @Test
    void probeUsesSafeLookupBeforeExecutingSha256() throws Exception {
        PgJdbcLoader loader = loader(PgSupportedVersion.VERSION_16, false);
        Statement statement = mock(Statement.class);
        ResultSet capability = capabilityRow(true, true, true);
        ResultSet vectorResult = vectorRow(true);
        when(loader.getRunner().runScript(eq(statement), anyString()))
                .thenReturn(capability, vectorResult);

        PgRoutineBodyFingerprintCapability.detect(true, loader, statement);

        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(loader.getRunner(), org.mockito.Mockito.times(2))
                .runScript(org.mockito.ArgumentMatchers.eq(statement), queries.capture());
        String lookup = queries.getAllValues().get(0);
        String vector = queries.getAllValues().get(1);
        assertEquals("""
                SELECT
                  c.function_oid IS NOT NULL AS available,
                  CASE WHEN c.function_oid IS NULL THEN FALSE
                    ELSE pg_catalog.has_function_privilege(c.function_oid, 'EXECUTE') END AS executable,
                  pg_catalog.current_setting('server_encoding') = 'UTF8' AS utf8_database
                FROM (
                  SELECT pg_catalog.to_regprocedure('pg_catalog.sha256(bytea)')::oid AS function_oid
                ) c""", lookup);
        assertEquals("""
                SELECT pg_catalog.sha256(pg_catalog.convert_to('abc', 'UTF8')) =
                  pg_catalog.decode('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
                    'hex') AS compatible""", vector);
        assertTrue(lookup.contains("to_regprocedure('pg_catalog.sha256(bytea)')"), lookup);
        assertTrue(lookup.contains("has_function_privilege"), lookup);
        assertEquals(0, countOccurrences(lookup, "SELECT pg_catalog.sha256("), lookup);
        assertTrue(vector.contains("pg_catalog.sha256("), vector);
        assertTrue(vector.contains("pg_catalog.convert_to('abc', 'UTF8')"), vector);
    }

    @Test
    void malformedProbeRowsAreHardFailures() throws Exception {
        Statement statement = mock(Statement.class);
        PgJdbcLoader emptyLoader = loader(PgSupportedVersion.VERSION_16, false);
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(emptyLoader.getRunner().runScript(eq(statement), anyString())).thenReturn(empty);

        assertThrows(SQLException.class,
                () -> PgRoutineBodyFingerprintCapability.detect(true, emptyLoader, statement));

        PgJdbcLoader nullLoader = loader(PgSupportedVersion.VERSION_16, false);
        ResultSet nullValue = mock(ResultSet.class);
        when(nullValue.next()).thenReturn(true, false);
        when(nullValue.wasNull()).thenReturn(true);
        when(nullLoader.getRunner().runScript(eq(statement), anyString())).thenReturn(nullValue);

        assertThrows(SQLException.class,
                () -> PgRoutineBodyFingerprintCapability.detect(true, nullLoader, statement));

        PgJdbcLoader duplicateLoader = loader(PgSupportedVersion.VERSION_16, false);
        ResultSet duplicate = capabilityRow(true, true, true);
        when(duplicate.next()).thenReturn(true, true);
        when(duplicateLoader.getRunner().runScript(eq(statement), anyString()))
                .thenReturn(duplicate);

        assertThrows(SQLException.class,
                () -> PgRoutineBodyFingerprintCapability.detect(
                        true, duplicateLoader, statement));
    }

    @Test
    void unexpectedSqlAndInterruptionPropagateByIdentity() throws Exception {
        Statement statement = mock(Statement.class);
        PgJdbcLoader sqlLoader = loader(PgSupportedVersion.VERSION_16, false);
        SQLException sqlFailure = new SQLException("controlled probe failure");
        when(sqlLoader.getRunner().runScript(eq(statement), anyString())).thenThrow(sqlFailure);

        SQLException thrownSql = assertThrows(SQLException.class,
                () -> PgRoutineBodyFingerprintCapability.detect(true, sqlLoader, statement));
        assertSame(sqlFailure, thrownSql);

        PgJdbcLoader interruptedLoader = loader(PgSupportedVersion.VERSION_16, false);
        InterruptedException interruption = new InterruptedException("controlled cancellation");
        when(interruptedLoader.getRunner().runScript(eq(statement), anyString()))
                .thenThrow(interruption);

        InterruptedException thrownInterruption = assertThrows(InterruptedException.class,
                () -> PgRoutineBodyFingerprintCapability.detect(
                        true, interruptedLoader, statement));
        assertSame(interruption, thrownInterruption);
    }

    @Test
    void secondPhaseFailureAndBetweenPhaseCancellationNeverFallback() throws Exception {
        Statement statement = mock(Statement.class);
        PgJdbcLoader sqlLoader = loader(PgSupportedVersion.VERSION_16, false);
        ResultSet capability = capabilityRow(true, true, true);
        SQLException vectorFailure = new SQLException("controlled vector failure");
        when(sqlLoader.getRunner().runScript(eq(statement), anyString()))
                .thenReturn(capability)
                .thenThrow(vectorFailure);

        SQLException thrown = assertThrows(SQLException.class,
                () -> PgRoutineBodyFingerprintCapability.detect(true, sqlLoader, statement));
        assertSame(vectorFailure, thrown);

        PgJdbcLoader cancelledLoader = loader(PgSupportedVersion.VERSION_16, false);
        ResultSet cancelledCapability = capabilityRow(true, true, true);
        when(cancelledLoader.getRunner().runScript(eq(statement), anyString()))
                .thenReturn(cancelledCapability);
        InterruptedException cancellation = new InterruptedException("between phases");
        doNothing().doThrow(cancellation).when(cancelledLoader)
                .checkCatalogReaderCancellation();

        InterruptedException thrownCancellation = assertThrows(InterruptedException.class,
                () -> PgRoutineBodyFingerprintCapability.detect(
                        true, cancelledLoader, statement));
        assertSame(cancellation, thrownCancellation);
        verify(cancelledLoader.getRunner()).runScript(eq(statement), anyString());
    }

    private static PgJdbcLoader loader(PgSupportedVersion version, boolean greenplum) {
        PgJdbcLoader loader = mock(PgJdbcLoader.class);
        JdbcRunner runner = mock(JdbcRunner.class);
        when(loader.getVersion()).thenReturn(version.getVersion());
        when(loader.isGreenplumDb()).thenReturn(greenplum);
        when(loader.getRunner()).thenReturn(runner);
        return loader;
    }

    private static ResultSet capabilityRow(boolean available, boolean executable,
                                           boolean utf8) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getBoolean("available")).thenReturn(available);
        when(result.getBoolean("executable")).thenReturn(executable);
        when(result.getBoolean("utf8_database")).thenReturn(utf8);
        return result;
    }

    private static ResultSet vectorRow(boolean compatible) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getBoolean("compatible")).thenReturn(compatible);
        return result;
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int fromIndex = 0;
        while ((fromIndex = text.indexOf(fragment, fromIndex)) >= 0) {
            count++;
            fromIndex += fragment.length();
        }
        return count;
    }
}
