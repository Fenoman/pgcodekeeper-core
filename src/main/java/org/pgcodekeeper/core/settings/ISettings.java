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
package org.pgcodekeeper.core.settings;

import org.pgcodekeeper.core.database.api.formatter.IFormatConfiguration;
import org.pgcodekeeper.core.database.api.jdbc.ISupportedVersion;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.base.parser.ParserExecutionPolicy;
import org.pgcodekeeper.core.dependencieslist.Dependency;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.model.difftree.ColumnUsers;
import org.pgcodekeeper.core.model.difftree.HiddenObjects;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Interface defining configuration settings for database comparison and migration operations.
 * Provides access to database type, formatting options, file paths, and various behavioral flags
 * that control how database schema comparisons and migrations are performed.
 */
public interface ISettings {

    /** Backward-compatible default for project-to-JDBC routine body reuse. */
    boolean DEFAULT_PG_ROUTINE_BODY_HASH_FIRST = false;
    /**
     * Default for skipping matched routine body analysis. Enabled: the skip
     * only ever activates inside the explicit hash-first comparison chain,
     * where a body is proven byte-identical between project and database.
     */
    boolean DEFAULT_PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS = true;
    /** Default maximum unmatched routine count per residual JDBC request. */
    int DEFAULT_PG_ROUTINE_BODY_RESIDUAL_BATCH_COUNT = 256;
    /** Default predicted UTF-8 grouping budget for a residual JDBC request. */
    long DEFAULT_PG_ROUTINE_BODY_RESIDUAL_BATCH_BYTES = 32L << 20;
    /** Default size cap of the persistent PostgreSQL catalog cache in megabytes. */
    long DEFAULT_PG_CATALOG_CACHE_MAX_MB = 512L;
    /** Backward-compatible default for row-level catalog caching. */
    boolean DEFAULT_PG_CATALOG_CACHE_ROWS = false;
    /** Backward-compatible default for the PostgreSQL catalog fingerprint probe. */
    boolean DEFAULT_PG_CATALOG_CACHE_FINGERPRINT_PROBE = false;
    /** Backward-compatible default: lane-parallel catalog readers disabled. */
    int DEFAULT_PG_PARALLEL_CATALOG_READERS = 0;

    /**
     * Checks if concurrent mode is enabled.
     *
     * @return true if concurrent mode is enabled
     */
    boolean isConcurrentlyMode();

    /**
     * Checks if migration scripts should be wrapped in transactions.
     *
     * @return true if transaction wrapping is enabled
     */
    boolean isAddTransaction();

    /**
     * Checks if existence checks should be generated in migration scripts.
     *
     * @return true if existence checks are enabled
     */
    boolean isGenerateExists();

    /**
     * Checks if constraints should be generated with NOT VALID option.
     *
     * @return true if NOT VALID constraints are enabled
     */
    boolean isGenerateConstraintNotValid();

    /**
     * Checks if existence check DO blocks should be generated.
     *
     * @return true if DO block generation is enabled
     */
    boolean isGenerateExistDoBlock();

    /**
     * Checks if USING clauses should be printed in generated SQL.
     *
     * @return true if USING clause printing is enabled
     */
    boolean isPrintUsing();

    /**
     * Checks if newlines should be preserved in generated SQL.
     *
     * @return true if newline preservation is enabled
     */
    boolean isKeepNewlines();

    /**
     * Checks if comments should be moved to the end of generated scripts.
     *
     * @return true if comments are moved to end
     */
    boolean isCommentsToEnd();

    /**
     * Checks if object code should be automatically formatted.
     *
     * @return true if auto-formatting is enabled
     */
    boolean isAutoFormatObjectCode();

    /**
     * Checks if privileges should be ignored during comparison.
     *
     * @return true if privileges are ignored
     */
    boolean isIgnorePrivileges();

    /**
     * Checks if column order should be ignored during comparison.
     *
     * @return true if column order is ignored
     */
    boolean isIgnoreColumnOrder();

    /**
     * Reports whether the cache of a sequence is left out of the comparison and
     * out of every {@code ALTER} that follows from it.
     * <p>
     * The cache decides how many values a session preallocates. It changes
     * nothing about the values a sequence is allowed to produce, so a project
     * and a database that disagree about it hold the same schema, and a
     * migration that carries the difference over is pure noise in a script - the
     * situation this exists for is a project of thousands of sequences whose
     * database was built with the server default.
     * <p>
     * The relaxation reaches the comparison and the {@code ALTER} it produces,
     * and stops there. A sequence that is created - on its own or as the
     * identity of a column - is still created with the cache written in the
     * project, because that cache is the one deliberate statement about it the
     * project makes, and losing it would quietly reset every sequence somebody
     * did size on purpose.
     *
     * @return true if the cache of a sequence takes no part in comparison
     */
    default boolean isIgnoreSequenceCache() {
        return false;
    }

    /**
     * Reports whether {@code ALTER TABLE} is written without {@code ONLY}.
     * <p>
     * {@code ONLY} restricts a change to the table named, keeping it away from
     * the children that inherit from it. It matters only where such children
     * exist, and it is outright rejected on a TimescaleDB hypertable for every
     * subcommand but {@code SET/RESET (...)}.
     * <p>
     * Whether a table is a hypertable is not a fact the generator can know: the
     * set differs between the database a script is built against and the
     * databases it is applied to, so no per-object decision made at generation
     * time can be right everywhere. A project without table inheritance loses
     * nothing by dropping the word, and gains a script that applies wherever a
     * hypertable happens to live.
     * <p>
     * The two forms TimescaleDB does permit - {@code SET/RESET (...)} on the
     * table and on a column - keep {@code ONLY} regardless, because there the
     * word still says something and costs nothing.
     *
     * @return true if ALTER TABLE is written without ONLY
     */
    default boolean isNoAlterTableOnly() {
        return false;
    }

    /**
     * Reports whether the statistics target of a column takes part in
     * comparison and produces an {@code ALTER}.
     * <p>
     * The target decides how much the planner samples. It says nothing about
     * what the column may hold, so a project and a database that disagree about
     * it hold the same schema. The situation this exists for is a database whose
     * targets are set by a scheduled job of its own: every value that job picks
     * shows up as a difference the project did not ask for, and the {@code ALTER}
     * that carries it over is undone on the job's next run.
     * <p>
     * The relaxation reaches the comparison and the {@code ALTER} it produces,
     * and stops there. A column that is created still carries the target written
     * in the project, because that target is the one deliberate statement about
     * it the project makes.
     * <p>
     * This is about {@code pg_attribute.attstattarget}. Extended statistics
     * objects - {@code CREATE STATISTICS} - are a different thing sharing an
     * unfortunate name, and take no notice of this.
     *
     * @return true if the statistics target takes no part in comparison
     */
    default boolean isIgnoreColumnStatistics() {
        return false;
    }

    /**
     * Reports which of the two compared states the migration script built from
     * this comparison produces, that is, which one is its target.
     * <p>
     * The convention of the core is that a comparison of {@code (old, new)}
     * feeds a script that turns the old state into the new one, so the target
     * is the new side and this returns {@code false}. A caller that compares
     * the two states in the opposite order to the script it generates - the
     * Eclipse project editor compares the project against the database and
     * reverts the tree before generating - must say so here.
     * <p>
     * Only comparison reads this. Script generation always knows its own
     * direction from the arguments it is given.
     *
     * @return true when the old side of the comparison is the migration target
     */
    default boolean isMigrationTargetOldSide() {
        return false;
    }

    /**
     * Reports whether table columns must be rendered in name order instead of
     * the order they are stored in.
     * <p>
     * Display only. The rendered order of the columns is part of the SQL a
     * {@code CREATE TABLE} statement carries, so this must never be enabled on
     * a path that produces a migration script: it would change the script.
     * It exists for a side by side view of two states of one object, where
     * rendering each side in its own stored order shows a permutation of the
     * columns as a difference.
     *
     * @return true if columns are rendered in name order
     */
    default boolean isSortColumnsForDisplay() {
        return false;
    }

    /**
     * Checks if function body dependencies analysis is enabled.
     *
     * @return true if function body dependencies are enabled
     */
    boolean isEnableFunctionBodiesDependencies();

    /**
     * Checks if data movement mode is enabled for migrations.
     *
     * @return true if data movement mode is enabled
     */
    boolean isDataMovementMode();

    /**
     * Checks if objects should be dropped before creating in migrations.
     *
     * @return true if drop-before-create is enabled
     */
    boolean isDropBeforeCreate();

    /**
     * Checks if migration should stop when encountering not-allowed operations.
     *
     * @return true if stop-on-not-allowed is enabled
     */
    boolean isStopNotAllowed();

    /**
     * Checks if only selected objects should be processed.
     *
     * @return true if selected-only mode is enabled
     */
    boolean isSelectedOnly();

    /**
     * Checks if concurrent modifications should be ignored.
     *
     * @return true if concurrent modifications are ignored
     */
    boolean isIgnoreConcurrentModification();

    /**
     * Checks if view definitions should be simplified.
     *
     * @return true if view simplification is enabled
     */
    boolean isSimplifyView();

    /**
     * Checks if not null CONSTRAINT should be simplified.
     *
     * @return true if not null CONSTRAINT simplification is enabled
     */
    boolean isSimplifyNotNull();

    /**
     * Checks if function body checking should be disabled.
     *
     * @return true if function body checking is disabled
     */
    boolean isDisableCheckFunctionBodies();

    /**
     * Returns the parallel load flag that controls how databases are loaded during comparison.
     *
     * @return {@code true} for parallel loading (faster, more resource-intensive),
     *         {@code false} for sequential loading (slower, fewer resources)
     */
    boolean isParallelLoad();

    /**
     * Returns whether PostgreSQL project-to-JDBC comparisons should exchange
     * routine body fingerprints before fetching full bodies from the server.
     *
     * @return true to reuse exactly matching project routine bodies
     */
    default boolean isPgRoutineBodyHashFirst() {
        return DEFAULT_PG_ROUTINE_BODY_HASH_FIRST;
    }

    /**
     * Returns whether the deferred parse and dependency analysis of a routine
     * body may be skipped when the body is byte-identical between the project
     * and the database (matched on the hash-first path) and is late-bound for
     * the server: any language other than {@code sql}, or a quoted {@code sql}
     * body while {@link #isDisableCheckFunctionBodies()} is on. Bodies stored
     * parsed by the server ({@code BEGIN ATOMIC}) are never skipped. Skipped
     * bodies contribute no body-derived dependencies, so drop-cascade scripts
     * may omit collateral recreates of such unchanged functions. Effective
     * only with {@link #isPgRoutineBodyHashFirst()}; changed or unmatched
     * bodies are always parsed and analyzed. Enabled by default; disable for
     * a rollback to the full-analysis behavior.
     *
     * @return true to skip analysis of matched late-bound routine bodies
     */
    default boolean isPgRoutineBodySkipMatchedAnalysis() {
        return DEFAULT_PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS;
    }

    /**
     * Gets the maximum number of unmatched PostgreSQL routine bodies fetched
     * by one residual JDBC request.
     *
     * @return positive residual routine count limit
     */
    default int getPgRoutineBodyResidualBatchCount() {
        return DEFAULT_PG_ROUTINE_BODY_RESIDUAL_BATCH_COUNT;
    }

    /**
     * Gets the predicted UTF-8 byte grouping budget for unmatched PostgreSQL
     * routine bodies. A single body larger than this budget is fetched alone.
     *
     * @return positive residual routine byte grouping budget
     */
    default long getPgRoutineBodyResidualBatchBytes() {
        return DEFAULT_PG_ROUTINE_BODY_RESIDUAL_BATCH_BYTES;
    }

    /**
     * Gets the base directory of the persistent local cache for PostgreSQL
     * catalog payloads. Each load resolves a secret-free target/session child
     * below this base; routine bodies and rows share only that target-scoped
     * child. Routine-body entries are consulted on the hash-first residual
     * path; row entries are independently consulted when
     * {@link #isPgCatalogCacheRows()} is enabled. Multiple local processes may
     * share the base: publication is atomic and contended size maintenance is
     * skipped rather than blocked. {@code null} disables both cache uses and
     * the target identity query, preserving existing behavior.
     *
     * @return cache directory path string, or {@code null} when disabled
     */
    default String getPgCatalogCacheDir() {
        return null;
    }

    /**
     * Gets the size cap of the persistent PostgreSQL catalog cache in
     * megabytes. After a load finishes, oldest published entries in the
     * target-scoped store are pruned until it fits the cap. Immutable reader
     * pack generations are evicted as logical units; their manifest and pack
     * are never pruned independently. Pruning is non-blocking under another
     * process's maintenance lock. Only meaningful with
     * {@link #getPgCatalogCacheDir()}.
     *
     * @return positive cache size cap in megabytes
     */
    default long getPgCatalogCacheMaxMb() {
        return DEFAULT_PG_CATALOG_CACHE_MAX_MB;
    }

    /**
     * Returns whether the persistent PostgreSQL catalog cache additionally
     * stores each catalog reader as one immutable packed generation with
     * binary server-side row hashes, so repeat comparisons transfer only
     * changed rows and avoid one file per row. Requires
     * {@link #getPgCatalogCacheDir()}; without a cache directory the flag is
     * a documented no-op. Disabled by default so the routine-body cache of
     * the directory setting can be used alone.
     *
     * @return true when row-level catalog caching is enabled
     */
    default boolean isPgCatalogCacheRows() {
        return DEFAULT_PG_CATALOG_CACHE_ROWS;
    }

    /**
     * Returns whether the target identity query may also authorize exact
     * same-snapshot pack replay before the packed row-hash pass. This does not
     * add a per-reader query: the snapshot token piggybacks the existing
     * secret-free identity row. Replay remains fail-closed unless the server
     * identity is trustworthy and the reader declares deterministic order.
     * Disabled by default to preserve the existing comparison behavior.
     *
     * @return true when the optional fingerprint probe is enabled
     */
    default boolean isPgCatalogCacheFingerprintProbe() {
        return DEFAULT_PG_CATALOG_CACHE_FINGERPRINT_PROBE;
    }

    /**
     * Returns the optional sink for comparison cache telemetry. The default
     * sink is disabled so existing callers incur no callback work.
     *
     * @return comparison telemetry sink
     */
    default IComparisonTelemetry getComparisonTelemetry() {
        return IComparisonTelemetry.NO_OP;
    }

    /**
     * Returns the parser execution policy for loaders created with these
     * settings. Existing callers retain the process-wide shared pool.
     *
     * @return parser execution policy
     */
    default ParserExecutionPolicy getParserExecutionPolicy() {
        return ParserExecutionPolicy.SHARED;
    }

    /**
     * Gets the number of worker connections used to read PostgreSQL catalogs
     * in parallel lanes sharing the primary connection's snapshot. Values
     * below {@code 2} keep the sequential reader flow. The parallel flow is
     * used only for plain PostgreSQL comparison loads; Greenplum, the system
     * loader and unsupported shared-snapshot setups stay sequential. When
     * row caching is enabled, one load-scoped cache is shared by the lanes and
     * every derived cache query stays on its lane connection.
     *
     * @return non-negative worker connection count; {@code 0} disables
     */
    default int getPgParallelCatalogReaders() {
        return DEFAULT_PG_PARALLEL_CATALOG_READERS;
    }

    /**
     * Returns whether this configuration requires the comparison factory path.
     * Parallel loading alone does not require factories, preserving the
     * preconstructed-loader API for existing callers.
     *
     * @return {@code true} when paired loader coordination is mandatory
     */
    default boolean requiresComparisonLoaderFactories() {
        return isPgRoutineBodyHashFirst();
    }

    /**
     * Gets the JDBC fetch-size hint applied to statements that read database catalogs.
     * A value of {@code 0} preserves the JDBC driver's default behavior.
     *
     * @return non-negative JDBC fetch size
     */
    default int getJdbcFetchSize() {
        return 0;
    }

    /**
     * Returns whether JDBC loaders should collect per-object DDL author
     * metadata recorded by the pg_dbo_timestamp extension. Authors are
     * display-only metadata: they never participate in comparison or script
     * generation, so batch diff runs may disable the collection to drop the
     * dbots_event_data join from every catalog query. The default preserves
     * existing API and IDE behavior.
     *
     * @return true if author metadata should be read
     */
    default boolean isReadAuthors() {
        return true;
    }

    /**
     * Whether loaders should build the file-to-object-location reverse index.
     * The default preserves API, UI, and third-party settings behavior.
     *
     * @return true if the reverse index should be built
     */
    default boolean isCollectObjectReferences() {
        return true;
    }

    /**
     * Gets the ordered root-relative filter applied to top-level project files.
     *
     * @return project file filter; allows every file by default
     */
    default ProjectFileFilter getProjectFileFilter() {
        return ProjectFileFilter.ALLOW_ALL;
    }

    /**
     * Checks whether automatic loading of project auxiliary files should be disabled
     *
     * @return true if whether automatic loading of project auxiliary files should be disabled
     */
    boolean isDisableAutoLoad();

    /**
     * Gets the input character encoding name.
     *
     * @return the character encoding name
     */
    String getInCharsetName();

    /**
     * Gets the time zone setting.
     *
     * @return the time zone string
     */
    String getTimeZone();

    /**
     * Gets the format configuration for code formatting.
     *
     * @return the format configuration instance
     */
    IFormatConfiguration getFormatConfiguration();

    /**
     * Gets the collection of allowed database object types for processing.
     *
     * @return collection of allowed object types
     */
    Collection<DbObjType> getAllowedTypes();

    /**
     * Gets the collection of pre-processing file paths.
     *
     * @return collection of pre-processing file paths
     */
    Collection<String> getPreFilePath();

    /**
     * Gets the collection of post-processing file paths.
     *
     * @return collection of post-processing file paths
     */
    Collection<String> getPostFilePath();

    /**
     * Creates a copy of this settings instance.
     *
     * @return a new settings instance with the same configuration
     */
    ISettings copy();

    /**
     * Gets the progress monitor for the current operation.
     *
     * @return the progress monitor
     */
    IMonitor getMonitor();

    /**
     * Sets the progress monitor for the current operation.
     *
     * @param monitor the progress monitor
     */
    void setMonitor(IMonitor monitor);

    /**
     * Gets the ignore list used to filter objects during diff operations.
     *
     * @return the ignore list
     */
    IgnoreList getIgnoreList();

    /**
     * Gets the holder of what names the columns of a table, for the one
     * operation these settings serve.
     * <p>
     * A {@code type=COLUMN} rule hides a column only while nothing in the
     * database still names it, and finding out means reading every statement.
     * The reading is the same work whether it answers for one table or for all
     * of them, while an export asks about one table at a time - 13 323 times on
     * the project this was measured on, which is 482 s instead of 40 s. The
     * holder is what turns those thousands of readings back into one.
     * <p>
     * These settings are the holder because their lifetime is already exactly
     * the right one. The Eclipse plugin builds them fresh for every operation
     * and the CLI for every output of a batch run, so an index kept here cannot
     * outlive the operation that built it - which it must not, since a model is
     * rebuilt one statement at a time while an editor is open and an index
     * remembered across such a rebuild would answer for a view that has since
     * stopped reading the column. For the same reason no copy of these settings
     * carries the index over, see {@code AbstractSettings.copy()}: a copy is
     * another operation, comparing another pair of databases.
     * <p>
     * The default is a holder that remembers nothing, so an implementation of
     * this interface that is none of ours keeps exactly the behaviour it had
     * before there was an index - correct, and as slow as it was.
     *
     * @return the holder for this operation, never {@code null}
     */
    default ColumnUsers getColumnUsers() {
        return ColumnUsers.NONE;
    }

    /**
     * Gets the holder of how many objects the ignore rules kept out of this
     * comparison, for the one operation these settings serve.
     * <p>
     * An object a rule hides whole never becomes a node of the difference tree,
     * so nothing about it can be marked or coloured - it is simply not there,
     * and a comparison whose rules have quietly stopped matching looks exactly
     * like one whose rules are working. The count is what a reader is told
     * instead, see {@link HiddenObjects}.
     * <p>
     * Held here for the reason {@link #getColumnUsers()} is: the lifetime of
     * these settings is exactly one comparison, so a number kept here cannot
     * outlive the tree it describes, and no copy of them carries it over. Held
     * here rather than recomputed also settles the cost - the number is taken
     * once, while the passes that hide are already walking the tree, and every
     * rendering after that reads a finished answer.
     * <p>
     * The default is a holder that records nothing, so an implementation of this
     * interface that is none of ours keeps exactly the behaviour it had before
     * there was a count.
     *
     * @return the holder for this operation, never {@code null}
     */
    default HiddenObjects getHiddenObjects() {
        return HiddenObjects.NONE;
    }

    /**
     * Parses the ignore list file and adds its rules to the ignore list.
     *
     * @param ignoreListPath path to the ignore list file
     * @throws IOException if the file cannot be read or parsed
     */
    void addIgnoreList(Path ignoreListPath) throws IOException;

    /**
     * Parses the ignore schema list file and adds its rules to the ignore schema list.
     *
     * @param ignoreSchemaListPath path to the ignore schema list file
     * @throws IOException if the file cannot be read or parsed
     */
    void addIgnoreSchemaList(Path ignoreSchemaListPath) throws IOException;

    /**
     * Checks whether the schema is allowed by the ignore schema list.
     *
     * @param schemaName schema name to check
     * @return true if the schema should be processed
     */
    boolean isAllowedSchema(String schemaName);

    /**
     * Checks whether a project loader caller explicitly excluded the schema
     * before SQL parsing. The default keeps existing settings implementations
     * and non-project loaders unaffected.
     *
     * @param schemaName exact, case-sensitive schema name
     * @return true if the schema must be skipped by the project loader
     */
    default boolean isAdditionalSchemaExcluded(String schemaName) {
        return false;
    }

    /**
     * Gets the caller-scoped exact schema exclusions applied by the project
     * loader. The set is part of the model identity: two loads that differ
     * here produce different project models, so any consumer that reuses or
     * caches an analyzed model must include it in its reuse key or refuse to
     * reuse while it is non-empty. The default keeps existing settings
     * implementations and non-project loaders unaffected.
     *
     * @return immutable set of exact, case-sensitive schema names, never null
     */
    default Set<String> getAdditionalExcludedSchemas() {
        return Set.of();
    }

    /**
     * Gets additional dependencies used during dependency resolution.
     *
     * @return list of additional dependencies
     */
    List<Dependency> getAdditionalDependencies();

    /**
     * Adds additional dependencies used during dependency resolution.
     *
     * @param deps dependencies to add
     */
    void addAdditionalDependencies(Collection<Dependency> deps);

    /**
     * Gets errors collected during loading and parsing.
     *
     * @return list of collected errors
     */
    List<Object> getErrors();

    /**
     * Adds an error to the error accumulator.
     *
     * @param error error to add
     */
    void addError(Object error);

    /**
     * Adds errors to the error accumulator.
     *
     * @param errors errors to add
     */
    void addErrors(Collection<Object> errors);

    /**
     * Clears all errors.
     */
    void clearErrors();

    /**
     * Gets the detected database version.
     *
     * @return the database version, or null if not detected yet
     */
    ISupportedVersion getVersion();

    /**
     * Stores the detected database version. If a version is already set,
     * the lower numeric version of the two is kept.
     *
     * @param version detected database version
     */
    void setVersion(ISupportedVersion version);

    /**
     * Resets the database version.
     */
    void resetVersion();

    /**
     * Sets whether privileges should be ignored during comparison.
     *
     * @param ignorePrivileges true to ignore privileges
     */
    void setIgnorePrivileges(boolean ignorePrivileges);

    /**
     * Get the cluster name for ClickHouse
     *
     * @return cluster name
     */
    String getClusterName();

    /**
     * Return flag to use correct migration script syntax
     *
     * @return true to generate migration script with actual syntax
     */
    boolean isUseActualVersionSyntax();
}
