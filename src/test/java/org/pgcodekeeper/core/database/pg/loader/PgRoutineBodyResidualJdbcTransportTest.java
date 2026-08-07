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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.monitor.NullMonitor;

class PgRoutineBodyResidualJdbcTransportTest {

    @Test
    void oneRegisteredStatementStreamsEveryBoundedBatchOnOwnedConnection()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.rows(fixture.firstResult,
                new Row(1L, 11L, new String("SELECT 11")),
                new Row(2L, 12L, new String("SELECT 12")));
        fixture.rows(fixture.secondResult,
                new Row(1L, 13L, new String("SELECT 13")));
        List<Row> observed = new ArrayList<>();

        fixture.transport.fetch(new Long[] { 11L, 12L },
                (ordinal, oid, raw) -> observed.add(new Row(ordinal, oid, raw)),
                new NullMonitor());
        fixture.transport.fetch(new Long[] { 13L },
                (ordinal, oid, raw) -> observed.add(new Row(ordinal, oid, raw)),
                new NullMonitor());
        fixture.transport.close();

        assertEquals(List.of(
                new Row(1L, 11L, "SELECT 11"),
                new Row(2L, 12L, "SELECT 12"),
                new Row(1L, 13L, "SELECT 13")), observed);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(fixture.loader).prepareCatalogStatement(sql.capture());
        assertEquals("""
                SELECT
                  requested.ordinality::bigint AS body_ordinal,
                  requested.oid::bigint AS body_oid,
                  proc.prosrc AS body_prosrc
                FROM pg_catalog.unnest(?::oid[]) WITH ORDINALITY
                  AS requested(oid, ordinality)
                JOIN pg_catalog.pg_proc proc ON proc.oid = requested.oid
                ORDER BY requested.ordinality""", sql.getValue());
        verify(fixture.loader).registerCatalogStatement(fixture.statement);
        verify(fixture.loader).clearCatalogStatement(fixture.statement);
        verify(fixture.runner, times(2)).runScript(fixture.statement);
        verify(fixture.statement).close();
        verify(fixture.firstResult).close();
        verify(fixture.secondResult).close();
        verify(fixture.firstArray).free();
        verify(fixture.secondArray).free();

        ArgumentCaptor<Object[]> arrays = ArgumentCaptor.forClass(Object[].class);
        verify(fixture.connection, times(2)).createArrayOf(eq("oid"), arrays.capture());
        assertArrayEquals(new Long[] { 11L, 12L }, arrays.getAllValues().get(0));
        assertArrayEquals(new Long[] { 13L }, arrays.getAllValues().get(1));

        InOrder order = inOrder(fixture.loader, fixture.connection,
                fixture.firstResult, fixture.firstArray, fixture.statement);
        order.verify(fixture.loader).registerCatalogStatement(fixture.statement);
        order.verify(fixture.connection).createArrayOf(eq("oid"), any());
        order.verify(fixture.firstResult).close();
        order.verify(fixture.firstArray).free();
        order.verify(fixture.loader).clearCatalogStatement(fixture.statement);
        order.verify(fixture.statement).close();
    }

    @Test
    void nullableProtocolColumnsFailBeforeConsumerPublication() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.firstResult.next()).thenReturn(true, false);
        when(fixture.firstResult.getLong("body_ordinal")).thenReturn(1L);
        when(fixture.firstResult.wasNull()).thenReturn(true);
        List<Row> observed = new ArrayList<>();

        IOException thrown = assertThrows(IOException.class,
                () -> fixture.transport.fetch(new Long[] { 11L },
                        (ordinal, oid, raw) -> observed.add(new Row(ordinal, oid, raw)),
                        new NullMonitor()));

        assertEquals(0, observed.size());
        assertEquals("NULL residual PostgreSQL routine body ordinal", thrown.getMessage());
        verify(fixture.firstResult).close();
        verify(fixture.firstArray).free();
        fixture.transport.close();
    }

    @Test
    void sqlFailureStaysTheCauseAndCleanupFailuresAreSuppressedOnce() throws Exception {
        Fixture fixture = new Fixture();
        SQLException primary = new SQLException("controlled execute failure");
        SQLException arrayFailure = new SQLException("controlled array cleanup failure");
        when(fixture.runner.runScript(fixture.statement)).thenThrow(primary);
        when(fixture.firstArray.getBaseTypeName()).thenReturn("oid");
        org.mockito.Mockito.doThrow(arrayFailure).when(fixture.firstArray).free();

        IOException thrown = assertThrows(IOException.class,
                () -> fixture.transport.fetch(new Long[] { 11L },
                        (ordinal, oid, raw) -> { }, new NullMonitor()));

        assertSame(primary, thrown.getCause());
        assertEquals(List.of(arrayFailure), List.of(primary.getSuppressed()));
        verify(fixture.firstArray).free();
        fixture.transport.close();
    }

    @Test
    void registrationFailureClosesStatementBeforeAnyArrayIsCreated() throws Exception {
        Fixture fixture = new Fixture();
        InterruptedException cancellation = new InterruptedException("controlled registration failure");
        org.mockito.Mockito.doThrow(cancellation).when(fixture.loader)
                .registerCatalogStatement(fixture.statement);

        InterruptedException thrown = assertThrows(InterruptedException.class,
                () -> fixture.transport.fetch(new Long[] { 11L },
                        (ordinal, oid, raw) -> { }, new NullMonitor()));

        assertSame(cancellation, thrown);
        verify(fixture.statement).close();
        verify(fixture.connection, never()).createArrayOf(anyString(), any());
    }

    @Test
    void batchCleanupFailuresKeepPhysicalOrderAndPrimaryIdentity() throws Exception {
        Fixture fixture = new Fixture();
        SQLException resultFailure = new SQLException("controlled result close failure");
        SQLException arrayFailure = new SQLException("controlled array free failure");
        SQLException parametersFailure = new SQLException("controlled parameter clear failure");
        org.mockito.Mockito.doThrow(resultFailure).when(fixture.firstResult).close();
        org.mockito.Mockito.doThrow(arrayFailure).when(fixture.firstArray).free();
        org.mockito.Mockito.doThrow(parametersFailure).when(fixture.statement).clearParameters();

        IOException thrown = assertThrows(IOException.class,
                () -> fixture.transport.fetch(new Long[] { 11L },
                        (ordinal, oid, raw) -> { }, new NullMonitor()));

        assertSame(resultFailure, thrown.getCause());
        assertEquals(List.of(arrayFailure, parametersFailure),
                List.of(resultFailure.getSuppressed()));
        InOrder cleanup = inOrder(
                fixture.firstResult, fixture.firstArray, fixture.statement);
        cleanup.verify(fixture.firstResult).close();
        cleanup.verify(fixture.firstArray).free();
        cleanup.verify(fixture.statement).clearParameters();
        fixture.transport.close();
    }

    @Test
    void cancellationClassificationRunsOnlyAfterBatchCleanup() throws Exception {
        Fixture fixture = new Fixture();
        SQLException queryFailure = new SQLException("controlled cancellation query failure");
        InterruptedException cancellation =
                new InterruptedException("controlled JDBC cancellation");
        when(fixture.runner.runScript(fixture.statement)).thenThrow(queryFailure);
        when(fixture.loader.classifyCatalogReaderCancellation(queryFailure))
                .thenReturn(cancellation);

        InterruptedException thrown = assertThrows(InterruptedException.class,
                () -> fixture.transport.fetch(new Long[] { 11L },
                        (ordinal, oid, raw) -> { }, new NullMonitor()));

        assertSame(cancellation, thrown);
        InOrder cleanup = inOrder(fixture.firstArray, fixture.statement, fixture.loader);
        cleanup.verify(fixture.firstArray).free();
        cleanup.verify(fixture.statement).clearParameters();
        cleanup.verify(fixture.loader).classifyCatalogReaderCancellation(queryFailure);
        fixture.transport.close();
    }

    @Test
    void terminalCleanupMergesFailuresOnceAndCloseIsIdempotent() throws Exception {
        Fixture fixture = new Fixture();
        fixture.transport.fetch(new Long[] { 11L },
                (ordinal, oid, raw) -> { }, new NullMonitor());
        IllegalStateException unregisterFailure =
                new IllegalStateException("controlled unregister failure");
        SQLException closeFailure = new SQLException("controlled statement close failure");
        org.mockito.Mockito.doThrow(unregisterFailure).when(fixture.loader)
                .clearCatalogStatement(fixture.statement);
        org.mockito.Mockito.doThrow(closeFailure).when(fixture.statement).close();

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, fixture.transport::close);
        fixture.transport.close();

        assertSame(unregisterFailure, thrown);
        assertEquals(List.of(closeFailure), List.of(thrown.getSuppressed()));
        verify(fixture.loader, times(1)).clearCatalogStatement(fixture.statement);
        verify(fixture.statement, times(1)).close();
    }

    @Test
    void threadInterruptStopsRowPublicationButStillCleansTheBatch() throws Exception {
        Thread.interrupted();
        Fixture fixture = new Fixture();
        fixture.rows(fixture.firstResult, new Row(1L, 11L, "SELECT 11"));
        Thread.currentThread().interrupt();

        try {
            assertThrows(InterruptedException.class,
                    () -> fixture.transport.fetch(new Long[] { 11L },
                            (ordinal, oid, raw) -> {
                                throw new AssertionError("cancelled row must not publish");
                            }, new NullMonitor()));
            verify(fixture.firstResult).close();
            verify(fixture.firstArray).free();
            verify(fixture.statement).clearParameters();
        } finally {
            Thread.interrupted();
            fixture.transport.close();
        }
    }

    private record Row(long ordinal, long oid, String raw) {
    }

    private static final class Fixture {
        private final PgJdbcLoader loader = mock(PgJdbcLoader.class);
        private final Connection connection = mock(Connection.class);
        private final PreparedStatement statement = mock(PreparedStatement.class);
        private final JdbcRunner runner = mock(JdbcRunner.class);
        private final Array firstArray = mock(Array.class);
        private final Array secondArray = mock(Array.class);
        private final ResultSet firstResult = mock(ResultSet.class);
        private final ResultSet secondResult = mock(ResultSet.class);
        private final PgRoutineBodyResidualJdbcTransport transport;

        private Fixture() throws Exception {
            when(loader.getConnection()).thenReturn(connection);
            when(loader.getRunner()).thenReturn(runner);
            when(loader.prepareCatalogStatement(anyString())).thenReturn(statement);
            when(connection.createArrayOf(eq("oid"), any()))
                    .thenReturn(firstArray, secondArray);
            when(runner.runScript(statement)).thenReturn(firstResult, secondResult);
            transport = new PgRoutineBodyResidualJdbcTransport(loader);
        }

        private void rows(ResultSet result, Row... rows) throws Exception {
            Boolean[] next = new Boolean[rows.length + 1];
            java.util.Arrays.fill(next, Boolean.TRUE);
            next[rows.length] = Boolean.FALSE;
            when(result.next()).thenReturn(next[0],
                    java.util.Arrays.copyOfRange(next, 1, next.length));
            Long[] ordinals = java.util.Arrays.stream(rows)
                    .map(Row::ordinal).toArray(Long[]::new);
            Long[] oids = java.util.Arrays.stream(rows)
                    .map(Row::oid).toArray(Long[]::new);
            String[] bodies = java.util.Arrays.stream(rows)
                    .map(Row::raw).toArray(String[]::new);
            when(result.getLong("body_ordinal")).thenReturn(
                    ordinals[0], java.util.Arrays.copyOfRange(ordinals, 1, ordinals.length));
            when(result.getLong("body_oid")).thenReturn(
                    oids[0], java.util.Arrays.copyOfRange(oids, 1, oids.length));
            when(result.getString("body_prosrc")).thenReturn(
                    bodies[0], java.util.Arrays.copyOfRange(bodies, 1, bodies.length));
        }
    }
}
