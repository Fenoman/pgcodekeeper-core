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
package org.pgcodekeeper.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Batch (multi-output) diff over one loaded comparison: each pass through
 * {@link PgCodeKeeperApi#diff} with per-pass settings must be byte-identical
 * to a standalone run with the same load settings.
 */
class BatchDiffApiTest {

    private static final String ORIGINAL = "_original.sql";
    private static final String NEW = "_new.sql";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    @Test
    void twoPassesOverOneLoadedPairMatchStandaloneRunsByteForByte()
            throws IOException, InterruptedException {
        // standalone run 1: plain diff
        String standalonePlain = PgCodeKeeperApi.diff(provider,
                dumpFactories("test_ignore"), new CoreSettings());

        // standalone run 2: same sources with an ignore list
        var standaloneIgnoreSettings = new CoreSettings();
        standaloneIgnoreSettings.addIgnoreList(getFilePath("ignore.pgcodekeeperignore"));
        String standaloneIgnored = PgCodeKeeperApi.diff(provider,
                dumpFactories("test_ignore"), standaloneIgnoreSettings);

        assertNotEquals(standalonePlain, standaloneIgnored,
                "fixture must react to the ignore list");

        // batch: load once, emit both outputs from the same models
        var baseSettings = new CoreSettings();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                dumpFactories("test_ignore"), baseSettings);

        assertNotSame(baseSettings, loaded.comparisonSettings());
        assertNotNull(loaded.comparisonSettings().getVersion(),
                "detected version must be published to the final settings");

        ISettings plainPass = loaded.comparisonSettings().copy();
        String batchPlain = PgCodeKeeperApi.diff(provider,
                loaded.oldDatabase(), loaded.newDatabase(), plainPass);

        ISettings ignoredPass = loaded.comparisonSettings().copy();
        ignoredPass.addIgnoreList(getFilePath("ignore.pgcodekeeperignore"));
        String batchIgnored = PgCodeKeeperApi.diff(provider,
                loaded.oldDatabase(), loaded.newDatabase(), ignoredPass);

        assertEquals(standalonePlain, batchPlain,
                "plain batch pass must be byte-identical to the standalone run");
        assertEquals(standaloneIgnored, batchIgnored,
                "ignore-list batch pass must be byte-identical to the standalone run");

        // passes must not mutate the shared models: repeat the first pass last
        String batchPlainAgain = PgCodeKeeperApi.diff(provider,
                loaded.oldDatabase(), loaded.newDatabase(),
                loaded.comparisonSettings().copy());
        assertEquals(standalonePlain, batchPlainAgain,
                "repeated pass over the shared models must reproduce the same script");

        TestUtils.assertErrors(baseSettings.getErrors());
    }

    @Test
    void perPassScriptFlagsMatchStandaloneRuns() throws IOException, InterruptedException {
        var transactionSettings = new CoreSettings();
        transactionSettings.setAddTransaction(true);
        String standaloneTransaction = PgCodeKeeperApi.diff(provider,
                dumpFactories("test_diff"), transactionSettings);
        String standalonePlain = PgCodeKeeperApi.diff(provider,
                dumpFactories("test_diff"), new CoreSettings());

        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                dumpFactories("test_diff"), new CoreSettings());

        CoreSettings transactionPass = (CoreSettings) loaded.comparisonSettings().copy();
        transactionPass.setAddTransaction(true);
        String batchTransaction = PgCodeKeeperApi.diff(provider,
                loaded.oldDatabase(), loaded.newDatabase(), transactionPass);

        String batchPlain = PgCodeKeeperApi.diff(provider,
                loaded.oldDatabase(), loaded.newDatabase(),
                loaded.comparisonSettings().copy());

        assertEquals(standaloneTransaction, batchTransaction);
        assertEquals(standalonePlain, batchPlain);
        assertTrue(batchTransaction.contains("START TRANSACTION;"));
        assertFalse(batchPlain.contains("START TRANSACTION;"));
    }

    /**
     * Documents why batch mode must NOT emulate a per-output
     * {@code --ignore-schema} with tree-level schema hiding: with the schema
     * loaded, DepcyResolver walks the full model and pulls hidden-schema
     * objects into the script as dependency collateral, so the result is not
     * byte-identical to a standalone run that excluded the schema at load
     * time. Batch mode therefore requires one shared ignore-schema set.
     */
    @Test
    void treeLevelSchemaHidingIsNotEquivalentToLoadLevelExclusion(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        Path oldProject = tempDir.resolve("old");
        Path newProject = tempDir.resolve("new");
        writeCrossSchemaProjects(oldProject, newProject);

        Path ignoreSchemaFile = tempDir.resolve("schemas.pgcodekeeperignoreschema");
        Files.writeString(ignoreSchemaFile, "SHOW ALL\nHIDE NONE hidden\n");

        // standalone narrow run: schema "hidden" is excluded at load time
        var narrowSettings = new CoreSettings();
        narrowSettings.addIgnoreSchemaList(ignoreSchemaFile);
        String narrowScript = PgCodeKeeperApi.diff(provider,
                projectFactories(oldProject, newProject), narrowSettings);

        // batch-style emulation: union load, schema hidden at tree level only
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                projectFactories(oldProject, newProject), new CoreSettings());
        ISettings emulatedPass = loaded.comparisonSettings().copy();
        emulatedPass.getIgnoreList().add(new IgnoredObject("hidden",
                false, true, false, EnumSet.of(DbObjType.SCHEMA)));
        String emulatedScript = PgCodeKeeperApi.diff(provider,
                loaded.oldDatabase(), loaded.newDatabase(), emulatedPass);

        assertTrue(narrowScript.contains("visible.payments"),
                "narrow load must still emit the visible table");
        assertFalse(narrowScript.contains("CREATE TYPE hidden.money_amount"),
                "narrow load must not emit DDL for the hidden dependency type");
        assertTrue(emulatedScript.contains("CREATE TYPE hidden.money_amount"),
                "union load pulls the hidden type in as dependency collateral");
        assertNotEquals(narrowScript, emulatedScript,
                "tree-level schema hiding is not byte-identical to load-level exclusion");
    }

    private void writeCrossSchemaProjects(Path oldProject, Path newProject)
            throws IOException {
        // old side: only the visible schema exists, no objects
        writeProjectFile(oldProject, "SCHEMA/visible/visible.sql",
                "CREATE SCHEMA visible;");

        // new side: a visible table depends on a type from the hidden schema
        writeProjectFile(newProject, "SCHEMA/visible/visible.sql",
                "CREATE SCHEMA visible;");
        writeProjectFile(newProject, "SCHEMA/hidden/hidden.sql",
                "CREATE SCHEMA hidden;");
        writeProjectFile(newProject, "SCHEMA/hidden/TYPE/money_amount.sql",
                "CREATE TYPE hidden.money_amount AS (amount numeric, currency text);");
        writeProjectFile(newProject, "SCHEMA/visible/TABLE/payments.sql",
                "CREATE TABLE visible.payments (id integer, total hidden.money_amount);");
    }

    private static void writeProjectFile(Path projectRoot, String relativePath, String sql)
            throws IOException {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, sql + '\n');
    }

    private ComparisonLoaderFactories dumpFactories(String baseName) {
        return new ComparisonLoaderFactories(
                sideSettings -> provider.getDumpLoader(
                        getFilePath(baseName + ORIGINAL), sideSettings),
                sideSettings -> provider.getDumpLoader(
                        getFilePath(baseName + NEW), sideSettings));
    }

    private ComparisonLoaderFactories projectFactories(Path oldProject, Path newProject) {
        return new ComparisonLoaderFactories(
                sideSettings -> provider.getProjectLoader(oldProject, sideSettings),
                sideSettings -> provider.getProjectLoader(newProject, sideSettings));
    }

    private Path getFilePath(String fileName) {
        return TestUtils.getFilePath(fileName, getClass());
    }
}
