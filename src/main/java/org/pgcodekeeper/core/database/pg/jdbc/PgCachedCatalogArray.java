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
package org.pgcodekeeper.core.database.pg.jdbc;

import java.util.Objects;

/**
 * One captured PostgreSQL array column in both shapes a catalog reader may
 * ask for.
 * <p>
 * pgJDBC serves an array column either as a {@link java.sql.Array} through
 * {@code getObject}/{@code getArray} or as its PostgreSQL text form through
 * {@code getString}, and catalog readers use both: privilege columns
 * ({@code nspacl}, {@code relacl}, {@code proacl}, ...) are read as text,
 * while option and column-detail arrays are read as decoded elements. A
 * {@link java.sql.Array} loses its text form once it is freed, so the cache
 * captures both while the driver row is still live and stores them together.
 * <p>
 * The text is the exact string {@code getString} returned, never a
 * re-rendered one, so the PostgreSQL array literal - including its quoting,
 * escaping and {@code NULL} elements - survives a cache round trip
 * byte for byte.
 *
 * @param elements exactly what {@link java.sql.Array#getArray()} returned at
 *                 capture time, with driver-specific element objects reduced
 *                 to their text, never null
 * @param text     the exact {@code getString} text of the same column, never
 *                 null
 */
public record PgCachedCatalogArray(Object elements, String text) {

    public PgCachedCatalogArray {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(text, "text");
    }
}
