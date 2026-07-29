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
package org.pgcodekeeper.core.database.api.loader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

import org.pgcodekeeper.core.database.api.schema.IDatabase;

/**
 * Interface for project loader
 */
public interface IProjectLoader extends ILoader {

    /**
     * Loads the given project files in isolation, without reading the whole
     * project.
     *
     * @param files project files to parse
     * @return database populated from the given files only
     */
    IDatabase loadFiles(Collection<Path> files) throws IOException, InterruptedException;
}
