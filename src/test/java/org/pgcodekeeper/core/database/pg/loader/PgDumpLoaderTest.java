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

    @Test
    void skipAddAnalysisLauncherTest() throws IOException, InterruptedException {
        String resource = "broken_procedure.sql";
        var settings = new CoreSettings();
        settings.setEnableFunctionBodiesDependencies(true);
        var l = new PgDatabaseProvider().getDumpLoader(TestUtils.getFilePath(resource, getClass()), settings);
        var db = l.load();
        assertFalse(l.getErrors().isEmpty());
        assertTrue(db.getAnalysisLaunchers().isEmpty());
    }
}
