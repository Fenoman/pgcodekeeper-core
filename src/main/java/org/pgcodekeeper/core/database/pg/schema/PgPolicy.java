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

/**
 * PostgreSQL row security policy implementation.
 * Policies control which rows are visible or modifiable for specific users or roles,
 * providing fine-grained access control at the row level.
 */
public class PgPolicy extends PgAbstractStatement implements ISubElement, IPolicy {

    /**
     * The roles the policy applies to, and an empty set for the one the catalog
     * cannot name: {@code PUBLIC}.
     * <p>
     * {@code pg_policy.polroles} holds {@code {0}} for a policy that applies to
     * everyone, and {@code 0} is not a row of {@code pg_roles}, so
     * {@code PgPoliciesReader} resolves it to nothing at all. An empty set is
     * therefore what the database side of such a policy carries, and both
     * writers below already read it that way - {@link #getCreationSQL} leaves
     * the {@code TO} clause out, which is the same policy, and
     * {@link #appendAlterSQL} writes {@code TO PUBLIC} out of it. The project
     * side is folded to match in {@code PgCreatePolicy.fillRoles}, where the
     * spelling is still visible.
     */
    private final Set<String> roles = new LinkedHashSet<>();

    private String check;
    private boolean isPermissive = true;
    private EventType event;
    private String using;

    /**
     * The {@code USING} filter as the comparison sees it: the same tokens with
     * canonical spacing, and the reserved words of the folded range
     * {@code SQLLexer.ALL..WITH} raised to upper case. Only that range folds, so
     * a word outside it - {@code IS}, for one - is still compared as written.
     * <p>
     * {@link #using} keeps the text the DDL is written from, because a project
     * file must round-trip exactly as its author wrote it.
     * <p>
     * The two halves must be present or absent together, which is why
     * {@link #setUsing(String, String)} takes both at once:
     * {@link #getCreationSQL(SQLScript)} decides from {@link #using} whether to
     * emit a {@code USING} clause at all, while {@link #computeHash(Hasher)} and
     * {@link #compare(IStatement)} read only this field. Filling the raw half
     * alone parts the two in both directions: the script carries a
     * {@code USING} the comparison believes is not there, and a filter the
     * grammar could not read compares equal to no filter at all, since an empty
     * normalized half is exactly what a policy without a {@code USING} has. So
     * the reader fills both halves with the catalog's own text before it
     * submits the parse, and the finalizer overwrites the normalized one when
     * the parse succeeds.
     * <p>
     * The database side wraps both halves in parentheses of its own, because
     * {@code pg_get_expr} renders an operator expression parenthesized but a
     * bare {@code Var}, {@code Const} or function call not, while
     * {@code CREATE POLICY ... USING} takes a parenthesized expression always.
     * Wrapping the normalized half the same way keeps it the normalization of
     * the raw half, and keeps a file exported from a database comparing equal to
     * that database; the price is that a hand-written file parenthesizing the
     * filter once still differs. Both ends are pinned by
     * {@code PgPolicyNormalizationTest}.
     */
    private String usingNormalized;

    /**
     * The {@code WITH CHECK} expression as the comparison sees it. Everything
     * said of {@link #usingNormalized} holds here, with {@link #check} for the
     * raw half and {@link #setCheck(String, String)} for the setter.
     */
    private String checkNormalized;

    /**
     * Creates a new PostgreSQL policy.
     *
     * @param name policy name
     */
    public PgPolicy(String name) {
        super(name);
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        final StringBuilder sbSQL = new StringBuilder();
        sbSQL.append("CREATE POLICY ");
        appendFullName(sbSQL);

        if (!isPermissive) {
            sbSQL.append("\n  AS RESTRICTIVE");
        }

        if (event != null) {
            sbSQL.append("\n  FOR ").append(event);
        }

        if (!roles.isEmpty()) {
            sbSQL.append("\n  TO ").append(String.join(", ", roles));
        }

        if (using != null && !using.isEmpty()) {
            sbSQL.append("\n  USING ").append(using);
        }

        if (check != null && !check.isEmpty()) {
            sbSQL.append("\n  WITH CHECK ").append(check);
        }
        script.addStatement(sbSQL);
        appendComments(script);
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        int startSize = script.getSize();
        PgPolicy newPolice = (PgPolicy) newCondition;

        if (!compareUnalterable(newPolice)) {
            return ObjectState.RECREATE;
        }

        Set<String> newRoles = newPolice.roles;
        String newUsing = newPolice.using;
        String newCheck = newPolice.check;

        // the gates below read the raw halves, not the normalized ones: they
        // decide what to write, and what gets written is the author's own text.
        // The price of that seam is measured rather than reasoned about - see
        // PgPolicyNormalizationTest.aRespelledPolicyOnlyEverJoinsAnAlterThatWasNeededAnyway
        if (!Objects.equals(roles, newRoles) || !Objects.equals(using, newUsing)
                || !Objects.equals(check, newCheck)) {
            StringBuilder sbSql = new StringBuilder();
            sbSql.append("ALTER POLICY ");
            appendFullName(sbSql);

            if (!Objects.equals(roles, newRoles)) {
                sbSql.append("\n  TO ");
                if (newRoles.isEmpty()) {
                    sbSql.append("PUBLIC");
                } else {
                    sbSql.append(String.join(", ", newRoles));
                }
            }

            if (!Objects.equals(using, newUsing)) {
                sbSql.append("\n  USING ").append(newUsing);
            }

            if (!Objects.equals(check, newCheck)) {
                sbSql.append("\n  WITH CHECK ").append(newCheck);
            }
            script.addStatement(sbSql);
        }
        appendAlterComments(newPolice, script);

        return getObjectState(script, startSize);
    }

    @Override
    public void appendFullName(StringBuilder sb) {
        sb.append(getQuotedName()).append(" ON ").append(parent.getQualifiedName());
    }

    private boolean compareUnalterable(PgPolicy police) {
        // we can alter but cannot remove
        if (using != null && police.using == null) {
            return false;
        }

        if (check != null && police.check == null) {
            return false;
        }

        return event == police.event && isPermissive == police.isPermissive;
    }

    /**
     * @param check           WITH CHECK expression as written, used for DDL output
     * @param checkNormalized the same expression normalized for comparison
     */
    public void setCheck(String check, String checkNormalized) {
        this.check = check;
        this.checkNormalized = checkNormalized;
        resetHash();
    }

    public void setEvent(EventType event) {
        this.event = event;
        resetHash();
    }

    public void addRole(String role) {
        roles.add(role);
        resetHash();
    }

    public void setPermissive(boolean isPermissive) {
        this.isPermissive = isPermissive;
        resetHash();
    }

    /**
     * @param using           USING filter as written, used for DDL output
     * @param usingNormalized the same filter normalized for comparison
     */
    public void setUsing(String using, String usingNormalized) {
        this.using = using;
        this.usingNormalized = usingNormalized;
        resetHash();
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.put(isPermissive);
        hasher.put(event);
        hasher.put(roles);
        hasher.put(usingNormalized);
        hasher.put(checkNormalized);
    }

    @Override
    public boolean compare(IStatement obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof PgPolicy police && super.compare(obj)
                && isPermissive == police.isPermissive
                && event == police.event
                && roles.equals(police.roles)
                && Objects.equals(usingNormalized, police.usingNormalized)
                && Objects.equals(checkNormalized, police.checkNormalized);
    }

    @Override
    protected PgPolicy getCopy() {
        PgPolicy copy = new PgPolicy(name);
        copy.isPermissive = isPermissive;
        copy.event = event;
        copy.roles.addAll(roles);
        copy.setUsing(using, usingNormalized);
        copy.setCheck(check, checkNormalized);
        return copy;
    }
}
