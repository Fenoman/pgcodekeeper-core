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

import java.nio.charset.StandardCharsets;
import java.sql.*;

import org.antlr.v4.runtime.CommonTokenStream;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.jdbc.QueryBuilder;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.parser.statement.PgCreateTrigger;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.database.pg.schema.PgTrigger.TgTypes;
import org.pgcodekeeper.core.database.pg.utils.PgDiffUtils;
import org.pgcodekeeper.core.utils.Pair;

/**
 * Reader for PostgreSQL triggers.
 * Loads trigger definitions from pg_trigger system catalog.
 */
public final class PgTriggersReader extends PgAbstractSearchPathJdbcReader {

    // pg_trigger.h
    private static final int TRIGGER_TYPE_ROW = 1 << 0;
    private static final int TRIGGER_TYPE_BEFORE = 1 << 1;
    private static final int TRIGGER_TYPE_INSERT = 1 << 2;
    private static final int TRIGGER_TYPE_DELETE = 1 << 3;
    private static final int TRIGGER_TYPE_UPDATE = 1 << 4;
    private static final int TRIGGER_TYPE_TRUNCATE = 1 << 5;
    private static final int TRIGGER_TYPE_INSTEAD = 1 << 6;

    private static final String NO_PARENT = "0";

    /**
     * Creates a new PgTriggersReader.
     *
     * @param loader the JDBC loader base for database operations
     */
    public PgTriggersReader(PgJdbcLoader loader) {
        super(loader);
    }

    @Override
    protected void processResult(ResultSet res, ISchema schema) throws SQLException {
        String tableName = res.getString("relname");
        IStatementContainer c = schema.getStatementContainer(tableName);
        if (c == null) {
            return;
        }

        String schemaName = schema.getName();
        String triggerName = res.getString("tgname");
        String tgEnabled = res.getString("tgenabled");

        if (c instanceof PgAbstractTable table
                && PgSupportedVersion.VERSION_15.isLE(loader.getVersion())
                && !NO_PARENT.equals(res.getString("tgparentid"))) {
            table.putTriggerState(triggerName, readEnabledState(tgEnabled, true));
            return;
        }

        loader.setCurrentObject(new ObjectReference(schemaName, tableName, triggerName, DbObjType.TRIGGER));
        PgTrigger t = new PgTrigger(triggerName);

        int firingConditions = res.getInt("tgtype");
        if ((firingConditions & TRIGGER_TYPE_DELETE) != 0) {
            t.setOnDelete(true);
        }
        if ((firingConditions & TRIGGER_TYPE_INSERT) != 0) {
            t.setOnInsert(true);
        }
        if ((firingConditions & TRIGGER_TYPE_UPDATE) != 0) {
            t.setOnUpdate(true);
        }
        if ((firingConditions & TRIGGER_TYPE_TRUNCATE) != 0) {
            t.setOnTruncate(true);
        }
        if ((firingConditions & TRIGGER_TYPE_ROW) != 0) {
            t.setForEachRow(true);
        }
        if ((firingConditions & TRIGGER_TYPE_BEFORE) != 0) {
            t.setType(TgTypes.BEFORE);
        } else if ((firingConditions & TRIGGER_TYPE_INSTEAD) != 0) {
            t.setType(TgTypes.INSTEAD_OF);
        } else {
            t.setType(TgTypes.AFTER);
        }

        String funcName = res.getString("proname");
        String funcSchema = res.getString("nspname");

        StringBuilder functionCall = new StringBuilder(funcName.length() + 2);
        functionCall.append(PgDiffUtils.getQuotedName(funcSchema)).append('.')
                .append(PgDiffUtils.getQuotedName(funcName)).append('(');
        t.setTriggerState(readEnabledState(tgEnabled, false));

        byte[] args = res.getBytes("tgargs");
        if (args.length > 0) {
            functionCall.append('\'');
            int start = 0;
            for (int i = 0; i < args.length; ++i) {
                if (args[i] != 0) {
                    continue;
                }

                functionCall.append(new String(args, start, i - start, StandardCharsets.UTF_8));
                if (i != args.length - 1) {
                    functionCall.append("', '");
                }
                start = i + 1;
            }
            functionCall.append('\'');
        }
        functionCall.append(')');
        t.setFunction(functionCall.toString());

        addDep(t, funcSchema, funcName + "()", DbObjType.FUNCTION);

        if (res.getLong("tgconstraint") != 0) {
            t.setConstraint(true);

            String refRelName = res.getString("refrelname");
            if (refRelName != null) {
                String refSchemaName = res.getString("refnspname");
                String sb = PgDiffUtils.getQuotedName(refSchemaName) + '.' +
                        PgDiffUtils.getQuotedName(refRelName);

                t.setRefTableName(sb);
                addDep(t, refSchemaName, refRelName, DbObjType.TABLE);
            }

            // before PostgreSQL 9.5
            if (res.getBoolean("tgdeferrable")) {
                t.setImmediate(!res.getBoolean("tginitdeferred"));
            }
        }

        // after Postgresql 10
        if (PgSupportedVersion.GP_VERSION_7.isLE(loader.getVersion())) {
            t.setOldTable(res.getString("tgoldtable"));
            t.setNewTable(res.getString("tgnewtable"));
        }

        String[] arrCols = PgJdbcUtils.getColArray(res, "cols", true);
        if (arrCols != null) {
            for (String colName : arrCols) {
                t.addUpdateColumn(colName);
                t.addDependency(new ObjectReference(schemaName, tableName, colName, DbObjType.COLUMN));
            }
        }

        // the full CREATE TRIGGER text is consumed only for the WHEN clause;
        // 96%+ of triggers have none, so the definition is fetched and parsed
        // only when pg_trigger.tgqual is present
        if (res.getBoolean("has_when")) {
            String definition = res.getString("definition");
            // has_when comes from the same catalog row, so a null definition
            // here can only mean a concurrently dropped trigger
            IPgJdbcReader.checkObjectValidity(definition, DbObjType.TRIGGER, triggerName);
            // the catalog's own statement is stored here and unconditionally,
            // before the task is submitted: the finalizer below runs only when
            // this task's parse reported no errors (AbstractJdbcLoader:377),
            // while the trigger reaches its container either way. This branch is
            // entered for the WHEN clause and for nothing else, so a failed
            // parse costs the trigger exactly what the parse was run for - and a
            // trigger written out without its WHEN fires on every row instead of
            // the few the condition names. The finalizer drops the statement
            // once the model carries the parsed condition
            t.setCatalogDefinition(getTextWithCheckNewLines(definition));
            loader.submitAntlrTask(definition,
                    p -> new Pair<>(p.sql().statement(0).schema_statement()
                            .schema_create().create_trigger_statement().when_trigger(),
                            (CommonTokenStream) p.getTokenStream()),
                    pair -> {
                        PgCreateTrigger.parseWhen(pair.getFirst(), t, schema.getDatabase(),
                                loader.getCurrentLocation(), pair.getSecond());
                        t.setCatalogDefinition(null);
                    });
        }

        loader.setAuthor(t, res);
        loader.setComment(t, res);
        c.addChild(t);
    }

    private PgTriggerState readEnabledState(String tgEnabled, boolean isChild) {
        return switch (tgEnabled) {
            case "f", "D" -> PgTriggerState.DISABLE;
            case "t", "O" -> isChild ? PgTriggerState.ENABLE : null;
            case "R" -> PgTriggerState.ENABLE_REPLICA;
            case "A" -> PgTriggerState.ENABLE_ALWAYS;
            default -> PgTriggerState.ENABLE;
        };
    }

    @Override
    public String getClassId() {
        return "pg_trigger";
    }

    @Override
    protected String getSchemaColumn() {
        return "cls.relnamespace";
    }

    @Override
    protected void fillQueryBuilder(QueryBuilder builder) {
        addExtensionSchemasCte(builder);
        addDescriptionPart(builder, true);

        QueryBuilder subselect = new QueryBuilder()
                .column("pg_catalog.array_agg(attname ORDER BY attnum)")
                .from("pg_catalog.pg_attribute a")
                .where("a.attrelid = cls.oid")
                .where("a.attnum = ANY(res.tgattr)");

        builder
                .column("cls.relname")
                .column("p.proname")
                .column("nsp.nspname")
                .column("res.tgname")
                .column("res.tgtype")
                .column("res.tgenabled")
                .column("res.tgargs")
                .column("res.tgconstraint::bigint")
                .column("res.tgdeferrable")
                .column("res.tginitdeferred")
                .column("relcon.relname as refrelname")
                .column("refnsp.nspname as refnspname")
                .column("", subselect, "AS cols")
                .column("res.tgqual IS NOT NULL AS has_when")
                .column("CASE WHEN res.tgqual IS NOT NULL"
                        + " THEN pg_catalog.pg_get_triggerdef(res.oid,false) END AS definition")
                .from("pg_catalog.pg_trigger res")
                .join("LEFT JOIN pg_catalog.pg_class cls ON cls.oid = res.tgrelid")
                .join("LEFT JOIN pg_catalog.pg_class relcon ON relcon.oid = res.tgconstrrelid")
                .join("LEFT JOIN pg_catalog.pg_namespace refnsp ON refnsp.oid = relcon.relnamespace")
                .join("JOIN pg_catalog.pg_proc p ON p.oid = res.tgfoid")
                .join("JOIN pg_catalog.pg_namespace nsp ON p.pronamespace = nsp.oid")
                .where("cls.relkind IN ('r', 'f', 'p', 'm', 'v')")
                .where("res.tgisinternal = FALSE");

        if (PgSupportedVersion.GP_VERSION_7.isLE(loader.getVersion())) {
            builder
                    .column("res.tgoldtable")
                    .column("res.tgnewtable");
        }

        if (PgSupportedVersion.VERSION_15.isLE(loader.getVersion())) {
            builder
                    .column("res.tgparentid")
                    .column("res.tgenabled")
                    .join("LEFT JOIN pg_catalog.pg_trigger u ON u.oid = res.tgparentid");
        }
    }

    private void addExtensionSchemasCte(QueryBuilder builder) {
        builder.with(EXTENSIONS_SCHEMAS, EXTENSION_SCHEMA_CTE);
        builder.where(getSchemaColumn() + " NOT IN (SELECT oid FROM extensions_schemas)");
    }
}
