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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractRegularTable;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractTable;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The seven alternatives of {@code ALTER TABLE} that state a property of the
 * table itself a {@code CREATE TABLE} could have stated: {@code SET
 * LOGGED/UNLOGGED}, {@code SET TABLESPACE}, {@code SET ACCESS METHOD},
 * {@code SET (...)}, {@code RESET (...)}, {@code SET WITHOUT CLUSTER} and
 * {@code INHERIT}/{@code NO INHERIT}, plus the {@code OPTIONS} of a foreign
 * table. All of them parsed and reached no writer.
 *
 * <p>
 * The Greenplum {@code SET DISTRIBUTED BY} joined them afterwards. It states a
 * property of the table the same way, but is spelled at statement level rather
 * than as one of the {@code table_action}s, so it is a branch of its own rather
 * than one more line in {@code fillTableProperty}.
 *
 * <p>
 * The criterion throughout is the one tasks 8c and 10c used: the model an
 * {@code ALTER} builds must be the model the equivalent {@code CREATE} builds,
 * because the database side has one answer for both spellings. So nearly every
 * case here loads two project files and asks for an empty script - and asks the
 * comparison as well wherever an empty script cannot see the difference on its
 * own.
 *
 * <p>
 * Two of the alternatives come in pairs writing one field - {@code SET} and
 * {@code RESET} both write the options, {@code INHERIT} and {@code NO INHERIT}
 * both write the parent list - so each pair also has a case stating both halves
 * in one statement. Measured on PostgreSQL 17.10: the actions of one
 * {@code ALTER TABLE} are applied left to right, so
 * {@code SET (fillfactor=70), RESET (fillfactor)} leaves no option behind while
 * {@code RESET (fillfactor), SET (fillfactor=90)} leaves 90, and
 * {@code INHERIT p, NO INHERIT p} leaves no parent.
 */
class PgAlterTablePropertyTest {

    private static final String SCHEMA = "public";
    private static final String TABLE = "t";

    // ----------------------------------------------------------- SET LOGGED

    /**
     * The defect, stated directly: a project file that unlogs a table must have
     * that reach the database. Unread, the model kept the table logged and the
     * tool proposed logging the database's table back.
     */
    @Test
    void anUnloggedTableStatedByAlterReachesTheModel() throws Exception {
        PgDatabase inline = load("CREATE UNLOGGED TABLE public.t (\n\tc1 integer\n);");
        PgDatabase byAlter = load(table("c1 integer") + """

                ALTER TABLE public.t SET UNLOGGED;""");

        assertFalse(((PgAbstractRegularTable) tableOf(byAlter)).isLogged(),
                "the persistence the file states must be the persistence the model carries");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "an UNLOGGED written by ALTER must build the same table");
    }

    /** The other half of the same alternative. */
    @Test
    void aLoggedTableStatedByAlterReachesTheModel() throws Exception {
        PgDatabase inline = load(table("c1 integer"));
        PgDatabase byAlter = load("CREATE UNLOGGED TABLE public.t (\n\tc1 integer\n);" + """


                ALTER TABLE public.t SET LOGGED;""");

        assertTrue(((PgAbstractRegularTable) tableOf(byAlter)).isLogged(),
                "the persistence the file states must be the persistence the model carries");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "a LOGGED written by ALTER must build the same table");
    }

    /**
     * The identity sequence goes with the table, because the server takes it
     * along: measured on PostgreSQL 17.10, {@code ALTER TABLE t SET UNLOGGED}
     * leaves {@code pg_class.relpersistence} of {@code t_c1_seq} at {@code u}.
     *
     * <p>
     * Not an extra the branch may skip. The generator writes the difference
     * out - {@code PgAbstractRegularTable.writeSequences} appends an
     * {@code ALTER SEQUENCE ... SET LOGGED} whenever the two disagree - so a
     * sequence left behind is a statement in the migration, which is what the
     * empty script below is asked about.
     */
    @Test
    void theIdentitySequenceFollowsTheTableIntoUnlogged() throws Exception {
        PgDatabase inline = load("CREATE UNLOGGED TABLE public.t (\n\tc1 integer GENERATED ALWAYS AS IDENTITY\n);");
        PgDatabase byAlter = load(table("c1 integer GENERATED ALWAYS AS IDENTITY") + """

                ALTER TABLE public.t SET UNLOGGED;""");

        assertFalse(tableOf(byAlter).getColumn("c1").getSequence().isLogged(),
                "the identity sequence must carry the persistence the table ends up with");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "an unlogged identity column must build the same table");
    }

    // ------------------------------------------------------- SET TABLESPACE

    /**
     * A tablespace stated by {@code ALTER}. Unread, the model kept the default
     * one and the tool proposed moving the database's table back to it - the
     * generator writes {@code SET TABLESPACE pg_default} for a table whose
     * model has none.
     */
    @Test
    void aTablespaceStatedByAlterReachesTheModel() throws Exception {
        PgDatabase inline = load("CREATE TABLE public.t (\n\tc1 integer\n)\nTABLESPACE ts;");
        PgDatabase byAlter = load(table("c1 integer") + """

                ALTER TABLE public.t SET TABLESPACE ts;""");

        assertTrue(creationSql(byAlter).contains("TABLESPACE ts"),
                () -> "the tablespace the file states must be the one the model carries, got:\n"
                        + creationSql(byAlter));
        assertEquals("", pipeline(inline, byAlter).trim(),
                "a tablespace written by ALTER must build the same table");
    }

    // --------------------------------------------------- SET ACCESS METHOD

    /**
     * An access method stated by {@code ALTER}. Both the comparison and the
     * script are asked, because the two see it differently: the model compares
     * unequal, while the script carries a whole recreate of the table.
     */
    @Test
    void anAccessMethodStatedByAlterReachesTheModel() throws Exception {
        PgDatabase inline = load("CREATE TABLE public.t (\n\tc1 integer\n)\nUSING columnar;");
        PgDatabase byAlter = load(table("c1 integer") + """

                ALTER TABLE public.t SET ACCESS METHOD columnar;""");

        assertTrue(tableOf(inline).compare(tableOf(byAlter)),
                "an access method written by ALTER must build the same table");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "and must therefore need no migration");
    }

    /**
     * {@code DEFAULT} is the default of the file, not a fixed name: measured on
     * PostgreSQL 17.10 against a server carrying a second table access method,
     * {@code SET ACCESS METHOD DEFAULT} leaves {@code pg_class.relam} at the
     * one {@code default_table_access_method} names, and back at {@code heap}
     * once the setting is reset - while a plain {@code CREATE TABLE} under that
     * setting lands on it too, which is the parity this case is about. The
     * listener already tracks the setting for the {@code CREATE TABLE} beside
     * it, so the two spellings answer to the same word.
     */
    @Test
    void theDefaultAccessMethodIsTheOneTheFileSet() throws Exception {
        String setting = "SET default_table_access_method = 'columnar';\n\n";
        PgDatabase inline = load(setting + table("c1 integer"));
        PgDatabase byAlter = load(setting + "CREATE TABLE public.t (\n\tc1 integer\n)\nUSING heap;" + """


                ALTER TABLE public.t SET ACCESS METHOD DEFAULT;""");

        assertTrue(tableOf(inline).compare(tableOf(byAlter)),
                "DEFAULT must mean the access method the file set");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "and must therefore need no migration");
    }

    /**
     * And a file that names no default of its own, where the word means the
     * method a {@code CREATE TABLE} of that file gets - the one the model
     * starts at.
     */
    @Test
    void theDefaultAccessMethodOfAFileThatSetsNoneIsTheModelsOwn() throws Exception {
        PgDatabase inline = load(table("c1 integer"));
        PgDatabase byAlter = load("CREATE TABLE public.t (\n\tc1 integer\n)\nUSING columnar;" + """


                ALTER TABLE public.t SET ACCESS METHOD DEFAULT;""");

        assertTrue(tableOf(inline).compare(tableOf(byAlter)),
                "DEFAULT must take the table back to the method a plain CREATE builds");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "and must therefore need no migration");
    }

    // -------------------------------------------------- SET / RESET options

    /** A storage parameter stated by {@code ALTER}. */
    @Test
    void storageParametersStatedByAlterReachTheModel() throws Exception {
        PgDatabase inline = load("CREATE TABLE public.t (\n\tc1 integer\n)\nWITH (fillfactor=70);");
        PgDatabase byAlter = load(table("c1 integer") + """

                ALTER TABLE public.t SET (fillfactor=70);""");

        // quoted because that is the one spelling both sides of a comparison
        // reach: the value arrives from a catalog quoted by
        // PgParserAbstract.fillOptionParams, so a file stating it bare is read
        // into the same text - see fillStorageParam
        assertEquals(Map.of("fillfactor", "'70'"), tableOf(byAlter).getOptions(),
                "the parameter the file states must be the one the model carries");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "a storage parameter written by ALTER must build the same table");
    }

    /**
     * {@code OIDS} keeps its own route through the storage parameters, the one
     * {@code CREATE TABLE} gives it: it is a field of the table and not an
     * entry in its option map, and the generator writes it back as
     * {@code OIDS=true} from that field.
     */
    @Test
    void theOidsParameterKeepsItsOwnRoute() throws Exception {
        PgDatabase inline = load("CREATE TABLE public.t (\n\tc1 integer\n)\nWITH (OIDS=true);");
        PgDatabase byAlter = load(table("c1 integer") + """

                ALTER TABLE public.t SET (OIDS=true);""");

        assertTrue(tableOf(byAlter).getOptions().isEmpty(),
                "OIDS is not an option of the map, it is a field of the table");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "OIDS written by ALTER must build the same table");
    }

    /** And the {@code toast.} prefix, which the same route builds. */
    @Test
    void aToastParameterKeepsItsPrefix() throws Exception {
        PgDatabase inline = load("CREATE TABLE public.t (\n\tc1 text\n)\nWITH (toast.autovacuum_enabled=false);");
        PgDatabase byAlter = load(table("c1 text") + """

                ALTER TABLE public.t SET (toast.autovacuum_enabled=false);""");

        assertEquals(Map.of("toast.autovacuum_enabled", "'false'"), tableOf(byAlter).getOptions(),
                "a toast parameter must be held under the name the CREATE holds it under");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "a toast parameter written by ALTER must build the same table");
    }

    /**
     * The other half of the pair. A file that resets a parameter states the
     * table ends up without it, so the model has to end up without it too -
     * unread, the tool proposed setting the database's value back.
     */
    @Test
    void aResetStorageParameterLeavesTheTable() throws Exception {
        PgDatabase inline = load(table("c1 integer"));
        PgDatabase byAlter = load("CREATE TABLE public.t (\n\tc1 integer\n)\nWITH (fillfactor=70);" + """


                ALTER TABLE public.t RESET (fillfactor);""");

        assertTrue(tableOf(byAlter).getOptions().isEmpty(),
                "the reset parameter must leave the model");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "a table whose parameter the file resets must equal one that never had it");
    }

    /** A reset asked under the prefixed name the option is held under. */
    @Test
    void aToastParameterIsResetUnderThePrefixedName() throws Exception {
        PgDatabase byAlter = load("CREATE TABLE public.t (\n\tc1 text\n)\nWITH (toast.autovacuum_enabled=false);"
                + """


                        ALTER TABLE public.t RESET (toast.autovacuum_enabled);""");

        assertTrue(tableOf(byAlter).getOptions().isEmpty(),
                () -> "a toast parameter must be resettable under its own name, left: "
                        + tableOf(byAlter).getOptions());
    }

    /**
     * The pair in one statement. Measured on PostgreSQL 17.10, the actions of
     * one {@code ALTER TABLE} are applied left to right:
     * {@code SET (fillfactor=70), RESET (fillfactor)} leaves
     * {@code reloptions} empty, and the same two the other way round leave the
     * value the {@code SET} names.
     */
    @Test
    void theTwoHalvesOfTheOptionsPairAreAppliedInOrder() throws Exception {
        PgDatabase setThenReset = load(table("c1 integer") + """

                ALTER TABLE public.t SET (fillfactor=70), RESET (fillfactor);""");
        assertTrue(tableOf(setThenReset).getOptions().isEmpty(),
                () -> "a RESET after a SET must leave nothing, got: " + tableOf(setThenReset).getOptions());

        PgDatabase resetThenSet = load("CREATE TABLE public.t (\n\tc1 integer\n)\nWITH (fillfactor=70);" + """


                ALTER TABLE public.t RESET (fillfactor), SET (fillfactor=90);""");
        assertEquals(Map.of("fillfactor", "'90'"), tableOf(resetThenSet).getOptions(),
                "a SET after a RESET must leave the value it names");
    }

    /**
     * Resetting a parameter the table does not carry is silence, as it is on
     * the server - measured on 17.10, {@code RESET} of an option that was never
     * set raises nothing.
     */
    @Test
    void resettingAParameterTheTableNeverHadIsSilent() throws Exception {
        var settings = new CoreSettings();
        PgDatabase db = load(table("c1 integer") + "\n\nALTER TABLE public.t RESET (fillfactor);", settings);
        assertTrue(settings.getErrors().isEmpty(),
                () -> "a reset of an unset parameter must be silent, got: " + settings.getErrors());
        assertTrue(tableOf(db).getOptions().isEmpty(), "and must leave the option map alone");
    }

    // -------------------------------------------------- SET WITHOUT CLUSTER

    /**
     * The other half of the pair whose {@code CLUSTER ON} half has a writer
     * already. Unread, a file that stopped clustering a table went on
     * describing it as clustered.
     */
    @Test
    void withoutClusterClearsAClusteredIndex() throws Exception {
        String declaration = table("c1 integer") + """

                CREATE INDEX t_c1_idx ON public.t (c1);""";
        PgDatabase inline = load(declaration);
        PgDatabase byAlter = load(declaration + """


                ALTER TABLE public.t CLUSTER ON t_c1_idx;

                ALTER TABLE public.t SET WITHOUT CLUSTER;""");

        assertFalse(tableOf(byAlter).isClustered(), "the cluster the file removes must leave the model");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "a table the file unclusters must equal one that was never clustered");
    }

    /**
     * And the second place a table keeps a clustered object, the one
     * {@code isClustered} has to look in as well: a primary key. A table whose
     * only clustered object is a key has no clustered index at all, so a check
     * that only walks the indexes misses it.
     */
    @Test
    void withoutClusterClearsAClusteredPrimaryKey() throws Exception {
        String declaration = table("c1 integer,\n\tCONSTRAINT t_pkey PRIMARY KEY (c1)");
        PgDatabase inline = load(declaration);
        PgDatabase byAlter = load(declaration + """


                ALTER TABLE public.t CLUSTER ON t_pkey;

                ALTER TABLE public.t SET WITHOUT CLUSTER;""");

        assertFalse(tableOf(byAlter).isClustered(), "the cluster the file removes must leave the model");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "a key the file unclusters must equal one that was never clustered");
    }

    // ------------------------------------------------------ INHERIT / NO INHERIT

    /** A parent stated by {@code ALTER} rather than in the {@code CREATE}. */
    @Test
    void anInheritStatedByAlterReachesTheModel() throws Exception {
        PgDatabase inline = load(parent() + "\n\nCREATE TABLE public.t (\n\tc1 integer\n)\nINHERITS (public.p);");
        PgDatabase byAlter = load(parent() + "\n\n" + table("c1 integer") + """


                ALTER TABLE public.t INHERIT public.p;""");

        assertEquals("[public.p]", tableOf(byAlter).getInherits().stream()
                .map(i -> i.getQualifiedName()).toList().toString(),
                "the parent the file states must be the parent the model carries");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "a parent written by ALTER must build the same table");
    }

    /**
     * The parent has to be created before the table that inherits it, so the
     * statement records the dependency its {@code CREATE} form records - the
     * table's own DDL names the parent from then on.
     */
    @Test
    void anInheritRecordsTheDependencyOnTheParent() throws Exception {
        PgDatabase byAlter = load(parent() + "\n\n" + table("c1 integer") + """


                ALTER TABLE public.t INHERIT public.p;""");

        assertTrue(tableOf(byAlter).getDependencies().stream()
                .anyMatch(dep -> "p".equals(dep.getName())),
                () -> "the parent must be a dependency of the table, got: " + tableOf(byAlter).getDependencies());
    }

    /** The other half of the pair. */
    @Test
    void aNoInheritStatedByAlterReachesTheModel() throws Exception {
        PgDatabase inline = load(parent() + "\n\n" + table("c1 integer"));
        PgDatabase byAlter = load(parent() + "\n\nCREATE TABLE public.t (\n\tc1 integer\n)\nINHERITS (public.p);"
                + """


                        ALTER TABLE public.t NO INHERIT public.p;""");

        assertTrue(tableOf(byAlter).getInherits().isEmpty(), "the parent the file removes must leave the model");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "a table whose parent the file removes must equal one that never had it");
    }

    /**
     * The pair in one statement, applied left to right the way the options pair
     * is - measured on PostgreSQL 17.10, {@code INHERIT p, NO INHERIT p} leaves
     * {@code pg_inherits} empty.
     *
     * <p>
     * The second half is the one that discriminates: the first states an end
     * state a pair of inert branches produces as well, while an implementation
     * that read both branches but not in the written order would answer it the
     * same way and this one differently.
     */
    @Test
    void theTwoHalvesOfTheInheritPairAreAppliedInOrder() throws Exception {
        PgDatabase inheritThenNo = load(parent() + "\n\n" + table("c1 integer") + """


                ALTER TABLE public.t INHERIT public.p, NO INHERIT public.p;""");
        assertTrue(tableOf(inheritThenNo).getInherits().isEmpty(),
                () -> "a NO INHERIT after an INHERIT must leave nothing, got: "
                        + tableOf(inheritThenNo).getInherits());

        PgDatabase noThenInherit = load(parent() + "\n\n" + table("c1 integer") + """


                ALTER TABLE public.t NO INHERIT public.p, INHERIT public.p;""");
        assertEquals("[public.p]", tableOf(noThenInherit).getInherits().stream()
                .map(i -> i.getQualifiedName()).toList().toString(),
                "an INHERIT after a NO INHERIT must leave the parent it names");
    }

    /**
     * The trap the parent table guards. {@code ALTER CONSTRAINT} carries an
     * {@code inherit_option} of its own - that is how a {@code NOT NULL}
     * constraint is made inheritable - and it is the same sub-rule this
     * alternative uses.
     *
     * <p>
     * Measured with the branch keyed on the clause alone: the table keeps its
     * parent, because the detach looks for a parent table the statement does
     * not carry and raises on the missing name - and that is the damage.
     * pgcodekeeper reports a legal {@code ALTER CONSTRAINT ... NO INHERIT} as
     * an unresolved reference, {@code Cannot invoke
     * Schema_qualified_nameContext.identifier() because "qNameCtx" is null},
     * so the assertion that speaks for the guard is the silence rather than the
     * parent.
     */
    @Test
    void anAlterConstraintNoInheritIsNotATableNoInherit() throws Exception {
        var settings = new CoreSettings();
        PgDatabase db = load(parent() + "\n\nCREATE TABLE public.t (\n\tc1 integer CONSTRAINT t_c1_nn NOT NULL\n)"
                + "\nINHERITS (public.p);" + """


                        ALTER TABLE public.t ALTER CONSTRAINT t_c1_nn NO INHERIT;""", settings);

        assertTrue(settings.getErrors().isEmpty(),
                () -> "the statement states nothing about a parent table, got: " + settings.getErrors());
        assertEquals("[public.p]", tableOf(db).getInherits().stream()
                .map(i -> i.getQualifiedName()).toList().toString(),
                "the clause states the constraint's inheritability, not the table's parents");
        assertEquals("NOT NULL c1 NO INHERIT", tableOf(db).getConstraint("t_c1_nn").getDefinition(),
                "and the constraint is where it does state it");
    }

    // ------------------------------------------------------ foreign OPTIONS

    /**
     * The foreign table's own options, where all three verbs of the clause meet
     * in one statement: {@code SET} replaces a value, {@code ADD} states a new
     * one, and {@code DROP} takes one away.
     */
    @Test
    void foreignOptionsStatedByAlterReachTheModel() throws Exception {
        PgDatabase inline = load(server() + """

                CREATE FOREIGN TABLE public.t (
                \tc1 integer
                ) SERVER srv OPTIONS (schema_name 'other', table_name 'remote');""");
        PgDatabase byAlter = load(server() + """

                CREATE FOREIGN TABLE public.t (
                \tc1 integer
                ) SERVER srv OPTIONS (schema_name 'public', updatable 'false');

                ALTER FOREIGN TABLE public.t OPTIONS (SET schema_name 'other', ADD table_name 'remote',
                    DROP updatable);""");

        assertEquals("{schema_name='other', table_name='remote'}", tableOf(byAlter).getOptions().toString(),
                "the options the file states must be the options the model carries");
        assertEquals("", pipeline(inline, byAlter).trim(),
                "options written by ALTER must build the same foreign table");
    }

    // --------------------------------------------------- SET DISTRIBUTED BY

    /**
     * The Greenplum distribution policy, which is a property of the table the
     * way the ones above are, but is spelled at statement level rather than as
     * one of the {@code table_action}s ({@code SQLParser.g4:402}) - so it needs
     * its own branch and shares none of theirs.
     *
     * <p>
     * Unread, it was not merely dropped. The model kept the policy the
     * {@code CREATE} declared and the comparison writes an {@code ALTER} for
     * exactly that disagreement, so against a database already distributed the
     * way the file asks the tool emitted {@code SET WITH (REORGANIZE=true)
     * DISTRIBUTED RANDOMLY} - measured: the statement the file had just left,
     * in reverse, and on Greenplum a full redistribution of the table's data.
     *
     * <p>
     * What the clause is read into is the text
     * {@code PgParserAbstract.parseDistribution} builds for the {@code CREATE},
     * so the two spellings of one policy build one model rather than two
     * strings that only look alike.
     *
     * <p>
     * Which is why the clause here is deliberately not written canonically -
     * lower case, and no space before the parenthesis. Spelled the way the
     * {@code CREATE} spells it, the source text of the clause and the text that
     * call builds are byte-identical and the assertion would pass for a branch
     * that simply took the text as written.
     */
    @Test
    void aDistributionStatedByAlterReachesTheModel() throws Exception {
        PgDatabase inline = load("CREATE TABLE public.t (\n\tc1 integer\n)\nDISTRIBUTED BY (c1);");
        PgDatabase byAlter = load(table("c1 integer") + """


                ALTER TABLE public.t SET distributed by(c1);""");

        assertTrue(creationSql(byAlter).contains("DISTRIBUTED BY (c1)"),
                () -> "the policy the file states must be the one the CREATE writes, got:\n" + creationSql(byAlter));
        assertEquals("", pipeline(inline, byAlter).trim(),
                "a distribution written by ALTER must build the same table");
    }

    /** The other direction: against a database still distributed the old way. */
    @Test
    void aDistributionStatedByAlterReachesTheDatabase() throws Exception {
        String script = pipeline(load("CREATE TABLE public.t (\n\tc1 integer\n)\nDISTRIBUTED RANDOMLY;"),
                load("CREATE TABLE public.t (\n\tc1 integer\n)\nDISTRIBUTED RANDOMLY;" + """


                        ALTER TABLE public.t SET DISTRIBUTED BY (c1);"""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t SET DISTRIBUTED BY (c1);""", script.trim());
    }

    /**
     * The other two forms of the clause, and the one word of it that is not
     * read. {@code REORGANIZE} says whether the server is to move the rows
     * about, which is how it carries the change out and not the policy the
     * table is left under - the same reading {@code NOWAIT} gets beside
     * {@code SET TABLESPACE} and {@code CASCADE} beside a drop. Written into
     * the model it would put the word into a {@code CREATE}, where the clause
     * has no place at all.
     */
    @Test
    void everyFormOfTheClauseLandsAndReorganizeIsNotPartOfIt() throws Exception {
        PgDatabase byAlter = load(table("c1 integer") + """


                ALTER TABLE public.t SET WITH (REORGANIZE=true) DISTRIBUTED REPLICATED;""");
        assertTrue(creationSql(byAlter).contains("DISTRIBUTED REPLICATED"),
                () -> "REORGANIZE states how the server gets there, not where the table ends up, got:\n"
                        + creationSql(byAlter));
        assertEquals("", pipeline(load("CREATE TABLE public.t (\n\tc1 integer\n)\nDISTRIBUTED REPLICATED;"),
                byAlter).trim(), "so the two spellings must build one table");

        PgDatabase randomly = load(table("c1 integer") + """


                ALTER TABLE public.t SET DISTRIBUTED RANDOMLY;""");
        assertTrue(creationSql(randomly).contains("DISTRIBUTED RANDOMLY"),
                () -> "and the third form of the clause must land too, got:\n" + creationSql(randomly));
    }

    // ------------------------------------------------------------- fixtures

    private static String table(String body) {
        return """
                CREATE TABLE public.t (
                \t%s
                );""".formatted(body);
    }

    private static String parent() {
        return """
                CREATE TABLE public.p (
                \tc1 integer
                );""";
    }

    private static String server() {
        return """
                CREATE SERVER srv FOREIGN DATA WRAPPER fdw;
                """;
    }

    // -------------------------------------------------------------- helpers

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return load(sql, new CoreSettings());
    }

    private static PgDatabase load(String sql, CoreSettings settings) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "table property test", settings).load();
    }

    private static PgAbstractTable tableOf(PgDatabase db) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA);
        PgAbstractTable table = schema == null ? null : schema.getTable(TABLE);
        assertNotNull(table, "no table was parsed");
        return table;
    }

    private static String creationSql(PgDatabase db) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, "\n");
        tableOf(db).getCreationSQL(script);
        return script.getFullScript();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
