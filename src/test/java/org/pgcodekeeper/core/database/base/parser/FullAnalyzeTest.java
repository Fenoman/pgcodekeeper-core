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
package org.pgcodekeeper.core.database.base.parser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.antlr.v4.runtime.ParserRuleContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ICompositeType;
import org.pgcodekeeper.core.database.api.schema.IConstraintPk;
import org.pgcodekeeper.core.database.api.schema.IFunction;
import org.pgcodekeeper.core.database.api.schema.IOperator;
import org.pgcodekeeper.core.database.api.schema.IRelation;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.base.loader.AbstractLoader;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.base.parser.launcher.AbstractAnalysisLauncher;
import org.pgcodekeeper.core.database.base.schema.meta.MetaContainer;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;

@Isolated("mutates parser pending-limit system properties")
class FullAnalyzeTest {

    private static final long HANDOFF_TIMEOUT_SECONDS = 5;

    private String originalMaxPending;
    private String originalMaxPendingBytes;

    @BeforeEach
    void configureWeightedPipeline() {
        originalMaxPending = System.getProperty(Consts.MAX_PENDING_TASKS);
        originalMaxPendingBytes = System.getProperty(Consts.MAX_PENDING_BYTES);
        System.setProperty(Consts.MAX_PENDING_TASKS, "1");
        System.setProperty(Consts.MAX_PENDING_BYTES, "1");
    }

    @AfterEach
    void restoreProperties() {
        restoreProperty(Consts.MAX_PENDING_TASKS, originalMaxPending);
        restoreProperty(Consts.MAX_PENDING_BYTES, originalMaxPendingBytes);
    }

    @Test
    void forwardsLauncherWeightBeforeLaunchingAnalysis() {
        var db = new PgDatabase();
        var launched = new AtomicBoolean();
        db.addAnalysisLauncher(new IAnalysisLauncher() {

            @Override
            public long getEstimatedParseBytes() {
                return -1;
            }

            @Override
            public IStatement getStmt() {
                return null;
            }

            @Override
            public void updateStmt(IDatabase database) {
                // nothing to update
            }

            @Override
            public Set<ObjectReference> launchAnalyze(List<Object> errors, IMetaContainer meta) {
                launched.set(true);
                return Set.of();
            }

            @Override
            public List<ObjectLocation> getReferences() {
                return List.of();
            }

            @Override
            public String getSchemaName() {
                return null;
            }
        });

        assertThrows(IllegalArgumentException.class,
                () -> FullAnalyze.fullAnalyze(db, new MetaContainer(), new ArrayList<>()));

        assertFalse(launched.get());
        assertTrue(db.getAnalysisLaunchers().isEmpty());
    }

    @Test
    void metadataBuildFailureReleasesLaunchersAndPostgresParserCache() {
        RuntimeException primary = new RuntimeException("controlled metadata failure");
        AbstractStatement failingStatement = mock(AbstractStatement.class);
        when(failingStatement.getStatementType()).thenThrow(primary);
        var db = new PgDatabase() {
            @Override
            public void fillDescendantsList(List<Collection<? extends AbstractStatement>> descendants) {
                descendants.add(List.of(failingStatement));
            }
        };
        db.addAnalysisLauncher(mock(IAnalysisLauncher.class));
        var first = PgParserUtils.createBoundedSqlParser("SELECT 1", "first", new ArrayList<>(), 0, 0, 0);
        var firstDfas = first.getInterpreter().decisionToDFA;

        try {
            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> FullAnalyze.fullAnalyze(db, new ArrayList<>(), PgSupportedVersion.VERSION_15));

            assertSame(primary, thrown);
            assertTrue(db.getAnalysisLaunchers().isEmpty());
            var second = PgParserUtils.createBoundedSqlParser(
                    "SELECT 2", "second", new ArrayList<>(), 0, 0, 0);
            assertNotSame(firstDfas, second.getInterpreter().decisionToDFA);
        } finally {
            PgParserUtils.releaseBodyParserCache();
        }
    }

    @Test
    void cleanupFailureIsSuppressedOntoAsyncAnalysisFailure() {
        RuntimeException primary = new RuntimeException("controlled analysis failure");
        IllegalStateException cleanup = new IllegalStateException("controlled cleanup failure");
        var db = new PgDatabase() {
            private int clears;

            @Override
            public void clearAnalysisLaunchers() {
                if (++clears == 2) {
                    throw cleanup;
                }
                super.clearAnalysisLaunchers();
            }
        };
        db.addAnalysisLauncher(failingLauncher(primary));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> FullAnalyze.fullAnalyze(db, new MetaContainer(), new ArrayList<>()));

        assertSame(primary, thrown.getCause().getCause());
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanup, thrown.getSuppressed()[0]);
    }

    @Test
    void taskQueueConstructionFailureReleasesDeferredState() {
        System.setProperty(Consts.MAX_PENDING_BYTES, "-1");
        var db = new PgDatabase();
        db.addAnalysisLauncher(mock(IAnalysisLauncher.class));
        var first = PgParserUtils.createBoundedSqlParser("SELECT 1", "first", new ArrayList<>(), 0, 0, 0);
        var firstDfas = first.getInterpreter().decisionToDFA;

        try {
            assertThrows(IllegalArgumentException.class,
                    () -> FullAnalyze.fullAnalyze(db, new MetaContainer(), new ArrayList<>()));

            assertTrue(db.getAnalysisLaunchers().isEmpty());
            var second = PgParserUtils.createBoundedSqlParser(
                    "SELECT 2", "second", new ArrayList<>(), 0, 0, 0);
            assertNotSame(firstDfas, second.getInterpreter().decisionToDFA);
        } finally {
            PgParserUtils.releaseBodyParserCache();
        }
    }

    @Test
    void cancellationAbortsActualWorkerAndReleasesDeferredStateBeforeReturn()
            throws Exception {
        var workerEntered = new CountDownLatch(1);
        var ownerAtSecondDescriptor = new CountDownLatch(1);
        var releaseSecondDescriptor = new CountDownLatch(1);
        var cancellationObserved = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        var workerExited = new AtomicBoolean();
        var ownerDone = new CountDownLatch(1);
        var monitor = new NullMonitor();
        var db = new PgDatabase();
        db.addAnalysisLauncher(blockingLauncher(
                monitor, workerEntered, cancellationObserved, releaseWorker, workerExited));
        db.addAnalysisLauncher(descriptorBarrierLauncher(
                ownerAtSecondDescriptor, releaseSecondDescriptor));
        var errors = new ArrayList<>();
        var firstParser = PgParserUtils.createBoundedSqlParser(
                "SELECT 1", "first", new ArrayList<>(), 0, 0, 0);
        var firstDfas = firstParser.getInterpreter().decisionToDFA;
        var executor = Executors.newSingleThreadExecutor();
        var owner = executor.submit(() -> {
            try {
                FullAnalyze.fullAnalyze(db, new MetaContainer(), errors, monitor);
                return (Void) null;
            } finally {
                ownerDone.countDown();
            }
        });

        try {
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
            assertTrue(ownerAtSecondDescriptor.await(5, TimeUnit.SECONDS));

            monitor.setCancelled(true);
            releaseSecondDescriptor.countDown();

            assertTrue(cancellationObserved.await(5, TimeUnit.SECONDS));
            assertFalse(ownerDone.await(200, TimeUnit.MILLISECONDS),
                    "FullAnalyze returned before the actual parser worker exited");
            releaseWorker.countDown();

            ExecutionException ownerFailure = assertThrows(ExecutionException.class,
                    () -> owner.get(5, TimeUnit.SECONDS));
            InterruptedException thrown = assertInstanceOf(
                    InterruptedException.class, ownerFailure.getCause());

            assertAll(
                    () -> assertNull(thrown.getMessage()),
                    () -> assertNull(thrown.getCause()),
                    () -> assertEquals(0, thrown.getSuppressed().length),
                    () -> assertTrue(workerExited.get(),
                            "FullAnalyze returned before the actual parser worker exited"),
                    () -> assertTrue(db.getAnalysisLaunchers().isEmpty()),
                    () -> assertTrue(errors.isEmpty(), errors::toString));
            var secondParser = PgParserUtils.createBoundedSqlParser(
                    "SELECT 2", "second", new ArrayList<>(), 0, 0, 0);
            assertNotSame(firstDfas, secondParser.getInterpreter().decisionToDFA,
                    "cancelled analysis retained the shared function-body parser generation");
        } finally {
            releaseSecondDescriptor.countDown();
            releaseWorker.countDown();
            owner.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            PgParserUtils.releaseBodyParserCache();
        }
    }

    @Test
    void unclassifiedTaskCancellationPreservesRuntimeIdentityWithoutDiagnostic() {
        var cancellation = new MonitorCancelledRuntimeException();
        var db = new PgDatabase();
        db.addAnalysisLauncher(failingLauncher(cancellation));
        var errors = new ArrayList<>();

        MonitorCancelledRuntimeException thrown = assertThrows(
                MonitorCancelledRuntimeException.class,
                () -> FullAnalyze.fullAnalyze(
                        db, new MetaContainer(), errors, new NullMonitor()));

        assertAll(
                () -> assertSame(cancellation, thrown),
                () -> assertEquals(0, thrown.getSuppressed().length),
                () -> assertTrue(db.getAnalysisLaunchers().isEmpty()),
                () -> assertTrue(errors.isEmpty(), errors::toString));
    }

    @Test
    void activeTaskCancellationMapsToNeutralInterruptedAndCopiesSuppressedCleanup() {
        var monitor = new NullMonitor();
        var cancellation = new MonitorCancelledRuntimeException();
        var cleanupFailure = new IllegalStateException("controlled cleanup failure");
        cancellation.addSuppressed(cleanupFailure);
        var ownerReachedTaskDrain = new CountDownLatch(1);
        var workerObservedHandoff = new AtomicBoolean();
        var db = new PgDatabase() {

            @Override
            public void clearAnalysisLaunchers() {
                super.clearAnalysisLaunchers();
                // the owner clears the launchers immediately before it drains
                // the task queue and performs no further monitor check until
                // then, so releasing the worker here keeps the cancellation on
                // the drain path this test covers
                ownerReachedTaskDrain.countDown();
            }
        };
        db.addAnalysisLauncher(new IAnalysisLauncher() {
            @Override
            public IStatement getStmt() {
                return new PgFunction("cancelled_function");
            }

            @Override
            public void updateStmt(IDatabase database) {
                // no-op test launcher
            }

            @Override
            public Set<ObjectReference> launchAnalyze(
                    List<Object> errors, IMetaContainer meta) {
                workerObservedHandoff.set(awaitHandoff(ownerReachedTaskDrain));
                monitor.setCancelled(true);
                throw cancellation;
            }

            @Override
            public List<ObjectLocation> getReferences() {
                return List.of();
            }

            @Override
            public String getSchemaName() {
                return null;
            }
        });
        var errors = new ArrayList<>();

        InterruptedException thrown = assertThrows(InterruptedException.class,
                () -> FullAnalyze.fullAnalyze(
                        db, new MetaContainer(), errors, monitor));

        assertAll(
                () -> assertTrue(workerObservedHandoff.get(),
                        "owner never handed the failure over to the task drain"),
                () -> assertNull(thrown.getMessage()),
                () -> assertNull(thrown.getCause()),
                () -> assertArrayEquals(
                        new Throwable[] { cleanupFailure }, thrown.getSuppressed()),
                () -> assertTrue(db.getAnalysisLaunchers().isEmpty()),
                () -> assertTrue(errors.isEmpty(), errors::toString));
    }

    @Test
    void cancellationStopsBulkErrorPublicationBeforeWholeBatchIsRetained() {
        int total = 4096;
        var errors = new ArrayList<>();
        var db = new PgDatabase();
        db.addAnalysisLauncher(publicationLauncher(
                new PgFunction("error_publication"),
                launcherErrors -> {
                    for (int i = 0; i < total; i++) {
                        launcherErrors.add("error_" + i);
                    }
                }, Set.of(), List.of()));
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return errors.size() >= 256;
            }
        };

        assertThrows(InterruptedException.class,
                () -> FullAnalyze.fullAnalyze(
                        db, new MetaContainer(), errors, monitor));

        assertAll(
                () -> assertTrue(errors.size() >= 256),
                () -> assertTrue(errors.size() < total,
                        "all launcher diagnostics were retained before cancellation"));
    }

    @Test
    void launcherWorkerMonitorFailurePreservesIdentityAtPublicBoundary() {
        RuntimeException monitorFailure = new IllegalStateException(
                "controlled launcher worker monitor failure");
        Thread ownerThread = Thread.currentThread();
        var workerChecks = new AtomicInteger();
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                if (Thread.currentThread() != ownerThread
                        && workerChecks.incrementAndGet() == 2) {
                    throw monitorFailure;
                }
                return false;
            }
        };
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        var function = new PgFunction("monitor_failure");
        db.addChild(schema);
        schema.addChild(function);
        db.addAnalysisLauncher(new AbstractAnalysisLauncher(
                function, new ParserRuleContext(), "test.sql") {
            @Override
            protected Set<ObjectLocation> analyze(
                    ParserRuleContext ignored, IMetaContainer meta) {
                return Set.of();
            }
        });
        var errors = new ArrayList<>();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> FullAnalyze.fullAnalyze(
                        db, new MetaContainer(), errors, monitor));

        assertAll(
                () -> assertSame(monitorFailure, thrown),
                () -> assertEquals(2, workerChecks.get()),
                () -> assertTrue(db.getAnalysisLaunchers().isEmpty()),
                () -> assertTrue(errors.isEmpty(), errors::toString));
    }

    @Test
    void inheritedMonitoredOverrideKeepsDynamicDispatchAndTaskFailure() {
        RuntimeException primary = new IllegalArgumentException(
                "controlled monitored override failure");
        var overrideCalls = new AtomicInteger();
        var fallbackCalls = new AtomicInteger();
        var sameMonitor = new AtomicBoolean();
        var monitor = new NullMonitor();
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        var function = new PgFunction("monitored_override");
        db.addChild(schema);
        schema.addChild(function);

        class MonitoredOverrideLauncher extends AbstractAnalysisLauncher {
            MonitoredOverrideLauncher() {
                super(function, new ParserRuleContext(), "test.sql");
            }

            @Override
            public Set<ObjectReference> launchAnalyze(
                    List<Object> errors, IMetaContainer meta, IMonitor actualMonitor) {
                overrideCalls.incrementAndGet();
                sameMonitor.set(actualMonitor == monitor);
                throw primary;
            }

            @Override
            protected Set<ObjectLocation> analyze(
                    ParserRuleContext ignored, IMetaContainer meta) {
                fallbackCalls.incrementAndGet();
                return Set.of();
            }
        }
        class InheritedMonitoredOverrideLauncher extends MonitoredOverrideLauncher {
        }
        db.addAnalysisLauncher(new InheritedMonitoredOverrideLauncher());

        Throwable thrown = null;
        try {
            FullAnalyze.fullAnalyze(
                    db, new MetaContainer(), new ArrayList<>(), monitor);
        } catch (Throwable ex) {
            thrown = ex;
        }
        Throwable actualFailure = thrown;

        assertAll(
                () -> assertInstanceOf(IllegalStateException.class, actualFailure),
                () -> assertEquals(1, overrideCalls.get()),
                () -> assertTrue(sameMonitor.get()),
                () -> assertEquals(0, fallbackCalls.get()),
                () -> {
                    var taskFailure = assertInstanceOf(
                            IllegalStateException.class, actualFailure);
                    var executionFailure = assertInstanceOf(
                            ExecutionException.class, taskFailure.getCause());
                    assertSame(primary, executionFailure.getCause());
                },
                () -> assertTrue(db.getAnalysisLaunchers().isEmpty()));
    }

    @Test
    void cancellationStopsBulkDependencyPublicationBeforeWholeBatchIsRetained() {
        int total = 4096;
        var statement = new PgFunction("dependency_publication");
        Set<ObjectReference> dependencies = new LinkedHashSet<>();
        for (int i = 0; i < total; i++) {
            dependencies.add(new ObjectReference(
                    "public", "dependency_" + i,
                    org.pgcodekeeper.core.database.api.schema.DbObjType.FUNCTION));
        }
        var db = new PgDatabase();
        db.addAnalysisLauncher(publicationLauncher(
                statement, ignored -> { }, dependencies, List.of()));
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return statement.getDependencies().size() >= 256;
            }
        };

        assertThrows(InterruptedException.class,
                () -> FullAnalyze.fullAnalyze(
                        db, new MetaContainer(), new ArrayList<>(), monitor));

        assertAll(
                () -> assertTrue(statement.getDependencies().size() >= 256),
                () -> assertTrue(statement.getDependencies().size() < total,
                        "all dependencies were retained before cancellation"));
    }

    @Test
    void cancellationStopsBulkReferencePublicationBeforeWholeBatchIsRetained() {
        int total = 4096;
        AtomicInteger referencesRead = new AtomicInteger();
        ObjectLocation location = mock(ObjectLocation.class);
        List<ObjectLocation> references = new AbstractList<>() {
            @Override
            public ObjectLocation get(int index) {
                referencesRead.incrementAndGet();
                return location;
            }

            @Override
            public int size() {
                return total;
            }
        };
        var db = new PgDatabase();
        db.addAnalysisLauncher(publicationLauncher(
                new PgFunction("reference_publication"),
                ignored -> { }, Set.of(), references));
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return referencesRead.get() >= 256;
            }
        };

        assertThrows(InterruptedException.class,
                () -> FullAnalyze.fullAnalyze(
                        db, new MetaContainer(), new ArrayList<>(), monitor));

        assertAll(
                () -> assertTrue(referencesRead.get() >= 256),
                () -> assertTrue(referencesRead.get() < total,
                        "all references were retained before cancellation"));
    }

    @Test
    void prebuiltMetadataOverloadObservesCancellationRaisedByFinalCleanup() {
        var monitor = new NullMonitor();
        var clears = new AtomicInteger();
        var db = new PgDatabase() {
            @Override
            public void clearAnalysisLaunchers() {
                super.clearAnalysisLaunchers();
                if (clears.incrementAndGet() == 2) {
                    monitor.setCancelled(true);
                }
            }
        };

        assertThrows(InterruptedException.class,
                () -> FullAnalyze.fullAnalyze(
                        db, new MetaContainer(), new ArrayList<>(), monitor));

        assertEquals(2, clears.get());
    }

    @Test
    void versionOverloadObservesCancellationRaisedByFinalCleanup() {
        var monitor = new NullMonitor();
        var clears = new AtomicInteger();
        var db = new PgDatabase() {
            @Override
            public void clearAnalysisLaunchers() {
                super.clearAnalysisLaunchers();
                if (clears.incrementAndGet() == 2) {
                    monitor.setCancelled(true);
                }
            }
        };

        assertThrows(InterruptedException.class,
                () -> FullAnalyze.fullAnalyze(
                        db, new ArrayList<>(), PgSupportedVersion.VERSION_15, monitor));

        assertEquals(2, clears.get());
    }

    @Test
    void finalCleanupMonitorFailurePreservesIdentityAtPublicBoundary() {
        RuntimeException monitorFailure = new IllegalStateException(
                "controlled post-cleanup monitor failure");
        var cleanupFinished = new AtomicBoolean();
        var clears = new AtomicInteger();
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                if (cleanupFinished.get()) {
                    throw monitorFailure;
                }
                return false;
            }
        };
        var db = new PgDatabase() {
            @Override
            public void clearAnalysisLaunchers() {
                super.clearAnalysisLaunchers();
                if (clears.incrementAndGet() == 2) {
                    cleanupFinished.set(true);
                }
            }
        };

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> FullAnalyze.fullAnalyze(
                        db, new MetaContainer(), new ArrayList<>(), monitor));

        assertAll(
                () -> assertSame(monitorFailure, thrown),
                () -> assertEquals(2, clears.get()));
    }

    @Test
    void loaderAnalyzesPartialDatabaseAgainstExternalMetadata() throws Exception {
        var db = new PgDatabase(true);
        var metadata = new CountingMetaContainer(() -> { });
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath("partial.sql")
                .build();
        var function = new PgFunction("partial_function");
        var analysisThread = new AtomicReference<String>();
        db.addAnalysisLauncher(publicationLauncher(
                function,
                errors -> {
                    analysisThread.set(Thread.currentThread().getName());
                    assertNotNull(metadata.findRelation("external", "items"));
                },
                Set.of(),
                List.of(location)));
        var settings = new CoreSettings();
        settings.setVersion(PgSupportedVersion.VERSION_15);
        settings.setParserExecutionPolicy(ParserExecutionPolicy.dedicated(1));
        var monitorChecks = new AtomicInteger();
        settings.setMonitor(new NullMonitor() {
            @Override
            public boolean isCancelled() {
                monitorChecks.incrementAndGet();
                return false;
            }
        });

        try (var loader = new StaticPgLoader(settings, db)) {
            assertSame(db, loader.loadAndAnalyze(metadata));
        }

        assertAll(
                () -> assertEquals(1, metadata.relationLookups.get()),
                () -> assertTrue(monitorChecks.get() > 0),
                () -> assertTrue(analysisThread.get()
                        .startsWith("pgck-antlr-index-")),
                () -> assertEquals(Set.of(location),
                        db.getObjReferences().get("partial.sql")));
    }

    @Test
    void externalMetadataAnalysisObservesMonitorCancellation() throws Exception {
        var db = new PgDatabase();
        var metadataRead = new AtomicBoolean();
        var metadata = new CountingMetaContainer(() -> metadataRead.set(true));
        db.addAnalysisLauncher(publicationLauncher(
                new PgFunction("cancelled_partial_function"),
                errors -> metadata.findRelation("external", "items"),
                Set.of(),
                List.of()));
        var settings = new CoreSettings();
        settings.setVersion(PgSupportedVersion.VERSION_15);
        settings.setMonitor(new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return metadataRead.get();
            }
        });

        try (var loader = new StaticPgLoader(settings, db)) {
            assertThrows(InterruptedException.class,
                    () -> loader.loadAndAnalyze(metadata));
        }

        assertTrue(metadataRead.get());
        assertTrue(db.getAnalysisLaunchers().isEmpty());
    }

    private static IAnalysisLauncher publicationLauncher(
            IStatement statement, Consumer<List<Object>> errorAction,
            Set<ObjectReference> dependencies, List<ObjectLocation> references) {
        return new IAnalysisLauncher() {
            @Override
            public IStatement getStmt() {
                return statement;
            }

            @Override
            public void updateStmt(IDatabase database) {
                // no-op test launcher
            }

            @Override
            public Set<ObjectReference> launchAnalyze(
                    List<Object> errors, IMetaContainer meta) {
                errorAction.accept(errors);
                return dependencies;
            }

            @Override
            public List<ObjectLocation> getReferences() {
                return references;
            }

            @Override
            public String getSchemaName() {
                return null;
            }
        };
    }

    private static final class StaticPgLoader extends AbstractLoader<PgDatabase> {

        private final PgDatabase database;

        private StaticPgLoader(CoreSettings settings, PgDatabase database) {
            super(settings, "partial");
            this.database = database;
        }

        @Override
        protected PgDatabase loadInternal() {
            return database;
        }

        @Override
        protected PgDatabase createDatabase() {
            return new PgDatabase();
        }
    }

    private static final class CountingMetaContainer implements IMetaContainer {

        private final Runnable findRelationAction;
        private final AtomicInteger relationLookups = new AtomicInteger();
        private final IRelation relation = new PgSimpleTable("items");

        private CountingMetaContainer(Runnable findRelationAction) {
            this.findRelationAction = findRelationAction;
        }

        @Override
        public IRelation findRelation(String schemaName, String relationName) {
            relationLookups.incrementAndGet();
            findRelationAction.run();
            return relation;
        }

        @Override
        public Collection<IFunction> availableFunctions(String schemaName) {
            return List.of();
        }

        @Override
        public Collection<IOperator> availableOperators(String schemaName) {
            return List.of();
        }

        @Override
        public ICompositeType findType(String schemaName, String typeName) {
            return null;
        }

        @Override
        public IFunction findFunction(String schemaName, String functionName) {
            return null;
        }

        @Override
        public IOperator findOperator(String schemaName, String operatorName) {
            return null;
        }

        @Override
        public Collection<IConstraintPk> getPrimaryKeys(
                String schemaName, String tableName) {
            return List.of();
        }

        @Override
        public boolean containsCastImplicit(String source, String target) {
            return false;
        }

        @Override
        public Map<String, Map<String, IRelation>> getRelations() {
            return Map.of();
        }
    }

    private static IAnalysisLauncher failingLauncher(RuntimeException failure) {
        return new IAnalysisLauncher() {
            @Override
            public IStatement getStmt() {
                return null;
            }

            @Override
            public void updateStmt(IDatabase database) {
                // nothing to update
            }

            @Override
            public Set<ObjectReference> launchAnalyze(List<Object> errors, IMetaContainer meta) {
                throw failure;
            }

            @Override
            public List<ObjectLocation> getReferences() {
                return List.of();
            }

            @Override
            public String getSchemaName() {
                return null;
            }
        };
    }

    private static IAnalysisLauncher blockingLauncher(
            IMonitor monitor, CountDownLatch workerEntered,
            CountDownLatch cancellationObserved, CountDownLatch releaseWorker,
            AtomicBoolean workerExited) {
        return new IAnalysisLauncher() {
            private final PgFunction function = new PgFunction("blocking_function");

            @Override
            public IStatement getStmt() {
                return function;
            }

            @Override
            public void updateStmt(IDatabase database) {
                // no-op test launcher
            }

            @Override
            public Set<ObjectReference> launchAnalyze(List<Object> errors, IMetaContainer meta) {
                fail("monitored overload was not used");
                return Set.of();
            }

            @Override
            public Set<ObjectReference> launchAnalyze(
                    List<Object> errors, IMetaContainer meta, IMonitor actualMonitor) {
                assertSame(monitor, actualMonitor);
                workerEntered.countDown();
                boolean interrupted = false;
                try {
                    while (true) {
                        try {
                            releaseWorker.await();
                            return Set.of();
                        } catch (InterruptedException ex) {
                            interrupted = true;
                            cancellationObserved.countDown();
                        }
                    }
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    workerExited.set(true);
                }
            }

            @Override
            public List<ObjectLocation> getReferences() {
                return List.of();
            }

            @Override
            public String getSchemaName() {
                return null;
            }
        };
    }

    private static IAnalysisLauncher descriptorBarrierLauncher(
            CountDownLatch ownerAtDescriptor, CountDownLatch releaseDescriptor) {
        return new IAnalysisLauncher() {
            private final PgFunction function = new PgFunction("descriptor_barrier");

            @Override
            public long getEstimatedParseBytes() {
                ownerAtDescriptor.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        releaseDescriptor.await();
                        break;
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }

            @Override
            public IStatement getStmt() {
                return function;
            }

            @Override
            public void updateStmt(IDatabase database) {
                // no-op test launcher
            }

            @Override
            public Set<ObjectReference> launchAnalyze(List<Object> errors, IMetaContainer meta) {
                return Set.of();
            }

            @Override
            public List<ObjectLocation> getReferences() {
                return List.of();
            }

            @Override
            public String getSchemaName() {
                return null;
            }
        };
    }

    /**
     * Waits on a worker thread until the owner thread reaches the agreed
     * handoff point. The wait is bounded so that a regression in that handoff
     * fails this test with its own diagnostic instead of hanging the suite.
     *
     * @param handoff latch the owner counts down at the handoff point
     * @return {@code true} if the handoff happened within the timeout
     */
    private static boolean awaitHandoff(CountDownLatch handoff) {
        try {
            return handoff.await(HANDOFF_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
