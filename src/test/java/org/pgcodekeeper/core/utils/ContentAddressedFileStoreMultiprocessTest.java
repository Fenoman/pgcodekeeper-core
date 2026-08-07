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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentAddressedFileStoreMultiprocessTest {

    private static final String CATEGORY = "multiprocess";
    private static final String QUALIFIER = "test";
    private static final int WORKER_COUNT = 4;
    private static final long STRESS_CAP_BYTES = 16L * 1024L;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PRUNER_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STRESS_TIMEOUT = Duration.ofSeconds(30);
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);

    @TempDir
    private Path tempDir;

    @Test
    void otherProcessSkipsPruneWhileLockIsHeld() throws Exception {
        Path storeRoot = tempDir.resolve("lock-store");
        var store = new ContentAddressedFileStore(storeRoot);
        byte[] payload = "entry protected by another JVM's prune lock"
                .getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);
        assertTrue(store.write(CATEGORY, sha, QUALIFIER, payload));

        Path markers = Files.createDirectories(tempDir.resolve("lock-markers"));
        Path readyMarker = markers.resolve("holder.ready");
        Path releaseMarker = markers.resolve("holder.release");
        Path doneMarker = markers.resolve("pruner.done");
        ChildProcess holder = null;
        ChildProcess pruner = null;
        try {
            holder = startChild("lock-holder", "hold-prune-lock",
                    storeRoot.toString(), readyMarker.toString(),
                    releaseMarker.toString());
            awaitMarkers(List.of(readyMarker), List.of(holder), READY_TIMEOUT);
            assertTrue(holder.process().isAlive(),
                    "Lock holder exited before the prune attempt:\n"
                            + childOutput(holder));

            pruner = startChild("lock-pruner", "attempt-prune",
                    storeRoot.toString(), doneMarker.toString(), "1");
            awaitSuccessfulExit(pruner, PRUNER_TIMEOUT);

            assertTrue(holder.process().isAlive(),
                    "Lock holder exited before the pruner reported its result:\n"
                            + childOutput(holder));
            assertTrue(Files.isRegularFile(doneMarker),
                    "Pruner did not publish its result:\n" + childOutput(pruner));
            assertEquals("0", Files.readString(doneMarker, StandardCharsets.UTF_8).trim());
            assertArrayEquals(payload, store.read(CATEGORY, sha, QUALIFIER));

            writeMarker(releaseMarker, "release");
            awaitSuccessfulExit(holder, PRUNER_TIMEOUT);
        } finally {
            try {
                writeMarker(releaseMarker, "release");
            } finally {
                try {
                    stopChild(pruner);
                } finally {
                    stopChild(holder);
                }
            }
        }
    }

    @Test
    void concurrentProcessesPublishReadAndPruneWithoutCorruption() throws Exception {
        Path storeRoot = Files.createDirectories(tempDir.resolve("stress-store"));
        Path readyDir = Files.createDirectories(tempDir.resolve("stress-ready"));
        Path goMarker = tempDir.resolve("stress.go");
        List<Path> readyMarkers = new ArrayList<>();
        List<ChildProcess> workers = new ArrayList<>();

        try {
            for (int worker = 0; worker < WORKER_COUNT; worker++) {
                String workerId = Integer.toString(worker);
                readyMarkers.add(readyDir.resolve(workerId + ".ready"));
                workers.add(startChild("stress-worker-" + workerId,
                        "write-read-prune", storeRoot.toString(), workerId,
                        readyDir.toString(), goMarker.toString(),
                        Long.toString(STRESS_CAP_BYTES)));
            }

            awaitMarkers(readyMarkers, workers, READY_TIMEOUT);
            writeMarker(goMarker, "go");
            long deadline = System.nanoTime() + STRESS_TIMEOUT.toNanos();
            for (ChildProcess worker : workers) {
                awaitSuccessfulExitBy(worker, deadline);
            }

            var store = new ContentAddressedFileStore(storeRoot);
            store.pruneToLimit(STRESS_CAP_BYTES);

            List<Path> published = findRegularFiles(storeRoot, ".bin");
            assertFalse(published.isEmpty(), "Stress pruning removed every published entry");
            long totalBytes = 0L;
            for (Path entry : published) {
                byte[] payload = Files.readAllBytes(entry);
                String fileName = entry.getFileName().toString();
                assertTrue(fileName.length() > 64 && fileName.charAt(64) == '-',
                        () -> "Unexpected published entry name: " + entry);
                assertEquals(fileName.substring(0, 64), sha256Hex(payload),
                        () -> "Corrupt or torn published entry: " + entry);
                totalBytes += payload.length;
            }
            assertTrue(totalBytes <= STRESS_CAP_BYTES,
                    "Published entries exceed cap: " + totalBytes);
            assertEquals(List.of(), findRegularFiles(storeRoot, ".tmp"),
                    "Writer temporary files remain after all processes exited");

            Path lockPath = storeRoot.resolve(ContentAddressedFileStore.PRUNE_LOCK_FILE);
            assertTrue(Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS),
                    "Prune lock file was removed or replaced");
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.WRITE);
                    FileLock lock = channel.tryLock()) {
                assertNotNull(lock, "Prune lock remained owned after workers exited");
            }
        } finally {
            for (ChildProcess worker : workers) {
                stopChild(worker);
            }
        }
    }

    private ChildProcess startChild(String label, String... arguments) throws IOException {
        String classPath = System.getProperty("surefire.test.class.path");
        if (classPath == null || classPath.isBlank()) {
            classPath = System.getProperty("java.class.path");
        }
        String executable = System.getProperty("os.name", "").startsWith("Windows")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path output = tempDir.resolve(label + ".out");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(classPath);
        command.add(ContentAddressedFileStoreProcessMain.class.getName());
        command.addAll(List.of(arguments));

        var builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        return new ChildProcess(label, builder.start(), output);
    }

    private static void awaitMarkers(List<Path> markers, List<ChildProcess> children,
            Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!markers.stream().allMatch(Files::isRegularFile)) {
            for (ChildProcess child : children) {
                if (!child.process().isAlive()) {
                    fail("Child exited before its ready marker: " + child.label()
                            + "\n" + childOutput(child));
                }
            }
            if (System.nanoTime() >= deadline) {
                fail("Timed out waiting for markers " + markers + "\n"
                        + childrenOutput(children));
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
    }

    private static void awaitSuccessfulExit(ChildProcess child, Duration timeout)
            throws Exception {
        boolean finished = child.process().waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
        assertTrue(finished, "Child timed out: " + child.label() + "\n"
                + childOutput(child));
        assertEquals(0, child.process().exitValue(),
                "Child failed: " + child.label() + "\n" + childOutput(child));
    }

    private static void awaitSuccessfulExitBy(ChildProcess child, long deadline)
            throws Exception {
        long remaining = deadline - System.nanoTime();
        assertTrue(remaining > 0L, "Stress deadline expired before waiting for "
                + child.label() + "\n" + childOutput(child));
        boolean finished = child.process().waitFor(remaining, TimeUnit.NANOSECONDS);
        assertTrue(finished, "Child exceeded the shared stress deadline: "
                + child.label() + "\n" + childOutput(child));
        assertEquals(0, child.process().exitValue(),
                "Child failed: " + child.label() + "\n" + childOutput(child));
    }

    private static void stopChild(ChildProcess child) throws InterruptedException {
        if (child == null || !child.process().isAlive()) {
            return;
        }
        child.process().destroy();
        if (!child.process().waitFor(1L, TimeUnit.SECONDS)) {
            child.process().destroyForcibly();
            child.process().waitFor(5L, TimeUnit.SECONDS);
        }
    }

    private static void writeMarker(Path marker, String value) throws IOException {
        if (marker == null) {
            return;
        }
        Path parent = marker.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(marker, value, StandardCharsets.UTF_8);
    }

    private static List<Path> findRegularFiles(Path root, String suffix)
            throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> Files.isRegularFile(path,
                            LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static String childrenOutput(List<ChildProcess> children)
            throws IOException {
        var output = new StringBuilder();
        for (ChildProcess child : children) {
            output.append(child.label()).append(':').append(System.lineSeparator())
                    .append(childOutput(child));
        }
        return output.toString();
    }

    private static String childOutput(ChildProcess child) throws IOException {
        if (child == null || !Files.isRegularFile(child.output())) {
            return "<no child output>";
        }
        return Files.readString(child.output(), StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private record ChildProcess(String label, Process process, Path output) {
    }
}
