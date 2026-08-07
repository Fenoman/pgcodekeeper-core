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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Unit tests for {@link PartialExportBackup} in isolation from the database
 * and diff-tree machinery: a hand-built directory tree stands in for a
 * project, and {@link PartialExportBackup#beforeTouch} is called directly,
 * exactly as {@link AbstractModelExporter#deleteStatementIfExists} calls it.
 */
class PartialExportBackupTest {

    @Test
    void testSnapshotContainsOnlyTouchedFilesNotTheWholeProject(@TempDir Path liveDir, @TempDir Path backupDir)
            throws IOException {
        // a "big project": 50 files the backup must never even look at
        for (int i = 0; i < 50; i++) {
            Path untouched = liveDir.resolve("SCHEMA/untouched_" + i + "/TABLE/t.sql");
            Files.createDirectories(untouched.getParent());
            Files.writeString(untouched, "content " + i);
        }

        Path existing = liveDir.resolve("SCHEMA/public/TABLE/t4.sql");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "original t4");

        Path brandNew = liveDir.resolve("SCHEMA/public/FUNCTION/fun3.sql"); // does not exist - a "create"

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);
        backup.beforeTouch(liveDir.relativize(existing));
        backup.beforeTouch(liveDir.relativize(brandNew));

        long backedUpFileCount;
        try (Stream<Path> walk = Files.walk(backupDir)) {
            backedUpFileCount = walk.filter(Files::isRegularFile).count();
        }
        Assertions.assertEquals(1, backedUpFileCount,
                "only the one touched-and-preexisting file belongs in the snapshot, "
                        + "not the 50 untouched ones and not the one that never existed");
    }

    @Test
    void testRestoreRewritesModifiedFileBackToOriginalBytes(@TempDir Path liveDir, @TempDir Path backupDir)
            throws IOException {
        Path file = liveDir.resolve("SCHEMA/public/TABLE/t4.sql");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "original");

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);
        backup.beforeTouch(liveDir.relativize(file));

        Files.writeString(file, "modified by a partial export that later failed");

        backup.restore();

        Assertions.assertEquals("original", Files.readString(file),
                "restore must bring back the exact original bytes");
    }

    @Test
    void testRestoreRecreatesADeletedFile(@TempDir Path liveDir, @TempDir Path backupDir) throws IOException {
        Path file = liveDir.resolve("SCHEMA/public/TABLE/t2.sql");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "still here");

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);
        backup.beforeTouch(liveDir.relativize(file));

        Files.delete(file); // the export removed the object entirely

        backup.restore();

        Assertions.assertTrue(Files.exists(file), "restore must recreate a file the export deleted");
        Assertions.assertEquals("still here", Files.readString(file));
    }

    @Test
    void testRestoreDeletesAFileTheExportCreatedOutOfNothing(@TempDir Path liveDir, @TempDir Path backupDir)
            throws IOException {
        Path file = liveDir.resolve("SCHEMA/public/FUNCTION/fun3.sql");

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);
        backup.beforeTouch(liveDir.relativize(file)); // absence recorded - the file does not exist yet

        Files.createDirectories(file.getParent());
        Files.writeString(file, "brand new function");

        backup.restore();

        Assertions.assertFalse(Files.exists(file),
                "restore must delete a file the export created where nothing was before");
    }

    @Test
    void testRestorePrunesDirectoriesCreatedSolelyForADeletedCreate(@TempDir Path liveDir, @TempDir Path backupDir)
            throws IOException {
        // SCHEMA already exists (shared by the whole project); "newschema" and its
        // own TABLE subdirectory do not yet - the export has to create both
        Files.createDirectories(liveDir.resolve("SCHEMA"));

        Path newSchemaFile = liveDir.resolve("SCHEMA/newschema/newschema.sql");
        Path newTableFile = liveDir.resolve("SCHEMA/newschema/TABLE/mytable.sql");

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);
        backup.beforeTouch(liveDir.relativize(newSchemaFile));
        backup.beforeTouch(liveDir.relativize(newTableFile));

        Files.createDirectories(newTableFile.getParent());
        Files.writeString(newSchemaFile, "create schema newschema;");
        Files.writeString(newTableFile, "create table newschema.mytable();");

        backup.restore();

        Assertions.assertFalse(Files.exists(newSchemaFile), "the new schema's own file must be gone");
        Assertions.assertFalse(Files.exists(newTableFile), "the new table's file must be gone");
        Assertions.assertFalse(Files.exists(liveDir.resolve("SCHEMA/newschema")),
                "the schema directory the export had to create must be pruned, not left behind empty");
        Assertions.assertTrue(Files.exists(liveDir.resolve("SCHEMA")),
                "the pre-existing SCHEMA directory itself must survive - it is shared by the whole project");
    }

    @Test
    void testRestoreLeavesACreatedDirectoryInPlaceIfSomethingElseIsStillInIt(
            @TempDir Path liveDir, @TempDir Path backupDir) throws IOException {
        Path newFile = liveDir.resolve("SCHEMA/newschema/newschema.sql");

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);
        backup.beforeTouch(liveDir.relativize(newFile));

        Files.createDirectories(newFile.getParent());
        Files.writeString(newFile, "create schema newschema;");
        // something outside anything this backup was ever told about lands in
        // the same freshly created directory
        Files.writeString(liveDir.resolve("SCHEMA/newschema/unrelated.txt"), "not mine");

        backup.restore();

        Assertions.assertFalse(Files.exists(newFile), "the touched file is still undone");
        Assertions.assertTrue(Files.exists(liveDir.resolve("SCHEMA/newschema")),
                "a created directory that turns out not to be empty must not be deleted");
    }

    /**
     * {@code beforeTouch} adds a path to {@code touchedPaths} only once the copy
     * that backs it up has succeeded. If the copy of an existing file fails -
     * denied read, disk full, any I/O error - and the path were marked anyway,
     * it would stay marked as "touched" with no backup behind it, and
     * {@code restore()} would read that absence as "did not exist before" and
     * delete a file the export had not even gotten to yet.
     */
    @Test
    void testBeforeTouchDoesNotMarkPathTouchedWhenCopyingAnExistingFileFails(
            @TempDir Path liveDir, @TempDir Path backupDir) throws IOException {
        Path file = liveDir.resolve("SCHEMA/public/TABLE/t4.sql");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ORIGINAL CONTENT THAT MUST SURVIVE");

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);
        Path relative = liveDir.relativize(file);

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(file);
        try {
            // deny read access on the live file itself so Files.copy(live, saved)
            // fails while the file on disk stays completely untouched otherwise
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w-------"));

            Assertions.assertThrows(IOException.class, () -> backup.beforeTouch(relative),
                    "a failed snapshot of an existing file must propagate as a real failure, "
                            + "not be swallowed - the caller (deleteStatementIfExists) must never "
                            + "reach its own delete once this throws");
        } finally {
            Files.setPosixFilePermissions(file, original);
        }

        // deleteStatementIfExists() calls beforeTouch() before its own
        // Files.deleteIfExists() - so a beforeTouch() failure means the export
        // never got as far as touching the live file at all. restore() must
        // therefore find nothing to undo for this path.
        backup.restore();

        Assertions.assertTrue(Files.exists(file),
                "a file whose snapshot failed, and which the export therefore never touched, "
                        + "must not be deleted by restore()");
        Assertions.assertEquals("ORIGINAL CONTENT THAT MUST SURVIVE", Files.readString(file),
                "restore() must not corrupt a file it was never able to back up");
    }

    /**
     * Some exporters (the overrides one) create their own root directory as a
     * setup step outside
     * any {@code beforeTouch} notification, so {@code recordAbsentAncestors}
     * never sees it - a single-component relative path's parent is
     * {@code null}, so the walk can never reach "the root itself". If that
     * root did not exist before the export and the export fails after
     * creating it, restore() must still remove it, not just its now-empty
     * contents.
     */
    @Test
    void testRestorePrunesTheLiveRootItselfIfItDidNotExistBeforeTheExport(@TempDir Path parentDir)
            throws IOException {
        Path liveDir = parentDir.resolve("OVERRIDES"); // does not exist yet
        Path backupDir = parentDir.resolve("backup");
        Files.createDirectories(backupDir);

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);

        // simulate what AbstractOverridesModelExporter.exportPartial() does at
        // its own start, outside any notification: create the root before
        // anything is touched through beforeTouch
        Files.createDirectories(liveDir);

        Path newFile = liveDir.resolve("SCHEMA/public/TABLE/t5.sql");
        backup.beforeTouch(liveDir.relativize(newFile));
        Files.createDirectories(newFile.getParent());
        Files.writeString(newFile, "owner/privileges for t5");

        backup.restore();

        Assertions.assertFalse(Files.exists(newFile), "the override file must be undone");
        Assertions.assertFalse(Files.exists(liveDir),
                "the OVERRIDES root itself, created fresh by the exporter's own setup step outside "
                        + "any notification, must be pruned too - it did not exist before this export");
    }

    @Test
    void testRestoreDoesNotPruneTheLiveRootIfItExistedBeforeTheExport(@TempDir Path liveDir, @TempDir Path backupDir)
            throws IOException {
        // liveDir is a JUnit @TempDir, so it already exists before the
        // PartialExportBackup is even constructed - matching a project that
        // already has an OVERRIDES/ directory from an earlier run
        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);

        Path newFile = liveDir.resolve("SCHEMA/public/TABLE/t5.sql");
        backup.beforeTouch(liveDir.relativize(newFile));
        Files.createDirectories(newFile.getParent());
        Files.writeString(newFile, "owner/privileges for t5");

        backup.restore();

        Assertions.assertFalse(Files.exists(newFile), "the override file must be undone");
        Assertions.assertTrue(Files.exists(liveDir),
                "a root that already existed before the export must survive - it is not this "
                        + "export's to remove");
    }

    /**
     * {@code restore()} must not abandon the whole rollback at the first path it
     * cannot put back. That is the worst possible moment to give up: a rollback
     * only runs because something already went wrong on this filesystem, and
     * whatever that was - a directory turned read-only, a full disk - blocks
     * putting a file back just as readily as it blocked writing one. Stopping
     * would leave every path after the failed one holding the failed export's
     * bytes, i.e. the project in a state that existed neither before the export
     * nor after it.
     */
    @Test
    void testRestoreKeepsGoingPastAPathItCannotPutBack(@TempDir Path liveDir, @TempDir Path backupDir)
            throws IOException {
        Path blocked = liveDir.resolve("SCHEMA/public/TABLE/t1.sql");
        Path reachable = liveDir.resolve("SCHEMA/public/VIEW/v1.sql");
        Files.createDirectories(blocked.getParent());
        Files.createDirectories(reachable.getParent());
        Files.writeString(blocked, "ORIGINAL t1");
        Files.writeString(reachable, "ORIGINAL v1");

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);
        // the blocked path is snapshotted first, so it is also the first one
        // the rollback loop reaches - the arrangement that used to end the loop
        backup.beforeTouch(liveDir.relativize(blocked));
        backup.beforeTouch(liveDir.relativize(reachable));

        // both rewritten by an export that then failed on a third object
        Files.writeString(blocked, "NEW t1");
        Files.writeString(reachable, "NEW v1");

        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(blocked.getParent());
        try {
            Files.setPosixFilePermissions(blocked.getParent(), PosixFilePermissions.fromString("r-xr-xr-x"));

            Assertions.assertThrows(IOException.class, backup::restore,
                    "a rollback that could not undo everything must still report that it failed");
        } finally {
            Files.setPosixFilePermissions(blocked.getParent(), writable);
        }

        Assertions.assertEquals("ORIGINAL v1", Files.readString(reachable),
                "a path the rollback could still reach must be put back even though an earlier one "
                        + "could not be - one unreachable file is not a reason to leave the rest rewritten");
        Assertions.assertEquals("NEW t1", Files.readString(blocked),
                "fixture sanity check: the blocked path really was the one that could not be undone");
        Assertions.assertEquals("ORIGINAL t1", Files.readString(backupDir.resolve(liveDir.relativize(blocked))),
                "the bytes the rollback could not put back are now only in the snapshot - it must "
                        + "still hold them for whoever has to repair the project by hand");
    }

    /**
     * A symbolic link must come back a symbolic link. Snapshotting per path with
     * a plain {@code Files.copy} would follow the link, so the rollback would
     * write the target's bytes into the entry, and a project that shares one
     * file between two paths would come back with two independent copies of it
     * instead - which the next export happily diverges.
     */
    @Test
    void testRestoreBringsBackASymlinkAsASymlink(@TempDir Path liveDir, @TempDir Path backupDir) throws IOException {
        Path target = liveDir.resolve("SCHEMA/public/TABLE/shared.sql");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "the bytes both paths share");

        Path link = liveDir.resolve("SCHEMA/public/TABLE/t4.sql");
        Path linkTarget = Path.of("shared.sql");
        Files.createSymbolicLink(link, linkTarget);

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);
        backup.beforeTouch(liveDir.relativize(link));

        // what an export does to a path it rewrites: delete, then write anew
        Files.delete(link);
        Files.writeString(link, "a plain file the export put where the link stood");

        backup.restore();

        Assertions.assertTrue(Files.isSymbolicLink(link),
                "the entry was a symbolic link before the export, so that is what must come back - "
                        + "a rollback that leaves a plain file behind has changed the project it "
                        + "claims to have left alone");
        Assertions.assertEquals(linkTarget, Files.readSymbolicLink(link),
                "the restored link must point where it pointed before");
        Assertions.assertEquals("the bytes both paths share", Files.readString(target),
                "the link's target is not one of the touched paths and must be untouched by all this");
    }

    /**
     * The same defect seen from its other side: a link whose target is missing
     * is still a directory entry the project holds, and still the export's to
     * put back. Asked through the link, the entry looks absent, nothing is
     * snapshotted, and {@code restore()} then reads that empty snapshot as
     * "the export created this path out of nothing" - and deletes the very
     * entry it was meant to protect.
     */
    @Test
    void testASymlinkWithNoTargetIsStillSnapshotted(@TempDir Path liveDir, @TempDir Path backupDir)
            throws IOException {
        Path link = liveDir.resolve("SCHEMA/public/TABLE/t4.sql");
        Path missingTarget = Path.of("not_here.sql");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, missingTarget);

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);
        backup.beforeTouch(liveDir.relativize(link));

        Files.delete(link);
        Files.writeString(link, "whatever the export wrote before it failed");

        backup.restore();

        Assertions.assertTrue(Files.isSymbolicLink(link),
                "a dangling link is still an entry that existed before the export - the rollback "
                        + "must put it back, not delete it as something the export invented");
        Assertions.assertEquals(missingTarget, Files.readSymbolicLink(link),
                "including the target it was pointing at, broken or not");
    }

    @Test
    void testBeforeTouchIsIdempotentPerPath(@TempDir Path liveDir, @TempDir Path backupDir) throws IOException {
        Path file = liveDir.resolve("SCHEMA/public/TABLE/t4.sql");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "original");

        PartialExportBackup backup = new PartialExportBackup(liveDir, backupDir);
        Path relative = liveDir.relativize(file);
        backup.beforeTouch(relative);

        Files.writeString(file, "first rewrite"); // as if delete-then-recreate already ran once

        backup.beforeTouch(relative); // a second statement sharing the same file - must be a no-op

        Files.writeString(file, "second rewrite, then the export fails");

        backup.restore();

        Assertions.assertEquals("original", Files.readString(file),
                "a second beforeTouch on an already-touched path must not clobber the real backup "
                        + "with the file's already-modified state");
    }
}
