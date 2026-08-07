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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Child-JVM fixture for {@link ContentAddressedFileStoreMultiprocessTest}.
 * The marker protocol keeps process ordering explicit and gives every wait a
 * deadline so a failed child cannot hang the Maven test process.
 */
public final class ContentAddressedFileStoreProcessMain {

    private static final String CONTENT_SHARED_CATEGORY = "mp-content-shared";
    private static final String CONTENT_WORKER_CATEGORY = "mp-content-workers";
    private static final String KEYED_SHARED_CATEGORY = "mp-keyed-shared";
    private static final String KEYED_WORKER_CATEGORY = "mp-keyed-workers";
    private static final String SHARED_QUALIFIER = "shared";
    private static final int STRESS_ITERATIONS = 48;
    private static final int ROUNDTRIP_ATTEMPTS = 64;
    private static final int KEYED_PAYLOAD_LIMIT = 4 * 1024;
    private static final Duration MARKER_TIMEOUT = Duration.ofSeconds(30);
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);

    private ContentAddressedFileStoreProcessMain() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable ex) {
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Missing child-process mode");
        }
        switch (args[0]) {
            case "hold-prune-lock" -> holdPruneLock(args);
            case "attempt-prune" -> attemptPrune(args);
            case "write-read-prune" -> writeReadPrune(args);
            default -> throw new IllegalArgumentException(
                    "Unknown child-process mode: " + args[0]);
        }
    }

    private static void holdPruneLock(String[] args) throws Exception {
        requireArity(args, 4);
        Path root = Path.of(args[1]);
        Path readyMarker = Path.of(args[2]);
        Path releaseMarker = Path.of(args[3]);
        Files.createDirectories(root);
        Path lockPath = root.toRealPath()
                .resolve(ContentAddressedFileStore.PRUNE_LOCK_FILE);

        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = requireImmediateLock(channel, lockPath)) {
            writeNewMarker(readyMarker, "ready");
            awaitMarker(releaseMarker, MARKER_TIMEOUT);
        }
    }

    private static void attemptPrune(String[] args) throws IOException {
        requireArity(args, 4);
        Path root = Path.of(args[1]);
        Path doneMarker = Path.of(args[2]);
        long capBytes = parsePositiveCap(args[3]);

        long removed = new ContentAddressedFileStore(root).pruneToLimit(capBytes);
        writeNewMarker(doneMarker, Long.toString(removed));
        System.out.println("removed=" + removed);
    }

    private static void writeReadPrune(String[] args) throws IOException {
        requireArity(args, 6);
        Path root = Path.of(args[1]);
        String workerId = args[2];
        Path readyDir = Path.of(args[3]);
        Path goMarker = Path.of(args[4]);
        long capBytes = parsePositiveCap(args[5]);
        Files.createDirectories(root);
        Files.createDirectories(readyDir);

        var store = new ContentAddressedFileStore(root);
        writeNewMarker(readyDir.resolve(workerId + ".ready"), "ready");
        awaitMarker(goMarker, MARKER_TIMEOUT);

        String workerQualifier = "worker-" + workerId;
        for (int iteration = 0; iteration < STRESS_ITERATIONS; iteration++) {
            roundtripContentAddressed(store, CONTENT_SHARED_CATEGORY,
                    SHARED_QUALIFIER, payload("content-shared", "all", iteration));
            roundtripContentAddressed(store, CONTENT_WORKER_CATEGORY,
                    workerQualifier,
                    payload("content-worker", workerId, iteration));
            roundtripKeyed(store, KEYED_SHARED_CATEGORY, SHARED_QUALIFIER,
                    payload("keyed-shared", "all", iteration));
            roundtripKeyed(store, KEYED_WORKER_CATEGORY, workerQualifier,
                    payload("keyed-worker", workerId, iteration));

            if ((iteration & 3) == 3) {
                store.pruneToLimit(capBytes);
            }
        }
        store.pruneToLimit(capBytes);
        System.out.println("worker=" + workerId + " ok");
    }

    private static void roundtripContentAddressed(ContentAddressedFileStore store,
            String category, String qualifier, byte[] payload) {
        String sha = sha256Hex(payload);
        for (int attempt = 0; attempt < ROUNDTRIP_ATTEMPTS; attempt++) {
            store.write(category, sha, qualifier, payload);
            byte[] actual = store.read(category, sha, qualifier);
            if (actual == null) {
                continue;
            }
            if (!Arrays.equals(payload, actual)) {
                throw new AssertionError("Content-addressed read returned wrong bytes for "
                        + sha);
            }
            return;
        }
        throw new AssertionError("Content-addressed entry was continually pruned for "
                + sha);
    }

    private static void roundtripKeyed(ContentAddressedFileStore store,
            String category, String qualifier, byte[] payload) {
        String key = sha256Hex(payload);
        for (int attempt = 0; attempt < ROUNDTRIP_ATTEMPTS; attempt++) {
            store.writeKeyed(category, key, qualifier, payload, KEYED_PAYLOAD_LIMIT);
            byte[] actual = store.readKeyed(category, key, qualifier,
                    KEYED_PAYLOAD_LIMIT);
            if (actual == null) {
                continue;
            }
            if (!Arrays.equals(payload, actual)) {
                throw new AssertionError("Keyed read returned wrong bytes for " + key);
            }
            return;
        }
        throw new AssertionError("Keyed entry was continually pruned for " + key);
    }

    private static byte[] payload(String kind, String owner, int iteration) {
        String value = kind + '|' + owner + '|' + iteration + '|'
                + Character.toString((char) ('a' + iteration % 26))
                        .repeat(256 + iteration % 37);
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static FileLock requireImmediateLock(FileChannel channel, Path lockPath)
            throws IOException {
        FileLock lock = channel.tryLock();
        if (lock == null) {
            throw new IOException("Unable to acquire prune lock: " + lockPath);
        }
        return lock;
    }

    private static void awaitMarker(Path marker, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.isRegularFile(marker)) {
            if (System.nanoTime() >= deadline) {
                throw new IOException("Timed out waiting for marker: " + marker);
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
    }

    private static void writeNewMarker(Path marker, String value) throws IOException {
        Path parent = marker.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(marker, value, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static long parsePositiveCap(String value) {
        long cap = Long.parseLong(value);
        if (cap <= 0L) {
            throw new IllegalArgumentException("Cache cap must be positive: " + value);
        }
        return cap;
    }

    private static void requireArity(String[] args, int expected) {
        if (args.length != expected) {
            throw new IllegalArgumentException("Mode " + args[0] + " expects "
                    + (expected - 1) + " arguments, got " + (args.length - 1));
        }
    }

    private static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
