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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionContext;
import org.pgcodekeeper.core.database.api.loader.ComparisonSide;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCatalogChannel;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyResidualTransport;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;

class PgJdbcRoutineFingerprintLoaderTest {

    @Test
    void routineBodyExchangeRegistrationIsDisabledByDefault() {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        when(connector.getDbName()).thenReturn("disabled");
        var loader = new PgJdbcLoader(connector, Consts.UTC, new CoreSettings());
        ComparisonExtensionContext context = mock(ComparisonExtensionContext.class);
        when(context.side()).thenReturn(ComparisonSide.NEW);

        loader.registerComparisonExtensions(context);

        verify(context).side();
        verifyNoMoreInteractions(context);
    }

    @Test
    void routineBodyExchangeRegistrationFollowsSettings() {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        when(connector.getDbName()).thenReturn("enabled");
        var settings = new CoreSettings();
        settings.setPgRoutineBodyHashFirst(true);
        var loader = new PgJdbcLoader(connector, Consts.UTC, settings);
        ComparisonExtensionContext context = mock(ComparisonExtensionContext.class);
        when(context.side()).thenReturn(ComparisonSide.NEW);

        loader.registerComparisonExtensions(context);

        verify(context).side();
        verify(context).register(PgRoutineBodyComparisonExtension.KEY,
                new PgRoutineBodyComparisonExtension.JdbcEndpoint(loader));
        verifyNoMoreInteractions(context);
    }

    @Test
    void invalidCustomLimitsFailBeforeResidualTransportAllocation() throws Exception {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        when(connector.getDbName()).thenReturn("invalid_limits");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        ResultSet lookup = mock(ResultSet.class);
        when(lookup.next()).thenReturn(true, false);
        when(lookup.getBoolean("available")).thenReturn(true);
        when(lookup.getBoolean("executable")).thenReturn(true);
        when(lookup.getBoolean("utf8_database")).thenReturn(true);
        ResultSet vector = mock(ResultSet.class);
        when(vector.next()).thenReturn(true, false);
        when(vector.getBoolean("compatible")).thenReturn(true);
        when(statement.executeQuery(argThat(
                sql -> sql != null && sql.contains("to_regprocedure"))))
                .thenReturn(lookup);
        when(statement.executeQuery(argThat(
                sql -> sql != null && sql.contains("convert_to"))))
                .thenReturn(vector);

        var settings = new CoreSettings() {
            @Override
            public int getPgRoutineBodyResidualBatchCount() {
                return 0;
            }
        };
        var loader = new InvalidLimitsLoader(connector, settings);

        assertThrows(IllegalArgumentException.class, loader::load);

        assertFalse(loader.transportCreated);
        verify(lookup).close();
        verify(vector).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void unexpectedCapabilityFailureStopsBeforeCatalogModelMutation() throws Exception {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        when(connector.getDbName()).thenReturn("fingerprint_failure");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        SQLException capabilityFailure = new SQLException("capability failure");
        when(statement.executeQuery(argThat(
                sql -> sql.contains("to_regprocedure('pg_catalog.sha256(bytea)')"))))
                        .thenThrow(capabilityFailure);

        var loader = new CapabilityFailureLoader(connector);
        IOException failure = assertThrows(IOException.class, loader::load);

        assertSame(capabilityFailure, failure.getCause().getCause().getCause());
        assertFalse(loader.catalogMutationReached);
        var order = inOrder(connection, statement);
        order.verify(connection).setAutoCommit(false);
        // Session setup uses one combined round trip; see
        // PgJdbcLoader.buildSessionSetupScript.
        order.verify(statement).execute(
                "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY; "
                        + "SET search_path TO pg_catalog; "
                        + "SET timezone = 'UTC'");
        order.verify(statement).executeQuery(argThat(
                sql -> sql.contains("to_regprocedure('pg_catalog.sha256(bytea)')")));
        verify(connection, never()).prepareStatement(argThat(
                sql -> sql.contains("pg_catalog.pg_proc")));
        verify(connection, never()).commit();
        verify(statement, times(1)).close();
        verify(connection, times(1)).close();
    }

    @Test
    void knownCapabilityFallbackCancelsAndDetachesProjectCatalogBeforeReadingObjects()
            throws Exception {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        when(connector.getDbName()).thenReturn("fingerprint_unsupported");
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        SQLException stop = new SQLException("stop after capability fallback");
        var loader = new UnsupportedCapabilityLoader(connector, channel, stop);
        loader.attachProjectRoutineBodyCatalog(channel,
                org.pgcodekeeper.core.database.api.loader.ComparisonSide.OLD);

        IOException failure = assertThrows(IOException.class, loader::load);

        assertSame(stop, failure.getCause());
        assertFalse(channel.publishIfOpen(new PgDatabase()),
                "unsupported consumers must decline before project catalog construction");
        assertThrows(InterruptedException.class,
                () -> channel.take(new NullMonitor()));
        loader.detachProjectRoutineBodyCatalog(channel);
        verify(statement, never()).executeQuery(argThat(
                sql -> sql.contains("to_regprocedure('pg_catalog.sha256(bytea)')")));
    }

    private static final class CapabilityFailureLoader extends PgJdbcLoader {

        private boolean catalogMutationReached;

        private CapabilityFailureLoader(IJdbcConnector connector) {
            super(connector, Consts.UTC, new CoreSettings());
            setVersion(PgSupportedVersion.VERSION_15.getVersion());
        }

        @Override
        public void preLoad() {
            // The test fixes the already detected server kind and v15 version.
        }

        @Override
        protected boolean requestRoutineBodyFingerprints() {
            return true;
        }

        @Override
        protected void queryCheckLastSysOid() {
            catalogMutationReached = true;
        }
    }

    private static final class UnsupportedCapabilityLoader extends PgJdbcLoader {

        private final ProjectRoutineBodyCatalogChannel channel;
        private final SQLException stop;

        private UnsupportedCapabilityLoader(IJdbcConnector connector,
                ProjectRoutineBodyCatalogChannel channel, SQLException stop) {
            super(connector, Consts.UTC, new CoreSettings());
            this.channel = channel;
            this.stop = stop;
            setVersion(130000);
        }

        @Override
        public void preLoad() {
            // The test fixes the already detected server kind and v13 version.
        }

        @Override
        protected boolean requestRoutineBodyFingerprints() {
            return true;
        }

        @Override
        protected void queryCheckLastSysOid() throws SQLException {
            assertFalse(channel.publishIfOpen(new PgDatabase()),
                    "fallback must cancel before the first catalog reader");
            throw stop;
        }
    }

    private static final class InvalidLimitsLoader extends PgJdbcLoader {

        private boolean transportCreated;

        private InvalidLimitsLoader(IJdbcConnector connector, CoreSettings settings) {
            super(connector, Consts.UTC, settings);
            setVersion(PgSupportedVersion.VERSION_15.getVersion());
        }

        @Override
        public void preLoad() {
            // The test fixes the already detected server kind and v15 version.
        }

        @Override
        protected boolean requestRoutineBodyFingerprints() {
            return true;
        }

        @Override
        protected PgRoutineBodyResidualTransport createRoutineBodyResidualTransport() {
            transportCreated = true;
            return mock(PgRoutineBodyResidualTransport.class);
        }
    }
}
