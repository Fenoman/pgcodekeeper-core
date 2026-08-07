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

import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.database.base.parser.ScriptParser;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;
import org.pgcodekeeper.core.model.graph.DepcyFinder;
import org.pgcodekeeper.core.model.graph.DepcyResolver;
import org.pgcodekeeper.core.model.graph.DepcyResolver.DepcyGraphs;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.telemetry.ComparisonStage;
import org.pgcodekeeper.core.telemetry.ComparisonStageTelemetry;
import org.pgcodekeeper.core.telemetry.ComparisonTelemetryPublisher;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.utils.Pair;
import org.pgcodekeeper.core.utils.PhaseTimer;
import org.pgcodekeeper.core.utils.Utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Main API class for pgCodeKeeper database operations.
 */
public final class PgCodeKeeperApi {

    private static final StageTiming DISABLED_STAGE_TIMING =
            new StageTiming(false, 0L);

    /**
     * Compares two databases and generates a tree.
     *
     * @param oldDbLoader  loader for the old database version to compare from
     * @param newDbLoader  loader for the new database version to compare to
     * @param settings configuration settings
     * @return the root element of generated tree
     * @throws IOException          if I/O operations fail
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static TreeElement createTree(ILoader oldDbLoader,
                                         ILoader newDbLoader,
                                         ISettings settings)
            throws IOException, InterruptedException {
        var subMonitor = settings.getMonitor().createSubMonitor();
        subMonitor.setWorkRemaining(65);

        var databases = loadLegacyDatabases(
                oldDbLoader, newDbLoader, settings, subMonitor);

        subMonitor.setTaskName(Messages.PgCodeKeeperApi_creating_tree);
        TreeElement root = createDiffTree(
                settings, databases.getFirst(), databases.getSecond());
        subMonitor.worked(5);

        return root;
    }

    /**
     * Compares two databases created from an ordered factory pair and generates a tree.
     *
     * @param loaderFactories factories for the logical old and new comparison sides
     * @param settings configuration settings
     * @return the root element of generated tree
     * @throws IOException          if I/O operations fail
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static TreeElement createTree(ComparisonLoaderFactories loaderFactories,
                                         ISettings settings)
            throws IOException, InterruptedException {
        var subMonitor = settings.getMonitor().createSubMonitor();
        subMonitor.setWorkRemaining(65);

        var loaded = loadForComparison(loaderFactories, settings);
        subMonitor.worked(60);

        subMonitor.setTaskName(Messages.PgCodeKeeperApi_creating_tree);
        TreeElement root = createTree(loaded);
        subMonitor.worked(5);

        return root;
    }

    /**
     * Compares two databases and generates a migration script.
     *
     * @param provider     the database provider determining SQL dialect
     * @param oldDbLoader  loader for the old database version to compare from
     * @param newDbLoader  loader for the new database version to compare to
     * @param settings configuration settings
     * @return the generated migration script as a string
     * @throws IOException          if I/O operations fail
     * @throws InterruptedException if the thread is interrupted during the
     *                              operation
     */
    public static String diff(IDatabaseProvider provider,
                              ILoader oldDbLoader,
                              ILoader newDbLoader,
                              ISettings settings)
            throws IOException, InterruptedException {
        var subMonitor = settings.getMonitor().createSubMonitor();
        subMonitor.setWorkRemaining(70);
        var databases = loadLegacyDatabases(
                oldDbLoader, newDbLoader, settings, subMonitor);

        subMonitor.setTaskName(Messages.PgCodeKeeperApi_building_script);
        var script = diff(provider, databases.getFirst(), databases.getSecond(), settings);
        subMonitor.worked(10);

        return script;
    }

    /**
     * Compares two databases created from an ordered factory pair and generates a
     * migration script.
     *
     * @param provider database provider determining SQL dialect
     * @param loaderFactories factories for the logical old and new comparison sides
     * @param settings configuration settings
     * @return the generated migration script as a string
     * @throws IOException          if I/O operations fail
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static String diff(IDatabaseProvider provider,
                              ComparisonLoaderFactories loaderFactories,
                              ISettings settings)
            throws IOException, InterruptedException {
        var subMonitor = settings.getMonitor().createSubMonitor();
        subMonitor.setWorkRemaining(70);

        var loaded = loadForComparison(loaderFactories, settings);
        subMonitor.worked(60);
        var finalSettings = loaded.comparisonSettings();

        subMonitor.setTaskName(Messages.PgCodeKeeperApi_building_script);
        var script = diff(provider,
                loaded.oldDatabase(), loaded.newDatabase(), finalSettings);
        subMonitor.worked(10);

        return script;
    }

    /**
     * Loads both comparison sides once at {@link ComparisonDepth#FULL} and
     * returns the analyzed models together with the final comparison settings.
     * How much of them the analysis phase actually covered is still a question
     * for the settings, not for this call - see {@link ComparisonDepth}.
     * <p>
     * The returned models are read-only afterwards: they may be passed through
     * {@link #diff(IDatabaseProvider, IDatabase, IDatabase, ISettings)} any
     * number of times with different post-load settings (ignore lists, script
     * generation flags) and each pass produces the same script a standalone
     * run with identical load settings would produce. Settings that change
     * what is loaded ({@code --ignore-schema}, charsets, privileges and other
     * loader options) are fixed at this call and must not vary between passes.
     *
     * @param loaderFactories factories for the logical old and new comparison sides
     * @param settings configuration settings used for loading; receives merged
     *                 load diagnostics and the detected server version
     * @return the loaded comparison: both database models and the final settings
     * @throws IOException          if I/O operations fail
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static LoadedComparison loadForComparison(ComparisonLoaderFactories loaderFactories,
                                                     ISettings settings)
            throws IOException, InterruptedException {
        return loadForComparison(loaderFactories, settings, ComparisonDepth.FULL);
    }

    /**
     * Loads both comparison sides once, stopping after the phase {@code depth}
     * asks for.
     * <p>
     * A {@link ComparisonDepth#STRUCTURAL_ONLY} load skips the analysis phase
     * entirely: the returned models are structurally complete but carry no
     * dependencies, so they must not be passed to script generation.
     * {@link ComparisonDepth#FULL} runs that phase, which a script does need,
     * but running it is not the same as resolving every dependency - see
     * {@link ComparisonDepth} for the setting that keeps part of a full load
     * unanalyzed.
     *
     * @param loaderFactories factories for the logical old and new comparison sides
     * @param settings configuration settings used for loading; receives merged
     *                 load diagnostics and the detected server version
     * @param depth how deep to load; see {@link ComparisonDepth}
     * @return the loaded comparison: both database models, the final settings
     *         and the depth actually loaded
     * @throws IOException          if I/O operations fail
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static LoadedComparison loadForComparison(ComparisonLoaderFactories loaderFactories,
                                                     ISettings settings, ComparisonDepth depth)
            throws IOException, InterruptedException {
        return loadForComparison(loaderFactories, settings, depth, System::nanoTime);
    }

    static LoadedComparison loadForComparison(ComparisonLoaderFactories loaderFactories,
            ISettings settings, LongSupplier telemetryNanoTime)
            throws IOException, InterruptedException {
        return loadForComparison(loaderFactories, settings, ComparisonDepth.FULL, telemetryNanoTime);
    }

    static LoadedComparison loadForComparison(ComparisonLoaderFactories loaderFactories,
            ISettings settings, ComparisonDepth depth, LongSupplier telemetryNanoTime)
            throws IOException, InterruptedException {
        IComparisonTelemetry telemetry = settings.getComparisonTelemetry();
        StageTiming telemetryStart = startStage(telemetry, telemetryNanoTime);
        long loadStart = PhaseTimer.start();
        var loaded = new ComparisonLoaderCoordinator().load(loaderFactories, settings, depth);
        PhaseTimer.end("load_databases", loadStart, "coordinator");
        finishStage(telemetry, ComparisonStage.DATABASE_LOAD_TOTAL,
                telemetryStart, telemetryNanoTime);
        return loaded;
    }

    /**
     * Generates a diff tree from a previously loaded comparison without loading
     * either side again.
     *
     * @param loaded fully analyzed comparison models and final settings
     * @return the root element of the generated tree
     * @throws InterruptedException if tree generation is cancelled
     */
    public static TreeElement createTree(LoadedComparison loaded)
            throws InterruptedException {
        return createTree(loaded, System::nanoTime);
    }

    static TreeElement createTree(LoadedComparison loaded,
            LongSupplier telemetryNanoTime) throws InterruptedException {
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(telemetryNanoTime, "telemetryNanoTime");
        return createDiffTree(loaded.comparisonSettings(),
                loaded.oldDatabase(), loaded.newDatabase(), telemetryNanoTime);
    }

    /**
     * Compares two databases and generates a migration script.
     *
     * @param provider     the database provider determining SQL dialect
     * @param oldDb        the old database version to compare from
     * @param newDb        the new database version to compare to
     * @param settings configuration settings
     * @return the generated migration script as a string
     * @throws IOException          if I/O operations fail
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static String diff(IDatabaseProvider provider,
                              IDatabase oldDb,
                              IDatabase newDb,
                              ISettings settings)
            throws IOException, InterruptedException {
        return diff(provider, oldDb, newDb, settings, (Supplier<DepcyGraphs>) null);
    }

    /**
     * Compares two databases and generates a migration script, reusing a pair of dependency
     * graphs the caller already built for these two models.
     * <p>
     * Intended for a caller that scripts one loaded comparison several times over with
     * different post-load settings - a batch run - where the graphs would otherwise be
     * rebuilt per output even though nothing they are derived from changed. Take a source
     * with {@link #sharedGraphs(IDatabase, IDatabase)} and pass the same one to every call
     * over the same two models. Passing {@code null} builds a fresh pair, which is what the
     * plain overload does.
     *
     * @param sharedGraphs source of graphs built from these same two models, or {@code null}
     * @return the generated migration script as a string
     * @throws IOException          if I/O operations fail
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static String diff(IDatabaseProvider provider,
                              IDatabase oldDb,
                              IDatabase newDb,
                              ISettings settings,
                              Supplier<DepcyGraphs> sharedGraphs)
            throws IOException, InterruptedException {
        TreeElement root = createDiffTree(settings, oldDb, newDb);
        root.setAllChecked();
        return diff(provider, oldDb, newDb, settings, root, sharedGraphs);
    }

    /**
     * Returns a source of the dependency graph pair for two models, for a caller that will
     * diff them more than once. The pair is derived from the models alone and carries no
     * settings, so it stays valid for as long as the models do.
     * <p>
     * The pair is built on the first call that actually needs it and handed out unchanged
     * afterwards. Lazily, because a comparison that yields an empty script never asks for a
     * graph, and a caller must not be charged for graphs no output of it needed. Not
     * thread-safe: hand it to one diff at a time.
     *
     * @param oldDb the old database version
     * @param newDb the new database version
     * @return the graph source to hand to
     *         {@link #diff(IDatabaseProvider, IDatabase, IDatabase, ISettings, Supplier)}
     */
    public static Supplier<DepcyGraphs> sharedGraphs(IDatabase oldDb, IDatabase newDb) {
        return new Supplier<>() {

            private DepcyGraphs graphs;

            @Override
            public DepcyGraphs get() {
                if (graphs == null) {
                    graphs = DepcyResolver.buildDependencyGraphs(oldDb, newDb);
                }
                return graphs;
            }
        };
    }

    /**
     * Compares two databases and generates a migration script with a pre-built tree.
     *
     * @param provider     the database provider determining SQL dialect
     * @param oldDb        the old database version to compare from
     * @param newDb        the new database version to compare to
     * @param settings configuration settings
     * @param root         root element of tree
     * @return the generated migration script as a string
     * @throws IOException if I/O operations fail
     */
    public static String diff(IDatabaseProvider provider,
                              IDatabase oldDb,
                              IDatabase newDb,
                              ISettings settings,
                              TreeElement root)
            throws IOException {
        return diff(provider, oldDb, newDb, settings, root, null);
    }

    /**
     * Compares two databases with a pre-built tree and a pre-built pair of dependency graphs.
     *
     * @param root         root element of tree
     * @param sharedGraphs source of graphs built from these same two models, or {@code null}
     *                     to build a fresh pair
     * @return the generated migration script as a string
     * @throws IOException if I/O operations fail
     */
    public static String diff(IDatabaseProvider provider,
                              IDatabase oldDb,
                              IDatabase newDb,
                              ISettings settings,
                              TreeElement root,
                              Supplier<DepcyGraphs> sharedGraphs)
            throws IOException {
        var scriptBuilder = provider.getScriptBuilder(settings);
        return scriptBuilder.createScript(root, oldDb, newDb, sharedGraphs);
    }

    /**
     * Exports or updates project files based on database schema.
     * <p>
     * If {@code oldDb} is {@code null}, exports {@code newDb} schema to an empty project directory.
     * If {@code oldDb} is provided, updates the existing project with changes between {@code oldDb} and {@code newDb}.
     *
     * @param provider     the database provider determining SQL dialect and exporter/updater implementation
     * @param oldDbLoader  loader for the old database version (existing project state), or {@code null} for a full export
     * @param newDbLoader  loader for the new new database version (target state)
     * @param projectPath  path to the target project directory
     * @param settings configuration settings
     * @throws IOException          if I/O operations fail, if the directory does not exist,
     *                              if the directory is not empty (export) or if path is a file
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static void exportToProject(IDatabaseProvider provider,
                                       ILoader oldDbLoader,
                                       ILoader newDbLoader,
                                       Path projectPath,
                                       ISettings settings)
            throws IOException, InterruptedException {
        exportToProject(provider, oldDbLoader, newDbLoader, projectPath, false, null, settings);
    }

    /**
     * Exports or updates project or overrides files based on database schema.
     * <p>
     * If {@code oldDb} is {@code null}, exports {@code newDb} schema to an empty project directory.
     * If {@code oldDb} is provided, updates the existing project with changes between {@code oldDb} and {@code newDb}.
     *
     * @param provider      the database provider determining SQL dialect and exporter/updater implementation
     * @param oldDbLoader   loader for the old database version (existing project state), or {@code null} for a full export
     * @param newDbLoader   loader for the new database version (target state)
     * @param projectPath   path to the target project directory
     * @param overridesOnly option to update only overrides
     * @param settings configuration settings
     * @throws IOException          if I/O operations fail, if the directory does not exist,
     *                              if the directory is not empty (export) or if path is a file
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static void exportToProject(IDatabaseProvider provider,
                                       ILoader oldDbLoader,
                                       ILoader newDbLoader,
                                       Path projectPath,
                                       boolean overridesOnly,
                                       ISettings settings)
            throws IOException, InterruptedException {
        exportToProject(provider, oldDbLoader, newDbLoader, projectPath, overridesOnly, null, settings);
    }

    /**
     * Exports or updates project or overrides files based on database schema, using
     * an externally supplied directory layout.
     * <p>
     * If {@code oldDb} is {@code null}, exports {@code newDb} schema to an empty project directory.
     * If {@code oldDb} is provided, updates the existing project with changes between {@code oldDb} and {@code newDb}.
     *
     * @param provider      the database provider determining SQL dialect and exporter/updater implementation
     * @param oldDbLoader   loader for the old database version (existing project state), or {@code null} for a full export
     * @param newDbLoader   loader for the new database version (target state)
     * @param projectPath   path to the target project directory
     * @param overridesOnly option to update only overrides
     * @param structureFile path to a properties file containing directory layout overrides
     *                      to apply, or {@code null} to use the default layout. The file may
     *                      have any name. When non-{@code null}, the resolved layout is
     *                      persisted to the exported project as {@code structure.properties}
     *                      regardless of the source filename. Only used when {@code oldDbLoader}
     *                      is {@code null} (full export).
     * @param settings configuration settings
     * @throws IOException          if I/O operations fail, if the directory does not exist,
     *                              if the directory is not empty (export) or if path is a file
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static void exportToProject(IDatabaseProvider provider,
                                       ILoader oldDbLoader,
                                       ILoader newDbLoader,
                                       Path projectPath,
                                       boolean overridesOnly,
                                       Path structureFile,
                                       ISettings settings)
            throws IOException, InterruptedException {
        var subMonitor = settings.getMonitor().createSubMonitor();
        subMonitor.setWorkRemaining(100);

        subMonitor.setTaskName(Messages.Utils_loading_old_database);
        var oldDb = oldDbLoader == null ? null : oldDbLoader.loadAndAnalyze();
        subMonitor.worked(30);

        subMonitor.setTaskName(Messages.Utils_loading_new_database);
        var newDb = newDbLoader.loadAndAnalyze();
        subMonitor.worked(30);

        IgnoreList ignoreList = settings.getIgnoreList();

        subMonitor.setTaskName(Messages.PgCodeKeeperApi_creating_tree);
        TreeElement root = createDiffTree(settings, oldDb, newDb);
        root.setAllChecked();
        subMonitor.worked(20);

        List<TreeElement> selected = new TreeFlattener()
                .onlySelected()
                .useIgnoreList(ignoreList)
                .onlyTypes(settings.getAllowedTypes())
                .flatten(root);

        subMonitor.setTaskName(Messages.PgCodeKeeperApi_exporting_project);
        exportToProject(provider, oldDb, newDb, selected, projectPath, overridesOnly, settings, structureFile);
        subMonitor.worked(20);
    }

    private static TreeElement createDiffTree(ISettings settings,
            IDatabase oldDb, IDatabase newDb) throws InterruptedException {
        return createDiffTree(settings, oldDb, newDb, System::nanoTime);
    }

    static TreeElement createDiffTree(ISettings settings,
            IDatabase oldDb, IDatabase newDb, LongSupplier telemetryNanoTime)
            throws InterruptedException {
        Objects.requireNonNull(telemetryNanoTime, "telemetryNanoTime");
        IComparisonTelemetry telemetry = settings.getComparisonTelemetry();
        StageTiming telemetryStart = startStage(telemetry, telemetryNanoTime);
        TreeElement root = DiffTree.create(
                settings, oldDb, newDb, settings.getMonitor());
        finishStage(telemetry, ComparisonStage.DIFF_TREE_CREATE,
                telemetryStart, telemetryNanoTime);
        return root;
    }

    private static Pair<IDatabase, IDatabase> loadLegacyDatabases(
            ILoader oldDbLoader, ILoader newDbLoader, ISettings settings,
            IMonitor monitor)
            throws IOException, InterruptedException {
        IComparisonTelemetry telemetry = settings.getComparisonTelemetry();
        LongSupplier nanoTime = System::nanoTime;
        StageTiming telemetryStart = startStage(telemetry, nanoTime);
        Pair<IDatabase, IDatabase> databases = Utils.loadDatabases(
                oldDbLoader, newDbLoader, settings, monitor);
        finishStage(telemetry, ComparisonStage.DATABASE_LOAD_TOTAL,
                telemetryStart, nanoTime);
        return databases;
    }

    private static StageTiming startStage(IComparisonTelemetry telemetry,
            LongSupplier nanoTime) {
        if (!ComparisonTelemetryPublisher.isEnabled(telemetry)) {
            return DISABLED_STAGE_TIMING;
        }
        return new StageTiming(true, nanoTime.getAsLong());
    }

    private static void finishStage(IComparisonTelemetry telemetry,
            ComparisonStage stage, StageTiming timing, LongSupplier nanoTime) {
        if (timing.enabled()) {
            long elapsedNanos = Math.max(
                    0L, nanoTime.getAsLong() - timing.startNanos());
            ComparisonTelemetryPublisher.publishComparisonStage(
                    telemetry, new ComparisonStageTelemetry(stage, elapsedNanos));
        }
    }

    private record StageTiming(boolean enabled, long startNanos) {
    }

    /**
     * Exports or updates project or overrides files based on selected elements.
     *
     * @param provider      the database provider determining SQL dialect and exporter/updater implementation
     * @param oldDb         the old database version (existing project state), or {@code null} for a full export
     * @param newDb         the new database version (target state)
     * @param selected      the selected elements
     * @param projectPath   path to the target project directory
     * @param overridesOnly option to update only overrides
     * @param settings      configuration settings
     * @throws IOException          if I/O operations fail, if the directory does not exist,
     *                              if the directory is not empty (export) or if path is a file
     */
    public static void exportToProject(IDatabaseProvider provider,
                                       IDatabase oldDb,
                                       IDatabase newDb,
                                       List<TreeElement> selected,
                                       Path projectPath,
                                       boolean overridesOnly,
                                       ISettings settings)
            throws IOException {
        exportToProject(provider, oldDb, newDb, selected, projectPath, overridesOnly, settings, null);
    }

    /**
     * Exports or updates project or overrides files based on selected elements, using
     * an externally supplied directory layout.
     *
     * @param provider      the database provider determining SQL dialect and exporter/updater implementation
     * @param oldDb         the old database version (existing project state), or {@code null} for a full export
     * @param newDb         the new database version (target state)
     * @param selected      the selected elements
     * @param projectPath   path to the target project directory
     * @param overridesOnly option to update only overrides
     * @param settings      configuration settings
     * @param structureFile path to a properties file containing directory layout overrides
     *                      to apply, or {@code null} to use the default layout. The file may
     *                      have any name. When non-{@code null}, the resolved layout is
     *                      persisted to the exported project as {@code structure.properties}
     *                      regardless of the source filename.
     * @throws IOException          if I/O operations fail, if the directory does not exist,
     *                              if the directory is not empty (export) or if path is a file
     */
    public static void exportToProject(IDatabaseProvider provider,
                                       IDatabase oldDb,
                                       IDatabase newDb,
                                       List<TreeElement> selected,
                                       Path projectPath,
                                       boolean overridesOnly,
                                       ISettings settings,
                                       Path structureFile)
            throws IOException {
        if (oldDb != null) {
            provider.getProjectUpdater(newDb, oldDb, selected, projectPath, overridesOnly, settings).updatePartial();
        } else {
            provider.getModelExporter(projectPath, newDb, selected, settings, structureFile).exportProject();
        }
    }

    /**
     * Analyzes database object dependencies and builds a dependency graph.
     *
     * @param loader       loader for the database to analyze
     * @param objectNames  collection of object name patterns to search for (e.g., "public.users", "*.orders")
     * @param depth        depth of dependency analysis (e.g., 10 levels)
     * @param reverse      false = direct dependencies (what this depends on), true = reverse dependencies (what depends on this)
     * @param filterTypes  types of objects to filter (TABLE, FUNCTION, VIEW), null = all types
     * @param invertFilter false = include only specified types, true = exclude specified types
     * @return list of strings with dependency information
     * @throws IOException          if I/O operations fail
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static List<String> analyzeDependencies(ILoader loader,
                                                   Collection<String> objectNames,
                                                   int depth,
                                                   boolean reverse,
                                                   Collection<DbObjType> filterTypes,
                                                   boolean invertFilter)
            throws IOException, InterruptedException {
        var db = loader.loadAndAnalyze();
        var settings = loader.getSettings();
        return DepcyFinder.byPatterns(depth, reverse, filterTypes, invertFilter, db, objectNames,
                settings.getAdditionalDependencies());
    }

    /**
     * Checks SQL script for dangerous operations (DROP TABLE, ALTER COLUMN, etc.).
     *
     * @param provider       the database provider determining SQL dialect
     * @param name           name of the script source (used as file identifier for parsing)
     * @param sql            the SQL script to check
     * @param settings   parsing settings
     * @param allowedDangers set of allowed dangerous operations
     * @return set of detected dangerous operations
     * @throws IOException          if I/O operations fail
     * @throws InterruptedException if the thread is interrupted during the operation
     */
    public static Set<DangerStatement> checkDangerousStatements(IDatabaseProvider provider,
                                                                String name, String sql,
                                                                ISettings settings,
                                                                Collection<DangerStatement> allowedDangers)
            throws IOException, InterruptedException {
        var subMonitor = settings.getMonitor().createSubMonitor();
        subMonitor.setWorkRemaining(100);

        subMonitor.setTaskName(Messages.PgCodeKeeperApi_parsing_script);
        long start = PhaseTimer.start();
        ScriptParser parser = new ScriptParser(provider.getDumpLoader(() -> new ByteArrayInputStream(
                sql.getBytes(StandardCharsets.UTF_8)), name, settings), name, sql);
        PhaseTimer.end("danger_parse", start);
        subMonitor.worked(50);

        subMonitor.setTaskName(Messages.PgCodeKeeperApi_checking_dangerous_statements);
        start = PhaseTimer.start();
        var result = parser.getDangerDdl(allowedDangers);
        PhaseTimer.end("danger_check", start);
        subMonitor.worked(50);

        return result;
    }

    /**
     * Parses and executes SQL script against a database.
     *
     * @param provider     the database provider determining SQL dialect and JDBC connector
     * @param name         name of the script source (used as file identifier for parsing)
     * @param sql          the SQL script to execute
     * @param url          full JDBC URL of the target database
     * @param settings parsing and execution settings
     * @throws IOException          if there is an error reading the script
     * @throws InterruptedException if the thread is interrupted during the operation
     * @throws SQLException         if a database access error occurs during execution
     */
    public static void runSQL(IDatabaseProvider provider, String name, String sql, String url,
                              ISettings settings)
            throws IOException, InterruptedException, SQLException {
        var subMonitor = settings.getMonitor().createSubMonitor();
        subMonitor.setWorkRemaining(100);

        subMonitor.setTaskName(Messages.PgCodeKeeperApi_parsing_script);
        ScriptParser parser = new ScriptParser(provider.getDumpLoader(() -> new ByteArrayInputStream(
                sql.getBytes(StandardCharsets.UTF_8)), name, settings), name, sql);
        subMonitor.worked(30);

        subMonitor.setTaskName(Messages.PgCodeKeeperApi_executing_script);
        new JdbcRunner(subMonitor).runBatches(provider.getJdbcConnector(url), parser.batch(), null);
        subMonitor.worked(70);
    }

    private PgCodeKeeperApi() {
        // only statics
    }
}
