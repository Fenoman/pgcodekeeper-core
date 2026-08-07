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
package org.pgcodekeeper.core.database.pg.parser.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.parser.FullAnalyze;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Guards the reproducibility of the dependency order produced by
 * {@code PgSelect.groupBy}, and through it of every generated script.
 * <p>
 * The GROUP BY analyzer buffers the dependencies of the grouping expressions and
 * then iterates that buffer to republish them and to derive the implicit primary
 * key dependencies. When the buffer is a plain {@code HashSet}, the iteration
 * follows {@link org.pgcodekeeper.core.database.api.schema.ObjectLocation}
 * hashes, which fold in the record hash of {@link ObjectReference}; its
 * {@link DbObjType} component hashes to the JVM identity hash of an enum
 * constant and therefore differs between runs. The resulting dependency
 * permutation propagates into the dependency graph, into the resolved action
 * order and finally into the emitted {@code -- DEPCY:} blocks, so two runs over
 * the same database produced byte-different scripts.
 */
class PgGroupByDepcyOrderTest {

    private static final PgDatabaseProvider PROVIDER = new PgDatabaseProvider();

    private static final List<String> TABLES =
            List.of("t_alpha", "t_beta", "t_gamma", "t_delta", "t_epsilon", "t_zeta");

    /**
     * Grouping columns are listed in this order, so the implicit primary key
     * dependencies must be published in exactly this order.
     */
    private static final List<String> EXPECTED_PK_ORDER = TABLES.stream()
            .map(table -> "pk_" + table)
            .toList();

    /**
     * Number of structurally identical views and functions in the fixture. Their
     * statements sit at different file offsets, so their location hashes differ:
     * a hash-ordered buffer would iterate them differently from statement to
     * statement, while an insertion-ordered buffer yields the same sequence
     * every time.
     */
    private static final int COPIES = 4;

    /**
     * The primary keys implied by a GROUP BY must be published in grouping
     * order, not in hash order.
     */
    @Test
    void groupByPublishesPrimaryKeyDependenciesInGroupingOrder() throws Exception {
        IDatabase db = loadFixture();

        assertEquals(EXPECTED_PK_ORDER,
                constraintDependencies(db, DbObjType.VIEW, "v_report_1"));
        assertEquals(EXPECTED_PK_ORDER,
                constraintDependencies(db, DbObjType.FUNCTION, "f_report_1()"));
    }

    /**
     * Structurally identical GROUP BY clauses must produce the same dependency
     * order regardless of where they sit in the file. A hash-ordered buffer
     * fails here because the statement offset feeds the location hash.
     */
    @Test
    void identicalGroupByClausesShareDependencyOrder() throws Exception {
        IDatabase db = loadFixture();
        List<List<String>> orders = new ArrayList<>(COPIES * 2);
        for (int i = 1; i <= COPIES; i++) {
            orders.add(constraintDependencies(db, DbObjType.VIEW, "v_report_" + i));
            orders.add(constraintDependencies(db, DbObjType.FUNCTION, "f_report_" + i + "()"));
        }

        for (List<String> order : orders) {
            assertEquals(EXPECTED_PK_ORDER, order, orders::toString);
        }
    }

    /**
     * End-to-end guard: two independent loads of the same source must yield a
     * byte-identical creation script. This covers the whole emission chain -
     * analysis, the concurrent OLD/NEW dependency graph build and action
     * resolution - not just the GROUP BY buffer.
     */
    @Test
    void repeatedCreationScriptGenerationIsByteIdentical() throws Exception {
        String first = createFullScript();
        String second = createFullScript();

        assertFalse(first.isEmpty(), "creation script must not be empty");
        assertTrue(first.contains("-- DEPCY:"), "fixture must exercise dependency comments");
        assertEquals(first, second);
    }

    private static String createFullScript() throws Exception {
        var settings = new CoreSettings();
        return PgCodeKeeperApi.diff(PROVIDER, new PgDatabase(), loadFixture(), settings);
    }

    private static List<String> constraintDependencies(IDatabase db, DbObjType type, String name) {
        IStatement statement = db.getStatement(new ObjectReference("public", name, type));
        assertNotNull(statement, name + " is missing from the fixture");
        return statement.getDependencies().stream()
                .filter(dep -> dep.type() == DbObjType.CONSTRAINT)
                .map(ObjectReference::getName)
                .toList();
    }

    private static IDatabase loadFixture() throws Exception {
        var settings = new CoreSettings();
        String script = fixture();
        var loader = PROVIDER.getDumpLoader(
                () -> new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                "group_by_depcy_order.sql", settings);
        IDatabase db = loader.load();
        FullAnalyze.fullAnalyze(db, settings.getErrors(), settings.getVersion());
        assertTrue(settings.getErrors().isEmpty(), () -> settings.getErrors().toString());
        return db;
    }

    private static String fixture() {
        StringBuilder sb = new StringBuilder("CREATE SCHEMA public;\n\n");
        for (String table : TABLES) {
            sb.append("CREATE TABLE public.").append(table)
                    .append(" (id integer NOT NULL, val integer);\n")
                    .append("ALTER TABLE public.").append(table)
                    .append(" ADD CONSTRAINT pk_").append(table)
                    .append(" PRIMARY KEY (id);\n\n");
        }

        for (int i = 1; i <= COPIES; i++) {
            sb.append("CREATE VIEW public.v_report_").append(i).append(" AS\n")
                    .append(groupingSelect()).append(";\n\n");
        }

        for (int i = 1; i <= COPIES; i++) {
            sb.append("CREATE FUNCTION public.f_report_").append(i)
                    .append("() RETURNS SETOF bigint LANGUAGE sql AS $$\n")
                    .append(groupingSelect()).append(";\n$$;\n\n");
        }

        return sb.toString();
    }

    private static String groupingSelect() {
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int t = 0; t < TABLES.size(); t++) {
            sb.append(t == 0 ? "" : ", ")
                    .append("sum(").append(TABLES.get(t)).append(".val) AS s").append(t);
        }
        sb.append("\nFROM ");
        for (int t = 0; t < TABLES.size(); t++) {
            sb.append(t == 0 ? "" : ", ").append("public.").append(TABLES.get(t));
        }
        sb.append("\nGROUP BY ");
        for (int t = 0; t < TABLES.size(); t++) {
            sb.append(t == 0 ? "" : ", ").append(TABLES.get(t)).append(".id");
        }
        return sb.toString();
    }
}
