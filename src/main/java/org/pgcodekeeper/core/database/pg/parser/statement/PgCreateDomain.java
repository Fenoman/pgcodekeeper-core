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

import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.*;
import org.pgcodekeeper.core.database.pg.parser.launcher.*;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.Utils;

/**
 * Parser for PostgreSQL CREATE DOMAIN statements.
 * <p>
 * This class handles parsing of domain definitions including the underlying
 * data type, default values, check constraints, collation, and null constraints.
 * Domains are user-defined data types based on existing types with additional
 * constraints and default values.
 */
public final class PgCreateDomain extends PgParserAbstract {

    private final Create_domain_statementContext ctx;
    private final CommonTokenStream stream;

    /**
     * Constructs a new CreateDomain parser.
     *
     * @param ctx      the CREATE DOMAIN statement context
     * @param db       the PostgreSQL database object
     * @param stream   the token stream for parsing
     * @param settings the ISettings object
     */
    public PgCreateDomain(Create_domain_statementContext ctx, PgDatabase db, CommonTokenStream stream, ISettings settings) {
        super(db, settings);
        this.ctx = ctx;
        this.stream = stream;
    }

    @Override
    public void parseObject() {
        List<ParserRuleContext> ids = getIdentifiers(ctx.name);
        PgDomain domain = new PgDomain(QNameParser.getFirstName(ids));
        domain.setDataType(getTypeName(ctx.dat_type));
        addTypeDepcy(ctx.dat_type, domain);
        for (Collate_identifierContext coll : ctx.collate_identifier()) {
            domain.setCollation(getFullCtxText(coll.collation));
            addDepSafe(domain, getIdentifiers(coll.collation), DbObjType.COLLATION);
        }
        VexContext exp = ctx.def_value;
        if (exp != null) {
            db.addAnalysisLauncher(new PgVexAnalysisLauncher(domain, exp, fileName));
            // the same token-level normalization the domain CHECK below gets,
            // so a re-cased or re-spaced DEFAULT no longer reads as a changed one
            domain.setDefaultValue(getExpressionText(exp, stream),
                    PgParserUtils.normalizeWhitespaceUnquoted(exp, stream));
        }
        for (Domain_constraintContext constrCtx : ctx.dom_constraint) {
            if (constrCtx.CHECK() != null) {
                IdentifierContext name = constrCtx.name;
                var constrCheck = new PgConstraintCheck(name != null ? name.getText() : "");
                parseDomainConstraint(domain, constrCheck, constrCtx, db, fileName, stream, settings);
                domain.addConstraint(constrCheck);
            }
            // вынесено ограничение, т.к. мы привязываем ограничение на нул к
            // объекту а не создаем отдельный констрайнт
            if (constrCtx.NULL() != null) {
                domain.setNotNull(constrCtx.NOT() != null);
            }
        }

        addSafe(getSchemaSafe(ids), domain, ids);
    }

    /**
     * Parses a domain constraint definition and configures the constraint object.
     * <p>
     * This method processes CHECK constraints for domains, including the constraint
     * expression and sets up analysis launchers for dependency tracking.
     * <p>
     * This is the single parsing point shared by all three domain CHECK sources -
     * {@code CREATE DOMAIN}, {@code ALTER DOMAIN ... ADD CONSTRAINT}, and the JDBC
     * catalog reader re-parsing {@code pg_get_constraintdef()} output - so the
     * normalized form is computed identically regardless of which one called in.
     *
     * @param domain   the domain object that owns the constraint
     * @param constr   the constraint object to configure
     * @param ctx      the domain constraint context
     * @param db       the database for analysis launchers
     * @param location the source location for error reporting
     * @param stream   the token stream that produced {@code ctx}, used to build
     *                 the normalized comparison form
     * @param settings the parser settings
     */
    public static void parseDomainConstraint(PgDomain domain, PgConstraintCheck constr,
                                             Domain_constraintContext ctx, IDatabase db, String location,
                                             CommonTokenStream stream, ISettings settings) {
        VexContext vexCtx = ctx.vex();
        String expr = Utils.checkNewLines(getFullCtxText(vexCtx), settings.isKeepNewlines());
        // Same token-level normalization CHECK constraints on tables already
        // get: canonical whitespace and upper case for the reserved words of the
        // folded range SQLLexer.ALL..WITH, so a re-cased or re-spaced domain
        // CHECK no longer reads as a changed one.
        String normalized = PgParserUtils.normalizeWhitespaceUnquoted(vexCtx, stream);
        constr.setExpression(expr, normalized);
        db.addAnalysisLauncher(new PgDomainAnalysisLauncher(domain, vexCtx, location));
    }

    @Override
    protected String getStmtAction() {
        return getStrForStmtAction(ACTION_CREATE, DbObjType.DOMAIN, getIdentifiers(ctx.name));
    }
}
