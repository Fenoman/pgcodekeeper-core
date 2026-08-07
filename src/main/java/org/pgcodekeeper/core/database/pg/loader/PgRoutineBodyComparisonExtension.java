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
import java.util.Optional;

import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionContext;
import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionKey;
import org.pgcodekeeper.core.database.api.loader.ComparisonSide;
import org.pgcodekeeper.core.database.api.loader.IComparisonExtensionBinding;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCatalogChannel;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCatalogPublisher;
import org.pgcodekeeper.core.database.pg.routine.ReusableProjectRoutineBodySnapshot;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;

/** Comparison-local project producer to PostgreSQL JDBC body channel. */
final class PgRoutineBodyComparisonExtension {

    static final ComparisonExtensionKey<Endpoint> KEY = new ComparisonExtensionKey<>(
            "PostgreSQL routine body exchange", Endpoint.class,
            PgRoutineBodyComparisonExtension::bind);

    private PgRoutineBodyComparisonExtension() {
    }

    static void registerProject(ComparisonExtensionContext context) {
        context.register(KEY, new ProjectEndpoint());
    }

    static void registerProject(ComparisonExtensionContext context,
            ReusableProjectRoutineBodySnapshot snapshot) {
        context.register(KEY, new ProjectEndpoint(
                Objects.requireNonNull(snapshot, "snapshot")));
    }

    static void registerJdbc(ComparisonExtensionContext context, PgJdbcLoader loader) {
        context.register(KEY, new JdbcEndpoint(loader));
    }

    private static Optional<? extends IComparisonExtensionBinding> bind(
            Endpoint oldEndpoint, Endpoint newEndpoint) {
        if (oldEndpoint instanceof ProjectEndpoint project
                && newEndpoint instanceof JdbcEndpoint jdbc) {
            return Optional.of(new Binding(
                    ComparisonSide.OLD, project, jdbc.loader()));
        }
        if (oldEndpoint instanceof JdbcEndpoint jdbc
                && newEndpoint instanceof ProjectEndpoint project) {
            return Optional.of(new Binding(
                    ComparisonSide.NEW, project, jdbc.loader()));
        }
        return Optional.empty();
    }

    sealed interface Endpoint permits ProjectEndpoint, JdbcEndpoint {
    }

    record ProjectEndpoint(
            ReusableProjectRoutineBodySnapshot snapshot) implements Endpoint {

        ProjectEndpoint() {
            this(null);
        }
    }

    record JdbcEndpoint(PgJdbcLoader loader) implements Endpoint {
        JdbcEndpoint {
            Objects.requireNonNull(loader, "loader");
        }
    }

    private static final class Binding implements IComparisonExtensionBinding {

        private final ComparisonSide projectSide;
        private final ProjectEndpoint projectEndpoint;
        private final PgJdbcLoader jdbcLoader;
        private final ProjectRoutineBodyCatalogChannel channel =
                new ProjectRoutineBodyCatalogChannel();
        private boolean attached;

        private Binding(ComparisonSide projectSide,
                ProjectEndpoint projectEndpoint, PgJdbcLoader jdbcLoader) {
            this.projectSide = Objects.requireNonNull(projectSide, "projectSide");
            this.projectEndpoint = Objects.requireNonNull(
                    projectEndpoint, "projectEndpoint");
            this.jdbcLoader = Objects.requireNonNull(jdbcLoader, "jdbcLoader");
        }

        @Override
        public void activate() {
            channel.open();
            try {
                // the JDBC endpoint occupies the side opposite to the project
                ComparisonSide jdbcSide = projectSide == ComparisonSide.OLD
                        ? ComparisonSide.NEW
                        : ComparisonSide.OLD;
                jdbcLoader.attachProjectRoutineBodyCatalog(channel, jdbcSide);
                attached = true;
            } catch (RuntimeException | Error ex) {
                channel.close();
                throw ex;
            }
        }

        @Override
        public void sideLoaded(ComparisonSide side, IDatabase database) {
            if (side != projectSide) {
                return;
            }
            if (!(database instanceof PgDatabase pgDatabase)) {
                throw new IllegalArgumentException(
                        "PostgreSQL routine body producer returned a non-PostgreSQL database");
            }
            ReusableProjectRoutineBodySnapshot snapshot =
                    projectEndpoint.snapshot();
            if (snapshot == null) {
                ProjectRoutineBodyCatalogPublisher.publishForComparison(
                        channel, pgDatabase);
            } else {
                channel.publishIfOpen(snapshot.newCatalog(pgDatabase));
            }
        }

        @Override
        public void sideFailed(ComparisonSide side, Throwable failure) {
            // The coordinator already owns the exact side failure. Wake the peer
            // with an induced cancellation so it cannot race the primary cause.
            channel.cancel();
        }

        @Override
        public void cancel() {
            channel.cancel();
        }

        @Override
        public void close() {
            try {
                if (attached) {
                    attached = false;
                    jdbcLoader.detachProjectRoutineBodyCatalog(channel);
                }
            } finally {
                channel.close();
            }
        }
    }
}
