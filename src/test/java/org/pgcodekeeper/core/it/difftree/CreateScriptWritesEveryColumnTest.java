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
package org.pgcodekeeper.core.it.difftree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * Hiding lives in the comparison. It does not live in the generation.
 * <p>
 * A {@code type=COLUMN} rule says a column is not pgCodeKeeper's to migrate, so
 * the comparison never reports it as added, dropped or altered, see
 * {@link HiddenColumnIsNeverDroppedTest}. What the generator is then asked to
 * write it writes whole: the truth of a migration script is the project, and a
 * {@code CREATE TABLE} states the table exactly as the project declares it,
 * whether the script creates a database from nothing or adds one table to a
 * database that is already there.
 * <p>
 * That also settles the coupling this used to get wrong in the other direction.
 * Everything a column carries beside its definition is written by a statement of
 * its own that names it - a comment, a statistics target, a storage mode, a
 * privilege, a named {@code NOT NULL} constraint, a foreign option, an identity
 * sequence - and every one of those fails outright against a table created
 * without the column. A script cannot be half right: either a column and all its
 * statements are written or none of them are. Now that the column is always
 * written, so is all of it.
 * <p>
 * Every case is asserted against the very same fixture rendered with no ignore
 * list at all, byte for byte. An ignore list can only change what a comparison
 * finds; it may not change how anything is written.
 *
 * @see HiddenColumnMigrationTest for what the rule does to a table both states
 * hold
 */
class CreateScriptWritesEveryColumnTest {

    /**
     * The columns of the real ignore list this was written for, so that a rule
     * naming several columns is exercised the way it is actually written.
     */
    private static final List<String> AUDIT_COLUMNS = List.of(
            "s_audit_id_create", "s_audit_id_modif", "s_create_date", "s_creator", "s_modif_date", "s_owner");

    private static final String NOTHING = "CREATE SCHEMA public;";

    /**
     * The shape reported from a live run: a column carrying a comment and a
     * statistics target, and nothing that would keep it under management.
     */
    private static final String COMMENTED_AND_ANALYSED = """
            CREATE TABLE public.t (
                link integer NOT NULL,
                s_create_date timestamp without time zone
            );

            ALTER TABLE ONLY public.t ALTER COLUMN s_create_date SET STATISTICS 500;

            COMMENT ON COLUMN public.t.link IS 'Идентификатор';

            COMMENT ON COLUMN public.t.s_create_date IS 'Дата создания';""";

    /** What the table above produces: the column, and every statement about it. */
    private static final String WITH_THE_WHOLE_COLUMN = """
            SET search_path = pg_catalog;

            CREATE TABLE public.t (
            \tlink integer NOT NULL,
            \ts_create_date timestamp without time zone
            );

            ALTER TABLE ONLY public.t ALTER COLUMN s_create_date SET STATISTICS 500;

            COMMENT ON COLUMN public.t.link IS 'Идентификатор';

            COMMENT ON COLUMN public.t.s_create_date IS 'Дата создания';""";

    /** A table the migration does not touch, to add a database around the one it does. */
    private static final String NEIGHBOUR = """
            CREATE TABLE public.already_there (
                id bigint NOT NULL
            );""";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    /**
     * The defect this replaced, read the other way round: the column stays in the
     * body of the {@code CREATE} and so do the statements about it, and the
     * script runs.
     */
    @Test
    void aCreatedTableWritesItsColumnAndEveryStatementAboutIt() throws IOException, InterruptedException {
        assertScriptIs(WITH_THE_WHOLE_COLUMN, script(NOTHING, COMMENTED_AND_ANALYSED, auditHidden()));
    }

    /**
     * The same table added to a database that already holds another one, since a
     * table is created by the same code either way and the promise must not
     * depend on how the database around it got there.
     */
    @Test
    void aTableAddedToAnExistingDatabaseWritesThemToo() throws IOException, InterruptedException {
        assertScriptIs(WITH_THE_WHOLE_COLUMN, script(NOTHING + "\n\n" + NEIGHBOUR,
                NEIGHBOUR + "\n\n" + COMMENTED_AND_ANALYSED, auditHidden()));
    }

    /**
     * The control that makes the two cases above measure the rules rather than
     * the fixture: the very same states with no rule in sight produce the very
     * same bytes.
     */
    @Test
    void theRulesChangeNothingAboutHowATableIsCreated() throws IOException, InterruptedException {
        assertScriptIs(script(NOTHING, COMMENTED_AND_ANALYSED, new CoreSettings()),
                script(NOTHING, COMMENTED_AND_ANALYSED, auditHidden()));
    }

    /**
     * Every other statement a column of a PostgreSQL table can carry outside the
     * body of its {@code CREATE}, on one table, so that one run proves the whole
     * list rather than the one that was reported.
     */
    @Test
    void aCreatedTableWritesEveryClauseOfEveryColumn() throws IOException, InterruptedException {
        String decorated = """
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_create_date timestamp without time zone,
                    s_creator text CONSTRAINT doc_creator_nn NOT NULL,
                    s_owner text,
                    s_modif_date timestamp without time zone
                );

                ALTER TABLE public.doc ADD CONSTRAINT doc_owner_nn NOT NULL s_owner NOT VALID;

                COMMENT ON CONSTRAINT doc_creator_nn ON public.doc IS 'Автор';

                ALTER TABLE ONLY public.doc ALTER COLUMN s_create_date SET STATISTICS 500;

                ALTER TABLE ONLY public.doc ALTER COLUMN s_modif_date SET STORAGE PLAIN;

                ALTER TABLE ONLY public.doc ALTER COLUMN s_creator SET (n_distinct=100);

                COMMENT ON COLUMN public.doc.s_create_date IS 'Дата создания';

                GRANT SELECT(s_owner) ON TABLE public.doc TO test_user;""";

        String created = assertRulesChangeNothing(decorated);

        assertTrue(created.contains("SET STATISTICS 500"), created);
        assertTrue(created.contains("SET STORAGE PLAIN"), created);
        // the quoted spelling the model holds a storage parameter value under,
        // the one every value read from a catalog already carries - see
        // PgParserAbstract.fillStorageParam
        assertTrue(created.contains("SET (n_distinct='100')"), created);
        assertTrue(created.contains("COMMENT ON COLUMN public.doc.s_create_date"), created);
        assertTrue(created.contains("GRANT SELECT(s_owner)"), created);
        assertTrue(created.contains("doc_owner_nn"), created);
    }

    /**
     * An identity column is a column with a sequence written inside the file of
     * its table, so writing the column has to bring the sequence with it.
     */
    @Test
    void anIdentityColumnKeepsItsSequence() throws IOException, InterruptedException {
        String created = assertRulesChangeNothing("""
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_audit_id_create bigint NOT NULL
                );

                ALTER TABLE public.doc ALTER COLUMN s_audit_id_create ADD GENERATED ALWAYS AS IDENTITY (
                    SEQUENCE NAME public.doc_audit_seq
                    START WITH 1
                    INCREMENT BY 1
                    NO MINVALUE
                    NO MAXVALUE
                    CACHE 1
                );""");

        assertTrue(created.contains("ADD GENERATED ALWAYS AS IDENTITY"), created);
        assertTrue(created.contains("SEQUENCE NAME public.doc_audit_seq"), created);
    }

    /**
     * A table of a composite type states only the columns it overrides. The
     * override is a part of what the project declares, so it is written like any
     * other and the parentheses keep something to hold.
     */
    @Test
    void aTypedTableKeepsTheOverridesItDeclares() throws IOException, InterruptedException {
        String created = assertRulesChangeNothing("""
                CREATE TYPE public.audit_stamp AS (
                    s_creator text,
                    s_create_date timestamp without time zone
                );

                CREATE TABLE public.doc OF public.audit_stamp (
                    s_creator WITH OPTIONS DEFAULT 'nobody'
                );""");

        assertTrue(created.contains("CREATE TABLE public.doc OF public.audit_stamp ("), created);
        assertTrue(created.contains("s_creator WITH OPTIONS DEFAULT 'nobody'"), created);
    }

    /**
     * Every column the project declares is named in the script, whichever way the
     * script is reached. The assertion is on the name alone, so it also catches a
     * clause nobody thought to write a case for.
     */
    @Test
    void everyColumnTheProjectDeclaresIsNamedInTheScript() throws IOException, InterruptedException {
        for (String sql : List.of(COMMENTED_AND_ANALYSED, NEIGHBOUR + "\n\n" + COMMENTED_AND_ANALYSED)) {
            String created = script(NOTHING, sql, auditHidden());
            for (String column : AUDIT_COLUMNS) {
                if (sql.contains(column)) {
                    assertTrue(created.contains(column),
                            "a column the project declares belongs in the script: " + column + ":\n" + created);
                }
            }
        }
    }

    /**
     * Creates the given state out of nothing twice, with the rules and without
     * them, and demands the two scripts be the same bytes.
     *
     * @return the script the rules produced, for a case that wants to look at it
     */
    private String assertRulesChangeNothing(String sql) throws IOException, InterruptedException {
        String created = script(NOTHING, sql, auditHidden());
        assertScriptIs(script(NOTHING, sql, new CoreSettings()), created);
        return created;
    }

    private static CoreSettings auditHidden() {
        CoreSettings settings = new CoreSettings();
        AUDIT_COLUMNS.forEach(column -> settings.getIgnoreList()
                .add(new IgnoredObject(column, null, false, false, false, false, EnumSet.of(DbObjType.COLUMN))));
        return settings;
    }

    private String script(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        LoadedComparison loaded = load(oldSql, newSql, settings);
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings);
    }

    private LoadedComparison load(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        settings.clearErrors();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> provider.getDumpLoader(source(oldSql), "old", sideSettings),
                        sideSettings -> provider.getDumpLoader(source(newSql), "new", sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        assertNotNull(loaded.newDatabase(), "fixture must load");
        return loaded;
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertScriptIs(String expected, String actual) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8),
                () -> "script must be byte for byte:\nexpected\n" + expected + "\nactual\n" + actual);
    }
}
