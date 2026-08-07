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

/**
 * Side-bound registration facade supplied to one comparison loader.
 * <p>
 * The facade deliberately exposes no lifecycle controls, so a loader cannot
 * cancel, complete, or close peer-owned coordination state. Registration is
 * valid only while the loader's registration hook is executing.
 */
public interface ComparisonExtensionContext {

    /**
     * @return logical side permanently bound to this facade
     */
    ComparisonSide side();

    /**
     * Registers one typed endpoint for this facade's logical side.
     *
     * @param key shared identity key
     * @param endpoint endpoint owned by this loader
     * @param <E> endpoint type
     * @throws IllegalArgumentException if this key already has an endpoint for
     *         the side, the endpoint has the wrong runtime type, or the same
     *         endpoint object is already registered for the peer side of this key
     * @throws IllegalStateException if registration for this side is sealed
     */
    <E> void register(ComparisonExtensionKey<E> key, E endpoint);
}
