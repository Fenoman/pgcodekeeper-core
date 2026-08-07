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
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.script.SQLScript;

/**
 * PostgreSQL trigger implementation.
 * Triggers are functions that are automatically executed in response to database events
 * like INSERT, UPDATE, DELETE, or TRUNCATE on tables or views.
 */
public class PgTrigger extends PgAbstractStatement implements ITrigger {

    public enum TgTypes {
        BEFORE, AFTER, INSTEAD_OF
    }

    /**
     * Optional list of columns for UPDATE event.
     */
    private final Set<String> updateColumns = new HashSet<>();

    private String function;
    private String refTableName;
    private PgTriggerState triggerState;
    /**
     * Whether the trigger should be fired BEFORE, AFTER or INSTEAD_OF action. Default is
     * before.
     */
    private TgTypes tgType = TgTypes.BEFORE;
    /**
     * Whether the trigger should be fired FOR EACH ROW or FOR EACH STATEMENT.
     * Default is FOR EACH STATEMENT.
     */
    private boolean isForEachRow;
    private boolean isOnDelete;
    private boolean isOnInsert;
    private boolean isOnUpdate;
    private boolean isOnTruncate;
    private boolean isConstraint;
    private Boolean isImmediate;
    private String when;

    /**
     * The WHEN condition as the comparison sees it: the same tokens with
     * canonical spacing, and the reserved words of the folded range
     * {@code SQLLexer.ALL..WITH} raised to upper case. Only that range folds, so
     * a word outside it - {@code IS}, for one - is still compared as written.
     * <p>
     * {@link #when} keeps the text the DDL is written from.
     */
    private String whenNormalized;

    /**
     * The server's own {@code pg_get_triggerdef()} statement, held only while
     * the parse of that statement has not succeeded.
     * <p>
     * Both halves of {@link #when} are written in one place,
     * {@code PgCreateTrigger.parseWhen}, which the JDBC reader reaches through
     * the loader's deferred finalizer - and that finalizer runs only for a
     * definition that parsed ({@code AbstractJdbcLoader:377}). The reader fetches
     * and parses that definition for the {@code WHEN} clause and for nothing
     * else, so a failed parse costs the trigger exactly the thing the parse was
     * run for: {@link #getCreationSQL(SQLScript)} writes no {@code WHEN} at all,
     * and a trigger without one fires on every row instead of the few its
     * condition names.
     * <p>
     * There is no raw half to fill instead - the reader holds a whole
     * {@code CREATE TRIGGER} statement, not the condition inside it - so the
     * statement is kept and emitted verbatim. It is not a second spelling of the
     * trigger: on the successful path the condition is a sub-span of this very
     * statement and the rest is reconstructed from the same catalog row.
     * <p>
     * {@code pg_get_triggerdef()} carries neither the enabled state nor the
     * comment, so both are appended after it exactly as they are after the
     * generator's own statement - see {@link #appendEnabledStateAndComments}.
     * <p>
     * It joins {@link #computeHash(Hasher)} and {@link #compareUnalterable} for
     * the reason {@link #whenNormalized} does: without it a trigger whose
     * condition could not be read carries, in every field the comparison reads,
     * exactly what an unconditional trigger carries - so it would compare equal
     * to a project file that has no {@code WHEN}, and the difference would leave
     * the diff tree and the script together.
     */
    private String catalogDefinition;

    /**
     * REFERENCING old table name
     */
    private String oldTable;
    /**
     * REFERENCING new table name
     */
    private String newTable;

    /**
     * Creates a new PostgreSQL trigger.
     *
     * @param name trigger name
     */
    public PgTrigger(String name) {
        super(name);
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        if (catalogDefinition != null) {
            // measured on PostgreSQL 17.10: pg_get_triggerdef() writes no
            // terminating semicolon, so nothing is stripped before the script
            // appends its own separator
            script.addStatement(catalogDefinition);
            appendEnabledStateAndComments(script);
            return;
        }

        final StringBuilder sbSQL = new StringBuilder();
        sbSQL.append("CREATE");
        if (isConstraint) {
            sbSQL.append(" CONSTRAINT");
        }
        sbSQL.append(" TRIGGER ");
        sbSQL.append(getQuotedName());
        sbSQL.append("\n\t");
        sbSQL.append(tgType == TgTypes.INSTEAD_OF ? "INSTEAD OF" : tgType);

        boolean firstEvent = true;

        if (isOnInsert) {
            sbSQL.append(" INSERT");
            firstEvent = false;
        }

        if (isOnUpdate) {
            if (firstEvent) {
                firstEvent = false;
            } else {
                sbSQL.append(" OR");
            }

            sbSQL.append(" UPDATE");

            if (!updateColumns.isEmpty()) {
                sbSQL.append(" OF ");
                for (String updateColumn : updateColumns) {
                    sbSQL.append(quote(updateColumn));
                    sbSQL.append(", ");
                }
                sbSQL.setLength(sbSQL.length() - 2);
            }
        }

        if (isOnDelete) {
            if (!firstEvent) {
                sbSQL.append(" OR");
            }

            sbSQL.append(" DELETE");
        }

        if (isOnTruncate) {
            if (!firstEvent) {
                sbSQL.append(" OR");
            }

            sbSQL.append(" TRUNCATE");
        }

        sbSQL.append(" ON ");
        sbSQL.append(parent.getQualifiedName());

        if (isConstraint) {
            if (refTableName != null) {
                sbSQL.append("\n\tFROM ").append(refTableName);
            }
            if (isImmediate != null) {
                sbSQL.append("\n\tDEFERRABLE INITIALLY ")
                        .append(isImmediate ? "IMMEDIATE" : "DEFERRED");
            } else {
                sbSQL.append("\n\tNOT DEFERRABLE INITIALLY IMMEDIATE");
            }
        }

        if (oldTable != null || newTable != null) {
            sbSQL.append("\n\tREFERENCING ");
            if (newTable != null) {
                sbSQL.append("NEW TABLE AS ");
                sbSQL.append(newTable);
                sbSQL.append(' ');
            }
            if (oldTable != null) {
                sbSQL.append("OLD TABLE AS ");
                sbSQL.append(oldTable);
                sbSQL.append(' ');
            }
        }

        sbSQL.append("\n\tFOR EACH ");
        sbSQL.append(isForEachRow ? "ROW" : "STATEMENT");

        if (when != null) {
            sbSQL.append("\n\tWHEN (");
            sbSQL.append(when);
            sbSQL.append(')');
        }

        sbSQL.append("\n\tEXECUTE PROCEDURE ");
        sbSQL.append(function);
        script.addStatement(sbSQL);
        appendEnabledStateAndComments(script);
    }

    /**
     * The two parts a trigger carries outside its definition: neither the
     * enabled state nor the comment is part of {@code pg_get_triggerdef()}, so
     * both are appended the same way whichever branch above wrote the statement.
     */
    private void appendEnabledStateAndComments(SQLScript script) {
        if (triggerState != null) {
            addAlterTable(triggerState, this, script);
        }
        appendComments(script);
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        int startSize = script.getSize();
        PgTrigger newTrg = (PgTrigger) newCondition;
        if (!compareUnalterable(newTrg)) {
            return ObjectState.RECREATE;
        }
        PgTriggerState newTriggerState = newTrg.triggerState;
        if (!Objects.equals(triggerState, newTriggerState)) {
            if (newTriggerState == null) {
                newTriggerState = PgTriggerState.ENABLE;
            }
            addAlterTable(newTriggerState, newTrg, script);
        }
        appendAlterComments(newTrg, script);

        return getObjectState(script, startSize);
    }

    private void addAlterTable(PgTriggerState enabledState, PgTrigger trigger, SQLScript script) {
        StringBuilder sql = new StringBuilder();
        sql.append(ALTER_TABLE)
                .append(parent.getQualifiedName())
                .append(' ')
                .append(enabledState.getValue())
                .append(" TRIGGER ")
                .append(trigger.getQuotedName());
        script.addStatement(sql);
    }

    @Override
    public boolean canDropBeforeCreate() {
        return true;
    }

    @Override
    public void appendFullName(StringBuilder sb) {
        sb.append(getQuotedName()).append(" ON ").append(parent.getQualifiedName());
    }

    public void setType(final TgTypes tgType) {
        this.tgType = tgType;
        resetHash();
    }

    public void setForEachRow(final boolean isForEachRow) {
        this.isForEachRow = isForEachRow;
        resetHash();
    }

    public void setFunction(final String function) {
        this.function = function;
        resetHash();
    }

    public void setOnDelete(final boolean isOnDelete) {
        this.isOnDelete = isOnDelete;
        resetHash();
    }

    public void setOnInsert(final boolean isOnInsert) {
        this.isOnInsert = isOnInsert;
        resetHash();
    }

    public void setOnUpdate(final boolean isOnUpdate) {
        this.isOnUpdate = isOnUpdate;
        resetHash();
    }

    public void setOnTruncate(final boolean isOnTruncate) {
        this.isOnTruncate = isOnTruncate;
        resetHash();
    }

    public void setConstraint(final boolean isConstraint) {
        this.isConstraint = isConstraint;
        resetHash();
    }

    /**
     * Adds a column name to the UPDATE OF clause.
     *
     * @param columnName column to monitor for updates
     */
    public void addUpdateColumn(final String columnName) {
        updateColumns.add(columnName);
        resetHash();
    }

    /**
     * @param when           WHEN condition text as written, used for DDL output
     * @param whenNormalized the same condition normalized for comparison
     */
    public void setWhen(final String when, final String whenNormalized) {
        this.when = when;
        this.whenNormalized = whenNormalized;
        resetHash();
    }

    /**
     * Holds - or releases - the server's own statement for this trigger.
     *
     * @param catalogDefinition the {@code pg_get_triggerdef()} text to emit
     *                          verbatim, or {@code null} once the model carries
     *                          the parsed {@code WHEN} condition
     */
    public void setCatalogDefinition(final String catalogDefinition) {
        this.catalogDefinition = catalogDefinition;
        resetHash();
    }

    public void setImmediate(final Boolean isImmediate) {
        this.isImmediate = isImmediate;
        resetHash();
    }

    public void setRefTableName(final String refTableName) {
        this.refTableName = refTableName;
        resetHash();
    }

    public void setOldTable(String oldTable) {
        this.oldTable = oldTable;
        resetHash();
    }

    public void setNewTable(String newTable) {
        this.newTable = newTable;
        resetHash();
    }

    public void setTriggerState(PgTriggerState triggerState) {
        this.triggerState = triggerState;
        resetHash();
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.put(tgType);
        hasher.put(isForEachRow);
        hasher.put(function);
        hasher.put(isOnDelete);
        hasher.put(isOnInsert);
        hasher.put(isOnTruncate);
        hasher.put(isOnUpdate);
        hasher.put(whenNormalized);
        hasher.put(updateColumns);
        hasher.put(isConstraint);
        hasher.put(isImmediate);
        hasher.put(refTableName);
        hasher.put(newTable);
        hasher.put(oldTable);
        hasher.put(triggerState);
        hasher.put(catalogDefinition);
    }

    @Override
    public boolean compare(IStatement obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof PgTrigger trigger && super.compare(obj)
                && compareUnalterable(trigger)
                && triggerState == trigger.triggerState;
    }

    private boolean compareUnalterable(PgTrigger trigger) {
        return tgType == trigger.tgType
                && (isForEachRow == trigger.isForEachRow)
                && Objects.equals(function, trigger.function)
                && (isOnDelete == trigger.isOnDelete)
                && (isOnInsert == trigger.isOnInsert)
                && (isOnTruncate == trigger.isOnTruncate)
                && (isOnUpdate == trigger.isOnUpdate)
                && Objects.equals(whenNormalized, trigger.whenNormalized)
                && Objects.equals(updateColumns, trigger.updateColumns)
                && (isConstraint == trigger.isConstraint)
                && Objects.equals(isImmediate, trigger.isImmediate)
                && Objects.equals(refTableName, trigger.refTableName)
                && Objects.equals(newTable, trigger.newTable)
                && Objects.equals(oldTable, trigger.oldTable)
                && Objects.equals(catalogDefinition, trigger.catalogDefinition);
    }

    @Override
    protected PgTrigger getCopy() {
        PgTrigger trigger = new PgTrigger(name);
        trigger.setType(tgType);
        trigger.setForEachRow(isForEachRow);
        trigger.setFunction(function);
        trigger.setOnDelete(isOnDelete);
        trigger.setOnInsert(isOnInsert);
        trigger.setOnTruncate(isOnTruncate);
        trigger.setOnUpdate(isOnUpdate);
        trigger.setWhen(when, whenNormalized);
        trigger.updateColumns.addAll(updateColumns);
        trigger.setConstraint(isConstraint);
        trigger.setImmediate(isImmediate);
        trigger.setRefTableName(refTableName);
        trigger.setNewTable(newTable);
        trigger.setOldTable(oldTable);
        trigger.setTriggerState(triggerState);
        trigger.setCatalogDefinition(catalogDefinition);
        return trigger;
    }
}
