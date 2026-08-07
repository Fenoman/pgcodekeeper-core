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
package org.pgcodekeeper.core.ignorelist;

import java.io.IOException;
import java.io.Serial;

/**
 * Signals that an ignore list file ({@code .pgcodekeeperignore},
 * {@code .pgcodekeeperignoreschema} or any file passed to
 * {@link IgnoreParser}) is syntactically invalid or could not be analyzed.
 * <p>
 * Extends {@link IOException} so every existing loader of ignore lists keeps
 * its signature and treats a broken file as a hard error instead of silently
 * proceeding without the rules it defines.
 */
public class IgnoreListParseException extends IOException {

    @Serial
    private static final long serialVersionUID = 5240565854492395517L;

    private final String listPath;

    /**
     * Creates an exception for a broken ignore list file.
     *
     * @param message  localized description including file location info
     * @param listPath path of the ignore list file that failed to parse
     */
    public IgnoreListParseException(String message, String listPath) {
        super(message);
        this.listPath = listPath;
    }

    /**
     * Creates an exception for a broken ignore list file with a cause.
     *
     * @param message  localized description including file location info
     * @param listPath path of the ignore list file that failed to parse
     * @param cause    underlying parse or analysis failure, may be null
     */
    public IgnoreListParseException(String message, String listPath, Throwable cause) {
        super(message, cause);
        this.listPath = listPath;
    }

    /**
     * @return path of the ignore list file that failed to parse
     */
    public String getListPath() {
        return listPath;
    }
}
