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
package org.pgcodekeeper.core.database.pg.parser.statement;

import java.util.*;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.*;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgVexAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Parser for PostgreSQL CREATE POLICY statements.
 * <p>
 * This class handles parsing of row-level security policy definitions including
 * policy type (permissive/restrictive), events (SELECT, INSERT, UPDATE, DELETE),
 * target roles, and policy expressions (USING and WITH CHECK clauses).
 */
public final class PgCreatePolicy extends PgParserAbstract {

    private final Create_policy_statementContext ctx;
    private final CommonTokenStream stream;

    /**
     * Constructs a new CreatePolicy parser.
     *
     * @param ctx      the CREATE POLICY statement context
     * @param db       the PostgreSQL database object
     * @param stream   the token stream that produced {@code ctx}
     * @param settings the ISettings object
     */
    public PgCreatePolicy(Create_policy_statementContext ctx, PgDatabase db, CommonTokenStream stream,
            ISettings settings) {
        super(db, settings);
        this.ctx = ctx;
        this.stream = stream;
    }

    @Override
    public void parseObject() {
        List<ParserRuleContext> ids = getIdentifiers(ctx.schema_qualified_name());
        addObjReference(ids, DbObjType.TABLE, null);

        PgPolicy policy = new PgPolicy(ctx.identifier().getText());

        policy.setPermissive(ctx.RESTRICTIVE() == null);

        if (ctx.FOR() != null && ctx.ALL() == null) {
            policy.setEvent(EventType.valueOf(ctx.event.getText().toUpperCase(Locale.ROOT)));
        }

        fillRoles(policy);

        VexContext vex = ctx.using;
        if (vex != null) {
            // the normalized half is what the comparison reads, so a re-cased or
            // re-spaced filter no longer reads as a changed policy
            policy.setUsing(getFullCtxText(vex), PgParserUtils.normalizeWhitespaceUnquoted(vex, stream));
            db.addAnalysisLauncher(new PgVexAnalysisLauncher(policy, vex, fileName));
        }

        vex = ctx.check;
        if (vex != null) {
            policy.setCheck(getFullCtxText(vex), PgParserUtils.normalizeWhitespaceUnquoted(vex, stream));
            db.addAnalysisLauncher(new PgVexAnalysisLauncher(policy, vex, fileName));
        }

        ParserRuleContext parent = QNameParser.getFirstNameCtx(ids);
        IStatementContainer cont = getSafe(ISchema::getStatementContainer, getSchemaSafe(ids), parent);
        addSafe(cont, policy, Arrays.asList(QNameParser.getSchemaNameCtx(ids), parent, ctx.identifier()));
    }

    /**
     * Fills the role list of the policy, dropping it whole when it names
     * {@code PUBLIC}.
     * <p>
     * An empty role set is how a policy that applies to everyone is held on
     * both sides, see {@link PgPolicy}: the catalog stores {@code polroles =
     * {0}} for it, and {@code 0} is no row of {@code pg_roles}, so the reader
     * resolves it to nothing. Keeping the word here instead would make a file
     * that spells {@code TO PUBLIC} differ from the very database it describes,
     * forever - the {@code ALTER POLICY ... TO PUBLIC} such a difference
     * produces writes {@code {0}} back and changes nothing.
     * <p>
     * The word swallows the rest of the list, which is the server's own answer:
     * {@code TO PUBLIC, r1} stores {@code {0}} and warns <i>ignoring specified
     * roles other than PUBLIC</i> (measured on PostgreSQL 17.10). A model that
     * kept {@code r1} would go on to narrow a policy the project declares open.
     */
    private void fillRoles(PgPolicy policy) {
        List<User_nameContext> roles = ctx.user_name();
        for (User_nameContext role : roles) {
            if (isPublicRole(getFullCtxText(role))) {
                return;
            }
        }

        for (User_nameContext role : roles) {
            policy.addRole(getFullCtxText(role));
        }
    }

    /**
     * Reports whether the role is spelled the way the server reads as the
     * {@code PUBLIC} pseudo-role.
     * <p>
     * An unquoted word reaches the comparison folded to lower case, so every
     * casing of it is the pseudo-role; a quoted one is compared as it stands,
     * and {@code "PUBLIC"} is a name a database can actually hold - measured:
     * {@code CREATE ROLE "PUBLIC"} succeeds and a policy {@code TO "PUBLIC"}
     * then points at that role, not at everyone.
     */
    private static boolean isPublicRole(String role) {
        return role.startsWith("\"") ? "\"public\"".equals(role) : "public".equalsIgnoreCase(role);
    }

    @Override
    protected String getStmtAction() {
        List<ParserRuleContext> ids = new ArrayList<>(getIdentifiers(ctx.schema_qualified_name()));
        ids.add(ctx.identifier());
        return getStrForStmtAction(ACTION_CREATE, DbObjType.POLICY, ids);
    }
}