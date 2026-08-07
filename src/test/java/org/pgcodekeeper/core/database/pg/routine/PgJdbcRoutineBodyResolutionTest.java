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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.base.parser.launcher.DeferredAnalysisStateException;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.monitor.NullMonitor;

class PgJdbcRoutineBodyResolutionTest {

    private static final RoutineBodyProfile PROFILE = RoutineBodyProfile.current(false);

    @Test
    void fullBodyResolutionPublishesAndLeasesTheExactPayloadOnce() throws Exception {
        PgFunction function = attachedFunction("exact_payload");
        String raw = new String("SELECT 'Привет 😀'");
        String canonical = new String("$$SELECT 'Привет 😀'$$");
        var resolution = PgJdbcRoutineBodyResolution.fullBody();

        RoutineBodySource registered = resolution.registerFullBody(function, raw, canonical,
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);
        DeferredRoutineBodySource deferred = assertInstanceOf(
                DeferredRoutineBodySource.class, registered);

        assertFalse(function.hasBodyReference(canonical));
        assertEquals(1, resolution.pendingCount());

        resolution.resolveAll(new NullMonitor());
        resolution.close();
        RoutineBody body = deferred.take();

        assertSame(raw, body.raw());
        assertSame(canonical, body.canonical());
        assertSame(body.measure(), deferred.authorization().fingerprint());
        assertTrue(function.hasBodyReference(canonical));
        assertEquals(0, resolution.pendingCount());
    }

    @Test
    void resolverReceivesImmutableSlotsInRegistrationOrder() throws Exception {
        PgFunction first = attachedFunction("first");
        PgFunction second = attachedFunction("second");
        String firstCanonical = new String("$$SELECT 1$$");
        String secondCanonical = new String("$$SELECT 2$$");
        List<RoutineIdentity> observed = new ArrayList<>();
        var resolution = new PgJdbcRoutineBodyResolution((slots, monitor) -> {
            assertThrows(UnsupportedOperationException.class, slots::clear);
            for (PgJdbcRoutineBodySlot slot : slots) {
                observed.add(slot.identity());
                slot.resolveFullBody();
            }
        });

        resolution.registerFullBody(first, "SELECT 1", firstCanonical,
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);
        resolution.registerFullBody(second, "SELECT 2", secondCanonical,
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);

        assertFalse(first.hasBodyReference(firstCanonical));
        assertFalse(second.hasBodyReference(secondCanonical));
        resolution.resolveAll(new NullMonitor());

        assertEquals(List.of(RoutineIdentity.from(first), RoutineIdentity.from(second)), observed);
        assertTrue(first.hasBodyReference(firstCanonical));
        assertTrue(second.hasBodyReference(secondCanonical));
    }

    @Test
    void activeResolverCannotCloseOrInvalidateItsBatch() throws Exception {
        PgFunction function = attachedFunction("reentrant_close");
        PgJdbcRoutineBodyResolution[] owner = new PgJdbcRoutineBodyResolution[1];
        owner[0] = new PgJdbcRoutineBodyResolution((slots, monitor) -> {
            assertThrows(IllegalStateException.class, owner[0]::close);
            slots.get(0).resolveFullBody();
        });
        RoutineBodySource source = owner[0].registerFullBody(
                function, "SELECT 1", "$$SELECT 1$$", PROFILE,
                RoutineBodyRepresentation.SQL_TEXT);

        owner[0].resolveAll(new NullMonitor());

        assertEquals("SELECT 1", source.take().raw());
    }

    @Test
    void terminalResolutionDetachesTheCorpusSizedBatch() throws Exception {
        PgFunction function = attachedFunction("detached_batch");
        var resolution = PgJdbcRoutineBodyResolution.fullBody();
        resolution.registerFullBody(function, "SELECT 1", "$$SELECT 1$$",
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);

        resolution.resolveAll(new NullMonitor());

        assertNull(pendingBatch(resolution));
        assertNull(retainedResolver(resolution));
    }

    @Test
    void incompleteResolverFailsClosedAndReleasesTheCorpus() {
        PgFunction first = attachedFunction("incomplete_first");
        PgFunction second = attachedFunction("incomplete_second");
        var resolution = new PgJdbcRoutineBodyResolution((slots, monitor) -> {
            slots.get(0).resolveFullBody();
        });
        RoutineBodySource firstSource = resolution.registerFullBody(first, "SELECT 1", "$$SELECT 1$$",
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);
        RoutineBodySource secondSource = resolution.registerFullBody(second, "SELECT 2", "$$SELECT 2$$",
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);

        assertThrows(DeferredAnalysisStateException.class,
                () -> resolution.resolveAll(new NullMonitor()));

        assertUnavailable(firstSource);
        assertUnavailable(secondSource);
        assertEquals(0, resolution.pendingCount());
        assertDoesNotThrow(resolution::close);
        assertDoesNotThrow(resolution::close);
    }

    @Test
    void resolverFailureRemainsThePrimaryException() {
        PgFunction function = attachedFunction("primary_failure");
        IOException primary = new IOException("controlled resolver failure");
        var resolution = new PgJdbcRoutineBodyResolution((slots, monitor) -> {
            throw primary;
        });
        RoutineBodySource source = resolution.registerFullBody(function, "SELECT 1", "$$SELECT 1$$",
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);

        IOException observed = assertThrows(IOException.class,
                () -> resolution.resolveAll(new NullMonitor()));

        assertSame(primary, observed);
        assertUnavailable(source);
        assertEquals(0, resolution.pendingCount());
    }

    @Test
    void cancellationRestoresInterruptAndClosesEveryPendingLease() {
        Thread.interrupted();
        PgFunction function = attachedFunction("cancelled");
        var resolution = PgJdbcRoutineBodyResolution.fullBody();
        RoutineBodySource source = resolution.registerFullBody(function, "SELECT 1", "$$SELECT 1$$",
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);
        var monitor = new NullMonitor();
        monitor.setCancelled(true);

        try {
            assertThrows(InterruptedException.class, () -> resolution.resolveAll(monitor));
            assertTrue(Thread.currentThread().isInterrupted());
            assertUnavailable(source);
            assertEquals(0, resolution.pendingCount());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void closeBeforeResolutionIsIdempotentAndTerminal() {
        PgFunction function = attachedFunction("closed");
        var resolution = PgJdbcRoutineBodyResolution.fullBody();
        RoutineBodySource source = resolution.registerFullBody(function, "SELECT 1", "$$SELECT 1$$",
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);

        resolution.close();
        resolution.close();

        assertUnavailable(source);
        assertEquals(0, resolution.pendingCount());
        assertThrows(IllegalStateException.class,
                () -> resolution.registerFullBody(attachedFunction("late"), "SELECT 2", "$$SELECT 2$$",
                        PROFILE, RoutineBodyRepresentation.SQL_TEXT));
        assertThrows(IllegalStateException.class,
                () -> resolution.resolveAll(new NullMonitor()));
    }

    @Test
    void malformedUtf16FallsBackWithoutLosingAnalyzability() throws Exception {
        PgFunction function = attachedFunction("malformed");
        String raw = new String("SELECT '\uD800'");
        String canonical = new String("$$SELECT '\uD800'$$");
        var resolution = PgJdbcRoutineBodyResolution.fullBody();

        RoutineBodySource source = resolution.registerFullBody(function, raw, canonical,
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);

        assertInstanceOf(OwnedRoutineBodySource.class, source);
        assertTrue(function.hasBodyReference(canonical));
        assertEquals(0, resolution.pendingCount());

        resolution.resolveAll(new NullMonitor());
        resolution.close();
        RoutineBody body = source.take();

        assertSame(raw, body.raw());
        assertSame(canonical, body.canonical());
        assertInstanceOf(RoutineBodyMeasure.Unreusable.class, body.measure());
    }

    @Test
    void registrationRequiresTheExactAttachedSchemaChild() {
        var resolution = PgJdbcRoutineBodyResolution.fullBody();
        var orphan = new PgFunction("orphan");
        var merelyParented = new PgFunction("merely_parented");
        var schema = new PgSchema("public");
        merelyParented.setParent(schema);

        assertThrows(IllegalArgumentException.class,
                () -> resolution.registerFullBody(orphan, "SELECT 1", "$$SELECT 1$$",
                        PROFILE, RoutineBodyRepresentation.SQL_TEXT));
        assertThrows(IllegalArgumentException.class,
                () -> resolution.registerFullBody(merelyParented, "SELECT 1", "$$SELECT 1$$",
                        PROFILE, RoutineBodyRepresentation.SQL_TEXT));
        assertEquals(0, resolution.pendingCount());
    }

    private static PgFunction attachedFunction(String name) {
        var schema = new PgSchema("public");
        var function = new PgFunction(name);
        schema.addChild(function);
        return function;
    }

    private static void assertUnavailable(RoutineBodySource source) {
        assertThrows(DeferredAnalysisStateException.class, source::take);
    }

    private static Object pendingBatch(PgJdbcRoutineBodyResolution resolution)
            throws ReflectiveOperationException {
        Field field = PgJdbcRoutineBodyResolution.class.getDeclaredField("slots");
        field.setAccessible(true);
        return field.get(resolution);
    }

    private static Object retainedResolver(PgJdbcRoutineBodyResolution resolution)
            throws ReflectiveOperationException {
        Field field = PgJdbcRoutineBodyResolution.class.getDeclaredField("resolver");
        field.setAccessible(true);
        return field.get(resolution);
    }
}
