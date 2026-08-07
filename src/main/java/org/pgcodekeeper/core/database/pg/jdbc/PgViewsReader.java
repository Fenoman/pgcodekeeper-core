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
import org.pgcodekeeper.core.database.pg.parser.launcher.*;
import org.pgcodekeeper.core.database.pg.parser.statement.PgParserAbstract;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.utils.Pair;
import org.pgcodekeeper.core.utils.Utils;

/**
 * Reader for PostgreSQL views and materialized views.
 * Loads view definitions from pg_class system catalog.
 */
public final class PgViewsReader extends PgAbstractSearchPathJdbcReader {

    private final boolean includePrivileges;

    /**
     * Constructs a new PgViewsReader.
     *
     * @param loader the JDBC loader base instance
     */
    public PgViewsReader(PgJdbcLoader loader) {
        super(loader);
        includePrivileges = !loader.getSettings().isIgnorePrivileges();
    }

    @Override
    protected void processResult(ResultSet res, ISchema schema) throws SQLException {
        String schemaName = schema.getName();
        String viewName = res.getString("relname");
        loader.setCurrentObject(new ObjectReference(schemaName, viewName, DbObjType.VIEW));

        PgAbstractView v;

        // materialized view
        if ("m".equals(res.getString("kind"))) {
            var matV = new PgMaterializedView(viewName);
            matV.setIsWithData(res.getBoolean("relispopulated"));
            String tableSpace = res.getString("table_space");
            if (tableSpace != null && !tableSpace.isEmpty()) {
                matV.setTablespace(tableSpace);
            }
            if (PgSupportedVersion.GP_VERSION_7.isLE(loader.getVersion())) {
                matV.setMethod(res.getString("access_method"));
            }
            if (loader.isGreenplumDb()) {
                String distribution = res.getString("distribution");
                if (distribution != null && !distribution.isBlank()) {
                    matV.setDistribution(distribution);
                }
            }
            v = matV;
        } else {
            v = new PgView(viewName);
        }

        String definition = res.getString("definition");
        IPgJdbcReader.checkObjectValidity(definition, DbObjType.VIEW, viewName);
        String viewDef = definition.trim();
        int semicolonPos = viewDef.length() - 1;
        String query = viewDef.charAt(semicolonPos) == ';' ? viewDef.substring(0, semicolonPos) : viewDef;

        IDatabase dataBase = schema.getDatabase();

        // the catalog's own query is stored here and unconditionally, before the
        // task is submitted: the finalizer below runs only when this task's
        // parse reported no errors (AbstractJdbcLoader:377), while the view
        // reaches its schema either way. Without it neither half is ever
        // written, and a view is its query: PgAbstractView.getCreationSQL sizes
        // its builder from query.length() before writing a character, so an
        // unreadable query used to end the whole load in a NullPointerException
        // rather than in a view
        //
        // both halves get it, and the normalized one must not be left empty:
        // compare, computeHash and needDrop read only that half, so an
        // unreadable query would otherwise compare equal to any other unreadable
        // one and the difference would vanish from the tree and from the script.
        // On a successful parse the finalizer overwrites it with the real
        // normalization
        v.setQuery(query, query);
        loader.submitAntlrTask(viewDef,
                p -> new Pair<>(
                        p.sql().statement(0).data_statement().select_stmt(),
                        (CommonTokenStream) p.getTokenStream()),
                pair -> {
                    dataBase.addAnalysisLauncher(new PgViewAnalysisLauncher(
                            v, pair.getFirst(), loader.getCurrentLocation()));
                    v.setQuery(query, PgParserUtils.normalizeViewQueryForComparison(
                            pair.getFirst(), pair.getSecond()));
                });


        // Query columns default values and comments
        String[] colNames = PgJdbcUtils.getColArray(res, "column_names", true);
        if (colNames != null) {
            String[] colComments = PgJdbcUtils.getColArray(res, "column_comments");
            String[] colDefaults = PgJdbcUtils.getColArray(res, "column_defaults");
            String[] colTypes = PgJdbcUtils.getColArray(res, "column_types");
            String[] colACLs = includePrivileges
                    ? PgJdbcUtils.getColArray(res, "column_acl") : null;

            for (int i = 0; i < colNames.length; i++) {
                String colName = colNames[i];
                v.addRelationColumn(colName, colTypes[i]);
                String colDefault = colDefaults[i];
                if (colDefault != null) {
                    ((PgView) v).addColumnDefaultValue(colName, colDefault);
                    loader.submitAntlrTask(colDefault, p -> p.vex_eof().vex().get(0),
                            ctx -> dataBase.addAnalysisLauncher(
                                    new PgVexAnalysisLauncher(v, ctx, loader.getCurrentLocation())));
                }
                String colComment = colComments[i];
                if (colComment != null) {
                    v.addColumnComment(colName, getTextWithCheckNewLines(Utils.quoteString(colComment)));
                }
                if (includePrivileges) {
                    String colAcl = colACLs[i];
                    // Привилегии на столбцы view записываются в саму view
                    if (colAcl != null) {
                        loader.setPrivileges(v, colAcl, colName, schemaName);
                    }
                }
            }
        }

        if (includePrivileges) {
            loader.setOwner(v, res.getLong("relowner"));
            loader.setPrivileges(v, res.getString("relacl"), schemaName);
        }
        loader.setAuthor(v, res);
        loader.setComment(v, res);

        // STORAGE PARAMETRS
        String[] options = PgJdbcUtils.getColArray(res, "reloptions", true);
        if (options != null) {
            PgParserAbstract.fillOptionParams(options, v::addOption, false, false, false);
        }

        schema.addChild(v);
    }

    @Override
    public String getClassId() {
        return "pg_class";
    }

    @Override
    protected String getSchemaColumn() {
        return "res.relnamespace";
    }

    @Override
    protected void fillQueryBuilder(QueryBuilder builder) {
        addExtensionDepsCte(builder);
        addColumnsPart(builder);
        addDescriptionPart(builder, true);

        builder
                .column("res.relname")
                .column("res.relkind AS kind")
                .column("tabsp.spcname as table_space");
        if (includePrivileges) {
            builder
                    .column("res.relacl::text")
                    .column("res.relowner::bigint");
        }
        builder
                .column("pg_catalog.pg_get_viewdef(res.oid, "
                        + loader.getSettings().isSimplifyView()
                        + ") AS definition")
                .column("res.reloptions")
                .column("am.amname AS access_method")
                .column("res.relispopulated")
                .from("pg_catalog.pg_class res")
                .join("LEFT JOIN pg_catalog.pg_tablespace tabsp ON tabsp.oid = res.reltablespace")
                .join("LEFT JOIN pg_catalog.pg_am am ON am.oid = res.relam")
                .where("res.relkind IN ('v','m')")
                .orderBy("res.oid");

        if (loader.isGreenplumDb()) {
            builder.column("pg_get_table_distributedby(res.oid) AS distribution");
        }
    }

    private void addColumnsPart(QueryBuilder builder) {
        QueryBuilder targetViews = new QueryBuilder()
                .column("target.oid")
                .from("pg_catalog.pg_class target")
                .where("target.relkind IN ('v','m')")
                .where("target.relnamespace IN (" + loader.getSchemas() + ')');
        builder.with("target_views", targetViews);

        QueryBuilder columns = new QueryBuilder()
                .column("attrelid")
                .column("pg_catalog.array_agg(attr.attname ORDER BY attr.attnum) AS column_names")
                .column("pg_catalog.array_agg(des.description ORDER BY attr.attnum) AS column_comments")
                .column("pg_catalog.array_agg(pg_catalog.pg_get_expr(def.adbin, def.adrelid) ORDER BY attr.attnum) AS column_defaults")
                .column("pg_catalog.array_agg(pg_catalog.format_type(attr.atttypid, attr.atttypmod) ORDER BY attr.attnum) AS column_types");
        if (includePrivileges) {
            columns.column("pg_catalog.array_agg(attr.attacl::text ORDER BY attr.attnum) AS column_acl");
        }
        columns
                .from("pg_catalog.pg_attribute attr")
                .join("JOIN target_views target ON target.oid = attr.attrelid")
                .join("LEFT JOIN pg_catalog.pg_attrdef def ON def.adnum = attr.attnum")
                .join("  AND attr.attrelid = def.adrelid")
                .join("  AND attr.attisdropped IS FALSE")
                .join("LEFT JOIN pg_catalog.pg_description des ON des.objoid = attr.attrelid")
                .join("  AND des.classoid = 'pg_catalog.pg_class'::pg_catalog.regclass")
                .join("  AND des.objsubid = attr.attnum")
                .groupBy("attrelid");

        builder.column("subselect.column_names");
        builder.column("subselect.column_comments");
        builder.column("subselect.column_defaults");
        builder.column("subselect.column_types");
        if (includePrivileges) {
            builder.column("subselect.column_acl");
        }
        builder.join("LEFT JOIN", columns, "subselect ON subselect.attrelid = res.oid");
    }
}
