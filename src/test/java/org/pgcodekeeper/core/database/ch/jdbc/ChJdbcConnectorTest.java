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
package org.pgcodekeeper.core.database.ch.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgcodekeeper.core.utils.testcontainer.TestContainerType;

class ChJdbcConnectorTest {

    @ParameterizedTest
    @EnumSource(ValidationFailurePoint.class)
    void validationSqlFailureClosesAcquiredConnection(ValidationFailurePoint failurePoint) {
        SQLException primary = new SQLException("controlled validation failure");
        var fixture = new ValidationFixture(failurePoint, primary, null);

        IOException thrown = assertThrows(IOException.class,
                () -> ChJdbcConnector.validateConnection(fixture.connection));

        assertSame(primary, thrown.getCause());
        assertEquals(1, fixture.connectionCloseCount);
    }

    @Test
    void successfulValidationReturnsOpenConnectionAndClosesOnlyValidationResources()
            throws Exception {
        var fixture = new ValidationFixture(null, null, null);

        Connection returned = ChJdbcConnector.validateConnection(fixture.connection);

        assertSame(fixture.connection, returned);
        assertEquals(List.of("createStatement", "executeQuery", "result.close", "statement.close"),
                fixture.events);
        assertEquals(0, fixture.connectionCloseCount);
    }

    @Test
    void validationRuntimeFailureClosesConnectionAndPreservesIdentity() {
        RuntimeException primary = new IllegalStateException("controlled runtime failure");
        var fixture = new ValidationFixture(ValidationFailurePoint.EXECUTE_QUERY, primary, null);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ChJdbcConnector.validateConnection(fixture.connection));

        assertSame(primary, thrown);
        assertEquals(1, fixture.connectionCloseCount);
    }

    @Test
    void validationErrorClosesConnectionAndPreservesIdentity() {
        Error primary = new AssertionError("controlled error");
        var fixture = new ValidationFixture(ValidationFailurePoint.RESULT_SET_CLOSE, primary, null);

        Error thrown = assertThrows(Error.class,
                () -> ChJdbcConnector.validateConnection(fixture.connection));

        assertSame(primary, thrown);
        assertEquals(1, fixture.connectionCloseCount);
    }

    @Test
    void sqlValidationFailureSuppressesDistinctUncheckedConnectionCloseFailure() {
        SQLException primary = new SQLException("controlled SQL failure");
        RuntimeException closeFailure = new IllegalStateException("controlled close failure");
        var fixture = new ValidationFixture(ValidationFailurePoint.EXECUTE_QUERY, primary, closeFailure);

        IOException thrown = assertThrows(IOException.class,
                () -> ChJdbcConnector.validateConnection(fixture.connection));

        assertSame(primary, thrown.getCause());
        assertEquals(List.of(closeFailure), List.of(primary.getSuppressed()));
        assertEquals(1, fixture.connectionCloseCount);
    }

    @Test
    void runtimeValidationFailureSuppressesDistinctErrorFromConnectionClose() {
        RuntimeException primary = new IllegalStateException("controlled runtime failure");
        Error closeFailure = new AssertionError("controlled close failure");
        var fixture = new ValidationFixture(ValidationFailurePoint.EXECUTE_QUERY, primary, closeFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ChJdbcConnector.validateConnection(fixture.connection));

        assertSame(primary, thrown);
        assertEquals(List.of(closeFailure), List.of(primary.getSuppressed()));
        assertEquals(1, fixture.connectionCloseCount);
    }

    @Test
    void sqlValidationFailureSuppressesDistinctCheckedConnectionCloseFailure() {
        SQLException primary = new SQLException("controlled validation failure");
        SQLException closeFailure = new SQLException("controlled close failure");
        var fixture = new ValidationFixture(ValidationFailurePoint.EXECUTE_QUERY, primary, closeFailure);

        IOException thrown = assertThrows(IOException.class,
                () -> ChJdbcConnector.validateConnection(fixture.connection));

        assertSame(primary, thrown.getCause());
        assertEquals(List.of(closeFailure), List.of(primary.getSuppressed()));
        assertEquals(1, fixture.connectionCloseCount);
    }

    @Test
    void sameConnectionCloseFailureIsNotSelfSuppressed() {
        SQLException primary = new SQLException("shared controlled failure");
        var fixture = new ValidationFixture(ValidationFailurePoint.EXECUTE_QUERY, primary, primary);

        IOException thrown = assertThrows(IOException.class,
                () -> ChJdbcConnector.validateConnection(fixture.connection));

        assertSame(primary, thrown.getCause());
        assertEquals(0, primary.getSuppressed().length);
        assertEquals(1, fixture.connectionCloseCount);
    }

    @Test
    void sameExecuteAndStatementCloseFailureDoesNotSelfSuppress() {
        SQLException primary = new SQLException("shared validation failure");
        var fixture = new ValidationFixture(Map.of(
                ValidationFailurePoint.EXECUTE_QUERY, primary,
                ValidationFailurePoint.STATEMENT_CLOSE, primary), null);

        IOException thrown = assertThrows(IOException.class,
                () -> ChJdbcConnector.validateConnection(fixture.connection));

        assertSame(primary, thrown.getCause());
        assertEquals(0, primary.getSuppressed().length);
        assertEquals(List.of("createStatement", "executeQuery", "statement.close", "connection.close"),
                fixture.events);
    }

    @Test
    void sameResultAndStatementCloseFailureStillAttemptsBothResources() {
        SQLException primary = new SQLException("shared validation close failure");
        var fixture = new ValidationFixture(Map.of(
                ValidationFailurePoint.RESULT_SET_CLOSE, primary,
                ValidationFailurePoint.STATEMENT_CLOSE, primary), null);

        IOException thrown = assertThrows(IOException.class,
                () -> ChJdbcConnector.validateConnection(fixture.connection));

        assertSame(primary, thrown.getCause());
        assertEquals(0, primary.getSuppressed().length);
        assertEquals(List.of("createStatement", "executeQuery", "result.close", "statement.close",
                "connection.close"), fixture.events);
    }

    @Test
    void sameStatementAndConnectionCloseFailureIsSuppressedOnlyOnce() {
        SQLException primary = new SQLException("controlled validation failure");
        RuntimeException sharedCloseFailure = new IllegalStateException("shared close failure");
        var fixture = new ValidationFixture(Map.of(
                ValidationFailurePoint.EXECUTE_QUERY, primary,
                ValidationFailurePoint.STATEMENT_CLOSE, sharedCloseFailure), sharedCloseFailure);

        IOException thrown = assertThrows(IOException.class,
                () -> ChJdbcConnector.validateConnection(fixture.connection));

        assertSame(primary, thrown.getCause());
        assertEquals(List.of(sharedCloseFailure), List.of(primary.getSuppressed()));
        assertEquals(List.of("createStatement", "executeQuery", "statement.close", "connection.close"),
                fixture.events);
    }

    @Test
    void chConnectionTest() throws IOException, SQLException {
        var connector = new ChJdbcConnector(TestContainerType.CH_24.getUrl());
        try (var connection = connector.getConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT 1")) {
            Assertions.assertTrue(rs.next());
        }
    }

    @Test
    void wrongUrlConnectionTest() {
        var connector = new ChJdbcConnector("jdbc:clickhouse://localhost:5432/broken?user=user&password=password");
        Assertions.assertThrows(IOException.class, connector::getConnection);
    }

    @Test
    void urlValidationFailTest() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ChJdbcConnector("test"));
    }

    @ParameterizedTest
    @CsvSource({
            "jdbc:clickhouse:",
            "jdbc:ch:"
    })
    void urlValidationTest(String url) {
        Assertions.assertDoesNotThrow(() -> new ChJdbcConnector(url));
    }

    private enum ValidationFailurePoint {
        CREATE_STATEMENT,
        EXECUTE_QUERY,
        RESULT_SET_CLOSE,
        STATEMENT_CLOSE
    }

    private static final class ValidationFixture {

        private final List<String> events = new ArrayList<>();
        private final Map<ValidationFailurePoint, Throwable> validationFailures =
                new EnumMap<>(ValidationFailurePoint.class);
        private final Throwable connectionCloseFailure;
        private final ResultSet resultSet;
        private final Statement statement;
        private final Connection connection;
        private int connectionCloseCount;

        private ValidationFixture(ValidationFailurePoint failurePoint, Throwable validationFailure,
                Throwable connectionCloseFailure) {
            if (failurePoint != null) {
                validationFailures.put(failurePoint, validationFailure);
            }
            this.connectionCloseFailure = connectionCloseFailure;
            resultSet = proxy(ResultSet.class, this::invokeResultSet);
            statement = proxy(Statement.class, this::invokeStatement);
            connection = proxy(Connection.class, this::invokeConnection);
        }

        private ValidationFixture(Map<ValidationFailurePoint, Throwable> validationFailures,
                Throwable connectionCloseFailure) {
            this.validationFailures.putAll(validationFailures);
            this.connectionCloseFailure = connectionCloseFailure;
            resultSet = proxy(ResultSet.class, this::invokeResultSet);
            statement = proxy(Statement.class, this::invokeStatement);
            connection = proxy(Connection.class, this::invokeConnection);
        }

        private Object invokeConnection(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
            case "createStatement" -> {
                events.add("createStatement");
                failAt(ValidationFailurePoint.CREATE_STATEMENT);
                yield statement;
            }
            case "close" -> {
                events.add("connection.close");
                connectionCloseCount++;
                if (connectionCloseFailure != null) {
                    throw connectionCloseFailure;
                }
                yield null;
            }
            default -> invokeObjectMethod(proxy, method, args);
            };
        }

        private Object invokeStatement(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
            case "executeQuery" -> {
                events.add("executeQuery");
                failAt(ValidationFailurePoint.EXECUTE_QUERY);
                yield resultSet;
            }
            case "close" -> {
                events.add("statement.close");
                failAt(ValidationFailurePoint.STATEMENT_CLOSE);
                yield null;
            }
            default -> invokeObjectMethod(proxy, method, args);
            };
        }

        private Object invokeResultSet(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName())) {
                events.add("result.close");
                failAt(ValidationFailurePoint.RESULT_SET_CLOSE);
                return null;
            }
            return invokeObjectMethod(proxy, method, args);
        }

        private void failAt(ValidationFailurePoint point) throws Throwable {
            Throwable failure = validationFailures.get(point);
            if (failure != null) {
                throw failure;
            }
        }
    }

    @FunctionalInterface
    private interface Invocation {

        Object invoke(Object proxy, Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                invocation::invoke));
    }

    private static Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() != Object.class) {
            throw new AssertionError("Unexpected JDBC call: " + method.getName());
        }
        return switch (method.getName()) {
        case "equals" -> proxy == args[0];
        case "hashCode" -> System.identityHashCode(proxy);
        case "toString" -> throw new AssertionError("JDBC resource toString must not be called");
        default -> throw new AssertionError("Unexpected Object call: " + method.getName());
        };
    }
}
