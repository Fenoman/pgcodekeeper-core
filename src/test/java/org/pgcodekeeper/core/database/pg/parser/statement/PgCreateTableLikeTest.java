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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractTable;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The third alternative of {@code table_column_def}
 * ({@code SQLParser.g4:1917-1921}): {@code LIKE source [ INCLUDING ... ]}.
 *
 * <p>
 * It parsed and reached no writer at all - {@code fillColumns} tested for the
 * other two alternatives and had no {@code else}, so the loop body was empty
 * and the table came out with zero columns and no dependency on the table it
 * was copied from. Against a database where those columns exist the comparison
 * then wrote an {@code ALTER TABLE ... DROP COLUMN} for every one of them.
 *
 * <p>
 * The criterion here is the server's: {@code LIKE} is a one-time copy that
 * leaves no trace in the catalogue, so the table it defines is exactly the
 * table the equivalent explicit {@code CREATE} defines - which is what a
 * {@code pg_dump} of the result writes out. Every case below therefore loads
 * two project files and asks for an empty script.
 */
class PgCreateTableLikeTest {

    private static final String SCHEMA = "public";

    // ------------------------------------------------------------ columns

    /** The defect, stated directly: a bare {@code LIKE} copies the columns. */
    @Test
    void aLikeClauseBringsTheColumnsOver() throws Exception {
        PgDatabase byLike = load(source("\tid integer NOT NULL,\n\tname text") + """


                CREATE TABLE public.t (
                \tLIKE public.src
                );""");

        assertEquals(2, tableOf(byLike, "t").getColumns().size(), "both columns must be copied");
        assertEquals("", pipeline(load(source("\tid integer NOT NULL,\n\tname text") + """


                CREATE TABLE public.t (
                \tid integer NOT NULL,
                \tname text
                );"""), byLike).trim(),
                "a table defined by LIKE must be the table the explicit CREATE defines");
    }

    /**
     * The columns of a {@code LIKE} take their place among the ones written out
     * beside it, in the order the elements are written - the order the server
     * puts them in, and the order the {@code CREATE} this model writes has to
     * reproduce.
     */
    @Test
    void theCopiedColumnsKeepTheirPlaceAmongTheOthers() throws Exception {
        PgDatabase byLike = load(source("\tid integer") + """


                CREATE TABLE public.t (
                \tbefore_col text,
                \tLIKE public.src,
                \tafter_col text
                );""");

        assertEquals("", pipeline(load(source("\tid integer") + """


                CREATE TABLE public.t (
                \tbefore_col text,
                \tid integer,
                \tafter_col text
                );"""), byLike).trim(),
                "the copied column belongs where the clause stands");
    }

    /**
     * {@code NOT NULL} comes over without being asked for - measured on
     * PostgreSQL 17.10, a bare {@code LIKE} carries it and no option turns it
     * off - but the constraint that carries it is rebuilt rather than copied,
     * because two tables cannot share one constraint name.
     */
    @Test
    void notNullComesOverWithoutBeingAskedFor() throws Exception {
        PgDatabase byLike = load(source("\tid integer NOT NULL") + """


                CREATE TABLE public.t (
                \tLIKE public.src
                );""");

        assertEquals("", pipeline(load(source("\tid integer NOT NULL") + """


                CREATE TABLE public.t (
                \tid integer NOT NULL
                );"""), byLike).trim(),
                "NOT NULL is copied by a bare LIKE");
    }

    // ------------------------------------------------------------ options

    /**
     * A default is copied only when the statement says so. Both halves matter:
     * a default copied without {@code INCLUDING DEFAULTS} is a default the
     * database does not have, and one dropped with it is a default the database
     * does have.
     */
    @Test
    void aDefaultComesOverOnlyWhenIncluded() throws Exception {
        String src = source("\tid integer DEFAULT 7");

        assertEquals("", pipeline(load(src + """


                CREATE TABLE public.t (
                \tid integer DEFAULT 7
                );"""), load(src + """


                CREATE TABLE public.t (
                \tLIKE public.src INCLUDING DEFAULTS
                );""")).trim(), "INCLUDING DEFAULTS must bring the default over");

        assertEquals("", pipeline(load(src + """


                CREATE TABLE public.t (
                \tid integer
                );"""), load(src + """


                CREATE TABLE public.t (
                \tLIKE public.src
                );""")).trim(), "a bare LIKE must leave the default behind");
    }

    /**
     * {@code INCLUDING ALL} names every word at once, which is the form that
     * appears in practice.
     */
    @Test
    void includingAllNamesEveryWord() throws Exception {
        String src = source("\tid integer DEFAULT 7,\n\tpayload text STORAGE EXTERNAL");

        assertEquals("", pipeline(load(src + """


                CREATE TABLE public.t (
                \tid integer DEFAULT 7,
                \tpayload text STORAGE EXTERNAL
                );"""), load(src + """


                CREATE TABLE public.t (
                \tLIKE public.src INCLUDING ALL
                );""")).trim(), "INCLUDING ALL must bring over everything the model holds");
    }

    /**
     * The options are read left to right, as the server reads them, so
     * {@code INCLUDING ALL EXCLUDING DEFAULTS} is every word but that one.
     */
    @Test
    void excludingTakesOneWordBackFromAll() throws Exception {
        String src = source("\tid integer DEFAULT 7,\n\tpayload text STORAGE EXTERNAL");

        assertEquals("", pipeline(load(src + """


                CREATE TABLE public.t (
                \tid integer,
                \tpayload text STORAGE EXTERNAL
                );"""), load(src + """


                CREATE TABLE public.t (
                \tLIKE public.src INCLUDING ALL EXCLUDING DEFAULTS
                );""")).trim(), "EXCLUDING takes back what ALL had named");
    }

    /**
     * A generated column is held in the same field as a default and needs its
     * own word: {@code INCLUDING DEFAULTS} does not bring it over and
     * {@code INCLUDING GENERATED} does.
     */
    @Test
    void aGeneratedColumnNeedsItsOwnWord() throws Exception {
        String src = source("\tid integer,\n\tdoubled integer GENERATED ALWAYS AS ((id * 2)) STORED");

        assertEquals("", pipeline(load(src + """


                CREATE TABLE public.t (
                \tid integer,
                \tdoubled integer GENERATED ALWAYS AS ((id * 2)) STORED
                );"""), load(src + """


                CREATE TABLE public.t (
                \tLIKE public.src INCLUDING GENERATED
                );""")).trim(), "INCLUDING GENERATED must bring the expression over");

        assertEquals("", pipeline(load(src + """


                CREATE TABLE public.t (
                \tid integer,
                \tdoubled integer
                );"""), load(src + """


                CREATE TABLE public.t (
                \tLIKE public.src INCLUDING DEFAULTS
                );""")).trim(), "the word for a default is not the word for a generated column");
    }

    // ------------------------------------------------------- dependency

    /**
     * The clause names a table, so it is a reference this statement makes, and
     * without it nothing tells the migration to create the source first.
     */
    @Test
    void theSourceTableIsADependency() throws Exception {
        PgDatabase byLike = load(source("\tid integer") + """


                CREATE TABLE public.t (
                \tLIKE public.src
                );""");

        assertTrue(tableOf(byLike, "t").getDependencies().stream()
                .anyMatch(dep -> "src".equals(dep.getName())),
                "the table copied from has to be a dependency");
    }

    // ------------------------------------------------------------ guards

    /**
     * A source the schema has not is reported, the way every other unresolved
     * name is - the clause has no word with which to say the table may not be
     * there. A source declared in a file read later is the same case, and this
     * is where it now says so instead of leaving the table empty.
     */
    @Test
    void copyingFromATableTheSchemaHasNotIsReported() throws Exception {
        var settings = new CoreSettings();
        load("""
                CREATE TABLE public.t (
                \tLIKE public.nosuch
                );""", settings);
        assertFalse(settings.getErrors().isEmpty(),
                "a LIKE has no way to say the source may not be there");
    }

    // ---------------------------------------------------------- fixtures

    private static String source(String columns) {
        return """
                CREATE TABLE public.src (
                %s
                );""".formatted(columns);
    }

    // ----------------------------------------------------------- helpers

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return load(sql, new CoreSettings());
    }

    private static PgDatabase load(String sql, CoreSettings settings) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "create table like test", settings).load();
    }

    private static PgAbstractTable tableOf(PgDatabase db, String name) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA);
        PgAbstractTable table = schema == null ? null : schema.getTable(name);
        assertNotNull(table, "no table " + name + " was parsed");
        return table;
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
