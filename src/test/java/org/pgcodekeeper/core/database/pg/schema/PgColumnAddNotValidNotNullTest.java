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
package org.pgcodekeeper.core.database.pg.schema;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Adding a column that carries a {@code NOT NULL} constraint marked
 * {@code NOT VALID} - the one shape of not-null that cannot be written inside
 * the column definition.
 * <p>
 * {@code PgColumn.getCreationSQL} merges the {@code DEFAULT} and the
 * {@code NOT NULL} into the {@code ADD COLUMN} it writes, which is worth doing:
 * a not-null column needs its rows filled anyway, so the merge costs nothing.
 * The merge used to be decided by the mere presence of the constraint, while
 * the text it writes leaves a {@code NOT VALID} one out - the server takes it
 * only as a separate {@code ALTER TABLE ... ADD CONSTRAINT ... NOT VALID},
 * measured on PostgreSQL 18.4: a column definition spelling
 * {@code NOT NULL NOT VALID} is a syntax error there and on 17.10 alike.
 * <p>
 * So the constraint was written nowhere at all. It is not a child of the diff
 * tree - it hangs off the column, and the column is not a container - so no
 * other generator would have picked it up, and the branch that does know the
 * shape ({@code compareNotNull}, which asks
 * {@code PgConstraintNotNull.isComplexNotNull}) was skipped for exactly the
 * same reason the constraint was dropped from the definition. The migration
 * then left the database short of a constraint the project declares, and only
 * a second run - one that no longer had to add the column - put it back.
 * <p>
 * The table's own creation path never had the defect:
 * {@code PgAbstractTable.appendNotNullTableConstraints} already sends a
 * {@code NOT VALID} constraint to {@code getCreationSQL} of its own.
 */
class PgColumnAddNotValidNotNullTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "t1";
    private static final String COLUMN_NAME = "c1";
    private static final String CONSTRAINT_NAME = "c1_nn";

    /**
     * The fixture itself, before anything is asked of the generator: the file
     * really does put a {@code NOT VALID} not-null on the column. A spelling
     * the parser dropped on the floor would make every assertion below hold for
     * the wrong reason.
     */
    @Test
    void theFixtureReallyCarriesANotValidNotNull() throws Exception {
        var constraint = columnOf(withColumn()).getNotNullConstraint();
        assertTrue(constraint != null && constraint.isNotValid(),
                "the project file must reach the model as a NOT VALID not-null constraint");
    }

    /**
     * The defect: the whole migration for a column the database does not have.
     */
    @Test
    void addingTheColumnCarriesItsConstraintAlong() throws Exception {
        String script = pipeline(withoutColumn(), withColumn());

        assertTrue(script.contains("ADD COLUMN " + COLUMN_NAME),
                () -> "the column must be added, got:\n" + script);
        assertTrue(script.contains("ADD CONSTRAINT " + CONSTRAINT_NAME + " NOT NULL " + COLUMN_NAME + " NOT VALID"),
                () -> "and its NOT VALID constraint must be added too, got:\n" + script);
        assertFalse(script.contains(COLUMN_NAME + " integer NOT NULL"),
                () -> "and never inside the column definition, which no server accepts, got:\n" + script);
    }

    /**
     * The same column with a default beside the constraint. The default has to
     * survive the split, and the rows have to be filled before a constraint
     * that will one day be validated is put on them.
     */
    @Test
    void aDefaultBesideItSurvivesTheSplit() throws Exception {
        String script = pipeline(withoutColumn(), withColumn("DEFAULT 5 "));

        assertTrue(script.contains("SET DEFAULT 5"),
                () -> "the default must still be written, got:\n" + script);
        assertTrue(script.contains("ADD CONSTRAINT " + CONSTRAINT_NAME + " NOT NULL " + COLUMN_NAME + " NOT VALID"),
                () -> "and so must the constraint, got:\n" + script);
    }

    /**
     * The mutation guard: an ordinary not-null must still be merged into the
     * {@code ADD COLUMN}, or the fix would have been "stop merging". The merge
     * is what keeps a column with a default from earning a table-wide
     * {@code UPDATE}.
     */
    @Test
    void anOrdinaryNotNullIsStillMergedIntoTheAddColumn() throws Exception {
        String script = pipeline(withoutColumn(), withPlainNotNull(""));

        assertTrue(script.contains("ADD COLUMN " + COLUMN_NAME + " integer DEFAULT 5 NOT NULL"),
                () -> "an ordinary not-null must still ride inside the ADD COLUMN, got:\n" + script);
        assertFalse(script.contains("UPDATE "),
                () -> "and must not earn a table-wide UPDATE, got:\n" + script);
    }

    /**
     * The boundary of the fix, on the other side. A named not-null is a
     * "complex" one too, and it is still merged: a column definition takes
     * {@code CONSTRAINT name NOT NULL} perfectly well. Only {@code NOT VALID}
     * has nowhere to go there.
     */
    @Test
    void aNamedNotNullIsStillMergedToo() throws Exception {
        String script = pipeline(withoutColumn(), withPlainNotNull("CONSTRAINT " + CONSTRAINT_NAME + " "));

        assertTrue(script.contains("ADD COLUMN " + COLUMN_NAME + " integer DEFAULT 5 CONSTRAINT "
                + CONSTRAINT_NAME + " NOT NULL"),
                () -> "a named not-null must still ride inside the ADD COLUMN, got:\n" + script);
        assertFalse(script.contains("UPDATE "),
                () -> "and must not earn a table-wide UPDATE either, got:\n" + script);
    }

    private static PgColumn columnOf(PgDatabase db) {
        var schema = (PgSchema) db.getChild(SCHEMA_NAME, DbObjType.SCHEMA);
        var table = (PgAbstractTable) schema.getStatementContainer(TABLE_NAME);
        return (PgColumn) table.getColumn(COLUMN_NAME);
    }

    private static PgDatabase withoutColumn() throws Exception {
        return loadProjectFile("CREATE TABLE %s.%s (id integer);\n".formatted(SCHEMA_NAME, TABLE_NAME));
    }

    private static PgDatabase withColumn() throws Exception {
        return withColumn("");
    }

    /**
     * The column, its constraint spelled the only way a server takes it - which
     * is also the way this very tool exports such a constraint.
     *
     * @param columnOptions what the column definition carries besides its type
     */
    private static PgDatabase withColumn(String columnOptions) throws Exception {
        return loadProjectFile("""
                CREATE TABLE %1$s.%2$s (
                    id integer,
                    %3$s integer %5$s
                );

                ALTER TABLE %1$s.%2$s
                    ADD CONSTRAINT %4$s NOT NULL %3$s NOT VALID;
                """.formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, CONSTRAINT_NAME, columnOptions));
    }

    /**
     * @param constraintClause what precedes the {@code NOT NULL} in the column
     *                         definition - empty, or a name for it
     */
    private static PgDatabase withPlainNotNull(String constraintClause) throws Exception {
        return loadProjectFile("""
                CREATE TABLE %1$s.%2$s (
                    id integer,
                    %3$s integer DEFAULT 5 %4$sNOT NULL
                );
                """.formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, constraintClause));
    }

    private static PgDatabase loadProjectFile(String sql) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "add column not valid not null test", settings()).load();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, settings());
    }

    /**
     * A named not-null constraint, and a {@code NOT VALID} one at that, is
     * PostgreSQL 18 syntax on both sides of the tool.
     */
    private static CoreSettings settings() {
        var settings = new CoreSettings();
        settings.setVersion(PgSupportedVersion.VERSION_18);
        return settings;
    }
}
