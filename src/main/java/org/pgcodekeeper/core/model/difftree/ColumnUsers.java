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
package org.pgcodekeeper.core.model.difftree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;

/**
 * Which statement of a database names which column of which table, read once for
 * the whole database and answered from there.
 * <p>
 * A column a rule asks to hide stays under management while anything at all
 * still names it, and the objects that do are spread over the whole database -
 * a view selecting it, a function reading it, an extended statistics object
 * gathered on it, the {@code OWNED BY} of a sequence. Finding them means walking
 * every statement, see {@link ColumnVisibility}. The walk answers for one table
 * or for every table of the database at very nearly the same cost, and an export
 * asks about one table at a time, so where the answer is kept decides
 * everything. Measured on a project of 13 323 tables and 48 353 statements,
 * exporting all of it into 22 302 files:
 * <ul>
 * <li>one walk answering for one table: 34 ms, and a full export asks for one
 * per table - 482 s;</li>
 * <li>one walk answering for every table of the database: 60 ms, asked once per
 * operation - 40 s, and the count of walks a whole export pays for is 1.</li>
 * </ul>
 * <p>
 * <b>The lifetime is one operation, and that is the whole of the contract.</b>
 * An index is a statement about a model as it stood when the index was built. A
 * model is rebuilt one statement at a time while an editor is open, so an index
 * that outlived the operation that built it would answer for a view that has
 * since stopped reading the column - the one wrong answer this whole mechanism
 * exists to prevent. It is therefore held by the settings of the operation,
 * which the Eclipse plugin creates fresh for each one
 * ({@code UISettings.forExport}, {@code UISettings.forGetChanges}) and the CLI
 * creates fresh for each output of a batch. It is deliberately <em>not</em> held
 * by the model, and deliberately not carried over by
 * {@code AbstractSettings.copy()} or by any {@code shallowCopy()}: the field
 * holding it is final and assigned at construction, so no copy can transfer it
 * even by accident.
 * <p>
 * A database is remembered by identity, never by name or content: the two states
 * of one comparison are two databases holding the same schemas and the same
 * table names, and each has its own answer.
 *
 * @see ColumnVisibility the one caller, and the reason all of this is asked
 */
public final class ColumnUsers {

    /**
     * The holder that remembers nothing.
     * <p>
     * For a caller with no operation to scope an index to - a settings
     * implementation of somebody else's, and the visibility that hides nothing
     * and therefore never asks. Every question is answered by a fresh walk,
     * exactly as it was before any index existed, so this is slow and cannot be
     * stale. It keeps neither a database nor an index, which is what lets it be
     * a constant: the visibility that hides nothing is one, and a constant
     * holding an index would hold a database for as long as the JVM lives.
     */
    public static final ColumnUsers NONE = new ColumnUsers(0);

    /**
     * How many databases one holder remembers, which is twice what a comparison
     * of two states asks for. Past this an index is still built and still
     * correct, only not remembered - the answer never depends on whether it was.
     */
    private static final int MAX_DATABASES = 4;

    private final int capacity;

    /** Remembered databases and their indexes, by position. Guarded by {@code this}. */
    private final List<IDatabase> databases;
    private final List<Map<TableName, Map<String, IStatement>>> indexes;

    /** How many walks were paid for. Guarded by {@code this}. */
    private int built;

    private ColumnUsers(int capacity) {
        this.capacity = capacity;
        this.databases = new ArrayList<>(capacity);
        this.indexes = new ArrayList<>(capacity);
    }

    /**
     * A holder for one operation - one comparison, one export, one output of a
     * batch run.
     *
     * @return a holder that remembers the databases of this operation and
     * nothing beyond it
     */
    public static ColumnUsers forOperation() {
        return new ColumnUsers(MAX_DATABASES);
    }

    /**
     * How many times the database was walked through this holder, which is what
     * the cost of the whole mechanism is measured in.
     * <p>
     * Zero for an ignore list that can hide no column, one per database
     * otherwise, and never one per table - that last is the regression this
     * holder exists to stop, and the only form it could take again.
     *
     * @return the number of walks paid for
     */
    public synchronized int indexesBuilt() {
        return built;
    }

    /**
     * Everything in the given database that names a column of the given table.
     *
     * @param db     the database to read, which is also the key: another
     *               instance holding the very same schemas is another answer
     * @param schema the name of the schema of the table, as the model spells it
     * @param table  the name of the table, as the model spells it
     * @return the columns of that table something names, each mapped to the
     * first statement that named it in the order the database lists them; empty
     * when nothing names any of them
     */
    Map<String, IStatement> namingColumnsOf(IDatabase db, String schema, String table) {
        return indexOf(db).getOrDefault(new TableName(schema, table), Map.of());
    }

    private synchronized Map<TableName, Map<String, IStatement>> indexOf(IDatabase db) {
        for (int i = 0; i < databases.size(); i++) {
            if (databases.get(i) == db) {
                return indexes.get(i);
            }
        }

        Map<TableName, Map<String, IStatement>> index = build(db);
        built++;
        if (databases.size() < capacity) {
            databases.add(db);
            indexes.add(index);
        }
        return index;
    }

    /**
     * Reads every statement of the database once.
     * <p>
     * The first statement to name a column wins, in the order the database lists
     * its descendants, which is the statement a walk of that same order would
     * have stopped at. The columns of one table are kept in the order they were
     * met for the same reason: whatever is built out of this is built in the
     * order it would have been built in before there was an index.
     * <p>
     * A reference is kept as the statement that made it and not as its qualified
     * name, because a name is built by walking up the parents of a statement and
     * only a handful of these are ever asked for - one per column of one table
     * that a rule names.
     */
    private static Map<TableName, Map<String, IStatement>> build(IDatabase db) {
        Map<TableName, Map<String, IStatement>> index = new HashMap<>();
        db.getDescendants().forEach(user -> user.getReferencedColumns().forEach(ref -> {
            if (ref.schema() == null || ref.table() == null || ref.column() == null) {
                // no table of the model answers to a name that is not there, so
                // such a reference was found by nobody before there was an index
                return;
            }
            index.computeIfAbsent(new TableName(ref.schema(), ref.table()), key -> new LinkedHashMap<>())
                    .putIfAbsent(ref.column(), user);
        }));
        return index;
    }

    /**
     * A table as a reference spells it: the raw names of the model, compared and
     * never resolved.
     */
    private record TableName(String schema, String table) {
    }
}
