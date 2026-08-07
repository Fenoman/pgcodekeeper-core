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
package org.pgcodekeeper.core.database.api.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.database.pg.loader.PgProjectLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractTable;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Guards {@link ITable#columnAdder}, the widening that every model walk which
 * collects dependency edges has to apply.
 * <p>
 * A column is not a child of its table. It hangs off {@link ITable#getColumns()}
 * and nowhere else, so {@code getDescendants()} alone never yields one, and a
 * walk that forgets the widening quietly loses every dependency edge a column
 * default contributes. That loss is invisible from the model: a statement
 * neither compares nor hashes its dependencies, so a model with edges and the
 * same model with none are equal and hash alike. It is also invisible to any
 * check that counts the model with the same walk, because such a check shrinks
 * by exactly as much as the thing it is checking.
 * <p>
 * The expected column count below is therefore derived by hand, from the schema
 * and table maps of the model, and never through {@code getDescendants()} or a
 * helper built on it. An oracle that shared the walk under test would confirm a
 * defective walk instead of catching it.
 * <p>
 * The guard lives beside the helper rather than beside one of its callers:
 * {@code columnAdder} is a model-traversal contract relied upon by the
 * dependency graph, the dependency finder, {@link IDatabase#listObjects()} and
 * the analysis replay alike, and it must keep holding when any one of them is
 * rewritten.
 */
class ColumnAdderWalkTest {

    /**
     * A schema whose tables carry a known number of columns, plus objects that
     * are reachable by the plain child walk (a constraint, an index) and one
     * relation that is not a table (a view), so that the difference between the
     * two walks can only be the table columns.
     */
    private static final Map<String, String> PROJECT = Map.of(
            "SCHEMA/app/app.sql", "CREATE SCHEMA app;",
            "SCHEMA/app/TABLE/customer.sql", """
                    CREATE TABLE app.customer (
                        id bigint NOT NULL,
                        name text NOT NULL,
                        city text
                    );

                    ALTER TABLE app.customer
                        ADD CONSTRAINT customer_pkey PRIMARY KEY (id);""",
            "SCHEMA/app/TABLE/orders.sql", """
                    CREATE TABLE app.orders (
                        id bigint NOT NULL,
                        customer_id bigint NOT NULL,
                        placed_at timestamptz,
                        total numeric
                    );

                    CREATE INDEX orders_customer_idx ON app.orders (customer_id);""",
            "SCHEMA/app/VIEW/customer_cities.sql", """
                    CREATE VIEW app.customer_cities AS
                        SELECT c.id, c.city FROM app.customer AS c;""");

    /** customer has three columns, orders has four. */
    private static final long EXPECTED_COLUMNS = 7;

    /**
     * The reason the widening exists: the plain walk cannot see a single column,
     * so anything that reads dependencies off a column has to widen first.
     */
    @Test
    void theChildWalkNeverYieldsAColumn(@TempDir Path root) throws Exception {
        PgDatabase database = load(root);

        assertEquals(0, database.getDescendants()
                .filter(st -> st.getStatementType() == DbObjType.COLUMN).count(),
                "a column must not be reachable as a descendant of its table");
    }

    /**
     * The widened walk yields the plain walk plus every table column, no more
     * and no less. Both bounds matter: the lower one catches a widening that
     * stopped reaching columns, the exact one catches a widening that started
     * duplicating statements, which would inflate every count built on it.
     */
    @Test
    void theWidenedWalkAddsExactlyTheTableColumns(@TempDir Path root)
            throws Exception {
        PgDatabase database = load(root);

        long plain = database.getDescendants().count();
        long widened = database.getDescendants()
                .flatMap(ITable::columnAdder).count();
        long columns = countColumnsWithoutTheWalk(database);

        assertEquals(EXPECTED_COLUMNS, columns,
                "the fixture must keep carrying table columns");
        assertTrue(widened > plain,
                "the widened walk must reach more than the plain child walk,"
                        + " otherwise no column is reachable at all");
        assertEquals(columns, widened - plain,
                "the widening must add exactly the table columns");
        assertEquals(columns, database.getDescendants()
                .flatMap(ITable::columnAdder)
                .filter(st -> st.getStatementType() == DbObjType.COLUMN).count(),
                "and everything it adds must be a column");
    }

    /**
     * Counts columns without touching the walk under test: the database hands
     * out its schemas, a schema hands out its tables and a table hands out its
     * columns, each from its own map. This is the whole point of the test - an
     * expectation computed through {@code getDescendants()} or through any
     * production helper built on it would agree with a defective walk.
     *
     * @param database model to count
     * @return number of columns of all tables of all schemas
     */
    private static long countColumnsWithoutTheWalk(PgDatabase database) {
        long columns = 0;
        for (PgSchema schema : database.getSchemas()) {
            for (PgAbstractTable table : schema.getTables()) {
                columns += table.getColumns().size();
            }
        }
        return columns;
    }

    private static PgDatabase load(Path root) throws Exception {
        for (Map.Entry<String, String> file : PROJECT.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.createDirectories(Objects.requireNonNull(path.getParent()));
            Files.writeString(path, file.getValue() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }
        var settings = new CoreSettings();
        settings.setInCharsetName(StandardCharsets.UTF_8.name());
        return new PgProjectLoader(root, settings).load();
    }
}
