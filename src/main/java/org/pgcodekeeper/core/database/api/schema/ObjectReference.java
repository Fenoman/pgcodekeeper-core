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
package org.pgcodekeeper.core.database.api.schema;

import java.io.Serializable;

/**
 * Represents an object reference with schema, table, column, and type information.
 * Used for identifying and referencing database objects across different contexts.
 *
 * @param schema the schema name
 * @param table  the table name
 * @param column the column name
 * @param type   the database object type
 */
public record ObjectReference(String schema, String table, String column, DbObjType type) implements Serializable {

    /**
     * Creates an object reference for a database object within a schema.
     *
     * @param schema the schema name
     * @param object the object name (table, view, function, etc.)
     * @param type   the database object type
     */
    public ObjectReference(String schema, String object, DbObjType type) {
        this(schema, object, null, type);
    }

    /**
     * Creates an object reference for a schema-level object.
     *
     * @param schema the schema name
     * @param type   the database object type
     */
    public ObjectReference(String schema, DbObjType type) {
        this(schema, null, type);
    }

    /**
     * Gets the name of the most specific object component.
     *
     * @return the column name if present, otherwise table name, otherwise schema name
     */
    public String getName() {
        if (column != null) {
            return column;
        }
        if (table != null) {
            return table;
        }
        if (schema != null) {
            return schema;
        }

        return "";
    }

    public String getFullName() {
        StringBuilder sb = new StringBuilder();
        if (schema != null) {
            sb.append(schema);
            sb.append('.');
        }
        if (table != null) {
            sb.append(table);
            sb.append('.');
        }
        if (column != null) {
            sb.append(column);
            sb.append('.');
        }

        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /**
     * Replaces the record-generated hash so that it stays identical between JVM
     * runs. The generated hash mixes in {@code type.hashCode()}, and
     * {@link Enum#hashCode()} is final and returns the JVM identity hash, which
     * is drawn from a per-thread generator and therefore varies from run to run.
     * Any hash container keyed by this record - or by a key that folds it in,
     * such as {@link ObjectLocation} - would then iterate in a different order on
     * every run. Hashing the stable {@link DbObjType#ordinal()} keeps such
     * iterations reproducible. Stays consistent with the generated
     * {@code equals}: equal references have equal ordinals.
     *
     * @return run-stable hash code
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (schema == null ? 0 : schema.hashCode());
        result = prime * result + (table == null ? 0 : table.hashCode());
        result = prime * result + (column == null ? 0 : column.hashCode());
        result = prime * result + (type == null ? 0 : type.ordinal());
        return result;
    }

    @Override
    public String toString() {
        return getFullName() + " (" + type + ')';
    }
}