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

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgDumpLoaderTest {

    /**
     * A body the grammar cannot read yields errors and leaves no launcher
     * behind.
     *
     * <p>Upstream answers both questions during {@code load()}, because it
     * parses every routine body while reading the CREATE statement. This
     * branch defers that parse: the body travels into the launcher as text and
     * is read when the analysis runs, which is what lets an unchanged body skip
     * being parsed at all. Nothing is lost, it is simply answered one phase
     * later -- five errors and no launcher, the same verdict upstream reaches
     * one step sooner.</p>
     *
     * <p>Asking after {@code load()} alone would assert the timing rather than
     * the behaviour, and would pass only for a loader that does the work this
     * one exists to avoid.</p>
     */
    @Test
    void skipAddAnalysisLauncherTest() throws IOException, InterruptedException {
        String resource = "broken_procedure.sql";
        var settings = new CoreSettings();
        settings.setEnableFunctionBodiesDependencies(true);
        var l = new PgDatabaseProvider().getDumpLoader(TestUtils.getFilePath(resource, getClass()), settings);
        var db = l.loadAndAnalyze();
        assertFalse(l.getErrors().isEmpty());
        assertTrue(db.getAnalysisLaunchers().isEmpty());
    }
}
