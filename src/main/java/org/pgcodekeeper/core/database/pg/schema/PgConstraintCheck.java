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

import java.util.Objects;

import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.hasher.Hasher;
/**
 * PostgreSQL CHECK constraint implementation.
 * CHECK constraints enforce domain integrity by limiting the values
 * that can be placed in a column based on a Boolean expression.
 */
public class PgConstraintCheck extends PgConstraint {

    /**
     * The tail {@code pg_get_constraintdef()} writes for a constraint that was
     * never validated. It is the last thing that function appends, after the
     * closing parenthesis of the expression and after any {@code NO INHERIT},
     * so a definition that ends with it ends with it unambiguously - the
     * expression itself is always wrapped in {@code CHECK (...)}.
     */
    private static final String NOT_VALID_TAIL = " NOT VALID";

    private boolean isInherit = true;
    private String expression;

    /**
     * The expression as the comparison sees it: the same tokens with canonical
     * spacing, and the reserved words of the folded range
     * {@code SQLLexer.ALL..WITH} raised to upper case. Only that range folds, so
     * a word outside it - {@code IS}, for one - is still compared as written.
     * <p>
     * {@link #expression} keeps the text the DDL is written from.
     */
    private String expressionNormalized;

    /**
     * The server's own {@code pg_get_constraintdef()} text, held only while the
     * parse of that text has not succeeded.
     * <p>
     * {@link #expression} and {@link #expressionNormalized} are written in one
     * place - {@code PgAlterTable.parseAlterTableConstraint} for a table,
     * {@code PgCreateDomain.parseDomainConstraint} for a domain - which both
     * JDBC readers reach only through the loader's deferred finalizer, and that
     * finalizer runs only for a definition that parsed
     * ({@code AbstractJdbcLoader:377}). With neither of them written,
     * {@link #getDefinition()} still spells out {@code CHECK (} + the expression
     * + {@code )} and hands the generator {@code CHECK (null)}: a constraint the
     * server accepts and which forbids nothing, because {@code null} is not
     * false. There is no raw half to fill instead - the reader holds the whole
     * {@code CHECK (...)} clause, not the expression inside it, and splitting
     * the two is exactly what the parse is for - so the whole clause is kept and
     * returned verbatim.
     * <p>
     * On the successful path the returned text is assembled out of sub-spans of
     * this very clause, so emitting the clause instead is the same output by a
     * shorter route, not a different one. The finalizer drops it as soon as the
     * model carries the real expression.
     * <p>
     * It joins {@link #computeHash(Hasher)} and
     * {@link #compareUnalterable(PgConstraint)} for the reason
     * {@link #expressionNormalized} does: without it every field the comparison
     * reads carries the same value for two different unreadable constraints -
     * and for one that genuinely checks nothing - so the difference would leave
     * the diff tree and the script together.
     */
    private String catalogDefinition;

    /**
     * Creates a new PostgreSQL CHECK constraint.
     *
     * @param name constraint name
     */
    public PgConstraintCheck(String name) {
        super(name);
    }

    @Override
    public String getDefinition() {
        if (catalogDefinition != null) {
            return catalogDefinition;
        }

        var sbSQL = new StringBuilder();
        sbSQL.append("CHECK (").append(expression).append(')');
        if (!isInherit) {
            sbSQL.append(" NO INHERIT");
        }
        return sbSQL.toString();
    }

    @Override
    public String getErrorCode() {
        return DUPLICATE_OBJECT;
    }

    public void setInherit(boolean isInherit) {
        this.isInherit = isInherit;
        resetHash();
    }

    /**
     * @param expression           expression text as written, used for DDL output
     * @param expressionNormalized the same expression normalized for comparison
     */
    public void setExpression(final String expression, final String expressionNormalized) {
        this.expression = expression;
        this.expressionNormalized = expressionNormalized;
        resetHash();
    }

    /**
     * Holds - or releases - the server's own definition of this constraint.
     * <p>
     * A trailing {@code NOT VALID} is taken out of the text and into
     * {@link #setNotValid(boolean)} rather than kept with it, because the two
     * generators that write this definition put that word in two different
     * places: {@code PgConstraint.getCreationSQL} appends it after the
     * definition it just wrote, while {@code CREATE DOMAIN} has no syntax for it
     * at all, and {@code PgDomain} answers that by routing a constraint whose
     * {@link #isNotValid()} is set into an {@code ALTER DOMAIN} statement of its
     * own. Left inside the text, the same word would be doubled on the first
     * path and misplaced on the second.
     *
     * @param definition the {@code pg_get_constraintdef()} clause to emit
     *                   verbatim, or {@code null} once the model carries the
     *                   parsed expression
     */
    public void setCatalogDefinition(final String definition) {
        if (definition != null && definition.endsWith(NOT_VALID_TAIL)) {
            catalogDefinition = definition.substring(0, definition.length() - NOT_VALID_TAIL.length());
            setNotValid(true);
        } else {
            catalogDefinition = definition;
        }
        resetHash();
    }

    @Override
    public void computeHash(Hasher hasher) {
        super.computeHash(hasher);
        hasher.put(isInherit);
        hasher.put(expressionNormalized);
        hasher.put(catalogDefinition);
    }

    @Override
    public boolean compare(IStatement obj) {
        return this == obj || super.compare(obj);
    }

    @Override
    protected boolean compareUnalterable(PgConstraint newConstr) {
        if (newConstr instanceof PgConstraintCheck con) {
            return super.compareUnalterable(con)
                    && isInherit == con.isInherit
                    && Objects.equals(expressionNormalized, con.expressionNormalized)
                    && Objects.equals(catalogDefinition, con.catalogDefinition);
        }
        return false;
    }

    @Override
    protected PgConstraint getConstraintCopy(String name) {
        var con = new PgConstraintCheck(name);
        con.setInherit(isInherit);
        con.setExpression(expression, expressionNormalized);
        // already stripped of its NOT VALID tail, which fillCopy carries on its
        // own, so the setter's split is a no-op here and must stay one
        con.setCatalogDefinition(catalogDefinition);
        return con;
    }
}
