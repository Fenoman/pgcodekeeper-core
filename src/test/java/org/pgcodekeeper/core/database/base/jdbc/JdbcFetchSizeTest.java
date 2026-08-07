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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.base.loader.AbstractJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

class JdbcFetchSizeTest {

    @Test
    void configuresPreparedAndPlainStatements() throws Exception {
        var settings = new CoreSettings();
        settings.setJdbcFetchSize(512);
        Connection connection = mock(Connection.class);
        Statement plain = mock(Statement.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(connection.createStatement()).thenReturn(plain);
        when(connection.prepareStatement("select 1")).thenReturn(prepared);
        var loader = new TestJdbcLoader(connection, settings);

        assertSame(plain, loader.openStatement(connection));
        assertSame(prepared, loader.openPrepared("select 1"));
        verify(plain).setFetchSize(512);
        verify(prepared).setFetchSize(512);
    }

    @Test
    void leavesStatementsUntouchedInCompatibilityMode() throws Exception {
        Connection connection = mock(Connection.class);
        Statement plain = mock(Statement.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(connection.createStatement()).thenReturn(plain);
        when(connection.prepareStatement("select 1")).thenReturn(prepared);
        var loader = new TestJdbcLoader(connection, new CoreSettings());

        assertSame(plain, loader.openStatement(connection));
        assertSame(prepared, loader.openPrepared("select 1"));
        verifyNoInteractions(plain, prepared);
    }

    @Test
    void closesNewStatementWhenFetchSizeConfigurationFails() throws Exception {
        var settings = new CoreSettings();
        settings.setJdbcFetchSize(512);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        SQLException failure = new SQLException("fetch size rejected");
        when(connection.createStatement()).thenReturn(statement);
        doThrow(failure).when(statement).setFetchSize(512);
        var loader = new TestJdbcLoader(connection, settings);

        SQLException actual = assertThrows(SQLException.class, () -> loader.openStatement(connection));

        assertSame(failure, actual);
        verify(statement).close();
    }

    @Test
    void closesNewStatementWhenFetchSizeLookupFails() throws Exception {
        ISettings settings = mock(ISettings.class);
        RuntimeException failure = new IllegalStateException("fetch size unavailable");
        when(settings.getJdbcFetchSize()).thenThrow(failure);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        var loader = new TestJdbcLoader(connection, settings);

        RuntimeException actual = assertThrows(RuntimeException.class, () -> loader.openStatement(connection));

        assertSame(failure, actual);
        verify(statement).close();
    }

    @Test
    void preservesConfigurationFailureWhenClosingStatementFails() throws Exception {
        var settings = new CoreSettings();
        settings.setJdbcFetchSize(512);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        SQLException configurationFailure = new SQLException("fetch size rejected");
        RuntimeException closeFailure = new IllegalStateException("close rejected");
        when(connection.createStatement()).thenReturn(statement);
        doThrow(configurationFailure).when(statement).setFetchSize(512);
        doThrow(closeFailure).when(statement).close();
        var loader = new TestJdbcLoader(connection, settings);

        SQLException actual = assertThrows(SQLException.class, () -> loader.openStatement(connection));

        assertSame(configurationFailure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(closeFailure, actual.getSuppressed()[0]);
    }

    private static final class TestJdbcLoader extends AbstractJdbcLoader<PgDatabase> {

        private TestJdbcLoader(Connection connection, ISettings settings) {
            super(mock(IJdbcConnector.class), settings);
            this.connection = connection;
        }

        private Statement openStatement(Connection connection) throws SQLException {
            return createCatalogStatement(connection);
        }

        private PreparedStatement openPrepared(String sql) throws SQLException {
            return prepareCatalogStatement(sql);
        }

        @Override
        protected PgDatabase loadInternal() {
            return createDatabase();
        }

        @Override
        protected PgDatabase createDatabase() {
            return new PgDatabase();
        }
    }
}
