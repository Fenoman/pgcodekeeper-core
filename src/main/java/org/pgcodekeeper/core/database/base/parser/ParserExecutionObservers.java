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

import java.util.Objects;
import java.util.function.Supplier;

/** Installs an observer only for isolated package-level verification. */
final class ParserExecutionObservers {

    private static volatile Supplier<? extends ParserExecutionObserver> factory;

    static ParserExecutionObserver create() {
        Supplier<? extends ParserExecutionObserver> current = factory;
        return current == null ? null : current.get();
    }

    static AutoCloseable install(
            Supplier<? extends ParserExecutionObserver> observerFactory) {
        Objects.requireNonNull(observerFactory, "observerFactory");
        if (factory != null) {
            throw new IllegalStateException("Parser execution observer already installed");
        }
        factory = observerFactory;
        return () -> {
            if (factory != observerFactory) {
                throw new IllegalStateException(
                        "Parser execution observer installation changed");
            }
            factory = null;
        };
    }

    private ParserExecutionObservers() {
        // only static
    }
}
