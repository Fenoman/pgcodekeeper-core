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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCatalog;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCatalogChannel;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

class PgRoutineBodyCoordinatorFailureTest {

    @Test
    @Timeout(5)
    void catalogBuildFailureStaysPrimaryWhileJdbcConsumerIsWaiting()
            throws Exception {
        var waiterEntered = new CountDownLatch(1);
        RuntimeException projectFailure = new RuntimeException(
                "controlled project catalog failure");
        var factories = new ComparisonLoaderFactories(
                settings -> new FailingCatalogProjectLoader(
                        settings, waiterEntered, projectFailure),
                settings -> new WaitingJdbcLoader(settings, waiterEntered));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> PgCodeKeeperApi.diff(
                        new PgDatabaseProvider(), factories, new CoreSettings()));

        assertSame(projectFailure, thrown);
    }

    private static final class FailingCatalogProjectLoader extends PgProjectLoader {

        private final CountDownLatch waiterEntered;
        private final RuntimeException failure;

        private FailingCatalogProjectLoader(ISettings settings,
                CountDownLatch waiterEntered, RuntimeException failure) {
            super(Path.of("controlled-project"), settings);
            this.waiterEntered = waiterEntered;
            this.failure = failure;
        }

        @Override
        public void preLoad() {
            // No project configuration is needed for this lifecycle oracle.
        }

        @Override
        public PgDatabase loadInternal() throws InterruptedException {
            if (!waiterEntered.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JDBC consumer did not start waiting");
            }
            return new PgDatabase() {
                @Override
                public List<IAnalysisLauncher> getAnalysisLaunchers() {
                    throw failure;
                }
            };
        }
    }

    private static final class WaitingJdbcLoader extends PgJdbcLoader {

        private final CountDownLatch waiterEntered;
        private ProjectRoutineBodyCatalogChannel channel;

        private WaitingJdbcLoader(ISettings settings, CountDownLatch waiterEntered) {
            super(mock(IJdbcConnector.class), Consts.UTC, settings);
            this.waiterEntered = waiterEntered;
        }

        @Override
        public void preLoad() {
            // The test exercises only the comparison-extension handoff.
        }

        @Override
        protected boolean requestRoutineBodyExchange() {
            return true;
        }

        @Override
        synchronized void attachProjectRoutineBodyCatalog(
                ProjectRoutineBodyCatalogChannel value,
                org.pgcodekeeper.core.database.api.loader.ComparisonSide jdbcSide) {
            super.attachProjectRoutineBodyCatalog(value, jdbcSide);
            channel = value;
        }

        @Override
        public PgDatabase loadInternal() throws InterruptedException {
            ProjectRoutineBodyCatalogChannel current = channel;
            if (current == null) {
                throw new IllegalStateException("Project catalog channel was not attached");
            }
            var monitor = new NullMonitor() {
                @Override
                public boolean isCancelled() {
                    waiterEntered.countDown();
                    return false;
                }
            };
            try (ProjectRoutineBodyCatalog ignored = current.take(monitor)) {
                return new PgDatabase();
            }
        }

        @Override
        protected void releaseLoadResources() {
            try {
                super.releaseLoadResources();
            } finally {
                channel = null;
            }
        }
    }
}
