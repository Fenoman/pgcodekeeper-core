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
package org.pgcodekeeper.core.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.Utils;

/**
 * Tests for parallel and sequential database loading behavior in {@link Utils#loadDatabases}.
 *
 * <p>Verifies the following scenarios:
 * <ul>
 *   <li>IOException propagation when one of the loaders fails during parallel load</li>
 *   <li>Monitor cancellation on loading error</li>
 * </ul>
 *
 * <p>Uses Mockito to simulate {@link ILoader} and {@link IMonitor} behavior.
 */
class ParallelLoadTest {

    private static final long WAIT_SECONDS = 5;
    private static final String EXISTING_ERROR = "existing error";

    private ILoader oldDbLoader;
    private ILoader newDbLoader;
    private IMonitor subMonitor;
    private IDatabase oldDb;
    private IDatabase newDb;

    @BeforeEach
    void initSettings() {
        oldDbLoader = mock(ILoader.class);
        newDbLoader = mock(ILoader.class);
        subMonitor = mock(IMonitor.class);
        oldDb = mock(IDatabase.class);
        newDb = mock(IDatabase.class);
    }

    @Test
    void testOneThreadWithException() throws IOException, InterruptedException {
        IOException ioException = new IOException("old DB read error");
        when(oldDbLoader.loadAndAnalyze()).thenThrow(ioException);
        when(newDbLoader.loadAndAnalyze()).thenReturn(newDb);

        var settings = new CoreSettings();
        settings.setParallelLoad(true);

        IOException thrown = assertThrows(IOException.class, () ->
                Utils.loadDatabases(oldDbLoader, newDbLoader, settings, subMonitor));

        assertEquals(ioException, thrown);
        verify(subMonitor).setCancelled(true);
    }

    @Test
    void testAnotherThreadWithException() throws IOException, InterruptedException {
        when(oldDbLoader.loadAndAnalyze()).thenReturn(oldDb);
        IOException ioException = new IOException("new DB read error");
        when(newDbLoader.loadAndAnalyze()).thenThrow(ioException);

        var settings = new CoreSettings();
        settings.setParallelLoad(true);

        IOException thrown = assertThrows(IOException.class,
                () -> Utils.loadDatabases(oldDbLoader, newDbLoader, settings, subMonitor));

        assertEquals(ioException, thrown);
        verify(subMonitor).setCancelled(true);
    }

    @Test
    void directLoaderPathRejectsFactoryRequiredSettingsBeforePreLoad() {
        var settings = new CoreSettings();
        settings.setPgRoutineBodyHashFirst(true);
        settings.addError(EXISTING_ERROR);
        settings.setVersion(PgSupportedVersion.VERSION_14);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Utils.loadDatabases(
                        oldDbLoader, newDbLoader, settings, subMonitor));

        assertEquals(Messages.Utils_comparison_loader_factories_required,
                failure.getMessage());
        assertEquals(List.of(EXISTING_ERROR), settings.getErrors());
        assertSame(PgSupportedVersion.VERSION_14, settings.getVersion());
        verifyNoInteractions(oldDbLoader, newDbLoader);
        verifyNoInteractions(subMonitor);
    }

    @Test
    void directLoaderParallelPathRetainsOrdinaryLifecycle()
            throws IOException, InterruptedException {
        var bothStarted = new CountDownLatch(2);
        var oldWorker = new AtomicReference<Thread>();
        var newWorker = new AtomicReference<Thread>();
        when(oldDbLoader.loadAndAnalyze()).thenAnswer(invocation ->
                loadAfterBothStarted(oldDb, oldWorker, bothStarted));
        when(newDbLoader.loadAndAnalyze()).thenAnswer(invocation ->
                loadAfterBothStarted(newDb, newWorker, bothStarted));
        var settings = new CoreSettings();
        settings.setParallelLoad(true);

        var databases = Utils.loadDatabases(
                oldDbLoader, newDbLoader, settings, subMonitor);

        assertSame(oldDb, databases.getFirst());
        assertSame(newDb, databases.getSecond());
        assertEquals(0, bothStarted.getCount());
        assertNotNull(oldWorker.get());
        assertNotNull(newWorker.get());
        assertNotSame(oldWorker.get(), newWorker.get());
        var preloadOrder = inOrder(oldDbLoader, newDbLoader);
        preloadOrder.verify(oldDbLoader).preLoad();
        preloadOrder.verify(newDbLoader).preLoad();
        verify(oldDbLoader).loadAndAnalyze();
        verify(newDbLoader).loadAndAnalyze();
        var monitorOrder = inOrder(subMonitor);
        monitorOrder.verify(subMonitor).setTaskName(Messages.Utils_loading_databases);
        monitorOrder.verify(subMonitor).worked(60);
        verify(subMonitor, never()).setCancelled(true);
        verify(oldDbLoader, never()).load();
        verify(newDbLoader, never()).load();
        verify(oldDbLoader, never()).cancel();
        verify(newDbLoader, never()).cancel();
        verify(oldDbLoader, never()).close();
        verify(newDbLoader, never()).close();
    }

    private static IDatabase loadAfterBothStarted(IDatabase database,
            AtomicReference<Thread> worker, CountDownLatch bothStarted)
            throws InterruptedException {
        worker.set(Thread.currentThread());
        bothStarted.countDown();
        if (!bothStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("both direct loaders did not run concurrently");
        }
        return database;
    }
}
