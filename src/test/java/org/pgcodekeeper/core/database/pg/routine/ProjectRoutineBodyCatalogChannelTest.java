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
package org.pgcodekeeper.core.database.pg.routine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.BodyType;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;

class ProjectRoutineBodyCatalogChannelTest {

    @Test
    void publishesAndTransfersCatalogExactlyOnceWithoutRetainingIt() throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        var catalog = ProjectRoutineBodyCatalog.build(new PgDatabase());
        channel.open();
        channel.publish(catalog);

        ProjectRoutineBodyCatalog transferred = channel.take(new NullMonitor());

        assertSame(catalog, transferred);
        assertNull(retainedCatalog(channel));
        assertThrows(IllegalStateException.class,
                () -> channel.take(new NullMonitor()));
        channel.close();
        assertEquals(0, transferred.candidateCount());
    }

    @Test
    void dormantChannelSkipsProjectCatalogConstruction() {
        var channel = new ProjectRoutineBodyCatalogChannel();

        assertFalse(channel.publishIfOpen(new PgDatabase()));
        assertThrows(IllegalStateException.class,
                () -> channel.take(new NullMonitor()));
    }

    @Test
    void directPublicationFailureRemainsSelfContainedAndWakesConsumer()
            throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        RuntimeException cause = new RuntimeException("controlled catalog failure");
        var database = new PgDatabase() {
            @Override
            public List<org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher>
                    getAnalysisLaunchers() {
                throw cause;
            }
        };
        channel.open();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> channel.publishIfOpen(database));
        ProjectRoutineBodyCatalogException consumerFailure = assertThrows(
                ProjectRoutineBodyCatalogException.class,
                () -> channel.take(new NullMonitor()));

        assertSame(cause, thrown);
        assertSame(cause, consumerFailure.getCause());
    }

    @Test
    @Timeout(5)
    void cancellationWakesWaitingConsumerAndClearsPublishedState() throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        var enteredMonitor = new CountDownLatch(1);
        IMonitor monitor = monitor(enteredMonitor, new AtomicBoolean());

        var executor = Executors.newSingleThreadExecutor();
        try {
            var waiting = executor.submit(() -> channel.take(monitor));
            assertTrue(enteredMonitor.await(1, TimeUnit.SECONDS));

            channel.cancel();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> waiting.get(1, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertNull(retainedCatalog(channel));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void cancellationDuringCatalogConstructionClosesTheUnpublishedCatalog()
            throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        var buildEntered = new CountDownLatch(1);
        var releaseBuild = new CountDownLatch(1);
        var database = new PgDatabase() {
            @Override
            public List<org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher>
                    getAnalysisLaunchers() {
                buildEntered.countDown();
                try {
                    if (!releaseBuild.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("catalog build was not released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return super.getAnalysisLaunchers();
            }
        };

        var executor = Executors.newSingleThreadExecutor();
        try {
            var publishing = executor.submit(() -> channel.publishIfOpen(database));
            assertTrue(buildEntered.await(1, TimeUnit.SECONDS));

            channel.cancel();
            releaseBuild.countDown();

            assertFalse(publishing.get(1, TimeUnit.SECONDS));
            assertNull(retainedCatalog(channel));
        } finally {
            releaseBuild.countDown();
            channel.close();
            executor.shutdownNow();
        }
    }

    @Test
    void monitorCancellationIsObservedWhileWaiting() throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        var cancelled = new AtomicBoolean();
        IMonitor monitor = monitor(new CountDownLatch(0), cancelled);

        var executor = Executors.newSingleThreadExecutor();
        try {
            var waiting = executor.submit(() -> channel.take(monitor));
            cancelled.set(true);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> waiting.get(2, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
        } finally {
            channel.cancel();
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void directThreadInterruptTerminatesChannelWithoutExternalCleanup() throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        channel.open();
        var enteredMonitor = new CountDownLatch(1);
        var finished = new CountDownLatch(1);

        var executor = Executors.newSingleThreadExecutor();
        try {
            var waiting = executor.submit(() -> {
                try {
                    return channel.take(monitor(enteredMonitor, new AtomicBoolean()));
                } finally {
                    finished.countDown();
                }
            });
            assertTrue(enteredMonitor.await(1, TimeUnit.SECONDS));

            assertTrue(waiting.cancel(true));
            assertTrue(finished.await(1, TimeUnit.SECONDS));

            assertFalse(channel.publishIfOpen(new PgDatabase()));
            assertNull(retainedCatalog(channel));
        } finally {
            channel.close();
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void producerFailureWinsInterruptRaceAfterWaiterWasNotified() throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        var cause = new IllegalArgumentException("project failed");
        channel.open();
        var enteredMonitor = new CountDownLatch(1);
        var waiterThread = new AtomicReference<Thread>();

        var executor = Executors.newSingleThreadExecutor();
        try {
            var waiting = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                return channel.take(monitor(enteredMonitor, new AtomicBoolean()));
            });
            assertTrue(enteredMonitor.await(1, TimeUnit.SECONDS));

            synchronized (channel) {
                waiterThread.get().interrupt();
                channel.fail(cause);
            }

            ExecutionException wrapper = assertThrows(ExecutionException.class,
                    () -> waiting.get(1, TimeUnit.SECONDS));
            ProjectRoutineBodyCatalogException failure = assertInstanceOf(
                    ProjectRoutineBodyCatalogException.class, wrapper.getCause());
            assertSame(cause, failure.getCause());
        } finally {
            channel.close();
            executor.shutdownNow();
        }
    }

    @Test
    void producerFailureRetainsExactCauseOnlyUntilObserved() throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        var cause = new IllegalArgumentException("project failed");
        channel.open();
        channel.fail(cause);

        ProjectRoutineBodyCatalogException failure = assertThrows(
                ProjectRoutineBodyCatalogException.class,
                () -> channel.take(new NullMonitor()));

        assertSame(cause, failure.getCause());
        assertNull(retainedCatalog(channel));
        channel.close();
    }

    @Test
    void coordinatorCancellationDoesNotOverwriteProducerFailure() throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        var cause = new IllegalArgumentException("project failed");
        channel.open();

        channel.fail(cause);
        channel.cancel();

        ProjectRoutineBodyCatalogException failure = assertThrows(
                ProjectRoutineBodyCatalogException.class,
                () -> channel.take(new NullMonitor()));
        assertSame(cause, failure.getCause());
    }

    @Test
    void cancelledMonitorClosesAlreadyPublishedCatalogBeforeTransfer() throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        var catalog = nonEmptyCatalog();
        var cancelled = new AtomicBoolean(true);
        channel.open();
        channel.publish(catalog);

        assertThrows(InterruptedException.class,
                () -> channel.take(monitor(new CountDownLatch(0), cancelled)));

        assertNull(retainedCatalog(channel));
        assertEquals(0, catalog.candidateCount());
    }

    @Test
    void preInterruptedCallerClosesPublishedCatalogBeforeTransfer() throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        var catalog = nonEmptyCatalog();
        channel.open();
        channel.publish(catalog);

        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> channel.take(new NullMonitor()));
        } finally {
            Thread.interrupted();
        }

        assertNull(retainedCatalog(channel));
        assertEquals(0, catalog.candidateCount());
    }

    @Test
    void closeBeforeTakeClearsPublishedCatalog() throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        var catalog = ProjectRoutineBodyCatalog.build(new PgDatabase());
        channel.open();
        channel.publish(catalog);

        channel.close();

        assertNull(retainedCatalog(channel));
        assertThrows(IllegalStateException.class,
                () -> channel.take(new NullMonitor()));
    }

    @Test
    void repeatedPublicationAndTerminalOperationsStayOneShot() throws Exception {
        var channel = new ProjectRoutineBodyCatalogChannel();
        var first = ProjectRoutineBodyCatalog.build(new PgDatabase());
        var second = ProjectRoutineBodyCatalog.build(new PgDatabase());
        channel.open();

        assertThrows(IllegalStateException.class, channel::open);
        channel.publish(first);
        assertThrows(IllegalStateException.class, () -> channel.publish(second));

        channel.cancel();
        channel.cancel();
        channel.close();
        channel.close();

        assertNull(retainedCatalog(channel));
        assertThrows(IllegalStateException.class,
                () -> channel.take(new NullMonitor()));
        second.close();
    }

    private static IMonitor monitor(CountDownLatch entered, AtomicBoolean cancelled) {
        return new NullMonitor() {
            @Override
            public boolean isCancelled() {
                entered.countDown();
                return cancelled.get();
            }
        };
    }

    private static Object retainedCatalog(ProjectRoutineBodyCatalogChannel channel)
            throws ReflectiveOperationException {
        Field field = ProjectRoutineBodyCatalogChannel.class.getDeclaredField("catalog");
        field.setAccessible(true);
        return field.get(channel);
    }

    private static ProjectRoutineBodyCatalog nonEmptyCatalog() {
        var database = new PgDatabase();
        var schema = new PgSchema("public");
        var function = new PgFunction("f");
        String canonical = "$$SELECT 1$$";
        function.setBody(canonical);
        database.addChild(schema);
        schema.addChild(function);
        var source = OwnedRoutineBodySource.exchangeCandidate(
                "SELECT 1", canonical, RoutineBodyProfile.current(false),
                RoutineBodyRepresentation.SQL_TEXT);
        database.addAnalysisLauncher(new PgFuncProcAnalysisLauncher(function,
                source, BodyType.SQL, "routine body", "test.sql", List.of(), true));
        return ProjectRoutineBodyCatalog.build(database);
    }
}
