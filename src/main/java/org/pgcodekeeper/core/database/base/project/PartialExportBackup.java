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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Snapshots the pre-write state of exactly the paths a partial export touches
 * under one directory, and can put all of them back as a single unit.
 * <p>
 * WHY per-path instead of a whole-directory copy: on a project with tens of
 * thousands of files, {@code exportPartial()} rewrites a handful of them even
 * for a large change; copying the untouched rest costs seconds for bytes that
 * would never be read back on the overwhelmingly common successful path.
 * Narrowing the copy to what will actually change turns that cost into a
 * function of the size of the *edit* instead of the size of the *project*.
 * <p>
 * WHY this class never decides on its own which paths qualify: it only reacts
 * to {@link AbstractModelExporter}'s own decisions about which path is about
 * to change, through {@link PartialExportPathListener}. Two independent
 * computations of "what will be touched" - one for the backup, another for
 * the write - can drift apart as {@code exportPartial()} evolves, and a
 * drifted backup is a backup that silently misses a file. Riding along with
 * the decisions the exporter already makes, instead of recomputing them,
 * leaves nothing to drift.
 */
final class PartialExportBackup implements PartialExportPathListener {

    private final Path liveDir;
    private final Path backupDir;

    /**
     * Whether {@link #liveDir} itself was absent the moment this backup was
     * created - i.e. before {@code exportPartial()} ran at all. Some exporters
     * (the overrides one) create their own root as a setup step that never
     * goes through {@link #beforeTouch}, so {@link #recordAbsentAncestors}
     * can never learn this on its own: a single-component relative path's
     * {@code getParent()} is {@code null}, so the ancestor walk can never
     * reach "the root itself" - there is no {@code Path} that means that.
     * Recording it here, at construction time - which always runs before the
     * exporter touches anything, see {@link AbstractProjectUpdater#updatePartial()}
     * - is the one place that can still observe it.
     */
    private final boolean liveDirAbsentAtStart;

    /** Every relative path successfully snapshotted so far, in first-touch order. */
    private final Set<Path> touchedPaths = new LinkedHashSet<>();

    /**
     * Ancestor directories (relative to {@link #liveDir}) that did not exist
     * yet the moment a path under them was first touched - meaning the export
     * itself must be the one creating them - kept so {@link #restore()} can
     * prune whichever of them are still empty once the touched paths
     * themselves have been restored or removed.
     */
    private final Set<Path> createdDirectories = new LinkedHashSet<>();

    /**
     * @param liveDir   the exporter's own output directory - the project files a
     *                  running export reads from and writes into
     * @param backupDir where the "before" state of touched paths is kept until
     *                  it is either discarded (export succeeded) or replayed by
     *                  {@link #restore()} (export failed)
     */
    PartialExportBackup(Path liveDir, Path backupDir) {
        this.liveDir = liveDir;
        this.backupDir = backupDir;
        this.liveDirAbsentAtStart = Files.notExists(liveDir);
    }

    /**
     * {@inheritDoc}
     * <p>
     * A path with nothing copied into {@code backupDir} once this returns
     * means the live file did not exist before the export started - that
     * absence is itself the record {@link #restore()} reads to know the path
     * must be deleted, rather than overwritten, to undo the export.
     * <p>
     * WHY the path is marked touched only after the copy succeeds, not before:
     * marking it first and copying second means a copy failure - denied read,
     * a full disk, any I/O error - on a file that does exist leaves the path
     * marked "touched" with nothing behind it in {@code backupDir}. {@code
     * restore()} cannot tell that apart from "did not exist before" and would
     * delete a file the export had not even reached yet - the exact defect
     * this ordering exists to rule out. Because {@code deleteStatementIfExists}
     * calls this before its own delete, a failure here also means the export
     * stops before it has touched the live file at all - the cheap snapshot
     * question is answered before the expensive write is allowed to begin.
     * <p>
     * WHY nothing here follows a symbolic link: what the export is about to
     * replace is the directory entry, and the entry is what has to come back.
     * Following the link asks about the wrong file twice over - it copies the
     * target's bytes, so the rollback puts a plain file where a link stood and
     * quietly severs it, and it calls a link with no target absent, so a
     * dangling one is not snapshotted at all and the rollback deletes it as
     * something the export invented. The whole-directory copy this class
     * replaced moved entries around and never had either problem; keeping
     * links intact is not a new promise, it is the one already made.
     *
     * @throws IOException if the existing file cannot be copied to the
     *                      snapshot; the path is left unmarked, as if this
     *                      call had never happened
     */
    @Override
    public void beforeTouch(Path relativePath) throws IOException {
        if (touchedPaths.contains(relativePath)) {
            return; // already snapshotted on an earlier touch of the same path
        }

        Path live = liveDir.resolve(relativePath);
        if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
            Path saved = backupDir.resolve(relativePath);
            Files.createDirectories(saved.getParent());
            Files.copy(live, saved, LinkOption.NOFOLLOW_LINKS);
        }

        // only reached once the copy above (if any was needed) actually
        // succeeded - see the "WHY" note above
        recordAbsentAncestors(relativePath.getParent());
        touchedPaths.add(relativePath);
    }

    /**
     * Walks from {@code relativeDir} up towards {@link #liveDir}, recording
     * every ancestor that does not exist yet. Stops as soon as it reaches one
     * that does exist, because a filesystem directory cannot exist unless
     * every one of its own ancestors does too - so everything above that
     * point is already known-good and does not need checking.
     */
    private void recordAbsentAncestors(Path relativeDir) {
        for (Path dir = relativeDir; dir != null && !createdDirectories.contains(dir); dir = dir.getParent()) {
            if (Files.exists(liveDir.resolve(dir))) {
                return;
            }
            createdDirectories.add(dir);
        }
    }

    /**
     * Undoes every touched path as one unit: a path with a backup gets its
     * original bytes back - whether the export deleted it, rewrote it, or
     * never actually got as far as changing it before failing - and a path
     * with no backup, because it did not exist before the export, gets
     * deleted. Directories that had to be created to hold a since-deleted path
     * are pruned too, deepest first, so a failed export leaves the directory
     * tree, not just its files, exactly as it found it - including
     * {@link #liveDir} itself, if the exporter created that too.
     * <p>
     * WHY every path is attempted even after one of them fails: a rollback
     * runs precisely because something already went wrong on this filesystem,
     * and whatever that was - a directory turned read-only, a full disk, a
     * revoked permission - is just as able to block putting one file back as
     * it was to block writing it. Stopping at the first path that cannot be
     * undone leaves the rest of the batch rewritten, i.e. the project in a
     * state that existed neither before the export nor after it, which is the
     * one outcome this whole class exists to prevent. Every failure is still
     * reported: the first one is thrown, the rest ride along on it as
     * suppressed exceptions.
     * <p>
     * WHY {@link Files#move} rather than a copy: {@link #backupDir} always
     * lives inside the project directory being exported (it is a {@code
     * TempDir} created there, see {@link AbstractProjectUpdater#updatePartial()}),
     * so putting a file back is a rename within one filesystem - it needs no
     * free space and cannot half-write a file. A copy needs room for a second
     * set of the bytes, and "no space left" is one of the very failures that
     * gets an export rolled back in the first place: the rollback would then
     * fail for the same reason the export did. Should the two ever end up on
     * different filesystems after all, {@code move} without
     * {@code ATOMIC_MOVE} degrades to exactly the copy-and-delete this
     * replaces, so nothing is lost by asking for the cheaper operation first.
     * <p>
     * Consumes the snapshot as it goes and must therefore be called at most
     * once; whatever is left in {@link #backupDir} afterwards is exactly the
     * set of files that could not be put back.
     *
     * @throws IOException if restoring or pruning any path fails - after all
     *                      the others have been attempted
     */
    void restore() throws IOException {
        IOException failures = null;

        for (Path relativePath : touchedPaths) {
            try {
                restoreOne(relativePath);
            } catch (Exception ex) {
                failures = collect(failures, ex);
            }
        }

        // deepest first, so a child directory is gone before its parent is checked
        List<Path> deepestFirst = new ArrayList<>(createdDirectories);
        deepestFirst.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path dir : deepestFirst) {
            try {
                deleteIfEmpty(liveDir.resolve(dir));
            } catch (Exception ex) {
                failures = collect(failures, ex);
            }
        }

        // the root itself is never a member of createdDirectories (see the
        // field javadoc) - handled as one explicit last step instead, always
        // after every path and every nested directory above has been undone
        if (liveDirAbsentAtStart) {
            try {
                deleteIfEmpty(liveDir);
            } catch (Exception ex) {
                failures = collect(failures, ex);
            }
        }

        if (failures != null) {
            throw failures;
        }
    }

    private void restoreOne(Path relativePath) throws IOException {
        Path live = liveDir.resolve(relativePath);
        Path saved = backupDir.resolve(relativePath);
        // NOFOLLOW_LINKS because "is there a snapshot for this path" is a
        // question about the entry, not about what it points at: a snapshotted
        // link keeps the target it had inside the live project, which is not
        // where the snapshot sits, so following it would report the one file
        // that is definitely there as missing - and the branch below reads
        // missing as "the export created this", i.e. deletes it.
        if (Files.exists(saved, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(live.getParent());
            Files.move(saved, live, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(live);
        }
    }

    /**
     * Folds one more rollback failure into whatever has already been
     * collected. The first failure is the one that ends up thrown, unchanged
     * whenever it already is an {@link IOException} - so the single-failure
     * case reads exactly as it did when the first failure was also the last
     * thing this method ever did.
     */
    private static IOException collect(IOException collected, Exception ex) {
        if (collected == null) {
            return ex instanceof IOException ioEx ? ioEx : new IOException(ex);
        }
        collected.addSuppressed(ex);
        return collected;
    }

    private static void deleteIfEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            if (children.findAny().isEmpty()) {
                Files.delete(dir);
            }
        }
    }
}
