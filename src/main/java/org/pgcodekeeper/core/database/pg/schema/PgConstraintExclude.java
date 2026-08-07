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
package org.pgcodekeeper.core.database.pg.schema;

import java.util.*;

import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.hasher.Hasher;

/**
 * PostgreSQL EXCLUDE constraint implementation.
 * EXCLUDE constraints ensure that if any two rows are compared on specified columns
 * using specified operators, not all comparisons will return TRUE.
 */
public class PgConstraintExclude extends PgConstraint implements PgIndexParamContainer, ISimpleColumnContainer {

    /**
     * The access method an exclusion constraint gets when its clause names
     * none.
     * <p>
     * The same constant a plain index defaults to, and for the same reason: the
     * server builds the constraint's index with {@code DEFAULT_INDEX_TYPE}
     * where {@code USING} is absent. Measured on 17.10, an
     * {@code EXCLUDE (id WITH =)} written without the word comes back from
     * {@code pg_get_constraintdef} as {@code EXCLUDE USING btree (id WITH =)}.
     */
    private static final String DEFAULT_INDEX_METHOD = "btree";

    private final Map<String, String> params = new HashMap<>();
    private final Set<String> columnNames = new HashSet<>();
    private final List<SimpleColumn> columns = new ArrayList<>();
    private final List<String> includes = new ArrayList<>();

    private String indexMethod;
    private String predicate;

    /**
     * The predicate as the comparison sees it: the same tokens with canonical
     * spacing, and the reserved words of the folded range
     * {@code SQLLexer.ALL..WITH} raised to upper case. Only that range folds, so
     * a word outside it - {@code IS}, for one - is still compared as written.
     * <p>
     * {@link #predicate} keeps the text the DDL is written from.
     */
    private String predicateNormalized;

    private String tablespace;

    /**
     * The server's own {@code pg_get_constraintdef()} text, held only while the
     * parse of that text has not succeeded.
     * <p>
     * Every field this definition is built from - {@link #indexMethod},
     * {@link #columns}, {@link #predicate} - is written in one place,
     * {@code PgTableAbstract.processTableConstraintBlank}, which
     * {@code PgConstraintsReader} reaches only through the loader's deferred
     * finalizer, and that finalizer runs only for a definition that parsed
     * ({@code AbstractJdbcLoader:377}). With none of them written,
     * {@link #getDefinition()} spells out the bare word {@code EXCLUDE} and
     * nothing else - measured, not assumed - which PostgreSQL 17.10 answers with
     * {@code syntax error at or near ";"}. So the failing path used to hand the
     * script a statement no server accepts.
     * <p>
     * The quieter half is that two exclude constraints whose definitions both
     * failed to parse carry, in every field the comparison reads, exactly the
     * same values: no method, no columns, no predicate. They compare equal and
     * hash equal, so a difference between two databases leaves the diff tree and
     * the script together. That half survives the loud one, because a comparison
     * that finds them equal never writes the statement that would have failed.
     * <p>
     * There is no raw half to fill instead - the reader holds the whole
     * {@code EXCLUDE ...} clause, and splitting it into a method, a column list
     * and a predicate is exactly what the parse is for - so the whole clause is
     * kept and returned verbatim. On the successful path the returned text is
     * assembled out of sub-spans of this very clause, so emitting the clause
     * instead is the same output by a shorter route, not a different one. The
     * finalizer drops it as soon as the model carries the real fields.
     * <p>
     * Nothing is split off the kept text, and both candidates were measured
     * rather than reasoned about. {@code NOT VALID} cannot appear in it at all:
     * PostgreSQL 17.10 answers {@code EXCLUDE ... NOT VALID} with
     * {@code EXCLUDE constraints cannot be marked NOT VALID}. A trailing
     * {@code DEFERRABLE INITIALLY DEFERRED} does appear, and stays inside,
     * because the flags {@link PgConstraint#getCreationSQL} would append it from
     * are themselves written only by the same finalizer
     * ({@code PgTableAbstract:642}) - false on this path - so the words are
     * written exactly once, by the kept text.
     * <p>
     * What the text does not carry is the tablespace: {@code
     * pg_get_constraintdef()} omits it, and {@code USING INDEX TABLESPACE}
     * belongs before the {@code WHERE}, so it cannot be appended to a string
     * this grammar could not read. Such a constraint is recreated in the default
     * tablespace - as it already was, since the failing path used to lose the
     * tablespace along with everything else.
     * <p>
     * It joins {@link #computeHash(Hasher)} and
     * {@link #compareUnalterable(PgConstraint)} for the reason
     * {@link #predicateNormalized} does: it is the only field that tells two
     * unreadable constraints apart.
     */
    private String catalogDefinition;

    /**
     * Creates a new PostgreSQL EXCLUDE constraint.
     *
     * @param name constraint name
     */
    public PgConstraintExclude(String name) {
        super(name);
    }

    @Override
    public String getDefinition() {
        if (catalogDefinition != null) {
            return catalogDefinition;
        }

        var sbSQL = new StringBuilder();
        sbSQL.append("EXCLUDE");
        if (indexMethod != null) {
            sbSQL.append(" USING ").append(indexMethod);
        }
        appendSimpleColumns(sbSQL, columns);
        appendIndexParam(sbSQL);
        if (predicate != null) {
            sbSQL.append(" WHERE ").append(predicate);
        }
        return sbSQL.toString();
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
            if (col.getOperator() != null) {
                sbSQL.append(" WITH ").append(col.getOperator());
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
        if (!params.isEmpty()) {
            sb.append(" WITH");
            StatementUtils.appendOptionsWithParen(sb, params, "=");
        }
        if (tablespace != null) {
            sb.append("\n\tUSING INDEX TABLESPACE ").append(tablespace);
        }
    }

    @Override
    public String getErrorCode() {
        return DUPLICATE_RELATION;
    }

    @Override
    public void addInclude(String include) {
        includes.add(include);
        resetHash();
    }

    @Override
    public Set<String> getColumns() {
        return Collections.unmodifiableSet(columnNames);
    }

    @Override
    public boolean containsColumn(String name) {
        return columnNames.contains(name);
    }

    @Override
    public void addColumn(SimpleColumn column) {
        columnNames.add(column.getName());
        columns.add(column);
        resetHash();
    }

    @Override
    public void addParam(String key, String value) {
        params.put(key, value);
        resetHash();
    }

    public void setIndexMethod(String indexMethod) {
        this.indexMethod = indexMethod;
        resetHash();
    }

    /**
     * The access method the comparison sees: the one the clause names, or
     * {@link #DEFAULT_INDEX_METHOD} where it names none.
     * <p>
     * {@link #indexMethod} keeps {@code null} in the second case so that
     * {@link #getDefinition()} writes the clause back as its author wrote it.
     * The catalog's clause always carries the word, and
     * {@code PgConstraintsReader} builds this constraint by parsing that
     * clause - so an author who left {@code USING} out compared unequal to the
     * database for ever, dropping and adding the constraint, and its index with
     * it, on every deployment.
     *
     * @return the access method, never null
     */
    private String getComparableIndexMethod() {
        return indexMethod == null ? DEFAULT_INDEX_METHOD : indexMethod;
    }

    /**
     * Sets the predicate text and its normalized form.
     *
     * @param predicate           predicate text as written, used for DDL output
     * @param predicateNormalized the same predicate normalized for comparison
     */
    public void setPredicate(final String predicate, final String predicateNormalized) {
        this.predicate = predicate;
        this.predicateNormalized = predicateNormalized;
        resetHash();
    }

    /**
     * Holds - or releases - the server's own definition of this constraint.
     *
     * @param definition the {@code pg_get_constraintdef()} clause to emit
     *                   verbatim, or {@code null} once the model carries the
     *                   parsed method, columns and predicate
     */
    public void setCatalogDefinition(final String definition) {
        this.catalogDefinition = definition;
        resetHash();
    }

    @Override
    public void setTablespace(String tablespace) {
        this.tablespace = tablespace;
        resetHash();
    }

    @Override
    public void computeHash(Hasher hasher) {
        super.computeHash(hasher);
        hasher.put(params);
        hasher.put(includes);
        hasher.putOrdered(columns);
        hasher.put(getComparableIndexMethod());
        hasher.put(predicateNormalized);
        hasher.put(tablespace);
        hasher.put(catalogDefinition);
    }

    @Override
    public boolean compare(IStatement obj) {
        return this == obj || super.compare(obj);
    }

    @Override
    protected boolean compareUnalterable(PgConstraint newConstr) {
        var con = (PgConstraintExclude) newConstr;
        return super.compareUnalterable(con)
                && Objects.equals(params, con.params)
                && Objects.equals(includes, con.includes)
                && Objects.equals(columns, con.columns)
                && getComparableIndexMethod().equals(con.getComparableIndexMethod())
                && Objects.equals(predicateNormalized, con.predicateNormalized)
                && Objects.equals(tablespace, con.tablespace)
                && Objects.equals(catalogDefinition, con.catalogDefinition);
    }

    @Override
    protected PgConstraint getConstraintCopy(String name) {
        var con = new PgConstraintExclude(name);
        con.params.putAll(params);
        con.includes.addAll(includes);
        con.columnNames.addAll(columnNames);
        con.columns.addAll(columns);
        con.setIndexMethod(indexMethod);
        con.setPredicate(predicate, predicateNormalized);
        con.setTablespace(tablespace);
        con.setCatalogDefinition(catalogDefinition);
        return con;
    }
}