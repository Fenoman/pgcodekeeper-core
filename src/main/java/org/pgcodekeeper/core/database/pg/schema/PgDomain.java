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
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.utils.Utils;

/**
 * PostgreSQL domain implementation.
 * A domain is a user-defined data type that is based on another underlying type,
 * with optional constraints, default values, and NOT NULL specifications.
 */
public class PgDomain extends PgAbstractStatement implements ISearchPath {

    private final List<PgConstraint> constraints = new ArrayList<>();

    private String dataType;
    private String collation;
    private String defaultValue;

    /**
     * The default as the comparison sees it: the same tokens with canonical
     * spacing, and the reserved words of the folded range
     * {@code SQLLexer.ALL..WITH} raised to upper case. Only that range folds, so
     * a word outside it - {@code IS}, for one - is still compared as written.
     * <p>
     * {@link #defaultValue} keeps the text the DDL is written from, because a
     * project file must round-trip exactly as its author wrote it.
     * <p>
     * The two halves must be present or absent together, which is why
     * {@link #setDefaultValue(String, String)} takes both at once:
     * {@link #getCreationSQL(SQLScript)} decides from {@link #defaultValue}
     * whether to write a {@code DEFAULT} clause at all, while
     * {@link #computeHash(Hasher)} and {@link #compare(IStatement)} read only
     * this field. Filling the raw half alone parts the two in both directions:
     * the script carries a {@code DEFAULT} the comparison believes is not
     * there, and a default the grammar could not read compares equal to no
     * default at all, since an empty normalized half is exactly what a domain
     * without a {@code DEFAULT} has. So the reader fills both halves with the
     * catalog's own text before it submits the parse, and the finalizer
     * overwrites the normalized one when the parse succeeds.
     * <p>
     * The gate in {@link #appendAlterSQL(IStatement, SQLScript)} reads the raw
     * half instead, deliberately. It is reached while a script is already being
     * written for something else - either for a domain the comparison called
     * changed, or for one {@code DepcyResolver} pulled in behind such a change,
     * where {@code addDropStatements} walks the reverse dependency graph into
     * {@code tryToDrop} and {@code getObjectState}, and {@code
     * addCreateStatements} does the same the other way round. Either way the
     * walk starts from an object the comparison did select, which writes at
     * least one statement of its own, so a merely re-spelled default adds one
     * redundant {@code SET DEFAULT} to a script that had to be written anyway
     * and never produces a script where there would otherwise be none. That
     * bound is measured over the whole pipeline rather than reasoned about, and
     * pinned whole by {@code PgTypesReaderDomainTest
     * .aRespelledDefaultOnlyEverJoinsAnAlterThatWasNeededAnyway}.
     */
    private String defaultValueNormalized;

    private boolean notNull;

    /**
     * Creates a new PostgreSQL domain.
     *
     * @param name domain name
     */
    public PgDomain(String name) {
        super(name);
    }

    @Override
    public DbObjType getStatementType() {
        return DbObjType.DOMAIN;
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE DOMAIN ").append(getQualifiedName())
                .append(" AS ").append(dataType);
        if (collation != null && !collation.isEmpty()) {
            sql.append(" COLLATE ").append(collation);
        }
        if (notNull) {
            sql.append(" NOT NULL");
        }
        if (defaultValue != null && !defaultValue.isEmpty()) {
            sql.append(" DEFAULT ").append(defaultValue);
        }

        List<PgConstraint> notValids = new ArrayList<>();
        for (PgConstraint constr : constraints) {
            if (constr.isNotValid()) {
                notValids.add(constr);
            } else {
                sql.append("\n\tCONSTRAINT ").append(constr.getQuotedName())
                        .append(' ').append(constr.getDefinition());
            }
        }
        script.addStatement(sql);

        for (PgConstraint notValid : notValids) {
            notValid.getCreationSQL(script);
        }

        appendOwnerSQL(script);
        appendPrivileges(script);
        appendComments(script);
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        int startSize = script.getSize();
        PgDomain newDomain = (PgDomain) newCondition;

        if (!Objects.equals(newDomain.dataType, dataType) ||
                !Objects.equals(newDomain.collation, collation)) {
            return ObjectState.RECREATE;
        }

        if (!Objects.equals(newDomain.defaultValue, defaultValue)) {
            StringBuilder sql = new StringBuilder();
            sql.append("ALTER DOMAIN ").append(getQualifiedName());
            if (newDomain.defaultValue == null) {
                sql.append("\n\tDROP DEFAULT");
            } else {
                sql.append("\n\tSET DEFAULT ").append(newDomain.defaultValue);
            }
            script.addStatement(sql);
        }

        if (newDomain.notNull != notNull) {
            StringBuilder sql = new StringBuilder();
            sql.append("ALTER DOMAIN ").append(getQualifiedName());
            if (newDomain.notNull) {
                sql.append("\n\tSET NOT NULL");
            } else {
                sql.append("\n\tDROP NOT NULL");
            }
            script.addStatement(sql);
        }

        appendAlterOwner(newDomain, script);
        alterPrivileges(newDomain, script);
        appendAlterComments(newDomain, script);

        for (PgConstraint oldConstr : constraints) {
            PgConstraint newConstr = newDomain.getConstraint(oldConstr.getName());
            if (newConstr == null) {
                oldConstr.getDropSQL(script);
            } else if ((oldConstr.appendAlterSQL(newConstr, script) == ObjectState.RECREATE)) {
                oldConstr.getDropSQL(script);
                newConstr.getCreationSQL(script);
            }
        }
        for (PgConstraint newConstr : newDomain.constraints) {
            if (getConstraint(newConstr.getName()) == null) {
                newConstr.getCreationSQL(script);
            }
        }

        return getObjectState(script, startSize);
    }

    @Override
    public void appendComments(SQLScript script) {
        super.appendComments(script);
        appendChildrenComments(script);
    }

    private void appendChildrenComments(SQLScript script) {
        for (PgConstraint c : constraints) {
            c.appendComments(script);
        }
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
        resetHash();
    }

    public void setCollation(String collation) {
        this.collation = collation;
        resetHash();
    }

    /**
     * @param defaultValue           the default expression as written, used for DDL output
     * @param defaultValueNormalized the same expression normalized for comparison
     */
    public void setDefaultValue(String defaultValue, String defaultValueNormalized) {
        this.defaultValue = defaultValue;
        this.defaultValueNormalized = defaultValueNormalized;
        resetHash();
    }

    public void setNotNull(boolean notNull) {
        this.notNull = notNull;
        resetHash();
    }

    /**
     * Returns a constraint by name.
     *
     * @param name constraint name
     * @return constraint or null if not found
     */
    public PgConstraint getConstraint(String name) {
        for (PgConstraint c : constraints) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Removes a constraint by name, if the domain has one under that name.
     * <p>
     * The counterpart of {@link #addConstraint(PgConstraint)}, for the
     * {@code ALTER DOMAIN ... DROP CONSTRAINT} a project file may carry: the
     * file states the constraints the domain ends up with, so one it drops has
     * to leave the model, or the database keeps a {@code CHECK} the project no
     * longer has.
     *
     * @param name constraint name, spelled as {@link #addConstraint} received it
     */
    public void removeConstraint(String name) {
        if (constraints.removeIf(c -> c.getName().equals(name))) {
            resetHash();
        }
    }

    /**
     * Adds a constraint to this domain.
     *
     * @param constraint constraint to add
     */
    public void addConstraint(PgConstraint constraint) {
        assertUnique(getConstraint(constraint.getName()), constraint);
        constraints.add(constraint);
        constraint.setParent(this);
        resetHash();
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.put(dataType);
        hasher.put(collation);
        hasher.put(defaultValueNormalized);
        hasher.put(notNull);
        hasher.putUnordered(constraints);
    }

    @Override
    public boolean compare(IStatement obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof PgDomain dom && super.compare(obj)
                && Objects.equals(dataType, dom.dataType)
                && Objects.equals(collation, dom.collation)
                && Objects.equals(defaultValueNormalized, dom.defaultValueNormalized)
                && notNull == dom.notNull
                && Utils.setLikeEquals(constraints, dom.constraints);
    }

    @Override
    protected PgDomain getCopy() {
        PgDomain domainDst = new PgDomain(name);
        domainDst.setDataType(dataType);
        domainDst.setCollation(collation);
        domainDst.setDefaultValue(defaultValue, defaultValueNormalized);
        domainDst.setNotNull(notNull);
        for (PgConstraint constr : constraints) {
            domainDst.addConstraint((PgConstraint) constr.deepCopy());
        }
        return domainDst;
    }
}
