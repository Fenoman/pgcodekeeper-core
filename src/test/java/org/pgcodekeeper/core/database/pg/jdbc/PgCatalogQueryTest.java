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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ISchema;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.database.pg.schema.PgView;
import org.pgcodekeeper.core.exception.ConcurrentModificationException;
import org.pgcodekeeper.core.utils.Pair;
import org.pgcodekeeper.core.database.base.jdbc.QueryBuilder;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.routine.PgJdbcRoutineBodyCatalogMode;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.ISettings;

class PgCatalogQueryTest {

    /**
     * The query body of the view fixture below, without the terminating
     * semicolon {@code pg_get_viewdef()} appends and {@code PgViewsReader}
     * strips again.
     */
    private static final String VIEW_DEFINITION = "SELECT 1 AS c1";

    @Test
    void fullBodyFunctionQueryMatchesExactVersion16Golden() throws IOException {
        String actual = new PgFunctionsReader(queryLoader(
                "11, 22", false, true, PgSupportedVersion.VERSION_16))
                .makeQuery().build();
        // Keep trailing whitespace significant without storing it invisibly in
        // the repository resource (which would fail git diff --check).
        actual = actual.replace(" \n", "<TRAILING_SPACE>\n");
        String stored = TestUtils.readResource(
                "pg_16_functions_full_body_query.sql", getClass());

        assertAll(
                () -> assertTrue(stored.endsWith("\n"),
                        "query golden must have one repository LF terminator"),
                () -> assertTrue(!stored.endsWith("\n\n"),
                        "query golden must have one repository LF terminator"));
        assertEquals(stored.substring(0, stored.length() - 1), actual);
    }

    @Test
    void fingerprintFunctionQueryProjectsOnlyBoundedBodyMetadata() throws IOException {
        String sql = new PgFunctionsReader(queryLoader(
                "11, 22", false, true, PgSupportedVersion.VERSION_16),
                PgJdbcRoutineBodyCatalogMode.FINGERPRINT_UTF8)
                .makeQuery().build();

        String eligible = "res.prokind <> 'a'\n"
                + "    AND l.lanname IN ('sql', 'plpgsql')\n"
                + "    AND (res.probin IS NULL OR res.probin = '')\n"
                + "    AND res.prosrc IS NOT NULL\n"
                + "    AND res.prosrc <> ''\n"
                + "    AND res.prosrc <> '-'";
        String prosrcProjection = "  CASE WHEN " + eligible
                + " THEN NULL::text ELSE res.prosrc END AS full_body_prosrc,\n"
                + "  CASE WHEN " + eligible
                + " THEN res.oid::bigint ELSE NULL::bigint END AS body_oid,\n"
                + "  CASE WHEN " + eligible + "\n"
                + "  THEN pg_catalog.octet_length("
                        + "pg_catalog.replace(res.prosrc, E'\\r', ''))::bigint\n"
                + "  ELSE NULL::bigint\n"
                + "END AS body_utf8_length,\n"
                + "  CASE WHEN " + eligible + "\n"
                + "  THEN pg_catalog.sha256(pg_catalog.convert_to("
                + "pg_catalog.replace(res.prosrc, E'\\r', ''), 'UTF8'))\n"
                + "  ELSE NULL::bytea\n"
                + "END AS body_sha256,\n";
        String probinProjection = "  CASE WHEN " + eligible
                + " THEN NULL::text ELSE res.probin END AS probin,\n";
        String prosqlbodyProjection = "  CASE\n"
                + "  WHEN " + eligible + " THEN NULL::text\n"
                + "  WHEN (res.prosrc IS NULL OR res.prosrc = '') AND l.lanname = 'sql'\n"
                + "    THEN pg_catalog.pg_get_function_sqlbody(res.oid)\n"
                + "  ELSE NULL::text\n"
                + "END AS prosqlbody,\n";
        String fullGolden = TestUtils.readResource(
                "pg_16_functions_full_body_query.sql", getClass());
        String expected = fullGolden.substring(0, fullGolden.length() - 1)
                .replace("  res.prosrc,\n", prosrcProjection)
                .replace("  res.probin,\n", probinProjection)
                .replace("  case when (res.prosrc is null or res.prosrc='') and l.lanname = 'sql'\n"
                        + "    then pg_get_function_sqlbody(res.oid) end as prosqlbody,\n",
                        prosqlbodyProjection);
        String normalizedActual = sql.replace(" \n", "<TRAILING_SPACE>\n");
        assertAll(
                () -> assertEquals(expected, normalizedActual),
                () -> assertTrue(sql.contains("CASE WHEN " + eligible
                        + " THEN NULL::text ELSE res.prosrc END AS full_body_prosrc"), sql),
                () -> assertTrue(sql.contains("CASE WHEN " + eligible
                        + " THEN res.oid::bigint ELSE NULL::bigint END AS body_oid"), sql),
                () -> assertTrue(sql.contains("pg_catalog.sha256("
                        + "pg_catalog.convert_to("
                        + "pg_catalog.replace(res.prosrc, E'\\r', ''), 'UTF8'))"), sql),
                () -> assertTrue(sql.contains("ELSE NULL::bytea\nEND AS body_sha256"), sql),
                () -> assertTrue(sql.contains("pg_catalog.octet_length("
                        + "pg_catalog.replace(res.prosrc, E'\\r', ''))::bigint"), sql),
                () -> assertTrue(sql.contains("ELSE NULL::bigint\nEND AS body_utf8_length"), sql),
                () -> assertTrue(sql.contains("THEN NULL::text ELSE res.probin END AS probin"), sql),
                () -> assertTrue(sql.contains("THEN NULL::text\n"
                        + "  WHEN (res.prosrc IS NULL OR res.prosrc = '') AND l.lanname = 'sql'\n"
                        + "    THEN pg_catalog.pg_get_function_sqlbody(res.oid)\n"
                        + "  ELSE NULL::text\nEND AS prosqlbody"), sql),
                () -> assertEquals(0, countOccurrences(sql, "\n  res.prosrc,"), sql),
                () -> assertEquals(0, countOccurrences(sql, "\n  res.probin,"), sql),
                () -> assertEquals(0, countOccurrences(sql,
                        "then pg_get_function_sqlbody(res.oid) end as prosqlbody"), sql),
                () -> assertTrue(sql.endsWith("\nORDER BY res.oid"), sql));
    }

    @Test
    void convertedFingerprintLengthRemainsExactForNonUtf8Databases() {
        String sql = new PgFunctionsReader(queryLoader(
                "11, 22", false, true, PgSupportedVersion.VERSION_16),
                PgJdbcRoutineBodyCatalogMode.FINGERPRINT_CONVERTED)
                .makeQuery().build();

        assertAll(
                () -> assertTrue(sql.contains("pg_catalog.octet_length("
                        + "pg_catalog.convert_to("
                        + "pg_catalog.replace(res.prosrc, E'\\r', ''), 'UTF8'))::bigint"), sql),
                () -> assertEquals(0, countOccurrences(sql,
                        "pg_catalog.octet_length(res.prosrc)::bigint"), sql),
                () -> assertTrue(sql.contains("ELSE NULL::bytea"), sql),
                () -> assertTrue(sql.contains("ELSE NULL::bigint"), sql));
    }

    @Test
    void keepNewlinesFingerprintHashesExactProsrcBytes() {
        PgJdbcLoader loader = queryLoader(
                "11, 22", false, true, PgSupportedVersion.VERSION_16);
        when(loader.getSettings().isKeepNewlines()).thenReturn(true);
        String sql = new PgFunctionsReader(loader,
                PgJdbcRoutineBodyCatalogMode.FINGERPRINT_UTF8)
                .makeQuery().build();

        assertAll(
                () -> assertTrue(sql.contains("pg_catalog.sha256("
                        + "pg_catalog.convert_to(res.prosrc, 'UTF8'))"), sql),
                () -> assertTrue(sql.contains(
                        "pg_catalog.octet_length(res.prosrc)::bigint"), sql),
                () -> assertEquals(0, countOccurrences(sql,
                        "pg_catalog.replace(res.prosrc"), sql));
    }

    @Test
    void functionCatalogRowsAreOrderedByOid() {
        String sql = new PgFunctionsReader(queryLoader(
                "11, 22", false, true, PgSupportedVersion.VERSION_16))
                .makeQuery().build();

        assertTrue(sql.endsWith("\nORDER BY res.oid"), sql);
    }

    @Test
    void postgresFunctionQueryExcludesAggregateJoinsAndRows() {
        String sql = new PgFunctionsReader(queryLoader("11, 22")).makeQuery().build();

        assertAll(
                () -> assertTrue(sql.contains("res.prokind <> 'a'"), sql),
                () -> assertEquals(0, countOccurrences(sql, "pg_catalog.pg_aggregate"), sql),
                () -> assertEquals(0, countOccurrences(sql, "sfunc"), sql),
                () -> assertEquals(0, countOccurrences(sql, "sortop"), sql),
                () -> assertEquals(0, countOccurrences(sql, "finalfunc_modify"), sql),
                () -> assertEquals(1, countOccurrences(sql, "pg_catalog.pg_proc res"), sql),
                () -> assertTrue(sql.contains("res.prokind = 'a' AS proisagg"), sql),
                () -> assertTrue(sql.contains("res.pronamespace IN (11, 22)"), sql),
                () -> assertTrue(sql.endsWith("\nORDER BY res.oid"), sql));
    }

    @Test
    void aggregateQueryCarriesAggregateJoinsForSchemaScopedAggregatesOnly() {
        String sql = new PgAggregatesReader(queryLoader("11, 22")).makeQuery().build();

        String functionsCte = "extension_deps AS (\n"
                + "  SELECT\n"
                + "    objid\n"
                + "  FROM pg_catalog.pg_depend\n"
                + "  WHERE deptype IN ('e', 'i')\n"
                + "    AND classid = 'pg_catalog.pg_proc'::pg_catalog.regclass\n"
                + ")";

        assertAll(
                () -> assertTrue(sql.contains(
                        "JOIN pg_catalog.pg_aggregate a ON a.aggfnoid = res.oid"), sql),
                () -> assertEquals(0, countOccurrences(sql,
                        "LEFT JOIN pg_catalog.pg_aggregate"), sql),
                () -> assertTrue(sql.contains("res.prokind = 'a'"), sql),
                () -> assertTrue(sql.contains("TRUE AS proisagg"), sql),
                () -> assertTrue(sql.contains(functionsCte), sql),
                () -> assertTrue(sql.contains(
                        "res.oid NOT IN (SELECT objid FROM extension_deps)"), sql),
                () -> assertTrue(sql.contains("a.aggkind"), sql),
                () -> assertTrue(sql.contains("a.aggfinalmodify AS finalfunc_modify"), sql),
                () -> assertTrue(sql.contains("a.aggmfinalmodify AS mfinalfunc_modify"), sql),
                () -> assertTrue(sql.contains("d.description"), sql),
                () -> assertTrue(sql.contains("res.proowner::bigint"), sql),
                () -> assertTrue(sql.contains("res.proacl::text AS aclarray"), sql),
                () -> assertTrue(sql.contains("res.pronamespace IN (11, 22)"), sql),
                () -> assertTrue(sql.endsWith("\nORDER BY res.oid"), sql),
                () -> assertEquals(0, countOccurrences(sql, "res.prosrc"), sql),
                () -> assertEquals(0, countOccurrences(sql, "lang_name"), sql));
    }

    @Test
    void ignoredPrivilegesAreNotProjectedByAggregateQuery() {
        String sql = new PgAggregatesReader(queryLoader("11, 22", false, true))
                .makeQuery().build();

        assertAll(
                () -> assertEquals(0, countOccurrences(sql, "res.proowner"), sql),
                () -> assertEquals(0, countOccurrences(sql, "res.proacl"), sql),
                () -> assertTrue(sql.contains("a.aggkind"), sql));
    }

    @Test
    void greenplumFunctionQueryKeepsCombinedAggregateProjection() {
        String gp6 = new PgFunctionsReader(
                queryLoader("11, 22", true, false, PgSupportedVersion.GP_VERSION_6))
                .makeQuery().build();
        String gp7 = new PgFunctionsReader(
                queryLoader("11, 22", true, false, PgSupportedVersion.GP_VERSION_7))
                .makeQuery().build();

        assertAll(
                () -> assertTrue(gp6.contains(
                        "LEFT JOIN pg_catalog.pg_aggregate a ON a.aggfnoid = res.oid"), gp6),
                () -> assertTrue(gp6.contains("res.proisagg"), gp6),
                () -> assertTrue(gp6.contains("sfunc.proname AS sfunc"), gp6),
                () -> assertEquals(0, countOccurrences(gp6, "prokind"), gp6),
                () -> assertEquals(0, countOccurrences(gp6, "finalfunc_modify"), gp6),
                () -> assertTrue(gp7.contains(
                        "LEFT JOIN pg_catalog.pg_aggregate a ON a.aggfnoid = res.oid"), gp7),
                () -> assertTrue(gp7.contains("res.prokind = 'a' AS proisagg"), gp7),
                () -> assertTrue(gp7.contains("a.aggfinalmodify AS finalfunc_modify"), gp7),
                () -> assertTrue(gp7.contains("a.aggmfinalmodify AS mfinalfunc_modify"), gp7),
                () -> assertEquals(0, countOccurrences(gp7, "res.prokind <> 'a'"), gp7),
                () -> assertEquals(0, countOccurrences(gp6, "res.prokind <> 'a'"), gp6),
                () -> assertInOrder(gp7, "sfunc.proname AS sfunc", "deserialfn_n.nspname AS deserialfunc_nsp",
                        "res.protrftypes::bigint[]", "res.prokind = 'p' AS proisproc",
                        "a.aggfinalmodify AS finalfunc_modify", "res.proexeclocation AS executeOn"));
    }

    @Test
    void viewColumnAggregateJoinsOnlySchemaScopedTargetViewRelations() {
        QueryBuilder query = new PgViewsReader(queryLoader("11, 22")).makeQuery();
        assertNotNull(query);
        String sql = query.build();

        String targetViewsCte = "target_views AS (\n"
                + "  SELECT\n"
                + "    target.oid\n"
                + "  FROM pg_catalog.pg_class target\n"
                + "  WHERE target.relkind IN ('v','m')\n"
                + "    AND target.relnamespace IN (11, 22)\n"
                + ")";
        String attrDefaultJoin = "LEFT JOIN pg_catalog.pg_attrdef def ON def.adnum = attr.attnum\n"
                + "    AND attr.attrelid = def.adrelid\n"
                + "    AND attr.attisdropped IS FALSE";

        assertAll(
                () -> assertTrue(sql.contains(targetViewsCte), sql),
                () -> assertTrue(sql.contains(
                        "JOIN target_views target ON target.oid = attr.attrelid"), sql),
                () -> assertTrue(sql.contains("res.relnamespace IN (11, 22)"), sql),
                () -> assertTrue(sql.endsWith("\nORDER BY res.oid"), sql),
                () -> assertEquals(5, countOccurrences(sql, "ORDER BY attr.attnum"), sql),
                () -> assertTrue(sql.contains("pg_catalog.array_agg(pg_catalog.format_type("
                        + "attr.atttypid, attr.atttypmod) ORDER BY attr.attnum) AS column_types"), sql),
                () -> assertTrue(sql.contains("subselect.column_types"), sql),
                () -> assertTrue(sql.contains(attrDefaultJoin), sql));
    }

    @Test
    void viewCatalogColumnsFeedRelationColumnsWithoutAffectingComparison() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true);
        ISchema schema = mock(ISchema.class);
        IDatabase database = mock(IDatabase.class);
        ResultSet result = mock(ResultSet.class);
        when(schema.getName()).thenReturn("app");
        when(schema.getDatabase()).thenReturn(database);
        java.sql.Array names = textArray("c1", "c2");
        java.sql.Array comments = textArray(null, null);
        java.sql.Array defaults = textArray(null, null);
        java.sql.Array types = textArray("integer", "character varying(20)");
        when(result.getString("relname")).thenReturn("v1");
        when(result.getString("kind")).thenReturn("v");
        when(result.getString("definition")).thenReturn(VIEW_DEFINITION + ';');
        when(result.getArray("column_names")).thenReturn(names);
        when(result.getArray("column_comments")).thenReturn(comments);
        when(result.getArray("column_defaults")).thenReturn(defaults);
        when(result.getArray("column_types")).thenReturn(types);
        when(result.getArray("reloptions")).thenReturn(null);

        new PgViewsReader(loader).processResult(result, schema);

        ArgumentCaptor<IStatement> captor = ArgumentCaptor.forClass(IStatement.class);
        verify(schema).addChild(captor.capture());
        PgView view = (PgView) captor.getValue();
        List<Pair<String, String>> columns = view.getRelationColumns().toList();
        // a project-side twin never carries catalog columns
        PgView projectTwin = new PgView("v1");
        // but it does carry a query, and so does the view above: the loader here
        // is a mock, so the parse finalizer that normally writes the normalized
        // half never runs, and PgViewsReader writes the raw definition into both
        // halves before submitting the task. The twin is given the same text
        // because the subject of this test is the catalog columns, and a query
        // that differs between the two sides would decide the comparison before
        // the columns ever got a say - which is exactly what it did while both
        // sides happened to hold none
        projectTwin.setQuery(VIEW_DEFINITION, VIEW_DEFINITION);

        assertAll(
                () -> assertEquals(List.of(new Pair<>("c1", "integer"),
                        new Pair<>("c2", "character varying(20)")), columns),
                // catalog columns are display metadata: they must not change
                // project-versus-database comparison or hashing
                () -> assertTrue(view.compare(projectTwin)),
                () -> assertEquals(view.hashCode(), projectTwin.hashCode()));
    }

    private static java.sql.Array textArray(String... values) throws SQLException {
        java.sql.Array array = mock(java.sql.Array.class);
        when(array.getArray()).thenReturn(values);
        return array;
    }

    @Test
    void triggerDefinitionIsFetchedOnlyForTriggersWithWhenClause() {
        String sql = new PgTriggersReader(queryLoader("11, 22")).makeQuery().build();

        assertAll(
                () -> assertTrue(sql.contains("res.tgqual IS NOT NULL AS has_when"), sql),
                () -> assertTrue(sql.contains("CASE WHEN res.tgqual IS NOT NULL"
                        + " THEN pg_catalog.pg_get_triggerdef(res.oid,false) END AS definition"), sql),
                () -> assertEquals(0, countOccurrences(sql,
                        "pg_catalog.pg_get_triggerdef(res.oid,false) AS definition"), sql));
    }

    @Test
    void triggerWithoutWhenClauseSkipsDefinitionAndAntlrParse() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true,
                PgSupportedVersion.VERSION_16);
        ISchema schema = mock(ISchema.class);
        ResultSet result = triggerRow(schema, false, null);

        new PgTriggersReader(loader).processResult(result, schema);

        assertAll(
                () -> verify(result, never()).getString("definition"),
                () -> verify(loader, never()).submitAntlrTask(anyString(), any(), any()));
    }

    @Test
    void triggerWithWhenClauseStillParsesItsDefinition() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true,
                PgSupportedVersion.VERSION_16);
        ISchema schema = mock(ISchema.class);
        ResultSet result = triggerRow(schema, true,
                "CREATE TRIGGER trg1 BEFORE INSERT ON app.t1 FOR EACH ROW"
                        + " WHEN (NEW.c1 > 0) EXECUTE FUNCTION app.fn_a();");

        new PgTriggersReader(loader).processResult(result, schema);

        verify(loader).submitAntlrTask(anyString(), any(), any());
    }

    @Test
    void concurrentlyDroppedTriggerWithWhenClauseIsStillDetected() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true,
                PgSupportedVersion.VERSION_16);
        ISchema schema = mock(ISchema.class);
        ResultSet result = triggerRow(schema, true, null);

        assertThrows(ConcurrentModificationException.class,
                () -> new PgTriggersReader(loader).processResult(result, schema));
    }

    private static ResultSet triggerRow(ISchema schema, boolean hasWhen, String definition)
            throws SQLException {
        PgSimpleTable table = new PgSimpleTable("t1");
        when(schema.getName()).thenReturn("app");
        when(schema.getStatementContainer("t1")).thenReturn(table);

        ResultSet result = mock(ResultSet.class);
        when(result.getString("relname")).thenReturn("t1");
        when(result.getString("tgparentid")).thenReturn("0");
        when(result.getString("tgname")).thenReturn("trg1");
        when(result.getString("tgenabled")).thenReturn("O");
        when(result.getInt("tgtype")).thenReturn(7);
        when(result.getString("proname")).thenReturn("fn_a");
        when(result.getString("nspname")).thenReturn("app");
        when(result.getBytes("tgargs")).thenReturn(new byte[0]);
        when(result.getLong("tgconstraint")).thenReturn(0L);
        when(result.getBoolean("has_when")).thenReturn(hasWhen);
        when(result.getString("definition")).thenReturn(definition);
        return result;
    }

    @Test
    void tableAggregateSubqueriesJoinOnlySchemaScopedTargetTableRelations() {
        QueryBuilder query = new PgTablesReader(queryLoader("11, 22")).makeQuery();
        assertNotNull(query);
        String sql = query.build();

        String targetTablesCte = "target_tables AS (\n"
                + "  SELECT\n"
                + "    target.oid\n"
                + "  FROM pg_catalog.pg_class target\n"
                + "  WHERE target.relkind IN ('f','r','p')\n"
                + "    AND target.relnamespace IN (11, 22)\n"
                + ")";

        assertAll(
                () -> assertTrue(sql.contains(targetTablesCte), sql),
                () -> assertTrue(sql.contains(
                        "JOIN target_tables cc ON cc.oid = a.attrelid"), sql),
                () -> assertTrue(sql.contains(
                        "JOIN target_tables tt ON tt.oid = i.inhrelid"), sql),
                () -> assertTrue(sql.contains(
                        "JOIN target_tables tt ON tt.oid = inh.inhrelid"), sql),
                () -> assertEquals(0, countOccurrences(sql, "JOIN pg_catalog.pg_class cc"), sql),
                () -> assertEquals(1, countOccurrences(sql, "pg_catalog.pg_class target"), sql),
                () -> assertTrue(sql.contains("res.relnamespace IN (11, 22)"), sql),
                () -> assertTrue(sql.contains("res.relkind IN ('f','r','p')"), sql),
                () -> assertInOrder(sql, "target_tables AS (", "inherits AS (",
                        "FROM pg_catalog.pg_class res"));
    }

    @Test
    void greenplumTableAggregateSubqueriesKeepSchemaScopedTargetTableRelations() {
        QueryBuilder query = new PgTablesReader(
                queryLoader("11, 22", true, false, PgSupportedVersion.GP_VERSION_6)).makeQuery();
        assertNotNull(query);
        String sql = query.build();

        assertAll(
                () -> assertTrue(sql.contains(
                        "JOIN target_tables cc ON cc.oid = a.attrelid"), sql),
                () -> assertTrue(sql.contains(
                        "JOIN target_tables tt ON tt.oid = i.inhrelid"), sql),
                () -> assertTrue(sql.contains(
                        "JOIN target_tables tt ON tt.oid = inh.inhrelid"), sql),
                () -> assertEquals(0, countOccurrences(sql, "JOIN pg_catalog.pg_class cc"), sql),
                () -> assertTrue(sql.contains("target.relnamespace IN (11, 22)"), sql));
    }

    @Test
    void tableColumnPayloadUsesSnapshotCachesAcrossSupportedServerBranches() {
        for (PgSupportedVersion version : PgSupportedVersion.values()) {
            boolean greenplum = version == PgSupportedVersion.GP_VERSION_6
                    || version == PgSupportedVersion.GP_VERSION_7;
            String sql = new PgTablesReader(queryLoader(
                    "11, 22", greenplum, true, version)).makeQuery().build();
            int expectedAttributeOrderings = switch (version) {
                case GP_VERSION_6, VERSION_18 -> 14;
                default -> 15;
            };
            String message = version + ":\n" + sql;

            assertAll(
                    () -> assertEquals(0, countOccurrences(sql, "col_default_storages"), message),
                    () -> assertEquals(0, countOccurrences(sql, "col_typcollation"), message),
                    () -> assertEquals(0, countOccurrences(sql, "col_collationname"), message),
                    () -> assertEquals(0, countOccurrences(sql, "col_collationnspname"), message),
                    () -> assertEquals(0, countOccurrences(sql, "nspnames AS"), message),
                    () -> assertEquals(0, countOccurrences(sql, "collations AS"), message),
                    () -> assertEquals(0, countOccurrences(sql,
                            "LEFT JOIN pg_catalog.pg_type t"), message),
                    () -> assertEquals(0, countOccurrences(sql,
                            "LEFT JOIN collations cl"), message),
                    () -> assertTrue(sql.contains("pg_catalog.array_agg(a.attstorage "
                            + "ORDER BY a.attnum) AS col_storages"), message),
                    () -> assertTrue(sql.contains("pg_catalog.array_agg(a.atttypid::bigint "
                            + "ORDER BY a.attnum) AS col_type_ids"), message),
                    () -> assertTrue(sql.contains("pg_catalog.array_agg(pg_catalog.format_type("
                            + "a.atttypid, a.atttypmod) ORDER BY a.attnum) AS col_type_name"),
                            message),
                    () -> assertTrue(sql.contains("pg_catalog.array_agg(a.attcollation::bigint "
                            + "ORDER BY a.attnum) AS col_collation"), message),
                    () -> assertEquals(expectedAttributeOrderings,
                            countOccurrences(sql, "ORDER BY a.attnum"), message));
        }
    }

    @Test
    void compositeAttributeAggregateJoinsOnlySchemaScopedCompositeRelations() {
        QueryBuilder query = new PgTypesReader(queryLoader("11, 22")).makeQuery();
        assertNotNull(query);
        String sql = query.build();

        String compositeRelationsCte = "composite_relations AS (\n"
                + "  SELECT\n"
                + "    target.oid\n"
                + "  FROM pg_catalog.pg_class target\n"
                + "  WHERE target.relkind = 'c'\n"
                + "    AND target.relnamespace IN (11, 22)\n"
                + ")";
        String compositeTypePredicate = "(res.typrelid = 0 OR EXISTS (SELECT 1 "
                + "FROM composite_relations type_rel WHERE type_rel.oid = res.typrelid))";
        String scalarTypeRelationLookup = "SELECT c.relkind FROM pg_catalog.pg_class c "
                + "WHERE c.oid = res.typrelid";

        assertAll(
                () -> assertTrue(sql.contains(compositeRelationsCte), sql),
                () -> assertTrue(sql.contains(
                        "JOIN composite_relations target ON target.oid = a.attrelid"), sql),
                () -> assertTrue(sql.contains(compositeTypePredicate), sql),
                () -> assertEquals(0, countOccurrences(sql, scalarTypeRelationLookup), sql),
                () -> assertEquals(1, countOccurrences(sql, "pg_catalog.pg_class target"), sql),
                () -> assertTrue(sql.contains("res.typnamespace IN (11, 22)"), sql),
                () -> assertTrue(sql.endsWith("\nORDER BY res.oid"), sql),
                () -> assertEquals(8, countOccurrences(sql, "ORDER BY a.attnum"), sql),
                () -> assertTrue(sql.contains("a.attisdropped = FALSE"), sql),
                () -> assertTrue(sql.contains(
                        "LEFT JOIN pg_catalog.pg_type ta ON ta.oid = a.atttypid"), sql),
                () -> assertTrue(sql.contains(
                        "LEFT JOIN collations cl ON cl.oid = a.attcollation"), sql),
                () -> assertTrue(sql.contains(
                        "LEFT JOIN pg_catalog.pg_description d ON d.objoid = a.attrelid AND d.objsubid = a.attnum"), sql));
    }

    @Test
    void domainConstraintAggregateJoinsOnlySchemaScopedDomainTypes() {
        QueryBuilder query = new PgTypesReader(queryLoader("11, 22")).makeQuery();
        assertNotNull(query);
        String sql = query.build();

        String scopedDomainJoin = "JOIN pg_catalog.pg_type dt ON dt.oid = c.contypid\n"
                + "    AND dt.typnamespace IN (11, 22)";

        assertAll(
                () -> assertTrue(sql.contains(scopedDomainJoin), sql),
                () -> assertTrue(sql.contains("c.contypid != 0"), sql),
                () -> assertTrue(sql.contains("c.contype != 'n'"), sql),
                () -> assertTrue(sql.contains(
                        "dom_constraints ON dom_constraints.contypid = res.oid"), sql),
                () -> assertInOrder(sql, "FROM pg_catalog.pg_constraint c",
                        "JOIN pg_catalog.pg_type dt ON dt.oid = c.contypid",
                        "LEFT JOIN pg_catalog.pg_description cd ON cd.objoid = c.oid"));
    }

    @Test
    void greenplumDomainConstraintAggregateKeepsSchemaScopedDomainTypes() {
        QueryBuilder query = new PgTypesReader(
                queryLoader("11, 22", true, false, PgSupportedVersion.GP_VERSION_6)).makeQuery();
        assertNotNull(query);
        String sql = query.build();

        assertAll(
                () -> assertTrue(sql.contains("JOIN pg_catalog.pg_type dt ON dt.oid = c.contypid"), sql),
                () -> assertTrue(sql.contains("dt.typnamespace IN (11, 22)"), sql));
    }

    @Test
    void greenplumCompositeTypePredicateRetainsScalarLookup() {
        QueryBuilder query = new PgTypesReader(queryLoader("11, 22", true)).makeQuery();
        assertNotNull(query);
        String sql = query.build();

        String scalarTypeRelationLookup = "(res.typrelid = 0 OR (SELECT c.relkind "
                + "FROM pg_catalog.pg_class c WHERE c.oid = res.typrelid) = 'c')";
        String compositeTypePredicate = "(res.typrelid = 0 OR EXISTS (SELECT 1 "
                + "FROM composite_relations type_rel WHERE type_rel.oid = res.typrelid))";

        assertAll(
                () -> assertTrue(sql.contains(scalarTypeRelationLookup), sql),
                () -> assertEquals(0, countOccurrences(sql, compositeTypePredicate), sql),
                () -> assertEquals(0, countOccurrences(sql, "ORDER BY res.oid"), sql));
    }

    @Test
    void emptySchemasSkipsViewQuery() throws Exception {
        PgJdbcLoader loader = queryLoader("");
        when(loader.getExtensionSchema()).thenReturn("pgcodekeeper");

        new PgViewsReader(loader).read();

        verify(loader, never()).prepareCatalogStatement(anyString());
    }

    @Test
    void ignoredPrivilegesAreNotProjectedByFunctionQuery() {
        String sql = new PgFunctionsReader(queryLoader("11, 22", false, true))
                .makeQuery().build();

        assertAll(
                () -> assertEquals(0, countOccurrences(sql, "res.proowner"), sql),
                () -> assertEquals(0, countOccurrences(sql, "res.proacl"), sql),
                () -> assertTrue(sql.contains("res.prosrc"), sql),
                () -> assertTrue(sql.contains("res.prorettype"), sql));
    }

    @Test
    void ignoredPrivilegesRemoveTableAndColumnAclWork() {
        String sql = new PgTablesReader(queryLoader("11, 22", false, true))
                .makeQuery().build();

        assertAll(
                () -> assertEquals(0, countOccurrences(sql, "res.relowner"), sql),
                () -> assertEquals(0, countOccurrences(sql, "res.relacl"), sql),
                () -> assertEquals(0, countOccurrences(sql, "a.attacl"), sql),
                () -> assertEquals(0, countOccurrences(sql, "columns.col_acl"), sql),
                () -> assertTrue(sql.contains("columns.col_names"), sql),
                () -> assertTrue(sql.contains("res.relkind IN ('f','r','p')"), sql));
    }

    @Test
    void ignoredPrivilegesRemoveViewAndColumnAclWork() {
        String sql = new PgViewsReader(queryLoader("11, 22", false, true))
                .makeQuery().build();

        assertAll(
                () -> assertEquals(0, countOccurrences(sql, "res.relowner"), sql),
                () -> assertEquals(0, countOccurrences(sql, "res.relacl"), sql),
                () -> assertEquals(0, countOccurrences(sql, "attr.attacl"), sql),
                () -> assertEquals(0, countOccurrences(sql, "subselect.column_acl"), sql),
                () -> assertTrue(sql.contains("subselect.column_names"), sql),
                () -> assertTrue(sql.endsWith("\nORDER BY res.oid"), sql));
    }

    @Test
    void ignoredPrivilegesAreNotProjectedByTypeQuery() {
        String sql = new PgTypesReader(queryLoader("11, 22", false, true))
                .makeQuery().build();

        assertAll(
                () -> assertEquals(0, countOccurrences(sql, "res.typowner"), sql),
                () -> assertEquals(0, countOccurrences(sql, "res.typacl"), sql),
                () -> assertTrue(sql.contains("res.typname"), sql),
                () -> assertTrue(sql.contains("res.typtype"), sql),
                () -> assertTrue(sql.contains("res.typnamespace IN (11, 22)"), sql));
    }

    @Test
    void ignoredPrivilegesAreNotProjectedBySequenceQuery() {
        String sql = new PgSequencesReader(queryLoader("11, 22", false, true))
                .makeQuery().build();

        assertAll(
                () -> assertEquals(0, countOccurrences(sql, "res.relowner"), sql),
                () -> assertEquals(0, countOccurrences(sql, "res.relacl"), sql),
                () -> assertTrue(sql.contains("res.relname"), sql),
                () -> assertTrue(sql.contains("res.relpersistence"), sql),
                () -> assertTrue(sql.contains("res.relkind = 'S'"), sql),
                () -> assertTrue(sql.contains("res.relnamespace IN (11, 22)"), sql));
    }

    @Test
    void ignoredTypePrivilegesAreNotReadFromResultSet() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true);
        ISchema schema = mock(ISchema.class);
        ResultSet result = mock(ResultSet.class);
        when(schema.getName()).thenReturn("app");
        when(result.getString("typtype")).thenReturn("e");
        when(result.getString("typname")).thenReturn("mood");

        new PgTypesReader(loader).processResult(result, schema);

        assertAll(
                () -> verify(result, never()).getLong("typowner"),
                () -> verify(result, never()).getString("typacl"));
    }

    @Test
    void ignoredSequencePrivilegesAreNotReadFromResultSet() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true);
        ISchema schema = mock(ISchema.class);
        ResultSet result = mock(ResultSet.class);
        when(schema.getName()).thenReturn("app");
        when(loader.getVersion()).thenReturn(PgSupportedVersion.GP_VERSION_6.getVersion());
        when(result.getString("relname")).thenReturn("seq");

        new PgSequencesReader(loader).processResult(result, schema);

        assertAll(
                () -> verify(result, never()).getLong("relowner"),
                () -> verify(result, never()).getString("aclarray"));
    }

    @Test
    void enabledPrivilegesKeepTypeAndSequenceProjectionOrder() {
        String types = new PgTypesReader(queryLoader("11, 22")).makeQuery().build();
        String sequences = new PgSequencesReader(queryLoader("11, 22")).makeQuery().build();

        assertAll(
                () -> assertInOrder(types, "res.typname", "res.typowner::bigint",
                        "res.typacl::text", "res.typtype"),
                () -> assertInOrder(sequences, "res.relowner::bigint", "res.relname",
                        "res.relpersistence", "referenced_table_name", "a.attname AS ref_col_name",
                        "res.relacl::text AS aclarray"));
    }

    @Test
    void ignoredPrivilegesOnlyRemoveScalarOwnerProjectionsAcrossVersions() {
        assertAll(
                () -> assertOnlyProjectionRemoved(
                        new PgOperatorsReader(queryLoader("11, 22", false, false,
                                PgSupportedVersion.VERSION_15)).makeQuery().build(),
                        new PgOperatorsReader(queryLoader("11, 22", false, true,
                                PgSupportedVersion.VERSION_15)).makeQuery().build(),
                        "res.oprowner AS owner"),
                () -> assertOnlyProjectionRemoved(
                        new PgStatisticsReader(queryLoader("11, 22", false, false,
                                PgSupportedVersion.GP_VERSION_6)).makeQuery().build(),
                        new PgStatisticsReader(queryLoader("11, 22", false, true,
                                PgSupportedVersion.GP_VERSION_6)).makeQuery().build(),
                        "res.stxowner"),
                () -> assertOnlyProjectionRemoved(
                        new PgStatisticsReader(queryLoader("11, 22", false, false,
                                PgSupportedVersion.VERSION_15)).makeQuery().build(),
                        new PgStatisticsReader(queryLoader("11, 22", false, true,
                                PgSupportedVersion.VERSION_15)).makeQuery().build(),
                        "res.stxowner"),
                () -> assertOnlyProjectionRemoved(
                        new PgFtsConfigurationsReader(queryLoader("11, 22", false, false,
                                PgSupportedVersion.VERSION_15)).makeQuery().build(),
                        new PgFtsConfigurationsReader(queryLoader("11, 22", false, true,
                                PgSupportedVersion.VERSION_15)).makeQuery().build(),
                        "res.cfgowner::bigint"),
                () -> assertOnlyProjectionRemoved(
                        new PgFtsDictionariesReader(queryLoader("11, 22", false, false,
                                PgSupportedVersion.VERSION_15)).makeQuery().build(),
                        new PgFtsDictionariesReader(queryLoader("11, 22", false, true,
                                PgSupportedVersion.VERSION_15)).makeQuery().build(),
                        "res.dictowner::bigint"),
                () -> assertOnlyProjectionRemoved(
                        new PgCollationsReader(queryLoader("11, 22", true, false,
                                PgSupportedVersion.GP_VERSION_6)).makeQuery().build(),
                        new PgCollationsReader(queryLoader("11, 22", true, true,
                                PgSupportedVersion.GP_VERSION_6)).makeQuery().build(),
                        "res.collowner::bigint"),
                () -> assertOnlyProjectionRemoved(
                        new PgCollationsReader(queryLoader("11, 22", true, false,
                                PgSupportedVersion.GP_VERSION_7)).makeQuery().build(),
                        new PgCollationsReader(queryLoader("11, 22", true, true,
                                PgSupportedVersion.GP_VERSION_7)).makeQuery().build(),
                        "res.collowner::bigint"),
                () -> assertOnlyProjectionRemoved(
                        new PgCollationsReader(queryLoader("11, 22", false, false,
                                PgSupportedVersion.VERSION_15)).makeQuery().build(),
                        new PgCollationsReader(queryLoader("11, 22", false, true,
                                PgSupportedVersion.VERSION_15)).makeQuery().build(),
                        "res.collowner::bigint"),
                () -> assertOnlyProjectionRemoved(
                        new PgCollationsReader(queryLoader("11, 22", false, false,
                                PgSupportedVersion.VERSION_16)).makeQuery().build(),
                        new PgCollationsReader(queryLoader("11, 22", false, true,
                                PgSupportedVersion.VERSION_16)).makeQuery().build(),
                        "res.collowner::bigint"),
                () -> assertOnlyProjectionRemoved(
                        new PgCollationsReader(queryLoader("11, 22", false, false,
                                PgSupportedVersion.VERSION_17)).makeQuery().build(),
                        new PgCollationsReader(queryLoader("11, 22", false, true,
                                PgSupportedVersion.VERSION_17)).makeQuery().build(),
                        "res.collowner::bigint"));
    }

    @Test
    void ignoredScalarOwnersAreNotReadFromResultSets() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true,
                PgSupportedVersion.VERSION_15);
        ISchema schema = mock(ISchema.class);
        when(schema.getName()).thenReturn("app");

        ResultSet operator = mock(ResultSet.class);
        when(operator.getString("name")).thenReturn("+");
        when(operator.getString("procedure_nsp")).thenReturn("pg_catalog");
        when(operator.getString("procedure")).thenReturn("int4pl");
        when(operator.getLong("result")).thenReturn(23L);
        PgJdbcType integerType = mock(PgJdbcType.class);
        when(integerType.getFullName()).thenReturn("integer");
        when(loader.getCachedTypeByOid(23L)).thenReturn(integerType);

        ResultSet statistics = mock(ResultSet.class);
        when(statistics.getString("stxname")).thenReturn("stat_one");
        when(statistics.getString("def")).thenReturn(
                "CREATE STATISTICS app.stat_one ON id FROM app.tab");

        ResultSet configuration = mock(ResultSet.class);
        when(configuration.getString("cfgname")).thenReturn("english");
        when(configuration.getString("prsnspname")).thenReturn("pg_catalog");
        when(configuration.getString("prsname")).thenReturn("default");

        ResultSet dictionary = mock(ResultSet.class);
        when(dictionary.getString("dictname")).thenReturn("english_stem");
        when(dictionary.getString("tmplnspname")).thenReturn("pg_catalog");
        when(dictionary.getString("tmplname")).thenReturn("snowball");

        ResultSet collation = mock(ResultSet.class);
        when(collation.getString("collname")).thenReturn("app_collation");
        when(collation.getString("collprovider")).thenReturn("c");

        new PgOperatorsReader(loader).processResult(operator, schema);
        new PgStatisticsReader(loader).processResult(statistics, schema);
        new PgFtsConfigurationsReader(loader).processResult(configuration, schema);
        new PgFtsDictionariesReader(loader).processResult(dictionary, schema);
        new PgCollationsReader(loader).processResult(collation, schema);

        assertAll(
                () -> verify(operator, never()).getLong("owner"),
                () -> verify(statistics, never()).getLong("stxowner"),
                () -> verify(configuration, never()).getLong("cfgowner"),
                () -> verify(dictionary, never()).getLong("dictowner"),
                () -> verify(collation, never()).getLong("collowner"));
    }

    @Test
    void enabledPrivilegesKeepScalarOwnerProjectionOrderAcrossVersions() {
        String operators = new PgOperatorsReader(queryLoader("11, 22", false, false,
                PgSupportedVersion.VERSION_15)).makeQuery().build();
        String statisticsGp6 = new PgStatisticsReader(queryLoader("11, 22", true, false,
                PgSupportedVersion.GP_VERSION_6)).makeQuery().build();
        String statisticsPg15 = new PgStatisticsReader(queryLoader("11, 22", false, false,
                PgSupportedVersion.VERSION_15)).makeQuery().build();
        String configurations = new PgFtsConfigurationsReader(queryLoader("11, 22", false, false,
                PgSupportedVersion.VERSION_15)).makeQuery().build();
        String dictionaries = new PgFtsDictionariesReader(queryLoader("11, 22", false, false,
                PgSupportedVersion.VERSION_15)).makeQuery().build();
        String collationsGp6 = new PgCollationsReader(queryLoader("11, 22", true, false,
                PgSupportedVersion.GP_VERSION_6)).makeQuery().build();
        String collationsGp7 = new PgCollationsReader(queryLoader("11, 22", true, false,
                PgSupportedVersion.GP_VERSION_7)).makeQuery().build();
        String collationsPg15 = new PgCollationsReader(queryLoader("11, 22", false, false,
                PgSupportedVersion.VERSION_15)).makeQuery().build();
        String collationsPg16 = new PgCollationsReader(queryLoader("11, 22", false, false,
                PgSupportedVersion.VERSION_16)).makeQuery().build();
        String collationsPg17 = new PgCollationsReader(queryLoader("11, 22", false, false,
                PgSupportedVersion.VERSION_17)).makeQuery().build();

        assertAll(
                () -> assertInOrder(operators, "prc_j.proname AS join",
                        "prc_j_n.nspname AS join_nsp", "res.oprowner AS owner",
                        "FROM pg_catalog.pg_operator res"),
                () -> assertInOrder(statisticsGp6, "res.stxname", "res.stxowner",
                        "pg_catalog.pg_get_statisticsobjdef", "FROM pg_catalog.pg_statistic_ext res"),
                () -> assertEquals(0, countOccurrences(statisticsGp6, "res.stxstattarget"),
                        statisticsGp6),
                () -> assertInOrder(statisticsPg15, "res.stxname", "res.stxowner",
                        "pg_catalog.pg_get_statisticsobjdef", "res.stxstattarget",
                        "FROM pg_catalog.pg_statistic_ext res"),
                () -> assertInOrder(configurations, "res.cfgname", "res.cfgowner::bigint",
                        "p.prsname", "n.nspname AS prsnspname",
                        "FROM pg_catalog.pg_ts_config res"),
                () -> assertInOrder(dictionaries, "res.dictname", "res.dictowner::bigint",
                        "t.tmplname", "n.nspname AS tmplnspname", "res.dictinitoption",
                        "FROM pg_catalog.pg_ts_dict res"),
                () -> assertInOrder(collationsGp6, "res.collname", "res.collcollate",
                        "res.collctype", "res.collowner::bigint",
                        "FROM pg_catalog.pg_collation res"),
                () -> assertEquals(0, countOccurrences(collationsGp6, "res.collprovider"),
                        collationsGp6),
                () -> assertInOrder(collationsGp7, "res.collowner::bigint", "res.collprovider",
                        "res.collisdeterministic", "FROM pg_catalog.pg_collation res"),
                () -> assertInOrder(collationsPg15, "res.collowner::bigint", "res.collprovider",
                        "res.collisdeterministic", "res.colliculocale",
                        "FROM pg_catalog.pg_collation res"),
                () -> assertInOrder(collationsPg16, "res.collowner::bigint", "res.collprovider",
                        "res.collisdeterministic", "res.colliculocale", "res.collicurules",
                        "FROM pg_catalog.pg_collation res"),
                () -> assertInOrder(collationsPg17, "res.collowner::bigint", "res.collprovider",
                        "res.collisdeterministic", "res.colllocale", "res.collicurules",
                        "FROM pg_catalog.pg_collation res"));
    }

    @Test
    void ignoredPrivilegesAreNotProjectedBySchemaQuery() {
        String sql = new PgSchemasReader(queryLoader("11, 22", false, true), mock(PgDatabase.class))
                .makeQuery().build();

        assertAll(
                () -> assertEquals(0, countOccurrences(sql, "res.nspowner"), sql),
                () -> assertEquals(0, countOccurrences(sql, "res.nspacl"), sql),
                () -> assertTrue(sql.contains("res.oid"), sql),
                () -> assertTrue(sql.contains("res.nspname"), sql),
                () -> assertTrue(sql.contains("res.nspname NOT LIKE 'pg\\_%'"), sql),
                () -> assertTrue(sql.contains("res.nspname != 'information_schema'"), sql));
    }

    @Test
    void ignoredPrivilegesRemoveEventTriggerOwnerJoin() {
        String sql = new PgEventTriggersReader(queryLoader("11, 22", false, true), mock(PgDatabase.class))
                .makeQuery().build();

        assertAll(
                () -> assertEquals(0, countOccurrences(sql, "o.rolname"), sql),
                () -> assertEquals(0, countOccurrences(sql, "JOIN pg_catalog.pg_roles o"), sql),
                () -> assertTrue(sql.contains("p.proname"), sql),
                () -> assertTrue(sql.contains("JOIN pg_catalog.pg_proc p ON p.oid = res.evtfoid"), sql),
                () -> assertTrue(sql.contains(
                        "JOIN pg_catalog.pg_namespace nsp ON p.pronamespace = nsp.oid"), sql));
    }

    @Test
    void ignoredPrivilegesAreNotProjectedByForeignDataWrapperQuery() {
        String sql = new PgForeignDataWrappersReader(
                queryLoader("11, 22", false, true), mock(PgDatabase.class)).makeQuery().build();

        assertAll(
                () -> assertEquals(0, countOccurrences(sql, "res.fdwowner"), sql),
                () -> assertEquals(0, countOccurrences(sql, "res.fdwacl"), sql),
                () -> assertTrue(sql.contains("res.fdwhandler"), sql),
                () -> assertTrue(sql.contains("res.fdwvalidator"), sql),
                () -> assertTrue(sql.contains("res.fdwoptions"), sql));
    }

    @Test
    void ignoredPrivilegesAreNotProjectedByServerQuery() {
        String sql = new PgServersReader(queryLoader("11, 22", false, true), mock(PgDatabase.class))
                .makeQuery().build();

        assertAll(
                () -> assertEquals(0, countOccurrences(sql, "res.srvowner"), sql),
                () -> assertEquals(0, countOccurrences(sql, "res.srvacl"), sql),
                () -> assertTrue(sql.contains("res.srvoptions"), sql),
                () -> assertTrue(sql.contains("fdw.fdwname"), sql),
                () -> assertTrue(sql.contains(
                        "LEFT JOIN pg_catalog.pg_foreign_data_wrapper fdw ON res.srvfdw = fdw.oid"), sql));
    }

    @Test
    void ignoredSchemaPrivilegesAreNotReadFromResultSet() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true);
        PgDatabase database = mock(PgDatabase.class);
        ResultSet result = mock(ResultSet.class);
        when(result.getString("nspname")).thenReturn("app");
        when(loader.isAllowedSchema("app")).thenReturn(true);

        new PgSchemasReader(loader, database).processResult(result);

        assertAll(
                () -> verify(result, never()).getLong("nspowner"),
                () -> verify(result, never()).getString("nspacl"));
    }

    @Test
    void ignoredPublicSchemaPrivilegesDoNotReadOwnerOrUseRoleCache() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true);
        PgDatabase database = mock(PgDatabase.class);
        ResultSet result = mock(ResultSet.class);
        when(result.getString("nspname")).thenReturn("public");
        when(loader.isAllowedSchema("public")).thenReturn(true);

        new PgSchemasReader(loader, database).processResult(result);

        assertAll(
                () -> verify(result, never()).getLong("nspowner"),
                () -> verify(result, never()).getString("nspacl"),
                () -> verify(loader, never()).getRoleByOid(anyLong()));
    }

    @Test
    void enabledPublicSchemaSuppressesDefaultPostgresOwner() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22");
        PgDatabase database = mock(PgDatabase.class);
        ResultSet result = mock(ResultSet.class);
        long ownerOid = 10L;
        when(result.getString("nspname")).thenReturn("public");
        when(result.getLong("nspowner")).thenReturn(ownerOid);
        when(loader.isAllowedSchema("public")).thenReturn(true);
        when(loader.getRoleByOid(ownerOid)).thenReturn("postgres");

        new PgSchemasReader(loader, database).processResult(result);

        assertAll(
                () -> verify(loader).getRoleByOid(ownerOid),
                () -> verify(loader, never()).setOwner(any(PgSchema.class), anyLong()));
    }

    @Test
    void enabledPublicSchemaSetsNonDefaultOwner() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22");
        PgDatabase database = mock(PgDatabase.class);
        ResultSet result = mock(ResultSet.class);
        long ownerOid = 42L;
        when(result.getString("nspname")).thenReturn("public");
        when(result.getLong("nspowner")).thenReturn(ownerOid);
        when(loader.isAllowedSchema("public")).thenReturn(true);
        when(loader.getRoleByOid(ownerOid)).thenReturn("app_owner");

        new PgSchemasReader(loader, database).processResult(result);

        assertAll(
                () -> verify(loader).getRoleByOid(ownerOid),
                () -> verify(loader).setOwner(any(PgSchema.class), eq(ownerOid)));
    }

    @Test
    void ignoredEventTriggerOwnerIsNotReadFromResultSet() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true);
        PgDatabase database = mock(PgDatabase.class);
        ResultSet result = mock(ResultSet.class);
        when(result.getString("evtname")).thenReturn("audit_ddl");
        when(result.getString("evtevent")).thenReturn("ddl_command_end");
        when(result.getString("evtenabled")).thenReturn("O");
        when(result.getString("proname")).thenReturn("audit_ddl");
        when(result.getString("nspname")).thenReturn("app");

        new PgEventTriggersReader(loader, database).processResult(result);

        verify(result, never()).getString("rolname");
    }

    @Test
    void ignoredForeignDataWrapperPrivilegesAreNotReadFromResultSet() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true);
        PgDatabase database = mock(PgDatabase.class);
        ResultSet result = mock(ResultSet.class);
        when(result.getString("fdwname")).thenReturn("remote_fdw");
        when(result.getString("fdwhandler")).thenReturn("-");
        when(result.getString("fdwvalidator")).thenReturn("-");

        new PgForeignDataWrappersReader(loader, database).processResult(result);

        assertAll(
                () -> verify(result, never()).getLong("fdwowner"),
                () -> verify(result, never()).getString("fdwacl"));
    }

    @Test
    void ignoredServerPrivilegesAreNotReadFromResultSet() throws Exception {
        PgJdbcLoader loader = queryLoader("11, 22", false, true);
        PgDatabase database = mock(PgDatabase.class);
        ResultSet result = mock(ResultSet.class);
        when(result.getString("srvname")).thenReturn("remote_server");
        when(result.getString("fdwname")).thenReturn("remote_fdw");

        new PgServersReader(loader, database).processResult(result);

        assertAll(
                () -> verify(result, never()).getLong("srvowner"),
                () -> verify(result, never()).getString("srvacl"));
    }

    @Test
    void enabledPrivilegesKeepGlobalProjectionAndJoinOrder() {
        PgJdbcLoader loader = queryLoader("11, 22");
        PgDatabase database = mock(PgDatabase.class);
        String schemas = new PgSchemasReader(loader, database).makeQuery().build();
        String events = new PgEventTriggersReader(loader, database).makeQuery().build();
        String wrappers = new PgForeignDataWrappersReader(loader, database).makeQuery().build();
        String servers = new PgServersReader(loader, database).makeQuery().build();

        assertAll(
                () -> assertInOrder(schemas, "res.oid", "res.nspname", "res.nspacl", "res.nspowner"),
                () -> assertInOrder(events, "res.evtname", "res.evtevent", "res.evtenabled", "res.evttags",
                        "nsp.nspname", "p.proname", "o.rolname"),
                () -> assertInOrder(events, "JOIN pg_catalog.pg_roles o", "JOIN pg_catalog.pg_proc p",
                        "JOIN pg_catalog.pg_namespace nsp"),
                () -> assertInOrder(wrappers, "res.fdwname", "res.fdwhandler", "res.fdwvalidator",
                        "res.fdwoptions", "res.fdwacl", "res.fdwowner"),
                () -> assertInOrder(servers, "res.srvname", "res.srvtype", "res.srvversion", "res.srvacl",
                        "res.srvoptions", "res.srvowner", "fdw.fdwname"));
    }

    @Test
    void extensionDepsCteUsesExtensionReferenceSidePredicate() {
        String tables = new PgTablesReader(queryLoader("11, 22")).makeQuery().build();
        String types = new PgTypesReader(queryLoader("11, 22")).makeQuery().build();
        String gpTables = new PgTablesReader(
                queryLoader("11, 22", true, false, PgSupportedVersion.GP_VERSION_6))
                .makeQuery().build();

        String tablesCte = "extension_deps AS (\n"
                + "  SELECT\n"
                + "    objid\n"
                + "  FROM pg_catalog.pg_depend\n"
                + "  WHERE refclassid = 'pg_catalog.pg_extension'::pg_catalog.regclass\n"
                + "    AND deptype = 'e'\n"
                + "    AND classid = 'pg_catalog.pg_class'::pg_catalog.regclass\n"
                + ")";
        String typesCte = "extension_deps AS (\n"
                + "  SELECT\n"
                + "    objid\n"
                + "  FROM pg_catalog.pg_depend\n"
                + "  WHERE refclassid = 'pg_catalog.pg_extension'::pg_catalog.regclass\n"
                + "    AND deptype = 'e'\n"
                + "    AND classid = 'pg_catalog.pg_type'::pg_catalog.regclass\n"
                + ")";

        assertAll(
                () -> assertTrue(tables.contains(tablesCte), tables),
                () -> assertTrue(types.contains(typesCte), types),
                () -> assertTrue(gpTables.contains(tablesCte), gpTables),
                () -> assertTrue(tables.contains(
                        "res.oid NOT IN (SELECT objid FROM extension_deps)"), tables),
                () -> assertTrue(types.contains(
                        "res.oid NOT IN (SELECT objid FROM extension_deps)"), types));
    }

    @Test
    void functionExtensionDepsCteKeepsInternalDependencyScan() {
        String functions = new PgFunctionsReader(queryLoader("11, 22")).makeQuery().build();

        String functionsCte = "extension_deps AS (\n"
                + "  SELECT\n"
                + "    objid\n"
                + "  FROM pg_catalog.pg_depend\n"
                + "  WHERE deptype IN ('e', 'i')\n"
                + "    AND classid = 'pg_catalog.pg_proc'::pg_catalog.regclass\n"
                + ")";

        assertAll(
                () -> assertTrue(functions.contains(functionsCte), functions),
                () -> assertEquals(0, countOccurrences(functions, "refclassid"), functions));
    }

    @Test
    void activeExtensionSchemaAddsAuthorJoinAndColumn() {
        PgJdbcLoader loader = queryLoader("11, 22");
        when(loader.getExtensionSchema()).thenReturn("dbo_ts");
        String tables = new PgTablesReader(loader).makeQuery().build();
        String functions = new PgFunctionsReader(loader).makeQuery().build();

        String tablesJoin = "LEFT JOIN dbo_ts.dbots_event_data time ON time.objid = res.oid"
                + " AND time.classid = 'pg_catalog.pg_class'::pg_catalog.regclass";
        String functionsJoin = "LEFT JOIN dbo_ts.dbots_event_data time ON time.objid = res.oid"
                + " AND time.classid = 'pg_catalog.pg_proc'::pg_catalog.regclass";

        assertAll(
                () -> assertTrue(tables.contains("time.ses_user"), tables),
                () -> assertTrue(tables.contains(tablesJoin), tables),
                () -> assertTrue(functions.contains("time.ses_user"), functions),
                () -> assertTrue(functions.contains(functionsJoin), functions));
    }

    @Test
    void missingExtensionSchemaOmitsAuthorJoinAcrossReaders() {
        PgJdbcLoader loader = queryLoader("11, 22");
        String tables = new PgTablesReader(loader).makeQuery().build();
        String views = new PgViewsReader(loader).makeQuery().build();
        String functions = new PgFunctionsReader(loader).makeQuery().build();
        String types = new PgTypesReader(loader).makeQuery().build();

        assertAll(
                () -> assertEquals(0, countOccurrences(tables, "dbots_event_data"), tables),
                () -> assertEquals(0, countOccurrences(tables, "ses_user"), tables),
                () -> assertEquals(0, countOccurrences(views, "dbots_event_data"), views),
                () -> assertEquals(0, countOccurrences(views, "ses_user"), views),
                () -> assertEquals(0, countOccurrences(functions, "dbots_event_data"), functions),
                () -> assertEquals(0, countOccurrences(functions, "ses_user"), functions),
                () -> assertEquals(0, countOccurrences(types, "dbots_event_data"), types),
                () -> assertEquals(0, countOccurrences(types, "ses_user"), types));
    }

    @Test
    void enabledPrivilegesKeepExistingCatalogProjection() {
        String functions = new PgFunctionsReader(queryLoader("11, 22"))
                .makeQuery().build();
        String tables = new PgTablesReader(queryLoader("11, 22"))
                .makeQuery().build();
        String views = new PgViewsReader(queryLoader("11, 22"))
                .makeQuery().build();

        assertAll(
                () -> assertTrue(functions.contains("res.proowner"), functions),
                () -> assertTrue(functions.contains("res.proacl"), functions),
                () -> assertTrue(tables.contains("a.attacl"), tables),
                () -> assertTrue(tables.contains("columns.col_acl"), tables),
                () -> assertTrue(views.contains("attr.attacl"), views),
                () -> assertTrue(views.contains("subselect.column_acl"), views));
    }

    private static PgJdbcLoader queryLoader(String schemas) {
        return queryLoader(schemas, false);
    }

    private static PgJdbcLoader queryLoader(String schemas, boolean greenplumDb) {
        return queryLoader(schemas, greenplumDb, false);
    }

    private static PgJdbcLoader queryLoader(String schemas, boolean greenplumDb,
                                            boolean ignorePrivileges) {
        return queryLoader(schemas, greenplumDb, ignorePrivileges,
                PgSupportedVersion.VERSION_16);
    }

    private static PgJdbcLoader queryLoader(String schemas, boolean greenplumDb,
                                            boolean ignorePrivileges,
                                            PgSupportedVersion version) {
        PgJdbcLoader loader = mock(PgJdbcLoader.class);
        ISettings settings = mock(ISettings.class);
        when(loader.getSchemas()).thenReturn(schemas);
        when(loader.getExtensionSchema()).thenReturn(null);
        when(loader.isGreenplumDb()).thenReturn(greenplumDb);
        when(loader.getVersion()).thenReturn(version.getVersion());
        when(loader.getSettings()).thenReturn(settings);
        when(settings.isIgnorePrivileges()).thenReturn(ignorePrivileges);
        return loader;
    }

    private static void assertOnlyProjectionRemoved(
            String enabledSql, String ignoredSql, String projection) {
        assertEquals(1, countOccurrences(enabledSql, projection), enabledSql);
        assertEquals(0, countOccurrences(ignoredSql, projection), ignoredSql);
        assertEquals(removeProjection(enabledSql, projection), ignoredSql);
    }

    private static String removeProjection(String sql, String projection) {
        String middleProjection = "  " + projection + ",\n";
        if (sql.contains(middleProjection)) {
            return sql.replace(middleProjection, "");
        }

        String finalProjection = ",\n  " + projection + "\nFROM";
        assertTrue(sql.contains(finalProjection), sql);
        return sql.replace(finalProjection, "\nFROM");
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int fromIndex = 0;
        while ((fromIndex = text.indexOf(fragment, fromIndex)) >= 0) {
            count++;
            fromIndex += fragment.length();
        }
        return count;
    }

    private static void assertInOrder(String sql, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = sql.indexOf(fragment);
            assertTrue(current > previous, "Expected fragment after offset " + previous
                    + ": " + fragment + '\n' + sql);
            previous = current;
        }
    }
}
