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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.ms.loader.MsJdbcLoader;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.settings.CoreSettings;

class ConcreteJdbcLoaderOwnedResourcesTest {

    @ParameterizedTest
    @EnumSource(LoaderKind.class)
    void loadSelfClaimsConnectionReturnedAfterCancellation(LoaderKind kind) throws Exception {
        IJdbcConnector connector = mock(IJdbcConnector.class);
        Connection connection = mock(Connection.class);
        var acquisitionEntered = new CountDownLatch(1);
        var releaseAcquisition = new CountDownLatch(1);
        when(connector.getDbName()).thenReturn("test");
        when(connector.getConnection()).thenAnswer(invocation -> {
            acquisitionEntered.countDown();
            awaitUninterruptibly(releaseAcquisition);
            return connection;
        });
        doAnswer(invocation -> null).when(connection).close();
        AbstractJdbcLoader<?> loader = kind.create(connector);
        setPreloaded(loader);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> owner = executor.submit(() -> {
            loader.load();
            return null;
        });

        try {
            assertTrue(acquisitionEntered.await(5, TimeUnit.SECONDS));
            loader.cancel();
            releaseAcquisition.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            verify(connection, times(2)).close();
            verify(connection, never()).createStatement();
        } finally {
            releaseAcquisition.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    private static void setPreloaded(AbstractJdbcLoader<?> loader) throws ReflectiveOperationException {
        Field field = AbstractLoader.class.getDeclaredField("isPreloaded");
        field.setAccessible(true);
        field.setBoolean(loader, true);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
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

    private enum LoaderKind {
        POSTGRES {
            @Override
            AbstractJdbcLoader<?> create(IJdbcConnector connector) {
                return new PgJdbcLoader(connector, "UTC", new CoreSettings());
            }
        },
        SQL_SERVER {
            @Override
            AbstractJdbcLoader<?> create(IJdbcConnector connector) {
                return new MsJdbcLoader(connector, new CoreSettings());
            }
        };

        abstract AbstractJdbcLoader<?> create(IJdbcConnector connector);
    }
}
