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
package org.pgcodekeeper.core.it.jdbc.pg;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.database.base.parser.AntlrError;
import org.pgcodekeeper.core.database.base.parser.FullAnalyze;
import org.pgcodekeeper.core.database.base.parser.ScriptParser;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.jdbc.PgJdbcConnector;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.loader.PgProjectLoader;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.BodyType;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.ParseDiagnosticPolicy;
import org.pgcodekeeper.core.database.pg.routine.DeferredRoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.RoutineBody;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyRepresentation;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.RoutineFingerprint;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyResidualTransport;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.testcontainer.TestContainerType;

@Isolated("mutates the shared PG16 testcontainer")
class PgJdbcRoutineFullBodyOracleTest {

    private static final String SERVER_FIXTURE = "pg_16_routine_full_body_server.sql";
    private static final String PROJECT_FIXTURE = "pg_16_routine_full_body_project.sql";
    private static final String JDBC_MODEL_GOLDEN = "pg_16_routine_full_body_jdbc_model.sql";
    private static final String DUMP_MODEL_GOLDEN = "pg_16_routine_full_body_dump_model.sql";
    private static final String DUMP_JDBC_DIFF_GOLDEN =
            "pg_16_routine_full_body_dump_jdbc_diff.sql";
    private static final String DIFF_GOLDEN = "pg_16_routine_full_body_diff.sql";
    private static final String CR_MARKER = "/*<CR>*/";
    private static final String UTF8_ROUTINE = "routine_oracle.utf8_body()";
    private static final Set<String> FIXTURE_SCHEMAS = Set.of(
            "routine_oracle", "Routine Oracle");

    // JDBC loading reads aggregates in a separate pass after all functions
    // (PgAggregatesReader), so the aggregate lands at the end of its schema
    private static final List<String> EXPECTED_JDBC_ROUTINE_ORDER = List.of(
            "FUNCTION|routine_oracle.ordered(integer)",
            "FUNCTION|routine_oracle.plpgsql_config(integer)",
            "PROCEDURE|routine_oracle.process_one(integer)",
            "FUNCTION|routine_oracle.aggregate_state(integer, integer)",
            "FUNCTION|routine_oracle.atomic_lookup(integer)",
            "FUNCTION|routine_oracle.internal_abs(integer)",
            "FUNCTION|routine_oracle.overloaded(integer)",
            "FUNCTION|routine_oracle.overloaded(text)",
            "FUNCTION|routine_oracle.utf8_body()",
            "FUNCTION|routine_oracle.bad_sql()",
            "FUNCTION|routine_oracle.bad_plpgsql()",
            "AGGREGATE|routine_oracle.aggregate_sum(integer)",
            "FUNCTION|\"Routine Oracle\".\"Mixed Routine\"(integer)");

    // dump loading keeps the script (creation) order with the aggregate inline
    private static final List<String> EXPECTED_DUMP_ROUTINE_ORDER = List.of(
            "FUNCTION|routine_oracle.ordered(integer)",
            "FUNCTION|routine_oracle.plpgsql_config(integer)",
            "PROCEDURE|routine_oracle.process_one(integer)",
            "FUNCTION|routine_oracle.aggregate_state(integer, integer)",
            "AGGREGATE|routine_oracle.aggregate_sum(integer)",
            "FUNCTION|routine_oracle.atomic_lookup(integer)",
            "FUNCTION|routine_oracle.internal_abs(integer)",
            "FUNCTION|routine_oracle.overloaded(integer)",
            "FUNCTION|routine_oracle.overloaded(text)",
            "FUNCTION|routine_oracle.utf8_body()",
            "FUNCTION|routine_oracle.bad_sql()",
            "FUNCTION|routine_oracle.bad_plpgsql()",
            "FUNCTION|\"Routine Oracle\".\"Mixed Routine\"(integer)");

    private static final List<String> EXPECTED_JDBC_LAUNCHER_ORDER = List.of(
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)|SQL|REPORT",
            "PgVexAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)|PLPGSQL|REPORT",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)|PLPGSQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.aggregate_state(integer, integer)|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)|FUNCTION_BODY|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.overloaded(integer)|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.overloaded(text)|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|\"Routine Oracle\".\"Mixed Routine\"(integer)|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.utf8_body()|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.bad_sql()|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.bad_plpgsql()|PLPGSQL|REPORT");

    private static final List<String> EXPECTED_DEFERRED_ROUTINES = List.of(
            "routine_oracle.ordered(integer)",
            "routine_oracle.plpgsql_config(integer)",
            "routine_oracle.process_one(integer)",
            "routine_oracle.aggregate_state(integer, integer)",
            "routine_oracle.overloaded(integer)",
            "routine_oracle.overloaded(text)",
            "\"Routine Oracle\".\"Mixed Routine\"(integer)",
            "routine_oracle.utf8_body()",
            "routine_oracle.bad_sql()",
            "routine_oracle.bad_plpgsql()");

    private static final List<String> EXPECTED_DUMP_LAUNCHER_ORDER = List.of(
            "PgVexAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)|PLPGSQL|REPORT",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)|PLPGSQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.aggregate_state(integer, integer)|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)|FUNCTION_BODY|SUPPRESS_DUPLICATE",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.overloaded(integer)|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.overloaded(text)|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|\"Routine Oracle\".\"Mixed Routine\"(integer)|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.utf8_body()|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.bad_sql()|SQL|REPORT",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.bad_plpgsql()|PLPGSQL|REPORT");

    private static final List<String> EXPECTED_ORDERED_TASKS = List.of(
            "BODY|PgFuncProcAnalysisLauncher|FUNCTION|jdbc:/routine_oracle/ordered",
            "ANTLR|41",
            "ANTLR|routine_oracle, pg_catalog");

    // Both fixtures place the broken routines on the same lines, so a body error
    // used to be indistinguishable between them: it reported the routine name and
    // dropped the file. Now the file is part of the address, and the two loads are
    // told apart by the only thing that ever differed between them.
    private static final List<String> EXPECTED_SERVER_DUMP_DIAGNOSTICS = List.of(
            SERVER_FIXTURE + "|75|24|extraneous input ')' expecting EOF, ';'",
            SERVER_FIXTURE + "|77|34|extraneous input ')' expecting ';'");

    private static final List<String> EXPECTED_PROJECT_DUMP_DIAGNOSTICS = List.of(
            PROJECT_FIXTURE + "|75|24|extraneous input ')' expecting EOF, ';'",
            PROJECT_FIXTURE + "|77|34|extraneous input ')' expecting ';'");

    private static final List<String> EXPECTED_JDBC_DIAGNOSTICS = List.of(
            "jdbc:/routine_oracle/bad_sql|1|7|extraneous input ')' expecting EOF, ';'",
            "jdbc:/routine_oracle/bad_plpgsql|1|13|extraneous input ')' expecting ';'");

    private static final String JDBC_ONLY_AGGREGATE_SCHEMA_DEPENDENCY =
            "AGGREGATE|routine_oracle.aggregate_sum(integer)->routine_oracle (SCHEMA)";

    // aggregate entries sit at the end of the schema: JDBC loading reads
    // aggregates in a separate pass after all functions (PgAggregatesReader)
    private static final List<String> EXPECTED_JDBC_DEPENDENCIES = List.of(
            "FUNCTION|routine_oracle.ordered(integer)->routine_oracle (SCHEMA)",
            "FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep.id (COLUMN)",
            "FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle (SCHEMA)",
            "FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep.id (COLUMN)",
            "PROCEDURE|routine_oracle.process_one(integer)->routine_oracle (SCHEMA)",
            "PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep.id (COLUMN)",
            "FUNCTION|routine_oracle.aggregate_state(integer, integer)->",
            "FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle (SCHEMA)",
            "FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep.id (COLUMN)",
            "FUNCTION|routine_oracle.internal_abs(integer)->",
            "FUNCTION|routine_oracle.overloaded(integer)->",
            "FUNCTION|routine_oracle.overloaded(text)->",
            "FUNCTION|routine_oracle.utf8_body()->",
            "FUNCTION|routine_oracle.bad_sql()->",
            "FUNCTION|routine_oracle.bad_plpgsql()->",
            JDBC_ONLY_AGGREGATE_SCHEMA_DEPENDENCY,
            "AGGREGATE|routine_oracle.aggregate_sum(integer)"
                    + "->routine_oracle.aggregate_state(integer, integer) (FUNCTION)",
            "FUNCTION|\"Routine Oracle\".\"Mixed Routine\"(integer)->");

    // dump loading keeps the script (creation) order with the aggregate inline
    private static final List<String> EXPECTED_DUMP_DEPENDENCIES = List.of(
            "FUNCTION|routine_oracle.ordered(integer)->routine_oracle (SCHEMA)",
            "FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep.id (COLUMN)",
            "FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle (SCHEMA)",
            "FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep.id (COLUMN)",
            "PROCEDURE|routine_oracle.process_one(integer)->routine_oracle (SCHEMA)",
            "PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep.id (COLUMN)",
            "FUNCTION|routine_oracle.aggregate_state(integer, integer)->",
            "AGGREGATE|routine_oracle.aggregate_sum(integer)"
                    + "->routine_oracle.aggregate_state(integer, integer) (FUNCTION)",
            "FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle (SCHEMA)",
            "FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep.id (COLUMN)",
            "FUNCTION|routine_oracle.internal_abs(integer)->",
            "FUNCTION|routine_oracle.overloaded(integer)->",
            "FUNCTION|routine_oracle.overloaded(text)->",
            "FUNCTION|routine_oracle.utf8_body()->",
            "FUNCTION|routine_oracle.bad_sql()->",
            "FUNCTION|routine_oracle.bad_plpgsql()->",
            "FUNCTION|\"Routine Oracle\".\"Mixed Routine\"(integer)->");

    private static final List<String> EXPECTED_JDBC_REFERENCES = List.of(
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgVexAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.aggregate_state(integer, integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.overloaded(integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.overloaded(text)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|\"Routine Oracle\".\"Mixed Routine\"(integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.utf8_body()->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.bad_sql()->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.bad_plpgsql()->");

    private static final List<String> EXPECTED_DUMP_REFERENCES = List.of(
            "PgVexAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.aggregate_state(integer, integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.overloaded(integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.overloaded(text)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|\"Routine Oracle\".\"Mixed Routine\"(integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.utf8_body()->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.bad_sql()->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.bad_plpgsql()->");

    private static final List<String> EXPECTED_PROJECT_DEPENDENCIES = List.of(
            "FUNCTION|routine_oracle.ordered(integer)->routine_oracle (SCHEMA)",
            "FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep.id (COLUMN)",
            "FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle (SCHEMA)",
            "FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep.id (COLUMN)",
            "PROCEDURE|routine_oracle.process_one(integer)->routine_oracle (SCHEMA)",
            "PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep.id (COLUMN)",
            "FUNCTION|routine_oracle.aggregate_state(integer, integer)->",
            "AGGREGATE|routine_oracle.aggregate_sum(integer)"
                    + "->routine_oracle.aggregate_state(integer, integer) (FUNCTION)",
            "FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle (SCHEMA)",
            "FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep.id (COLUMN)",
            "FUNCTION|routine_oracle.internal_abs(integer)->",
            "FUNCTION|routine_oracle.overloaded(integer)->",
            "FUNCTION|routine_oracle.overloaded(text)->",
            "FUNCTION|routine_oracle.utf8_body()->",
            "FUNCTION|routine_oracle.bad_sql()->",
            "FUNCTION|routine_oracle.bad_plpgsql()->",
            "FUNCTION|\"Routine Oracle\".\"Mixed Routine\"(integer)->");

    private static final List<String> EXPECTED_PROJECT_REFERENCES = List.of(
            "PgVexAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.ordered(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.plpgsql_config(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|PROCEDURE|routine_oracle.process_one(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.aggregate_state(integer, integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle (SCHEMA)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.atomic_lookup(integer)->routine_oracle.dep (TABLE)",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.overloaded(integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.overloaded(text)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|\"Routine Oracle\".\"Mixed Routine\"(integer)->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.utf8_body()->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.bad_sql()->",
            "PgFuncProcAnalysisLauncher|FUNCTION|routine_oracle.bad_plpgsql()->");

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @TempDir
    Path tempDir;

    @Test
    void fullBodyAndFingerprintRoutinePathsPreserveModelAnalysisAndExactDiffBytes()
            throws Exception {
        String url = TestContainerType.PG_16.getUrl();
        var connector = new PgJdbcConnector(url);
        String serverScript = fixture(SERVER_FIXTURE);
        String projectScript = fixture(PROJECT_FIXTURE);
        assertAll(
                () -> assertTrue(serverScript.contains("\r\n"), "fixture must exercise a raw CRLF body"),
                () -> assertTrue(serverScript.contains("Привет 😀 $function$"),
                        "fixture must exercise exact UTF-8 and a dollar-tag collision"));

        cleanup(connector);
        try {
            applyFixture(connector, SERVER_FIXTURE, serverScript);

            var jdbcSettings = settings();
            var jdbcLoader = new RecordingPgJdbcLoader(connector, jdbcSettings, false);
            IDatabase jdbcDb = jdbcLoader.load();
            List<IAnalysisLauncher> jdbcLaunchers = List.copyOf(jdbcDb.getAnalysisLaunchers());

            var fingerprintSettings = settings();
            var fingerprintLoader = new RecordingPgJdbcLoader(
                    connector, fingerprintSettings, true);
            IDatabase fingerprintDb = fingerprintLoader.load();
            List<IAnalysisLauncher> fingerprintLaunchers =
                    List.copyOf(fingerprintDb.getAnalysisLaunchers());

            var dumpSettings = pg16Settings();
            IDatabase dumpDb = dumpLoader(SERVER_FIXTURE, serverScript, dumpSettings).load();
            List<IAnalysisLauncher> dumpLaunchers = List.copyOf(dumpDb.getAnalysisLaunchers());

            String expectedJdbcModel = goldenText(JDBC_MODEL_GOLDEN);
            String expectedDumpModel = goldenText(DUMP_MODEL_GOLDEN);
            assertAll(
                    () -> assertEquals(EXPECTED_JDBC_ROUTINE_ORDER, routineOrder(jdbcDb)),
                    () -> assertEquals(EXPECTED_JDBC_ROUTINE_ORDER, routineOrder(fingerprintDb)),
                    () -> assertEquals(EXPECTED_DUMP_ROUTINE_ORDER, routineOrder(dumpDb)),
                    () -> assertEquals(EXPECTED_JDBC_LAUNCHER_ORDER, launcherOrder(jdbcLaunchers)),
                    () -> assertEquals(EXPECTED_JDBC_LAUNCHER_ORDER,
                            launcherOrder(fingerprintLaunchers)),
                    () -> assertEquals(EXPECTED_DUMP_LAUNCHER_ORDER, launcherOrder(dumpLaunchers)),
                    () -> assertEquals(EXPECTED_ORDERED_TASKS, jdbcLoader.getOrderedRoutineTasks()),
                    () -> assertEquals(EXPECTED_DEFERRED_ROUTINES,
                            jdbcLoader.getDeferredRoutineRegistrations()),
                    () -> assertEquals(List.of(),
                            jdbcLoader.getFingerprintRoutineRegistrations()),
                    () -> assertEquals(EXPECTED_ORDERED_TASKS,
                            fingerprintLoader.getOrderedRoutineTasks()),
                    () -> assertEquals(EXPECTED_DEFERRED_ROUTINES,
                            fingerprintLoader.getDeferredRoutineRegistrations()),
                    () -> assertEquals(EXPECTED_DEFERRED_ROUTINES,
                            fingerprintLoader.getFingerprintRoutineRegistrations()),
                    () -> assertEquals(expectedJdbcModel, scopedModelSnapshot(jdbcDb)),
                    () -> assertEquals(expectedJdbcModel, scopedModelSnapshot(fingerprintDb)),
                    () -> assertEquals(expectedDumpModel, scopedModelSnapshot(dumpDb)),
                    () -> assertNoAggregateBodyLauncher(jdbcLaunchers),
                    () -> assertNoAggregateBodyLauncher(fingerprintLaunchers),
                    () -> assertNoAggregateBodyLauncher(dumpLaunchers),
                    () -> assertDeferredBodySources(jdbcLaunchers),
                    () -> assertDeferredBodySources(fingerprintLaunchers),
                    () -> assertUtf8RawBody(jdbcLaunchers, dumpLaunchers),
                    () -> assertUtf8RawBody(fingerprintLaunchers, dumpLaunchers));

            FullAnalyze.fullAnalyze(dumpDb, dumpSettings.getErrors(), dumpSettings.getVersion());
            FullAnalyze.fullAnalyze(jdbcDb, jdbcSettings.getErrors(), jdbcSettings.getVersion());
            FullAnalyze.fullAnalyze(fingerprintDb,
                    fingerprintSettings.getErrors(), fingerprintSettings.getVersion());
            List<String> jdbcDependencies = orderedRoutineDependencies(jdbcDb);
            List<String> fingerprintDependencies = orderedRoutineDependencies(fingerprintDb);
            List<String> dumpDependencies = orderedRoutineDependencies(dumpDb);
            List<String> jdbcReferences = orderedReferences(jdbcLaunchers);
            List<String> fingerprintReferences = orderedReferences(fingerprintLaunchers);
            List<String> dumpReferences = orderedReferences(dumpLaunchers);

            var projectSettings = pg16Settings();
            IDatabase projectDb = dumpLoader(PROJECT_FIXTURE, projectScript, projectSettings).load();
            List<IAnalysisLauncher> projectLaunchers = List.copyOf(projectDb.getAnalysisLaunchers());
            FullAnalyze.fullAnalyze(projectDb, projectSettings.getErrors(), projectSettings.getVersion());
            List<String> projectDependencies = orderedRoutineDependencies(projectDb);
            List<String> projectReferences = orderedReferences(projectLaunchers);

            String dumpJdbcDiff = PgCodeKeeperApi.diff(
                    provider, dumpDb, jdbcDb, comparisonSettings());
            String dumpFingerprintDiff = PgCodeKeeperApi.diff(
                    provider, dumpDb, fingerprintDb, comparisonSettings());
            String exactDiff = PgCodeKeeperApi.diff(provider, jdbcDb, projectDb, comparisonSettings());
            String fingerprintDiff = PgCodeKeeperApi.diff(
                    provider, fingerprintDb, projectDb, comparisonSettings());
            Path exactProject = writeProject("exact-project", serverScript);
            String secondMismatch = "AS 'SELECT $1 + 1';";
            assertEquals(1, countOccurrences(projectScript, secondMismatch));
            String multiMismatchProjectScript = projectScript.replace(
                    secondMismatch, "AS 'SELECT $1 + 3';");
            Path changedProject = writeProject(
                    "changed-project", multiMismatchProjectScript);
            var exactProjectLoaderSettings = pg16Settings();
            IDatabase exactProjectDb = new PgProjectLoader(
                    exactProject, exactProjectLoaderSettings).load();
            FullAnalyze.fullAnalyze(exactProjectDb,
                    exactProjectLoaderSettings.getErrors(),
                    exactProjectLoaderSettings.getVersion());
            var changedProjectLoaderSettings = pg16Settings();
            IDatabase changedProjectDb = new PgProjectLoader(
                    changedProject, changedProjectLoaderSettings).load();
            FullAnalyze.fullAnalyze(changedProjectDb,
                    changedProjectLoaderSettings.getErrors(),
                    changedProjectLoaderSettings.getVersion());
            String exactProjectJdbcBaseline = PgCodeKeeperApi.diff(
                    provider, exactProjectDb, jdbcDb, comparisonSettings());
            String jdbcExactProjectBaseline = PgCodeKeeperApi.diff(
                    provider, jdbcDb, exactProjectDb, comparisonSettings());
            String changedProjectJdbcBaseline = PgCodeKeeperApi.diff(
                    provider, changedProjectDb, jdbcDb, comparisonSettings());
            String jdbcChangedProjectBaseline = PgCodeKeeperApi.diff(
                    provider, jdbcDb, changedProjectDb, comparisonSettings());
            List<String> exactProjectJdbcDiagnostics = concatDiagnostics(
                    exactProjectLoaderSettings, jdbcSettings);
            List<String> jdbcExactProjectDiagnostics = concatDiagnostics(
                    jdbcSettings, exactProjectLoaderSettings);
            List<String> changedProjectJdbcDiagnostics = concatDiagnostics(
                    changedProjectLoaderSettings, jdbcSettings);
            List<String> jdbcChangedProjectDiagnostics = concatDiagnostics(
                    jdbcSettings, changedProjectLoaderSettings);

            var keepNewlinesJdbcSettings = settings();
            keepNewlinesJdbcSettings.setKeepNewlines(true);
            var keepNewlinesJdbcLoader = new RecordingPgJdbcLoader(
                    connector, keepNewlinesJdbcSettings, false);
            IDatabase keepNewlinesJdbcDb = keepNewlinesJdbcLoader.load();
            FullAnalyze.fullAnalyze(keepNewlinesJdbcDb,
                    keepNewlinesJdbcSettings.getErrors(),
                    keepNewlinesJdbcSettings.getVersion());
            var keepNewlinesProjectSettings = pg16Settings();
            keepNewlinesProjectSettings.setKeepNewlines(true);
            IDatabase keepNewlinesProjectDb = new PgProjectLoader(
                    exactProject, keepNewlinesProjectSettings).load();
            FullAnalyze.fullAnalyze(keepNewlinesProjectDb,
                    keepNewlinesProjectSettings.getErrors(),
                    keepNewlinesProjectSettings.getVersion());
            String keepNewlinesBaseline = PgCodeKeeperApi.diff(
                    provider, keepNewlinesProjectDb, keepNewlinesJdbcDb,
                    keepNewlinesComparisonSettings());
            String keepNewlinesReverseBaseline = PgCodeKeeperApi.diff(
                    provider, keepNewlinesJdbcDb, keepNewlinesProjectDb,
                    keepNewlinesComparisonSettings());
            List<String> keepNewlinesProjectJdbcDiagnostics = concatDiagnostics(
                    keepNewlinesProjectSettings, keepNewlinesJdbcSettings);
            List<String> keepNewlinesJdbcProjectDiagnostics = concatDiagnostics(
                    keepNewlinesJdbcSettings, keepNewlinesProjectSettings);
            var keepNewlinesProbe = new RoutineExchangeProbe(true);
            var keepNewlinesCoordinatedSettings = keepNewlinesComparisonSettings();
            String coordinatedKeepNewlines = coordinatedProjectJdbcDiff(
                    connector, exactProject, true, keepNewlinesProbe,
                    keepNewlinesCoordinatedSettings);
            var keepNewlinesReverseProbe = new RoutineExchangeProbe(true);
            var keepNewlinesReverseSettings = keepNewlinesComparisonSettings();
            String coordinatedKeepNewlinesReverse = coordinatedProjectJdbcDiff(
                    connector, exactProject, false, keepNewlinesReverseProbe,
                    keepNewlinesReverseSettings);

            var exactProjectOldProbe = new RoutineExchangeProbe(true);
            var exactProjectOldSettings = comparisonSettings();
            String coordinatedExactProjectOld = coordinatedProjectJdbcDiff(
                    connector, exactProject, true, exactProjectOldProbe,
                    exactProjectOldSettings);
            var exactJdbcOldProbe = new RoutineExchangeProbe(true);
            var exactJdbcOldSettings = comparisonSettings();
            String coordinatedExactJdbcOld = coordinatedProjectJdbcDiff(
                    connector, exactProject, false, exactJdbcOldProbe,
                    exactJdbcOldSettings);
            var changedProjectOldProbe = new RoutineExchangeProbe(false);
            var changedProjectOldSettings = comparisonSettings();
            changedProjectOldSettings.setPgRoutineBodyResidualBatchCount(1);
            String coordinatedChangedProjectOld = coordinatedProjectJdbcDiff(
                    connector, changedProject, true, changedProjectOldProbe,
                    changedProjectOldSettings);
            var changedJdbcOldProbe = new RoutineExchangeProbe(false);
            var changedJdbcOldSettings = comparisonSettings();
            changedJdbcOldSettings.setPgRoutineBodyResidualBatchBytes(1);
            String coordinatedChangedJdbcOld = coordinatedProjectJdbcDiff(
                    connector, changedProject, false, changedJdbcOldProbe,
                    changedJdbcOldSettings);
            assertAll(
                    () -> assertArrayEquals(goldenBytes(DUMP_JDBC_DIFF_GOLDEN),
                            dumpJdbcDiff.getBytes(StandardCharsets.UTF_8)),
                    () -> assertArrayEquals(goldenBytes(DUMP_JDBC_DIFF_GOLDEN),
                            dumpFingerprintDiff.getBytes(StandardCharsets.UTF_8)),
                    () -> assertEquals(EXPECTED_SERVER_DUMP_DIAGNOSTICS,
                            diagnosticOrder(dumpSettings.getErrors())),
                    () -> assertEquals(EXPECTED_JDBC_DIAGNOSTICS,
                            diagnosticOrder(jdbcSettings.getErrors())),
                    () -> assertEquals(EXPECTED_JDBC_DIAGNOSTICS,
                            diagnosticOrder(fingerprintSettings.getErrors())),
                    () -> assertEquals(EXPECTED_JDBC_DEPENDENCIES, jdbcDependencies,
                            "JDBC dependencies"),
                    () -> assertEquals(EXPECTED_JDBC_DEPENDENCIES, fingerprintDependencies,
                            "fingerprint JDBC dependencies"),
                    () -> assertEquals(EXPECTED_DUMP_DEPENDENCIES, dumpDependencies,
                            "dump dependencies"),
                    () -> assertEquals(EXPECTED_JDBC_REFERENCES, jdbcReferences),
                    () -> assertEquals(EXPECTED_JDBC_REFERENCES, fingerprintReferences),
                    () -> assertEquals(EXPECTED_DUMP_REFERENCES, dumpReferences),
                    () -> assertFalse(exactDiff.isEmpty(), "project/server oracle must be non-empty"),
                    () -> assertArrayEquals(goldenBytes(DIFF_GOLDEN),
                            exactDiff.getBytes(StandardCharsets.UTF_8)),
                    () -> assertArrayEquals(goldenBytes(DIFF_GOLDEN),
                            fingerprintDiff.getBytes(StandardCharsets.UTF_8)),
                    () -> assertArrayEquals(
                            exactProjectJdbcBaseline.getBytes(StandardCharsets.UTF_8),
                            coordinatedExactProjectOld.getBytes(StandardCharsets.UTF_8)),
                    () -> assertArrayEquals(
                            jdbcExactProjectBaseline.getBytes(StandardCharsets.UTF_8),
                            coordinatedExactJdbcOld.getBytes(StandardCharsets.UTF_8)),
                    () -> assertArrayEquals(
                            changedProjectJdbcBaseline.getBytes(StandardCharsets.UTF_8),
                            coordinatedChangedProjectOld.getBytes(StandardCharsets.UTF_8)),
                    () -> assertArrayEquals(
                            jdbcChangedProjectBaseline.getBytes(StandardCharsets.UTF_8),
                            coordinatedChangedJdbcOld.getBytes(StandardCharsets.UTF_8)),
                    () -> assertArrayEquals(
                            keepNewlinesBaseline.getBytes(StandardCharsets.UTF_8),
                            coordinatedKeepNewlines.getBytes(StandardCharsets.UTF_8)),
                    () -> assertArrayEquals(
                            keepNewlinesReverseBaseline.getBytes(StandardCharsets.UTF_8),
                            coordinatedKeepNewlinesReverse.getBytes(StandardCharsets.UTF_8)),
                    () -> assertExactExchange(exactProjectOldProbe),
                    () -> assertExactExchange(exactJdbcOldProbe),
                    () -> assertResidualExchange(changedProjectOldProbe),
                    () -> assertResidualExchange(changedJdbcOldProbe),
                    () -> assertEquals(2, changedProjectOldProbe.fetchedBodyCount.get()),
                    () -> assertEquals(1, changedProjectOldProbe.maxFetchedBodyCount.get()),
                    () -> assertEquals(changedProjectOldProbe.fetchCalls.get(),
                            changedProjectOldProbe.fetchedBodyCount.get()),
                    () -> assertEquals(2, changedJdbcOldProbe.fetchedBodyCount.get()),
                    () -> assertEquals(1, changedJdbcOldProbe.maxFetchedBodyCount.get()),
                    () -> assertEquals(changedJdbcOldProbe.fetchCalls.get(),
                            changedJdbcOldProbe.fetchedBodyCount.get()),
                    () -> assertExactExchange(keepNewlinesProbe),
                    () -> assertExactExchange(keepNewlinesReverseProbe),
                    () -> assertEquals(exactProjectJdbcDiagnostics,
                            diagnosticOrder(exactProjectOldSettings.getErrors())),
                    () -> assertEquals(jdbcExactProjectDiagnostics,
                            diagnosticOrder(exactJdbcOldSettings.getErrors())),
                    () -> assertEquals(changedProjectJdbcDiagnostics,
                            diagnosticOrder(changedProjectOldSettings.getErrors())),
                    () -> assertEquals(jdbcChangedProjectDiagnostics,
                            diagnosticOrder(changedJdbcOldSettings.getErrors())),
                    () -> assertEquals(keepNewlinesProjectJdbcDiagnostics,
                            diagnosticOrder(keepNewlinesCoordinatedSettings.getErrors())),
                    () -> assertEquals(keepNewlinesJdbcProjectDiagnostics,
                            diagnosticOrder(keepNewlinesReverseSettings.getErrors())),
                    () -> assertEquals(PgSupportedVersion.VERSION_16.getVersion(),
                            jdbcSettings.getVersion().getVersion()),
                    () -> assertEquals(PgSupportedVersion.VERSION_16.getVersion(),
                            fingerprintSettings.getVersion().getVersion()),
                    () -> assertEquals(EXPECTED_PROJECT_DUMP_DIAGNOSTICS,
                            diagnosticOrder(projectSettings.getErrors())),
                    () -> assertEquals(EXPECTED_DUMP_LAUNCHER_ORDER,
                            launcherOrder(projectLaunchers)),
                    () -> assertEquals(EXPECTED_PROJECT_DEPENDENCIES, projectDependencies,
                            "project dependencies"),
                    () -> assertEquals(EXPECTED_PROJECT_REFERENCES, projectReferences,
                            "project references"));
        } finally {
            cleanup(connector);
        }
    }

    private org.pgcodekeeper.core.database.pg.loader.PgDumpLoader dumpLoader(
            String name, String script, CoreSettings settings) {
        return provider.getDumpLoader(
                () -> new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                name, settings);
    }

    private String coordinatedProjectJdbcDiff(PgJdbcConnector connector,
            Path projectPath, boolean projectOld, RoutineExchangeProbe probe,
            CoreSettings comparisonSettings)
            throws Exception {
        var projectFactory = (org.pgcodekeeper.core.database.api.loader.ILoaderFactory)
                sideSettings -> new PgProjectLoader(projectPath, sideSettings);
        var jdbcFactory = (org.pgcodekeeper.core.database.api.loader.ILoaderFactory)
                sideSettings -> {
                    var loader = new RecordingPgJdbcLoader(
                            connector, sideSettings, false, true, probe);
                    probe.loader.set(loader);
                    return loader;
                };
        var factories = projectOld
                ? new ComparisonLoaderFactories(projectFactory, jdbcFactory)
                : new ComparisonLoaderFactories(jdbcFactory, projectFactory);
        return PgCodeKeeperApi.diff(provider, factories, comparisonSettings);
    }

    private Path writeProject(String name, String script) throws Exception {
        Path project = tempDir.resolve(name);
        Path schema = Files.createDirectories(
                project.resolve("SCHEMA").resolve("routine_oracle"));
        Files.writeString(schema.resolve("routine_oracle.sql"),
                script, StandardCharsets.UTF_8);
        return project;
    }

    private static void assertExactExchange(RoutineExchangeProbe probe) {
        RecordingPgJdbcLoader loader = probe.loader.get();
        assertNotNull(loader);
        assertAll(
                () -> assertEquals(1, probe.transportCreateCalls.get()),
                () -> assertEquals(0, probe.fetchCalls.get()),
                () -> assertEquals(0, probe.fetchedBodyCount.get()),
                () -> assertEquals(1, probe.transportCloseCalls.get()),
                () -> assertEquals(EXPECTED_DEFERRED_ROUTINES,
                        loader.getFingerprintRoutineRegistrations()),
                () -> assertOptimizedJdbcSemantics(loader));
    }

    private static void assertResidualExchange(RoutineExchangeProbe probe) {
        RecordingPgJdbcLoader loader = probe.loader.get();
        assertNotNull(loader);
        assertAll(
                () -> assertEquals(1, probe.transportCreateCalls.get()),
                () -> assertTrue(probe.fetchCalls.get() > 0),
                () -> assertTrue(probe.fetchedBodyCount.get() > 0),
                () -> assertTrue(probe.fetchedBodyCount.get()
                        < EXPECTED_DEFERRED_ROUTINES.size()),
                () -> assertEquals(1, probe.transportCloseCalls.get()),
                () -> assertEquals(EXPECTED_DEFERRED_ROUTINES,
                        loader.getFingerprintRoutineRegistrations()),
                () -> assertOptimizedJdbcSemantics(loader));
    }

    private static void assertOptimizedJdbcSemantics(RecordingPgJdbcLoader loader) {
        IDatabase database = loader.getDatabase();
        assertNotNull(database);
        List<IAnalysisLauncher> launchers = loader.getAnalysisLaunchersBeforeAnalyze();
        assertAll(
                () -> assertEquals(EXPECTED_JDBC_ROUTINE_ORDER, routineOrder(database)),
                () -> assertEquals(EXPECTED_JDBC_LAUNCHER_ORDER,
                        assertDoesNotThrow(() -> launcherOrder(launchers))),
                () -> assertEquals(EXPECTED_ORDERED_TASKS,
                        loader.getOrderedRoutineTasks()),
                () -> assertEquals(EXPECTED_JDBC_DEPENDENCIES,
                        orderedRoutineDependencies(database)),
                () -> assertEquals(EXPECTED_JDBC_REFERENCES,
                        orderedReferences(launchers)));
    }

    private void applyFixture(PgJdbcConnector connector, String name, String script)
            throws Exception {
        var fixtureLoader = dumpLoader(name, script, settings());
        new JdbcRunner(new NullMonitor()).runBatches(connector,
                new ScriptParser(fixtureLoader, name, script).batch(), null);
    }

    private static String fixture(String name) throws Exception {
        String template = Files.readString(
                TestUtils.getFilePath(name, PgJdbcRoutineFullBodyOracleTest.class),
                StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(template, CR_MARKER), name);
        return template.replace(CR_MARKER, "\r");
    }

    private static CoreSettings settings() {
        var settings = new CoreSettings();
        settings.setEnableFunctionBodiesDependencies(true);
        settings.setIgnorePrivileges(true);
        return settings;
    }

    private static CoreSettings pg16Settings() {
        var settings = settings();
        settings.setVersion(PgSupportedVersion.VERSION_16);
        return settings;
    }

    private static CoreSettings comparisonSettings() {
        var settings = pg16Settings();
        settings.setAllowedTypes(Arrays.stream(DbObjType.values())
                .filter(type -> type != DbObjType.EXTENSION)
                .toList());
        return settings;
    }

    private static CoreSettings keepNewlinesComparisonSettings() {
        var settings = comparisonSettings();
        settings.setKeepNewlines(true);
        return settings;
    }

    private static void cleanup(PgJdbcConnector connector) throws Exception {
        try (Connection connection = connector.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"Routine Oracle\" CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS routine_oracle CASCADE");
        }
    }

    private static List<String> routineOrder(IDatabase db) {
        return db.getDescendants()
                .filter(statement -> isRoutine(statement.getStatementType()))
                .map(statement -> statement.getStatementType() + "|" + statement.getQualifiedName())
                .toList();
    }

    private static List<String> launcherOrder(List<IAnalysisLauncher> launchers)
            throws ReflectiveOperationException {
        Field bodyTypeField = PgFuncProcAnalysisLauncher.class.getDeclaredField("bodyType");
        bodyTypeField.setAccessible(true);
        Field diagnosticPolicyField = PgFuncProcAnalysisLauncher.class
                .getDeclaredField("diagnosticPolicy");
        diagnosticPolicyField.setAccessible(true);

        var result = new ArrayList<String>(launchers.size());
        for (IAnalysisLauncher launcher : launchers) {
            IStatement statement = launcher.getStmt();
            if (!isRoutine(statement.getStatementType())) {
                continue;
            }
            StringBuilder value = new StringBuilder(launcher.getClass().getSimpleName())
                    .append('|').append(statement.getStatementType())
                    .append('|').append(statement.getQualifiedName());
            if (launcher instanceof PgFuncProcAnalysisLauncher) {
                value.append('|').append((BodyType) bodyTypeField.get(launcher))
                        .append('|').append((ParseDiagnosticPolicy) diagnosticPolicyField.get(launcher));
            }
            result.add(value.toString());
        }
        return List.copyOf(result);
    }

    private static void assertNoAggregateBodyLauncher(List<IAnalysisLauncher> launchers) {
        assertTrue(launchers.stream()
                .filter(PgFuncProcAnalysisLauncher.class::isInstance)
                .noneMatch(launcher -> launcher.getStmt().getStatementType() == DbObjType.AGGREGATE),
                "aggregate must not have a reusable function-body launcher");
    }

    private static void assertDeferredBodySources(List<IAnalysisLauncher> launchers)
            throws ReflectiveOperationException {
        Field sourceField = PgFuncProcAnalysisLauncher.class.getDeclaredField("bodySource");
        sourceField.setAccessible(true);
        Set<String> expectedDeferred = Set.copyOf(EXPECTED_DEFERRED_ROUTINES);

        for (IAnalysisLauncher launcher : launchers) {
            if (!(launcher instanceof PgFuncProcAnalysisLauncher)) {
                continue;
            }
            Object source = sourceField.get(launcher);
            String qualifiedName = launcher.getStmt().getQualifiedName();
            assertEquals(expectedDeferred.contains(qualifiedName),
                    source instanceof DeferredRoutineBodySource, qualifiedName);
        }
    }

    private static void assertUtf8RawBody(List<IAnalysisLauncher> jdbcLaunchers,
                                          List<IAnalysisLauncher> dumpLaunchers)
            throws ReflectiveOperationException {
        String jdbcRaw = rawBody(jdbcLaunchers, UTF8_ROUTINE);
        String dumpRaw = rawBody(dumpLaunchers, UTF8_ROUTINE);
        assertAll(
                () -> assertEquals(dumpRaw, jdbcRaw),
                () -> assertTrue(jdbcRaw.contains("\r\n"), jdbcRaw),
                () -> assertTrue(jdbcRaw.contains("Привет 😀 $function$"), jdbcRaw));
    }

    private static String rawBody(List<IAnalysisLauncher> launchers, String qualifiedName)
            throws ReflectiveOperationException {
        Field sourceField = PgFuncProcAnalysisLauncher.class.getDeclaredField("bodySource");
        sourceField.setAccessible(true);
        IAnalysisLauncher launcher = launchers.stream()
                .filter(PgFuncProcAnalysisLauncher.class::isInstance)
                .filter(candidate -> candidate.getStmt().getQualifiedName().equals(qualifiedName))
                .findFirst()
                .orElseThrow();
        Object source = sourceField.get(launcher);
        Field bodyField = source.getClass().getDeclaredField("body");
        bodyField.setAccessible(true);
        return ((RoutineBody) bodyField.get(source)).raw();
    }

    private static String scopedModelSnapshot(IDatabase db) {
        CoreSettings settings = pg16Settings();
        return db.getDescendants()
                .filter(PgJdbcRoutineFullBodyOracleTest::isFixtureScoped)
                .flatMap(ITable::columnAdder)
                .map(statement -> {
                    var script = new SQLScript(settings, statement.getSeparator());
                    statement.getCreationSQL(script);
                    return statement.getStatementType() + "|" + statement.getQualifiedName()
                            + "\nPARENT|" + modelIdentity(statement.getParent())
                            + "\nCHILDREN|" + modelChildren(statement)
                            + '\n' + script.getFullScript();
                })
                .collect(Collectors.joining("\n-- MODEL OBJECT --\n"));
    }

    private static boolean isFixtureScoped(IStatement statement) {
        IStatement current = statement;
        while (current != null && current.getStatementType() != DbObjType.SCHEMA) {
            current = current.getParent();
        }
        return current != null && FIXTURE_SCHEMAS.contains(current.getName());
    }

    private static String modelChildren(IStatement statement) {
        Stream<? extends IStatement> children = statement.getChildren();
        if (statement instanceof ITable table) {
            children = Stream.concat(table.getColumns().stream(), children);
        }
        return children.map(PgJdbcRoutineFullBodyOracleTest::modelIdentity)
                .collect(Collectors.joining(","));
    }

    private static String modelIdentity(IStatement statement) {
        if (statement == null) {
            return "-";
        }
        if (statement.getStatementType() == DbObjType.DATABASE) {
            return "DATABASE|<database>";
        }
        return statement.getStatementType() + "|" + statement.getQualifiedName();
    }

    private static List<String> diagnosticOrder(List<Object> errors) {
        return errors.stream().map(error -> {
            if (!(error instanceof AntlrError antlr)) {
                return error.toString();
            }
            String location = antlr.getFilePath().startsWith("jdbc:")
                    ? antlr.getFilePath()
                    : Path.of(antlr.getFilePath()).getFileName().toString();
            return location + '|' + antlr.getLineNumber() + '|'
                    + antlr.getCharPositionInLine() + '|' + antlr.getMsg();
        }).toList();
    }

    private static List<String> concatDiagnostics(CoreSettings first, CoreSettings second) {
        return Stream.concat(
                diagnosticOrder(first.getErrors()).stream(),
                diagnosticOrder(second.getErrors()).stream()).toList();
    }

    private static List<String> orderedRoutineDependencies(IDatabase db) {
        var result = new ArrayList<String>();
        db.getDescendants()
                .filter(statement -> isRoutine(statement.getStatementType()))
                .forEach(statement -> {
                    String prefix = statement.getStatementType() + "|"
                            + statement.getQualifiedName() + "->";
                    if (statement.getDependencies().isEmpty()) {
                        result.add(prefix);
                    } else {
                        statement.getDependencies().forEach(reference -> result.add(prefix + reference));
                    }
                });
        return List.copyOf(result);
    }

    private static List<String> orderedReferences(List<IAnalysisLauncher> launchers) {
        var result = new ArrayList<String>();
        for (IAnalysisLauncher launcher : launchers) {
            IStatement statement = launcher.getStmt();
            if (!isRoutine(statement.getStatementType())) {
                continue;
            }
            String prefix = launcher.getClass().getSimpleName() + '|'
                    + statement.getStatementType() + '|' + statement.getQualifiedName() + "->";
            if (launcher.getReferences().isEmpty()) {
                result.add(prefix);
            } else {
                launcher.getReferences().stream()
                        .map(ObjectLocation::getObjectReference)
                        .map(reference -> Objects.requireNonNull(reference,
                                () -> "null body reference for " + statement.getQualifiedName()))
                        .map(ObjectReference::toString)
                        .forEach(reference -> result.add(prefix + reference));
            }
        }
        return List.copyOf(result);
    }

    private static boolean isRoutine(DbObjType type) {
        return type.in(DbObjType.FUNCTION, DbObjType.PROCEDURE, DbObjType.AGGREGATE);
    }

    private static String goldenText(String name) throws Exception {
        return new String(goldenBytes(name), StandardCharsets.UTF_8);
    }

    private static byte[] goldenBytes(String name) throws Exception {
        byte[] stored = Files.readAllBytes(
                TestUtils.getFilePath(name, PgJdbcRoutineFullBodyOracleTest.class));
        assertTrue(stored.length > 0 && stored[stored.length - 1] == '\n',
                () -> name + " must end in exactly one repository LF terminator");
        assertTrue(stored.length == 1 || stored[stored.length - 2] != '\n',
                () -> name + " must end in exactly one repository LF terminator");
        return Arrays.copyOf(stored, stored.length - 1);
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

    private static final class RecordingPgJdbcLoader extends PgJdbcLoader {

        private final List<String> orderedRoutineTasks = new ArrayList<>();
        private final List<String> deferredRoutineRegistrations = new ArrayList<>();
        private final List<String> fingerprintRoutineRegistrations = new ArrayList<>();
        private final List<IAnalysisLauncher> analysisLaunchersBeforeAnalyze = new ArrayList<>();
        private final boolean requestFingerprints;
        private final boolean requestExchange;
        private final RoutineExchangeProbe exchangeProbe;

        private RecordingPgJdbcLoader(PgJdbcConnector connector, CoreSettings settings,
                                      boolean requestFingerprints) {
            this(connector, settings, requestFingerprints, false, null);
        }

        private RecordingPgJdbcLoader(PgJdbcConnector connector, ISettings settings,
                                      boolean requestFingerprints, boolean requestExchange,
                                      RoutineExchangeProbe exchangeProbe) {
            super(connector, Consts.UTC, settings);
            this.requestFingerprints = requestFingerprints;
            this.requestExchange = requestExchange;
            this.exchangeProbe = exchangeProbe;
        }

        @Override
        public PgDatabase loadAndAnalyze() throws java.io.IOException, InterruptedException {
            PgDatabase database = load();
            analysisLaunchersBeforeAnalyze.clear();
            analysisLaunchersBeforeAnalyze.addAll(database.getAnalysisLaunchers());
            return super.loadAndAnalyze();
        }

        @Override
        protected boolean requestRoutineBodyFingerprints() {
            return requestFingerprints || super.requestRoutineBodyFingerprints();
        }

        @Override
        protected boolean requestRoutineBodyExchange() {
            return requestExchange;
        }

        @Override
        protected PgRoutineBodyResidualTransport createRoutineBodyResidualTransport() {
            PgRoutineBodyResidualTransport delegate =
                    super.createRoutineBodyResidualTransport();
            RoutineExchangeProbe probe = exchangeProbe;
            if (probe == null) {
                return delegate;
            }
            probe.transportCreateCalls.incrementAndGet();
            return new PgRoutineBodyResidualTransport() {
                @Override
                public void fetch(Long[] orderedOids, RowConsumer rows, IMonitor monitor)
                        throws java.io.IOException, InterruptedException {
                    probe.fetchCalls.incrementAndGet();
                    probe.fetchedBodyCount.addAndGet(orderedOids.length);
                    probe.maxFetchedBodyCount.accumulateAndGet(orderedOids.length, Math::max);
                    if (probe.rejectResiduals) {
                        throw new AssertionError(
                                "exact project match must not fetch residual routine bodies");
                    }
                    delegate.fetch(orderedOids, rows, monitor);
                }

                @Override
                public void close() throws java.io.IOException {
                    probe.transportCloseCalls.incrementAndGet();
                    delegate.close();
                }
            };
        }

        @Override
        public <T> void submitAntlrTask(String sql, Function<SQLParser, T> parserCtxReader,
                                        Consumer<T> finalizer) {
            if (getCurrentLocation().endsWith("/ordered")) {
                orderedRoutineTasks.add("ANTLR|" + sql);
            }
            super.submitAntlrTask(sql, parserCtxReader, finalizer);
        }

        @Override
        public void submitAnalysisLauncher(IAnalysisLauncher launcher, IDatabase db) {
            if (getCurrentLocation().endsWith("/ordered")) {
                orderedRoutineTasks.add("BODY|" + launcher.getClass().getSimpleName() + '|'
                        + launcher.getStmt().getStatementType() + '|'
                        + getCurrentLocation());
            }
            super.submitAnalysisLauncher(launcher, db);
        }

        @Override
        public RoutineBodySource registerFullBodyRoutineBody(
                PgAbstractFunction routine, String raw, String canonical,
                RoutineBodyRepresentation representation) {
            assertAttached(routine);
            assertFalse(routine.hasBodyReference(canonical),
                    () -> "body must stay unpublished before the resolution barrier: "
                            + routine.getQualifiedName());
            deferredRoutineRegistrations.add(routine.getQualifiedName());
            return super.registerFullBodyRoutineBody(routine, raw, canonical, representation);
        }

        @Override
        public RoutineBodySource registerFingerprintRoutineBody(
                PgAbstractFunction routine, long bodyOid, long metadataOrdinal,
                RoutineFingerprint fingerprint, RoutineBodyRepresentation representation) {
            assertAttached(routine);
            deferredRoutineRegistrations.add(routine.getQualifiedName());
            fingerprintRoutineRegistrations.add(routine.getQualifiedName());
            return super.registerFingerprintRoutineBody(
                    routine, bodyOid, metadataOrdinal, fingerprint, representation);
        }

        private static void assertAttached(PgAbstractFunction routine) {
            assertTrue(routine.getParent() instanceof PgSchema,
                    () -> "routine must be attached before body registration: "
                            + routine.getBareName());
            PgSchema schema = (PgSchema) routine.getParent();
            assertSame(routine, schema.getFunction(routine.getName()),
                    () -> "routine must be the final schema object: "
                            + routine.getQualifiedName());
        }

        private List<String> getOrderedRoutineTasks() {
            return List.copyOf(orderedRoutineTasks);
        }

        private List<String> getDeferredRoutineRegistrations() {
            return List.copyOf(deferredRoutineRegistrations);
        }

        private List<String> getFingerprintRoutineRegistrations() {
            return List.copyOf(fingerprintRoutineRegistrations);
        }

        private List<IAnalysisLauncher> getAnalysisLaunchersBeforeAnalyze() {
            return List.copyOf(analysisLaunchersBeforeAnalyze);
        }
    }

    private static final class RoutineExchangeProbe {

        private final boolean rejectResiduals;
        private final AtomicInteger transportCreateCalls = new AtomicInteger();
        private final AtomicInteger fetchCalls = new AtomicInteger();
        private final AtomicInteger fetchedBodyCount = new AtomicInteger();
        private final AtomicInteger maxFetchedBodyCount = new AtomicInteger();
        private final AtomicInteger transportCloseCalls = new AtomicInteger();
        private final AtomicReference<RecordingPgJdbcLoader> loader = new AtomicReference<>();

        private RoutineExchangeProbe(boolean rejectResiduals) {
            this.rejectResiduals = rejectResiduals;
        }
    }
}
