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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The alternatives of {@code sequence_body} ({@code SQLParser.g4:1429-1439}) as
 * an {@code ALTER SEQUENCE} states them. Of the nine, one had a writer
 * ({@code OWNED BY}) and one is read by neither statement on purpose
 * ({@code SEQUENCE NAME}, a name rather than a property); the other seven -
 * {@code AS type}, {@code INCREMENT}, {@code MINVALUE}, {@code MAXVALUE},
 * {@code START}, {@code CACHE} and {@code CYCLE} - parsed and reached no writer.
 *
 * <p>
 * The sequence's counterpart to {@link PgAlterTableColumnPropertyTest}, which
 * closed the same gap for the sequence behind an identity column, and the same
 * machinery: silence in a {@code CREATE} means the default, silence in an
 * {@code ALTER} means keep what is there.
 *
 * <p>
 * Measured before the fix, none of the seven was merely dropped - each made the
 * tool write back the state the file had just left: {@code INCREMENT BY 5}
 * produced {@code INCREMENT BY 1}, {@code AS smallint} produced
 * {@code AS bigint}, {@code CACHE 7} produced {@code CACHE 1},
 * {@code MAXVALUE 100} produced {@code NO MAXVALUE}, {@code MINVALUE 5}
 * produced {@code NO MINVALUE} with a {@code START WITH 1}, {@code START
 * WITH 5} produced {@code START WITH 1} and {@code CYCLE} produced
 * {@code NO CYCLE}.
 *
 * <p>
 * The fourteen pairs asserted equal below are pairs of states PostgreSQL 17.10
 * itself arrives at. Each {@code CREATE} plus {@code ALTER} and the inline
 * spelling beside it were run against a live server and their rows in
 * {@code pg_sequences} compared column by column - start value, minimum,
 * maximum, increment, cache, cycle and type - and all fourteen came back equal,
 * so the equality asserted here is the server's and not this tool's idea of one.
 * The {@code OWNED BY} case is the one not covered that way, ownership not being
 * a column of that view; it is asserted on the model instead.
 */
class PgAlterSequenceTest {

    private static final String SCHEMA = "public";
    private static final String SEQUENCE = "s";

    // -------------------------------------------------------------- INCREMENT

    /**
     * The defect, stated directly: a file that alters the increment must have the
     * increment reach the database. Unread, the model kept the default the
     * {@code CREATE} gave it and the tool proposed setting the sequence back to
     * it.
     */
    @Test
    void theIncrementStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s INCREMENT BY 5;""");
        String script = pipeline(load("CREATE SEQUENCE public.s INCREMENT BY 5;"), byAlter);
        assertEquals("", script.trim(),
                () -> "an increment written by ALTER must build the same sequence, got:\n" + script);
    }

    /** The other direction: against a database still incrementing by one. */
    @Test
    void theIncrementStatedByAlterReachesTheDatabase() throws Exception {
        String script = pipeline(load("CREATE SEQUENCE public.s;"), load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s INCREMENT BY 5;"""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER SEQUENCE public.s
                \tINCREMENT BY 5;""", script.trim());
    }

    // ---------------------------------------------------------------- AS type

    /**
     * The data type, which the model keeps in a field of its own and the
     * {@code CREATE} writes as {@code AS smallint}.
     */
    @Test
    void theTypeStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s AS smallint;""");
        assertEquals("smallint", sequenceOf(byAlter).getDataType(),
                "the type the file states must be the type the model carries");

        String script = pipeline(load("CREATE SEQUENCE public.s AS smallint;"), byAlter);
        assertEquals("", script.trim(),
                () -> "a type written by ALTER must build the same sequence, got:\n" + script);
    }

    /**
     * The type is read before the bounds are merged, because the boundary a bound
     * is measured against is the boundary of the new type. Measured on
     * PostgreSQL 17.10: {@code CREATE SEQUENCE s MAXVALUE 32767} followed by
     * {@code ALTER SEQUENCE s AS smallint} leaves {@code max_value = 32767}, which
     * is exactly the state {@code CREATE SEQUENCE s AS smallint} arrives at - the
     * maximum stops being one the DDL has to name.
     */
    @Test
    void theTypeIsReadBeforeTheBoundsAreMerged() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s MAXVALUE 32767;

                ALTER SEQUENCE public.s AS smallint;""");
        String script = pipeline(load("CREATE SEQUENCE public.s AS smallint;"), byAlter);
        assertEquals("", script.trim(),
                () -> "a maximum at the new type's boundary is the boundary, got:\n" + script);
    }

    // ------------------------------------------------------------------ CACHE

    /** The cache, which a {@code CREATE} naming none leaves at one. */
    @Test
    void theCacheStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s CACHE 7;""");
        String script = pipeline(load("CREATE SEQUENCE public.s CACHE 7;"), byAlter);
        assertEquals("", script.trim(),
                () -> "a cache written by ALTER must build the same sequence, got:\n" + script);
    }

    // ------------------------------------------------------------------ CYCLE

    /** Cycling, which a sequence does not do until something says so. */
    @Test
    void theCycleStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s CYCLE;""");
        String script = pipeline(load("CREATE SEQUENCE public.s CYCLE;"), byAlter);
        assertEquals("", script.trim(),
                () -> "a cycle written by ALTER must build the same sequence, got:\n" + script);
    }

    /**
     * And the word that takes it away, which is one alternative of the grammar
     * with {@code CYCLE} and has to be told from it.
     */
    @Test
    void noCycleStatedByAlterTakesTheCycleAway() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s CYCLE;

                ALTER SEQUENCE public.s NO CYCLE;""");
        String script = pipeline(load("CREATE SEQUENCE public.s;"), byAlter);
        assertEquals("", script.trim(),
                () -> "NO CYCLE must take the cycle away, got:\n" + script);
    }

    // --------------------------------------------------------------- MAXVALUE

    /** An upper bound the {@code CREATE} does not name. */
    @Test
    void theMaximumStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s MAXVALUE 100;""");
        String script = pipeline(load("CREATE SEQUENCE public.s MAXVALUE 100;"), byAlter);
        assertEquals("", script.trim(),
                () -> "a maximum written by ALTER must build the same sequence, got:\n" + script);
    }

    /**
     * {@code NO MAXVALUE} is a value the statement states, not an option it fails
     * to mention - the difference an alter has to make and a create does not,
     * where a maximum left unsaid is simply the boundary of the type. The two are
     * one alternative of the grammar, so nothing but this distinction tells them
     * apart.
     */
    @Test
    void noMaxvalueStatedByAlterTakesTheMaximumAway() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s MAXVALUE 100;

                ALTER SEQUENCE public.s NO MAXVALUE;""");
        String script = pipeline(load("CREATE SEQUENCE public.s;"), byAlter);
        assertEquals("", script.trim(),
                () -> "NO MAXVALUE must take the maximum away, got:\n" + script);
    }

    // --------------------------------------------------------------- MINVALUE

    /**
     * A lower bound. The start value is written into the fixture because
     * PostgreSQL refuses the statement without it - {@code CREATE SEQUENCE s}
     * followed by {@code ALTER SEQUENCE s MINVALUE 5} is
     * {@code ERROR: START value (1) cannot be less than MINVALUE (5)}, measured -
     * so a fixture starting at one would assert about DDL no server accepts.
     */
    @Test
    void theMinimumStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s START WITH 10;

                ALTER SEQUENCE public.s MINVALUE 5;""");
        String script = pipeline(load("CREATE SEQUENCE public.s START WITH 10 MINVALUE 5;"), byAlter);
        assertEquals("", script.trim(),
                () -> "a minimum written by ALTER must build the same sequence, got:\n" + script);
    }

    /** And its own {@code NO}, the twin of {@code NO MAXVALUE}. */
    @Test
    void noMinvalueStatedByAlterTakesTheMinimumAway() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s START WITH 10 MINVALUE 5;

                ALTER SEQUENCE public.s NO MINVALUE;""");
        String script = pipeline(load("CREATE SEQUENCE public.s START WITH 10;"), byAlter);
        assertEquals("", script.trim(),
                () -> "NO MINVALUE must take the minimum away, got:\n" + script);
    }

    // ------------------------------------------------------------------ START

    /**
     * The start value, which is not the value the sequence is next to hand out -
     * see {@link #restartStatesNoPartOfTheDdl} below.
     */
    @Test
    void theStartValueStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s START WITH 9;""");
        String script = pipeline(load("CREATE SEQUENCE public.s START WITH 9;"), byAlter);
        assertEquals("", script.trim(),
                () -> "a start value written by ALTER must build the same sequence, got:\n" + script);
    }

    // ------------------------------------------------- the whole statement

    /**
     * The half that tells an alter from a create: the statement names one option
     * and the sequence keeps every other one. Filling the sequence the way a
     * {@code CREATE} fills it would reset the cache, the maximum and the cycle
     * this fixture states, because a create starts from the defaults and an alter
     * starts from what is there. Measured on the server, which keeps them too.
     */
    @Test
    void optionsTheStatementDoesNotNameAreKept() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s CACHE 7 MAXVALUE 100 CYCLE;

                ALTER SEQUENCE public.s INCREMENT BY 5;""");
        String script = pipeline(load("CREATE SEQUENCE public.s INCREMENT BY 5 CACHE 7 MAXVALUE 100 CYCLE;"),
                byAlter);
        assertEquals("", script.trim(),
                () -> "the option the file states must land and the ones it does not must stay, got:\n" + script);
    }

    /**
     * The clause is {@code sequence_body*}, so one {@code ALTER SEQUENCE} may
     * carry several options and every one of them has to land.
     */
    @Test
    void severalOptionsInOneStatementAllLand() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s INCREMENT BY 5 CACHE 7 MAXVALUE 100;""");
        String script = pipeline(load("CREATE SEQUENCE public.s INCREMENT BY 5 CACHE 7 MAXVALUE 100;"), byAlter);
        assertEquals("", script.trim(),
                () -> "every option of one statement must land, got:\n" + script);
    }

    // --------------------------------------------------------------- OWNED BY

    /**
     * The one alternative that had a writer before the rest were given one. It is
     * written by the shared routine now, so this pins that the move did not lose
     * it - and so do thirty-four tests that were already there, from
     * {@code PgDiffTest} to the containerized loaders: measured by making the
     * branch inert for alters, which reddens them all.
     *
     * <p>
     * The other half of the statement - the reference it registers for the
     * <i>table</i> the column belongs to - is pinned by
     * {@code PgObjReferencesTest} over {@code dependency.sql:93}, whose expected
     * output carries it at {@code dependency_refs.txt:43}; removing the
     * registration reddens exactly that one case. Not {@code sequence.sql}, which
     * writes {@code OWNED BY} in its {@code CREATE}s only - a claim this comment
     * made until the mutation named the file itself.
     */
    @Test
    void theOwnerStatedByAlterStillReachesTheModel() throws Exception {
        PgDatabase byAlter = load("""
                CREATE TABLE public.t (c integer);

                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s OWNED BY public.t.c;""");
        assertNotNull(sequenceOf(byAlter).getOwnedBy(), "the owning column must reach the model");
        assertTrue(byAlter.getObjReferences().values().stream().flatMap(Set::stream)
                .filter(location -> location.getLineNumber() == 5)
                .map(ObjectLocation::getObjectReference)
                .filter(Objects::nonNull)
                .anyMatch(ref -> DbObjType.TABLE == ref.type() && "t".equals(ref.table())),
                "and the table it belongs to must stay a reference of this statement");

        PgDatabase inline = load("""
                CREATE TABLE public.t (c integer);

                CREATE SEQUENCE public.s OWNED BY public.t.c;""");
        assertTrue(sequenceOf(inline).compare(sequenceOf(byAlter)),
                "an owner written by ALTER must build the same sequence");
    }

    /**
     * The other half of that alternative: the word that takes the owner away.
     * {@code OWNED BY NONE} is to {@code OWNED BY} what {@code NO MAXVALUE} is to
     * {@code MAXVALUE} - a statement rather than silence - and it reached no
     * writer at all, so the owning column stayed where the {@code CREATE} put it.
     * <p>
     * Measured on PostgreSQL 17.10: {@code ALTER SEQUENCE s OWNED BY NONE}
     * removes the {@code deptype = 'a'} row {@code pg_depend} held for the owning
     * column, which is the state {@code CREATE SEQUENCE s} without an owner is
     * in.
     */
    @Test
    void theOwnerIsTakenAwayByOwnedByNone() throws Exception {
        PgDatabase byAlter = load("""
                CREATE TABLE public.t (c integer);

                CREATE SEQUENCE public.s OWNED BY public.t.c;

                ALTER SEQUENCE public.s OWNED BY NONE;""");
        assertNull(sequenceOf(byAlter).getOwnedBy(), "OWNED BY NONE must take the owning column away");

        PgDatabase unowned = load("""
                CREATE TABLE public.t (c integer);

                CREATE SEQUENCE public.s;""");
        String script = pipeline(unowned, byAlter);
        assertEquals("", script.trim(),
                () -> "a sequence disowned by ALTER must be the sequence that never had an owner, got:\n" + script);
    }

    /**
     * The statement stated here is one pgcodekeeper writes itself:
     * {@code PgSequence.compareSequenceBody} emits {@code OWNED BY NONE} when the
     * owner goes away, and {@code alter_sequence_owned_by_diff.sql} carries it as
     * expected output. So this is a migration the tool generated and could not
     * read back - the same shape {@code ALTER CONSTRAINT} was in before 10b.
     * <p>
     * Asserted as the round trip rather than on the model: the script the tool
     * writes is appended to the project it was written against, and the result
     * has to be the project it was written to reach.
     * <p>
     * Both sides name an owner because disowning a sequence is guarded by
     * {@code ActionsToScriptConverter}, which refuses to move ownership between a
     * table and a sequence whose owners it cannot match - without the
     * {@code OWNER TO} lines this fixture fails for that reason instead of the
     * one it is about, which is the trap of a fixture that is incomplete for the
     * pipeline rather than for the field under test.
     */
    @Test
    void theOwnedByNoneTheToolWritesIsReadBack() throws Exception {
        String owned = """
                CREATE TABLE public.t (c integer);

                ALTER TABLE public.t OWNER TO owner;

                CREATE SEQUENCE public.s OWNED BY public.t.c;

                ALTER SEQUENCE public.s OWNER TO owner;""";
        PgDatabase target = load("""
                CREATE TABLE public.t (c integer);

                ALTER TABLE public.t OWNER TO owner;

                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s OWNER TO owner;""");

        String migration = pipeline(load(owned), target);
        assertTrue(migration.contains("OWNED BY NONE"),
                () -> "the tool writes OWNED BY NONE to disown a sequence, got:\n" + migration);

        PgDatabase migrated = load(owned + "\n\n" + migration);
        String script = pipeline(target, migrated);
        assertEquals("", script.trim(),
                () -> "applying the tool's own migration must reach the project it was written for, got:\n"
                        + script);
    }

    // ----------------------------------------------- SET LOGGED / UNLOGGED

    /**
     * Not an alternative of {@code sequence_body} but a sibling of it in
     * {@code alter_sequence_statement} ({@code SQLParser.g4:602-610}), and it had
     * the same hole. The clause is applied to the sequence behind an identity
     * column only; for a sequence the schema itself holds, the parser found the
     * object and returned without writing anything.
     * <p>
     * Measured on PostgreSQL 17.10, so the equality asserted here is the
     * server's: {@code CREATE SEQUENCE s} followed by
     * {@code ALTER SEQUENCE s SET UNLOGGED} leaves {@code pg_class.relpersistence
     * = 'u'}, which is the state {@code CREATE UNLOGGED SEQUENCE s} is created
     * in.
     */
    @Test
    void theLoggedStateStatedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s SET UNLOGGED;""");
        assertFalse(sequenceOf(byAlter).isLogged(), "SET UNLOGGED must reach the model");

        String script = pipeline(load("CREATE UNLOGGED SEQUENCE public.s;"), byAlter);
        assertEquals("", script.trim(),
                () -> "a sequence unlogged by ALTER must be the sequence created unlogged, got:\n" + script);
    }

    /**
     * The defect stated as what it costs: against a database that is already
     * unlogged, the unread clause made the tool write the sequence back to the
     * state the file had just left it. Measured before the change - this produced
     * {@code ALTER SEQUENCE public.s SET LOGGED}.
     */
    @Test
    void theLoggedStateStatedByAlterIsNotWrittenBack() throws Exception {
        String script = pipeline(load("CREATE UNLOGGED SEQUENCE public.s;"), load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s SET UNLOGGED;"""));
        assertEquals("", script.trim(),
                () -> "the tool must not write back the state the file left, got:\n" + script);
    }

    /** And the other direction, so the two words are told apart rather than assumed. */
    @Test
    void theOtherWordIsReadAsTheOtherState() throws Exception {
        PgDatabase byAlter = load("""
                CREATE UNLOGGED SEQUENCE public.s;

                ALTER SEQUENCE public.s SET LOGGED;""");
        assertTrue(sequenceOf(byAlter).isLogged(), "SET LOGGED must reach the model too");

        String script = pipeline(load("CREATE SEQUENCE public.s;"), byAlter);
        assertEquals("", script.trim(),
                () -> "a sequence logged by ALTER must be the sequence created logged, got:\n" + script);
    }

    /**
     * The clause still reaches the database when it has to. The point of reading
     * the value is not that the statement falls silent - a file that unlogs a
     * sequence the database still logs has to produce the statement that unlogs
     * it.
     */
    @Test
    void theLoggedStateStatedByAlterReachesTheDatabase() throws Exception {
        String script = pipeline(load("CREATE SEQUENCE public.s;"), load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s SET UNLOGGED;"""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER SEQUENCE public.s SET UNLOGGED;""", script.trim());
    }

    /**
     * The statement used to raise on a regular sequence rather than ignore it -
     * the loop below the early return looks for an identity column of that name
     * and threw {@code UnresolvedReferenceException} when it found none, so the
     * whole file failed to load. That is the parsing error upstream {@code
     * a4c6662b} fixed with the early return this change gave a writer to, and
     * reading the value must not bring the failure back.
     * <p>
     * The load helper asserts an empty error list, so this is the assertion, and
     * the reference the statement registers is checked as well: a sequence that
     * is only altered is still an object this file names.
     */
    @Test
    void aRegularSequenceStillLoadsClean() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s SET UNLOGGED;""");
        assertTrue(byAlter.getObjReferences().values().stream().flatMap(Set::stream)
                .map(ObjectLocation::getObjectReference)
                .filter(Objects::nonNull)
                .anyMatch(ref -> DbObjType.SEQUENCE == ref.type() && SEQUENCE.equals(ref.getName())),
                "the statement still registers the sequence it names");
    }

    // -------------------------------------------------- what states nothing

    /**
     * {@code RESTART} is not {@code START WITH}. It sets the value the sequence is
     * next to hand out, which is state of the database and not of the DDL - the
     * model has no field for it, and writing it into the start value would put a
     * number in the {@code CREATE} the file never declared. The identity clause
     * of {@code ALTER TABLE} reads the word the same way.
     */
    @Test
    void restartStatesNoPartOfTheDdl() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s RESTART WITH 100;""");
        String script = pipeline(load("CREATE SEQUENCE public.s;"), byAlter);
        assertEquals("", script.trim(),
                () -> "RESTART states no part of the DDL, got:\n" + script);
    }

    /**
     * A rename changes the identity of the sequence and not its content, and a
     * project file writes the name it means in the {@code CREATE} itself - the
     * same reading {@code ALTER TABLE} and {@code ALTER DOMAIN} give it. So the
     * statement is deliberately not applied, and the point of asserting it is
     * that it is not applied by halves either: the sequence keeps the name it was
     * created with, and no second one appears.
     */
    @Test
    void aRenameIsLeftAlone() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s RENAME TO s2;""");
        PgSchema schema = (PgSchema) byAlter.getSchema(SCHEMA);
        assertNotNull(schema.getSequence(SEQUENCE), "the sequence keeps the name the CREATE gave it");
        assertNull(schema.getSequence("s2"), "and the new name reaches nothing");
    }

    /** {@code SET SCHEMA} is the same decision about the other half of the name. */
    @Test
    void aSchemaChangeIsLeftAlone() throws Exception {
        PgDatabase byAlter = load("""
                CREATE SCHEMA sc;

                CREATE SEQUENCE public.s;

                ALTER SEQUENCE public.s SET SCHEMA sc;""");
        assertNotNull(((PgSchema) byAlter.getSchema(SCHEMA)).getSequence(SEQUENCE),
                "the sequence keeps the schema the CREATE put it in");
        assertNull(((PgSchema) byAlter.getSchema("sc")).getSequence(SEQUENCE),
                "and the named schema gets nothing");
    }

    // ------------------------------------------------------------------ hash

    /**
     * The start value is part of {@link PgSequence#computeHash}, and
     * {@code hashCode()} caches its answer until a setter drops it - so a setter
     * that writes the field without dropping the cached hash leaves two different
     * sequences hashing alike. Every route through this class reaches
     * {@code setMinMaxInc} after {@code setStartWith} and that one does drop it,
     * which is what kept the hole shut; this asserts the setter's own contract
     * rather than the order its callers happen to keep.
     */
    @Test
    void aStartValueInvalidatesTheHash() {
        PgSequence sequence = new PgSequence(SEQUENCE);
        sequence.setMinMaxInc(1, null, null, "bigint", 0);
        int before = sequence.hashCode();
        sequence.setStartWith("9");
        assertTrue(before != sequence.hashCode(),
                "a start value the hash is built from must invalidate the cached hash");
    }

    // -------------------------------------------------------------- helpers

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        PgDatabase db = new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "alter sequence test", settings).load();
        assertTrue(settings.getErrors().isEmpty(),
                () -> "the fixture must load clean, got: " + settings.getErrors());
        return db;
    }

    private static PgSequence sequenceOf(PgDatabase db) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA);
        PgSequence sequence = schema == null ? null : schema.getSequence(SEQUENCE);
        assertNotNull(sequence, "no sequence was parsed");
        return sequence;
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
