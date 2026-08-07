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
package org.pgcodekeeper.core.database.base.schema;

import java.io.*;
import java.util.*;

import org.pgcodekeeper.core.hasher.*;

/**
 * Represents a simple column reference with ordering and operator class information.
 * Used primarily for index column definitions and similar contexts where column
 * attributes like sorting, collation, and operator classes are needed.
 */
public class SimpleColumn implements Serializable, IHashable {

    @Serial
    private static final long serialVersionUID = -4183664175731032909L;

    private final Map<String, String> opClassParams = new HashMap<>();
    private String collation;

    private final String name;

    /**
     * The column text as the comparison sees it, or null when nothing
     * normalized it. Only the PostgreSQL parse fills this: ClickHouse folds
     * identifier case differently and SQL Server has no normalizer at all, so
     * for those dialects the raw name stays the only thing there is.
     */
    private final String nameNormalized;

    private String operator;
    private String opClass;
    private String nullsOrdering;
    private boolean isDesc;

    /**
     * Creates a column reference.
     *
     * @param name           the column text as written
     * @param nameNormalized the column text as the comparison sees it, or null
     *                       when this dialect has no normalizer of its own and
     *                       the raw name is the only thing there is
     */
    public SimpleColumn(String name, String nameNormalized) {
        this.name = name;
        this.nameNormalized = nameNormalized;
    }

    /**
     * Adds an operator class parameter.
     *
     * @param key the parameter key
     * @param value the parameter value
     */
    public void addOpClassParam(String key, String value) {
        opClassParams.put(key, value);
    }

    public Map<String, String> getOpClassParams() {
        return Collections.unmodifiableMap(opClassParams);
    }

    public void setCollation(String collation) {
        this.collation = collation;
    }

    public String getCollation() {
        return collation;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getOperator() {
        return operator;
    }

    public void setOpClass(String opClass) {
        this.opClass = opClass;
    }

    public String getOpClass() {
        return opClass;
    }

    public void setNullsOrdering(String nullsOrdering) {
        this.nullsOrdering = nullsOrdering;
    }

    public String getNullsOrdering() {
        return nullsOrdering;
    }

    public boolean isDesc() {
        return isDesc;
    }

    public void setDesc(boolean isDesc) {
        this.isDesc = isDesc;
    }

    public String getName() {
        return name;
    }

    @Override
    public int hashCode() {
        JavaHasher hasher = new JavaHasher();
        computeHash(hasher);
        return hasher.getResult();
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.put(comparableName());
        hasher.put(opClassParams);
        hasher.put(collation);
        hasher.put(operator);
        hasher.put(opClass);
        hasher.put(nullsOrdering);
        hasher.put(isDesc);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SimpleColumn other)) {
            return false;
        }
        return Objects.equals(comparableName(), other.comparableName())
                && Objects.equals(opClassParams, other.opClassParams)
                && Objects.equals(collation, other.collation)
                && Objects.equals(operator, other.operator)
                && Objects.equals(opClass, other.opClass)
                && Objects.equals(nullsOrdering, other.nullsOrdering)
                && isDesc == other.isDesc;
    }

    private String comparableName() {
        return nameNormalized != null ? nameNormalized : name;
    }
}
