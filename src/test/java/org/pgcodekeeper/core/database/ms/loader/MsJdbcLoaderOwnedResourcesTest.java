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
package org.pgcodekeeper.core.database.ms.loader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.base.loader.AbstractJdbcLoader;
import org.pgcodekeeper.core.database.base.loader.AbstractLoader;
import org.pgcodekeeper.core.database.ms.jdbc.MsSupportedVersion;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;

class MsJdbcLoaderOwnedResourcesTest {

    @Test
    void preloadEntryCancellationRestoresInterruptFlag() throws Exception {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        when(connector.getDbName()).thenReturn("test");
        var loader = new MsJdbcLoader(connector, new CoreSettings());
        Thread.interrupted();
        loader.cancel();

        try {
            assertThrows(InterruptedException.class, loader::preLoad);

            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(preloaded(loader));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preloadPublicationCancellationRestoresInterruptFlag() throws Exception {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(connector.getDbName()).thenReturn("test");
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(MsSupportedVersion.VERSION_17.getVersion());
        var settings = new CoreSettings();
        settings.setMonitor(new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                        AbstractJdbcLoader.class.getName().equals(frame.getClassName())
                                && "publishPreloaded".equals(frame.getMethodName())));
            }
        });
        var loader = new MsJdbcLoader(connector, settings);
        Thread.interrupted();

        try {
            assertThrows(InterruptedException.class, loader::preLoad);

            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(preloaded(loader));
            verify(result).close();
            verify(statement).close();
            verify(connection).close();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preloadFinalGateRunsAfterPhysicalCloseBeforePublication() throws Exception {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(connector.getDbName()).thenReturn("test");
        when(connector.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(MsSupportedVersion.VERSION_17.getVersion());
        var loader = new MsJdbcLoader(connector, new CoreSettings());
        doAnswer(invocation -> {
            loader.cancel();
            return null;
        }).when(statement).close();
        Thread.interrupted();

        try {
            assertThrows(InterruptedException.class, loader::preLoad);

            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(preloaded(loader));
            verify(result).close();
            verify(statement).close();
            verify(connection).close();
        } finally {
            Thread.interrupted();
        }
    }

    private static boolean preloaded(MsJdbcLoader loader) throws ReflectiveOperationException {
        Field field = AbstractLoader.class.getDeclaredField("isPreloaded");
        field.setAccessible(true);
        return field.getBoolean(loader);
    }
}
