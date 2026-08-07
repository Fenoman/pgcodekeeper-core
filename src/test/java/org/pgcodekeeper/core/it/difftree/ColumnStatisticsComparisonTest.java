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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * The statistics target of a column is owned by whoever runs ANALYZE policy on
 * the target database, not by the project: OmniX sets it from a scheduled
 * procedure over hundreds of columns. A project that carries the difference
 * produces an ALTER the next run of that procedure undoes.
 */
class ColumnStatisticsComparisonTest {

    private static final String OLD = """
            CREATE TABLE public.t (c integer);
            ALTER TABLE ONLY public.t ALTER COLUMN c SET STATISTICS 500;
            """;
    private static final String NEW = """
            CREATE TABLE public.t (c integer);
            """;
    private static final String NEW_WITH_STAT = """
            CREATE TABLE public.t (c integer);
            ALTER TABLE ONLY public.t ALTER COLUMN c SET STATISTICS 100;
            """;
    private static final String NOTHING = "";

    private static final String EXTENDED_OLD = """
            CREATE TABLE public.t (a integer, b integer);
            CREATE STATISTICS public.s ON a, b FROM public.t;
            """;
    private static final String EXTENDED_NEW = """
            CREATE TABLE public.t (a integer, b integer);
            CREATE STATISTICS public.s ON a, b FROM public.t;
            ALTER STATISTICS public.s SET STATISTICS 1024;
            """;

    @Test
    void resetIsGeneratedByDefault() throws Exception {
        assertTrue(script(OLD, NEW, new CoreSettings()).contains("SET STATISTICS -1"));
    }

    @Test
    void resetDisappearsWhenIgnored() throws Exception {
        assertEquals("", script(OLD, NEW, ignoring()).trim(),
                "a difference in statistics alone must leave nothing to migrate");
    }

    @Test
    void changeDisappearsWhenIgnored() throws Exception {
        assertEquals("", script(OLD, NEW_WITH_STAT, ignoring()).trim());
    }

    /**
     * The value the project declares is the one deliberate statement it makes
     * about the column, so a table being created still carries it.
     */
    @Test
    void creationStillWritesTheProjectValue() throws Exception {
        assertTrue(script(NOTHING, NEW_WITH_STAT, ignoring()).contains("SET STATISTICS 100"));
    }

    /**
     * The relaxation is of the comparison alone. Two columns differing only in
     * statistics stay unequal and keep hashing apart, so a caller that did not
     * ask for it is unaffected - the contract ColumnRelaxations states.
     */
    @Test
    void otherDifferencesSurviveTheRelaxation() throws Exception {
        String sql = script(OLD, """
                CREATE TABLE public.t (c bigint);
                """, ignoring());
        assertTrue(sql.contains("ALTER COLUMN c"), sql);
    }

    /**
     * An extended statistics object carries a statistics target of its own,
     * written {@code ALTER STATISTICS ... SET STATISTICS n}. It is a different
     * thing sharing an unfortunate name with {@code pg_attribute.attstattarget},
     * and the setting takes no notice of it.
     */
    @Test
    void extendedStatisticsObjectsAreUntouched() throws Exception {
        String sql = script(EXTENDED_OLD, EXTENDED_NEW, ignoring());
        assertTrue(sql.contains("ALTER STATISTICS public.s SET STATISTICS 1024"), sql);
    }

    /**
     * The relaxation lives beside {@code equals} and the hash, never inside
     * either: two states of a column differing only in their statistics target
     * stay unequal and keep hashing apart, so a caller that has not asked for
     * the relaxation sees nothing of it. A relaxation reaching one of the two
     * alone would leave a pair the comparison calls equal while the hash tells
     * it apart, which is exactly the failure {@code Comparison} guards against
     * everywhere else. Both states are loaded with the setting on, because that
     * is the configuration under which the contract could break.
     */
    @Test
    void theRelaxationStaysOutOfEqualsAndHashCode() throws Exception {
        IColumn one = column(OLD);
        IColumn other = column(NEW_WITH_STAT);

        assertNotEquals(one, other, "the two states are equal only up to their statistics target");
        assertNotEquals(one.hashCode(), other.hashCode(), "the hash must keep telling them apart");
    }

    private static CoreSettings ignoring() {
        CoreSettings settings = new CoreSettings();
        settings.setIgnoreColumnStatistics(true);
        return settings;
    }

    private IColumn column(String sql) throws IOException, InterruptedException {
        CoreSettings settings = ignoring();
        IDatabase database = provider.getDumpLoader(source(sql), "test/" + getClass().getName(), settings).load();
        TestUtils.assertErrors(settings.getErrors());
        ITable table = (ITable) database.getStatement(new ObjectReference("public", "t", DbObjType.TABLE));
        assertNotNull(table, "fixture must define the table");
        return table.getColumns().iterator().next();
    }

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    private String script(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        settings.clearErrors();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> provider.getDumpLoader(source(oldSql), "old", sideSettings),
                        sideSettings -> provider.getDumpLoader(source(newSql), "new", sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings);
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }
}
