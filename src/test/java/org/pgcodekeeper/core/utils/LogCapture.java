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

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.event.Level;

/**
 * Test-only capture of formatted log messages produced through the
 * {@link CapturingLogProvider} test binding. While no capture is active every
 * logger stays disabled at all levels. Messages are recorded from any thread.
 *
 * <p>Test classes run concurrently, so assertions must match unique message
 * content (for example a probe-specific {@code detail=} value) instead of
 * expecting an exclusive capture.
 */
public final class LogCapture implements AutoCloseable {

    private static final List<LogCapture> ACTIVE = new CopyOnWriteArrayList<>();

    private final Queue<Entry> entries = new ConcurrentLinkedQueue<>();

    private LogCapture() {
    }

    /**
     * One recorded call, kept with the level it was made at so that a test can
     * assert not only that something was said but how loudly. A message that
     * has to reach an operator and one that merely explains a decision are
     * different promises, and only the level tells them apart.
     *
     * @param level   the level the call was made at
     * @param message the formatted message
     */
    public record Entry(Level level, String message) {
    }

    /**
     * Activates a new capture. Close it (try-with-resources) to deactivate.
     */
    public static LogCapture start() {
        LogCapture capture = new LogCapture();
        ACTIVE.add(capture);
        return capture;
    }

    static boolean isAnyActive() {
        return !ACTIVE.isEmpty();
    }

    static void record(Level level, String message) {
        for (LogCapture capture : ACTIVE) {
            capture.entries.add(new Entry(level, message));
        }
    }

    /**
     * @return snapshot of all messages recorded so far, in arrival order
     */
    public List<String> messages() {
        return entries().stream().map(Entry::message).toList();
    }

    /**
     * @param fragment substring to search for
     * @return recorded messages containing the given fragment
     */
    public List<String> messagesContaining(String fragment) {
        return messages().stream().filter(message -> message.contains(fragment)).toList();
    }

    /**
     * @return snapshot of all calls recorded so far, level and all, in arrival
     * order
     */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * @param fragment substring to search for
     * @return the levels of the recorded calls whose message contains the given
     * fragment, in arrival order
     */
    public List<Level> levelsOf(String fragment) {
        return entries().stream()
                .filter(entry -> entry.message().contains(fragment))
                .map(Entry::level)
                .toList();
    }

    @Override
    public void close() {
        ACTIVE.remove(this);
    }
}
