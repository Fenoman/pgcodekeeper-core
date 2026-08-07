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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.base.parser.FullAnalyze;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * The comparison's own answer, where the two sides spell one object two ways.
 * <p>
 * The migration script is not the whole of what a difference costs. A statement
 * that answers "changed" is reported as changed whether or not anything is
 * written for it, and its hash is what the incremental build compares - so a
 * spelling that only the script forgives is still an object the tool shows as
 * modified on every run. The diff fixtures next door watch the script; this
 * watches {@code compare} and the hash, and the two together are what the
 * measured circle against PostgreSQL 17.10 closed: a hand-written file applied
 * to a database and read back through the JDBC path compares equal to itself.
 */
class PgSpellingComparisonTest {

    private static final String TABLE = """
            CREATE TABLE public.t (
                id integer,
                c text
            );
            """;

    /**
     * {@code pg_get_indexdef} always prints the access method; a hand-written
     * file usually leaves it to the server.
     */
    @Test
    void anIndexComparesEqualToItsSpelledOutMethod() throws Exception {
        assertSameObject(DbObjType.INDEX, "idx",
                TABLE + "CREATE INDEX idx ON public.t USING btree (c);",
                TABLE + "CREATE INDEX idx ON public.t (c);");
    }

    /** And a method that really differs is really a difference. */
    @Test
    void anIndexOfAnotherMethodComparesUnequal() throws Exception {
        assertDifferentObject(DbObjType.INDEX, "idx",
                TABLE + "CREATE INDEX idx ON public.t (c);",
                TABLE + "CREATE INDEX idx ON public.t USING hash (c);");
    }

    /** The same for the index an exclusion constraint builds. */
    @Test
    void anExcludeConstraintComparesEqualToItsSpelledOutMethod() throws Exception {
        assertSameObject(DbObjType.CONSTRAINT, "ex",
                TABLE + "ALTER TABLE public.t ADD CONSTRAINT ex EXCLUDE USING btree (id WITH =);",
                TABLE + "ALTER TABLE public.t ADD CONSTRAINT ex EXCLUDE (id WITH =);");
    }

    /**
     * {@code pg_get_expr} writes the bound in the server's hand; the manual -
     * and the files written from it - use upper case and no space after the
     * comma.
     */
    @Test
    void aPartitionComparesEqualToTheServersSpellingOfItsBound() throws Exception {
        String hash = """
                CREATE TABLE public.h (
                    id integer
                )
                PARTITION BY HASH (id);

                CREATE TABLE public.h0 PARTITION OF public.h
                %s;
                """;
        assertSameObject(DbObjType.TABLE, "h0",
                hash.formatted("FOR VALUES WITH (modulus 4, remainder 0)"),
                hash.formatted("FOR VALUES WITH (MODULUS 4, REMAINDER 0)"));

        String list = """
                CREATE TABLE public.l (
                    c text
                )
                PARTITION BY LIST (c);

                CREATE TABLE public.l0 PARTITION OF public.l
                %s;
                """;
        assertSameObject(DbObjType.TABLE, "l0",
                list.formatted("FOR VALUES IN ('a', 'b')"),
                list.formatted("for values in ('a','b')"));
    }

    /**
     * A string constant is a value and not a spelling, so the folding must not
     * reach it.
     */
    @Test
    void aPartitionOfAnotherBoundComparesUnequal() throws Exception {
        String list = """
                CREATE TABLE public.l (
                    c text
                )
                PARTITION BY LIST (c);

                CREATE TABLE public.l0 PARTITION OF public.l
                %s;
                """;
        assertDifferentObject(DbObjType.TABLE, "l0",
                list.formatted("FOR VALUES IN ('a', 'b')"),
                list.formatted("FOR VALUES IN ('a', 'B')"));
    }

    /**
     * The storage parameters of a table, of its toast relation and of the
     * primary key, in the two spellings the two sides state them.
     */
    @Test
    void aTableComparesEqualToTheQuotedSpellingOfItsParameters() throws Exception {
        String quoted = """
                CREATE TABLE public.t (
                    id integer,
                    c text,
                    CONSTRAINT pk_t PRIMARY KEY (id) WITH (fillfactor='70')
                )
                WITH (fillfactor='70', autovacuum_enabled='true', toast.autovacuum_enabled='false');
                """;
        String bare = """
                CREATE TABLE public.t (
                    id integer,
                    c text,
                    CONSTRAINT pk_t PRIMARY KEY (id) WITH (fillfactor=70)
                )
                WITH (FILLFACTOR=70, autovacuum_enabled=TRUE, toast.autovacuum_enabled=False);
                """;
        assertSameObject(DbObjType.TABLE, "t", quoted, bare);
        assertSameObject(DbObjType.CONSTRAINT, "pk_t", quoted, bare);
    }

    /** And a parameter that really differs is really a difference. */
    @Test
    void aTableOfAnotherParameterValueComparesUnequal() throws Exception {
        assertDifferentObject(DbObjType.TABLE, "t",
                "CREATE TABLE public.t (id integer) WITH (fillfactor='70');",
                "CREATE TABLE public.t (id integer) WITH (fillfactor=80);");
    }

    private static void assertSameObject(DbObjType type, String name, String left, String right)
            throws Exception {
        IStatement a = find(load(left), type, name);
        IStatement b = find(load(right), type, name);
        assertTrue(a.compare(b), () -> type + " " + name
                + " must compare equal to the same object spelled the other way");
        assertEquals(((AbstractStatement) a).hashIgnoringChildren(),
                ((AbstractStatement) b).hashIgnoringChildren(), () -> type + " " + name
                + " must hash the same, or every incremental build sees a change");
    }

    private static void assertDifferentObject(DbObjType type, String name, String left, String right)
            throws Exception {
        IStatement a = find(load(left), type, name);
        IStatement b = find(load(right), type, name);
        assertTrue(!a.compare(b), () -> type + " " + name + " must still compare unequal");
    }

    private static IStatement find(IDatabase db, DbObjType type, String name) {
        return db.getDescendants()
                .filter(st -> st.getStatementType() == type && name.equals(st.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " " + name + " in the model"));
    }

    private static IDatabase load(String sql) throws Exception {
        var settings = new CoreSettings();
        InputStreamProvider input = () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
        IDatabase db = new PgDatabaseProvider().getDumpLoader(input, "spelling.sql", settings).load();
        FullAnalyze.fullAnalyze(db, settings.getErrors(), settings.getVersion());
        return db;
    }
}
