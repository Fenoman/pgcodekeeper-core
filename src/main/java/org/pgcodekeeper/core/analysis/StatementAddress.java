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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;

/**
 * Load-stable identity of one statement inside a loaded model.
 * <p>
 * The address is the path from the database root to the statement, each step
 * being the child's {@link DbObjType} and its {@link IStatement#getName()}.
 * That pair is exactly what the model itself enforces to be unique: a
 * container rejects a second child of the same type and name while it is being
 * built, so no two statements of one valid model can share an address.
 * Overloaded routines and operators stay distinct because their name is their
 * signature.
 * <p>
 * The address deliberately does not use {@code toObjectReference()}: that form
 * is a tolerant <em>lookup</em> key which widens types and drops the table of
 * an index, so two different statements can answer to one reference. An
 * identity used to replay analysis results must never be tolerant.
 *
 * @param segments root-to-statement path, never empty
 */
public record StatementAddress(List<Segment> segments)
        implements Comparable<StatementAddress> {

    /**
     * One step of a statement path.
     *
     * @param type child object type
     * @param name child name, the signature for routines and operators
     */
    public record Segment(DbObjType type, String name) {

        public Segment {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(name, "name");
        }
    }

    public StatementAddress {
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Statement address must not be empty");
        }
    }

    /**
     * Builds the address of one statement.
     *
     * @param statement statement to address, must not be the database itself
     * @return root-to-statement address
     * @throws IllegalArgumentException if the statement is a database root or
     *                                  carries a null name on its path
     */
    public static StatementAddress of(IStatement statement) {
        Objects.requireNonNull(statement, "statement");
        var reversed = new ArrayList<Segment>();
        for (IStatement current = statement;
                current != null && current.getParent() != null;
                current = current.getParent()) {
            String name = current.getName();
            if (name == null) {
                throw new IllegalArgumentException(
                        "Statement without a name cannot be addressed: "
                                + current.getStatementType());
            }
            reversed.add(new Segment(current.getStatementType(), name));
        }
        if (reversed.isEmpty()) {
            throw new IllegalArgumentException(
                    "The database root has no statement address");
        }
        Collections.reverse(reversed);
        return new StatementAddress(reversed);
    }

    /**
     * Orders addresses by their path, so a payload can be stored in one
     * canonical order regardless of how the model happened to iterate.
     */
    @Override
    public int compareTo(StatementAddress other) {
        int common = Math.min(segments.size(), other.segments.size());
        for (int i = 0; i < common; i++) {
            Segment mine = segments.get(i);
            Segment theirs = other.segments.get(i);
            int byType = mine.type().compareTo(theirs.type());
            if (byType != 0) {
                return byType;
            }
            int byName = mine.name().compareTo(theirs.name());
            if (byName != 0) {
                return byName;
            }
        }
        return Integer.compare(segments.size(), other.segments.size());
    }

    /**
     * Indexes every statement of a model by its address.
     * <p>
     * The traversal is {@code getDescendants()} widened by
     * {@link ITable#columnAdder}, which is the same walk the dependency graph
     * itself uses. Table columns are not children of their table, they are
     * reachable only through the table, and they do carry dependency edges of
     * their own - a walk that omits them would lose every edge a column default
     * contributes and, worse, would agree with itself about the model size.
     * <p>
     * A duplicate address means the model does not satisfy the uniqueness this
     * identity relies on, so the whole index is rejected rather than silently
     * losing one of the colliding statements.
     *
     * @param database loaded model to index
     * @return address index, or {@code null} when two statements collide or a
     *         statement cannot be addressed
     */
    public static Map<StatementAddress, IStatement> index(IDatabase database) {
        Objects.requireNonNull(database, "database");
        var index = new HashMap<StatementAddress, IStatement>();
        var statements = statements(database).iterator();
        while (statements.hasNext()) {
            IStatement statement = statements.next();
            StatementAddress address;
            try {
                address = of(statement);
            } catch (IllegalArgumentException ex) {
                return null;
            }
            if (index.putIfAbsent(address, statement) != null) {
                return null;
            }
        }
        return index;
    }

    /**
     * Streams every addressable statement of a model, columns included.
     *
     * @param database loaded model to walk
     * @return every statement below the database root
     */
    public static Stream<? extends IStatement> statements(IDatabase database) {
        Objects.requireNonNull(database, "database");
        return database.getDescendants().flatMap(ITable::columnAdder);
    }
}
