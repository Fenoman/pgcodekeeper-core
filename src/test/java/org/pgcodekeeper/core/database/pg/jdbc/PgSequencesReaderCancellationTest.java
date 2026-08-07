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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;

class PgSequencesReaderCancellationTest {

    @Test
    void entryGateRunsBeforeOperationMutationAndResourceAcquisition() throws Exception {
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenReturn(true);
        Fixture fixture = new Fixture(monitor);
        fixture.loader.setCurrentOperation("operation-before-sequence-data");

        assertThrows(InterruptedException.class,
                () -> fixture.reader.querySequencesData(fixture.database));

        assertEquals("operation-before-sequence-data", fixture.loader.currentOperation());
        verify(fixture.connection, never()).prepareStatement(anyString());
    }

    @ParameterizedTest
    @EnumSource(AccessPhase.class)
    void preparedStatementIsRegisteredBeforeArrayCreation(AccessPhase phase) throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch arrayEntered = new CountDownLatch(1);
        CountDownLatch releaseArray = new CountDownLatch(1);
        AtomicInteger arrayCalls = new AtomicInteger();
        PreparedStatement targetStatement = fixture.statement(phase);
        Array targetArray = fixture.array(phase);
        AtomicInteger targetCancels = new AtomicInteger();
        doAnswer(invocation -> {
            targetCancels.incrementAndGet();
            releaseArray.countDown();
            return null;
        }).when(targetStatement).cancel();
        when(fixture.connection.createArrayOf(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    int call = arrayCalls.getAndIncrement();
                    if (call == phase.ordinal()) {
                        arrayEntered.countDown();
                        awaitUninterruptibly(releaseArray);
                        return targetArray;
                    }
                    return fixture.schemaArray;
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> owner = executor.submit(() -> {
            fixture.reader.querySequencesData(fixture.database);
            return null;
        });

        try {
            assertTrue(arrayEntered.await(5, TimeUnit.SECONDS));
            fixture.loader.cancel();
            assertEquals(1, targetCancels.get());
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertSame(InterruptedException.class, thrown.getCause().getClass());
        } finally {
            releaseArray.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @ParameterizedTest
    @EnumSource(AccessPhase.class)
    void preparedCleanupWaitsForCancellationDrainBeforePhysicalClose(AccessPhase phase)
            throws Exception {
        Fixture fixture = new Fixture();
        ResultSet targetResult = fixture.result(phase);
        PreparedStatement targetStatement = fixture.statement(phase);
        Array targetArray = fixture.array(phase);
        SensitiveSQLException raw = new SensitiveSQLException();
        CountDownLatch nextEntered = new CountDownLatch(1);
        CountDownLatch releaseNext = new CountDownLatch(1);
        CountDownLatch cancelEntered = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        CountDownLatch resultClosed = new CountDownLatch(1);
        CountDownLatch arrayFreed = new CountDownLatch(1);
        CountDownLatch statementClosed = new CountDownLatch(1);
        List<String> cleanup = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            nextEntered.countDown();
            awaitUninterruptibly(releaseNext);
            throw raw;
        }).when(targetResult).next();
        doAnswer(invocation -> {
            cancelEntered.countDown();
            releaseNext.countDown();
            awaitUninterruptibly(releaseCancel);
            return null;
        }).when(targetStatement).cancel();
        doAnswer(invocation -> {
            cleanup.add("result");
            resultClosed.countDown();
            return null;
        }).when(targetResult).close();
        doAnswer(invocation -> {
            cleanup.add("array");
            arrayFreed.countDown();
            return null;
        }).when(targetArray).free();
        doAnswer(invocation -> {
            cleanup.add("statement");
            statementClosed.countDown();
            return null;
        }).when(targetStatement).close();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> owner = executor.submit(() -> {
            fixture.reader.querySequencesData(fixture.database);
            return null;
        });
        Future<?> cancellation = null;

        try {
            assertTrue(nextEntered.await(5, TimeUnit.SECONDS));
            cancellation = executor.submit(() -> {
                fixture.loader.cancel();
                return null;
            });
            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS));
            assertTrue(resultClosed.await(5, TimeUnit.SECONDS));
            assertTrue(arrayFreed.await(5, TimeUnit.SECONDS));
            assertFalse(statementClosed.await(250, TimeUnit.MILLISECONDS));
            assertFalse(owner.isDone());
            releaseCancel.countDown();
            cancellation.get(5, TimeUnit.SECONDS);

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            InterruptedException interrupted = (InterruptedException) thrown.getCause();
            assertEquals(1, interrupted.getSuppressed().length);
            assertSame(raw, interrupted.getSuppressed()[0]);
            assertIterableEquals(List.of("result", "array", "statement"), cleanup);
        } finally {
            releaseCancel.countDown();
            releaseNext.countDown();
            if (cancellation != null) {
                cancellation.cancel(true);
            }
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @ParameterizedTest
    @EnumSource(AccessPhase.class)
    void monitorCancellationIsCheckedInsidePreparedResultLoops(AccessPhase phase)
            throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> armed.get());
        Fixture fixture = new Fixture(monitor);
        ResultSet targetResult = fixture.result(phase);
        String column = phase == AccessPhase.SCHEMAS ? "nspname" : "qname";
        String value = phase == AccessPhase.SCHEMAS ? Fixture.SCHEMA : Fixture.QUALIFIED_SEQUENCE;
        AtomicInteger getters = new AtomicInteger();
        when(targetResult.next()).thenReturn(true, true, false);
        when(targetResult.getString(column)).thenAnswer(invocation -> {
            getters.incrementAndGet();
            armed.set(true);
            return value;
        });

        assertThrows(InterruptedException.class,
                () -> fixture.reader.querySequencesData(fixture.database));

        assertEquals(1, getters.get());
        verify(targetResult).close();
        verify(fixture.array(phase)).free();
        verify(fixture.statement(phase)).close();
    }

    @Test
    void ordinaryFailurePreservesIdentityAndCleanupSuppressionOrder() throws Exception {
        Fixture fixture = new Fixture();
        SQLException primary = new SQLException("primary");
        SQLException resultClose = new SQLException("result close");
        SQLException arrayFree = new SQLException("array free");
        SQLException statementClose = new SQLException("statement close");
        when(fixture.schemaResult.next()).thenThrow(primary);
        doThrow(resultClose).when(fixture.schemaResult).close();
        doThrow(arrayFree).when(fixture.schemaArray).free();
        doThrow(statementClose).when(fixture.schemaStatement).close();

        SQLException thrown = assertThrows(SQLException.class,
                () -> fixture.reader.querySequencesData(fixture.database));

        assertSame(primary, thrown);
        assertIterableEquals(List.of(resultClose, arrayFree, statementClose),
                List.of(thrown.getSuppressed()));
        verify(fixture.schemaResult).close();
        verify(fixture.schemaArray).free();
        verify(fixture.schemaStatement).close();
    }

    @Test
    void cleanupDoesNotSelfSuppressWhenEveryStepThrowsThePrimaryFailure() throws Exception {
        Fixture fixture = new Fixture();
        SQLException primary = new SensitiveSQLException();
        when(fixture.schemaResult.next()).thenThrow(primary);
        doThrow(primary).when(fixture.schemaResult).close();
        doThrow(primary).when(fixture.schemaArray).free();
        doThrow(primary).when(fixture.schemaStatement).close();

        SQLException thrown = assertThrows(SQLException.class,
                () -> fixture.reader.querySequencesData(fixture.database));

        assertSame(primary, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        verify(fixture.schemaResult).close();
        verify(fixture.schemaArray).free();
        verify(fixture.schemaStatement).close();
    }

    @Test
    void cleanupSuppressesRepeatedSecondaryIdentityOnlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        SQLException primary = new SensitiveSQLException();
        SQLException repeated = new SensitiveSQLException();
        when(fixture.schemaResult.next()).thenThrow(primary);
        doThrow(repeated).when(fixture.schemaResult).close();
        doThrow(repeated).when(fixture.schemaArray).free();
        doThrow(repeated).when(fixture.schemaStatement).close();

        SQLException thrown = assertThrows(SQLException.class,
                () -> fixture.reader.querySequencesData(fixture.database));

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(repeated, thrown.getSuppressed()[0]);
        verify(fixture.schemaResult).close();
        verify(fixture.schemaArray).free();
        verify(fixture.schemaStatement).close();
    }

    @Test
    void preparedFinalGateRunsAfterPhysicalCloseBeforeEmptyUnionReturn() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> armed.get());
        Fixture fixture = new Fixture(monitor);
        when(fixture.sequenceResult.next()).thenReturn(false);
        doAnswer(invocation -> {
            armed.set(true);
            return null;
        }).when(fixture.sequenceStatement).close();

        assertThrows(InterruptedException.class,
                () -> fixture.reader.querySequencesData(fixture.database));

        verify(fixture.sequenceStatement).close();
        verify(fixture.ownerStatement, never()).executeQuery(anyString());
    }

    @Test
    void ownerStatementFinalGateRunsAfterResultCloseWithoutClosingStatement() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> armed.get());
        Fixture fixture = new Fixture(monitor);
        doAnswer(invocation -> {
            armed.set(true);
            return null;
        }).when(fixture.dataResult).close();

        assertThrows(InterruptedException.class,
                () -> fixture.reader.querySequencesData(fixture.database));

        verify(fixture.dataResult).close();
        verify(fixture.ownerStatement, never()).close();
    }

    @Test
    void ownerStatementRemainsRegisteredAfterSuccessfulRead() throws Exception {
        Fixture fixture = new Fixture();

        fixture.reader.querySequencesData(fixture.database);
        fixture.loader.cancel();

        verify(fixture.dataResult).close();
        verify(fixture.ownerStatement).cancel();
        verify(fixture.ownerStatement, never()).close();
    }

    @Test
    void sensitiveSqlFailureIsNeverInspectedOrStringified() throws Exception {
        Fixture fixture = new Fixture();
        SQLException primary = new SensitiveSQLException();
        when(fixture.schemaResult.next()).thenThrow(primary);

        SQLException thrown = assertThrows(SQLException.class,
                () -> fixture.reader.querySequencesData(fixture.database));

        assertSame(primary, thrown);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private enum AccessPhase {
        SCHEMAS,
        SEQUENCES
    }

    private static final class Fixture {

        private static final String SCHEMA = "app";
        private static final String QUALIFIED_SEQUENCE = "\"app\".\"seq\"";

        private final Connection connection = mock(Connection.class);
        private final Statement ownerStatement = mock(Statement.class);
        private final PreparedStatement schemaStatement = mock(PreparedStatement.class);
        private final PreparedStatement sequenceStatement = mock(PreparedStatement.class);
        private final Array schemaArray = mock(Array.class);
        private final Array sequenceArray = mock(Array.class);
        private final ResultSet schemaResult = mock(ResultSet.class);
        private final ResultSet sequenceResult = mock(ResultSet.class);
        private final ResultSet dataResult = mock(ResultSet.class);
        private final PgDatabase database = new PgDatabase();
        private final TestPgJdbcLoader loader;
        private final PgSequencesReader reader;

        private Fixture() throws Exception {
            this(null);
        }

        private Fixture(IMonitor monitor) throws Exception {
            IJdbcConnector connector = mock(IJdbcConnector.class);
            when(connector.getDbName()).thenReturn("test");
            CoreSettings settings = new CoreSettings();
            if (monitor != null) {
                settings.setMonitor(monitor);
            }
            loader = new TestPgJdbcLoader(connector, connection, ownerStatement, settings);
            reader = new PgSequencesReader(loader);

            PgSchema schema = new PgSchema(SCHEMA);
            schema.addChild(new PgSequence("seq"));
            database.addChild(schema);

            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                return sql.contains("has_schema_privilege") ? schemaStatement : sequenceStatement;
            });
            when(connection.createArrayOf(anyString(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(schemaArray, sequenceArray);
            when(schemaStatement.executeQuery()).thenReturn(schemaResult);
            when(sequenceStatement.executeQuery()).thenReturn(sequenceResult);
            when(ownerStatement.executeQuery(anyString())).thenReturn(dataResult);

            when(schemaResult.next()).thenReturn(true, false);
            when(schemaResult.getString("nspname")).thenReturn(SCHEMA);
            when(schemaResult.getObject("has_priv")).thenReturn(Boolean.TRUE);
            when(sequenceResult.next()).thenReturn(true, false);
            when(sequenceResult.getString("qname")).thenReturn(QUALIFIED_SEQUENCE);
            when(sequenceResult.getObject("has_priv")).thenReturn(Boolean.TRUE);
            when(dataResult.next()).thenReturn(false);
        }

        private PreparedStatement statement(AccessPhase phase) {
            return phase == AccessPhase.SCHEMAS ? schemaStatement : sequenceStatement;
        }

        private ResultSet result(AccessPhase phase) {
            return phase == AccessPhase.SCHEMAS ? schemaResult : sequenceResult;
        }

        private Array array(AccessPhase phase) {
            return phase == AccessPhase.SCHEMAS ? schemaArray : sequenceArray;
        }
    }

    private static final class TestPgJdbcLoader extends PgJdbcLoader {

        private TestPgJdbcLoader(IJdbcConnector connector, Connection connection,
                Statement statement, CoreSettings settings) {
            super(connector, "UTC", settings);
            this.connection = connection;
            this.statement = statement;
        }

        private String currentOperation() {
            return currentOperation;
        }
    }

    private static final class SensitiveSQLException extends SQLException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new AssertionError("JDBC message must not be inspected");
        }

        @Override
        public String getLocalizedMessage() {
            throw new AssertionError("JDBC localized message must not be inspected");
        }

        @Override
        public String toString() {
            throw new AssertionError("JDBC failure must not be stringified");
        }
    }
}
