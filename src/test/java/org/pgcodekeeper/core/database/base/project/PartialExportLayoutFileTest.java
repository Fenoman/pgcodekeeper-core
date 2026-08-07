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
package org.pgcodekeeper.core.database.base.project;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.pg.project.PgModelExporter;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Holds {@value AbstractWorkDirs#ALT_DIRS_FILENAME} to the same all-or-nothing
 * rule every other path a partial export touches obeys.
 * <p>
 * The layout file is written by {@code writeDumps} through
 * {@link AbstractWorkDirs#saveAltDirs}, not by {@code deleteStatementIfExists},
 * so it is the one write in {@code exportPartial()} that could plausibly be
 * left out of the rollback's notifications. It is also the one whose loss hurts
 * most: {@code saveAltDirs} <em>deletes</em> the file whenever the layout has
 * come back to the default, and a project without one reads as a default-layout
 * project, which makes every directory the file used to name invisible. One
 * lost {@code .sql} file is one object; one lost layout file is the whole
 * project's shape.
 * <p>
 * No shipping caller reaches this today - all three
 * {@code ProjectUpdater.createModelExporter} implementations build their
 * exporter through the constructor that leaves {@code saveLayout} off - so
 * these tests drive the exporter directly through the constructor that does
 * take a structure file, which is exactly the wiring a future caller would use.
 */
class PartialExportLayoutFileTest {

    /**
     * A layout file that spells out values identical to the defaults - what a
     * project looks like after someone renames a directory back. That equality
     * is what sends {@code saveAltDirs} down its deleting branch.
     */
    private static final String DEFAULT_LAYOUT = "is_split_by_schema=true\nTABLE=TABLE\n";

    /** A layout the defaults do not match, so the file gets rewritten instead. */
    private static final String CUSTOM_LAYOUT = "is_split_by_schema=true\nTABLE=TABLES\n";

    @Test
    void testRollbackBringsBackALayoutFileTheExportDeleted(@TempDir Path projectDir, @TempDir Path backupDir)
            throws Exception {
        Path layoutFile = projectDir.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME);
        Files.writeString(layoutFile, DEFAULT_LAYOUT);

        PartialExportBackup backup = exportPartialWithBackup(projectDir, backupDir, layoutFile);

        Assertions.assertFalse(Files.exists(layoutFile),
                "fixture sanity check: a layout that has come back to the default is what makes "
                        + "saveAltDirs delete the file - the destructive branch this test is about");

        backup.restore();

        Assertions.assertEquals(DEFAULT_LAYOUT, Files.readString(layoutFile),
                "a rolled back export must leave the layout file exactly as it found it - without "
                        + "it the project reads as a default-layout one and every directory it named "
                        + "stops being found");
    }

    @Test
    void testRollbackBringsBackALayoutFileTheExportRewrote(@TempDir Path projectDir, @TempDir Path backupDir)
            throws Exception {
        Path layoutFile = projectDir.resolve(AbstractWorkDirs.ALT_DIRS_FILENAME);
        Files.writeString(layoutFile, CUSTOM_LAYOUT);

        PartialExportBackup backup = exportPartialWithBackup(projectDir, backupDir, layoutFile);

        Assertions.assertNotEquals(CUSTOM_LAYOUT, Files.readString(layoutFile),
                "fixture sanity check: a layout the defaults do not match sends saveAltDirs down "
                        + "its writing branch");

        backup.restore();

        Assertions.assertEquals(CUSTOM_LAYOUT, Files.readString(layoutFile),
                "a rolled back export must put the original layout bytes back, not leave the "
                        + "rewritten ones behind");
    }

    /**
     * Runs a partial export that changes no object at all - the layout file and
     * the version marker are then the only paths it writes, which is precisely
     * what these tests want to watch.
     */
    private static PartialExportBackup exportPartialWithBackup(
            Path projectDir, Path backupDir, Path layoutFile) throws Exception {
        var exporter = new PgModelExporter(projectDir, new PgDatabase(false), new PgDatabase(false),
                layoutFile, List.of(), Consts.UTF_8, new CoreSettings());
        PartialExportBackup backup = new PartialExportBackup(projectDir, backupDir);
        exporter.setPartialExportPathListener(backup);

        exporter.exportPartial();

        return backup;
    }
}
