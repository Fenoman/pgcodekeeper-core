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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * The structural phase registers an analysis launcher per analyzable statement,
 * and a launcher pins its parser context - through the context's tokens, the
 * character stream of a whole source file. Only the analysis and the analysis
 * replay release them, and a {@link ComparisonDepth#STRUCTURAL_ONLY} load
 * reaches neither, so the model handed back would carry every parsed file for
 * as long as its caller keeps it.
 */
class StructuralLoadLauncherReleaseTest {

    @Test
    void structuralLoadReleasesAndDropsEveryLauncher() throws Exception {
        var oldSide = new SideFixture();
        var newSide = new SideFixture();

        var loaded = PgCodeKeeperApi.loadForComparison(
                factories(oldSide, newSide), new CoreSettings(),
                ComparisonDepth.STRUCTURAL_ONLY);

        assertAll(
                () -> assertEquals(1, oldSide.launcher.released,
                        "OLD launcher must be told its analysis will never run"),
                () -> assertEquals(1, newSide.launcher.released,
                        "NEW launcher must be told its analysis will never run"),
                () -> assertTrue(loaded.oldDatabase().getAnalysisLaunchers().isEmpty(),
                        "OLD model must not keep launchers it will never analyze"),
                () -> assertTrue(loaded.newDatabase().getAnalysisLaunchers().isEmpty(),
                        "NEW model must not keep launchers it will never analyze"));
    }

    /**
     * The full path already has an owner for this cleanup - the analysis
     * itself - so the coordinator must not reach into it. The fixture skips the
     * real analysis, so a launcher surviving here proves the coordinator left
     * that path alone.
     */
    @Test
    void fullLoadLeavesLauncherCleanupToTheAnalysis() throws Exception {
        var oldSide = new SideFixture();
        var newSide = new SideFixture();

        var loaded = PgCodeKeeperApi.loadForComparison(
                factories(oldSide, newSide), new CoreSettings(),
                ComparisonDepth.FULL);

        assertAll(
                () -> assertEquals(0, oldSide.launcher.released,
                        "a full load releases launchers through its analysis, not here"),
                () -> assertFalse(loaded.oldDatabase().getAnalysisLaunchers().isEmpty(),
                        "the coordinator must not clear what the analysis owns"));
    }

    private static ComparisonLoaderFactories factories(SideFixture oldSide, SideFixture newSide) {
        return new ComparisonLoaderFactories(
                settings -> new FixtureLoader(oldSide, settings),
                settings -> new FixtureLoader(newSide, settings));
    }

    private static final class SideFixture {

        private final PgDatabase database = new PgDatabase();
        private final RecordingLauncher launcher = new RecordingLauncher();

        private SideFixture() {
            database.addAnalysisLauncher(launcher);
        }
    }

    /**
     * Counts the one call this test is about. Everything else is the minimum an
     * {@link IAnalysisLauncher} must answer.
     */
    private static final class RecordingLauncher implements IAnalysisLauncher {

        private int released;

        @Override
        public void releaseWithoutAnalysis() {
            released++;
        }

        @Override
        public IStatement getStmt() {
            return null;
        }

        @Override
        public void updateStmt(IDatabase database) {
            // nothing to rebind
        }

        @Override
        public java.util.Set<org.pgcodekeeper.core.database.api.schema.ObjectReference> launchAnalyze(
                List<Object> errors,
                org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer meta) {
            throw new AssertionError("this fixture never analyzes");
        }

        @Override
        public List<ObjectLocation> getReferences() {
            return List.of();
        }

        @Override
        public String getSchemaName() {
            return null;
        }
    }

    /**
     * Hands the coordinator a prebuilt model at either depth: the full path
     * returns the same model without running a real analysis, which is exactly
     * what makes the surviving launcher observable there.
     */
    private static final class FixtureLoader implements ILoader {

        private final SideFixture fixture;
        private final ISettings settings;

        private FixtureLoader(SideFixture fixture, ISettings settings) {
            this.fixture = fixture;
            this.settings = settings;
        }

        @Override
        public IDatabase load() {
            return fixture.database;
        }

        @Override
        public IDatabase loadAndAnalyze() {
            return fixture.database;
        }

        @Override
        public IDatabase getDatabase() {
            return fixture.database;
        }

        @Override
        public String getDatabaseName() {
            return "fixture";
        }

        @Override
        public ISettings getSettings() {
            return settings;
        }

        @Override
        public List<Object> getErrors() {
            return List.of();
        }
    }
}
