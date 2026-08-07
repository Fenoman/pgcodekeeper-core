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
package org.pgcodekeeper.core.database.ch.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.base.loader.AbstractJdbcLoader;
import org.pgcodekeeper.core.database.base.loader.AbstractLoader;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;

class ChJdbcLoaderOwnedResourcesTest {

    @Test
    void cancelledLoaderDoesNotPublishClickHousePreload() throws Exception {
        var loader = new ChJdbcLoader(connector(() -> {
            throw new AssertionError("preload must not acquire a connection");
        }), new CoreSettings());
        loader.cancel();

        assertThrows(InterruptedException.class, loader::preLoad);

        assertFalse(preloaded(loader));
    }

    @Test
    void preloadMonitorCallbackRunsOutsidePublicationMonitor() throws Exception {
        var publicationMonitor = new AtomicReference<Object>();
        var callbackObserved = new AtomicBoolean();
        var settings = new CoreSettings();
        settings.setMonitor(new NullMonitor() {
            @Override
            public boolean isCancelled() {
                callbackObserved.set(true);
                Object monitor = publicationMonitor.get();
                assertFalse(monitor != null && Thread.holdsLock(monitor),
                        "arbitrary monitor callbacks must not run under the publication monitor");
                return false;
            }
        });
        var loader = new ChJdbcLoader(connector(() -> {
            throw new AssertionError("preload must not acquire a connection");
        }), settings);
        publicationMonitor.set(publicationMonitor(loader));

        loader.preLoad();

        assertTrue(callbackObserved.get());
        assertTrue(preloaded(loader));
    }

    @Test
    void cancelBlockedCreateStatementClosesPublishedConnection() throws Exception {
        var createEntered = new CountDownLatch(1);
        var releaseCreate = new CountDownLatch(1);
        var connectionClosed = new CountDownLatch(1);
        var connectionCloses = new AtomicInteger();
        Statement statement = statement();
        Connection connection = connection(() -> {
            createEntered.countDown();
            awaitUninterruptibly(releaseCreate);
            return statement;
        }, () -> {
            connectionCloses.incrementAndGet();
            connectionClosed.countDown();
            releaseCreate.countDown();
        });
        var loader = new ChJdbcLoader(connector(() -> connection), new CoreSettings());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> owner = executor.submit(() -> {
            loader.load();
            return null;
        });

        try {
            assertTrue(createEntered.await(5, TimeUnit.SECONDS));
            loader.cancel();

            assertTrue(connectionClosed.await(5, TimeUnit.SECONDS),
                    "cancel must close the registered connection and release createStatement");
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> owner.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(connectionCloses.get() >= 1);
        } finally {
            releaseCreate.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @Test
    void connectionReturnedAfterCancellationIsSelfClaimedBeforeStatementCreation() throws Exception {
        var acquisitionEntered = new CountDownLatch(1);
        var releaseAcquisition = new CountDownLatch(1);
        var connectionCloses = new AtomicInteger();
        var createCalls = new AtomicInteger();
        Connection connection = connection(() -> {
            createCalls.incrementAndGet();
            throw new AssertionError("cancelled connection must not create a statement");
        }, connectionCloses::incrementAndGet);
        var loader = new ChJdbcLoader(connector(() -> {
            acquisitionEntered.countDown();
            awaitUninterruptibly(releaseAcquisition);
            return connection;
        }), new CoreSettings());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> owner = executor.submit(() -> {
            loader.load();
            return null;
        });

        try {
            assertTrue(acquisitionEntered.await(5, TimeUnit.SECONDS));
            loader.cancel();
            releaseAcquisition.countDown();

            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> owner.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertEquals(2, connectionCloses.get());
            assertEquals(0, createCalls.get());
        } finally {
            releaseAcquisition.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    private static IJdbcConnector connector(ConnectionSupplier supplier) {
        return new IJdbcConnector() {
            @Override
            public Connection getConnection() throws IOException {
                return supplier.get();
            }

            @Override
            public String getBatchDelimiter() {
                return null;
            }

            @Override
            public String getUrl() {
                return "jdbc:test";
            }

            @Override
            public String getDbName() {
                return "test";
            }
        };
    }

    private static boolean preloaded(ChJdbcLoader loader) throws ReflectiveOperationException {
        Field field = AbstractLoader.class.getDeclaredField("isPreloaded");
        field.setAccessible(true);
        return field.getBoolean(loader);
    }

    private static Object publicationMonitor(ChJdbcLoader loader) throws ReflectiveOperationException {
        Field field = AbstractJdbcLoader.class.getDeclaredField("preloadPublicationMonitor");
        field.setAccessible(true);
        return field.get(loader);
    }

    private static Connection connection(StatementSupplier create, SqlRun close) {
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
        case "createStatement" -> create.get();
        case "close" -> {
            close.run();
            yield null;
        }
        default -> objectMethod(proxy, method.getName(), args, method.getReturnType(), "Connection");
        });
    }

    private static Statement statement() {
        return proxy(Statement.class, (proxy, method, args) -> switch (method.getName()) {
        case "cancel", "close" -> null;
        default -> objectMethod(proxy, method.getName(), args, method.getReturnType(), "Statement");
        });
    }

    private static Object objectMethod(Object proxy, String method, Object[] args,
                                       Class<?> returnType, String label) {
        return switch (method) {
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        case "toString" -> label;
        default -> returnType == boolean.class ? false : returnType == int.class ? 0 : null;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
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

    @FunctionalInterface
    private interface ConnectionSupplier {

        Connection get() throws IOException;
    }

    @FunctionalInterface
    private interface StatementSupplier {

        Statement get();
    }

    @FunctionalInterface
    private interface SqlRun {

        void run();
    }
}
