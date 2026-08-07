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

import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.script.SQLScript;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL table rule implementation.
 * Rules define actions to be performed when certain operations (INSERT, UPDATE, DELETE)
 * are executed on a table, effectively implementing view-like behavior and query rewriting.
 */
public class PgRule extends PgAbstractStatement implements IRule {

    private final List<String> commands = new ArrayList<>();

    private EventType event;
    private String condition;

    /**
     * The condition as the comparison sees it: the same tokens with canonical
     * spacing, and the reserved words of the folded range
     * {@code SQLLexer.ALL..WITH} raised to upper case. Only that range folds, so
     * a word outside it - {@code IS}, for one - is still compared as written.
     * <p>
     * {@link #condition} keeps the text the DDL is written from, because a
     * project file must round-trip exactly as its author wrote it.
     * <p>
     * The two halves must be present or absent together, which is why
     * {@link #setCondition(String, String)} takes both at once:
     * {@link #getCreationSQL(SQLScript)} decides from {@link #condition} whether
     * to emit a {@code WHERE} at all, while {@link #computeHash(Hasher)} and the
     * comparison read only this field. Filling the raw half alone - as a reader
     * might, to keep a value whose parse failed - would emit a condition that
     * the comparison believes is not there, and the two would part in silence.
     * That is why a definition whose parse failed is kept whole in
     * {@link #catalogDefinition} rather than halved into this pair.
     * <p>
     * {@link #commands}, the rule's action body, is deliberately not mirrored
     * this way and is still compared as written.
     */
    private String conditionNormalized;

    /**
     * The server's own {@code pg_get_ruledef()} statement, held only while the
     * parse of that statement has not succeeded.
     * <p>
     * {@link #condition} and every entry of {@link #commands} are written in one
     * place, {@code PgCreateRule.setConditionAndAddCommands}, which the JDBC
     * reader reaches through the loader's deferred finalizer - and that
     * finalizer runs only for a definition that parsed
     * ({@code AbstractJdbcLoader:377}). With neither of them written,
     * {@link #getCreationSQL(SQLScript)} reconstructs a rule with no condition
     * and no commands, and a rule with no commands is spelled
     * {@code DO NOTHING}: a statement the server accepts, and which on an
     * {@code INSTEAD} rule silently swallows every write the rule fires on.
     * There is no raw half to fill instead, because splitting one
     * {@code pg_get_ruledef()} string into a condition and its commands is
     * exactly what the parse is for - so the whole statement is kept instead,
     * and the generator emits it verbatim rather than reconstructing one it
     * cannot build. The finalizer drops it as soon as the model carries the real
     * halves.
     * <p>
     * On the successful path the generator's reconstruction is assembled from
     * the same catalog text - {@link #condition} and {@link #commands} are the
     * sub-spans of that very statement - so emitting the whole statement instead
     * is the same output by a shorter route, not a different one.
     * <p>
     * It joins {@link #computeHash(Hasher)} and
     * {@link #compareUnalterable(PgRule)} for the reason
     * {@link #conditionNormalized} does: a rule whose definition could not be
     * read must never compare equal to a rule that was read - least of all to
     * one that genuinely does say {@code DO NOTHING}, which is what an
     * unreadable rule looks like from every field the comparison reads.
     */
    private String catalogDefinition;

    private boolean instead;
    /**
     * null is default (ENABLED), otherwise contains "{ENABLE|DISABLE} [ALWAYS|REPLICA]" string
     */
    private String enabledState;

    /**
     * Creates a new PostgreSQL rule.
     *
     * @param name rule name
     */
    public PgRule(String name) {
        super(name);
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        if (catalogDefinition != null) {
            script.addStatement(stripStatementSeparator(catalogDefinition));
            appendEnabledStateAndComments(script);
            return;
        }

        final StringBuilder sbSQL = new StringBuilder();
        sbSQL.append("CREATE RULE ");
        sbSQL.append(getQuotedName());
        sbSQL.append(" AS\n    ON ").append(event);
        sbSQL.append(" TO ").append(parent.getQualifiedName());
        if (condition != null && !condition.isEmpty()) {
            sbSQL.append("\n  WHERE ").append(condition);
        }
        sbSQL.append(" DO ");
        if (instead) {
            sbSQL.append("INSTEAD ");
        }
        switch (commands.size()) {
            case 0:
                sbSQL.append("NOTHING");
                break;
            case 1:
                // space before is defined by get_query_def
                sbSQL.append(' ').append(commands.get(0));
                break;
            default:
                sbSQL.append('(');
                for (String command : commands) {
                    sbSQL.append(' ').append(command).append(";\n");
                }
                sbSQL.append(')');
        }
        script.addStatement(sbSQL);
        appendEnabledStateAndComments(script);
    }

    /**
     * The two parts a rule carries outside its definition: neither the enabled
     * state nor the comment is part of {@code pg_get_ruledef()}, so both are
     * appended the same way whichever branch above wrote the statement.
     */
    private void appendEnabledStateAndComments(SQLScript script) {
        if (enabledState != null) {
            addAlterTable(enabledState, this, script);
        }
        appendComments(script);
    }

    /**
     * Drops the terminating {@code ;} of a server-written statement, because
     * {@link SQLScript#addStatement(String)} appends the script's own separator
     * and the pair would otherwise read {@code ;;}. Only the last one goes: the
     * action body of a multi-command rule ends every command with a {@code ;} of
     * its own, inside the parentheses.
     */
    private static String stripStatementSeparator(String definition) {
        String trimmed = definition.stripTrailing();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        int startSize = script.getSize();
        PgRule newRule = (PgRule) newCondition;

        if (!compareUnalterable(newRule)) {
            return ObjectState.RECREATE;
        }
        String newEnabledState = newRule.enabledState;
        if (!Objects.equals(enabledState, newEnabledState)) {
            if (newEnabledState == null) {
                newEnabledState = "ENABLE";
            }
            addAlterTable(newEnabledState, newRule, script);
        }
        appendAlterComments(newRule, script);

        return getObjectState(script, startSize);
    }

    private void addAlterTable(String enabledState, PgRule rule, SQLScript script) {
        StringBuilder sql = new StringBuilder();
        sql.append(ALTER_TABLE)
                .append(parent.getQualifiedName())
                .append(' ')
                .append(enabledState)
                .append(" RULE ")
                .append(rule.getQuotedName());
        script.addStatement(sql);
    }

    @Override
    public void appendFullName(StringBuilder sb) {
        sb.append(getQuotedName()).append(" ON ").append(parent.getQualifiedName());
    }

    @Override
    public boolean canDropBeforeCreate() {
        return true;
    }

    public void setEvent(EventType event) {
        this.event = event;
        resetHash();
    }

    /**
     * @param condition           WHERE condition text as written, used for DDL output
     * @param conditionNormalized the same condition normalized for comparison
     */
    public void setCondition(String condition, String conditionNormalized) {
        this.condition = condition;
        this.conditionNormalized = conditionNormalized;
        resetHash();
    }

    /**
     * Holds - or releases - the server's own statement for this rule.
     *
     * @param catalogDefinition the {@code pg_get_ruledef()} text to emit
     *                          verbatim, or {@code null} once the model carries
     *                          the parsed condition and action commands
     */
    public void setCatalogDefinition(String catalogDefinition) {
        this.catalogDefinition = catalogDefinition;
        resetHash();
    }

    public void setInstead(boolean instead) {
        this.instead = instead;
        resetHash();
    }

    /**
     * Adds an action command to be executed when this rule fires.
     *
     * @param command SQL command to execute
     */
    public void addCommand(String command) {
        commands.add(command);
        resetHash();
    }

    public void setEnabledState(String enabledState) {
        this.enabledState = enabledState;
        resetHash();
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.put(event);
        hasher.put(conditionNormalized);
        hasher.put(instead);
        hasher.put(commands);
        hasher.put(enabledState);
        hasher.put(catalogDefinition);
    }

    @Override
    public boolean compare(IStatement obj) {
        if (this == obj) {
            return true;
        }

        return obj instanceof PgRule rule && super.compare(obj)
                && compareUnalterable(rule)
                && Objects.equals(enabledState, rule.enabledState);
    }

    private boolean compareUnalterable(PgRule rule) {
        return event == rule.event
                && Objects.equals(conditionNormalized, rule.conditionNormalized)
                && instead == rule.instead
                && commands.equals(rule.commands)
                && Objects.equals(catalogDefinition, rule.catalogDefinition);
    }

    @Override
    protected PgRule getCopy() {
        PgRule ruleDst = new PgRule(name);
        ruleDst.setEvent(event);
        ruleDst.setCondition(condition, conditionNormalized);
        ruleDst.setInstead(instead);
        ruleDst.commands.addAll(commands);
        ruleDst.setEnabledState(enabledState);
        ruleDst.setCatalogDefinition(catalogDefinition);
        return ruleDst;
    }
}
