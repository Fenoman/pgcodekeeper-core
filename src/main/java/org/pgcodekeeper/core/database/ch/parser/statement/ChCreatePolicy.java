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
package org.pgcodekeeper.core.database.ch.parser.statement;

import java.util.Arrays;

import org.antlr.v4.runtime.CommonTokenStream;
import org.pgcodekeeper.core.database.ch.parser.ChParserUtils;
import org.pgcodekeeper.core.database.ch.parser.generated.CHParser.*;
import org.pgcodekeeper.core.database.ch.parser.launcher.ChExpressionAnalysisLauncher;
import org.pgcodekeeper.core.database.ch.schema.*;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Parser for ClickHouse CREATE POLICY statements.
 * Handles row-level security policy creation with support for multiple policies
 * on multiple tables, including policy actions and role assignments.
 */
public final class ChCreatePolicy extends ChParserAbstract {

    private static final String POLICY_NAME = "%s ON %s";

    private final Create_policy_stmtContext ctx;

    /**
     * Creates a parser for ClickHouse CREATE POLICY statements.
     *
     * @param ctx      the ANTLR parse tree context for the CREATE POLICY statement
     * @param db       the ClickHouse database schema being processed
     * @param stream   the token stream for expression normalization
     * @param settings parsing configuration settings
     */
    public ChCreatePolicy(Create_policy_stmtContext ctx, ChDatabase db, CommonTokenStream stream,
                          ISettings settings) {
        super(db, stream, settings);
        this.ctx = ctx;
    }

    @Override
    public void parseObject() {
        for (var fullNameCtx : ctx.policy_name()) {
            for (var tableNameCtx : fullNameCtx.qualified_name_or_asterisk()) {
                String parentName = getFullCtxText(tableNameCtx);
                for (var policyNameCtx : fullNameCtx.identifier()) {
                    String shortName = getFullCtxText(policyNameCtx);
                    ChPolicy policy = new ChPolicy(POLICY_NAME.formatted(shortName, parentName));
                    ctx.policy_action().forEach(e -> parsePolicyOption(policy, e));
                    addSafe(db, policy, Arrays.asList(tableNameCtx, policyNameCtx));
                }
            }
        }
    }

    private void parsePolicyOption(ChPolicy policy, Policy_actionContext actionCtx) {
        if (actionCtx.RESTRICTIVE() != null) {
            policy.setPermissive(false);
            return;
        }

        ExprContext using = actionCtx.expr();
        if (using != null) {
            setUsingWithAnalyze(policy, getFullCtxText(using), using, stream, db, fileName);
            return;
        }

        addRoles(actionCtx.users(), policy, ChPolicy::addRole, ChPolicy::addExcept, "ALL");
    }

    /**
     * The one place a policy filter is normalized, called from both sides of
     * the comparison: from here for a project file, and from
     * {@code ChPoliciesReader} for the filter of {@code system.row_policies}.
     * The two sides parse different shapes - a whole {@code CREATE POLICY}
     * against a bare expression - so this method, rather than a parser class,
     * is what makes them agree, and a test that drives one of them drives what
     * the other runs.
     * <p>
     * The raw half is a parameter because each side knows a different authority
     * for it: the project file has the author's own text, the reader has the
     * catalog's.
     *
     * @param policy   the policy to fill
     * @param using    the filter text as written, used for DDL output
     * @param ctx      the parsed filter, normalized here for comparison
     * @param stream   the token stream {@code ctx} came from
     * @param db       the database collecting analysis launchers
     * @param location where to report an unresolved reference from
     */
    public static void setUsingWithAnalyze(ChPolicy policy, String using, ExprContext ctx,
                                           CommonTokenStream stream, ChDatabase db, String location) {
        policy.setUsing(using, ChParserUtils.normalizeWhitespaceUnquoted(ctx, stream));
        db.addAnalysisLauncher(new ChExpressionAnalysisLauncher(policy, ctx, location));
    }

    @Override
    protected String getStmtAction() {
        return "CREATE POLICY";
    }
}
