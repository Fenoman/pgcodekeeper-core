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
package org.pgcodekeeper.core.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentAddressedFileStoreTest {

    private static final String CATEGORY = "bodies";
    private static final String QUALIFIER = "17-PLPGSQL_TEXT";
    private static final int KEYED_PAYLOAD_LIMIT = 8;

    @TempDir
    private Path root;

    @Test
    void roundtripPublishesVerifiedEntryWithoutTempLeftovers() throws IOException {
        var store = new ContentAddressedFileStore(root);
        byte[] content = "BEGIN RETURN; END".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(content);

        assertNull(store.read(CATEGORY, sha, QUALIFIER));
        assertTrue(store.write(CATEGORY, sha, QUALIFIER, content));
        assertArrayEquals(content, store.read(CATEGORY, sha, QUALIFIER));

        Path entry = entryPath(sha);
        assertTrue(Files.isRegularFile(entry));
        assertEquals(List.of(entry), regularFiles());

        // an already published entry is left untouched
        assertFalse(store.write(CATEGORY, sha, QUALIFIER, content));
        assertArrayEquals(content, store.read(CATEGORY, sha, QUALIFIER));
    }

    @Test
    void mismatchedPayloadIsNeverPublished() throws IOException {
        var store = new ContentAddressedFileStore(root);
        byte[] content = "SELECT 1".getBytes(StandardCharsets.UTF_8);
        String foreignSha = sha256Hex("SELECT 2".getBytes(StandardCharsets.UTF_8));

        assertFalse(store.write(CATEGORY, foreignSha, QUALIFIER, content));
        assertNull(store.read(CATEGORY, foreignSha, QUALIFIER));
        assertEquals(List.of(), regularFiles());
    }

    @Test
    void corruptEntryIsReportedAsMissAndDeleted() throws IOException {
        var store = new ContentAddressedFileStore(root);
        byte[] content = "BEGIN RETURN 1; END".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(content);
        assertTrue(store.write(CATEGORY, sha, QUALIFIER, content));

        Path entry = entryPath(sha);
        Files.write(entry, "garbage payload".getBytes(StandardCharsets.UTF_8));

        assertNull(store.read(CATEGORY, sha, QUALIFIER));
        assertFalse(Files.exists(entry));
    }

    @Test
    void utf8RoundtripStreamsVerifiedTextAndSharesEntriesWithByteReads() throws IOException {
        var store = new ContentAddressedFileStore(root);
        String text = "BEGIN RAISE NOTICE 'кэш каталога 😀'; END";
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(utf8);

        assertNull(store.readUtf8(CATEGORY, sha, QUALIFIER, utf8.length));
        assertTrue(store.writeUtf8(CATEGORY, sha, QUALIFIER, text));
        assertFalse(store.writeUtf8(CATEGORY, sha, QUALIFIER, text));
        assertEquals(text, store.readUtf8(CATEGORY, sha, QUALIFIER, utf8.length));
        assertArrayEquals(utf8, store.read(CATEGORY, sha, QUALIFIER));
        assertEquals(List.of(entryPath(sha)), regularFiles());
    }

    @Test
    void utf8WriteRefusesMismatchedAddressAndMalformedText() throws IOException {
        var store = new ContentAddressedFileStore(root);
        String text = "SELECT 'well-formed';";
        String foreignSha = sha256Hex("SELECT 'other';".getBytes(StandardCharsets.UTF_8));
        String malformed = "lone surrogate \uD800 tail";
        String malformedSha = sha256Hex("irrelevant".getBytes(StandardCharsets.UTF_8));

        assertFalse(store.writeUtf8(CATEGORY, foreignSha, QUALIFIER, text));
        assertFalse(store.writeUtf8(CATEGORY, malformedSha, QUALIFIER, malformed));
        assertEquals(List.of(), regularFiles());
    }

    @Test
    void utf8ReadDeletesCorruptAndUndecodableEntries() throws IOException {
        var store = new ContentAddressedFileStore(root);
        String text = "BEGIN RETURN 42; END";
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(utf8);

        assertTrue(store.writeUtf8(CATEGORY, sha, QUALIFIER, text));
        Files.write(entryPath(sha), "still utf-8 but wrong hash".getBytes(StandardCharsets.UTF_8));
        assertNull(store.readUtf8(CATEGORY, sha, QUALIFIER, utf8.length));
        assertFalse(Files.exists(entryPath(sha)));

        assertTrue(store.writeUtf8(CATEGORY, sha, QUALIFIER, text));
        Files.write(entryPath(sha), new byte[] { (byte) 0xFF, (byte) 0xFE, (byte) 0xFD });
        assertNull(store.readUtf8(CATEGORY, sha, QUALIFIER, utf8.length));
        assertFalse(Files.exists(entryPath(sha)));
    }

    @Test
    void deleteIsQuietForMissingEntries() throws IOException {
        var store = new ContentAddressedFileStore(root);
        byte[] content = "SELECT 3".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(content);

        store.delete(CATEGORY, sha, QUALIFIER);
        assertTrue(store.write(CATEGORY, sha, QUALIFIER, content));
        store.delete(CATEGORY, sha, QUALIFIER);
        assertNull(store.read(CATEGORY, sha, QUALIFIER));
        store.delete(CATEGORY, sha, QUALIFIER);
    }

    @Test
    void qualifierSeparatesEntriesOfTheSameContentHash() throws IOException {
        var store = new ContentAddressedFileStore(root);
        byte[] content = "BEGIN RETURN 2; END".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(content);

        assertTrue(store.write(CATEGORY, sha, "19-SQL_TEXT", content));
        assertNull(store.read(CATEGORY, sha, "19-PLPGSQL_TEXT"));
        assertArrayEquals(content, store.read(CATEGORY, sha, "19-SQL_TEXT"));
    }

    @Test
    void pruneRemovesOldestModifiedFilesUntilUnderCap() throws IOException {
        var store = new ContentAddressedFileStore(root);
        byte[] oldest = "oldest entry payload".getBytes(StandardCharsets.UTF_8);
        byte[] middle = "middle entry payload".getBytes(StandardCharsets.UTF_8);
        byte[] newest = "newest entry payload".getBytes(StandardCharsets.UTF_8);
        String oldestSha = sha256Hex(oldest);
        String middleSha = sha256Hex(middle);
        String newestSha = sha256Hex(newest);
        assertTrue(store.write(CATEGORY, oldestSha, QUALIFIER, oldest));
        assertTrue(store.write(CATEGORY, middleSha, QUALIFIER, middle));
        assertTrue(store.write(CATEGORY, newestSha, QUALIFIER, newest));
        Files.setLastModifiedTime(entryPath(oldestSha), FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(entryPath(middleSha), FileTime.fromMillis(2_000L));
        Files.setLastModifiedTime(entryPath(newestSha), FileTime.fromMillis(3_000L));

        // everything fits: nothing is removed
        assertEquals(0L, store.pruneToLimit(oldest.length + middle.length + newest.length));

        long removed = store.pruneToLimit(newest.length + middle.length);
        assertEquals(oldest.length, removed);
        assertNull(store.read(CATEGORY, oldestSha, QUALIFIER));
        assertArrayEquals(middle, store.read(CATEGORY, middleSha, QUALIFIER));
        assertArrayEquals(newest, store.read(CATEGORY, newestSha, QUALIFIER));

        removed = store.pruneToLimit(1L);
        assertEquals(middle.length + newest.length, removed);
        assertEquals(List.of(root.resolve(ContentAddressedFileStore.PRUNE_LOCK_FILE)),
                regularFiles());
    }

    @Test
    void pruneCountsAndDeletesOnlyPublishedBinEntries() throws IOException {
        var store = new ContentAddressedFileStore(root);
        Path directory = root.resolve("manual");
        Files.createDirectories(directory);
        Path published = directory.resolve("published.bin");
        Path backup = directory.resolve("published.bin.bak");
        Path uppercase = directory.resolve("uppercase-entry.BIN");
        byte[] publishedBytes = {1, 2, 3, 4};
        byte[] backupBytes = {5, 6, 7, 8, 9};
        byte[] uppercaseBytes = {10, 11, 12};
        Files.write(published, publishedBytes);
        Files.write(backup, backupBytes);
        Files.write(uppercase, uppercaseBytes);

        assertEquals(publishedBytes.length, store.pruneToLimit(1L));
        assertFalse(Files.exists(published));
        assertArrayEquals(backupBytes, Files.readAllBytes(backup));
        assertArrayEquals(uppercaseBytes, Files.readAllBytes(uppercase));
    }

    @Test
    void pruneKeepsDeletableEntriesWhenReaderPacksAlreadyFillTheCap()
            throws IOException {
        var store = new ContentAddressedFileStore(root);
        Path packDirectory = root.resolve("reader-packs").resolve("qualifier");
        Files.createDirectories(packDirectory);
        Path pack = packDirectory.resolve("generation.bin");
        Files.write(pack, new byte[100]);
        byte[] body = "reusable routine body".getBytes(StandardCharsets.UTF_8);
        String bodySha = sha256Hex(body);
        assertTrue(store.write(CATEGORY, bodySha, QUALIFIER, body));

        // packs alone exceed the cap and cannot be removed by this pruner:
        // wiping every body would still leave the store over the cap
        assertEquals(0L, store.pruneToLimit(50L));

        assertArrayEquals(body, store.read(CATEGORY, bodySha, QUALIFIER));
        assertEquals(100L, Files.size(pack));
    }

    @Test
    void pruneEnforcesTheCapLeftOverAfterNonDeletableReaderPacks()
            throws IOException {
        var store = new ContentAddressedFileStore(root);
        Path packDirectory = root.resolve("reader-packs").resolve("qualifier");
        Files.createDirectories(packDirectory);
        Path pack = packDirectory.resolve("generation.bin");
        Files.write(pack, new byte[10]);
        byte[] older = "older routine body xx".getBytes(StandardCharsets.UTF_8);
        byte[] newer = "newer routine body yy".getBytes(StandardCharsets.UTF_8);
        String olderSha = sha256Hex(older);
        String newerSha = sha256Hex(newer);
        assertTrue(store.write(CATEGORY, olderSha, QUALIFIER, older));
        assertTrue(store.write(CATEGORY, newerSha, QUALIFIER, newer));
        Files.setLastModifiedTime(entryPath(olderSha), FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(entryPath(newerSha), FileTime.fromMillis(2_000L));
        Files.setLastModifiedTime(pack, FileTime.fromMillis(500L));

        // cap 10 + newer only: the oldest body is evicted, the pack is kept
        assertEquals(older.length,
                store.pruneToLimit(10L + newer.length));

        assertNull(store.read(CATEGORY, olderSha, QUALIFIER));
        assertArrayEquals(newer, store.read(CATEGORY, newerSha, QUALIFIER));
        assertEquals(10L, Files.size(pack));
    }

    @Test
    void pruneLeavesTemporaryAndLockFilesUntouched() throws IOException {
        var store = new ContentAddressedFileStore(root);
        Path directory = root.resolve("manual");
        Files.createDirectories(directory);
        Path published = directory.resolve("published.bin");
        Path temporary = directory.resolve("writer.tmp");
        Path lockFile = root.resolve(ContentAddressedFileStore.PRUNE_LOCK_FILE);
        byte[] publishedBytes = {1, 2, 3, 4};
        byte[] temporaryBytes = {5, 6, 7, 8, 9};
        byte[] lockBytes = {10, 11, 12};
        Files.write(published, publishedBytes);
        Files.write(temporary, temporaryBytes);
        Files.write(lockFile, lockBytes);

        assertEquals(publishedBytes.length, store.pruneToLimit(1L));
        assertFalse(Files.exists(published));
        assertArrayEquals(temporaryBytes, Files.readAllBytes(temporary));
        assertArrayEquals(lockBytes, Files.readAllBytes(lockFile));
    }

    @Test
    void pruneRemovesOldestPublishedEntriesDeterministically() throws IOException {
        var store = new ContentAddressedFileStore(root);
        Path directory = root.resolve("manual");
        Files.createDirectories(directory);
        Path lexicallyLast = directory.resolve("z-last.bin");
        Path lexicallyFirst = directory.resolve("a-first.bin");
        byte[] entryBytes = {1, 2, 3, 4};
        Files.write(lexicallyLast, entryBytes);
        Files.write(lexicallyFirst, entryBytes);
        FileTime sameModificationTime = FileTime.fromMillis(1_000L);
        Files.setLastModifiedTime(lexicallyLast, sameModificationTime);
        Files.setLastModifiedTime(lexicallyFirst, sameModificationTime);

        assertEquals(entryBytes.length, store.pruneToLimit(entryBytes.length));
        assertFalse(Files.exists(lexicallyFirst));
        assertArrayEquals(entryBytes, Files.readAllBytes(lexicallyLast));
    }

    @Test
    void pruneSkipsImmediatelyWhenAnotherOwnerHoldsTheFileLock() throws IOException {
        Path directory = root.resolve("manual");
        Files.createDirectories(directory);
        Path first = directory.resolve("first.bin");
        Path second = directory.resolve("second.bin");
        byte[] firstBytes = {1, 2, 3, 4};
        byte[] secondBytes = {5, 6, 7, 8};
        Files.write(first, firstBytes);
        Files.write(second, secondBytes);

        Path lockFile = root.resolve(ContentAddressedFileStore.PRUNE_LOCK_FILE);
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            var otherStore = new ContentAddressedFileStore(root);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                    assertEquals(0L, otherStore.pruneToLimit(1L)));
        }

        assertArrayEquals(firstBytes, Files.readAllBytes(first));
        assertArrayEquals(secondBytes, Files.readAllBytes(second));
    }

    /**
     * Cached routine sources describe one exact database, so neither the
     * category directories the store creates nor its prune lock may be
     * readable by other users of the machine.
     */
    @Test
    void createdDirectoriesAndLockStayOwnerOnly() throws IOException {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        Path cacheRoot = root.resolve("target-v2-fixture");
        var store = new ContentAddressedFileStore(cacheRoot);
        byte[] content = {1, 2, 3, 4};
        String address = sha256Hex(content);

        assertTrue(store.write("bodies", address, "q", content));
        assertEquals(0L, store.pruneToLimit(1L << 30));

        Set<PosixFilePermission> ownerOnlyDirectory =
                PosixFilePermissions.fromString("rwx------");
        List<Path> shared = new ArrayList<>();
        try (var tree = Files.walk(cacheRoot)) {
            for (Path path : tree.filter(Files::isDirectory).toList()) {
                if (!ownerOnlyDirectory.equals(
                        Files.getPosixFilePermissions(path))) {
                    shared.add(path);
                }
            }
        }

        assertEquals(List.of(), shared,
                "every store directory must be private to its owner");
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(cacheRoot.resolve(
                        ContentAddressedFileStore.PRUNE_LOCK_FILE)));
    }

    @Test
    void pruneReturnsZeroWhenLazyTraversalFails() throws IOException {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        Path published = root.resolve("published.bin");
        byte[] publishedBytes = {1, 2, 3, 4};
        Files.write(published, publishedBytes);
        Path unreadable = Files.createDirectory(root.resolve("unreadable"));
        Set<PosixFilePermission> originalPermissions =
                Files.getPosixFilePermissions(unreadable);
        Files.setPosixFilePermissions(unreadable, Set.of());

        try {
            assumeTrue(directoryTraversalFails(unreadable),
                    "The current process can still traverse a permissionless directory");
            var store = new ContentAddressedFileStore(root);
            assertEquals(0L, store.pruneToLimit(1L));
            assertArrayEquals(publishedBytes, Files.readAllBytes(published));
        } finally {
            Files.setPosixFilePermissions(unreadable, originalPermissions);
        }
    }

    @Test
    void pruneResolvesSymbolicRootBeforeWalking() throws IOException {
        Path actualRoot = Files.createDirectory(root.resolve("actual"));
        Path published = actualRoot.resolve("published.bin");
        byte[] publishedBytes = {1, 2, 3, 4};
        Files.write(published, publishedBytes);
        Path symbolicRoot = root.resolve("alias");
        createSymbolicLinkOrSkip(symbolicRoot, actualRoot);

        var store = new ContentAddressedFileStore(symbolicRoot);
        assertEquals(publishedBytes.length, store.pruneToLimit(1L));
        assertFalse(Files.exists(published));
        assertTrue(Files.isRegularFile(
                actualRoot.resolve(ContentAddressedFileStore.PRUNE_LOCK_FILE)));
    }

    @Test
    void pruneDoesNotCountOrDeleteLeafSymbolicBinLinks() throws IOException {
        Path storeRoot = Files.createDirectory(root.resolve("store"));
        Path directory = Files.createDirectory(storeRoot.resolve("manual"));
        Path linkedTarget = root.resolve("outside.payload");
        byte[] linkedBytes = {1, 2, 3, 4, 5};
        Files.write(linkedTarget, linkedBytes);
        Files.setLastModifiedTime(linkedTarget, FileTime.fromMillis(1_000L));
        Path symbolicBin = directory.resolve("linked.bin");
        createSymbolicLinkOrSkip(symbolicBin, linkedTarget);
        Path published = directory.resolve("published.bin");
        byte[] publishedBytes = {6, 7, 8, 9};
        Files.write(published, publishedBytes);
        Files.setLastModifiedTime(published, FileTime.fromMillis(2_000L));

        var store = new ContentAddressedFileStore(storeRoot);
        assertEquals(publishedBytes.length, store.pruneToLimit(1L));
        assertFalse(Files.exists(published));
        assertTrue(Files.isSymbolicLink(symbolicBin));
        assertArrayEquals(linkedBytes, Files.readAllBytes(symbolicBin));
    }

    @Test
    void pruneToleratesMissingRootAndRejectsNonPositiveCap() {
        Path missingRoot = root.resolve("never-created");
        var store = new ContentAddressedFileStore(missingRoot);

        assertEquals(0L, store.pruneToLimit(1L));
        assertFalse(Files.exists(missingRoot));
        assertThrows(IllegalArgumentException.class, () -> store.pruneToLimit(0L));
        assertThrows(IllegalArgumentException.class, () -> store.pruneToLimit(-1L));
    }

    @Test
    void unsafeAddressComponentsAreRejected() {
        var store = new ContentAddressedFileStore(root);
        byte[] content = "SELECT 4".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(content);

        assertThrows(IllegalArgumentException.class,
                () -> store.read("../escape", sha, QUALIFIER));
        assertThrows(IllegalArgumentException.class,
                () -> store.read(CATEGORY, "not-a-hash", QUALIFIER));
        assertThrows(IllegalArgumentException.class,
                () -> store.read(CATEGORY, sha.toUpperCase(), QUALIFIER));
        assertThrows(IllegalArgumentException.class,
                () -> store.write(CATEGORY, sha, "path/escape", content));
        assertThrows(IllegalArgumentException.class,
                () -> store.write(CATEGORY, sha, "", content));
    }

    @Test
    void keyedEntriesRoundTripWithoutContentVerification() throws IOException {
        var store = new ContentAddressedFileStore(root);
        String md5Key = "ab".repeat(16);
        byte[] payload = "opaque row payload".getBytes(StandardCharsets.UTF_8);
        int payloadLimit = payload.length;

        assertNull(store.readKeyed("rows", md5Key, QUALIFIER, payloadLimit));
        assertFalse(store.containsKeyed("rows", md5Key, QUALIFIER));
        assertTrue(store.writeKeyed("rows", md5Key, QUALIFIER, payload, payloadLimit));
        assertTrue(store.containsKeyed("rows", md5Key, QUALIFIER));
        // the payload is intentionally not derivable from the key; the store
        // returns it verbatim and integrity stays with the caller
        assertArrayEquals(payload,
                store.readKeyed("rows", md5Key, QUALIFIER, payloadLimit));

        assertFalse(store.writeKeyed("rows", md5Key, QUALIFIER,
                "different".getBytes(StandardCharsets.UTF_8), payloadLimit));
        assertArrayEquals(payload,
                store.readKeyed("rows", md5Key, QUALIFIER, payloadLimit));

        store.deleteKeyed("rows", md5Key, QUALIFIER);
        assertNull(store.readKeyed("rows", md5Key, QUALIFIER, payloadLimit));
        assertFalse(store.containsKeyed("rows", md5Key, QUALIFIER));
    }

    @Test
    void keyedEntriesAcceptSha256LengthKeysToo() {
        var store = new ContentAddressedFileStore(root);
        String sha256Key = "cd".repeat(32);
        byte[] payload = {1, 2, 3};

        assertTrue(store.writeKeyed("row-manifests", sha256Key, QUALIFIER,
                payload, payload.length));
        assertArrayEquals(payload, store.readKeyed("row-manifests", sha256Key,
                QUALIFIER, payload.length));
    }

    @Test
    void keyedEntriesRejectMalformedKeys() {
        var store = new ContentAddressedFileStore(root);
        byte[] payload = {1};

        assertThrows(IllegalArgumentException.class,
                () -> store.writeKeyed("rows", "zz".repeat(16), QUALIFIER, payload,
                        payload.length));
        assertThrows(IllegalArgumentException.class,
                () -> store.writeKeyed("rows", "ab".repeat(8), QUALIFIER, payload,
                        payload.length));
        assertThrows(IllegalArgumentException.class,
                () -> store.readKeyed("rows", "AB".repeat(16), QUALIFIER,
                        payload.length));
        assertThrows(IllegalArgumentException.class,
                () -> store.containsKeyed("rows", "../../../etc", QUALIFIER));
    }

    @Test
    void readKeyedAcceptsPayloadExactlyAtLimit() throws IOException {
        var store = new ContentAddressedFileStore(root);
        String key = "ab".repeat(16);
        byte[] payload = new byte[KEYED_PAYLOAD_LIMIT];
        Path entry = keyedEntryPath("rows", key);
        Files.createDirectories(entry.getParent());
        Files.write(entry, payload);

        assertArrayEquals(payload,
                store.readKeyed("rows", key, QUALIFIER, KEYED_PAYLOAD_LIMIT));
        assertTrue(Files.isRegularFile(entry));
    }

    @Test
    void readKeyedRejectsAndDeletesPayloadAboveLimit() throws IOException {
        var store = new ContentAddressedFileStore(root);
        String key = "ab".repeat(16);
        Path entry = keyedEntryPath("rows", key);
        Files.createDirectories(entry.getParent());
        Files.write(entry, new byte[KEYED_PAYLOAD_LIMIT + 1]);

        assertNull(store.readKeyed("rows", key, QUALIFIER, KEYED_PAYLOAD_LIMIT));
        assertFalse(Files.exists(entry));
    }

    @Test
    void writeKeyedRejectsPayloadAboveLimitBeforeCreatingFiles() throws IOException {
        var store = new ContentAddressedFileStore(root);
        String key = "ab".repeat(16);
        List<Path> before = regularFiles();

        assertFalse(store.writeKeyed("rows", key, QUALIFIER,
                new byte[KEYED_PAYLOAD_LIMIT + 1], KEYED_PAYLOAD_LIMIT));
        assertEquals(before, regularFiles());
        assertFalse(Files.exists(keyedEntryPath("rows", key).getParent()));
    }

    @Test
    void keyedPayloadLimitMustBePositiveAndLeaveRoomForSentinelByte() {
        var store = new ContentAddressedFileStore(root);
        String key = "ab".repeat(16);
        byte[] payload = {1};

        for (int invalidLimit : new int[] {-1, 0, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.readKeyed("rows", key, QUALIFIER, invalidLimit));
            assertThrows(IllegalArgumentException.class,
                    () -> store.writeKeyed("rows", key, QUALIFIER, payload,
                            invalidLimit));
        }
        assertFalse(Files.exists(root.resolve("rows")));
    }

    private Path entryPath(String sha) {
        return root.resolve(CATEGORY).resolve(sha.substring(0, 2))
                .resolve(sha + '-' + QUALIFIER + ".bin");
    }

    private Path keyedEntryPath(String category, String key) {
        return root.resolve(category).resolve(key.substring(0, 2))
                .resolve(key + '-' + QUALIFIER + ".bin");
    }

    private List<Path> regularFiles() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private static boolean directoryTraversalFails(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            entries.toList();
            return false;
        } catch (IOException | UncheckedIOException ex) {
            return true;
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target)
            throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException ex) {
            assumeTrue(false, "Symbolic links are unavailable: " + ex.getMessage());
        } catch (FileSystemException ex) {
            assumeTrue(false, "Symbolic links are unavailable: " + ex.getMessage());
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
