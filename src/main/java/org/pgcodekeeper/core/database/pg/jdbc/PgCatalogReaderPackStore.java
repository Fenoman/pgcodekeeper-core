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
package org.pgcodekeeper.core.database.pg.jdbc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.utils.ContentAddressedFileStore;

/** Atomic manifest and immutable generation storage for catalog reader packs. */
final class PgCatalogReaderPackStore {

    private static final byte[] MANIFEST_MAGIC = {
            'P', 'G', 'C', 'K', 'R', 'P', 'M', '1'
    };
    private static final int MANIFEST_VERSION = 1;
    private static final String PACKS_DIRECTORY = "reader-packs";
    private static final String MANIFEST_FILE = "current.bin";
    private static final String PUBLISH_LOCK_FILE = ".publish.lock";
    private static final String PACK_PREFIX = "generation-";
    private static final String PACK_SUFFIX = ".bin";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String TEMP_LOCK_SUFFIX = ".lock";
    private static final String TARGET_V1_PREFIX = "target-v1-";
    private static final String TARGET_V2_PREFIX = "target-v2-";
    private static final String RETIRED_V1_PREFIX = ".pgck-retired-v1-";
    private static final String RETIRE_V1_LOCK = ".pgck-retire-v1.lock";
    /**
     * Idle period a legacy v1 cache must survive before it is retired. One
     * cache root is shared by every build pointed at it, so in a mixed-version
     * fleet an older build keeps reading and writing target-v1-* while a newer
     * build publishes target-v2-*. Deleting a v1 cache that is still in use
     * only forces the older build to refetch the whole catalog on its next
     * run, so retirement waits until nothing has touched the directory for a
     * full week. The gate is deliberately conservative: a still-active v1
     * cache is simply left alone and re-checked by a later session.
     */
    private static final long RETIRE_V1_MIN_IDLE_MILLIS = TimeUnit.DAYS.toMillis(7);
    /**
     * Depth of the idle probe below a v1 target directory. Reaches the pack
     * files at {@code reader-packs/<qualifier>/<file>} and keeps the scan
     * bounded; the probe short-circuits on the first recent entry.
     */
    private static final int RETIRE_V1_SCAN_DEPTH = 4;
    private static final int MANIFEST_BYTES = MANIFEST_MAGIC.length
            + Integer.BYTES + Long.BYTES * 3 + Integer.BYTES
            + PgPackedCatalogHashes.MD5_BYTES + 1
            + PgCatalogReaderPackGeneration.SNAPSHOT_DIGEST_BYTES
            + Integer.BYTES;

    static final int MAX_MANIFEST_BYTES = MANIFEST_BYTES;
    /**
     * Fresh files cover the short cross-version window before a writer owns
     * its sidecar lock; the lock itself protects writers of any duration.
     */
    static final long STALE_TEMP_MIN_AGE_MILLIS =
            TimeUnit.HOURS.toMillis(24);
    /**
     * The cache tree describes one exact database target and holds full
     * catalog rows, so every file and directory this store creates stays
     * private to its owner. File systems without POSIX permissions (Windows)
     * fall back to the platform default, exactly as before.
     */
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");
    private static final FileAttribute<?>[] NO_ATTRIBUTES = {};
    private static final Set<OpenOption> LOCK_OPTIONS =
            Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    private static final Set<OpenOption> CREATE_NEW_OPTIONS =
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

    private final Path root;
    private final AtomicBoolean legacyRetirementCompleted =
            new AtomicBoolean();

    PgCatalogReaderPackStore(Path root) {
        this.root = java.util.Objects.requireNonNull(root, "root");
    }

    Path createTemporaryPack(String qualifier, UUID generation)
            throws IOException {
        Path directory = readerDirectory(qualifier);
        createPrivateDirectories(directory);
        return directory.resolve(PACK_PREFIX + generation + TEMP_SUFFIX);
    }

    Path manifestPath(String qualifier) {
        return readerDirectory(qualifier).resolve(MANIFEST_FILE);
    }

    Path packPath(String qualifier, PgCatalogReaderPackGeneration generation) {
        return readerDirectory(qualifier).resolve(PACK_PREFIX
                + generation.packManifest().generationId() + PACK_SUFFIX);
    }

    PgCatalogReaderPackGeneration readCurrent(String qualifier,
            IMonitor monitor) throws InterruptedException {
        IMonitor.checkCancelled(monitor);
        Path manifest = manifestPath(qualifier);
        byte[] bytes;
        try {
            long size = Files.size(manifest);
            if (size != MANIFEST_BYTES || size > MAX_MANIFEST_BYTES) {
                return null;
            }
            bytes = Files.readAllBytes(manifest);
        } catch (IOException ex) {
            return null;
        }
        IMonitor.checkCancelled(monitor);
        try {
            PgCatalogReaderPackGeneration generation = decode(bytes);
            Path pack = packPath(qualifier, generation);
            if (!Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(pack)
                            != generation.packManifest().packSize()) {
                return null;
            }
            return generation;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    boolean publish(String qualifier, Path temporary,
            PgCatalogReaderPackManifest packManifest, byte[] snapshotDigest,
            IMonitor monitor) throws IOException, InterruptedException {
        java.util.Objects.requireNonNull(temporary, "temporary");
        java.util.Objects.requireNonNull(packManifest, "packManifest");
        var generation = new PgCatalogReaderPackGeneration(packManifest,
                snapshotDigest);
        Path directory = readerDirectory(qualifier);
        Path lockPath = directory.resolve(PUBLISH_LOCK_FILE);
        try {
            createPrivateDirectories(directory);
            try (FileChannel lockChannel = openPrivateFile(lockPath,
                    LOCK_OPTIONS)) {
                FileLock lock;
                try {
                    lock = lockChannel.tryLock();
                } catch (OverlappingFileLockException ex) {
                    deleteQuietly(temporary);
                    return false;
                }
                if (lock == null) {
                    deleteQuietly(temporary);
                    return false;
                }
                try (lock) {
                    return publishLocked(qualifier, temporary, generation,
                            monitor);
                }
            }
        } catch (IOException | InterruptedException | RuntimeException ex) {
            // A failure before publishLocked takes ownership must not leave an
            // unbounded temporary file that size-cap pruning intentionally
            // ignores.
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                deleteQuietly(temporary);
            }
            throw ex;
        }
    }

    boolean refreshSnapshot(String qualifier,
            PgCatalogReaderPackGeneration expected, byte[] snapshotDigest,
            IMonitor monitor) throws IOException, InterruptedException {
        java.util.Objects.requireNonNull(expected, "expected");
        java.util.Objects.requireNonNull(snapshotDigest, "snapshotDigest");
        var refreshed = new PgCatalogReaderPackGeneration(
                expected.packManifest(), snapshotDigest);
        Path directory = readerDirectory(qualifier);
        Path lockPath = directory.resolve(PUBLISH_LOCK_FILE);
        createPrivateDirectories(directory);
        try (FileChannel lockChannel = openPrivateFile(lockPath,
                LOCK_OPTIONS)) {
            FileLock lock;
            try {
                lock = lockChannel.tryLock();
            } catch (OverlappingFileLockException ex) {
                return false;
            }
            if (lock == null) {
                return false;
            }
            try (lock) {
                PgCatalogReaderPackGeneration current = readCurrent(
                        qualifier, monitor);
                if (!samePack(current, expected)) {
                    return false;
                }
                writeManifestDurably(qualifier, refreshed, monitor);
                PgCatalogReaderPackGeneration durable = readCurrent(
                        qualifier, monitor);
                if (!samePack(durable, refreshed)
                        || !Arrays.equals(durable.snapshotDigest(),
                                refreshed.snapshotDigest())) {
                    throw new IOException(
                            "Refreshed catalog snapshot manifest is not durable");
                }
                return true;
            }
        }
    }

    long pruneToLimit(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException(
                    "Catalog cache size cap must be positive");
        }
        Path pruneRoot;
        try {
            pruneRoot = root.toRealPath();
        } catch (IOException ex) {
            return 0L;
        }
        Path pruneLock = pruneRoot.resolve(
                ContentAddressedFileStore.PRUNE_LOCK_FILE);
        try (FileChannel channel = openPrivateFile(pruneLock,
                LOCK_OPTIONS)) {
            FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException | IOException ex) {
                return 0L;
            }
            if (acquired == null) {
                return 0L;
            }
            try (acquired) {
                return pruneLocked(pruneRoot, maxBytes);
            }
        } catch (IOException ex) {
            return 0L;
        }
    }

    private boolean publishLocked(String qualifier, Path temporary,
            PgCatalogReaderPackGeneration generation, IMonitor monitor)
            throws IOException, InterruptedException {
        IMonitor.checkCancelled(monitor);
        PgCatalogReaderPackManifest packManifest = generation.packManifest();
        if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)
                || Files.size(temporary) != packManifest.packSize()) {
            deleteQuietly(temporary);
            return false;
        }

        Path pack = packPath(qualifier, generation);
        Path manifest = manifestPath(qualifier);
        Path manifestTemp = manifest.resolveSibling(MANIFEST_FILE + "-"
                + packManifest.generationId() + TEMP_SUFFIX);
        boolean packPublished = false;
        try {
            moveAtomically(temporary, pack);
            packPublished = true;
            IMonitor.checkCancelled(monitor);
            byte[] payload = encode(generation);
            try (FileChannel channel = openPrivateFile(manifestTemp,
                    CREATE_NEW_OPTIONS)) {
                PgCatalogReaderPackFormat.writeFully(channel,
                        ByteBuffer.wrap(payload));
                IMonitor.checkCancelled(monitor);
                channel.force(true);
            }
            moveAtomically(manifestTemp, manifest);
            forceDirectoryBestEffort(manifest.getParent());
            if (!isCurrentGeneration(qualifier, generation)) {
                throw new IOException(
                        "Published catalog pack generation is not durable");
            }
            cleanupAfterDurablePublish(qualifier, pack);
            return true;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            deleteQuietly(manifestTemp);
            if (packPublished) {
                deleteQuietly(pack);
            } else {
                deleteQuietly(temporary);
            }
            throw ex;
        }
    }

    private void writeManifestDurably(String qualifier,
            PgCatalogReaderPackGeneration generation, IMonitor monitor)
            throws IOException, InterruptedException {
        IMonitor.checkCancelled(monitor);
        Path manifest = manifestPath(qualifier);
        Path manifestTemp = manifest.resolveSibling(MANIFEST_FILE + "-"
                + generation.packManifest().generationId() + '-'
                + UUID.randomUUID() + TEMP_SUFFIX);
        try {
            byte[] payload = encode(generation);
            try (FileChannel channel = openPrivateFile(manifestTemp,
                    CREATE_NEW_OPTIONS)) {
                PgCatalogReaderPackFormat.writeFully(channel,
                        ByteBuffer.wrap(payload));
                IMonitor.checkCancelled(monitor);
                channel.force(true);
            }
            moveAtomically(manifestTemp, manifest);
            forceDirectoryBestEffort(manifest.getParent());
        } finally {
            deleteQuietly(manifestTemp);
        }
    }

    private static boolean samePack(PgCatalogReaderPackGeneration left,
            PgCatalogReaderPackGeneration right) {
        return left != null && right != null
                && left.packManifest().generationId().equals(
                        right.packManifest().generationId())
                && left.packManifest().packSize()
                        == right.packManifest().packSize()
                && left.packManifest().rowCount()
                        == right.packManifest().rowCount()
                && Arrays.equals(left.packManifest().orderedFingerprint(),
                        right.packManifest().orderedFingerprint());
    }

    private void cleanupAfterDurablePublish(String qualifier, Path pack) {
        try {
            retireUnreferencedPacks(qualifier, pack);
            cleanupStaleTemporaryPacks(readerDirectory(qualifier));
            retireDurableLegacyCaches();
        } catch (RuntimeException ex) {
            // cleanup must never invalidate an already durable generation
        }
    }

    private static void cleanupStaleTemporaryPacks(Path directory) {
        List<PruneCandidate> stale = new ArrayList<>();
        addStaleTemporaryCandidates(stale, directory,
                System.currentTimeMillis());
        stale.forEach(PgCatalogReaderPackStore::deleteTemporaryCandidate);
    }

    private long pruneLocked(Path pruneRoot, long maxBytes) {
        List<PruneCandidate> candidates = listPruneCandidates(pruneRoot);
        long total = candidates.stream().mapToLong(PruneCandidate::size).sum();
        if (total <= maxBytes) {
            return 0L;
        }
        candidates.sort(Comparator.comparing(PruneCandidate::lastModified)
                .thenComparing(PruneCandidate::path));
        long removed = 0L;
        for (PruneCandidate candidate : candidates) {
            if (total - removed <= maxBytes) {
                break;
            }
            removed += candidate.readerDirectory()
                    ? deleteReaderGeneration(candidate)
                    : candidate.temporary()
                            ? deleteTemporaryCandidate(candidate)
                            : deleteRegularCandidate(candidate);
        }
        return removed;
    }

    private List<PruneCandidate> listPruneCandidates(Path pruneRoot) {
        List<PruneCandidate> candidates = new ArrayList<>();
        Path packsRoot = pruneRoot.resolve(PACKS_DIRECTORY);
        try (Stream<Path> walk = Files.walk(pruneRoot)) {
            walk.filter(path -> Files.isRegularFile(path,
                            LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString()
                            .endsWith(PACK_SUFFIX))
                    .filter(path -> !path.startsWith(packsRoot))
                    .forEach(path -> addRegularCandidate(candidates, path));
        } catch (IOException ex) {
            return List.of();
        }
        if (!Files.isDirectory(packsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return candidates;
        }
        try (Stream<Path> walk = Files.walk(packsRoot, 2)) {
            walk.filter(path -> Files.isDirectory(path,
                            LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> packsRoot.relativize(path)
                            .getNameCount() == 2)
                    .forEach(path -> {
                        addReaderCandidate(candidates, path);
                        addStaleTemporaryCandidates(candidates, path,
                                System.currentTimeMillis());
                    });
        } catch (IOException ex) {
            // candidates already collected remain safe to prune
        }
        return candidates;
    }

    private static void addRegularCandidate(List<PruneCandidate> target,
            Path path) {
        try {
            target.add(new PruneCandidate(path, Files.size(path),
                    Files.getLastModifiedTime(path), false, false));
        } catch (IOException ex) {
            // concurrently removed
        }
    }

    private static void addReaderCandidate(List<PruneCandidate> target,
            Path directory) {
        try {
            String qualifier = directory.getFileName().toString();
            requireQualifier(qualifier);
            ReaderFiles files = readerFiles(directory);
            if (files.size() > 0L) {
                target.add(new PruneCandidate(directory, files.size(),
                        files.lastModified(), true, false));
            }
        } catch (IOException | IllegalArgumentException ex) {
            // malformed or concurrently changed reader directory
        }
    }

    private static ReaderFiles readerFiles(Path directory) throws IOException {
        long size = 0L;
        FileTime newest = FileTime.fromMillis(0L);
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> Files.isRegularFile(path,
                            LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString()
                            .endsWith(PACK_SUFFIX)).toList()) {
                size = Math.addExact(size, Files.size(file));
                FileTime modified = Files.getLastModifiedTime(file);
                if (modified.compareTo(newest) > 0) {
                    newest = modified;
                }
            }
        }
        return new ReaderFiles(size, newest);
    }

    private static long deleteRegularCandidate(PruneCandidate candidate) {
        try {
            return Files.deleteIfExists(candidate.path())
                    ? candidate.size() : 0L;
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static void addStaleTemporaryCandidates(
            List<PruneCandidate> target, Path directory, long nowMillis) {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> Files.isRegularFile(path,
                            LinkOption.NOFOLLOW_LINKS))
                    .filter(PgCatalogReaderPackStore
                            ::isGenerationTemporaryPack)
                    .toList()) {
                FileTime modified = Files.getLastModifiedTime(file);
                if (isStaleTemporaryPack(modified, nowMillis)
                        && temporaryCleanupLockAvailable(file)) {
                    target.add(new PruneCandidate(file, Files.size(file),
                            modified, false, true));
                }
            }
        } catch (IOException | RuntimeException ex) {
            // a concurrent writer or cleaner owns the temporary generation
        }
    }

    private static long deleteTemporaryCandidate(PruneCandidate candidate) {
        Path temporary = candidate.path();
        Path lockPath = temporaryLockPath(temporary);
        long removed;
        try (FileChannel channel = openPrivateFile(lockPath,
                LOCK_OPTIONS)) {
            FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException | IOException ex) {
                return 0L;
            }
            if (acquired == null) {
                return 0L;
            }
            try (acquired) {
                if (!Files.isRegularFile(temporary,
                        LinkOption.NOFOLLOW_LINKS)) {
                    return 0L;
                }
                FileTime modified = Files.getLastModifiedTime(temporary);
                if (modified.compareTo(candidate.lastModified()) > 0
                        || !isStaleTemporaryPack(modified,
                                System.currentTimeMillis())) {
                    return 0L;
                }
                if (!Files.deleteIfExists(temporary)) {
                    return 0L;
                }
                removed = candidate.size();
            }
        } catch (IOException | RuntimeException ex) {
            return 0L;
        }
        deleteQuietly(lockPath);
        return removed;
    }

    private static boolean temporaryCleanupLockAvailable(Path temporary) {
        Path lockPath = temporaryLockPath(temporary);
        try (FileChannel channel = openPrivateFile(lockPath,
                LOCK_OPTIONS)) {
            try (FileLock lock = channel.tryLock()) {
                return lock != null;
            } catch (OverlappingFileLockException ex) {
                return false;
            }
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static boolean isGenerationTemporaryPack(Path path) {
        String name = path.getFileName().toString();
        if (!name.startsWith(PACK_PREFIX) || !name.endsWith(TEMP_SUFFIX)) {
            return false;
        }
        try {
            UUID.fromString(name.substring(PACK_PREFIX.length(),
                    name.length() - TEMP_SUFFIX.length()));
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean isStaleTemporaryPack(FileTime modified,
            long nowMillis) {
        return modified.toMillis()
                <= nowMillis - STALE_TEMP_MIN_AGE_MILLIS;
    }

    static Path temporaryLockPath(Path temporary) {
        return temporary.resolveSibling(
                temporary.getFileName() + TEMP_LOCK_SUFFIX);
    }

    private long deleteReaderGeneration(PruneCandidate candidate) {
        Path directory = candidate.path();
        Path lockPath = directory.resolve(PUBLISH_LOCK_FILE);
        try (FileChannel channel = openPrivateFile(lockPath,
                LOCK_OPTIONS)) {
            FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException | IOException ex) {
                return 0L;
            }
            if (acquired == null) {
                return 0L;
            }
            try (acquired) {
                ReaderFiles current = readerFiles(directory);
                if (current.lastModified().compareTo(
                        candidate.lastModified()) > 0) {
                    return 0L;
                }
                long removed = deleteMeasured(directory.resolve(MANIFEST_FILE));
                try (Stream<Path> files = Files.list(directory)) {
                    for (Path file : files.filter(path -> Files.isRegularFile(
                                    path, LinkOption.NOFOLLOW_LINKS))
                            .filter(path -> path.getFileName().toString()
                                    .startsWith(PACK_PREFIX))
                            .filter(path -> path.getFileName().toString()
                                    .endsWith(PACK_SUFFIX)).toList()) {
                        removed = Math.addExact(removed,
                                deleteMeasured(file));
                    }
                }
                return removed;
            }
        } catch (IOException | ArithmeticException ex) {
            return 0L;
        }
    }

    private static long deleteMeasured(Path path) {
        try {
            long size = Files.size(path);
            return Files.deleteIfExists(path) ? size : 0L;
        } catch (IOException ex) {
            return 0L;
        }
    }

    private boolean isCurrentGeneration(String qualifier,
            PgCatalogReaderPackGeneration expected) {
        try {
            byte[] manifest = Files.readAllBytes(manifestPath(qualifier));
            PgCatalogReaderPackGeneration actual = decode(manifest);
            Path pack = packPath(qualifier, actual);
            return actual.packManifest().generationId().equals(
                    expected.packManifest().generationId())
                    && actual.packManifest().packSize()
                            == expected.packManifest().packSize()
                    && Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(pack)
                            == expected.packManifest().packSize();
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private void retireDurableLegacyCaches() {
        deleteTree(root.resolve("rows"));
        deleteTree(root.resolve("row-manifests"));
        retireV1TargetDirectories();
    }

    private void retireV1TargetDirectories() {
        Path name = root.getFileName();
        Path base = root.getParent();
        if (name == null || base == null
                || !isTargetDirectory(name.toString(), TARGET_V2_PREFIX)
                || legacyRetirementCompleted.get()) {
            return;
        }
        Path lockPath = base.resolve(RETIRE_V1_LOCK);
        try (FileChannel channel = openPrivateFile(lockPath,
                LOCK_OPTIONS)) {
            FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException | IOException ex) {
                return;
            }
            if (acquired == null) {
                return;
            }
            try (acquired) {
                if (!legacyRetirementCompleted.compareAndSet(false, true)) {
                    return;
                }
                List<Path> retired = new ArrayList<>();
                long now = System.currentTimeMillis();
                try (Stream<Path> siblings = Files.list(base)) {
                    for (Path sibling : siblings.toList()) {
                        String siblingName = sibling.getFileName().toString();
                        if (Files.isDirectory(sibling,
                                LinkOption.NOFOLLOW_LINKS)
                                && isTargetDirectory(siblingName,
                                        TARGET_V1_PREFIX)) {
                            if (!isIdleV1Directory(sibling, now)) {
                                // still used by an older build in this fleet
                                continue;
                            }
                            Path marker = base.resolve(RETIRED_V1_PREFIX
                                    + siblingName.substring(
                                            TARGET_V1_PREFIX.length())
                                    + '-' + UUID.randomUUID());
                            try {
                                moveAtomically(sibling, marker);
                                retired.add(marker);
                            } catch (IOException ex) {
                                // another process may still own the v1 cache
                            }
                        } else if (Files.isDirectory(sibling,
                                LinkOption.NOFOLLOW_LINKS)
                                && siblingName.startsWith(
                                        RETIRED_V1_PREFIX)
                                && isRetiredV1Directory(siblingName)) {
                            retired.add(sibling);
                        }
                    }
                }
                retired.forEach(PgCatalogReaderPackStore::deleteTree);
            }
        } catch (IOException ex) {
            // legacy cleanup is best-effort after durable v2 publication
        }
    }

    /**
     * Reports whether nothing has touched a legacy v1 cache for at least
     * {@link #RETIRE_V1_MIN_IDLE_MILLIS}. Any unreadable or concurrently
     * changing entry counts as recent activity so the cache is kept.
     */
    private static boolean isIdleV1Directory(Path directory, long nowMillis) {
        long cutoff = nowMillis - RETIRE_V1_MIN_IDLE_MILLIS;
        try (Stream<Path> tree = Files.walk(directory, RETIRE_V1_SCAN_DEPTH)) {
            return tree.noneMatch(path -> isModifiedAfter(path, cutoff));
        } catch (IOException | UncheckedIOException ex) {
            return false;
        }
    }

    private static boolean isModifiedAfter(Path path, long cutoffMillis) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                    .toMillis() > cutoffMillis;
        } catch (IOException ex) {
            return true;
        }
    }

    private static boolean isTargetDirectory(String name, String prefix) {
        if (!name.startsWith(prefix) || name.length() != prefix.length() + 64) {
            return false;
        }
        for (int i = prefix.length(); i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRetiredV1Directory(String name) {
        int digestStart = RETIRED_V1_PREFIX.length();
        int uuidSeparator = digestStart + 64;
        if (name.length() != uuidSeparator + 1 + 36
                || name.charAt(uuidSeparator) != '-') {
            return false;
        }
        for (int i = digestStart; i < uuidSeparator; i++) {
            char c = name.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        try {
            UUID.fromString(name.substring(uuidSeparator + 1));
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static void forceDirectoryBestEffort(Path directory) {
        try (FileChannel channel = FileChannel.open(directory,
                StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException
                | SecurityException ex) {
            // atomic rename plus forced files remains the portable baseline
        }
    }

    private void retireUnreferencedPacks(String qualifier, Path current) {
        Path directory = readerDirectory(qualifier);
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> Files.isRegularFile(path,
                            LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString()
                            .startsWith(PACK_PREFIX))
                    .filter(path -> path.getFileName().toString()
                            .endsWith(PACK_SUFFIX))
                    .filter(path -> !path.equals(current))
                    .forEach(PgCatalogReaderPackStore::deleteQuietly);
        } catch (IOException | RuntimeException ex) {
            // size-cap pruning will eventually remove an unreferenced pack
        }
    }

    private Path readerDirectory(String qualifier) {
        requireQualifier(qualifier);
        return root.resolve(PACKS_DIRECTORY)
                .resolve(qualifier.substring(1, 3)).resolve(qualifier);
    }

    private static byte[] encode(PgCatalogReaderPackGeneration generation) {
        PgCatalogReaderPackManifest pack = generation.packManifest();
        byte[] snapshot = generation.snapshotDigest();
        ByteBuffer target = ByteBuffer.allocate(MANIFEST_BYTES)
                .put(MANIFEST_MAGIC)
                .putInt(MANIFEST_VERSION)
                .putLong(pack.generationId().getMostSignificantBits())
                .putLong(pack.generationId().getLeastSignificantBits())
                .putLong(pack.packSize())
                .putInt(pack.rowCount())
                .put(pack.orderedFingerprint())
                .put((byte) (snapshot == null ? 0 : 1));
        if (snapshot == null) {
            target.put(new byte[PgCatalogReaderPackGeneration.SNAPSHOT_DIGEST_BYTES]);
        } else {
            target.put(snapshot);
        }
        var crc = new CRC32C();
        crc.update(target.array(), 0, target.position());
        target.putInt((int) crc.getValue());
        return target.array();
    }

    private static PgCatalogReaderPackGeneration decode(byte[] bytes)
            throws IOException {
        if (bytes.length != MANIFEST_BYTES) {
            throw new IOException("Invalid catalog pack manifest size");
        }
        var crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - Integer.BYTES);
        ByteBuffer source = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[MANIFEST_MAGIC.length];
        source.get(magic);
        if (!Arrays.equals(magic, MANIFEST_MAGIC)
                || source.getInt() != MANIFEST_VERSION) {
            throw new IOException("Invalid catalog pack manifest header");
        }
        UUID generation = new UUID(source.getLong(), source.getLong());
        long packSize = source.getLong();
        int rowCount = source.getInt();
        byte[] fingerprint = new byte[PgPackedCatalogHashes.MD5_BYTES];
        source.get(fingerprint);
        byte snapshotPresent = source.get();
        byte[] snapshot = new byte[PgCatalogReaderPackGeneration.SNAPSHOT_DIGEST_BYTES];
        source.get(snapshot);
        int expectedCrc = source.getInt();
        if (expectedCrc != (int) crc.getValue()
                || (snapshotPresent != 0 && snapshotPresent != 1)) {
            throw new IOException("Invalid catalog pack manifest checksum");
        }
        var pack = new PgCatalogReaderPackManifest(generation, packSize,
                rowCount, fingerprint);
        return new PgCatalogReaderPackGeneration(pack,
                snapshotPresent == 0 ? null : snapshot);
    }

    /**
     * Creates a directory and every missing parent with owner-only access.
     * The permissions are requested atomically through the POSIX view where
     * the file system supports it, so the {@code target-v*} cache root and
     * the reader directories below it are never world-readable.
     */
    private static void createPrivateDirectories(Path directory)
            throws IOException {
        Files.createDirectories(directory,
                directoryAttributes(directory));
    }

    /** Opens a store file with owner-only access where POSIX is supported. */
    private static FileChannel openPrivateFile(Path file,
            Set<OpenOption> options) throws IOException {
        return FileChannel.open(file, options, fileAttributes(file));
    }

    private static FileAttribute<?>[] fileAttributes(Path path) {
        return supportsPosix(path)
                ? new FileAttribute<?>[] {
                        PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE) }
                : NO_ATTRIBUTES;
    }

    private static FileAttribute<?>[] directoryAttributes(Path path) {
        return supportsPosix(path)
                ? new FileAttribute<?>[] { PosixFilePermissions
                        .asFileAttribute(OWNER_ONLY_DIRECTORY) }
                : NO_ATTRIBUTES;
    }

    private static boolean supportsPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews()
                .contains("posix");
    }

    private static void moveAtomically(Path source, Path target)
            throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ex) {
            // cache cleanup is best-effort
        }
    }

    private static void deleteTree(Path directory) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(PgCatalogReaderPackStore::deleteQuietly);
        } catch (IOException | RuntimeException ex) {
            // legacy cache cleanup is best-effort after durable publication
        }
    }

    private static void requireQualifier(String qualifier) {
        if (qualifier == null || qualifier.length() != 65
                || qualifier.charAt(0) != 'n') {
            throw new IllegalArgumentException("Invalid catalog reader qualifier");
        }
        for (int i = 1; i < qualifier.length(); i++) {
            char c = qualifier.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                throw new IllegalArgumentException(
                        "Invalid catalog reader qualifier");
            }
        }
    }

    private record PruneCandidate(Path path, long size,
            FileTime lastModified, boolean readerDirectory,
            boolean temporary) {
    }

    private record ReaderFiles(long size, FileTime lastModified) {
    }
}
