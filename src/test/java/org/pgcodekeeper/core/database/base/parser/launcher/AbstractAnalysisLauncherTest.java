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
package org.pgcodekeeper.core.database.base.parser.launcher;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.antlr.v4.runtime.ParserRuleContext;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.database.base.schema.meta.MetaContainer;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;

class AbstractAnalysisLauncherTest {

    @Test
    void detachesRetainedSubtreeAndReleasesItAfterAnalysis() throws ReflectiveOperationException {
        var root = new ParserRuleContext();
        var retainedChild = new ParserRuleContext(root, 1);
        var retainedLeaf = new ParserRuleContext(retainedChild, 2);
        root.addChild(retainedChild);
        retainedChild.addChild(retainedLeaf);

        var db = new PgDatabase();
        var schema = new PgSchema("public");
        var function = new PgFunction("test_function");
        db.addChild(schema);
        schema.addChild(function);

        var expectedReference = new ObjectReference("public", "target_function", DbObjType.FUNCTION);
        var launcher = new TestAnalysisLauncher(function, retainedChild, expectedReference);

        assertNull(retainedChild.getParent(), "retained analysis subtree must not keep its statement root alive");
        assertSame(retainedChild, retainedLeaf.getParent(), "detaching must preserve the retained subtree");
        assertEquals(Set.of(expectedReference),
                launcher.launchAnalyze(new ArrayList<>(), new MetaContainer()));
        assertNull(readRetainedContext(launcher),
                "completed launcher must release its materialized parser context");
    }

    @Test
    void releasesRetainedContextWhenAnalysisFails() throws ReflectiveOperationException {
        var context = new ParserRuleContext();
        var function = attachedFunction();
        var launcher = new AbstractAnalysisLauncher(function, context, "test.sql") {
            @Override
            protected Set<ObjectLocation> analyze(ParserRuleContext ignored, IMetaContainer meta) {
                throw new IllegalStateException("controlled failure");
            }
        };
        var errors = new ArrayList<>();

        assertTrue(launcher.launchAnalyze(errors, new MetaContainer()).isEmpty());
        assertEquals(1, errors.size());
        assertNull(readRetainedContext(launcher));
    }

    @Test
    void preservesContextFreeLaunchers() {
        var launcher = new AbstractAnalysisLauncher(attachedFunction(), null, "test.sql") {
            @Override
            protected Set<ObjectLocation> analyze(ParserRuleContext context, IMetaContainer meta) {
                assertNull(context);
                return Set.of();
            }
        };
        var errors = new ArrayList<>();

        assertTrue(launcher.launchAnalyze(errors, new MetaContainer()).isEmpty());
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void monitoredLauncherRethrowsCancellationWithoutAddingDiagnostic() {
        var context = new ParserRuleContext();
        var cancellation = new MonitorCancelledRuntimeException();
        var launcher = new AbstractAnalysisLauncher(attachedFunction(), context, "test.sql") {
            @Override
            protected Set<ObjectLocation> analyze(ParserRuleContext ignored, IMetaContainer meta) {
                throw cancellation;
            }
        };
        var errors = new ArrayList<>();

        MonitorCancelledRuntimeException thrown = assertThrows(
                MonitorCancelledRuntimeException.class,
                () -> launcher.launchAnalyze(errors, new MetaContainer(), new NullMonitor()));

        assertSame(cancellation, thrown);
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void duplicateLauncherChecksCancellationAfterReleasingContext()
            throws ReflectiveOperationException {
        var context = new ParserRuleContext();
        var checks = new AtomicInteger();
        IMonitor monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return checks.incrementAndGet() == 2;
            }
        };
        var launcher = new AbstractAnalysisLauncher(
                new PgFunction("duplicate_function"), context, "test.sql") {
            @Override
            protected Set<ObjectLocation> analyze(
                    ParserRuleContext ignored, IMetaContainer meta) {
                fail("duplicate launcher must not be analyzed");
                return Set.of();
            }
        };
        var errors = new ArrayList<>();

        assertThrows(InterruptedException.class,
                () -> launcher.launchAnalyze(errors, new MetaContainer(), monitor));

        assertEquals(2, checks.get());
        assertNull(readRetainedContext(launcher));
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void monitoredDispatchInvokesCompatibilityOverrideOnlySubclass() throws Exception {
        var compatibilityCalls = new AtomicInteger();
        var expected = Set.of(new ObjectReference(
                "public", "compatibility_target", DbObjType.FUNCTION));
        var launcher = new AbstractAnalysisLauncher(
                attachedFunction(), new ParserRuleContext(), "test.sql") {
            @Override
            public Set<ObjectReference> launchAnalyze(
                    List<Object> errors, IMetaContainer meta) {
                compatibilityCalls.incrementAndGet();
                return expected;
            }

            @Override
            protected Set<ObjectLocation> analyze(
                    ParserRuleContext ignored, IMetaContainer meta) {
                fail("base analysis must not bypass the compatibility override");
                return Set.of();
            }
        };

        Set<ObjectReference> actual = launcher.launchAnalyze(
                new ArrayList<>(), new MetaContainer(), new NullMonitor());

        assertSame(expected, actual);
        assertEquals(1, compatibilityCalls.get());
    }

    @Test
    void compatibilityOverrideCanCallSuperWithoutRecursiveDispatch() throws Exception {
        var compatibilityCalls = new AtomicInteger();
        var expected = new ObjectReference(
                "public", "super_target", DbObjType.FUNCTION);
        var launcher = new AbstractAnalysisLauncher(
                attachedFunction(), new ParserRuleContext(), "test.sql") {
            @Override
            public Set<ObjectReference> launchAnalyze(
                    List<Object> errors, IMetaContainer meta) {
                compatibilityCalls.incrementAndGet();
                return super.launchAnalyze(errors, meta);
            }

            @Override
            protected Set<ObjectLocation> analyze(
                    ParserRuleContext ignored, IMetaContainer meta) {
                return Set.of(new ObjectLocation.Builder()
                        .setReference(expected)
                        .build());
            }
        };

        Set<ObjectReference> actual = launcher.launchAnalyze(
                new ArrayList<>(), new MetaContainer(), new NullMonitor());

        assertEquals(Set.of(expected), actual);
        assertEquals(1, compatibilityCalls.get());
    }

    @Test
    void cancellationStopsWideLocationPublicationBeforeWholeBatchIsRetained() {
        Set<ObjectLocation> locations = wideLocations(4096);
        AtomicReference<AbstractAnalysisLauncher> holder = new AtomicReference<>();
        var launcher = new AbstractAnalysisLauncher(
                attachedFunction(), new ParserRuleContext(), "test.sql") {
            @Override
            protected Set<ObjectLocation> analyze(
                    ParserRuleContext ignored, IMetaContainer meta) {
                return locations;
            }
        };
        holder.set(launcher);
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return holder.get().getReferences().size() >= 256;
            }
        };

        assertThrows(InterruptedException.class,
                () -> launcher.launchAnalyze(
                        new ArrayList<>(), new MetaContainer(), monitor));

        assertAll(
                () -> assertTrue(launcher.getReferences().size() >= 256),
                () -> assertTrue(launcher.getReferences().size() < locations.size(),
                        "all references were retained before cancellation"));
    }

    @Test
    void wideLocationMonitorFailurePreservesIdentityWithoutDiagnostic() {
        Set<ObjectLocation> locations = wideLocations(4096);
        AtomicReference<AbstractAnalysisLauncher> holder = new AtomicReference<>();
        RuntimeException monitorFailure = new IllegalStateException(
                "controlled location publication monitor failure");
        var launcher = new AbstractAnalysisLauncher(
                attachedFunction(), new ParserRuleContext(), "test.sql") {
            @Override
            protected Set<ObjectLocation> analyze(
                    ParserRuleContext ignored, IMetaContainer meta) {
                return locations;
            }
        };
        holder.set(launcher);
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                if (holder.get().getReferences().size() >= 256) {
                    throw monitorFailure;
                }
                return false;
            }
        };
        var errors = new ArrayList<>();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> launcher.launchAnalyze(errors, new MetaContainer(), monitor));

        assertAll(
                () -> assertSame(monitorFailure, thrown),
                () -> assertTrue(launcher.getReferences().size() < locations.size()),
                () -> assertTrue(errors.isEmpty(), errors::toString));
    }

    @Test
    void threadInterruptStopsWideLocationPublication() {
        Set<ObjectLocation> delegate = wideLocations(4096);
        var locations = new AbstractSet<ObjectLocation>() {
            @Override
            public Iterator<ObjectLocation> iterator() {
                Iterator<ObjectLocation> iterator = delegate.iterator();
                return new Iterator<>() {
                    private int read;

                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public ObjectLocation next() {
                        ObjectLocation location = iterator.next();
                        if (++read == 300) {
                            Thread.currentThread().interrupt();
                        }
                        return location;
                    }
                };
            }

            @Override
            public int size() {
                return delegate.size();
            }
        };
        var launcher = new AbstractAnalysisLauncher(
                attachedFunction(), new ParserRuleContext(), "test.sql") {
            @Override
            protected Set<ObjectLocation> analyze(
                    ParserRuleContext ignored, IMetaContainer meta) {
                return locations;
            }
        };

        try {
            assertThrows(InterruptedException.class,
                    () -> launcher.launchAnalyze(
                            new ArrayList<>(), new MetaContainer(), new NullMonitor()));

            assertAll(
                    () -> assertTrue(Thread.currentThread().isInterrupted()),
                    () -> assertTrue(launcher.getReferences().size() < locations.size()));
        } finally {
            Thread.interrupted();
        }
    }

    private static PgFunction attachedFunction() {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        var function = new PgFunction("test_function");
        db.addChild(schema);
        schema.addChild(function);
        return function;
    }

    private static Object readRetainedContext(AbstractAnalysisLauncher launcher)
            throws ReflectiveOperationException {
        Field field = AbstractAnalysisLauncher.class.getDeclaredField("ctx");
        field.setAccessible(true);
        return field.get(launcher);
    }

    private static Set<ObjectLocation> wideLocations(int count) {
        Set<ObjectLocation> locations = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            locations.add(new ObjectLocation.Builder()
                    .setReference(new ObjectReference(
                            "public", "target_" + i, DbObjType.FUNCTION))
                    .setOffset(i)
                    .setLineNumber(1)
                    .build());
        }
        return locations;
    }

    private static final class TestAnalysisLauncher extends AbstractAnalysisLauncher {

        private final ParserRuleContext expectedContext;
        private final ObjectReference expectedReference;

        private TestAnalysisLauncher(PgFunction function, ParserRuleContext context,
                                     ObjectReference expectedReference) {
            super(function, context, "test.sql");
            this.expectedContext = context;
            this.expectedReference = expectedReference;
        }

        @Override
        protected Set<ObjectLocation> analyze(ParserRuleContext context, IMetaContainer meta) {
            assertSame(expectedContext, context);
            return Set.of(new ObjectLocation.Builder().setReference(expectedReference).build());
        }
    }
}
