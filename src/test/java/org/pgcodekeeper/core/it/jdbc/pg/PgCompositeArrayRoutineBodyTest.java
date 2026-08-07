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
package org.pgcodekeeper.core.it.jdbc.pg;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.settings.CoreSettings;

class PgCompositeArrayRoutineBodyTest extends AbstractPgGpJdbcLoaderTest {

    private static final String FIXTURE = "pg_18_composite_array_routine.sql";

    private static final Set<ObjectReference> EXPECTED_DEPENDENCIES = Set.of(
            new ObjectReference("app", DbObjType.SCHEMA),
            new ObjectReference("app", "item", DbObjType.TYPE));

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @Test
    void dumpBodyAnalyzesQualifiedCompositeArraysWithoutErrors() throws Exception {
        CoreSettings settings = settings();
        Path fixture = TestUtils.getFilePath(FIXTURE, getClass());

        var db = provider.getDumpLoader(fixture, settings).loadAndAnalyze();
        var routine = db.getDescendants()
                .filter(statement -> "app.collect_items()".equals(statement.getQualifiedName()))
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertTrue(settings.getErrors().isEmpty(), settings.getErrors()::toString),
                () -> assertEquals(EXPECTED_DEPENDENCIES, Set.copyOf(routine.getDependencies())));
    }

    @Test
    @ResourceLock(AbstractPgGpJdbcLoaderTest.SHARED_PG_TEST_DATABASE)
    void jdbcBodyMatchesDumpAndKeepsCompositeArrayAnalysisClean() throws Exception {
        CoreSettings settings = settings();

        jdbcLoaderTest(false, "composite_array_routine", "PG_18", settings);

        assertTrue(settings.getErrors().isEmpty(), settings.getErrors()::toString);
    }

    private static CoreSettings settings() {
        var settings = new CoreSettings();
        settings.setVersion(PgSupportedVersion.VERSION_18);
        settings.setEnableFunctionBodiesDependencies(true);
        settings.setIgnorePrivileges(true);
        return settings;
    }
}
