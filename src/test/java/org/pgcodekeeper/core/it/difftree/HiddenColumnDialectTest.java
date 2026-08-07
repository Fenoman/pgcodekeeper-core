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
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.ch.ChDatabaseProvider;
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * Every dialect that owns a table owns its columns in a list of its own and
 * compares that list in code of its own, so the promise about a hidden column
 * has to be kept three times over.
 * <p>
 * The cases are the same three questions in each dialect, and they are the two
 * halves of the boundary: hiding lives in the comparison, so a table that
 * differs only in a hidden column is unchanged and produces no script; hiding
 * does not live in the generation, so a table created from scratch is created
 * with every column the project declares, byte for byte as it would be with no
 * ignore list at all.
 */
class HiddenColumnDialectTest {

    private static final String HIDDEN = "s_creator";

    @Test
    void postgresHidesTheColumnEverywhere() throws IOException, InterruptedException {
        assertDialectHidesTheColumn(new PgDatabaseProvider(), "CREATE SCHEMA public;", """
                CREATE TABLE public.doc (
                    id bigint,
                    s_creator text
                );""", """
                CREATE TABLE public.doc (
                    id bigint,
                    s_creator character varying(100)
                );""", "id bigint");
    }

    @Test
    void msSqlHidesTheColumnEverywhere() throws IOException, InterruptedException {
        assertDialectHidesTheColumn(new MsDatabaseProvider(), "CREATE SCHEMA [dbo]\nGO", """
                CREATE TABLE [dbo].[doc](
                    [id] [bigint] NULL,
                    [s_creator] [nvarchar](50) NULL
                )
                GO""", """
                CREATE TABLE [dbo].[doc](
                    [id] [bigint] NULL,
                    [s_creator] [nvarchar](100) NULL
                )
                GO""", "[id] [bigint]");
    }

    @Test
    void clickHouseHidesTheColumnEverywhere() throws IOException, InterruptedException {
        assertDialectHidesTheColumn(new ChDatabaseProvider(), "CREATE DATABASE default;", """
                CREATE TABLE default.doc
                (
                    `id` Int64,
                    `s_creator` String
                )
                ENGINE = Log;""", """
                CREATE TABLE default.doc
                (
                    `id` Int64,
                    `s_creator` Nullable(String)
                )
                ENGINE = Log;""", "`id` Int64");
    }

    /**
     * @param empty       a state holding the container of the table and nothing else
     * @param table       the table with the hidden column
     * @param altered     the same table whose hidden column alone differs
     * @param visiblePart the text of the visible column as its CREATE writes it
     */
    private void assertDialectHidesTheColumn(IDatabaseProvider provider, String empty, String table,
                                             String altered, String visiblePart)
            throws IOException, InterruptedException {
        CoreSettings hiding = hidingSettings();

        LoadedComparison onlyHiddenDiffers = load(provider, table, altered, hiding);
        assertEquals(List.of(), changedTables(
                        DiffTree.create(hiding, onlyHiddenDiffers.oldDatabase(), onlyHiddenDiffers.newDatabase())),
                "a table differing only in its hidden column is unchanged");
        assertEquals("", PgCodeKeeperApi.diff(provider, onlyHiddenDiffers.oldDatabase(),
                onlyHiddenDiffers.newDatabase(), hiding), "and produces no script");

        LoadedComparison created = load(provider, empty, table, hiding);
        String createScript = PgCodeKeeperApi.diff(provider, created.oldDatabase(), created.newDatabase(), hiding);

        CoreSettings plain = new CoreSettings();
        LoadedComparison unhidden = load(provider, empty, table, plain);
        String plainScript = PgCodeKeeperApi.diff(provider, unhidden.oldDatabase(), unhidden.newDatabase(), plain);

        assertTrue(plainScript.contains(HIDDEN), "the fixture must hold the column at all: " + plainScript);
        assertEquals(plainScript, createScript,
                "a new table is created exactly as it would be with no rule in sight: " + createScript);
        assertTrue(createScript.contains(visiblePart), "and with every column of the project: " + createScript);
    }

    private static CoreSettings hidingSettings() {
        CoreSettings settings = new CoreSettings();
        settings.getIgnoreList().add(new IgnoredObject(HIDDEN, null, false, false, false, false,
                EnumSet.of(DbObjType.COLUMN)));
        return settings;
    }

    private static List<String> changedTables(TreeElement root) {
        return root.getChildren().stream()
                .flatMap(schema -> schema.getChildren().stream())
                .filter(el -> el.getType() == DbObjType.TABLE)
                .map(TreeElement::getName)
                .toList();
    }

    private LoadedComparison load(IDatabaseProvider provider, String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        settings.clearErrors();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> provider.getDumpLoader(source(oldSql), "old", sideSettings),
                        sideSettings -> provider.getDumpLoader(source(newSql), "new", sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        return loaded;
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }
}
