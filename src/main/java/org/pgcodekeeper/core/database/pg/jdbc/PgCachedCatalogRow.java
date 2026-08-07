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
 * One fully materialized PostgreSQL catalog row of the row-level cache.
 * Labels are the lower-case JDBC column labels in server column order and
 * values hold the exact objects a pgJDBC {@code getObject} call returned at
 * capture time: {@code null}, {@link String}, {@link Long}, {@link Integer},
 * {@link Short}, {@link Boolean}, {@link Double}, {@link Float},
 * {@code byte[]} or a typed object array eagerly decoded from a
 * {@link java.sql.Array} ({@code String[]}, {@code Long[]}, ...). A column
 * captured from a live driver row keeps its array inside a
 * {@link PgCachedCatalogArray}, which carries the decoded elements and the
 * exact {@code getString} text of the same column side by side.
 *
 * @param labels column labels in server order, never null
 * @param values column values in the same order, never null
 */
public record PgCachedCatalogRow(String[] labels, Object[] values) {

    public PgCachedCatalogRow {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(values, "values");
        if (labels.length != values.length) {
            throw new IllegalArgumentException(
                    "Catalog row labels and values must have equal length");
        }
    }
}
