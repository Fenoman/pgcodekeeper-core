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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ISchema;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.database.base.loader.AbstractJdbcLoader;
import org.pgcodekeeper.core.database.base.loader.AbstractLoader;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.pg.jdbc.PgCatalogCacheNamespace;
import org.pgcodekeeper.core.database.pg.jdbc.PgCatalogRowCache;
import org.pgcodekeeper.core.database.pg.jdbc.PgJdbcType;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader.CachedCollation;
import org.pgcodekeeper.core.database.pg.routine.PgJdbcRoutineBodyResolution;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyCache;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyProfile;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyRepresentation;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodySource;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.telemetry.PgRoutineBodyCacheTelemetry;
import org.pgcodekeeper.core.utils.ContentAddressedFileStore;

class PgJdbcLoaderLifecycleTest {

    @Test
    void systemIdentityFailureRollsBackAndUsesSafeIdentity(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        SQLException identityFailure = new SQLException("controlled identity failure");
        when(statement.executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY))
                .thenThrow(identityFailure);
        ResultSet safeIdentity = identityResult("captured_database", null,
                null);
        when(statement.executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK))
                .thenReturn(safeIdentity);
        var settings = new CoreSettings();
        settings.setPgCatalogCacheDir(cacheDir.toString());
        settings.setPgCatalogCacheRows(true);
        SQLException stop = new SQLException("stop after cache construction");
        var loader = new CacheBoundaryLoader(connector, settings, stop, false);

        IOException thrown = assertThrows(IOException.class, loader::load);

        assertSame(stop, thrown.getCause());
        assertTrue(loader.catalogMutationReached);
        assertNull(loader.routineCacheDirectory);
        assertTrue(loader.rowCacheDirectory != null);
        assertNull(loader.rowCacheSnapshotDigest);
        var order = inOrder(connection, statement);
        order.verify(connection).setAutoCommit(false);
        order.verify(statement).execute(PgJdbcLoader.buildSessionSetupScript("UTC"));
        order.verify(connection).setSavepoint();
        order.verify(statement).executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY);
        order.verify(connection).rollback(probeSavepoint);
        order.verify(statement).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK);
        verify(connection, never()).commit();
    }

    @Test
    void routineAndRowCachesReceiveTheSameResolvedTargetDirectory(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        ResultSet lookup = capabilityResult();
        ResultSet vector = mock(ResultSet.class);
        when(vector.next()).thenReturn(true, false);
        when(vector.getBoolean("compatible")).thenReturn(true);
        ResultSet identity = identityResult("captured_database");
        when(statement.executeQuery(argThat(sql -> sql != null
                && sql.contains("to_regprocedure")))).thenReturn(lookup);
        when(statement.executeQuery(argThat(sql -> sql != null
                && sql.contains("convert_to")))).thenReturn(vector);
        when(statement.executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY))
                .thenReturn(identity);
        var settings = new CoreSettings();
        settings.setPgCatalogCacheDir(cacheDir.toString());
        settings.setPgCatalogCacheRows(true);
        SQLException stop = new SQLException("stop after cache construction");
        var loader = new CacheBoundaryLoader(connector, settings, stop, true);

        IOException thrown = assertThrows(IOException.class, loader::load);

        assertSame(stop, thrown.getCause());
        assertTrue(loader.catalogMutationReached);
        assertEquals(loader.routineCacheDirectory, loader.rowCacheDirectory);
        assertEquals(cacheDir, loader.rowCacheDirectory.getParent());
        assertTrue(loader.rowCacheDirectory.getFileName().toString()
                .matches("target-v2-[0-9a-f]{64}"));
        var order = inOrder(connection, statement);
        order.verify(connection).setAutoCommit(false);
        order.verify(statement).execute(PgJdbcLoader.buildSessionSetupScript("UTC"));
        order.verify(statement).executeQuery(argThat(sql -> sql.contains("to_regprocedure")));
        order.verify(statement).executeQuery(argThat(sql -> sql.contains("convert_to")));
        order.verify(connection).setSavepoint();
        order.verify(statement).executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY);
    }

    @Test
    void routineBodyCacheUsesComparisonTelemetryFromSettings(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        ResultSet lookup = capabilityResult();
        ResultSet vector = mock(ResultSet.class);
        when(vector.next()).thenReturn(true, false);
        when(vector.getBoolean("compatible")).thenReturn(true);
        ResultSet identity = identityResult("captured_database");
        when(statement.executeQuery(argThat(sql -> sql != null
                && sql.contains("to_regprocedure")))).thenReturn(lookup);
        when(statement.executeQuery(argThat(sql -> sql != null
                && sql.contains("convert_to")))).thenReturn(vector);
        when(statement.executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY))
                .thenReturn(identity);

        var events = new ArrayList<PgRoutineBodyCacheTelemetry>();
        var settings = new CoreSettings();
        settings.setPgCatalogCacheDir(cacheDir.toString());
        settings.setComparisonTelemetry(new IComparisonTelemetry() {
            @Override
            public void pgRoutineBodyCacheFinished(PgRoutineBodyCacheTelemetry event) {
                events.add(event);
            }
        });
        SQLException stop = new SQLException("stop after cache construction");
        var loader = new CacheBoundaryLoader(connector, settings, stop, true);
        loader.resolveRoutineBodiesAtBoundary();

        IOException thrown = assertThrows(IOException.class, loader::load);

        assertSame(stop, thrown.getCause());
        assertEquals(1, events.size());
        assertEquals(new PgRoutineBodyCacheTelemetry(0, 0, 0, 0, 0,
                        events.get(0).elapsedNanos()),
                events.get(0));
    }

    @Test
    void unavailableSystemIdentifierKeepsPackedCacheButDisablesDirectReplay(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        ResultSet identity = identityResult("captured_database", null);
        when(statement.executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT))
                .thenReturn(identity);
        var settings = new CoreSettings();
        settings.setPgCatalogCacheDir(cacheDir.toString());
        settings.setPgCatalogCacheRows(true);
        settings.setPgCatalogCacheFingerprintProbe(true);
        SQLException stop = new SQLException("stop after cache construction");
        var loader = new CacheBoundaryLoader(connector, settings, stop, false);

        IOException thrown = assertThrows(IOException.class, loader::load);

        assertSame(stop, thrown.getCause());
        assertTrue(loader.catalogMutationReached);
        assertTrue(loader.rowCacheDirectory != null);
        assertNull(loader.rowCacheSnapshotDigest,
                "null system identifier must fail closed for direct replay");
        verify(statement).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT);
        verify(statement, never()).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK);
    }

    @Test
    void unsupportedSnapshotProbeRollsBackSavepointAndUsesBaseIdentity(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        SQLException unsupported = new SQLException(
                "snapshot function is unavailable");
        when(statement.executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT))
                .thenThrow(unsupported);
        ResultSet baseIdentity = identityResult("captured_database",
                "7504815387372040237", null);
        when(statement.executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY))
                .thenReturn(baseIdentity);
        var settings = new CoreSettings();
        settings.setPgCatalogCacheDir(cacheDir.toString());
        settings.setPgCatalogCacheRows(true);
        settings.setPgCatalogCacheFingerprintProbe(true);
        SQLException stop = new SQLException("stop after cache construction");
        var loader = new CacheBoundaryLoader(connector, settings, stop, false);

        IOException thrown = assertThrows(IOException.class, loader::load);

        assertSame(stop, thrown.getCause());
        assertTrue(loader.catalogMutationReached);
        assertNull(loader.rowCacheSnapshotDigest);
        var order = inOrder(connection, statement);
        order.verify(connection).setSavepoint();
        order.verify(statement).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT);
        order.verify(connection).rollback(probeSavepoint);
        order.verify(statement).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY);
    }

    @Test
    void privilegedIdentityUsesTheTwoFunctionFastPath(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        ResultSet identity = identityResult("captured_database",
                "7504815387372040237", "100:200:");
        when(statement.executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT))
                .thenReturn(identity);
        var settings = new CoreSettings();
        settings.setPgCatalogCacheDir(cacheDir.toString());
        settings.setPgCatalogCacheRows(true);
        settings.setPgCatalogCacheFingerprintProbe(true);
        SQLException stop = new SQLException("stop after cache construction");
        var loader = new CacheBoundaryLoader(connector, settings, stop, false);

        IOException thrown = assertThrows(IOException.class, loader::load);

        assertSame(stop, thrown.getCause());
        assertTrue(loader.catalogMutationReached);
        assertTrue(loader.rowCacheSnapshotDigest != null);
        verify(connection).setSavepoint();
        verify(connection, never()).rollback(probeSavepoint);
        verify(statement, never()).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY);
        verify(statement, never()).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK);
        verify(statement, never()).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK);
    }

    @Test
    void systemDeniedSnapshotAllowedUsesSnapshotOnlyVariant(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        when(statement.executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT))
                .thenThrow(new SQLException("system denied in combined probe"));
        when(statement.executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY))
                .thenThrow(new SQLException("system denied"));
        ResultSet snapshotIdentity = identityResult("captured_database", null,
                "100:200:");
        when(statement.executeQuery(PgCatalogCacheNamespace
                .IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK))
                .thenReturn(snapshotIdentity);
        var settings = cacheSettings(cacheDir, true);
        SQLException stop = new SQLException("stop after cache construction");
        var loader = new CacheBoundaryLoader(connector, settings, stop, false);

        IOException thrown = assertThrows(IOException.class, loader::load);

        assertSame(stop, thrown.getCause());
        assertTrue(loader.catalogMutationReached);
        assertNull(loader.rowCacheSnapshotDigest,
                "snapshot-only identity must not enable direct replay");
        var order = inOrder(connection, statement);
        order.verify(connection).setSavepoint();
        order.verify(statement).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT);
        order.verify(connection).rollback(probeSavepoint);
        order.verify(statement).executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY);
        order.verify(connection).rollback(probeSavepoint);
        order.verify(statement).executeQuery(PgCatalogCacheNamespace
                .IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK);
        verify(statement, never()).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK);
    }

    @Test
    void bothOptionalFunctionsDeniedFallsBackToFullySafeIdentity(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        SQLException denied = new SQLException("optional function denied");
        when(statement.executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT))
                .thenThrow(denied);
        when(statement.executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY))
                .thenThrow(denied);
        when(statement.executeQuery(PgCatalogCacheNamespace
                .IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK))
                .thenThrow(denied);
        ResultSet safeIdentity = identityResult("captured_database", null,
                null);
        when(statement.executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK))
                .thenReturn(safeIdentity);
        var settings = cacheSettings(cacheDir, true);
        SQLException stop = new SQLException("stop after cache construction");
        var loader = new CacheBoundaryLoader(connector, settings, stop, false);

        IOException thrown = assertThrows(IOException.class, loader::load);

        assertSame(stop, thrown.getCause());
        assertTrue(loader.catalogMutationReached);
        var order = inOrder(connection, statement);
        order.verify(connection).setSavepoint();
        for (String query : new String[] {
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT,
                PgCatalogCacheNamespace.IDENTITY_QUERY,
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK
        }) {
            order.verify(statement).executeQuery(query);
            order.verify(connection).rollback(probeSavepoint);
        }
        order.verify(statement).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK);
    }

    @Test
    void emptyOptionalIdentityRollsBackBeforeTheNextVariant(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        when(statement.executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT))
                .thenReturn(empty);
        ResultSet systemIdentity = identityResult("captured_database",
                "7504815387372040237", null);
        when(statement.executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY))
                .thenReturn(systemIdentity);
        var settings = cacheSettings(cacheDir, true);
        SQLException stop = new SQLException("stop after cache construction");
        var loader = new CacheBoundaryLoader(connector, settings, stop, false);

        IOException thrown = assertThrows(IOException.class, loader::load);

        assertSame(stop, thrown.getCause());
        var order = inOrder(connection, statement);
        order.verify(connection).setSavepoint();
        order.verify(statement).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT);
        order.verify(connection).rollback(probeSavepoint);
        order.verify(statement).executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY);
    }

    @Test
    void duplicateOptionalIdentityRollsBackBeforeTheNextVariant(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet duplicate = identityResult("captured_database",
                "7504815387372040237", "100:200:");
        when(duplicate.next()).thenReturn(true, true);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        when(statement.executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT))
                .thenReturn(duplicate);
        ResultSet systemIdentity = identityResult("captured_database",
                "7504815387372040237", null);
        when(statement.executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY))
                .thenReturn(systemIdentity);
        var settings = cacheSettings(cacheDir, true);
        SQLException stop = new SQLException("stop after cache construction");
        var loader = new CacheBoundaryLoader(connector, settings, stop, false);

        IOException thrown = assertThrows(IOException.class, loader::load);

        assertSame(stop, thrown.getCause());
        var order = inOrder(connection, statement);
        order.verify(connection).setSavepoint();
        order.verify(statement).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT);
        order.verify(connection).rollback(probeSavepoint);
        order.verify(statement).executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY);
    }

    @Test
    void probeBoundaryFailureAbortsIdentityWithoutFallbackOrCacheMutation(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        SQLException boundaryFailure = new SQLException(
                "controlled probe boundary failure");
        when(connection.setSavepoint()).thenThrow(boundaryFailure);
        var loader = new CacheBoundaryLoader(connector,
                cacheSettings(cacheDir, false),
                new SQLException("must not reach catalog mutation"), false);

        IOException thrown = assertThrows(IOException.class, loader::load);

        assertEquals(boundaryFailure.getMessage(), thrown.getCause().getMessage());
        assertFalse(loader.catalogMutationReached);
        assertNull(loader.routineCacheDirectory);
        assertNull(loader.rowCacheDirectory);
        verify(statement, never()).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY);
        verify(statement, never()).executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK);
    }

    /**
     * The identity ladder must leave its rollback boundary open: every later
     * catalog probe of this connection reuses it instead of paying two round
     * trips of its own.
     */
    @Test
    void successfulIdentityKeepsTheProbeBoundaryOpen(@TempDir Path cacheDir)
            throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        ResultSet identity = identityResult("captured_database");
        when(statement.executeQuery(PgCatalogCacheNamespace.IDENTITY_QUERY))
                .thenReturn(identity);
        var loader = new CacheBoundaryLoader(connector,
                cacheSettings(cacheDir, false),
                new SQLException("stop after cache construction"), false);

        assertThrows(IOException.class, loader::load);

        assertTrue(loader.catalogMutationReached);
        verify(connection).setSavepoint();
        verify(connection, never()).releaseSavepoint(probeSavepoint);
        verify(connection, never()).rollback(probeSavepoint);
    }

    @Test
    void cancellationAfterOptionalIdentityDoesNotEnterFallbackLadder(
            @TempDir Path cacheDir) throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        var monitor = new NullMonitor();
        ResultSet identity = identityResult("captured_database",
                "7504815387372040237", "100:200:");
        when(identity.next()).thenReturn(true).thenAnswer(invocation -> {
            monitor.setCancelled(true);
            return false;
        });
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        when(statement.executeQuery(
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT))
                .thenReturn(identity);
        var settings = cacheSettings(cacheDir, true);
        settings.setMonitor(monitor);
        var loader = new CacheBoundaryLoader(connector, settings,
                new SQLException("must not reach catalog mutation"), false);
        Thread.interrupted();

        try {
            assertThrows(InterruptedException.class, loader::load);
            verify(statement).executeQuery(
                    PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT);
            verify(connection, never()).rollback(probeSavepoint);
            verify(statement, never()).executeQuery(
                    PgCatalogCacheNamespace.IDENTITY_QUERY);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void databaseFactoryPropagatesObjectReferencePolicy() {
        ISettings disabledSettings = mock(ISettings.class);
        when(disabledSettings.isCollectObjectReferences()).thenReturn(false);
        var disabledLoader = new ExposedPgJdbcLoader(mockConnector(), disabledSettings);
        var enabledLoader = new ExposedPgJdbcLoader(mockConnector(), new CoreSettings());
        var location = mock(ObjectLocation.class);

        var disabled = disabledLoader.createEmptyDatabase();
        disabled.addReference("disabled.sql", location);
        var enabled = enabledLoader.createEmptyDatabase();
        enabled.addReference("enabled.sql", location);

        assertFalse(disabled.getObjReferences().containsKey("disabled.sql"));
        assertTrue(enabled.getObjReferences().containsKey("enabled.sql"));
    }

    @Test
    void terminalHookClearsPostgresAndBaseCaches() throws Exception {
        var loader = new ExposedPgJdbcLoader(mockConnector(), new CoreSettings());
        var types = new HashMap<Long, PgJdbcType>();
        types.put(1L, mock(PgJdbcType.class));
        var collations = new HashMap<Long, CachedCollation>();
        collations.put(1L, new CachedCollation("app", "collation"));
        var roles = new HashMap<Long, String>();
        roles.put(1L, "role");

        setField(PgJdbcLoader.class, loader, "cachedTypesByOid", types);
        setField(PgJdbcLoader.class, loader, "cachedCollationsByOid", collations);
        setField(PgJdbcLoader.class, loader, "extensionSchema", "pgcodekeeper");
        setField(AbstractJdbcLoader.class, loader, "cachedRolesNamesByOid", roles);
        loader.putSchema(1L, mock(ISchema.class));
        loader.setCurrentOperation("operation");
        loader.setCurrentObject(new ObjectReference("public", "table", DbObjType.TABLE));

        loader.release();

        assertNull(getField(PgJdbcLoader.class, loader, "cachedTypesByOid"));
        assertNull(getField(PgJdbcLoader.class, loader, "cachedCollationsByOid"));
        assertNull(getField(PgJdbcLoader.class, loader, "extensionSchema"));
        assertNull(getField(AbstractJdbcLoader.class, loader, "cachedRolesNamesByOid"));
        assertNull(getField(AbstractJdbcLoader.class, loader, "currentObject"));
        assertNull(getField(AbstractLoader.class, loader, "currentOperation"));
        assertEquals(0, types.size());
        assertEquals(0, collations.size());
        assertEquals(0, roles.size());
        assertTrue(loader.getSchemas().isEmpty());
    }

    @Test
    void terminalHookClosesPendingRoutineBodies() throws Exception {
        var loader = new ExposedPgJdbcLoader(mockConnector(), new CoreSettings());
        var resolution = PgJdbcRoutineBodyResolution.fullBody();
        var schema = new PgSchema("public");
        var function = new PgFunction("pending_body");
        schema.addChild(function);
        RoutineBodySource source = resolution.registerFullBody(
                function, "SELECT 1", "$$SELECT 1$$", RoutineBodyProfile.current(false),
                RoutineBodyRepresentation.SQL_TEXT);
        setField(PgJdbcLoader.class, loader, "routineBodyResolution", resolution);

        loader.release();

        assertNull(getField(PgJdbcLoader.class, loader, "routineBodyResolution"));
        assertThrows(IllegalStateException.class, source::take);
    }

    @Test
    void failedLoadCreatesFreshRoutineBodyManagerOnRetry() throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection preloadConnection = mock(Connection.class);
        Statement preloadStatement = mock(Statement.class);
        IOException firstFailure = new IOException("first load failure");
        IOException secondFailure = new IOException("second load failure");
        when(preloadConnection.createStatement()).thenReturn(preloadStatement);
        when(connector.getConnection())
                .thenReturn(preloadConnection)
                .thenThrow(firstFailure)
                .thenThrow(secondFailure);
        var loader = new ExposedPgJdbcLoader(connector, new CoreSettings());

        IOException firstThrown = assertThrows(IOException.class, loader::load);
        assertSame(firstFailure, firstThrown.getCause());
        assertNull(getField(PgJdbcLoader.class, loader, "routineBodyResolution"));
        IOException secondThrown = assertThrows(IOException.class, loader::load);
        assertSame(secondFailure, secondThrown.getCause());
        assertNull(getField(PgJdbcLoader.class, loader, "routineBodyResolution"));
    }

    @Test
    void systemLoaderFailureAbortsTasksAndReleasesAllRetainedState() throws Exception {
        IJdbcConnector connector = mockConnector();
        IOException connectionFailure = new IOException("controlled connection failure");
        when(connector.getConnection()).thenThrow(connectionFailure);
        PgJdbcSystemLoader loader = newSystemLoader(connector);
        Connection staleConnection = mock(Connection.class);
        Statement staleStatement = mock(Statement.class);
        var types = new HashMap<Long, PgJdbcType>();
        types.put(1L, mock(PgJdbcType.class));
        var collations = new HashMap<Long, CachedCollation>();
        collations.put(1L, new CachedCollation("app", "collation"));
        var roles = new HashMap<Long, String>();
        roles.put(1L, "role");
        var future = new FutureTask<>(() -> "pending");

        setField(AbstractJdbcLoader.class, loader, "connection", staleConnection);
        setField(AbstractJdbcLoader.class, loader, "statement", staleStatement);
        setField(AbstractJdbcLoader.class, loader, "cachedRolesNamesByOid", roles);
        setField(PgJdbcLoader.class, loader, "cachedTypesByOid", types);
        setField(PgJdbcLoader.class, loader, "cachedCollationsByOid", collations);
        setField(PgJdbcLoader.class, loader, "extensionSchema", "pgcodekeeper");
        loader.putSchema(1L, mock(ISchema.class));
        loader.setCurrentObject(new ObjectReference("public", "table", DbObjType.TABLE));
        parserTasks(loader).add(new AntlrTask<>(future, ignored -> { }));

        IOException thrown = assertThrows(IOException.class, loader::getStorageFromJdbc);

        assertSame(connectionFailure, thrown.getCause());
        assertTrue(future.isCancelled());
        assertTrue(parserTasks(loader).isEmpty());
        assertNull(getField(AbstractJdbcLoader.class, loader, "connection"));
        assertNull(getField(AbstractJdbcLoader.class, loader, "statement"));
        assertNull(getField(AbstractJdbcLoader.class, loader, "cachedRolesNamesByOid"));
        assertNull(getField(AbstractJdbcLoader.class, loader, "currentObject"));
        assertNull(getField(AbstractLoader.class, loader, "currentOperation"));
        assertNull(getField(PgJdbcLoader.class, loader, "cachedTypesByOid"));
        assertNull(getField(PgJdbcLoader.class, loader, "cachedCollationsByOid"));
        assertNull(getField(PgJdbcLoader.class, loader, "extensionSchema"));
        assertEquals(0, roles.size());
        assertEquals(0, types.size());
        assertEquals(0, collations.size());
        assertTrue(loader.getSchemas().isEmpty());
        verify(staleConnection, never()).close();
        verify(staleStatement, never()).close();
    }

    @Test
    void systemLoaderPreservesPrimaryFailureWhenTaskCancellationFails() throws Exception {
        IJdbcConnector connector = mockConnector();
        IOException connectionFailure = new IOException("controlled connection failure");
        when(connector.getConnection()).thenThrow(connectionFailure);
        PgJdbcSystemLoader loader = newSystemLoader(connector);
        IllegalStateException cancellationFailure = new IllegalStateException("controlled cancellation failure");
        @SuppressWarnings("unchecked")
        Future<String> future = mock(Future.class);
        when(future.cancel(true)).thenThrow(cancellationFailure);
        parserTasks(loader).add(new AntlrTask<>(future, ignored -> { }));

        IOException thrown = assertThrows(IOException.class, loader::getStorageFromJdbc);

        assertSame(connectionFailure, thrown.getCause());
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cancellationFailure, thrown.getSuppressed()[0]);
        assertTrue(parserTasks(loader).isEmpty());
    }

    @Test
    void systemLoaderRestoresInterruptFlagBeforeRethrowingInterruption() throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        JdbcRunner runner = mock(JdbcRunner.class);
        InterruptedException interruption = new InterruptedException("controlled interruption");
        doThrow(interruption).when(runner).run(statement,
                PgJdbcLoader.buildSessionSetupScript(Consts.UTC));
        PgJdbcSystemLoader loader = newSystemLoader(connector);
        setField(AbstractJdbcLoader.class, loader, "runner", runner);
        Thread.interrupted();

        try {
            InterruptedException thrown = assertThrows(InterruptedException.class, loader::getStorageFromJdbc);

            assertSame(interruption, thrown);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void systemLoaderReleaseRethrowingPrimaryErrorDoesNotSelfSuppress() throws Exception {
        IJdbcConnector connector = mockConnector();
        AssertionError primary = new AssertionError("controlled failure");
        when(connector.getConnection()).thenThrow(primary);
        PgJdbcSystemLoader loader = newSystemLoader(connector);
        @SuppressWarnings("unchecked")
        Map<Long, String> roles = mock(Map.class);
        doThrow(primary).when(roles).clear();
        setField(AbstractJdbcLoader.class, loader, "cachedRolesNamesByOid", roles);

        AssertionError thrown = assertThrows(AssertionError.class, loader::getStorageFromJdbc);

        assertSame(primary, thrown);
    }

    @Test
    void preloadEntryCancellationRestoresInterruptFlag() throws Exception {
        var loader = new ExposedPgJdbcLoader(mockConnector(), new CoreSettings());
        Thread.interrupted();
        loader.cancel();

        try {
            assertThrows(InterruptedException.class, loader::preLoad);

            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(loader.preloaded());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preloadPublicationCancellationRestoresInterruptFlag() throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        var settings = new CoreSettings();
        settings.setMonitor(new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                        AbstractJdbcLoader.class.getName().equals(frame.getClassName())
                                && "publishPreloaded".equals(frame.getMethodName())));
            }
        });
        var loader = new ExposedPgJdbcLoader(connector, settings);
        Thread.interrupted();

        try {
            assertThrows(InterruptedException.class, loader::preLoad);

            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(loader.preloaded());
            verify(statement).close();
            verify(connection).close();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preloadFinalGateRunsAfterPhysicalCloseBeforePublication() throws Exception {
        IJdbcConnector connector = mockConnector();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        Savepoint probeSavepoint = stubProbeSavepoint(connection);
        var loader = new ExposedPgJdbcLoader(connector, new CoreSettings());
        doAnswer(invocation -> {
            loader.cancel();
            return null;
        }).when(statement).close();
        Thread.interrupted();

        try {
            assertThrows(InterruptedException.class, loader::preLoad);

            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(loader.preloaded());
            verify(statement).close();
            verify(connection).close();
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Stubs the shared catalog probe boundary of one mocked connection so the
     * loader gets a distinguishable savepoint to roll back to.
     */
    private static Savepoint stubProbeSavepoint(Connection connection)
            throws SQLException {
        Savepoint savepoint = mock(Savepoint.class);
        when(connection.setSavepoint()).thenReturn(savepoint);
        return savepoint;
    }

    private static IJdbcConnector mockConnector() {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        when(connector.getDbName()).thenReturn("test");
        return connector;
    }

    private static CoreSettings cacheSettings(Path cacheDir,
            boolean snapshotProbe) {
        var settings = new CoreSettings();
        settings.setPgCatalogCacheDir(cacheDir.toString());
        settings.setPgCatalogCacheRows(true);
        settings.setPgCatalogCacheFingerprintProbe(snapshotProbe);
        return settings;
    }

    private static ResultSet capabilityResult() throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getBoolean("available")).thenReturn(true);
        when(result.getBoolean("executable")).thenReturn(true);
        when(result.getBoolean("utf8_database")).thenReturn(true);
        return result;
    }

    private static ResultSet identityResult(String databaseName) throws SQLException {
        return identityResult(databaseName, "7504815387372040237");
    }

    private static ResultSet identityResult(String databaseName,
            String systemIdentifier) throws SQLException {
        return identityResult(databaseName, systemIdentifier, "100:200:");
    }

    private static ResultSet identityResult(String databaseName,
            String systemIdentifier, String snapshotToken)
            throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getString(anyString())).thenAnswer(invocation -> switch (
                invocation.<String>getArgument(0)) {
            case "server_address" -> "192.0.2.10";
            case "server_port" -> "5432";
            case "database_name" -> databaseName;
            case "database_oid" -> "16384";
            case "system_identifier" -> systemIdentifier;
            case "session_user_name" -> "session_user";
            case "current_role_name" -> "current_role";
            case "server_version_num" -> "170006";
            case "timezone" -> "UTC";
            case "date_style" -> "ISO, MDY";
            case "interval_style" -> "postgres";
            case "extra_float_digits" -> "1";
            case "bytea_output" -> "hex";
            case "quote_all_identifiers" -> "off";
            case "snapshot_token" -> snapshotToken;
            default -> throw new AssertionError("Unexpected identity column");
        });
        return result;
    }

    private static PgJdbcSystemLoader newSystemLoader(IJdbcConnector connector) throws Exception {
        var constructor = PgJdbcSystemLoader.class.getDeclaredConstructor(IJdbcConnector.class);
        constructor.setAccessible(true);
        return constructor.newInstance(connector);
    }

    @SuppressWarnings("unchecked")
    private static Queue<AntlrTask<?>> parserTasks(PgJdbcSystemLoader loader) throws Exception {
        return (Queue<AntlrTask<?>>) getField(AbstractLoader.class, loader, "antlrTasks");
    }

    private static Object getField(Class<?> owner, Object target, String name)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class ExposedPgJdbcLoader extends PgJdbcLoader {

        private ExposedPgJdbcLoader(IJdbcConnector connector, ISettings settings) {
            super(connector, "UTC", settings);
        }

        private void release() {
            releaseLoadResources();
        }

        private PgDatabase createEmptyDatabase() {
            return createDatabase();
        }

        @Override
        protected void queryCheckServerVersion(Statement statement) {
            // no-op: lifecycle tests do not need a live server
        }

        private boolean preloaded() {
            return isPreloaded;
        }
    }

    private static final class CacheBoundaryLoader extends PgJdbcLoader {

        private final SQLException stop;
        private final boolean fingerprint;
        private boolean catalogMutationReached;
        private Path routineCacheDirectory;
        private Path rowCacheDirectory;
        private byte[] rowCacheSnapshotDigest;
        private boolean resolveRoutineBodiesAtBoundary;

        private CacheBoundaryLoader(IJdbcConnector connector, CoreSettings settings,
                SQLException stop, boolean fingerprint) {
            super(connector, "UTC", settings);
            this.stop = stop;
            this.fingerprint = fingerprint;
            setVersion(150000);
        }

        @Override
        public void preLoad() {
            // The test fixes PostgreSQL v15 and exercises only the load boundary.
        }

        @Override
        protected boolean requestRoutineBodyFingerprints() {
            return fingerprint;
        }

        private void resolveRoutineBodiesAtBoundary() {
            resolveRoutineBodiesAtBoundary = true;
        }

        @Override
        protected void queryCheckLastSysOid() throws SQLException {
            try {
                if (resolveRoutineBodiesAtBoundary) {
                    var resolution = (PgJdbcRoutineBodyResolution) getField(
                            PgJdbcLoader.class, this, "routineBodyResolution");
                    resolution.resolveAll(new NullMonitor());
                }
                Object rowCache = getField(PgJdbcLoader.class, this,
                        "catalogRowCache");
                if (rowCache != null) {
                    rowCacheDirectory = cacheRoot(PgCatalogRowCache.class,
                            rowCache);
                    rowCacheSnapshotDigest = (byte[]) getField(
                            PgCatalogRowCache.class, rowCache,
                            "snapshotDigest");
                }
            } catch (IOException | InterruptedException
                    | ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
            catalogMutationReached = true;
            throw stop;
        }

        @Override
        protected void releaseLoadResources() {
            try {
                Object resolution = getField(PgJdbcLoader.class, this,
                        "routineBodyResolution");
                if (resolution != null) {
                    Object resolver = getField(PgJdbcRoutineBodyResolution.class,
                            resolution, "resolver");
                    Object bodyCache = getField(resolver.getClass(), resolver,
                            "bodyCache");
                    routineCacheDirectory = cacheRoot(PgRoutineBodyCache.class,
                            bodyCache);
                }
                Object rowCache = getField(PgJdbcLoader.class, this, "catalogRowCache");
                if (rowCache != null) {
                    rowCacheDirectory = cacheRoot(PgCatalogRowCache.class, rowCache);
                    rowCacheSnapshotDigest = (byte[]) getField(
                            PgCatalogRowCache.class, rowCache,
                            "snapshotDigest");
                }
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            } finally {
                super.releaseLoadResources();
            }
        }

        private static Path cacheRoot(Class<?> cacheType, Object cache)
                throws ReflectiveOperationException {
            if (cacheType == PgCatalogRowCache.class) {
                Object packStore = getField(cacheType, cache, "packStore");
                return (Path) getField(packStore.getClass(), packStore,
                        "root");
            }
            Object store = getField(cacheType, cache, "store");
            return (Path) getField(ContentAddressedFileStore.class, store, "root");
        }
    }
}
