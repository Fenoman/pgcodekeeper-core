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
package org.pgcodekeeper.core.analysis;

import java.io.IOException;
import java.io.Serial;

/**
 * Signals that a persisted analysis result is damaged or was written by an
 * incompatible version.
 * <p>
 * This exception is the proof that the bytes themselves are wrong, as opposed
 * to a storage error that merely prevented reading them. Only this distinction
 * may lead a cache to delete its own state; an {@link IOException} of any other
 * kind must leave the state in place for the next attempt.
 */
public class AnalysisReplayFormatException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AnalysisReplayFormatException(String message) {
        super(message);
    }

    public AnalysisReplayFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
