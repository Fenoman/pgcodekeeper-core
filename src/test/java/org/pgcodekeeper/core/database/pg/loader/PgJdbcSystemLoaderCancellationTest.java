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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.base.loader.AbstractJdbcLoader;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStorage;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;

class PgJdbcSystemLoaderCancellationTest {

    @Test
    void entryCancellationPreventsConnectionAcquisitionAndRestoresInterruptFlag() throws Exception {
        IJdbcConnector connector = mockConnector();
        PgJdbcSystemLoader loader = newSystemLoader(connector);
        Thread.interrupted();
        loader.cancel();

        try {
            assertThrows(InterruptedException.class, loader::getStorageFromJdbc);

            assertTrue(Thread.currentThread().isInterrupted());
            verify(connector, never()).getConnection();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void connectionIsRegisteredBeforeStatementAcquisition() throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenAnswer(invocation -> {
            createEntered.countDown();
            awaitUninterruptibly(releaseCreate);
            return statement;
        });
        doAnswer(invocation -> {
            releaseCreate.countDown();
            return null;
        }).when(connection).close();
        PgJdbcSystemLoader loader = newSystemLoader(connector);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> owner = executor.submit(() -> {
            loader.getStorageFromJdbc();
            return null;
        });

        try {
            assertTrue(createEntered.await(5, TimeUnit.SECONDS));
            loader.cancel();
            verify(connection, atLeastOnce()).close();

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertSame(InterruptedException.class, thrown.getCause().getClass());
        } finally {
            releaseCreate.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @Test
    void statementIsRegisteredBeforeFetchSizeConfiguration() throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        CountDownLatch configureEntered = new CountDownLatch(1);
        CountDownLatch releaseConfigure = new CountDownLatch(1);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        doAnswer(invocation -> {
            configureEntered.countDown();
            awaitUninterruptibly(releaseConfigure);
            return null;
        }).when(statement).setFetchSize(512);
        doAnswer(invocation -> {
            releaseConfigure.countDown();
            return null;
        }).when(statement).cancel();
        PgJdbcSystemLoader loader = newSystemLoader(connector);
        ((CoreSettings) loader.getSettings()).setJdbcFetchSize(512);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> owner = executor.submit(() -> {
            loader.getStorageFromJdbc();
            return null;
        });

        try {
            assertTrue(configureEntered.await(5, TimeUnit.SECONDS));
            loader.cancel();
            verify(statement).cancel();

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertSame(InterruptedException.class, thrown.getCause().getClass());
        } finally {
            releaseConfigure.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @ParameterizedTest
    @EnumSource(OwnerQuery.class)
    void ownerQueryUsesRunnerAndNeverClosesOwnerStatement(OwnerQuery phase) throws Exception {
        PgJdbcSystemLoader loader = newSystemLoader(mockConnector());
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        CountDownLatch executeEntered = new CountDownLatch(1);
        CountDownLatch releaseExecute = new CountDownLatch(1);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            executeEntered.countDown();
            awaitUninterruptibly(releaseExecute);
            return result;
        });
        doAnswer(invocation -> {
            releaseExecute.countDown();
            return null;
        }).when(statement).cancel();
        when(result.next()).thenReturn(false);
        setField(AbstractJdbcLoader.class, loader, "statement", statement);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> owner = executor.submit(() -> {
            invokeReader(loader, phase.methodName);
            return null;
        });

        try {
            assertTrue(executeEntered.await(5, TimeUnit.SECONDS));
            loader.cancel();
            verify(statement).cancel();

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertSame(InterruptedException.class, thrown.getCause().getClass());
            // The runner rejects publication after cancellation, so the reader never owns
            // the worker's synthetic result set. A separate test covers cleanup after publication.
            verify(result, never()).close();
            verify(statement, never()).close();
        } finally {
            releaseExecute.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @ParameterizedTest
    @EnumSource(OwnerQuery.class)
    void ownerQueryFinalGateRunsAfterResultClose(OwnerQuery phase) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> cancelled.get());
        PgJdbcSystemLoader loader = newSystemLoader(mockConnector());
        loader.getSettings().setMonitor(monitor);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(false);
        doAnswer(invocation -> {
            cancelled.set(true);
            return null;
        }).when(result).close();
        setField(AbstractJdbcLoader.class, loader, "statement", statement);

        assertThrows(InterruptedException.class, () -> invokeReader(loader, phase.methodName));

        verify(result).close();
        verify(statement, never()).close();
    }

    @Test
    void castStatementIsRegisteredBeforeParameterBinding() throws Exception {
        PgJdbcSystemLoader loader = newSystemLoader(mockConnector());
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        CountDownLatch bindingEntered = new CountDownLatch(1);
        CountDownLatch releaseBinding = new CountDownLatch(1);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        doAnswer(invocation -> {
            bindingEntered.countDown();
            awaitUninterruptibly(releaseBinding);
            return null;
        }).when(statement).setLong(1, 0L);
        doAnswer(invocation -> {
            releaseBinding.countDown();
            return null;
        }).when(statement).cancel();
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(false);
        setField(AbstractJdbcLoader.class, loader, "connection", connection);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> owner = executor.submit(() -> {
            invokeReader(loader, "readCasts");
            return null;
        });

        try {
            assertTrue(bindingEntered.await(5, TimeUnit.SECONDS));
            loader.cancel();
            verify(statement).cancel();

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertSame(InterruptedException.class, thrown.getCause().getClass());
            verify(statement).close();
        } finally {
            releaseBinding.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @Test
    void castCleanupClosesResultThenWaitsForDrainBeforeStatementClose() throws Exception {
        PgJdbcSystemLoader loader = newSystemLoader(mockConnector());
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        SensitiveSQLException raw = new SensitiveSQLException();
        CountDownLatch nextEntered = new CountDownLatch(1);
        CountDownLatch releaseNext = new CountDownLatch(1);
        CountDownLatch cancelEntered = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        CountDownLatch resultClosed = new CountDownLatch(1);
        CountDownLatch statementClosed = new CountDownLatch(1);
        List<String> cleanup = Collections.synchronizedList(new ArrayList<>());
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenAnswer(invocation -> {
            nextEntered.countDown();
            awaitUninterruptibly(releaseNext);
            throw raw;
        });
        doAnswer(invocation -> {
            cancelEntered.countDown();
            releaseNext.countDown();
            awaitUninterruptibly(releaseCancel);
            return null;
        }).when(statement).cancel();
        doAnswer(invocation -> {
            cleanup.add("result");
            resultClosed.countDown();
            return null;
        }).when(result).close();
        doAnswer(invocation -> {
            cleanup.add("statement");
            statementClosed.countDown();
            return null;
        }).when(statement).close();
        setField(AbstractJdbcLoader.class, loader, "connection", connection);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> owner = executor.submit(() -> {
            invokeReader(loader, "readCasts");
            return null;
        });
        Future<?> cancellation = null;

        try {
            assertTrue(nextEntered.await(5, TimeUnit.SECONDS));
            cancellation = executor.submit(() -> {
                loader.cancel();
                return null;
            });
            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS));
            assertTrue(resultClosed.await(2, TimeUnit.SECONDS));
            assertFalse(statementClosed.await(250, TimeUnit.MILLISECONDS));
            assertFalse(owner.isDone());
            releaseCancel.countDown();
            cancellation.get(5, TimeUnit.SECONDS);

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            InterruptedException interrupted = (InterruptedException) thrown.getCause();
            assertEquals(1, interrupted.getSuppressed().length);
            assertSame(raw, interrupted.getSuppressed()[0]);
            assertIterableEquals(List.of("result", "statement"), cleanup);
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

    @Test
    void castCleanupNeverSelfSuppressesRepeatedFailureIdentity() throws Exception {
        PgJdbcSystemLoader loader = newSystemLoader(mockConnector());
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        SQLException primary = new SQLException("same failure identity");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenThrow(primary);
        doThrow(primary).when(result).close();
        doThrow(primary).when(statement).close();
        setField(AbstractJdbcLoader.class, loader, "connection", connection);

        SQLException thrown = assertThrows(SQLException.class,
                () -> invokeReader(loader, "readCasts"));

        assertSame(primary, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        verify(result).close();
        verify(statement).close();
    }

    @Test
    void systemLoadFinalGateRunsAfterBothOuterResourcesClose() throws Exception {
        SystemFixture fixture = new SystemFixture();
        doAnswer(invocation -> {
            fixture.loader.cancel();
            return null;
        }).when(fixture.ownerStatement).close();
        Thread.interrupted();

        try {
            assertThrows(InterruptedException.class, fixture.loader::getStorageFromJdbc);

            assertTrue(Thread.currentThread().isInterrupted());
            InOrder closes = inOrder(fixture.ownerStatement, fixture.connection);
            closes.verify(fixture.ownerStatement).close();
            closes.verify(fixture.connection).close();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void successfulSystemLoadSupportsOwnerCloseWithoutDoubleClosingResources() throws Exception {
        SystemFixture fixture = new SystemFixture();

        fixture.loader.getStorageFromJdbc();
        fixture.loader.close();

        verify(fixture.castResult).close();
        verify(fixture.castStatement, times(1)).close();
        verify(fixture.ownerStatement, times(1)).close();
        verify(fixture.connection, times(1)).close();
    }

    @Test
    void serializeClosesItsOwnedSystemLoader(@TempDir Path tempDir) throws Exception {
        MetaStorage storage = new MetaStorage();
        Path output = tempDir.resolve("system-objects.ser");
        try (MockedConstruction<PgJdbcSystemLoader> construction =
                mockConstruction(PgJdbcSystemLoader.class, (loader, context) ->
                        when(loader.getStorageFromJdbc()).thenReturn(storage))) {

            PgJdbcSystemLoader.serialize(output.toString(), "jdbc:postgresql://localhost/test");

            assertEquals(1, construction.constructed().size());
            PgJdbcSystemLoader loader = construction.constructed().get(0);
            verify(loader).close();
            assertTrue(Files.exists(output));
        }
    }

    private static IJdbcConnector mockConnector() {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        when(connector.getDbName()).thenReturn("test");
        return connector;
    }

    private static PgJdbcSystemLoader newSystemLoader(IJdbcConnector connector) throws Exception {
        var constructor = PgJdbcSystemLoader.class.getDeclaredConstructor(IJdbcConnector.class);
        constructor.setAccessible(true);
        return constructor.newInstance(connector);
    }

    private static void invokeReader(PgJdbcSystemLoader loader, String methodName) throws Exception {
        Method method = PgJdbcSystemLoader.class.getDeclaredMethod(methodName, MetaStorage.class);
        method.setAccessible(true);
        try {
            method.invoke(loader, new MetaStorage());
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        }
    }

    private static void setField(Class<?> owner, Object target, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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

    private enum OwnerQuery {
        FUNCTIONS("readFunctions"),
        RELATIONS("readRelations"),
        OPERATORS("readOperators");

        private final String methodName;

        OwnerQuery(String methodName) {
            this.methodName = methodName;
        }
    }

    private static final class SystemFixture {

        private final IJdbcConnector connector = mockConnector();
        private final Connection connection = mock(Connection.class);
        private final Statement ownerStatement = mock(Statement.class);
        private final PreparedStatement castStatement = mock(PreparedStatement.class);
        private final ResultSet versionResult = mock(ResultSet.class);
        private final ResultSet typesResult = mock(ResultSet.class);
        private final ResultSet relationsResult = mock(ResultSet.class);
        private final ResultSet functionsResult = mock(ResultSet.class);
        private final ResultSet operatorsResult = mock(ResultSet.class);
        private final ResultSet castResult = mock(ResultSet.class);
        private final PgJdbcSystemLoader loader;

        private SystemFixture() throws Exception {
            when(connector.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(ownerStatement);
            when(connection.prepareStatement(anyString())).thenReturn(castStatement);
            when(ownerStatement.executeQuery(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                if (sql.contains("server_version_num")) {
                    // combined Greenplum/version probe, one round trip
                    return versionResult;
                }
                if (sql.contains("pg_type t")) {
                    return typesResult;
                }
                if (sql.contains("pg_class c")) {
                    return relationsResult;
                }
                if (sql.contains("pg_proc p")) {
                    return functionsResult;
                }
                if (sql.contains("pg_operator o")) {
                    return operatorsResult;
                }
                throw new AssertionError("Unexpected system query");
            });
            when(castStatement.executeQuery()).thenReturn(castResult);
            when(versionResult.next()).thenReturn(true, false);
            when(versionResult.getString("version_string")).thenReturn("PostgreSQL 15.0");
            when(versionResult.getInt("version_num")).thenReturn(150000);
            when(typesResult.next()).thenReturn(false);
            when(relationsResult.next()).thenReturn(false);
            when(functionsResult.next()).thenReturn(false);
            when(operatorsResult.next()).thenReturn(false);
            when(castResult.next()).thenReturn(false);
            loader = newSystemLoader(connector);
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
