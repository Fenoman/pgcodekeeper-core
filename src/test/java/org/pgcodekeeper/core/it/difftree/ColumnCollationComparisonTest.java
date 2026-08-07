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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * A column collation is a difference only when the state a migration produces
 * names one.
 * <p>
 * {@code PgColumn.compareTypes} writes the collation of the target state and
 * nothing else: with no collation named there it emits nothing at all, because
 * a column declared without a collation and a column declared with the default
 * collation of its type are the same thing to this tool. Reporting the
 * difference anyway shows the operator a change that no script can carry out.
 * <p>
 * The relaxation is about the columns of a table and about nothing else, so the
 * cases below also pin what must keep its place in the diff: the collation the
 * migration does name, the generated column that is recreated for it, and the
 * child object that the hash alone tells apart.
 * <p>
 * Every case states both what the comparison reports and, byte for byte, the
 * script that follows from it.
 */
class ColumnCollationComparisonTest {

    private static final String WITH_COLLATION = """
            CREATE TABLE public.t (
                c_period text COLLATE pg_catalog."ru_RU"
            );""";

    private static final String WITHOUT_COLLATION = """
            CREATE TABLE public.t (
                c_period text
            );""";

    private static final String OTHER_COLLATION = """
            CREATE TABLE public.t (
                c_period text COLLATE pg_catalog."sv_SE"
            );""";

    private static final String GENERATED_WITH_COLLATION = """
            CREATE TABLE public.t (
                c_source text,
                c_period text COLLATE pg_catalog."ru_RU" GENERATED ALWAYS AS (c_source) STORED
            );""";

    private static final String GENERATED_WITHOUT_COLLATION = """
            CREATE TABLE public.t (
                c_source text,
                c_period text GENERATED ALWAYS AS (c_source) STORED
            );""";

    /**
     * The type change warning is localized, so it is taken from the same source
     * the generator takes it from. Everything else is compared literally.
     */
    private static final String SET_COLLATION_SCRIPT = """
            SET search_path = pg_catalog;

            ALTER TABLE public.t
            \tALTER COLUMN c_period TYPE text COLLATE pg_catalog."ru_RU" USING c_period::text; /* %s */"""
            .formatted(Messages.Table_TypeParameterChange.formatted("public.t", "text", "text"));

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @Test
    void collationOnlyInTheMigrationSourceIsNotADifference() throws IOException, InterruptedException {
        assertNoDifference(WITH_COLLATION, WITHOUT_COLLATION, new CoreSettings());
    }

    /**
     * The same pair compared while the order of the columns is ignored: one
     * relaxation must not swallow the other.
     */
    @Test
    void collationOnlyInTheMigrationSourceIsNotADifferenceIgnoringColumnOrder()
            throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        settings.setIgnoreColumnOrder(true);
        assertNoDifference(WITH_COLLATION, WITHOUT_COLLATION, settings);
    }

    @Test
    void collationNamedByTheMigrationTargetIsAlteredAsBefore() throws IOException, InterruptedException {
        assertScriptIs(SET_COLLATION_SCRIPT,
                assertTableDiffers(WITHOUT_COLLATION, WITH_COLLATION, new CoreSettings()));
    }

    @Test
    void twoNamedCollationsStayADifference() throws IOException, InterruptedException {
        assertScriptIs(SET_COLLATION_SCRIPT,
                assertTableDiffers(OTHER_COLLATION, WITH_COLLATION, new CoreSettings()));
    }

    /**
     * A generated column is recreated on any collation change, so there the
     * difference is migratable and must stay visible.
     */
    @Test
    void generatedColumnKeepsTheCollationDifference() throws IOException, InterruptedException {
        assertScriptIs("""
                SET search_path = pg_catalog;

                ALTER TABLE ONLY public.t
                \tDROP COLUMN c_period;

                ALTER TABLE public.t
                \tADD COLUMN c_period text GENERATED ALWAYS AS (c_source) STORED;""",
                assertTableDiffers(GENERATED_WITH_COLLATION, GENERATED_WITHOUT_COLLATION, new CoreSettings()));
    }

    /**
     * The relaxation is about a collation alone: the very same pair with one
     * more difference stays in the tree and is migrated in full.
     */
    @Test
    void aRealDifferenceBesideTheCollationIsStillReported() throws IOException, InterruptedException {
        String script = assertTableDiffers("""
                CREATE TABLE public.t (
                    c_period text COLLATE pg_catalog."ru_RU"
                );""", """
                CREATE TABLE public.t (
                    c_period text NOT NULL
                );""", new CoreSettings());

        assertScriptIs("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t
                \tALTER COLUMN c_period SET NOT NULL;""", script);
    }

    /**
     * The Eclipse project editor compares the project against the database and
     * generates its script from a reverted copy of that tree, so its comparison
     * runs in the opposite direction to its migration. The side whose collation
     * may be ignored follows the migration, not the comparison: the collation
     * the project does not name is still the one that cannot be migrated.
     */
    @Test
    void theRelaxedSideFollowsTheMigrationTargetNotTheComparisonOrder() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        settings.setMigrationTargetOldSide(true);

        IDatabase projectWithout = load(WITHOUT_COLLATION, settings);
        IDatabase databaseWith = load(WITH_COLLATION, settings);
        assertEquals(List.of(), tableNames(DiffTree.create(settings, projectWithout, databaseWith)),
                "the database-only collation cannot be migrated into the project");
        assertScriptIs("", editorScript(projectWithout, databaseWith, settings));

        IDatabase projectWith = load(WITH_COLLATION, settings);
        IDatabase databaseWithout = load(WITHOUT_COLLATION, settings);
        assertEquals(List.of("t"), tableNames(DiffTree.create(settings, projectWith, databaseWithout)),
                "the collation named by the project is migratable and must be reported");
        assertScriptIs(SET_COLLATION_SCRIPT, editorScript(projectWith, databaseWithout, settings));
    }

    /**
     * A table is asked the relaxed question only about its own state; its
     * children keep the hash guard that {@code equals} is given everywhere else,
     * because the two do not always cover the same fields.
     * {@code PgConstraintFk.compareUnalterable} leaves {@code DEFERRABLE} out of
     * its comparison while the hash keeps it, so a table whose foreign key
     * differs in nothing else compares equal all the way down and is told apart
     * by the hash alone - while {@code ALTER CONSTRAINT} migrates that
     * difference perfectly well.
     */
    @Test
    void aChildToldApartByItsHashAloneKeepsItsTableInTheDiff() throws IOException, InterruptedException {
        String deferred = """
                CREATE TABLE public.t1 (
                    id bigint PRIMARY KEY
                );

                CREATE TABLE public.t (
                    id bigint REFERENCES public.t1(id) DEFERRABLE INITIALLY DEFERRED
                );""";
        String notDeferred = """
                CREATE TABLE public.t1 (
                    id bigint PRIMARY KEY
                );

                CREATE TABLE public.t (
                    id bigint REFERENCES public.t1(id)
                );""";

        String expected = """
                SET search_path = pg_catalog;

                ALTER TABLE public.t
                \tALTER CONSTRAINT t_id_fkey NOT DEFERRABLE;""";

        for (boolean ignoreColumnOrder : new boolean[] { false, true }) {
            CoreSettings settings = new CoreSettings();
            settings.setIgnoreColumnOrder(ignoreColumnOrder);
            IDatabase oldDb = load(deferred, settings);
            IDatabase newDb = load(notDeferred, settings);

            assertEquals(List.of("t"), tableNames(DiffTree.create(settings, oldDb, newDb)),
                    "ignoring the column order must not lose a constraint the hash alone tells apart");
            assertScriptIs(expected, PgCodeKeeperApi.diff(provider, oldDb, newDb, settings));
        }
    }

    /**
     * The relaxation lives beside {@code equals}, never inside it: the two
     * states are still unequal and still hash differently, so equality and hash
     * keep agreeing with each other. A relaxation reaching only one of them
     * would leave a pair that {@code equals} calls equal while
     * {@code Comparison.compare} rejects it on the hash it is guarded by, or
     * the other way round.
     */
    @Test
    void theRelaxationStaysOutOfEqualsAndHashCode() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        ITable source = table(load(WITH_COLLATION, settings));
        ITable target = table(load(WITHOUT_COLLATION, settings));

        assertNotEquals(source, target, "the two states are not equal, only unmigratably different");
        assertNotEquals(source.hashCode(), target.hashCode(), "the hash must keep telling them apart");
        assertTrue(source.compareIgnoringUnmigratableColumns(target, settings),
                "and the difference between them cannot be migrated");
        assertFalse(target.compareIgnoringUnmigratableColumns(source, settings),
                "which is not so in the other direction");

        ITable sameSource = table(load(WITH_COLLATION, settings));
        assertEquals(source, sameSource, "equal states stay equal");
        assertEquals(source.hashCode(), sameSource.hashCode(), "and keep their hash");
    }

    private static ITable table(IDatabase database) {
        return (ITable) database.getStatement(new ObjectReference("public", "t", DbObjType.TABLE));
    }

    /**
     * Reproduces the script the project editor offers: the tree compares the
     * project against the database, the script runs from the database to the
     * project and is built from a reverted copy of that tree.
     */
    private String editorScript(IDatabase project, IDatabase database, CoreSettings settings)
            throws IOException, InterruptedException {
        TreeElement tree = DiffTree.create(settings, project, database);
        tree.setAllChecked();
        return PgCodeKeeperApi.diff(provider, database, project, settings, tree.getRevertedCopy());
    }

    private void assertNoDifference(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        IDatabase oldDb = load(oldSql, settings);
        IDatabase newDb = load(newSql, settings);

        assertEquals(List.of(), tableNames(DiffTree.create(settings, oldDb, newDb)),
                "a collation the migration target does not name is not a difference");
        assertScriptIs("", PgCodeKeeperApi.diff(provider, oldDb, newDb, settings));
    }

    /**
     * @return the migration script of the pair, with everything selected
     */
    private String assertTableDiffers(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        IDatabase oldDb = load(oldSql, settings);
        IDatabase newDb = load(newSql, settings);

        assertEquals(List.of("t"), tableNames(DiffTree.create(settings, oldDb, newDb)),
                "a migratable difference must stay in the diff tree");
        return PgCodeKeeperApi.diff(provider, oldDb, newDb, settings);
    }

    private static void assertScriptIs(String expected, String actual) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8),
                () -> "script must be byte for byte:\nexpected\n" + expected + "\nactual\n" + actual);
    }

    private static List<String> tableNames(TreeElement root) {
        List<String> names = new ArrayList<>();
        collectTables(root, names);
        return names;
    }

    private static void collectTables(TreeElement el, List<String> names) {
        if (el.getType() == DbObjType.TABLE) {
            names.add(el.getName());
        }
        for (TreeElement child : el.getChildren()) {
            collectTables(child, names);
        }
    }

    private IDatabase load(String sql, CoreSettings settings) throws IOException, InterruptedException {
        settings.clearErrors();
        IDatabase database = provider.getDumpLoader(
                        () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)),
                        "test/" + getClass().getName(), settings)
                .load();
        TestUtils.assertErrors(settings.getErrors());
        assertNotNull(database.getStatement(new ObjectReference("public", "t", DbObjType.TABLE)),
                "fixture must define the table");
        return database;
    }
}
