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

import org.pgcodekeeper.core.database.api.schema.IDatabase;
import java.util.Collection;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.pgcodekeeper.core.database.api.loader.ComparisonExtensionContext;
import org.pgcodekeeper.core.database.api.loader.IProjectLoader;
import org.pgcodekeeper.core.database.pg.routine.ReusableProjectRoutineBodySnapshot;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Project loader backed by one already analyzed PostgreSQL model.
 * <p>
 * The retained model is not generally immutable. Callers must hold an
 * exclusive lease for its exact generation until the coordinated comparison
 * and all diff consumers have finished.
 */
public final class PreanalyzedProjectLoader implements IProjectLoader {

    private enum State {
        OPEN,
        CANCELLED,
        CLOSED
    }

    private final PgDatabase database;
    private final ReusableProjectRoutineBodySnapshot routineSnapshot;
    private final ISettings settings;
    private final String databaseName;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private volatile boolean loaded;

    public PreanalyzedProjectLoader(PgDatabase database,
            ReusableProjectRoutineBodySnapshot routineSnapshot,
            ISettings settings, String databaseName) {
        this.database = Objects.requireNonNull(database, "database");
        this.routineSnapshot = Objects.requireNonNull(
                routineSnapshot, "routineSnapshot");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.databaseName = Objects.requireNonNull(databaseName, "databaseName");
        if (!database.getAnalysisLaunchers().isEmpty()) {
            throw new IllegalArgumentException(
                    "Reusable project model must be fully analyzed");
        }
        if (!routineSnapshot.isCompatibleWith(database)) {
            throw new IllegalArgumentException(
                    "Routine snapshot does not match the reusable project model");
        }
        // Additional schema exclusions change which project files are loaded,
        // so they are part of the model identity. The reuse key is owned by
        // the caller; fail loudly here instead of silently serving a model
        // that was built under a different exclusion set.
        if (!settings.getAdditionalExcludedSchemas().isEmpty()) {
            throw new IllegalArgumentException(
                    "Reusable project models are not supported with additional schema exclusions");
        }
    }

    @Override
    public PgDatabase load() throws InterruptedException {
        requireOpen();
        loaded = true;
        return database;
    }

    @Override
    public PgDatabase loadAndAnalyze() throws InterruptedException {
        requireOpen();
        loaded = true;
        return database;
    }

    /**
     * Loading a subset of files is not something this loader can answer.
     *
     * <p>It exists to hand back one model that was already analysed as a whole,
     * under a lease its caller holds. Reading a few files off disk would produce
     * a second, unrelated database and quietly break that guarantee, so the
     * request is refused by name rather than served with something else.</p>
     */
    @Override
    public IDatabase loadFiles(Collection<Path> files) {
        throw new UnsupportedOperationException(
                "A preanalyzed project loader serves one whole model and cannot load selected files");
    }

    @Override
    public PgDatabase getDatabase() {
        return loaded ? database : null;
    }

    @Override
    public String getDatabaseName() {
        return databaseName;
    }

    @Override
    public ISettings getSettings() {
        return settings;
    }

    @Override
    public List<Object> getErrors() {
        return Collections.unmodifiableList(settings.getErrors());
    }

    @Override
    public void markCommonConfigurationContributed() {
        // The model already includes the acknowledged project configuration.
    }

    @Override
    public void registerComparisonExtensions(ComparisonExtensionContext context) {
        requireOpenUnchecked();
        PgRoutineBodyComparisonExtension.registerProject(
                context, routineSnapshot);
    }

    @Override
    public void cancel() {
        state.compareAndSet(State.OPEN, State.CANCELLED);
    }

    @Override
    public void close() {
        state.set(State.CLOSED);
    }

    private void requireOpen() throws InterruptedException {
        State current = state.get();
        if (current == State.CANCELLED) {
            throw new InterruptedException();
        }
        if (current == State.CLOSED) {
            throw new IllegalStateException("Preanalyzed project loader is closed");
        }
    }

    private void requireOpenUnchecked() {
        State current = state.get();
        if (current != State.OPEN) {
            throw new IllegalStateException(
                    "Preanalyzed project loader is not open: " + current);
        }
    }
}
