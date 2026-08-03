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
package org.pgcodekeeper.core.it.diff.pg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.FILES_POSTFIX;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.it.IntegrationTestUtils;
import org.pgcodekeeper.core.library.Library;
import org.pgcodekeeper.core.library.LibraryXmlStore;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

class ProjectLibDiffTest {

    @Test
    void diffProjectWithLibraryIgnorePrivTest(@TempDir Path tempDir) throws IOException, InterruptedException {
        var provider = new PgDatabaseProvider();
        String template = "project_lib_ignore_priv";
        var settings = new CoreSettings();
        settings.setParallelLoad(true);
        Path projectDir = tempDir.resolve("project");

        String projectFile = template + "_project" + FILES_POSTFIX.SQL;
        IntegrationTestUtils.createProjectFromDump(provider, projectDir, projectFile, getClass());
        String libPath = TestUtils.getFilePath(template + FILES_POSTFIX.LIBRARY_SQL, getClass()).toString();
        Path depsFile = projectDir.resolve(LibraryXmlStore.FILE_NAME);
        new LibraryXmlStore(depsFile).writeDependencies(List.of(new Library("", libPath, true, "")), false);

        ILoader projectLoader = provider.getProjectLoader(projectDir, settings);

        String inputObjectName = template + "_db" + FILES_POSTFIX.SQL;
        InputStreamProvider input = () -> getClass().getResourceAsStream(inputObjectName);
        ILoader dummpLoader = provider.getDumpLoader(input, inputObjectName, settings);

        String script = PgCodeKeeperApi.diff(provider, projectLoader, dummpLoader, settings);
        assertEquals("", script);
        TestUtils.assertErrors(settings.getErrors());
    }
}
