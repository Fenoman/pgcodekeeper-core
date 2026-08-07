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

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcReader;
import org.pgcodekeeper.core.database.api.schema.ISchema;
import org.pgcodekeeper.core.database.base.loader.AbstractJdbcLoader;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.exception.ConcurrentModificationException;
import org.pgcodekeeper.core.exception.XmlReaderException;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;

@Isolated("mutates the parser max-pending system property")
class JdbcParserDrainTest {

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
    void restoreMaxPendingProperty() {
        if (originalMaxPending == null) {
            System.clearProperty(Consts.MAX_PENDING_TASKS);
        } else {
            System.setProperty(Consts.MAX_PENDING_TASKS, originalMaxPending);
        }
        if (originalMaxPendingBytes == null) {
            System.clearProperty(Consts.MAX_PENDING_BYTES);
        } else {
            System.setProperty(Consts.MAX_PENDING_BYTES, originalMaxPendingBytes);
        }
    }

    @Test
    void abstractJdbcReaderDrainsOneCompletedTaskAfterEveryRow() throws Exception {
        ReaderFixture fixture = new ReaderFixture(2);
        fixture.loader.enqueueCompleted("finalize1");
        fixture.loader.enqueueCompleted("finalize2");
        var reader = new TestJdbcReader(fixture.loader);

        reader.read();

        assertIterableEquals(List.of("process1", "finalize1", "process2", "finalize2"),
                fixture.loader.events);
        verify(fixture.result).close();
        fixture.loader.cancel();
        verify(fixture.statement, never()).cancel();
    }

    @Test
    void searchPathJdbcReaderDrainsOneCompletedTaskAfterEveryRow() throws Exception {
        ReaderFixture fixture = new ReaderFixture(2);
        fixture.loader.enqueueCompleted("finalize1");
        fixture.loader.enqueueCompleted("finalize2");
        ISchema schema = mock(ISchema.class);
        fixture.loader.putSchema(1, schema);
        when(fixture.result.getObject("schema_id")).thenReturn(1);
        var reader = new TestSearchPathJdbcReader(fixture.loader, schema);

        reader.read();

        assertIterableEquals(List.of("process1", "finalize1", "process2", "finalize2"),
                fixture.loader.events);
        verify(fixture.result).close();
        fixture.loader.cancel();
        verify(fixture.statement, never()).cancel();
    }

    @Test
    void readerWrapsCompletedParserIOExceptionWithoutChangingItsIdentity() throws Exception {
        ReaderFixture fixture = new ReaderFixture(1);
        IOException expected = new IOException("parser input failed");
        var reader = new FailingJdbcReader(fixture.loader, expected);

        XmlReaderException actual = assertThrows(XmlReaderException.class, reader::read);

        assertSame(expected, actual.getCause());
    }

    @ParameterizedTest
    @MethodSource("entryGateCases")
    void entryGateRunsBeforeOperationMutationAndQueryConstruction(EntryBlocker blocker,
            boolean searchPath)
            throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        var queryCalls = new AtomicInteger();
        fixture.loader.setCurrentOperation("operation-before-reader");
        switch (blocker) {
        case CANCELLED -> fixture.loader.cancel();
        case CLOSED -> {
            fixture.loader.close();
            fixture.loader.setCurrentOperation("operation-before-reader");
        }
        case MONITOR -> fixture.loader.getMonitor().setCancelled(true);
        case THREAD -> Thread.currentThread().interrupt();
        }
        IJdbcReader reader = searchPath
                ? new EntryTrackingSearchPathJdbcReader(fixture.loader, queryCalls)
                : new EntryTrackingJdbcReader(fixture.loader, queryCalls);

        try {
            if (blocker == EntryBlocker.CLOSED) {
                assertThrows(IllegalStateException.class, reader::read);
            } else {
                assertThrows(InterruptedException.class, reader::read);
            }
        } finally {
            Thread.interrupted();
        }

        assertEquals(0, queryCalls.get());
        assertEquals("operation-before-reader", fixture.loader.currentOperation());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void finalGateRunsBeforeNullQueryEarlyReturn(boolean searchPath) throws Exception {
        var armed = new AtomicBoolean();
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> armed.get());
        ReaderFixture fixture = new ReaderFixture(0, monitor);
        IJdbcReader reader = searchPath
                ? new NullQuerySearchPathJdbcReader(fixture.loader, armed)
                : new NullQueryJdbcReader(fixture.loader, armed);

        assertThrows(InterruptedException.class, reader::read);

        verify(fixture.statement, never()).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void blockedResultNextClosesResultThenWaitsForDrainBeforeStatementClose(boolean searchPath)
            throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        SQLException nextFailure = new SensitiveSQLException();
        var nextEntered = new CountDownLatch(1);
        var releaseNext = new CountDownLatch(1);
        var cancelEntered = new CountDownLatch(1);
        var releaseCancel = new CountDownLatch(1);
        var resultClosed = new CountDownLatch(1);
        var statementCloses = new AtomicInteger();
        doAnswer(invocation -> {
            nextEntered.countDown();
            awaitUninterruptibly(releaseNext);
            throw nextFailure;
        }).when(fixture.result).next();
        doAnswer(invocation -> {
            cancelEntered.countDown();
            releaseNext.countDown();
            awaitUninterruptibly(releaseCancel);
            return null;
        }).when(fixture.statement).cancel();
        doAnswer(invocation -> {
            resultClosed.countDown();
            return null;
        }).when(fixture.result).close();
        doAnswer(invocation -> {
            statementCloses.incrementAndGet();
            return null;
        }).when(fixture.statement).close();
        IJdbcReader reader = createReader(fixture, searchPath);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> owner = executor.submit(() -> {
            reader.read();
            return null;
        });
        Future<?> cancellation = null;

        try {
            assertTrue(nextEntered.await(5, TimeUnit.SECONDS));
            cancellation = executor.submit(() -> {
                fixture.loader.cancel();
                return null;
            });
            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS));
            assertTrue(resultClosed.await(1, TimeUnit.SECONDS));
            assertEquals(0, statementCloses.get());
            assertFalse(owner.isDone());
        } finally {
            releaseCancel.countDown();
            releaseNext.countDown();
            if (cancellation != null) {
                cancellation.get(5, TimeUnit.SECONDS);
            }
            shutdown(executor);
        }

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> owner.get(5, TimeUnit.SECONDS));
        InterruptedException interrupted = (InterruptedException) failure.getCause();
        assertEquals(1, interrupted.getSuppressed().length);
        assertSame(nextFailure, interrupted.getSuppressed()[0]);
        assertEquals(1, statementCloses.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void uncheckedFailureRemainsPrimaryDuringConcurrentLoaderCancellation(boolean error)
            throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        Throwable primary = error
                ? new AssertionError("primary error")
                : new IllegalStateException("primary runtime");
        var nextEntered = new CountDownLatch(1);
        var releaseNext = new CountDownLatch(1);
        doAnswer(invocation -> {
            nextEntered.countDown();
            awaitUninterruptibly(releaseNext);
            throw primary;
        }).when(fixture.result).next();
        doAnswer(invocation -> {
            releaseNext.countDown();
            return null;
        }).when(fixture.statement).cancel();
        var reader = new TestJdbcReader(fixture.loader);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> owner = executor.submit(() -> {
            reader.read();
            return null;
        });
        Future<?> cancellation = null;

        try {
            assertTrue(nextEntered.await(5, TimeUnit.SECONDS));
            cancellation = executor.submit(() -> {
                fixture.loader.cancel();
                return null;
            });
            cancellation.get(5, TimeUnit.SECONDS);
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertSame(primary, thrown.getCause());
            assertEquals(0, primary.getSuppressed().length);
        } finally {
            releaseNext.countDown();
            owner.cancel(true);
            if (cancellation != null) {
                cancellation.cancel(true);
            }
            shutdown(executor);
        }
    }

    @Test
    void uncheckedErrorRemainsPrimaryWhenMonitorClassificationFails() throws Exception {
        Error primary = new AssertionError("primary error");
        RuntimeException monitorFailure = new IllegalStateException("monitor failure");
        var armed = new AtomicBoolean();
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> {
            if (armed.get()) {
                throw monitorFailure;
            }
            return false;
        });
        ReaderFixture fixture = new ReaderFixture(0, monitor);
        doAnswer(invocation -> {
            armed.set(true);
            throw primary;
        }).when(fixture.result).next();
        var reader = new TestJdbcReader(fixture.loader);

        Error thrown = assertThrows(Error.class, reader::read);

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(monitorFailure, thrown.getSuppressed()[0]);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void uncheckedPrimaryCollectsDistinctCancellationActionFailureWithoutWrapper(
            boolean sameFailure) throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        Error primary = new AssertionError("primary error");
        Throwable cancelFailure = sameFailure
                ? primary
                : new IllegalStateException("cancel failure");
        doThrow(cancelFailure).when(fixture.statement).cancel();
        fixture.loader.registerCatalogStatement(fixture.statement);
        fixture.loader.getMonitor().setCancelled(true);

        InterruptedException classification =
                fixture.loader.classifyCatalogReaderCancellation(primary);

        assertNull(classification);
        if (sameFailure) {
            assertEquals(0, primary.getSuppressed().length);
        } else {
            assertEquals(1, primary.getSuppressed().length);
            assertSame(cancelFailure, primary.getSuppressed()[0]);
        }
        verify(fixture.statement).cancel();
    }

    @Test
    void uncheckedPrimaryRetainsClosedFailureAsSuppressed() throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        Error primary = new AssertionError("primary error");
        fixture.loader.close();

        assertNull(fixture.loader.classifyCatalogReaderCancellation(primary));

        assertEquals(1, primary.getSuppressed().length);
        assertSame(IllegalStateException.class, primary.getSuppressed()[0].getClass());
    }

    @Test
    void uncheckedPrimaryPreservesThreadInterruptWithoutNeutralWrapper() throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        Error primary = new AssertionError("primary error");
        Thread.currentThread().interrupt();

        try {
            assertNull(fixture.loader.classifyCatalogReaderCancellation(primary));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, primary.getSuppressed().length);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void uncheckedPrimaryPreDrainsThreadCancellationBeforeFailingMonitor(boolean error)
            throws Exception {
        RuntimeException monitorFailure = new IllegalStateException("monitor failure");
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenThrow(monitorFailure);
        ReaderFixture fixture = new ReaderFixture(0, monitor);
        Connection activeConnection = mock(Connection.class);
        var connectionCloses = new AtomicInteger();
        doAnswer(invocation -> {
            connectionCloses.incrementAndGet();
            return null;
        }).when(activeConnection).close();
        fixture.loader.registerConnectionForTest(activeConnection);
        Throwable primary = error
                ? new AssertionError("primary error")
                : new IllegalArgumentException("primary runtime");
        Thread.currentThread().interrupt();

        try {
            Throwable thrown = null;
            try {
                AbstractJdbcReader.finishCatalogRead(fixture.loader, null, null, primary);
            } catch (Throwable ex) {
                thrown = ex;
            }
            assertSame(primary, thrown);
            assertEquals(1, connectionCloses.get());
            assertEquals(1, primary.getSuppressed().length);
            assertSame(monitorFailure, primary.getSuppressed()[0]);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void uncheckedPrimaryPostDrainsInterruptArrivingInsideFailingMonitor() throws Exception {
        RuntimeException monitorFailure = new IllegalStateException("monitor failure");
        var monitorEntered = new CountDownLatch(1);
        var releaseMonitor = new CountDownLatch(1);
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> {
            monitorEntered.countDown();
            awaitUninterruptibly(releaseMonitor);
            throw monitorFailure;
        });
        ReaderFixture fixture = new ReaderFixture(0, monitor);
        Connection activeConnection = mock(Connection.class);
        var connectionCloses = new AtomicInteger();
        doAnswer(invocation -> {
            connectionCloses.incrementAndGet();
            return null;
        }).when(activeConnection).close();
        fixture.loader.registerConnectionForTest(activeConnection);
        Error primary = new AssertionError("primary error");
        var ownerThread = new AtomicReference<Thread>();
        var interruptPreserved = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> owner = executor.submit(() -> {
            ownerThread.set(Thread.currentThread());
            try {
                AbstractJdbcReader.finishCatalogRead(fixture.loader, null, null, primary);
                return null;
            } catch (Throwable ex) {
                return ex;
            } finally {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
                Thread.interrupted();
            }
        });

        try {
            assertTrue(monitorEntered.await(5, TimeUnit.SECONDS));
            ownerThread.get().interrupt();
            releaseMonitor.countDown();
            assertSame(primary, owner.get(5, TimeUnit.SECONDS));
            assertEquals(1, connectionCloses.get());
            assertEquals(1, primary.getSuppressed().length);
            assertSame(monitorFailure, primary.getSuppressed()[0]);
            assertTrue(interruptPreserved.get());
        } finally {
            releaseMonitor.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void checkedPrimaryDrainsInterruptArrivingInsideFailingMonitor(boolean error)
            throws Exception {
        Throwable monitorFailure = error
                ? new AssertionError("monitor error")
                : new IllegalStateException("monitor runtime");
        var monitorEntered = new CountDownLatch(1);
        var releaseMonitor = new CountDownLatch(1);
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> {
            monitorEntered.countDown();
            awaitUninterruptibly(releaseMonitor);
            throw monitorFailure;
        });
        ReaderFixture fixture = new ReaderFixture(0, monitor);
        Connection activeConnection = mock(Connection.class);
        var connectionCloses = new AtomicInteger();
        doAnswer(invocation -> {
            connectionCloses.incrementAndGet();
            return null;
        }).when(activeConnection).close();
        fixture.loader.registerConnectionForTest(activeConnection);
        SQLException raw = new SensitiveSQLException();
        var ownerThread = new AtomicReference<Thread>();
        var interruptPreserved = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> owner = executor.submit(() -> {
            ownerThread.set(Thread.currentThread());
            try {
                return fixture.loader.classifyCatalogReaderCancellation(raw);
            } catch (Throwable ex) {
                return ex;
            } finally {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
                Thread.interrupted();
            }
        });

        try {
            assertTrue(monitorEntered.await(5, TimeUnit.SECONDS));
            ownerThread.get().interrupt();
            releaseMonitor.countDown();
            assertSame(monitorFailure, owner.get(5, TimeUnit.SECONDS));
            assertEquals(1, connectionCloses.get());
            assertEquals(1, monitorFailure.getSuppressed().length);
            assertSame(raw, monitorFailure.getSuppressed()[0]);
            assertTrue(interruptPreserved.get());
        } finally {
            releaseMonitor.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void readerGateDrainsInterruptArrivingInsideFailingMonitor(boolean finalGate)
            throws Exception {
        Throwable monitorFailure = finalGate
                ? new AssertionError("final monitor error")
                : new IllegalStateException("entry monitor runtime");
        var armed = new AtomicBoolean(!finalGate);
        var monitorEntered = new CountDownLatch(1);
        var releaseMonitor = new CountDownLatch(1);
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> {
            if (!armed.get()) {
                return false;
            }
            monitorEntered.countDown();
            awaitUninterruptibly(releaseMonitor);
            throw monitorFailure;
        });
        ReaderFixture fixture = new ReaderFixture(0, monitor);
        if (finalGate) {
            doAnswer(invocation -> {
                armed.set(true);
                return null;
            }).when(fixture.statement).close();
        }
        Connection activeConnection = mock(Connection.class);
        var connectionCloses = new AtomicInteger();
        doAnswer(invocation -> {
            connectionCloses.incrementAndGet();
            return null;
        }).when(activeConnection).close();
        fixture.loader.registerConnectionForTest(activeConnection);
        var reader = new TestJdbcReader(fixture.loader);
        var ownerThread = new AtomicReference<Thread>();
        var interruptPreserved = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Throwable> owner = executor.submit(() -> {
            ownerThread.set(Thread.currentThread());
            try {
                reader.read();
                return null;
            } catch (Throwable ex) {
                return ex;
            } finally {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
                Thread.interrupted();
            }
        });

        try {
            assertTrue(monitorEntered.await(5, TimeUnit.SECONDS));
            ownerThread.get().interrupt();
            releaseMonitor.countDown();
            assertSame(monitorFailure, owner.get(5, TimeUnit.SECONDS));
            assertEquals(1, connectionCloses.get());
            assertEquals(0, monitorFailure.getSuppressed().length);
            assertTrue(interruptPreserved.get());
            if (finalGate) {
                verify(fixture.statement).close();
            } else {
                verify(fixture.statement, never()).close();
            }
        } finally {
            releaseMonitor.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @Test
    void statementIsRegisteredBeforeSetQueryParams() throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        var paramsEntered = new CountDownLatch(1);
        var releaseParams = new CountDownLatch(1);
        var statementCancels = new AtomicInteger();
        doAnswer(invocation -> {
            statementCancels.incrementAndGet();
            releaseParams.countDown();
            return null;
        }).when(fixture.statement).cancel();
        var reader = new BlockingParamsJdbcReader(fixture.loader, paramsEntered, releaseParams);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> owner = executor.submit(() -> {
            reader.read();
            return null;
        });

        try {
            assertTrue(paramsEntered.await(5, TimeUnit.SECONDS));
            fixture.loader.cancel();
            assertEquals(1, statementCancels.get());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertSame(InterruptedException.class, failure.getCause().getClass());
        } finally {
            releaseParams.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void finalCancellationGateRunsAfterPhysicalStatementClose(boolean loaderCancellation)
            throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        var closeEntered = new CountDownLatch(1);
        var releaseClose = new CountDownLatch(1);
        var statementCancels = new AtomicInteger();
        doAnswer(invocation -> {
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
            return null;
        }).when(fixture.statement).close();
        doAnswer(invocation -> {
            statementCancels.incrementAndGet();
            return null;
        }).when(fixture.statement).cancel();
        var reader = new TestJdbcReader(fixture.loader);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> owner = executor.submit(() -> {
            reader.read();
            return null;
        });

        try {
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
            if (loaderCancellation) {
                fixture.loader.cancel();
            } else {
                fixture.loader.getMonitor().setCancelled(true);
            }
            assertEquals(0, statementCancels.get());
            releaseClose.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            assertSame(InterruptedException.class, failure.getCause().getClass());
        } finally {
            releaseClose.countDown();
            owner.cancel(true);
            shutdown(executor);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void finalGateMonitorFailureIsNotReclassifiedThroughASecondMonitorCall(boolean searchPath)
            throws Exception {
        RuntimeException primary = new IllegalStateException("first monitor failure");
        RuntimeException replacement = new IllegalArgumentException("second monitor failure");
        var armed = new AtomicBoolean();
        var armedCalls = new AtomicInteger();
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> {
            if (!armed.get()) {
                return false;
            }
            if (armedCalls.getAndIncrement() == 0) {
                throw primary;
            }
            throw replacement;
        });
        ReaderFixture fixture = new ReaderFixture(0, monitor);
        doAnswer(invocation -> {
            armed.set(true);
            return null;
        }).when(fixture.statement).close();
        IJdbcReader reader = createReader(fixture, searchPath);

        RuntimeException thrown = assertThrows(RuntimeException.class, reader::read);

        assertSame(primary, thrown);
        assertEquals(1, armedCalls.get());
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void monitorFailureDuringClassificationKeepsIdentityWithoutInspectingJdbcFailure() throws Exception {
        RuntimeException monitorFailure = new IllegalStateException("monitor failure");
        var armed = new AtomicBoolean();
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> {
            if (armed.get()) {
                throw monitorFailure;
            }
            return false;
        });
        ReaderFixture fixture = new ReaderFixture(0, monitor);
        SQLException raw = new SensitiveSQLException();
        doAnswer(invocation -> {
            armed.set(true);
            throw raw;
        }).when(fixture.result).next();
        var reader = new TestJdbcReader(fixture.loader);

        Throwable thrown = null;
        try {
            reader.read();
        } catch (Throwable ex) {
            thrown = ex;
        }

        assertTrue(thrown == monitorFailure);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(raw, thrown.getSuppressed()[0]);
    }

    @Test
    void monitorFailureClassificationAcceptsSuccessfulNullPath() throws Exception {
        RuntimeException monitorFailure = new IllegalStateException("monitor failure");
        var armed = new AtomicBoolean();
        IMonitor monitor = mock(IMonitor.class);
        when(monitor.isCancelled()).thenAnswer(invocation -> {
            if (armed.get()) {
                throw monitorFailure;
            }
            return false;
        });
        ReaderFixture fixture = new ReaderFixture(0, monitor);
        armed.set(true);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.loader.classifyCatalogReaderCancellation(null));

        assertSame(monitorFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void closedLoaderClassificationRejectsAndRetainsRawFailure() throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        SQLException raw = new SQLException("raw");
        fixture.loader.close();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> fixture.loader.classifyCatalogReaderCancellation(raw));

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(raw, thrown.getSuppressed()[0]);
    }

    @Test
    void closedLoaderClassificationAcceptsSuccessfulNullPath() throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        fixture.loader.close();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> fixture.loader.classifyCatalogReaderCancellation(null));

        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void ordinarySqlFailurePreservesIdentityAndCleanupSuppressionOrder() throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        SQLException primary = new SQLException("primary");
        SQLException resultClose = new SQLException("result close");
        SQLException statementClose = new SQLException("statement close");
        when(fixture.result.next()).thenThrow(primary);
        doThrow(resultClose).when(fixture.result).close();
        doThrow(statementClose).when(fixture.statement).close();
        var reader = new TestJdbcReader(fixture.loader);

        SQLException thrown = assertThrows(SQLException.class, reader::read);

        assertSame(primary, thrown);
        assertIterableEquals(List.of(resultClose, statementClose), List.of(thrown.getSuppressed()));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cleanupDoesNotSelfSuppressWhenBodyAndClosesThrowSameFailure(boolean searchPath)
            throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        SQLException primary = new SensitiveSQLException();
        when(fixture.result.next()).thenThrow(primary);
        doThrow(primary).when(fixture.result).close();
        doThrow(primary).when(fixture.statement).close();
        IJdbcReader reader = createReader(fixture, searchPath);

        SQLException thrown = assertThrows(SQLException.class, reader::read);

        assertSame(primary, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        verify(fixture.result).close();
        verify(fixture.statement).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void cleanupSuppressesRepeatedSecondaryFailureOnlyOnce(boolean searchPath) throws Exception {
        ReaderFixture fixture = new ReaderFixture(0);
        SQLException primary = new SensitiveSQLException();
        SQLException repeatedSecondary = new SensitiveSQLException();
        when(fixture.result.next()).thenThrow(primary);
        doThrow(repeatedSecondary).when(fixture.result).close();
        doThrow(repeatedSecondary).when(fixture.statement).close();
        IJdbcReader reader = createReader(fixture, searchPath);

        SQLException thrown = assertThrows(SQLException.class, reader::read);

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(repeatedSecondary, thrown.getSuppressed()[0]);
        verify(fixture.result).close();
        verify(fixture.statement).close();
    }

    private static final class ReaderFixture {

        private final ResultSet result = mock(ResultSet.class);
        private final PreparedStatement statement = mock(PreparedStatement.class);
        private final TestJdbcLoader loader;

        private ReaderFixture(int rowCount) throws Exception {
            this(rowCount, null);
        }

        private ReaderFixture(int rowCount, IMonitor monitor) throws Exception {
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(result);
            Boolean[] rows = new Boolean[rowCount + 1];
            for (int i = 0; i < rowCount; i++) {
                rows[i] = true;
            }
            rows[rowCount] = false;
            when(result.next()).thenReturn(rows[0], java.util.Arrays.copyOfRange(rows, 1, rows.length));
            CoreSettings settings = new CoreSettings();
            if (monitor != null) {
                settings.setMonitor(monitor);
            }
            loader = new TestJdbcLoader(connection, settings);
        }
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

    private static IJdbcReader createReader(ReaderFixture fixture, boolean searchPath) throws SQLException {
        if (!searchPath) {
            return new TestJdbcReader(fixture.loader);
        }
        ISchema schema = mock(ISchema.class);
        fixture.loader.putSchema(1, schema);
        when(fixture.result.getObject("schema_id")).thenReturn(1);
        return new TestSearchPathJdbcReader(fixture.loader, schema);
    }

    private static Stream<Arguments> entryGateCases() {
        return Stream.of(EntryBlocker.values())
                .flatMap(blocker -> Stream.of(false, true)
                        .map(searchPath -> Arguments.of(blocker, searchPath)));
    }

    private static final class TestJdbcLoader extends AbstractJdbcLoader<PgDatabase> {

        private final List<String> events = new ArrayList<>();

        private TestJdbcLoader(Connection connection, CoreSettings settings) {
            super(mock(IJdbcConnector.class), settings);
            this.connection = connection;
        }

        private void enqueueCompleted(String value) {
            var future = new FutureTask<>(() -> value);
            future.run();
            antlrTasks.add(new AntlrTask<>(future, events::add));
        }

        private void enqueueFailure(IOException failure) {
            var future = new FutureTask<String>(() -> {
                throw failure;
            });
            future.run();
            antlrTasks.add(new AntlrTask<>(future, ignored -> { }));
        }

        private String currentOperation() {
            return currentOperation;
        }

        private void registerConnectionForTest(Connection activeConnection)
                throws IOException, InterruptedException {
            registerActiveConnection(activeConnection);
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

    private static final class TestJdbcReader extends AbstractJdbcReader<TestJdbcLoader> {

        private int row;

        private TestJdbcReader(TestJdbcLoader loader) {
            super(loader);
        }

        @Override
        protected void fillQueryBuilder(QueryBuilder builder) {
            builder.column("value").from("test_rows");
        }

        @Override
        protected void processResult(ResultSet result) {
            int current = ++row;
            loader.events.add("process" + current);
        }
    }

    private static final class FailingJdbcReader extends AbstractJdbcReader<TestJdbcLoader> {

        private final IOException failure;

        private FailingJdbcReader(TestJdbcLoader loader, IOException failure) {
            super(loader);
            this.failure = failure;
        }

        @Override
        protected void fillQueryBuilder(QueryBuilder builder) {
            builder.column("value").from("test_rows");
        }

        @Override
        protected void processResult(ResultSet result) {
            loader.enqueueFailure(failure);
        }
    }

    private static final class BlockingParamsJdbcReader extends AbstractJdbcReader<TestJdbcLoader> {

        private final CountDownLatch paramsEntered;
        private final CountDownLatch releaseParams;

        private BlockingParamsJdbcReader(TestJdbcLoader loader, CountDownLatch paramsEntered,
                                         CountDownLatch releaseParams) {
            super(loader);
            this.paramsEntered = paramsEntered;
            this.releaseParams = releaseParams;
        }

        @Override
        protected void fillQueryBuilder(QueryBuilder builder) {
            builder.column("value").from("test_rows");
        }

        @Override
        protected void setQueryParams(PreparedStatement statement) {
            paramsEntered.countDown();
            awaitUninterruptibly(releaseParams);
        }

        @Override
        protected void processResult(ResultSet result) {
            // no rows expected
        }
    }

    private static final class EntryTrackingJdbcReader extends AbstractJdbcReader<TestJdbcLoader> {

        private final AtomicInteger queryCalls;

        private EntryTrackingJdbcReader(TestJdbcLoader loader, AtomicInteger queryCalls) {
            super(loader);
            this.queryCalls = queryCalls;
        }

        @Override
        protected QueryBuilder makeQuery() {
            queryCalls.incrementAndGet();
            return super.makeQuery();
        }

        @Override
        protected void fillQueryBuilder(QueryBuilder builder) {
            builder.column("value").from("test_rows");
        }

        @Override
        protected void processResult(ResultSet result) {
            // entry gate prevents query execution
        }
    }

    private static final class NullQueryJdbcReader extends AbstractJdbcReader<TestJdbcLoader> {

        private final AtomicBoolean armed;

        private NullQueryJdbcReader(TestJdbcLoader loader, AtomicBoolean armed) {
            super(loader);
            this.armed = armed;
        }

        @Override
        protected QueryBuilder makeQuery() {
            armed.set(true);
            return null;
        }

        @Override
        protected void fillQueryBuilder(QueryBuilder builder) {
            // makeQuery returns early
        }

        @Override
        protected void processResult(ResultSet result) {
            // makeQuery returns early
        }
    }

    private static final class EntryTrackingSearchPathJdbcReader
            extends AbstractSearchPathJdbcReader<TestJdbcLoader> {

        private final AtomicInteger queryCalls;

        private EntryTrackingSearchPathJdbcReader(TestJdbcLoader loader, AtomicInteger queryCalls) {
            super(loader);
            this.queryCalls = queryCalls;
        }

        @Override
        protected QueryBuilder makeQuery() {
            queryCalls.incrementAndGet();
            return super.makeQuery();
        }

        @Override
        protected void fillQueryBuilder(QueryBuilder builder) {
            builder.column("value").from("test_rows");
        }

        @Override
        protected String getSchemaColumn() {
            return "test_rows.schema_id";
        }

        @Override
        protected void processResult(ResultSet result, ISchema schema) {
            // entry gate prevents query execution
        }
    }

    private static final class NullQuerySearchPathJdbcReader
            extends AbstractSearchPathJdbcReader<TestJdbcLoader> {

        private final AtomicBoolean armed;

        private NullQuerySearchPathJdbcReader(TestJdbcLoader loader, AtomicBoolean armed) {
            super(loader);
            this.armed = armed;
        }

        @Override
        protected QueryBuilder makeQuery() {
            armed.set(true);
            return super.makeQuery();
        }

        @Override
        protected void fillQueryBuilder(QueryBuilder builder) {
            // empty schemas make makeQuery return early before filling
        }

        @Override
        protected String getSchemaColumn() {
            return "test_rows.schema_id";
        }

        @Override
        protected void processResult(ResultSet result, ISchema schema) {
            // makeQuery returns early
        }
    }

    private static final class TestSearchPathJdbcReader
            extends AbstractSearchPathJdbcReader<TestJdbcLoader> {

        private final ISchema expectedSchema;
        private int row;

        private TestSearchPathJdbcReader(TestJdbcLoader loader, ISchema expectedSchema) {
            super(loader);
            this.expectedSchema = expectedSchema;
        }

        @Override
        protected void fillQueryBuilder(QueryBuilder builder) {
            builder.column("value").from("test_rows");
        }

        @Override
        protected String getSchemaColumn() {
            return "test_rows.schema_id";
        }

        @Override
        protected void processResult(ResultSet result, ISchema schema)
                throws ConcurrentModificationException, SQLException, XmlReaderException {
            assertSame(expectedSchema, schema);
            int current = ++row;
            loader.events.add("process" + current);
        }
    }

    private static final class SensitiveSQLException extends SQLException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new AssertionError("JDBC message must not be inspected");
        }

        @Override
        public String getLocalizedMessage() {
            throw new AssertionError("JDBC localized message must not be inspected");
        }

        @Override
        public String toString() {
            throw new AssertionError("JDBC failure must not be stringified");
        }
    }

    private enum EntryBlocker {
        CANCELLED,
        CLOSED,
        MONITOR,
        THREAD
    }
}
