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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * A {@code type=COLUMN} rule hides a column only while nothing at all needs it,
 * and the database does not end at the table the column belongs to.
 * <p>
 * A view selecting the column, a sequence owned by it, an extended statistics
 * object gathered on it and a row policy reading it are all written as objects
 * of their own, outside the {@code CREATE TABLE}. Each of them fails outright
 * against a table created without the column, so each of them holds the column
 * under management exactly as a key or an index inside the table does.
 *
 * @see HiddenColumnMigrationTest for the users that live inside the table
 */
class HiddenColumnOutsideUserTest {

    private static final List<String> AUDIT_COLUMNS = List.of(
            "s_audit_id_create", "s_audit_id_modif", "s_create_date", "s_creator", "s_modif_date", "s_owner");

    private static final String NOTHING = "CREATE SCHEMA public;";

    private static final String DOC = """
            CREATE TABLE public.doc (
                id bigint NOT NULL,
                s_creator text
            );""";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    /**
     * The case measured on a live database: 150 views select the six audit
     * columns, and a project that hides them cannot create its own database.
     */
    @Test
    void aColumnAViewSelectsStaysManaged() throws IOException, InterruptedException {
        assertColumnStaysManaged(DOC + """


                CREATE VIEW public.doc_v AS
                    SELECT id, s_creator FROM public.doc;""", "s_creator");
    }

    /**
     * A sequence records the column it belongs to in a field of its own rather
     * than as a dependency, so this one is held by nothing the reverse index
     * would find in {@code getDependencies}.
     */
    @Test
    void aColumnASequenceIsOwnedByStaysManaged() throws IOException, InterruptedException {
        assertColumnStaysManaged(DOC + """


                CREATE SEQUENCE public.doc_seq;

                ALTER SEQUENCE public.doc_seq OWNED BY public.doc.s_creator;""", "s_creator");
    }

    /**
     * Extended statistics are a child of the schema, not of the table they read,
     * so the table alone cannot see that the column is spoken for.
     */
    @Test
    void aColumnStatisticsAreGatheredOnStaysManaged() throws IOException, InterruptedException {
        assertColumnStaysManaged(DOC + """


                CREATE STATISTICS public.doc_stat ON id, s_creator FROM public.doc;""", "s_creator");
    }

    /**
     * A policy is a child of its table, and the class that decides all this
     * promises in as many words that a policy keeps its column. The promise was
     * empty: the analysis of a policy expression ran without the namespace of the
     * table and resolved no column at all.
     */
    @Test
    void aColumnAPolicyReadsStaysManaged() throws IOException, InterruptedException {
        assertColumnStaysManaged(DOC + """


                CREATE POLICY doc_pol ON public.doc USING ((s_creator = CURRENT_USER));""", "s_creator");
    }

    /**
     * The analysis of a policy expression must record what the expression reads,
     * which is what the case above rests on and what every other user of a
     * dependency graph reads too.
     */
    @Test
    void aPolicyRecordsTheColumnItReads() throws IOException, InterruptedException {
        LoadedComparison loaded = load(NOTHING, DOC + """


                CREATE POLICY doc_pol ON public.doc USING ((s_creator = CURRENT_USER));""", new CoreSettings());

        IStatement policy = loaded.newDatabase().getStatement(
                new ObjectReference("public", "doc", "doc_pol", DbObjType.POLICY));
        assertNotNull(policy, "the policy must load");
        assertTrue(policy.getDependencies().contains(
                        new ObjectReference("public", "doc", "s_creator", DbObjType.COLUMN)),
                "a policy must depend on the column it reads: " + policy.getDependencies());
    }

    @Test
    void aColumnAFunctionReadsStaysManaged() throws IOException, InterruptedException {
        assertColumnStaysManaged(DOC + """


                CREATE FUNCTION public.last_creator() RETURNS text
                    LANGUAGE sql
                    AS $$SELECT s_creator FROM public.doc LIMIT 1$$;""", "s_creator");
    }

    /**
     * The point of the whole inversion still holds one table over: a column
     * nothing anywhere names is still left out of the comparison. Without this
     * the fix above would be indistinguishable from giving the rule up
     * altogether.
     */
    @Test
    void aTableNothingReferencesStillLosesTheColumn() throws IOException, InterruptedException {
        String twoTables = """
                CREATE TABLE public.needs_it (
                    id bigint NOT NULL,
                    s_creator %1$s
                );

                CREATE VIEW public.needs_it_v AS
                    SELECT id, s_creator FROM public.needs_it;

                CREATE TABLE public.spares_it (
                    id bigint NOT NULL,
                    s_creator %1$s
                );""";

        String migrated = script(twoTables.formatted("text"),
                twoTables.formatted("character varying(100)"), auditHidden());

        assertTrue(migrated.contains("ALTER TABLE public.needs_it"),
                "the table a view reads migrates the column:\n" + migrated);
        assertFalse(migrated.contains("public.spares_it"),
                "the table nothing reads says nothing about it:\n" + migrated);
    }

    /**
     * A rule that applies to one table and not to the next says so, and names the
     * object that held the column - which is now as likely to live outside the
     * table as inside it.
     */
    @Test
    void theObjectOutsideTheTableIsNamedAsTheReason() throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        LoadedComparison loaded = load(NOTHING, DOC + """


                CREATE VIEW public.doc_v AS
                    SELECT id, s_creator FROM public.doc;""", settings);

        assertEquals(Map.of("s_creator", "public.doc_v"),
                ColumnVisibility.of(settings).pinnedColumns(table(loaded, "doc")),
                "the view that holds the column must be named");
    }

    /**
     * All four forms at once, which is how a real project meets them, and the
     * assertion that matters to whoever runs the migration: every column
     * something outside its table names is migrated, and the one column nothing
     * names is not.
     * <p>
     * Every column of the table changes in the very same way, so the two answers
     * are the users speaking and nothing else.
     */
    @Test
    void everyOutsideUserHoldsItsColumnAtOnce() throws IOException, InterruptedException {
        String schema = """
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_create_date %1$s,
                    s_creator %1$s,
                    s_modif_date %1$s,
                    s_owner %1$s,
                    s_audit_id_create %1$s
                );

                CREATE VIEW public.doc_v AS
                    SELECT id, s_creator FROM public.doc;

                CREATE SEQUENCE public.doc_seq;

                ALTER SEQUENCE public.doc_seq OWNED BY public.doc.s_owner;

                CREATE STATISTICS public.doc_stat ON id, s_create_date FROM public.doc;

                CREATE POLICY doc_pol ON public.doc USING ((s_modif_date IS NOT NULL));""";

        String migrated = script(schema.formatted("text"),
                schema.formatted("character varying(100)"), auditHidden());

        for (String column : List.of("s_create_date", "s_creator", "s_modif_date", "s_owner")) {
            assertTrue(migrated.contains("ALTER COLUMN " + column + " TYPE character varying(100)"),
                    "the column its database needs must be migrated: " + column + "\n" + migrated);
        }
        assertFalse(migrated.contains("s_audit_id_create"),
                "and the one nothing names must not be:\n" + migrated);
    }

    private static CoreSettings auditHidden() {
        CoreSettings settings = new CoreSettings();
        AUDIT_COLUMNS.forEach(column -> settings.getIgnoreList()
                .add(new IgnoredObject(column, null, false, false, false, false, EnumSet.of(DbObjType.COLUMN))));
        return settings;
    }

    /**
     * A column something outside its table needs stays under management although
     * a rule names it, so the comparison goes on migrating it as it always did.
     * <p>
     * Asked of the rules directly rather than of a script. A script is written
     * from the project whatever the rules say, see
     * {@link CreateScriptWritesEveryColumnTest}, so it cannot tell a column the
     * rules kept from one they let go.
     */
    private void assertColumnStaysManaged(String sql, String column) throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        LoadedComparison loaded = load(NOTHING, sql, settings);

        assertTrue(ColumnVisibility.of(settings).pinnedColumns(table(loaded, "doc")).containsKey(column),
                "the column its database needs must stay managed although a rule names it");
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

    private static ITable table(LoadedComparison loaded, String name) {
        return (ITable) loaded.newDatabase().getStatement(
                new ObjectReference("public", name, DbObjType.TABLE));
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }
}
