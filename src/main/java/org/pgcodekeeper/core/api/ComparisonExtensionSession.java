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

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionContext;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionKey;
import org.pgcodekeeper.core.database.api.loader.ComparisonSide;
import org.pgcodekeeper.core.database.api.loader.IComparisonExtensionBinding;
import org.pgcodekeeper.core.database.api.schema.IDatabase;

/** Operation-local lifecycle owner hidden from participating loaders. */
final class ComparisonExtensionSession {

    private final Object lock = new Object();
    private final IdentityHashMap<ComparisonExtensionKey<?>, EndpointPair> endpoints =
            new IdentityHashMap<>();
    private final List<ComparisonExtensionKey<?>> keyOrder = new ArrayList<>();
    private final List<IComparisonExtensionBinding> bindings = new ArrayList<>();
    private final SideContext oldContext = new SideContext(ComparisonSide.OLD);
    private final SideContext newContext = new SideContext(ComparisonSide.NEW);
    private final AtomicBoolean oldLoaded = new AtomicBoolean();
    private final AtomicBoolean newLoaded = new AtomicBoolean();
    private final AtomicBoolean oldFailed = new AtomicBoolean();
    private final AtomicBoolean newFailed = new AtomicBoolean();
    private final AtomicBoolean cancelStarted = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();

    private State state = State.REGISTERING;
    private boolean oldSealed;
    private boolean newSealed;

    ComparisonExtensionContext context(ComparisonSide side) {
        return Objects.requireNonNull(side, "side") == ComparisonSide.OLD
                ? oldContext
                : newContext;
    }

    void seal(ComparisonSide side) {
        Objects.requireNonNull(side, "side");
        synchronized (lock) {
            requireState(State.REGISTERING, "seal registration");
            if (side == ComparisonSide.OLD) {
                if (oldSealed) {
                    throw new IllegalStateException("OLD extension registration is already sealed");
                }
                oldSealed = true;
            } else {
                if (newSealed) {
                    throw new IllegalStateException("NEW extension registration is already sealed");
                }
                newSealed = true;
            }
        }
    }

    void activate() throws IOException, InterruptedException {
        List<Negotiation> negotiations;
        synchronized (lock) {
            requireState(State.REGISTERING, "activate");
            if (!oldSealed || !newSealed) {
                throw new IllegalStateException(
                        "Both comparison extension registrations must be sealed");
            }
            state = State.ACTIVATING;
            negotiations = snapshotNegotiations();
            endpoints.clear();
            keyOrder.clear();
        }

        var products = new IdentityHashMap<IComparisonExtensionBinding, Boolean>();
        try {
            for (Negotiation negotiation : negotiations) {
                if (!negotiation.complete()) {
                    continue;
                }
                Optional<? extends IComparisonExtensionBinding> candidate =
                        Objects.requireNonNull(bind(negotiation),
                                "comparison extension binding Optional");
                if (candidate.isEmpty()) {
                    continue;
                }
                IComparisonExtensionBinding binding = Objects.requireNonNull(
                        candidate.get(), "comparison extension binding");
                if (products.put(binding, Boolean.TRUE) != null) {
                    throw new IllegalArgumentException(
                            "Comparison extension keys returned the same binding instance");
                }
                synchronized (lock) {
                    bindings.add(binding);
                }
            }
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            markActivationFailed();
            throw failure;
        } finally {
            negotiations.clear();
        }

        try {
            for (IComparisonExtensionBinding binding : snapshotBindings()) {
                binding.activate();
            }
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            markActivationFailed();
            throw failure;
        }
        synchronized (lock) {
            state = State.ACTIVE;
        }
    }

    void sideLoaded(ComparisonSide side, IDatabase database)
            throws IOException, InterruptedException {
        Objects.requireNonNull(side, "side");
        List<IComparisonExtensionBinding> snapshot = activeBindings();
        if (!snapshot.isEmpty()) {
            Objects.requireNonNull(database, "database");
        }
        AtomicBoolean delivered = side == ComparisonSide.OLD ? oldLoaded : newLoaded;
        if (!delivered.compareAndSet(false, true)) {
            return;
        }
        for (IComparisonExtensionBinding binding : snapshot) {
            binding.sideLoaded(side, database);
        }
    }

    List<Throwable> sideFailed(ComparisonSide side, Throwable failure) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(failure, "failure");
        List<IComparisonExtensionBinding> snapshot = activeBindings();
        AtomicBoolean delivered = side == ComparisonSide.OLD ? oldFailed : newFailed;
        if (!delivered.compareAndSet(false, true)) {
            return List.of();
        }
        var failures = new ArrayList<Throwable>();
        for (IComparisonExtensionBinding binding : snapshot) {
            try {
                binding.sideFailed(side, failure);
            } catch (Throwable callbackFailure) {
                addIdentity(failures, callbackFailure);
            }
        }
        return List.copyOf(failures);
    }

    List<Throwable> cancel() {
        var failures = new ArrayList<Throwable>();
        cancelInto(failures::add);
        return List.copyOf(failures);
    }

    void cancelInto(Consumer<? super Throwable> failureSink) {
        Objects.requireNonNull(failureSink, "failureSink");
        List<IComparisonExtensionBinding> snapshot;
        synchronized (lock) {
            if (state == State.ACTIVATING) {
                throw new IllegalStateException(
                        "Cannot cancel comparison extensions while activating");
            }
            if (!cancelStarted.compareAndSet(false, true)) {
                return;
            }
            if (state == State.REGISTERING) {
                state = State.CANCELLED;
                endpoints.clear();
                keyOrder.clear();
            }
            snapshot = List.copyOf(bindings);
        }
        var published = new ArrayList<Throwable>();
        for (IComparisonExtensionBinding binding : snapshot) {
            try {
                binding.cancel();
            } catch (Throwable callbackFailure) {
                if (addIdentity(published, callbackFailure)) {
                    failureSink.accept(callbackFailure);
                }
            }
        }
    }

    List<Throwable> closeBindings() {
        List<IComparisonExtensionBinding> snapshot;
        synchronized (lock) {
            if (state == State.ACTIVATING) {
                throw new IllegalStateException(
                        "Cannot close comparison extensions while activating");
            }
            if (!closeStarted.compareAndSet(false, true)) {
                return List.of();
            }
            snapshot = List.copyOf(bindings);
            bindings.clear();
            endpoints.clear();
            keyOrder.clear();
            state = State.CLOSED;
        }
        var failures = new ArrayList<Throwable>();
        for (IComparisonExtensionBinding binding : snapshot) {
            try {
                binding.close();
            } catch (Throwable callbackFailure) {
                addIdentity(failures, callbackFailure);
            }
        }
        return List.copyOf(failures);
    }

    int retainedEndpointCount() {
        synchronized (lock) {
            return endpoints.values().stream().mapToInt(EndpointPair::size).sum();
        }
    }

    int retainedBindingCount() {
        synchronized (lock) {
            return bindings.size();
        }
    }

    private void register(ComparisonSide side,
            ComparisonExtensionKey<?> key, Object endpoint) {
        Objects.requireNonNull(key, "key");
        Object typedEndpoint = key.castEndpoint(endpoint);
        synchronized (lock) {
            requireState(State.REGISTERING, "register");
            if (side == ComparisonSide.OLD ? oldSealed : newSealed) {
                throw new IllegalStateException(side + " extension registration is sealed");
            }
            EndpointPair pair = endpoints.get(key);
            if (pair == null) {
                pair = new EndpointPair();
                endpoints.put(key, pair);
                keyOrder.add(key);
            }
            if (!pair.register(side, typedEndpoint)) {
                throw new IllegalArgumentException(
                        "Duplicate " + side + " endpoint for comparison extension " + key);
            }
        }
    }

    private List<Negotiation> snapshotNegotiations() {
        var snapshot = new ArrayList<Negotiation>(keyOrder.size());
        for (ComparisonExtensionKey<?> key : keyOrder) {
            EndpointPair pair = endpoints.get(key);
            snapshot.add(new Negotiation(key, pair.oldEndpoint, pair.newEndpoint));
        }
        return snapshot;
    }

    private List<IComparisonExtensionBinding> activeBindings() {
        synchronized (lock) {
            requireState(State.ACTIVE, "deliver lifecycle callback");
            return List.copyOf(bindings);
        }
    }

    private List<IComparisonExtensionBinding> snapshotBindings() {
        synchronized (lock) {
            return List.copyOf(bindings);
        }
    }

    private void markActivationFailed() {
        synchronized (lock) {
            state = State.FAILED;
        }
    }

    private void requireState(State expected, String operation) {
        if (state != expected) {
            throw new IllegalStateException(
                    "Cannot " + operation + " comparison extensions in state " + state);
        }
    }

    private static Optional<? extends IComparisonExtensionBinding> bind(
            Negotiation negotiation) throws IOException, InterruptedException {
        return bindCaptured(
                negotiation.key, negotiation.oldEndpoint, negotiation.newEndpoint);
    }

    private static <E> Optional<? extends IComparisonExtensionBinding> bindCaptured(
            ComparisonExtensionKey<E> key, Object oldEndpoint, Object newEndpoint)
            throws IOException, InterruptedException {
        return key.bind(
                key.castEndpoint(oldEndpoint), key.castEndpoint(newEndpoint));
    }

    private static boolean addIdentity(
            List<Throwable> failures, Throwable candidate) {
        for (Throwable failure : failures) {
            if (failure == candidate) {
                return false;
            }
        }
        failures.add(candidate);
        return true;
    }

    private final class SideContext implements ComparisonExtensionContext {

        private final ComparisonSide side;

        private SideContext(ComparisonSide side) {
            this.side = side;
        }

        @Override
        public ComparisonSide side() {
            return side;
        }

        @Override
        public <E> void register(ComparisonExtensionKey<E> key, E endpoint) {
            ComparisonExtensionSession.this.register(side, key, endpoint);
        }
    }

    private static final class EndpointPair {

        private Object oldEndpoint;
        private Object newEndpoint;

        private boolean register(ComparisonSide side, Object endpoint) {
            if (side == ComparisonSide.OLD) {
                if (oldEndpoint != null || newEndpoint == endpoint) {
                    return false;
                }
                oldEndpoint = endpoint;
            } else {
                if (newEndpoint != null || oldEndpoint == endpoint) {
                    return false;
                }
                newEndpoint = endpoint;
            }
            return true;
        }

        private int size() {
            return (oldEndpoint == null ? 0 : 1) + (newEndpoint == null ? 0 : 1);
        }
    }

    private record Negotiation(
            ComparisonExtensionKey<?> key,
            Object oldEndpoint,
            Object newEndpoint) {

        private boolean complete() {
            return oldEndpoint != null && newEndpoint != null;
        }
    }

    private enum State {
        REGISTERING,
        ACTIVATING,
        ACTIVE,
        FAILED,
        CANCELLED,
        CLOSED
    }
}
