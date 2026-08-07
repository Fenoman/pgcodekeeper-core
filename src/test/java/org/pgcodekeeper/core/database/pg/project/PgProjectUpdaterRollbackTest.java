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
package org.pgcodekeeper.core.database.pg.project;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;
import org.pgcodekeeper.core.settings.CoreSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.pgcodekeeper.core.it.IntegrationTestUtils.loadTestDump;

/**
 * End-to-end coverage of {@link PgProjectUpdater#updatePartial()}'s rollback:
 * the owner chose to keep "every touched file applies, or none of them do"
 * even after narrowing the pre-write snapshot down from a whole-project copy
 * to just the affected paths (see
 * {@code PartialExportBackup}/{@code PartialExportPathListener}). These tests
 * exist to prove that guarantee still holds - a snapshot of only two or three
 * files is worthless if a failure elsewhere in the same batch is not undone
 * just as completely as the old whole-project copy undid it.
 */
class PgProjectUpdaterRollbackTest {

    private static IDatabase dbSource;
    private static IDatabase dbTarget;

    @BeforeAll
    static void initDiffTree() throws InterruptedException, IOException {
        PgDatabaseProvider databaseProvider = new PgDatabaseProvider();
        var settings = new CoreSettings();
        settings.setInCharsetName(Consts.UTF_8);

        dbSource = loadTestDump(databaseProvider, "TestPartialExportSource.sql", PgPartialExporterTest.class,
                settings, false);
        dbTarget = loadTestDump(databaseProvider, "TestPartialExportTarget.sql", PgPartialExporterTest.class,
                settings, false);

        Assertions.assertNotNull(dbSource);
        Assertions.assertNotNull(dbTarget);
    }

    /**
     * Modifies one existing file, creates a brand new one that is actually
     * written successfully, and creates a second brand new one whose write is
     * forced to fail - all in the same {@code updatePartial()} call - then
     * checks that the whole project tree comes back byte-for-byte identical
     * to how it stood before the call, including the file that was
     * successfully created before the failure elsewhere in the batch.
     * <p>
     * WHY the relative write order of the three is not left to chance:
     * {@code AbstractModelExporter} builds {@code dumps} as a
     * {@code LinkedHashMap}, so it iterates in insertion order, which is
     * the order {@code list.stream().sorted(ExportTableOrder.INSTANCE)}
     * produces. {@code t4}, {@code fun3()} and {@code v1} are all
     * {@code ExportTableOrder} rank 0 (none is an index/trigger/rule/
     * constraint/policy/statistics), so the tie-break is alphabetical by bare
     * name: "fun3" &lt; "t4" &lt; "v1". Locking only {@code v1}'s directory
     * therefore deterministically lets {@code fun3.sql} and {@code t4.sql} be
     * written first - one created, one rewritten - before {@code v1.sql}'s
     * write fails and aborts the batch.
     */
    @org.junit.jupiter.api.Test
    void testFailedPartialUpdateRestoresProjectByteForByte(@TempDir Path projectDir) throws Exception {
        var settings = new CoreSettings();
        new PgModelExporter(projectDir, dbSource, Consts.UTF_8, settings).exportFull();

        TreeElement tree = DiffTree.create(settings, dbSource, dbTarget);
        // an existing file changes ...
        tree.getChild("public").getChild("t4").getChild("t4_c2_key").setSelected(true);
        // ... a brand new file is created and its write succeeds ...
        tree.getChild("public").getChild("fun3()").setSelected(true);
        // ... and a second brand new file is created whose write fails
        tree.getChild("public").getChild("v1").setSelected(true);
        Collection<TreeElement> list = new TreeFlattener().onlySelected().onlyEdits(dbSource, dbTarget).flatten(tree);

        Path createdFile = projectDir.resolve("SCHEMA/public/FUNCTION/fun3.sql");
        Path poisonFile = projectDir.resolve("SCHEMA/public/VIEW/v1.sql");
        Path lockedDir = poisonFile.getParent();
        Assertions.assertFalse(Files.exists(createdFile), "fixture sanity check: fun3.sql must not exist yet");
        Assertions.assertFalse(Files.exists(lockedDir), "fixture sanity check: the VIEW directory must not exist yet");
        // created here, before the run, purely so there is something to lock
        // permissions on below - which also means PartialExportBackup sees it
        // as pre-existing and correctly leaves it alone on rollback; the
        // "a directory the export itself creates gets pruned" property is
        // covered by PartialExportBackupTest instead, where it can be isolated
        // from needing a lockable target
        Files.createDirectories(lockedDir);

        TreeSnapshot before = snapshot(projectDir);
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(lockedDir);
        try {
            // deletion (over changeList, unconditional for all three) needs no
            // write access here - none of the three files existed in this
            // directory before - so it is the later write of v1.sql alone
            // that this blocks; see the ordering note in the javadoc above
            Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("r-xr-xr-x"));

            var updater = new PgProjectUpdater(dbTarget, dbSource, list, Consts.UTF_8, projectDir, false, settings);
            IOException thrown = Assertions.assertThrows(IOException.class, updater::updatePartial,
                    "the injected permission failure must surface as a real IOException, not be swallowed");
            Assertions.assertNotNull(thrown.getCause(), "the original write failure must be chained, not replaced");
        } finally {
            Files.setPosixFilePermissions(lockedDir, writable);
        }

        Assertions.assertFalse(Files.exists(createdFile),
                "fun3.sql was actually created - its write ran before v1.sql's - but rollback must remove "
                        + "it anyway, because the batch as a whole failed");
        Assertions.assertFalse(Files.exists(poisonFile), "v1.sql's write never succeeded in the first place");
        Assertions.assertTrue(Files.isDirectory(lockedDir),
                "the VIEW directory was created by this test, before updatePartial() ran, purely to have "
                        + "something to lock - it must survive, since it is not this export's to remove");

        TreeSnapshot after = snapshot(projectDir);
        assertTreesByteIdentical(before, after);
        assertNoLeftoverTempDir(projectDir);
    }

    /**
     * Same shape of scenario as {@link #testFailedPartialUpdateRestoresProjectByteForByte},
     * but through the {@code overridesOnly} branch, which runs a different
     * exporter ({@code AbstractOverridesModelExporter}) against a different
     * root ({@code OVERRIDES/}). Proves the rollback is wired the same way on
     * both branches instead of only on the one the analysis focused on - and
     * this is the branch the CLI drives through {@code --update-project},
     * i.e. the one where a rollback quietly not wired up at all costs files
     * with nobody in the room to notice.
     * <p>
     * WHY two objects in two directories rather than one: with a single object
     * whose own write is the one that fails, nothing is ever deleted and
     * nothing is ever written, so a rollback has nothing to undo and every
     * assertion below holds even with the snapshot machinery removed from this
     * branch outright. A test of a rollback has to watch a file actually change
     * and actually come back.
     * <p>
     * WHY the arrangement needs no assumption about write order: the
     * overrides exporter does not sort what it writes ({@code list} keeps
     * {@code oldDb.getDescendants()}'s own order, no {@code ExportTableOrder}
     * involved), so which of the two goes first is not this test's to decide.
     * It does not have to. Every {@code deleteStatementIfExists} runs in the
     * first loop over {@code changeList}, before the first write of the
     * second loop, so {@code t4}'s override file is already gone by the time
     * either write is attempted - and {@code fun1}'s write fails whichever of
     * the two goes first, because its directory is the locked one. Either
     * order therefore owes {@code t4}'s original bytes back.
     */
    @org.junit.jupiter.api.Test
    void testFailedOverridesOnlyUpdateRestoresProjectByteForByte(@TempDir Path projectDir) throws Exception {
        var settings = new CoreSettings();
        new PgModelExporter(projectDir, dbSource, Consts.UTF_8, settings).exportFull();

        TreeElement tree = DiffTree.create(settings, dbSource, dbTarget);
        // one object whose override file already exists, in a writable directory ...
        tree.getChild("public").getChild("t4").setSelected(true);
        // ... and one whose write is bound to fail, in a directory of its own
        tree.getChild("public").getChild("fun1()").setSelected(true);
        Collection<TreeElement> list = new TreeFlattener().onlySelected().onlyEdits(dbSource, dbTarget).flatten(tree);
        Assertions.assertEquals(2, list.size(),
                "fixture sanity check: both objects must reach the exporter, or the two directories "
                        + "this test relies on are not both in play");

        Path overridesRoot = projectDir.resolve("OVERRIDES");
        Path survivor = overridesRoot.resolve("SCHEMA/public/TABLE/t4.sql");
        Path poisonFile = overridesRoot.resolve("SCHEMA/public/FUNCTION/fun1.sql");
        Path blockedDir = poisonFile.getParent();
        Files.createDirectories(survivor.getParent());
        Files.createDirectories(blockedDir);
        // an override file left by an earlier run: the export deletes it
        // before rewriting it, which makes it the file the rollback owes back
        byte[] survivorBytes = "-- owner and privileges for t4, from an earlier run\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(survivor, survivorBytes);

        TreeSnapshot before = snapshot(projectDir);
        Set<PosixFilePermission> writable = PosixFilePermissions.fromString("rwxr-xr-x");
        try {
            Files.setPosixFilePermissions(blockedDir, PosixFilePermissions.fromString("r-xr-xr-x"));

            var updater = new PgProjectUpdater(dbTarget, dbSource, list, Consts.UTF_8, projectDir, true, settings);
            Assertions.assertThrows(IOException.class, updater::updatePartial,
                    "the injected permission failure must surface as a real IOException");
        } finally {
            Files.setPosixFilePermissions(blockedDir, writable);
        }

        Assertions.assertTrue(Files.exists(survivor),
                "t4's override file is deleted before any write is attempted, so a batch that then "
                        + "fails must put it back - this branch needs its rollback wired up too");
        Assertions.assertArrayEquals(survivorBytes, Files.readAllBytes(survivor),
                "t4's override file must come back with the bytes it had before the update, not the "
                        + "ones the failed export would have left there");
        Assertions.assertFalse(Files.exists(poisonFile),
                "fun1's override file must not exist - its own write is the one that failed");

        TreeSnapshot after = snapshot(projectDir);
        assertTreesByteIdentical(before, after);
        assertNoLeftoverTempDir(projectDir);
    }

    /**
     * The snapshot lives in a
     * {@code TempDir} that {@code updatePartial()} closes on the way out, and
     * closing it deletes it recursively. That is right whenever the rollback
     * worked - and destroys the last copy of the user's bytes whenever it did
     * not, because whatever the rollback could not put back is still sitting
     * in there and nowhere else. The update must therefore hand the snapshot
     * over instead of deleting it, and say where it left it: a rescued copy
     * nobody can find is the same as no copy at all, and {@code
     * --update-project} runs in CI where nobody is watching.
     * <p>
     * The failure injected here is the shape the whole defect is about - one
     * read-only directory that breaks the export and the rollback alike. The
     * snapshot of {@code fun1.sql} is taken successfully (reading through a
     * {@code r-x} directory is allowed), then the export's own delete of it
     * fails, and then the rollback's attempt to put it back fails for exactly
     * the same reason.
     */
    @org.junit.jupiter.api.Test
    void testFailedRollbackKeepsTheSnapshotAndSaysWhereItIs(@TempDir Path projectDir) throws Exception {
        var settings = new CoreSettings();
        new PgModelExporter(projectDir, dbSource, Consts.UTF_8, settings).exportFull();

        TreeElement tree = DiffTree.create(settings, dbSource, dbTarget);
        tree.getChild("public").getChild("fun1()").setSelected(true);
        Collection<TreeElement> list = new TreeFlattener().onlySelected().onlyEdits(dbSource, dbTarget).flatten(tree);
        Assertions.assertEquals(1, list.size(), "fixture sanity check: exactly one object must be exported");

        Path relative = Path.of("SCHEMA/public/FUNCTION/fun1.sql");
        Path victim = projectDir.resolve(relative);
        byte[] originalBytes = Files.readAllBytes(victim);
        Path lockedDir = victim.getParent();

        IOException thrown;
        Set<PosixFilePermission> writable = Files.getPosixFilePermissions(lockedDir);
        try {
            Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("r-xr-xr-x"));

            var updater = new PgProjectUpdater(dbTarget, dbSource, list, Consts.UTF_8, projectDir, false, settings);
            thrown = Assertions.assertThrows(IOException.class, updater::updatePartial,
                    "a rollback that could not finish must surface as a real IOException");
        } finally {
            Files.setPosixFilePermissions(lockedDir, writable);
        }

        Path kept = findSnapshotDir(projectDir);
        Assertions.assertNotNull(kept,
                "the snapshot must be kept when the rollback fails - it is the only copy left of the "
                        + "bytes the rollback could not put back");
        Assertions.assertArrayEquals(originalBytes, Files.readAllBytes(kept.resolve(relative)),
                "the kept snapshot must actually hold the file's pre-update bytes");
        Assertions.assertTrue(thrown.getMessage().contains(kept.toString()),
                "the failure must name the kept snapshot, or the operator - a CI job, most of the time "
                        + "- has no way of finding what was rescued; message was: " + thrown.getMessage());
    }

    /**
     * The one {@code tmp-export*} directory left under {@code projectDir}, or
     * {@code null} when there is none.
     */
    private static Path findSnapshotDir(Path projectDir) throws IOException {
        try (Stream<Path> children = Files.list(projectDir)) {
            return children.filter(p -> p.getFileName().toString().startsWith("tmp-export")).findFirst().orElse(null);
        }
    }

    /**
     * A directory tree's full state: every regular file's bytes, keyed by
     * path relative to the snapshotted root, and every directory's relative
     * path on its own - directories carry no bytes, but an orphaned empty one
     * left behind by an incomplete rollback would otherwise be invisible to a
     * comparison that only ever looks at files.
     */
    private record TreeSnapshot(Map<Path, byte[]> files, Set<Path> directories) {
    }

    /**
     * Recursively reads {@code root} into a {@link TreeSnapshot}. Small enough
     * a fixture project for this to be the simplest, most direct comparison
     * tool available.
     */
    private static TreeSnapshot snapshot(Path root) throws IOException {
        Map<Path, byte[]> files = new TreeMap<>();
        Set<Path> directories = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.equals(root)) {
                    continue;
                }
                Path relative = root.relativize(p);
                if (Files.isDirectory(p)) {
                    directories.add(relative);
                } else if (Files.isRegularFile(p)) {
                    files.put(relative, Files.readAllBytes(p));
                }
            }
        }
        return new TreeSnapshot(files, directories);
    }

    /**
     * {@code byte[]} has no content {@code equals()}, so comparing two
     * {@code Map<Path, byte[]>} directly would compare array identity, not
     * bytes - always "different" for two independently read snapshots even
     * when their content matches exactly. This compares what actually
     * matters: the same set of directories, the same set of files, each with
     * the same bytes.
     */
    private static void assertTreesByteIdentical(TreeSnapshot before, TreeSnapshot after) {
        Assertions.assertEquals(before.directories(), after.directories(),
                "the set of directories on disk must be exactly what it was before the failed update - "
                        + "a directory the export created for a since-undone path must not be left behind");
        Assertions.assertEquals(before.files().keySet(), after.files().keySet(),
                "the set of files on disk must be exactly what it was before the failed update");
        for (Path path : before.files().keySet()) {
            Assertions.assertArrayEquals(before.files().get(path), after.files().get(path),
                    "file " + path + " must be byte-for-byte identical to before the failed update");
        }
    }

    private static void assertNoLeftoverTempDir(Path projectDir) throws IOException {
        try (Stream<Path> children = Files.list(projectDir)) {
            boolean leftover = children.anyMatch(p -> p.getFileName().toString().startsWith("tmp-export"));
            Assertions.assertFalse(leftover, "the backup temp directory must be cleaned up even after a failure");
        }
    }
}
