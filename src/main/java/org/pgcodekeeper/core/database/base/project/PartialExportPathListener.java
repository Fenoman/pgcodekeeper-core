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
import java.nio.file.Path;

/**
 * Notified by {@link AbstractModelExporter#exportPartial()} the moment it is
 * about to delete, overwrite, or freshly create the file at a given
 * project-relative path - once per distinct path, per export call, and always
 * before anything happens to that path.
 * <p>
 * WHY this exists instead of a precomputed list of affected paths: a caller
 * that wants to snapshot "before" state for an all-or-nothing rollback of a
 * partial export needs exactly the same set of paths {@code exportPartial()}
 * is about to touch. Computing that set a second time, independently of the
 * export itself, is exactly how the two would drift apart as the exporter's
 * logic evolves - a changed edge case updated on one side and forgotten on
 * the other. There are two places {@code exportPartial()} touches a path -
 * {@link AbstractModelExporter#deleteStatementIfExists} for everything driven
 * by {@code changeList}, and {@code writeDumps} for the one path written
 * unconditionally, the project version marker - and both notify from the
 * exact statement that performs the touch, not from a summary computed
 * elsewhere. Nothing here decides on its own which paths qualify; there is
 * nothing left to drift, because there is nowhere a path is touched without
 * this firing.
 *
 * @see PartialExportBackup
 */
@FunctionalInterface
public interface PartialExportPathListener {

    /**
     * Called once per distinct relative path, before the exporter deletes,
     * overwrites, or creates the file at that path for the first time in the
     * current {@code exportPartial()} call.
     *
     * @param relativePath the affected path, relative to the exporter's output directory
     * @throws IOException if the listener fails to record the path's current state
     */
    void beforeTouch(Path relativePath) throws IOException;
}
