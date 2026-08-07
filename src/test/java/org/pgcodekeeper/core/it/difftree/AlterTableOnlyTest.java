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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * TimescaleDB rejects {@code ALTER TABLE ONLY} on a hypertable for every
 * subcommand but {@code SET/RESET (...)}, and a hypertable is not a fact the
 * generator can know: the set differs between the database a script is built
 * against and the databases it is applied to. The setting therefore suppresses
 * ONLY outright rather than deciding per object.
 */
class AlterTableOnlyTest {

    private static final String STAT_OLD = """
            CREATE TABLE public.t (c integer);
            ALTER TABLE ONLY public.t ALTER COLUMN c SET STATISTICS 500;
            """;
    private static final String STAT_NEW = """
            CREATE TABLE public.t (c integer);
            ALTER TABLE ONLY public.t ALTER COLUMN c SET STATISTICS 100;
            """;
    private static final String DEFAULT_OLD = """
            CREATE TABLE public.t (c integer);
            """;
    private static final String DEFAULT_NEW = """
            CREATE TABLE public.t (c integer DEFAULT 1);
            """;
    private static final String STORAGE_OLD = """
            CREATE TABLE public.t (c text);
            ALTER TABLE ONLY public.t ALTER COLUMN c SET STORAGE PLAIN;
            """;
    private static final String STORAGE_NEW = """
            CREATE TABLE public.t (c text);
            ALTER TABLE ONLY public.t ALTER COLUMN c SET STORAGE EXTENDED;
            """;
    private static final String NOTNULL_OLD = """
            CREATE TABLE public.t (c integer NOT NULL);
            """;
    private static final String NOTNULL_NEW = """
            CREATE TABLE public.t (c integer);
            """;
    private static final String OPTIONS_OLD = """
            CREATE TABLE public.t (c integer);
            """;
    private static final String OPTIONS_NEW = """
            CREATE TABLE public.t (c integer);
            ALTER TABLE ONLY public.t ALTER COLUMN c SET (n_distinct=100);
            """;

    @Test
    void statisticsKeepsOnlyByDefault() throws Exception {
        assertTrue(script(STAT_OLD, STAT_NEW, new CoreSettings()).contains("ALTER TABLE ONLY public.t"),
                "default behaviour must be unchanged");
    }

    @Test
    void statisticsDropsOnlyWhenAsked() throws Exception {
        String sql = script(STAT_OLD, STAT_NEW, noOnly());
        assertFalse(sql.contains("ALTER TABLE ONLY"), sql);
        assertTrue(sql.contains("ALTER TABLE public.t\n\tALTER COLUMN c SET STATISTICS 100"), sql);
    }

    @Test
    void defaultDropsOnlyWhenAsked() throws Exception {
        String sql = script(DEFAULT_OLD, DEFAULT_NEW, noOnly());
        assertFalse(sql.contains("ALTER TABLE ONLY"), sql);
    }

    @Test
    void storageDropsOnlyWhenAsked() throws Exception {
        assertFalse(script(STORAGE_OLD, STORAGE_NEW, noOnly()).contains("ALTER TABLE ONLY"));
    }

    @Test
    void notNullDropsOnlyWhenAsked() throws Exception {
        assertFalse(script(NOTNULL_OLD, NOTNULL_NEW, noOnly()).contains("ALTER TABLE ONLY"));
    }

    /**
     * The one form TimescaleDB permits with ONLY, so the setting must leave it
     * alone: AT_SetOptions is on its whitelist in process_utility.c.
     */
    @Test
    void columnOptionsKeepOnlyEvenWhenAsked() throws Exception {
        assertTrue(script(OPTIONS_OLD, OPTIONS_NEW, noOnly()).contains("ALTER TABLE ONLY public.t"));
    }

    private static CoreSettings noOnly() {
        CoreSettings settings = new CoreSettings();
        settings.setNoAlterTableOnly(true);
        return settings;
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
