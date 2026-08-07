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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionContext;
import org.pgcodekeeper.core.database.api.loader.ComparisonSide;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.telemetry.PgConnectionLifecycleTelemetry;
import org.pgcodekeeper.core.telemetry.PgConnectionLifecycleTelemetry.Lifecycle;
import org.pgcodekeeper.core.telemetry.PgConnectionLifecycleTelemetry.LogicalSide;
import org.pgcodekeeper.core.telemetry.PgConnectionRole;
import org.postgresql.PGConnection;

class PgJdbcConnectionTelemetryTest {

    @Test
    void preflightPublishesSideRoleBackendAndCloseRequestInOrder()
            throws Exception {
        List<PgConnectionLifecycleTelemetry> events = new ArrayList<>();
        List<String> order = new ArrayList<>();
        var settings = settings(events, order);
        IJdbcConnector connector = connector("database");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PGConnection pgConnection = mock(PGConnection.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        when(pgConnection.getBackendPID()).thenReturn(4711);
        doAnswer(invocation -> {
            order.add("jdbc-close");
            return null;
        }).when(connection).close();

        var loader = new VersionlessLoader(connector, settings);
        ComparisonExtensionContext context =
                mock(ComparisonExtensionContext.class);
        when(context.side()).thenReturn(ComparisonSide.NEW);
        loader.registerComparisonExtensions(context);

        loader.preLoad();

        assertEquals(List.of(
                new PgConnectionLifecycleTelemetry(LogicalSide.NEW,
                        PgConnectionRole.PREFLIGHT, 0, Lifecycle.OPENED, 4711),
                new PgConnectionLifecycleTelemetry(LogicalSide.NEW,
                        PgConnectionRole.PREFLIGHT, 0,
                        Lifecycle.CLOSE_REQUESTED, 4711)),
                events);
        assertEquals(List.of("OPENED", "CLOSE_REQUESTED", "jdbc-close"),
                order);
    }

    @Test
    void backendPidLookupFailureIsTelemetryOnly() throws Exception {
        List<PgConnectionLifecycleTelemetry> events = new ArrayList<>();
        var settings = settings(events, new ArrayList<>());
        IJdbcConnector connector = connector("database");
        Connection connection = mock(Connection.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.unwrap(PGConnection.class))
                .thenThrow(new SQLException("unsupported unwrap"));

        var loader = new VersionlessLoader(connector, settings);
        loader.preLoad();

        assertEquals(2, events.size());
        assertEquals(LogicalSide.UNBOUND, events.get(0).side());
        assertEquals(0, events.get(0).backendPid());
        assertEquals(0, events.get(1).backendPid());
    }

    @Test
    void disabledTelemetryDoesNotInspectTheConnection() throws Exception {
        IJdbcConnector connector = connector("database");
        Connection connection = mock(Connection.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));

        new VersionlessLoader(connector, new CoreSettings()).preLoad();

        verify(connection, never()).unwrap(PGConnection.class);
    }

    @Test
    void failingTelemetryCallbackCannotAffectConnectionCleanup()
            throws Exception {
        var settings = new CoreSettings();
        settings.setComparisonTelemetry(new IComparisonTelemetry() {
            @Override
            public void pgConnectionLifecycle(
                    PgConnectionLifecycleTelemetry event) {
                throw new IllegalStateException("expected telemetry failure");
            }
        });
        IJdbcConnector connector = connector("database");
        Connection connection = mock(Connection.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));

        new VersionlessLoader(connector, settings).preLoad();

        verify(connection).close();
    }

    @Test
    void eventRejectsInvalidLaneAndBackendPid() {
        assertThrows(IllegalArgumentException.class,
                () -> new PgConnectionLifecycleTelemetry(
                        LogicalSide.OLD, PgConnectionRole.CATALOG_LANE, 0,
                        Lifecycle.OPENED, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PgConnectionLifecycleTelemetry(
                        LogicalSide.OLD, PgConnectionRole.PRIMARY, 1,
                        Lifecycle.OPENED, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PgConnectionLifecycleTelemetry(
                        LogicalSide.OLD, PgConnectionRole.PRIMARY, 0,
                        Lifecycle.OPENED, -1));
    }

    private static CoreSettings settings(
            List<PgConnectionLifecycleTelemetry> events,
            List<String> order) {
        var settings = new CoreSettings();
        settings.setComparisonTelemetry(new IComparisonTelemetry() {
            @Override
            public void pgConnectionLifecycle(
                    PgConnectionLifecycleTelemetry event) {
                events.add(event);
                order.add(event.lifecycle().name());
            }
        });
        return settings;
    }

    private static IJdbcConnector connector(String database) {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        when(connector.getDbName()).thenReturn(database);
        return connector;
    }

    private static final class VersionlessLoader extends PgJdbcLoader {

        private VersionlessLoader(IJdbcConnector connector,
                CoreSettings settings) {
            super(connector, "UTC", settings);
        }

        @Override
        protected void queryCheckServerVersion(Statement statement) {
            // The connection lifecycle test does not query a live server.
        }
    }
}
