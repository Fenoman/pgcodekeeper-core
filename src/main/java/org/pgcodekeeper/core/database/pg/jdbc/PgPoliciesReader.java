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
package org.pgcodekeeper.core.database.pg.jdbc;

import java.sql.*;

import org.antlr.v4.runtime.CommonTokenStream;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.jdbc.QueryBuilder;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgVexAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.schema.PgPolicy;
import org.pgcodekeeper.core.utils.Pair;

/**
 * Reader for PostgreSQL policies.
 * Loads policy definitions from pg_policy and related system catalogs.
 */
public class PgPoliciesReader extends PgAbstractSearchPathJdbcReader {

    /**
     * Creates a new PgPoliciesReader.
     *
     * @param loader the JDBC loader instance
     */
    public PgPoliciesReader(PgJdbcLoader loader) {
        super(loader);
    }

    @Override
    protected void processResult(ResultSet res, ISchema schema) throws SQLException {
        String tableName = res.getString("relname");
        var c = schema.getStatementContainer(tableName);
        if (c == null) {
            return;
        }

        String schemaName = schema.getName();
        String policyName = res.getString("polname");
        loader.setCurrentObject(new ObjectReference(schemaName, tableName, policyName, DbObjType.POLICY));

        PgPolicy p = new PgPolicy(policyName);

        switch (res.getString("polcmd")) {
            case "r":
                p.setEvent(EventType.SELECT);
                break;
            case "w":
                p.setEvent(EventType.UPDATE);
                break;
            case "a":
                p.setEvent(EventType.INSERT);
                break;
            case "d":
                p.setEvent(EventType.DELETE);
                break;
        }

        String[] roles = PgJdbcUtils.getColArray(res, "polroles", true);
        if (roles != null) {
            for (String role : roles) {
                p.addRole(role);
            }
        }

        if (PgSupportedVersion.GP_VERSION_7.isLE(loader.getVersion())) {
            p.setPermissive(res.getBoolean("polpermissive"));
        }
        IDatabase db = schema.getDatabase();

        String using = res.getString("polqual");
        if (using != null) {
            // pg_get_expr renders an operator expression parenthesized but a
            // bare Var, Const or function call not, while CREATE POLICY ...
            // USING takes a parenthesized expression always - hence the wrap,
            // which the normalized half below repeats so that it stays the
            // normalization of the text actually stored here
            //
            // the catalog's own text is stored unconditionally, before the task
            // is submitted: the finalizer runs only when this task's parse
            // reported no errors (AbstractJdbcLoader:377), and a filter this
            // grammar cannot read must still reach the model - without it the
            // policy would be written out with no USING at all, dropping the row
            // restriction
            //
            // both halves get it, and the normalized one must not be left empty:
            // compare and computeHash read only that half, so an unreadable
            // filter would otherwise compare equal to no filter at all and the
            // difference would vanish from the tree and from the script. On a
            // successful parse the finalizer overwrites it with the real
            // normalization
            String wrappedUsing = '(' + using + ')';
            p.setUsing(wrappedUsing, wrappedUsing);
            loader.submitAntlrTask(using,
                    parser -> new Pair<>(parser.vex_eof().vex().get(0), (CommonTokenStream) parser.getTokenStream()),
                    pair -> {
                        var vex = pair.getFirst();
                        p.setUsing(wrappedUsing,
                                '(' + PgParserUtils.normalizeWhitespaceUnquoted(vex, pair.getSecond()) + ')');
                        db.addAnalysisLauncher(new PgVexAnalysisLauncher(p, vex, loader.getCurrentLocation()));
                    });
        }

        String check = res.getString("polwithcheck");
        if (check != null) {
            String wrappedCheck = '(' + check + ')';
            p.setCheck(wrappedCheck, wrappedCheck);
            loader.submitAntlrTask(check,
                    parser -> new Pair<>(parser.vex_eof().vex().get(0), (CommonTokenStream) parser.getTokenStream()),
                    pair -> {
                        var vex = pair.getFirst();
                        p.setCheck(wrappedCheck,
                                '(' + PgParserUtils.normalizeWhitespaceUnquoted(vex, pair.getSecond()) + ')');
                        db.addAnalysisLauncher(new PgVexAnalysisLauncher(p, vex, loader.getCurrentLocation()));
                    });
        }

        loader.setAuthor(p, res);
        loader.setComment(p, res);

        c.addChild(p);
    }

    @Override
    public String getClassId() {
        return "pg_policy";
    }

    @Override
    protected String getSchemaColumn() {
        return "c.relnamespace";
    }

    @Override
    protected void fillQueryBuilder(QueryBuilder builder) {
        addExtensionSchemasCte(builder);
        addDescriptionPart(builder);

        builder
                .column("res.polname")
                .column("c.relname")
                .column("res.polcmd")
                .column("ARRAY(SELECT pg_catalog.quote_ident(rolname) FROM pg_catalog.pg_roles WHERE oid = ANY(res.polroles)) AS polroles")
                .column("pg_catalog.pg_get_expr(res.polqual, res.polrelid) AS polqual")
                .column("pg_catalog.pg_get_expr(res.polwithcheck, res.polrelid) AS polwithcheck")
                .from("pg_catalog.pg_policy res")
                .join("JOIN pg_catalog.pg_class c ON c.oid = res.polrelid");

        if (PgSupportedVersion.GP_VERSION_7.isLE(loader.getVersion())) {
            builder.column("res.polpermissive");
        }
    }

    private void addExtensionSchemasCte(QueryBuilder builder) {
        builder.with(EXTENSIONS_SCHEMAS, EXTENSION_SCHEMA_CTE);
        builder.where(getSchemaColumn() + " NOT IN (SELECT oid FROM extensions_schemas)");
    }
}
