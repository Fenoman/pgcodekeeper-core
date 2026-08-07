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
package org.pgcodekeeper.core.database.pg.loader;

import java.util.Objects;

import org.pgcodekeeper.core.database.base.loader.JdbcCatalogLane;

/** Optional package-local seam for catalog-lane cancellation verification. */
interface PgParallelCatalogReadersObserver {

    void laneStarted(JdbcCatalogLane lane);

    void laneDrainStarted(JdbcCatalogLane lane);

    final class Installation {

        private static volatile PgParallelCatalogReadersObserver observer;

        static PgParallelCatalogReadersObserver current() {
            return observer;
        }

        static AutoCloseable install(
                PgParallelCatalogReadersObserver installedObserver) {
            Objects.requireNonNull(installedObserver, "installedObserver");
            if (observer != null) {
                throw new IllegalStateException(
                        "Parallel catalog observer already installed");
            }
            observer = installedObserver;
            return () -> {
                if (observer != installedObserver) {
                    throw new IllegalStateException(
                            "Parallel catalog observer installation changed");
                }
                observer = null;
            };
        }

        private Installation() {
            // only static
        }
    }
}
