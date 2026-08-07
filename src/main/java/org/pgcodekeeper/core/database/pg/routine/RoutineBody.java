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
 * One immutable raw/canonical routine-body payload shared by independent
 * project and JDBC leases.
 */
public final class RoutineBody {

    private final String raw;
    private final String canonical;
    private final RoutineBodyMeasure measure;

    private RoutineBody(String raw, String canonical, RoutineBodyMeasure measure) {
        this.raw = raw;
        this.canonical = canonical;
        this.measure = measure;
    }

    public static RoutineBody create(String raw, String canonical) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(canonical, "canonical");
        return new RoutineBody(raw, canonical, RoutineBodyFingerprinter.measure(raw));
    }

    /**
     * Creates a payload whose measure follows the profile normalization used
     * by the cross-loader exchange: with {@code keepNewLines} disabled the
     * fingerprint ignores carriage returns exactly like canonicalization
     * does, so fingerprint equality tracks canonical equality.
     */
    public static RoutineBody create(String raw, String canonical, boolean keepNewLines) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(canonical, "canonical");
        return new RoutineBody(raw, canonical,
                RoutineBodyFingerprinter.measure(raw, keepNewLines));
    }

    public String raw() {
        return raw;
    }

    public String canonical() {
        return canonical;
    }

    public RoutineBodyMeasure measure() {
        return measure;
    }
}
