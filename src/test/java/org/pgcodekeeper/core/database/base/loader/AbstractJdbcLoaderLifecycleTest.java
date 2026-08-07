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
package org.pgcodekeeper.core.database.base.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ISchema;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;

class AbstractJdbcLoaderLifecycleTest {

    @Test
    void terminalLoadReleasesJdbcStateButKeepsModelLaunchers() throws Exception {
        var loader = new TestJdbcLoader(false);

        PgDatabase db = loader.load();

        assertNull(loader.retainedConnection());
        assertNull(loader.retainedStatement());
        assertEquals(0, loader.schemaCacheSize());
        assertNull(loader.retainedRoles());
        assertNull(loader.retainedCurrentObject());
        assertNull(loader.retainedCurrentOperation());
        assertEquals(0, loader.roles.size());
        assertFalse(db.getAnalysisLaunchers().isEmpty());
        verify(loader.connectionMock, never()).close();
        verify(loader.statementMock, never()).close();

        assertSame(db, loader.load());
        assertEquals(1, loader.loadCount);
        assertEquals(1, loader.releaseCount);
    }

    @Test
    void failedLoadAlsoReleasesJdbcStateWithoutClosingOwnedResources() throws Exception {
        var loader = new TestJdbcLoader(true);

        assertThrows(IOException.class, loader::load);

        assertNull(loader.retainedConnection());
        assertNull(loader.retainedStatement());
        assertEquals(0, loader.schemaCacheSize());
        assertNull(loader.retainedRoles());
        assertNull(loader.retainedCurrentObject());
        assertNull(loader.retainedCurrentOperation());
        assertEquals(0, loader.roles.size());
        verify(loader.connectionMock, never()).close();
        verify(loader.statementMock, never()).close();
    }

    private static final class TestJdbcLoader extends AbstractJdbcLoader<PgDatabase> {

        private final boolean fail;
        private final Connection connectionMock = mock(Connection.class);
        private final Statement statementMock = mock(Statement.class);
        private final Map<Long, String> roles = new HashMap<>();
        private int loadCount;
        private int releaseCount;

        private TestJdbcLoader(boolean fail) {
            super(connector(), new CoreSettings());
            this.fail = fail;
        }

        private static IJdbcConnector connector() {
            IJdbcConnector connector = mock(IJdbcConnector.class);
            when(connector.getDbName()).thenReturn("test");
            return connector;
        }

        @Override
        protected PgDatabase loadInternal() throws IOException {
            loadCount++;
            connection = connectionMock;
            statement = statementMock;
            schemaIds.put(1L, mock(ISchema.class));
            roles.put(1L, "role");
            cachedRolesNamesByOid = roles;
            setCurrentOperation("operation");
            setCurrentObject(new ObjectReference("public", "table", DbObjType.TABLE));
            if (fail) {
                throw new IOException("controlled failure");
            }

            PgDatabase db = createDatabase();
            db.addAnalysisLauncher(mock(IAnalysisLauncher.class));
            return db;
        }

        @Override
        protected void releaseLoadResources() {
            releaseCount++;
            super.releaseLoadResources();
        }

        @Override
        protected PgDatabase createDatabase() {
            return new PgDatabase();
        }

        private Connection retainedConnection() {
            return connection;
        }

        private Statement retainedStatement() {
            return statement;
        }

        private int schemaCacheSize() {
            return schemaIds.size();
        }

        private Map<Long, String> retainedRoles() {
            return cachedRolesNamesByOid;
        }

        private String retainedCurrentOperation() {
            return currentOperation;
        }

        private Object retainedCurrentObject() throws ReflectiveOperationException {
            Field field = AbstractJdbcLoader.class.getDeclaredField("currentObject");
            field.setAccessible(true);
            return field.get(this);
        }
    }
}
