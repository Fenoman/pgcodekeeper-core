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
package org.pgcodekeeper.core.database.pg.routine;

import java.util.Objects;

/**
 * Versioned compatibility profile for exact routine-body exchange.
 */
public record RoutineBodyProfile(int protocolVersion, boolean keepNewLines,
                                 int parserVersion, HashAlgorithm hashAlgorithm,
                                 int canonicalizerVersion) {

    // Version 2: the fingerprint measures the profile-normalized body text
    // (carriage returns stripped when keepNewLines is disabled), so
    // fingerprint equality tracks canonical equality. The bump cleanly
    // invalidates persistent cache entries addressed by version-1 hashes.
    private static final int CURRENT_PROTOCOL_VERSION = 2;
    private static final int CURRENT_PARSER_VERSION = 1;
    private static final int CURRENT_CANONICALIZER_VERSION = 1;
    private static final RoutineBodyProfile CURRENT_NORMALIZED = createCurrent(false);
    private static final RoutineBodyProfile CURRENT_KEEP_NEW_LINES = createCurrent(true);

    public enum HashAlgorithm {
        SHA_256
    }

    public RoutineBodyProfile {
        if (protocolVersion <= 0) {
            throw new IllegalArgumentException("Protocol version must be positive");
        }
        if (parserVersion <= 0) {
            throw new IllegalArgumentException("Parser version must be positive");
        }
        if (canonicalizerVersion <= 0) {
            throw new IllegalArgumentException("Canonicalizer version must be positive");
        }
        Objects.requireNonNull(hashAlgorithm, "hashAlgorithm");
    }

    public static RoutineBodyProfile current(boolean keepNewLines) {
        return keepNewLines ? CURRENT_KEEP_NEW_LINES : CURRENT_NORMALIZED;
    }

    private static RoutineBodyProfile createCurrent(boolean keepNewLines) {
        return new RoutineBodyProfile(CURRENT_PROTOCOL_VERSION, keepNewLines,
                CURRENT_PARSER_VERSION, HashAlgorithm.SHA_256,
                CURRENT_CANONICALIZER_VERSION);
    }
}
