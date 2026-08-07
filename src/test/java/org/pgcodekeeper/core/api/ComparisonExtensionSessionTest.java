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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionContext;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionKey;
import org.pgcodekeeper.core.database.api.loader.ComparisonSide;
import org.pgcodekeeper.core.database.api.loader.IComparisonExtensionBinding;
import org.pgcodekeeper.core.database.api.schema.IDatabase;

class ComparisonExtensionSessionTest {

    @Test
    void completeKeysBindAndActivateInFirstRegistrationOrder() throws Exception {
        var events = new ArrayList<String>();
        var firstBinding = new RecordingBinding("first", events);
        var secondBinding = new RecordingBinding("second", events);
        var first = key("same-name", firstBinding, events);
        var second = key("same-name", secondBinding, events);
        var session = new ComparisonExtensionSession();

        session.context(ComparisonSide.OLD).register(first, "old-first");
        session.context(ComparisonSide.OLD).register(second, "old-second");
        session.seal(ComparisonSide.OLD);
        session.context(ComparisonSide.NEW).register(second, "new-second");
        session.context(ComparisonSide.NEW).register(first, "new-first");
        session.seal(ComparisonSide.NEW);
        session.activate();

        assertNotSame(first, second, "keys with equal labels retain identity semantics");
        assertEquals(List.of(
                "first bind old-first/new-first",
                "second bind old-second/new-second",
                "first activate", "second activate"), events);
        assertEquals(0, session.retainedEndpointCount());
    }

    @Test
    void contextsAreSideBoundAndIncompleteOrDeclinedPairsCreateNoBinding()
            throws Exception {
        var events = new ArrayList<String>();
        var incomplete = key("incomplete",
                new RecordingBinding("incomplete", events), events);
        var declined = new ComparisonExtensionKey<String>(
                "declined", String.class, (oldValue, newValue) -> {
                    events.add("declined bind " + oldValue + "/" + newValue);
                    return Optional.empty();
                });
        var session = new ComparisonExtensionSession();
        ComparisonExtensionContext oldContext = session.context(ComparisonSide.OLD);
        ComparisonExtensionContext newContext = session.context(ComparisonSide.NEW);

        assertEquals(ComparisonSide.OLD, oldContext.side());
        assertEquals(ComparisonSide.NEW, newContext.side());
        oldContext.register(incomplete, "old-only");
        oldContext.register(declined, "old-declined");
        newContext.register(declined, "new-declined");
        session.seal(ComparisonSide.OLD);
        session.seal(ComparisonSide.NEW);
        session.activate();
        session.sideLoaded(ComparisonSide.OLD, null);
        session.sideFailed(ComparisonSide.NEW, new IOException("ignored"));
        session.cancel();
        session.closeBindings();

        assertEquals(List.of("declined bind old-declined/new-declined"), events);
    }

    @Test
    void registrationRejectsNullDuplicateAndSameEndpointIdentity() {
        var key = key("key", new RecordingBinding("key", new ArrayList<>()),
                new ArrayList<>());
        var session = new ComparisonExtensionSession();
        var oldContext = session.context(ComparisonSide.OLD);
        var newContext = session.context(ComparisonSide.NEW);

        assertThrows(NullPointerException.class, () -> oldContext.register(null, "endpoint"));
        assertThrows(NullPointerException.class, () -> oldContext.register(key, null));

        String endpoint = new String("shared");
        oldContext.register(key, endpoint);
        assertThrows(IllegalArgumentException.class,
                () -> oldContext.register(key, "duplicate"));
        assertThrows(IllegalArgumentException.class,
                () -> newContext.register(key, endpoint),
                "one endpoint object cannot impersonate both logical sides");
    }

    @Test
    void oneEndpointObjectMayImplementCapabilitiesForDifferentKeys() throws Exception {
        var first = new ComparisonExtensionKey<String>(
                "first", String.class, (oldValue, newValue) -> Optional.empty());
        var second = new ComparisonExtensionKey<String>(
                "second", String.class, (oldValue, newValue) -> Optional.empty());
        var session = new ComparisonExtensionSession();
        String sharedOldEndpoint = new String("shared-old");

        session.context(ComparisonSide.OLD).register(first, sharedOldEndpoint);
        session.context(ComparisonSide.OLD).register(second, sharedOldEndpoint);
        session.context(ComparisonSide.NEW).register(first, "new-first");
        session.context(ComparisonSide.NEW).register(second, "new-second");
        sealBoth(session);

        assertDoesNotThrow(session::activate);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    void endpointTokenRejectsRawTypeMismatchBeforeRetention() {
        var key = new ComparisonExtensionKey<String>(
                "strings", String.class, (oldValue, newValue) -> Optional.empty());
        ComparisonExtensionKey rawKey = key;
        var session = new ComparisonExtensionSession();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> session.context(ComparisonSide.OLD).register(rawKey, Integer.valueOf(7)));

        assertTrue(failure.getMessage().contains("java.lang.String"));
        assertEquals(0, session.retainedEndpointCount());
    }

    @Test
    void activationRequiresBothSealsAndRejectsNullBinderResultAndLateUse()
            throws Exception {
        var nullResult = new ComparisonExtensionKey<String>(
                "null-result", String.class, (oldValue, newValue) -> null);
        var session = new ComparisonExtensionSession();
        session.context(ComparisonSide.OLD).register(nullResult, "old");
        session.context(ComparisonSide.NEW).register(nullResult, "new");
        session.seal(ComparisonSide.OLD);
        assertThrows(IllegalStateException.class, session::activate);
        session.seal(ComparisonSide.NEW);

        assertThrows(NullPointerException.class, session::activate);
        assertEquals(0, session.retainedEndpointCount(),
                "failed negotiation clears all endpoint references");
        assertThrows(IllegalStateException.class,
                () -> session.context(ComparisonSide.OLD).register(
                        key("late", new RecordingBinding("late", new ArrayList<>()),
                                new ArrayList<>()), "late"));
        assertThrows(IllegalStateException.class, session::activate);
    }

    @Test
    void lifecycleCallbacksUseBindingOrderAndAreOneShot() throws Exception {
        var events = new ArrayList<String>();
        var first = new RecordingBinding("first", events);
        var second = new RecordingBinding("second", events);
        var session = activatedSession(events, first, second);
        IDatabase oldDatabase = org.mockito.Mockito.mock(IDatabase.class);
        var failure = new IOException("NEW failed");

        session.sideLoaded(ComparisonSide.OLD, oldDatabase);
        session.sideLoaded(ComparisonSide.OLD, oldDatabase);
        session.sideFailed(ComparisonSide.NEW, failure);
        session.sideFailed(ComparisonSide.NEW, failure);
        session.cancel();
        session.cancel();
        session.closeBindings();
        session.closeBindings();

        assertEquals(List.of(
                "first bind old-first/new-first",
                "second bind old-second/new-second",
                "first activate", "second activate",
                "first loaded OLD", "second loaded OLD",
                "first failed NEW NEW failed", "second failed NEW NEW failed",
                "first cancel", "second cancel",
                "first close", "second close"), events);
        assertEquals(0, session.retainedBindingCount());
        assertThrows(IllegalStateException.class, session::activate);
        assertThrows(IllegalStateException.class,
                () -> session.context(ComparisonSide.NEW).register(
                        key("terminal", first, events), "terminal"));
    }

    @Test
    void duplicateBindingIdentityIsRejectedBeforeAnyActivationAndOwnedOnce()
            throws Exception {
        var events = new ArrayList<String>();
        var shared = new RecordingBinding("shared", events);
        var first = key("first", shared, events);
        var second = key("second", shared, events);
        var session = new ComparisonExtensionSession();
        registerPair(session, first, "first");
        registerPair(session, second, "second");
        sealBoth(session);

        assertThrows(IllegalArgumentException.class, session::activate);
        assertEquals(List.of(
                "shared bind old-first/new-first",
                "shared bind old-second/new-second"), events,
                "all negotiation precedes activation and duplicate rejection");

        session.cancel();
        session.closeBindings();
        assertEquals(1, shared.cancelCalls);
        assertEquals(1, shared.closeCalls);
        assertEquals(0, events.stream().filter("shared activate"::equals).count());
    }

    @Test
    void binderFailureRetainsEarlierProductAndClearsEndpoints() throws Exception {
        var events = new ArrayList<String>();
        var retained = new RecordingBinding("retained", events);
        var first = key("first", retained, events);
        var binderFailure = new IOException("second binder failed");
        var second = new ComparisonExtensionKey<String>(
                "second", String.class, (oldValue, newValue) -> {
                    events.add("second bind");
                    throw binderFailure;
                });
        var session = new ComparisonExtensionSession();
        registerPair(session, first, "first");
        registerPair(session, second, "second");
        sealBoth(session);

        IOException failure = assertThrows(IOException.class, session::activate);

        assertSame(binderFailure, failure);
        assertEquals(1, session.retainedBindingCount());
        assertEquals(0, session.retainedEndpointCount());
        assertEquals(List.of(), session.cancel());
        assertEquals(List.of(), session.closeBindings());
        assertEquals(1, retained.cancelCalls);
        assertEquals(1, retained.closeCalls);
    }

    @Test
    void activationFailureStillOwnsAndCleansUnactivatedTailInKeyOrder()
            throws Exception {
        var events = new ArrayList<String>();
        var first = new RecordingBinding("first", events);
        var second = new RecordingBinding("second", events);
        var third = new RecordingBinding("third", events);
        var activationFailure = new IOException("second activate failed");
        second.activateFailure = activationFailure;
        var session = activatedProductsBeforeFailure(events, first, second, third);

        IOException failure = assertThrows(IOException.class, session::activate);

        assertSame(activationFailure, failure);
        assertEquals(List.of(
                "first bind old-first/new-first",
                "second bind old-second/new-second",
                "third bind old-third/new-third",
                "first activate", "second activate"), events);
        session.cancel();
        session.closeBindings();
        assertEquals(List.of(
                "first cancel", "second cancel", "third cancel",
                "first close", "second close", "third close"),
                events.subList(5, events.size()));
        assertEquals(0, third.activateCalls,
                "tail product is owned even though activation never reached it");
    }

    @Test
    void cancellationDuringRegistrationSealsAndReleasesSavedFacades() {
        var session = new ComparisonExtensionSession();
        ComparisonExtensionContext saved = session.context(ComparisonSide.OLD);
        var key = new ComparisonExtensionKey<String>(
                "saved", String.class, (oldValue, newValue) -> Optional.empty());
        saved.register(key, "old");

        session.cancel();

        assertEquals(0, session.retainedEndpointCount());
        assertThrows(IllegalStateException.class,
                () -> saved.register(key("late",
                        new RecordingBinding("late", new ArrayList<>()),
                        new ArrayList<>()), "late"));
        assertThrows(IllegalStateException.class, session::activate);
        assertEquals(List.of(), session.closeBindings());
    }

    @Test
    void cancellationAndCloseRejectActivatingWithoutConsumingOneShot()
            throws Exception {
        var events = new ArrayList<String>();
        var binding = new RecordingBinding("binding", events);
        binding.activateStarted = new CountDownLatch(1);
        binding.activateRelease = new CountDownLatch(1);
        var session = activatedProductsBeforeFailure(events, binding);
        var activationFailure = new AtomicReference<Throwable>();
        var activationThread = new Thread(() -> {
            try {
                session.activate();
            } catch (Throwable failure) {
                activationFailure.set(failure);
            }
        });

        activationThread.start();
        assertTrue(binding.activateStarted.await(2, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, session::cancel);
        assertThrows(IllegalStateException.class, session::closeBindings);
        assertEquals(0, binding.cancelCalls);
        assertEquals(0, binding.closeCalls);
        binding.activateRelease.countDown();
        activationThread.join(2_000);

        assertTrue(!activationThread.isAlive());
        assertNull(activationFailure.get());
        session.cancel();
        session.closeBindings();
        assertEquals(1, binding.cancelCalls,
                "rejected overlapping cancel does not consume the one-shot");
        assertEquals(1, binding.closeCalls,
                "rejected overlapping close does not consume the one-shot");
    }

    @Test
    void projectAndJdbcRolesBindInEitherOrientationButSameRolesDecline()
            throws Exception {
        var orientations = new ArrayList<String>();
        var bindings = new ArrayList<RecordingBinding>();
        var key = new ComparisonExtensionKey<RoutineRoleEndpoint>(
                "role-matrix", RoutineRoleEndpoint.class, (oldEndpoint, newEndpoint) -> {
                    if (oldEndpoint.getClass() == newEndpoint.getClass()) {
                        orientations.add("declined " + oldEndpoint.role()
                                + "/" + newEndpoint.role());
                        return Optional.empty();
                    }
                    orientations.add("bound " + oldEndpoint.role()
                            + "/" + newEndpoint.role());
                    var binding = new RecordingBinding(
                            oldEndpoint.role() + "-" + newEndpoint.role(), orientations);
                    bindings.add(binding);
                    return Optional.of(binding);
                });

        activateRolePair(key, new ProjectEndpoint(), new JdbcEndpoint());
        activateRolePair(key, new JdbcEndpoint(), new ProjectEndpoint());
        activateRolePair(key, new ProjectEndpoint(), new ProjectEndpoint());

        assertEquals(List.of(
                "bound project/jdbc", "project-jdbc activate", "project-jdbc close",
                "bound jdbc/project", "jdbc-project activate", "jdbc-project close",
                "declined project/project"), orientations);
        assertEquals(2, bindings.size());
        assertEquals(1, bindings.get(0).activateCalls);
        assertEquals(1, bindings.get(1).activateCalls);
    }

    @Test
    void cleanupCallbacksAttemptEveryBindingAndSuppressInOrder() throws Exception {
        var events = new ArrayList<String>();
        var first = new RecordingBinding("first", events);
        var second = new RecordingBinding("second", events);
        var third = new RecordingBinding("third", events);
        var firstCancel = new IOException("first cancel");
        var secondCancel = new IllegalStateException("second cancel");
        var firstClose = new IOException("first close");
        var thirdClose = new AssertionError("third close");
        first.cancelFailure = firstCancel;
        second.cancelFailure = secondCancel;
        first.closeFailure = firstClose;
        third.closeFailure = thirdClose;
        var session = activatedSession(events, first, second, third);

        List<Throwable> cancelFailures = session.cancel();
        assertEquals(List.of(firstCancel, secondCancel), cancelFailures);
        List<Throwable> closeFailures = session.closeBindings();
        assertEquals(List.of(firstClose, thirdClose), closeFailures);

        assertDoesNotThrow(session::cancel, "failed cancellation remains one-shot");
        assertDoesNotThrow(session::closeBindings, "failed close remains one-shot");
        assertEquals(1, first.cancelCalls);
        assertEquals(1, second.cancelCalls);
        assertEquals(1, third.cancelCalls);
        assertEquals(1, first.closeCalls);
        assertEquals(1, second.closeCalls);
        assertEquals(1, third.closeCalls);
    }

    @Test
    void sideFailureCleanupAttemptsEveryBindingAndSuppressesInOrder() throws Exception {
        var events = new ArrayList<String>();
        var first = new RecordingBinding("first", events);
        var second = new RecordingBinding("second", events);
        var firstFailure = new IOException("first sideFailed");
        var secondFailure = new IllegalStateException("second sideFailed");
        first.sideFailedFailure = firstFailure;
        second.sideFailedFailure = secondFailure;
        var session = activatedSession(events, first, second);
        var workerFailure = new IOException("worker");

        List<Throwable> callbackFailures =
                session.sideFailed(ComparisonSide.OLD, workerFailure);

        assertEquals(List.of(firstFailure, secondFailure), callbackFailures);
        assertDoesNotThrow(() -> session.sideFailed(ComparisonSide.OLD, workerFailure));
        assertEquals(1, first.sideFailedCalls);
        assertEquals(1, second.sideFailedCalls);
    }

    @Test
    void lifecycleDeliveryBeforeActiveIsRejectedWithoutConsumingOneShot() throws Exception {
        var events = new ArrayList<String>();
        var binding = new RecordingBinding("binding", events);
        var session = new ComparisonExtensionSession();
        registerPair(session, key("binding", binding, events), "binding");
        sealBoth(session);

        assertThrows(IllegalStateException.class,
                () -> session.sideLoaded(ComparisonSide.OLD, null));
        assertThrows(IllegalStateException.class,
                () -> session.sideFailed(ComparisonSide.NEW, new IOException("early")));
        session.activate();
        session.sideLoaded(ComparisonSide.OLD,
                org.mockito.Mockito.mock(IDatabase.class));
        session.sideFailed(ComparisonSide.NEW, new IOException("actual"));

        assertEquals(1, binding.sideLoadedCalls);
        assertEquals(1, binding.sideFailedCalls);
    }

    @Test
    void nullDatabaseDoesNotConsumeLoadedOneShotForNonemptySession() throws Exception {
        var events = new ArrayList<String>();
        var binding = new RecordingBinding("binding", events);
        var session = activatedSession(events, binding);
        IDatabase database = org.mockito.Mockito.mock(IDatabase.class);

        assertThrows(NullPointerException.class,
                () -> session.sideLoaded(ComparisonSide.OLD, null));
        session.sideLoaded(ComparisonSide.OLD, database);

        assertEquals(1, binding.sideLoadedCalls);
    }

    @Test
    void bindersAndBindingHooksRunWithoutSessionLock() throws Exception {
        var events = new ArrayList<String>();
        var session = new ComparisonExtensionSession();
        var probeFailure = new AtomicReference<Throwable>();
        var probeKey = new ComparisonExtensionKey<String>(
                "probe", String.class, (oldValue, newValue) -> {
                    probeConcurrentRejectedRegistration(session, probeFailure);
                    return Optional.of(new RecordingBinding("probe", events) {
                        @Override
                        public void sideLoaded(ComparisonSide side, IDatabase database)
                                throws InterruptedException {
                            probeConcurrentRejectedRegistration(session, probeFailure);
                            super.sideLoaded(side, database);
                        }
                    });
                });
        registerPair(session, probeKey, "probe");
        sealBoth(session);

        session.activate();
        session.sideLoaded(ComparisonSide.OLD,
                org.mockito.Mockito.mock(IDatabase.class));

        assertSame(IllegalStateException.class, probeFailure.get().getClass());
    }

    @Test
    void emptySessionLifecycleIsSilentAndIdempotent() {
        var session = new ComparisonExtensionSession();
        sealBoth(session);

        assertDoesNotThrow(session::activate);
        assertDoesNotThrow(() -> session.sideLoaded(ComparisonSide.OLD, null));
        assertDoesNotThrow(() -> session.sideLoaded(ComparisonSide.NEW, null));
        assertDoesNotThrow(() -> session.sideFailed(
                ComparisonSide.OLD, new IOException("ignored")));
        assertDoesNotThrow(session::cancel);
        assertDoesNotThrow(session::closeBindings);
    }

    private static ComparisonExtensionKey<String> key(String name,
            RecordingBinding binding, List<String> events) {
        return new ComparisonExtensionKey<>(name, String.class, (oldValue, newValue) -> {
            events.add(binding.name + " bind " + oldValue + "/" + newValue);
            return Optional.of(binding);
        });
    }

    private static ComparisonExtensionSession activatedSession(
            List<String> events, RecordingBinding... bindings) throws Exception {
        var session = new ComparisonExtensionSession();
        for (RecordingBinding binding : bindings) {
            registerPair(session, key(binding.name, binding, events), binding.name);
        }
        sealBoth(session);
        session.activate();
        return session;
    }

    private static ComparisonExtensionSession activatedProductsBeforeFailure(
            List<String> events, RecordingBinding... bindings) {
        var session = new ComparisonExtensionSession();
        for (RecordingBinding binding : bindings) {
            registerPair(session, key(binding.name, binding, events), binding.name);
        }
        sealBoth(session);
        return session;
    }

    private static void registerPair(ComparisonExtensionSession session,
            ComparisonExtensionKey<String> key, String value) {
        session.context(ComparisonSide.OLD).register(key, "old-" + value);
        session.context(ComparisonSide.NEW).register(key, "new-" + value);
    }

    private static void activateRolePair(
            ComparisonExtensionKey<RoutineRoleEndpoint> key,
            RoutineRoleEndpoint oldEndpoint,
            RoutineRoleEndpoint newEndpoint) throws Exception {
        var session = new ComparisonExtensionSession();
        session.context(ComparisonSide.OLD).register(key, oldEndpoint);
        session.context(ComparisonSide.NEW).register(key, newEndpoint);
        sealBoth(session);
        session.activate();
        session.closeBindings();
    }

    private static void sealBoth(ComparisonExtensionSession session) {
        session.seal(ComparisonSide.OLD);
        session.seal(ComparisonSide.NEW);
    }

    private static void probeConcurrentRejectedRegistration(
            ComparisonExtensionSession session, AtomicReference<Throwable> result)
            throws InterruptedException {
        var thread = new Thread(() -> {
            try {
                session.context(ComparisonSide.OLD).register(
                        new ComparisonExtensionKey<String>(
                                "late", String.class, (oldValue, newValue) -> Optional.empty()),
                        "late");
            } catch (Throwable failure) {
                result.set(failure);
            }
        });
        thread.start();
        thread.join(2_000);
        assertTrue(!thread.isAlive(), "session lock was held while user callback ran");
    }

    private static class RecordingBinding implements IComparisonExtensionBinding {

        private final String name;
        private final List<String> events;

        private int sideLoadedCalls;
        private int sideFailedCalls;
        private int activateCalls;
        private int cancelCalls;
        private int closeCalls;
        private Throwable activateFailure;
        private Throwable sideFailedFailure;
        private Throwable cancelFailure;
        private Throwable closeFailure;
        private CountDownLatch activateStarted = new CountDownLatch(0);
        private CountDownLatch activateRelease = new CountDownLatch(0);

        private RecordingBinding(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void activate() throws IOException, InterruptedException {
            activateCalls++;
            events.add(name + " activate");
            activateStarted.countDown();
            if (!activateRelease.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("activation release timed out");
            }
            throwFailure(activateFailure);
        }

        @Override
        public void sideLoaded(ComparisonSide side, IDatabase database)
                throws InterruptedException {
            sideLoadedCalls++;
            events.add(name + " loaded " + side);
        }

        @Override
        public void sideFailed(ComparisonSide side, Throwable failure) throws IOException {
            sideFailedCalls++;
            events.add(name + " failed " + side + " " + failure.getMessage());
            throwFailure(sideFailedFailure);
        }

        @Override
        public void cancel() throws IOException {
            cancelCalls++;
            events.add(name + " cancel");
            throwFailure(cancelFailure);
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            events.add(name + " close");
            throwFailure(closeFailure);
        }

        private static void throwFailure(Throwable failure) throws IOException {
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private sealed interface RoutineRoleEndpoint
            permits ProjectEndpoint, JdbcEndpoint {

        String role();
    }

    private record ProjectEndpoint() implements RoutineRoleEndpoint {

        @Override
        public String role() {
            return "project";
        }
    }

    private record JdbcEndpoint() implements RoutineRoleEndpoint {

        @Override
        public String role() {
            return "jdbc";
        }
    }
}
