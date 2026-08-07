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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

class PgLoaderObjectReferencePolicyTest {

    @Test
    void disabledSettingsDisableDumpProjectAndLibraryFactories(@TempDir Path tempDir) {
        ISettings settings = mock(ISettings.class);
        when(settings.isCollectObjectReferences()).thenReturn(false);

        assertAll(
                () -> assertReferencePolicy(new ExposedPgDumpLoader(tempDir, settings).create(), false),
                () -> assertReferencePolicy(new ExposedPgProjectLoader(tempDir, settings).create(), false),
                () -> assertReferencePolicy(new ExposedPgLibraryLoader(tempDir, settings).create(), false));
    }

    @Test
    void defaultSettingsEnableDumpProjectAndLibraryFactories(@TempDir Path tempDir) {
        var settings = new CoreSettings();

        assertAll(
                () -> assertReferencePolicy(new ExposedPgDumpLoader(tempDir, settings).create(), true),
                () -> assertReferencePolicy(new ExposedPgProjectLoader(tempDir, settings).create(), true),
                () -> assertReferencePolicy(new ExposedPgLibraryLoader(tempDir, settings).create(), true));
    }

    @Test
    void scriptModeForcesDumpFactoryEnabled(@TempDir Path tempDir) {
        ISettings settings = mock(ISettings.class);
        when(settings.isCollectObjectReferences()).thenReturn(false);
        var loader = new ExposedPgDumpLoader(tempDir, settings);
        loader.setMode(ParserListenerMode.SCRIPT);

        assertReferencePolicy(loader.create(), true);
    }

    private static void assertReferencePolicy(PgDatabase database, boolean enabled) {
        database.addReference("probe.sql", mock(ObjectLocation.class));

        if (enabled) {
            assertTrue(database.getObjReferences().containsKey("probe.sql"));
        } else {
            assertFalse(database.getObjReferences().containsKey("probe.sql"));
        }
    }

    private static final class ExposedPgDumpLoader extends PgDumpLoader {

        private ExposedPgDumpLoader(Path path, ISettings settings) {
            super(path, settings);
        }

        private PgDatabase create() {
            return createDatabase();
        }
    }

    private static final class ExposedPgProjectLoader extends PgProjectLoader {

        private ExposedPgProjectLoader(Path path, ISettings settings) {
            super(path, settings);
        }

        private PgDatabase create() {
            return createDatabase();
        }
    }

    private static final class ExposedPgLibraryLoader extends PgLibraryLoader {

        private ExposedPgLibraryLoader(Path path, ISettings settings) {
            super(new PgDatabase(), path, new HashSet<>(), settings);
        }

        private PgDatabase create() {
            return createDatabase();
        }
    }
}
