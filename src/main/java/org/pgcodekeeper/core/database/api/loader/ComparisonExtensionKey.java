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
package org.pgcodekeeper.core.database.api.loader;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Identity-based key for a typed pair of comparison extension endpoints.
 * <p>
 * A key is intentionally equal only to itself. Both participating loaders must
 * therefore register the same key instance. The binder always receives the
 * logical OLD endpoint first and the logical NEW endpoint second.
 *
 * @param <E> common endpoint type understood by this key's binder
 */
public final class ComparisonExtensionKey<E> {

    private final String name;
    private final Class<E> endpointType;
    private final Binder<E> binder;

    /**
     * Creates an identity key.
     *
     * @param name diagnostic name
     * @param endpointType runtime endpoint type token
     * @param binder typed endpoint binder
     */
    public ComparisonExtensionKey(String name, Class<E> endpointType, Binder<E> binder) {
        this.name = Objects.requireNonNull(name, "name");
        this.endpointType = Objects.requireNonNull(endpointType, "endpointType");
        this.binder = Objects.requireNonNull(binder, "binder");
    }

    /**
     * Validates and casts an endpoint using this key's runtime token.
     *
     * @param endpoint endpoint supplied through a registration facade
     * @return typed endpoint
     * @throws IllegalArgumentException if the endpoint type is incompatible
     */
    public E castEndpoint(Object endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpointType.isInstance(endpoint)) {
            throw new IllegalArgumentException("Comparison extension " + name
                    + " requires endpoint type " + endpointType.getName()
                    + " but received " + endpoint.getClass().getName());
        }
        return endpointType.cast(endpoint);
    }

    /**
     * Negotiates one complete, already type-checked endpoint pair.
     * Normal return transfers ownership of a present binding to the comparison
     * coordinator. If the binder throws before returning, the binder retains
     * ownership of every unpublished partial product.
     */
    public Optional<? extends IComparisonExtensionBinding> bind(
            E oldEndpoint, E newEndpoint)
            throws IOException, InterruptedException {
        return binder.bind(
                Objects.requireNonNull(oldEndpoint, "oldEndpoint"),
                Objects.requireNonNull(newEndpoint, "newEndpoint"));
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Negotiates a binding for one complete endpoint pair. Returning an empty
     * value declines the pair without installing a binding.
     *
     * @param <E> endpoint type
     */
    @FunctionalInterface
    public interface Binder<E> {

        Optional<? extends IComparisonExtensionBinding> bind(
                E oldEndpoint, E newEndpoint)
                throws IOException, InterruptedException;
    }
}
