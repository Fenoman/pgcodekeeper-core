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

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.FutureTask;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.base.loader.AbstractJdbcLoader;
import org.pgcodekeeper.core.database.base.loader.AbstractLoader;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.base.schema.meta.MetaStorage;

@Isolated("mutates parser pending-limit system properties")
class PgJdbcSystemLoaderDrainTest {

    private String originalMaxPending;
    private String originalMaxPendingBytes;

    @BeforeEach
    void configureBoundedPipeline() {
        originalMaxPending = System.getProperty(Consts.MAX_PENDING_TASKS);
        originalMaxPendingBytes = System.getProperty(Consts.MAX_PENDING_BYTES);
        System.setProperty(Consts.MAX_PENDING_TASKS, "1");
        System.setProperty(Consts.MAX_PENDING_BYTES, "0");
    }

    @AfterEach
    void restorePendingProperties() {
        restoreProperty(Consts.MAX_PENDING_TASKS, originalMaxPending);
        restoreProperty(Consts.MAX_PENDING_BYTES, originalMaxPendingBytes);
    }

    @Test
    void systemFunctionLoopDrainsCompletedTaskAtRowBoundary() throws Exception {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        when(connector.getDbName()).thenReturn("test");
        var constructor = PgJdbcSystemLoader.class.getDeclaredConstructor(IJdbcConnector.class);
        constructor.setAccessible(true);
        PgJdbcSystemLoader loader = constructor.newInstance(connector);

        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getString("name")).thenReturn("test_function");
        when(result.getString("nspname")).thenReturn("pg_catalog");
        when(result.getString("proarguments")).thenReturn("");
        when(result.getString("prorettype")).thenReturn("integer");
        setField(AbstractJdbcLoader.class, loader, "statement", statement);

        var finalized = new ArrayList<String>();
        var completed = new FutureTask<>(() -> "finalized");
        completed.run();
        parserTasks(loader).add(new AntlrTask<>(completed, finalized::add));

        Method readFunctions = PgJdbcSystemLoader.class
                .getDeclaredMethod("readFunctions", MetaStorage.class);
        readFunctions.setAccessible(true);
        readFunctions.invoke(loader, new MetaStorage());

        assertIterableEquals(List.of("finalized"), finalized);
    }

    @SuppressWarnings("unchecked")
    private static Queue<AntlrTask<?>> parserTasks(PgJdbcSystemLoader loader) throws Exception {
        Field field = AbstractLoader.class.getDeclaredField("antlrTasks");
        field.setAccessible(true);
        return (Queue<AntlrTask<?>>) field.get(loader);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
