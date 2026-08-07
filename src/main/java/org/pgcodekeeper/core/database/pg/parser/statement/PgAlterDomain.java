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
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.*;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgVexAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Parser for PostgreSQL ALTER DOMAIN statements.
 * <p>
 * Reads the alternatives {@code alter_domain_statement} offers that state
 * something a {@code CREATE DOMAIN} could have stated: {@code ADD CONSTRAINT
 * ... CHECK}, {@code DROP CONSTRAINT}, {@code VALIDATE CONSTRAINT}, the pair
 * {@code SET DEFAULT} / {@code DROP DEFAULT}, and the pair
 * {@code SET NOT NULL} / {@code DROP NOT NULL}. The statement is recorded as a
 * reference to the domain whichever alternative it turned out to be.
 * <p>
 * The remaining alternatives - {@code RENAME CONSTRAINT}, {@code RENAME TO} and
 * {@code SET SCHEMA} - reach no writer, and deliberately so: they change what
 * the object is called or where it lives rather than what it holds, and a
 * project file already spells the name and the schema it means in the
 * {@code CREATE} itself. Neither does the {@code NOT? NULL} form of
 * {@code domain_constraint} ({@code SQLParser.g4:981-983}) when it arrives
 * after {@code ADD}: only the {@code CREATE DOMAIN} parser acts on that form.
 */
public final class PgAlterDomain extends PgParserAbstract {

    private final Alter_domain_statementContext ctx;
    private final CommonTokenStream stream;

    /**
     * Constructs a new AlterDomain parser.
     *
     * @param ctx      the ALTER DOMAIN statement context
     * @param db       the PostgreSQL database object
     * @param stream   the token stream for parsing
     * @param settings the ISettings object
     */
    public PgAlterDomain(Alter_domain_statementContext ctx, PgDatabase db, CommonTokenStream stream, ISettings settings) {
        super(db, settings);
        this.ctx = ctx;
        this.stream = stream;
    }

    @Override
    public void parseObject() {
        List<ParserRuleContext> ids = getIdentifiers(ctx.name);
        PgDomain domain = getSafe(PgSchema::getDomain,
                getSchemaSafe(ids), QNameParser.getFirstNameCtx(ids));

        Domain_constraintContext constrCtx = ctx.dom_constraint;
        if (constrCtx != null && constrCtx.CHECK() != null) {
            IdentifierContext name = constrCtx.name;
            var constrCheck = new PgConstraintCheck(name != null ? name.getText() : "");
            PgCreateDomain.parseDomainConstraint(domain, constrCheck, constrCtx, db, fileName, stream, settings);
            if (ctx.not_valid != null) {
                constrCheck.setNotValid(true);
            }
            doSafe(PgDomain::addConstraint, domain, constrCheck);
        }

        Set_def_columnContext setDefault = ctx.set_def_column();
        if (setDefault != null) {
            VexContext exp = setDefault.vex();
            doSafe((d, e) -> {
                // both halves at once, the way every other writer of a domain
                // default fills them: the raw text is what the DDL is written
                // from, while compare and computeHash read only the normalized
                // one - and an empty normalized half is precisely what a domain
                // with no DEFAULT carries, so filling the raw half alone would
                // leave this ALTER comparing equal to no default at all
                d.setDefaultValue(getExpressionText(e, stream),
                        PgParserUtils.normalizeWhitespaceUnquoted(e, stream));
                db.addAnalysisLauncher(new PgVexAnalysisLauncher(d, e, fileName));
            }, domain, exp);
        } else if (ctx.drop_def() != null) {
            // DROP DEFAULT clears the value rather than being ignored. A project
            // file states the domain's final shape, so a file whose last word on
            // the default is DROP describes a domain without one, and a database
            // that still has one differs from it - which is the same rule the
            // SET branch above follows, applied in the other direction. Ignoring
            // the clause instead would mirror the very asymmetry this class was
            // fixed for: the file would say "no default", the model would keep
            // one, and the database would never be told.
            doSafe((d, unused) -> d.setDefaultValue(null, null), domain, null);
        } else if (ctx.NULL() != null) {
            // (SET | DROP) NOT NULL, the flag half of the rule the DEFAULT pair
            // above follows: a project file states the shape the domain is in,
            // so SET raises the flag and DROP clears it. Left unread the flag
            // arrived absent whatever the file said, and appendAlterSQL wrote
            // the opposite statement against the database - DROP NOT NULL at a
            // domain whose project file had just declared it.
            //
            // NULL is what tells this alternative apart. NOT cannot: it belongs
            // to the ADD ... NOT VALID alternative as well. SET and DROP can,
            // and are used below for the direction, because the alternatives
            // that also open with those words - set_def_column, drop_def,
            // drop_constraint and set_schema - keep them inside sub-rules of
            // their own, so at this level they appear for this one alone
            doSafe(PgDomain::setNotNull, domain, ctx.SET() != null);
        } else if (ctx.drop_constraint() != null) {
            Drop_constraintContext dropCtx = ctx.drop_constraint();
            IdentifierContext conNameCtx = dropCtx.constraint_name;
            if (dropCtx.if_exists() == null) {
                // the name has to resolve, the same way the domain's own name
                // does above; the removal below is what happens either way, so
                // this call is the check and nothing else. In REF mode getSafe
                // reports nothing and returns null, and doSafe skips too
                getSafe(PgDomain::getConstraint, domain, conNameCtx);
            }
            // IF EXISTS is the author's own word that the constraint may not be
            // there, so the unknown name is silence rather than a reported one
            doSafe(PgDomain::removeConstraint, domain, conNameCtx.getText());
            // CASCADE and RESTRICT are deliberately not read: they say how the
            // database should carry the drop out, while a project file states
            // the shape the domain ends up in, which is the same either way
        } else if (ctx.validate_constraint() != null) {
            ParserRuleContext conNameCtx = QNameParser.getFirstNameCtx(
                    getIdentifiers(ctx.validate_constraint().constraint_name));
            PgConstraint constr = getSafe(PgDomain::getConstraint, domain, conNameCtx);
            // the validation is state, not history: NOT VALID is what the model
            // carries and what the DDL writes, so a file that validates a
            // constraint describes one that is not NOT VALID any more. Left
            // unread, a project asking for the validation matched a database
            // that had never performed it and the request never left the file
            doSafe(PgConstraint::setNotValid, constr, false);
        }

        addObjReference(ids, DbObjType.DOMAIN, ACTION_ALTER);
    }

    @Override
    protected String getStmtAction() {
        return getStrForStmtAction(ACTION_ALTER, DbObjType.DOMAIN, getIdentifiers(ctx.name));
    }
}
