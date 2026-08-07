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
import java.nio.file.Path;

/**
 * Wrapper for creation and automatic recursive deletion of a temporary directory.
 * Intended for try-with-resources usage to ensure proper cleanup.
 * Automatically deletes the directory and all its contents when closed.
 *
 * @author Alexander Levsha
 */
public final class TempDir implements AutoCloseable {

    private final Path dir;

    /**
     * Set by {@link #keep()} when the contents turn out to be worth more than
     * the tidiness of removing them.
     */
    private boolean kept;

    /**
     * Creates a temporary directory with specified prefix in the given parent directory.
     *
     * @param dir    the parent directory
     * @param prefix the directory name prefix
     * @throws IOException if directory creation fails
     */
    public TempDir(Path dir, String prefix) throws IOException {
        this.dir = FileUtils.createTempDirectory(dir, prefix);
    }

    /**
     * Returns the path to the temporary directory.
     *
     * @return path to the temporary directory
     */
    public Path get() {
        return dir;
    }

    /**
     * Hands the directory over to the caller for good: {@link #close()} stops
     * deleting it, and whoever is told about the path owns it from then on.
     * <p>
     * WHY a temporary directory would ever be made permanent: a rollback that
     * fails leaves its backup holding the only remaining copy of bytes the
     * user cannot reproduce - the pre-update state of files an aborted update
     * has already overwritten. Deleting that on the way out, which is exactly
     * what an unconditional cleanup does, turns a recoverable failure into
     * data loss. The caller is then responsible for naming the path in
     * whatever it reports, so someone can actually go and get those bytes.
     */
    public void keep() {
        kept = true;
    }

    @Override
    public void close() throws IOException {
        if (kept) {
            return;
        }
        FileUtils.deleteRecursive(dir);
    }
}
