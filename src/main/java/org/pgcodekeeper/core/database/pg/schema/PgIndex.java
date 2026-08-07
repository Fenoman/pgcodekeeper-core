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
 **
 * Copyright 2006 StartNet s.r.o.
 *
 * Distributed under MIT license
 *******************************************************************************/
package org.pgcodekeeper.core.database.pg.schema;

import java.util.*;

import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * PostgreSQL index implementation.
 * Supports all PostgreSQL index features including unique constraints,
 * partial indexes, expression indexes, and index inheritance for partitioned tables.
 */
public class PgIndex extends PgAbstractStatement implements IIndex {

    private static final String ALTER_INDEX = "ALTER INDEX ";

    /**
     * The access method an index gets when its statement names none.
     * <p>
     * PostgreSQL has no setting that moves it: measured on 17.10, the only
     * {@code default_%method%} setting the server carries is
     * {@code default_table_access_method}, so unlike a table's {@code heap} this
     * default is a constant of the language rather than of the session.
     */
    private static final String DEFAULT_METHOD = "btree";

    private final List<SimpleColumn> columns = new ArrayList<>();
    private final List<String> includes = new ArrayList<>();
    private final Map<String, String> options = new LinkedHashMap<>();

    private Inherits inherit;
    private String method;
    private boolean nullsDistinction = true;
    private boolean unique;
    private String where;

    /**
     * The predicate as the comparison sees it: the same tokens with canonical
     * spacing, and the reserved words of the folded range
     * {@code SQLLexer.ALL..WITH} raised to upper case. Only that range folds, so
     * a word outside it - {@code IS}, for one - is still compared as written.
     * <p>
     * {@link #where} keeps the text the DDL is written from, because a project
     * file must round-trip exactly as its author wrote it.
     */
    private String whereNormalized;

    private boolean isClustered;
    private String tablespace;

    /**
     * Creates a new PostgreSQL index.
     *
     * @param name index name
     */
    public PgIndex(String name) {
        super(name);
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        getCreationSQL(script, name);
        appendComments(script);
    }

    /**
     * {@inheritDoc}
     * <p>
     * An index whose key, method or predicate has changed is not alterable at
     * all, so this answers {@link ObjectState#RECREATE} and writes nothing -
     * writing under that answer is writing into a script nobody reads.
     * {@code DepcyResolver.getObjectState} keeps the script of an
     * {@link ObjectState#ALTER} and an {@link ObjectState#ALTER_WITH_DEP} and
     * of no other state, and the one place that rebuilds it,
     * {@code ActionsToScriptConverter}, throws away what it built for the same
     * reason. A {@code RECREATE} reaches the migration as a {@code DROP} action
     * and a {@code CREATE} action instead.
     * <p>
     * {@code --concurrently-mode} does not change that, and never did. It is
     * the {@code CREATE} of the pair that carries the word, see
     * {@link #getCreationSQL(SQLScript, String)}.
     */
    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        int startSize = script.getSize();
        PgIndex newIndex = (PgIndex) newCondition;

        if (!compareUnalterable(newIndex) || (null != inherit && !Objects.equals(inherit, newIndex.inherit))) {
            return ObjectState.RECREATE;
        }

        if (null == inherit && null != newIndex.inherit) {
            script.addStatement(ALTER_INDEX + newIndex.inherit.getQualifiedName() + " ATTACH PARTITION " + getQualifiedName());
        }

        if (!Objects.equals(tablespace, newIndex.tablespace)) {
            StringBuilder sql = new StringBuilder();
            sql.append(ALTER_INDEX).append(newIndex.getQualifiedName())
                    .append(" SET TABLESPACE ");

            String newSpace = newIndex.tablespace;
            sql.append(newSpace == null ? PG_DEFAULT : newSpace);
            script.addStatement(sql);
        }

        if (newIndex.isClustered != isClustered) {
            if (newIndex.isClustered) {
                script.addStatement(newIndex.appendClusterSql());
            } else if (!((PgAbstractStatementContainer) newIndex.parent).isClustered()) {
                script.addStatement(ALTER_TABLE + newIndex.parent.getQualifiedName() + " SET WITHOUT CLUSTER");
            }
        }

        compareOptions(newIndex, script);
        appendAlterComments(newIndex, script);

        return getObjectState(script, startSize);
    }

    /**
     * Writes the {@code CREATE INDEX} of this index, under the name given.
     * <p>
     * The one place {@code --concurrently-mode} is honoured, and the whole of
     * what it means: the index is built without a write lock on the table. A
     * rebuild is still a {@code DROP} and then this, so the table is left
     * without the index for the length of the build - and for a {@code UNIQUE}
     * index, without the guarantee it carries. That is the trade the setting
     * offers, and the reason it excludes {@code --add-transaction}, which a
     * {@code CREATE INDEX CONCURRENTLY} cannot run inside.
     *
     * @param script the script to write into
     * @param name   bare name to create the index under
     */
    private void getCreationSQL(SQLScript script, String name) {
        final StringBuilder sbSQL = new StringBuilder();
        sbSQL.append("CREATE ");

        if (unique) {
            sbSQL.append("UNIQUE ");
        }

        sbSQL.append("INDEX ");
        ISettings settings = script.getSettings();
        if (settings.isConcurrentlyMode() && !settings.isAddTransaction()) {
            sbSQL.append("CONCURRENTLY ");
        }
        if (inherit != null || settings.isGenerateExists()) {
            sbSQL.append("IF NOT EXISTS ");
        }
        sbSQL.append(quote(name)).append(" ON ");
        if (isOnPartitionedParent() && hasAttachingChildren()) {
            sbSQL.append("ONLY ");
        }
        sbSQL.append(parent.getQualifiedName());
        if (method != null) {
            sbSQL.append(" USING ").append(quote(method));
        }
        appendSimpleColumns(sbSQL, columns);
        appendIndexParam(sbSQL);
        appendWhere(sbSQL);
        script.addStatement(sbSQL);

        if (isClustered) {
            script.addStatement(appendClusterSql());
        }

        if (inherit != null) {
            script.addStatement(ALTER_INDEX + inherit.getQualifiedName() + " ATTACH PARTITION " + getQualifiedName());
        }
    }

    private boolean isOnPartitionedParent() {
        return parent instanceof PgAbstractRegularTable regTable && regTable.getPartitionBy() != null;
    }

    /**
     * Reports whether an index of a partition will be attached to this one.
     * <p>
     * {@code ON ONLY} leaves the index invalid until an index of every partition
     * has been attached, and those attaches are written only for the partitions
     * the model holds. Where the model holds none - the partitions live on the
     * target database and never enter the project - the word would leave behind
     * an index no plan can use, so a plain recursive {@code CREATE INDEX} is
     * both correct and what the author meant.
     * <p>
     * Deliberately not memoised. The plugin rebuilds its model incrementally, and
     * a stale answer here would cost far more than the scan: only indexes of a
     * partitioned parent ask at all, the test is a null check on all but a few,
     * and it stops at the first match.
     */
    private boolean hasAttachingChildren() {
        Inherits self = new Inherits(getSchemaName(), getName());
        return getDatabase().getDescendants()
                .anyMatch(st -> st instanceof PgIndex index && self.equals(index.inherit));
    }

    private void appendSimpleColumns(StringBuilder sbSQL, List<SimpleColumn> columns) {
        // The grammar requires at least one column here, so an empty list never
        // reaches this method today - guarded anyway the same way
        // StatementUtils.appendCols() is, whose unconditional variant once cut
        // into whatever the caller had written before the opening paren on an
        // empty collection instead of a trailing ", " that was never written.
        if (columns.isEmpty()) {
            return;
        }
        sbSQL.append(" (");
        for (var col : columns) {
            // column name already quoted
            sbSQL.append(col.getName());
            if (col.getCollation() != null) {
                sbSQL.append(" COLLATE ").append(col.getCollation());
            }
            if (col.getOpClass() != null) {
                sbSQL.append(' ').append(col.getOpClass());
                var opClassParams = col.getOpClassParams();
                if (!opClassParams.isEmpty()) {
                    StatementUtils.appendOptionsWithParen(sbSQL, opClassParams, "=");
                }
            }
            if (col.isDesc()) {
                sbSQL.append(" DESC");
            }
            if (col.getNullsOrdering() != null) {
                sbSQL.append(col.getNullsOrdering());
            }
            sbSQL.append(", ");
        }
        sbSQL.setLength(sbSQL.length() - 2);
        sbSQL.append(')');
    }

    private void appendIndexParam(StringBuilder sb) {
        if (!includes.isEmpty()) {
            sb.append(" INCLUDE ");
            StatementUtils.appendCols(sb, includes, getQuoter());
        }
        if (!nullsDistinction) {
            sb.append(" NULLS NOT DISTINCT");
        }
        if (!options.isEmpty()) {
            sb.append("\nWITH");
            StatementUtils.appendOptionsWithParen(sb, options, "=");
        }
        if (tablespace != null) {
            sb.append("\nTABLESPACE ").append(tablespace);
        }
    }

    private void appendWhere(StringBuilder sbSQL) {
        if (where != null) {
            sbSQL.append("\nWHERE ").append(where);
        }
    }

    @Override
    public String getQualifiedName() {
        if (qualifiedName == null) {
            qualifiedName = quote(getSchemaName()) + '.' + getQuotedName();
        }
        return qualifiedName;
    }

    private String appendClusterSql() {
        return "ALTER " + parent.getTypeName() + ' ' + parent.getQualifiedName() + " CLUSTER ON " + name;
    }

    @Override
    public boolean canDrop() {
        return inherit == null;
    }

    @Override
    public boolean compareColumns(Collection<String> refs) {
        if (refs.size() != columns.size()) {
            return false;
        }
        int i = 0;
        for (String ref : refs) {
            if (!ref.equals(columns.get(i++).getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * PostgreSQL pairs the referenced columns with the index key columns in any order - see
     * {@code transformFkeyCheckAttrs} - so {@code FOREIGN KEY (x, y) REFERENCES p (b, a)} is backed by a unique index
     * on {@code (a, b)} just as well as one on {@code (b, a)}. Matching positionally would miss that index and leave
     * the key out of the dependency graph, which costs the migration the DROP/CREATE pair around a recreated index.
     */
    @Override
    public boolean canBackForeignKey(Collection<String> refs) {
        if (refs.size() != columns.size()) {
            return false;
        }
        List<String> sortedRefs = new ArrayList<>(refs);
        List<String> sortedColumns = new ArrayList<>(refs.size());
        for (SimpleColumn column : columns) {
            sortedColumns.add(column.getName());
        }
        Collections.sort(sortedRefs);
        Collections.sort(sortedColumns);
        return sortedRefs.equals(sortedColumns);
    }

    /**
     * Gets the index access method (btree, hash, gin, gist, etc.).
     *
     * @return index method name
     */
    public String getMethod() {
        return method;
    }

    /**
     * The access method the comparison sees: the one the statement names, or
     * {@link #DEFAULT_METHOD} where it names none.
     * <p>
     * {@link #method} keeps {@code null} in the second case, because a project
     * file must round-trip exactly as its author wrote it and
     * {@link #getCreationSQL(SQLScript, String)} writes {@code USING} only where
     * there was one. The database side has no such freedom:
     * {@code pg_get_indexdef} always prints the method, and
     * {@code PgIndicesReader} builds its index by handing that text to this
     * dialect's own {@code CREATE INDEX} parser - so a hand-written
     * {@code CREATE INDEX i ON t (c)} met {@code btree} from the catalog and
     * compared unequal to it for ever, rebuilding the index on every deployment.
     *
     * @return the access method, never null
     */
    private String getComparableMethod() {
        return method == null ? DEFAULT_METHOD : method;
    }

    public void setMethod(String method) {
        this.method = method;
        resetHash();
    }

    /**
     * Sets the parent index for this partitioned index.
     *
     * @param schemaName parent index schema name
     * @param indexName  parent index name
     */
    public void addInherit(final String schemaName, final String indexName) {
        inherit = new Inherits(schemaName, indexName);
        resetHash();
    }

    public void setNullsDistinction(boolean nullsDistinction) {
        this.nullsDistinction = nullsDistinction;
        resetHash();
    }

    @Override
    public void addOption(String key, String value) {
        options.put(key, value);
        resetHash();
    }

    /**
     * Removes a storage parameter by name, if the index carries one under that
     * name.
     * <p>
     * The counterpart of {@link #addOption(String, String)}, for the
     * {@code ALTER INDEX ... RESET (...)} of a project file. A file states the
     * parameters the index ends up with, so one it resets has to leave the
     * model, or the database keeps a parameter the project no longer sets - and
     * the tool writes the {@code SET} back on the next comparison.
     * <p>
     * A name that matches nothing is left alone rather than reported, the same
     * answer {@code PgAbstractTable.removeOption} gives and the same the server
     * gives: measured on PostgreSQL 17.10, resetting a parameter that was never
     * set raises nothing.
     *
     * @param option option name, spelled as {@link #addOption} received it
     */
    public void removeOption(String option) {
        if (options.remove(option) != null) {
            resetHash();
        }
    }

    @Override
    public Map<String, String> getOptions() {
        return Collections.unmodifiableMap(options);
    }

    @Override
    public void addColumn(SimpleColumn column) {
        columns.add(column);
        resetHash();
    }

    @Override
    public void addInclude(String column) {
        includes.add(column);
        resetHash();
    }

    public boolean isClustered() {
        return isClustered;
    }

    public void setClustered(boolean isClustered) {
        this.isClustered = isClustered;
        resetHash();
    }

    public void setUnique(boolean isUnique) {
        this.unique = isUnique;
        resetHash();
    }

    /**
     * @param where           predicate text as written, used for DDL output
     * @param whereNormalized the same predicate normalized for comparison
     */
    public void setWhere(final String where, final String whereNormalized) {
        this.where = where;
        this.whereNormalized = whereNormalized;
        resetHash();
    }

    public void setTablespace(String tablespace) {
        this.tablespace = tablespace;
        resetHash();
    }

    @Override
    public boolean isUnique() {
        return unique;
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.putOrdered(columns);
        hasher.put(unique);
        hasher.put(whereNormalized);
        hasher.put(includes);
        hasher.put(inherit);
        hasher.put(getComparableMethod());
        hasher.put(nullsDistinction);
        hasher.put(isClustered);
        hasher.put(tablespace);
        hasher.put(options);
    }

    @Override
    public boolean compare(IStatement obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof PgIndex index && super.compare(obj)
                && compareUnalterable(index)
                && Objects.equals(inherit, index.inherit)
                && isClustered == index.isClustered
                && Objects.equals(tablespace, index.tablespace)
                && Objects.equals(options, index.options);
    }

    private boolean compareUnalterable(PgIndex index) {
        return Objects.equals(columns, index.columns)
                && unique == index.unique
                && Objects.equals(whereNormalized, index.whereNormalized)
                && Objects.equals(includes, index.includes)
                && getComparableMethod().equals(index.getComparableMethod())
                && nullsDistinction == index.nullsDistinction;
    }

    @Override
    protected PgIndex getCopy() {
        PgIndex copy = new PgIndex(name);
        copy.columns.addAll(columns);
        copy.unique = unique;
        copy.where = where;
        copy.whereNormalized = whereNormalized;
        copy.includes.addAll(includes);
        copy.inherit = inherit;
        copy.setMethod(method);
        copy.setNullsDistinction(nullsDistinction);
        copy.isClustered = isClustered;
        copy.tablespace = tablespace;
        copy.options.putAll(options);
        return copy;
    }
}
