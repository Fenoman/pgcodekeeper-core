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
package org.pgcodekeeper.core.dependencieslist;

import java.io.Serial;

/**
 * Signals that a {@code .pgcodekeeperdependencies} file - the one named by
 * {@code --additional-dependencies} or picked up from a project root - is
 * syntactically invalid or could not be analyzed.
 * <p>
 * The counterpart for ignore lists, {@code IgnoreListParseException}, extends
 * {@link java.io.IOException} because every one of its call sites already
 * declared one. This one cannot: {@code DependenciesReader.getDependencies} is
 * called from a property page of the Eclipse plugin, from inside an override
 * that may not declare a checked exception, so widening the signature would
 * make a broken file a compile error somewhere instead of a diagnostic here.
 * Unchecked keeps every existing signature and still stops the run: the CLI
 * reports it through {@code Application.process}, the same generic handler that
 * already prints a broken ignore list.
 * <p>
 * There is deliberately no lenient second entry point. A reader that answers a
 * broken file with an empty list is the defect this type exists to close, and a
 * second method that still does it would only move the defect behind a name.
 */
public class DependenciesListParseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 8455318079857135911L;

    private final String listPath;

    /**
     * Creates an exception for a broken dependencies file.
     *
     * @param message  localized description including file location info
     * @param listPath path of the dependencies file that failed to parse
     * @param cause    underlying parse or analysis failure, may be null
     */
    public DependenciesListParseException(String message, String listPath, Throwable cause) {
        super(message, cause);
        this.listPath = listPath;
    }

    /**
     * @return path of the dependencies file that failed to parse
     */
    public String getListPath() {
        return listPath;
    }
}
