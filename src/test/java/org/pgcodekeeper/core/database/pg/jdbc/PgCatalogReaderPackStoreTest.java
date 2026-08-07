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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.utils.ContentAddressedFileStore;

class PgCatalogReaderPackStoreTest {

    private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    @TempDir
    private Path root;

    @Test
    void publishesOneAtomicGenerationWithBoundedCrcManifest() throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "ab".repeat(32);
        UUID generation = UUID.randomUUID();
        Path temporary = store.createTemporaryPack(qualifier, generation);
        Files.write(temporary, new byte[] { 1, 2, 3 });
        var pack = new PgCatalogReaderPackManifest(generation, 3, 2,
                new byte[PgPackedCatalogHashes.MD5_BYTES]);
        byte[] snapshot = new byte[PgCatalogReaderPackGeneration.SNAPSHOT_DIGEST_BYTES];
        snapshot[0] = 7;

        assertTrue(store.publish(qualifier, temporary, pack, snapshot, null));
        PgCatalogReaderPackGeneration current = store.readCurrent(qualifier, null);

        assertEquals(pack.generationId(), current.packManifest().generationId());
        assertEquals(pack.packSize(), current.packManifest().packSize());
        assertEquals(pack.rowCount(), current.packManifest().rowCount());
        assertArrayEquals(pack.orderedFingerprint(),
                current.packManifest().orderedFingerprint());
        assertArrayEquals(snapshot, current.snapshotDigest());
        assertEquals(new byte[] { 1, 2, 3 }.length,
                Files.size(store.packPath(qualifier, current)));
        assertFalse(Files.exists(temporary));
        assertTrue(Files.size(store.manifestPath(qualifier))
                <= PgCatalogReaderPackStore.MAX_MANIFEST_BYTES);
    }

    /**
     * A published generation describes one exact database target and holds
     * full catalog rows, so nothing the store creates below the cache root -
     * directories, manifest, packs or lock files - may be readable by other
     * users of the machine.
     */
    @Test
    void everyPublishedCacheFileAndDirectoryStaysOwnerOnly() throws Exception {
        Path target = root.resolve("target-v2-" + "ab".repeat(32));
        var store = new PgCatalogReaderPackStore(target);
        String qualifier = "n" + "ef".repeat(32);
        UUID generation = UUID.randomUUID();
        Path temporary = store.createTemporaryPack(qualifier, generation);
        Files.write(temporary, new byte[] { 1, 2, 3 });
        // the real pack writer creates its file owner-only; publication
        // only moves it, so the fixture reproduces that starting point
        Files.setPosixFilePermissions(temporary, OWNER_ONLY_FILE);
        var pack = new PgCatalogReaderPackManifest(generation, 3, 2,
                new byte[PgPackedCatalogHashes.MD5_BYTES]);
        assertTrue(store.publish(qualifier, temporary, pack, null, null));
        store.pruneToLimit(1L << 30);

        assumeTrue(target.getFileSystem().supportedFileAttributeViews()
                .contains("posix"), "POSIX permissions are unavailable");
        List<Path> shared = new ArrayList<>();
        try (var tree = Files.walk(target)) {
            for (Path path : tree.toList()) {
                Set<PosixFilePermission> actual =
                        Files.getPosixFilePermissions(path);
                Set<PosixFilePermission> expected =
                        Files.isDirectory(path) ? OWNER_ONLY_DIRECTORY
                                : OWNER_ONLY_FILE;
                if (!expected.equals(actual)) {
                    shared.add(path);
                }
            }
        }

        assertEquals(List.of(), shared,
                "every cache entry must be private to its owner");
        assertTrue(Files.exists(target.resolve(
                ContentAddressedFileStore.PRUNE_LOCK_FILE)),
                "the prune lock is part of the checked tree");
        assertEquals(OWNER_ONLY_DIRECTORY,
                Files.getPosixFilePermissions(target),
                "the target cache root itself must be private");
    }

    @Test
    void corruptOrOversizedManifestIsAColdMissWithoutRacingPublication()
            throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "cd".repeat(32);
        Path manifest = store.manifestPath(qualifier);
        Files.createDirectories(manifest.getParent());
        Files.write(manifest, new byte[] { 1, 2, 3 });

        assertNull(store.readCurrent(qualifier, null));

        Files.write(manifest,
                new byte[PgCatalogReaderPackStore.MAX_MANIFEST_BYTES + 1]);
        assertNull(store.readCurrent(qualifier, null));
        assertTrue(Files.exists(manifest),
                "readers must not delete a manifest that a publisher may replace");
    }

    @Test
    void replacingManifestRetiresTheOldUnreferencedPack() throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "ef".repeat(32);
        PgCatalogReaderPackGeneration first = publish(store, qualifier, (byte) 1);
        Path firstPack = store.packPath(qualifier, first);
        PgCatalogReaderPackGeneration second = publish(store, qualifier, (byte) 2);

        assertFalse(Files.exists(firstPack));
        assertTrue(Files.exists(store.packPath(qualifier, second)));
        assertEquals(second.packManifest().generationId(),
                store.readCurrent(qualifier, null).packManifest().generationId());
    }

    @Test
    void refreshesOnlySnapshotMetadataForTheCurrentImmutablePack()
            throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "f1".repeat(32);
        PgCatalogReaderPackGeneration current = publish(store, qualifier,
                (byte) 4);
        Path pack = store.packPath(qualifier, current);
        byte[] packBytes = Files.readAllBytes(pack);
        byte[] snapshot = new byte[
                PgCatalogReaderPackGeneration.SNAPSHOT_DIGEST_BYTES];
        snapshot[0] = 23;

        assertTrue(store.refreshSnapshot(qualifier, current, snapshot, null));
        PgCatalogReaderPackGeneration refreshed = store.readCurrent(
                qualifier, null);

        assertEquals(current.packManifest().generationId(),
                refreshed.packManifest().generationId());
        assertArrayEquals(snapshot, refreshed.snapshotDigest());
        assertArrayEquals(packBytes, Files.readAllBytes(pack));
    }

    @Test
    void staleGenerationCannotRefreshTheCurrentSnapshotMetadata()
            throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "f2".repeat(32);
        PgCatalogReaderPackGeneration stale = publish(store, qualifier,
                (byte) 5);
        PgCatalogReaderPackGeneration current = publish(store, qualifier,
                (byte) 6);
        byte[] snapshot = new byte[
                PgCatalogReaderPackGeneration.SNAPSHOT_DIGEST_BYTES];
        snapshot[0] = 24;

        assertFalse(store.refreshSnapshot(qualifier, stale, snapshot, null));

        PgCatalogReaderPackGeneration unchanged = store.readCurrent(
                qualifier, null);
        assertEquals(current.packManifest().generationId(),
                unchanged.packManifest().generationId());
        assertNull(unchanged.snapshotDigest());
    }

    @Test
    void genericPruningCountsButNeverSplitsReaderPackGenerations()
            throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "12".repeat(32);
        PgCatalogReaderPackGeneration current = publish(store, qualifier,
                (byte) 1);

        new ContentAddressedFileStore(root).pruneToLimit(1L);

        assertEquals(current.packManifest().generationId(),
                store.readCurrent(qualifier, null)
                        .packManifest().generationId());
        assertTrue(Files.exists(store.manifestPath(qualifier)));
        assertTrue(Files.exists(store.packPath(qualifier, current)));
    }

    @Test
    void packAwarePruningRemovesOneGenerationAsALogicalUnit()
            throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "34".repeat(32);
        PgCatalogReaderPackGeneration current = publish(store, qualifier,
                (byte) 1);

        assertTrue(store.pruneToLimit(1L) > 0L);

        assertNull(store.readCurrent(qualifier, null));
        assertFalse(Files.exists(store.manifestPath(qualifier)));
        assertFalse(Files.exists(store.packPath(qualifier, current)));
    }

    @Test
    void pruningCannotCrossAConcurrentReaderPublishLock() throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "56".repeat(32);
        PgCatalogReaderPackGeneration current = publish(store, qualifier,
                (byte) 1);
        Path lockPath = store.manifestPath(qualifier).resolveSibling(
                ".publish.lock");

        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            assertEquals(0L, store.pruneToLimit(1L));
            assertNotNull(store.readCurrent(qualifier, null));
        }

        assertTrue(Files.exists(store.packPath(qualifier, current)));
    }

    @Test
    void firstDurableV2GenerationRetiresOnlyExactV1TargetDirectories()
            throws Exception {
        Path base = root.resolve("cache-base");
        Path v2 = base.resolve("target-v2-" + "aa".repeat(32));
        Path legacyV1 = base.resolve("target-v1-" + "bb".repeat(32));
        Path similarlyNamed = base.resolve("target-v1-backup");
        Files.createDirectories(legacyV1.resolve("rows"));
        Files.write(legacyV1.resolve("rows/old.bin"), new byte[] { 1 });
        Files.createDirectories(similarlyNamed);
        Files.write(similarlyNamed.resolve("keep.bin"), new byte[] { 2 });
        makeIdleForRetirement(legacyV1);
        var store = new PgCatalogReaderPackStore(v2);
        String qualifier = "n" + "78".repeat(32);

        assertTrue(Files.exists(legacyV1));
        PgCatalogReaderPackGeneration current = publish(store, qualifier,
                (byte) 3);

        assertFalse(Files.exists(legacyV1));
        assertTrue(Files.exists(similarlyNamed));
        assertNotNull(store.readCurrent(qualifier, null));
        assertTrue(Files.exists(store.packPath(qualifier, current)));
    }

    @Test
    void recentlyUsedV1TargetDirectoriesSurviveRetirement() throws Exception {
        Path base = root.resolve("mixed-fleet-base");
        Path v2 = base.resolve("target-v2-" + "1a".repeat(32));
        Path activeV1 = base.resolve("target-v1-" + "2b".repeat(32));
        Path idleV1 = base.resolve("target-v1-" + "3c".repeat(32));
        Files.createDirectories(activeV1.resolve("reader-packs/current"));
        Files.write(activeV1.resolve("reader-packs/current/pack.bin"),
                new byte[] { 1 });
        Files.createDirectories(idleV1.resolve("rows"));
        Files.write(idleV1.resolve("rows/old.bin"), new byte[] { 2 });
        makeIdleForRetirement(idleV1);
        var store = new PgCatalogReaderPackStore(v2);
        String qualifier = "n" + "4d".repeat(32);

        publish(store, qualifier, (byte) 5);

        // an older build in the fleet still owns the recently used v1 cache
        assertTrue(Files.exists(activeV1));
        assertTrue(Files.exists(
                activeV1.resolve("reader-packs/current/pack.bin")));
        assertFalse(Files.exists(idleV1));
    }

    @Test
    void deeplyNestedRecentV1EntriesBlockRetirement() throws Exception {
        Path base = root.resolve("nested-base");
        Path v2 = base.resolve("target-v2-" + "5e".repeat(32));
        Path activeV1 = base.resolve("target-v1-" + "6f".repeat(32));
        Path nested = activeV1.resolve("reader-packs/qualifier");
        Files.createDirectories(nested);
        Path recent = nested.resolve("generation.bin");
        Files.write(recent, new byte[] { 1 });
        makeIdleForRetirement(activeV1);
        // only the deepest file was touched by the older build
        Files.setLastModifiedTime(recent,
                FileTime.fromMillis(System.currentTimeMillis()));
        var store = new PgCatalogReaderPackStore(v2);

        publish(store, "n" + "7a".repeat(32), (byte) 8);

        assertTrue(Files.exists(activeV1));
        assertTrue(Files.exists(recent));
    }

    /**
     * Backdates a whole legacy tree past the retirement idle window, deepest
     * entries first so a parent's own timestamp is not refreshed afterwards.
     */
    private static void makeIdleForRetirement(Path directory)
            throws IOException {
        FileTime idle = FileTime.fromMillis(System.currentTimeMillis()
                - Duration.ofDays(30).toMillis());
        try (var tree = Files.walk(directory)) {
            List<Path> paths = new ArrayList<>(tree.toList());
            paths.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path path : paths) {
                Files.setLastModifiedTime(path, idle);
            }
        }
    }

    @Test
    void failedV2PublicationNeverRetiresV1Targets() throws Exception {
        Path base = root.resolve("failed-base");
        Path v2 = base.resolve("target-v2-" + "cc".repeat(32));
        Path legacyV1 = base.resolve("target-v1-" + "dd".repeat(32));
        Files.createDirectories(legacyV1);
        var store = new PgCatalogReaderPackStore(v2);
        String qualifier = "n" + "9a".repeat(32);
        UUID generation = UUID.randomUUID();
        Path temporary = store.createTemporaryPack(qualifier, generation);
        Files.write(temporary, new byte[] { 1 });
        var wrongSize = new PgCatalogReaderPackManifest(generation, 2, 0,
                new byte[PgPackedCatalogHashes.MD5_BYTES]);

        assertFalse(store.publish(qualifier, temporary, wrongSize, null,
                null));
        assertTrue(Files.exists(legacyV1));
    }

    @Test
    void publishLockFailureRemovesTheUnprunableTemporaryPack()
            throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "bc".repeat(32);
        UUID generation = UUID.randomUUID();
        Path temporary = store.createTemporaryPack(qualifier, generation);
        Files.write(temporary, new byte[] { 1 });
        Path lockPath = store.manifestPath(qualifier).resolveSibling(
                ".publish.lock");
        Files.createDirectory(lockPath);
        var pack = new PgCatalogReaderPackManifest(generation, 1, 0,
                new byte[PgPackedCatalogHashes.MD5_BYTES]);

        assertThrows(IOException.class, () -> store.publish(qualifier,
                temporary, pack, null, null));
        assertFalse(Files.exists(temporary));
    }

    @Test
    void pruningCountsAndRemovesStaleGenerationTemporaryPack()
            throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "d1".repeat(32);
        Path temporary = store.createTemporaryPack(qualifier,
                UUID.randomUUID());
        Files.write(temporary, new byte[] { 1, 2, 3, 4 });
        makeStale(temporary);

        assertEquals(4L, store.pruneToLimit(1L));
        assertFalse(Files.exists(temporary));
        assertFalse(Files.exists(
                PgCatalogReaderPackStore.temporaryLockPath(temporary)));
    }

    @Test
    void pruningPreservesFreshGenerationTemporaryPack() throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "d2".repeat(32);
        Path temporary = store.createTemporaryPack(qualifier,
                UUID.randomUUID());
        Files.write(temporary, new byte[] { 1, 2, 3, 4 });

        assertEquals(0L, store.pruneToLimit(1L));
        assertTrue(Files.exists(temporary));
    }

    @Test
    void pruningPreservesOldTemporaryPackOwnedByALiveWriter()
            throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "d3".repeat(32);
        Path temporary = store.createTemporaryPack(qualifier,
                UUID.randomUUID());

        try (var writer = new PgCatalogReaderPackWriter(temporary,
                new String[] { "value" }, 1024)) {
            makeStale(temporary);

            assertEquals(0L, store.pruneToLimit(1L));
            assertTrue(Files.exists(temporary));
            assertTrue(Files.exists(
                    PgCatalogReaderPackStore.temporaryLockPath(temporary)));
        }
        assertFalse(Files.exists(
                PgCatalogReaderPackStore.temporaryLockPath(temporary)));
    }

    @Test
    void durablePublishRemovesOtherStaleGenerationTemporaryPacks()
            throws Exception {
        var store = new PgCatalogReaderPackStore(root);
        String qualifier = "n" + "d4".repeat(32);
        Path stale = store.createTemporaryPack(qualifier,
                UUID.randomUUID());
        Files.write(stale, new byte[] { 1, 2, 3, 4 });
        makeStale(stale);
        Path fresh = store.createTemporaryPack(qualifier,
                UUID.randomUUID());
        Files.write(fresh, new byte[] { 5, 6, 7, 8 });

        PgCatalogReaderPackGeneration current = publish(store, qualifier,
                (byte) 9);

        assertFalse(Files.exists(stale));
        assertFalse(Files.exists(
                PgCatalogReaderPackStore.temporaryLockPath(stale)));
        assertTrue(Files.exists(fresh));
        assertNotNull(store.readCurrent(qualifier, null));
        assertTrue(Files.exists(store.packPath(qualifier, current)));
    }

    private static void makeStale(Path temporary) throws IOException {
        Files.setLastModifiedTime(temporary, FileTime.fromMillis(
                System.currentTimeMillis()
                        - PgCatalogReaderPackStore.STALE_TEMP_MIN_AGE_MILLIS
                        - 1_000L));
    }

    private static PgCatalogReaderPackGeneration publish(
            PgCatalogReaderPackStore store, String qualifier, byte value)
            throws Exception {
        UUID generation = UUID.randomUUID();
        Path temporary = store.createTemporaryPack(qualifier, generation);
        Files.write(temporary, new byte[] { value });
        var pack = new PgCatalogReaderPackManifest(generation, 1, 0,
                new byte[PgPackedCatalogHashes.MD5_BYTES]);
        assertTrue(store.publish(qualifier, temporary, pack, null, null));
        return store.readCurrent(qualifier, null);
    }
}
