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
package org.pgcodekeeper.core.database.api.launcher;

import java.util.List;
import java.util.Objects;

/**
 * Per-thread redirection of {@code addAnalysisLauncher} publications into a
 * caller-owned buffer. The lane-parallel catalog readers buffer each reader's
 * launchers on its worker thread and publish the buffers on the coordinator in
 * the canonical serial reader order, which keeps the database launcher list
 * byte-identical to a sequential load.
 * <p>
 * Threads that never install a redirect pay only one {@link ThreadLocal} read
 * per publication. This type is an internal bridge between loader packages and
 * is not a supported extension API.
 */
public final class AnalysisLauncherRedirect {

    private static final ThreadLocal<List<IAnalysisLauncher>> ACTIVE = new ThreadLocal<>();

    /**
     * Returns the buffer installed on the current thread, or {@code null} when
     * publications must go directly to the database.
     *
     * @return active redirect buffer, or {@code null}
     */
    public static List<IAnalysisLauncher> active() {
        return ACTIVE.get();
    }

    /**
     * Runs an action with the given buffer installed as the current thread's
     * redirect target, restoring the previous target afterwards. A
     * {@code null} sink runs the action without touching the thread state.
     *
     * @param sink   buffer receiving launcher publications, or {@code null}
     * @param action action to run under the redirect
     */
    public static void run(List<IAnalysisLauncher> sink, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (sink == null) {
            action.run();
            return;
        }

        List<IAnalysisLauncher> previous = ACTIVE.get();
        ACTIVE.set(sink);
        try {
            action.run();
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    /**
     * Installs a buffer as the current thread's redirect target for a code
     * region delimited by a caller-owned try/finally.
     *
     * @param sink buffer receiving launcher publications, or {@code null}
     * @return previous target to pass to {@link #restore(List)}
     */
    public static List<IAnalysisLauncher> install(List<IAnalysisLauncher> sink) {
        List<IAnalysisLauncher> previous = ACTIVE.get();
        if (sink == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(sink);
        }
        return previous;
    }

    /**
     * Restores a previously installed redirect target.
     *
     * @param previous value returned by the paired {@link #install(List)}
     */
    public static void restore(List<IAnalysisLauncher> previous) {
        if (previous == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(previous);
        }
    }

    private AnalysisLauncherRedirect() {
        // only statics
    }
}
