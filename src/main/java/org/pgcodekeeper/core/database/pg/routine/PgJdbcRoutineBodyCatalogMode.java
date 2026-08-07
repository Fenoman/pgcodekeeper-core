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

/**
 * Internal per-load PostgreSQL routine catalog projection. This bridge is not
 * a supported extension API; it keeps capability selection immutable while a
 * catalog reader is active.
 */
public enum PgJdbcRoutineBodyCatalogMode {
    FULL_BODY(false),
    FINGERPRINT_UTF8(true),
    FINGERPRINT_CONVERTED(false);

    private final boolean directUtf8Length;

    PgJdbcRoutineBodyCatalogMode(boolean directUtf8Length) {
        this.directUtf8Length = directUtf8Length;
    }

    public boolean isFingerprint() {
        return this != FULL_BODY;
    }

    public boolean usesDirectUtf8Length() {
        return directUtf8Length;
    }
}
