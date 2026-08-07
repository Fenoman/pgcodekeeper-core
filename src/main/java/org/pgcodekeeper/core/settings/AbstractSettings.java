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

import org.pgcodekeeper.core.database.api.jdbc.ISupportedVersion;
import org.pgcodekeeper.core.database.base.parser.ParserExecutionPolicy;
import org.pgcodekeeper.core.dependencieslist.Dependency;
import org.pgcodekeeper.core.ignorelist.IIgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoreSchemaList;
import org.pgcodekeeper.core.model.difftree.ColumnUsers;
import org.pgcodekeeper.core.model.difftree.HiddenObjects;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.utils.FileUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Base implementation of {@link ISettings} that owns the per-operation state:
 * progress monitor, ignore lists, additional dependencies, error accumulator and
 * detected database version.
 */
public abstract class AbstractSettings implements ISettings {

    private final List<Object> errors = Collections.synchronizedList(new ArrayList<>());
    private final IgnoreList ignoreList = new IgnoreList();
    private final IgnoreSchemaList ignoreSchemaList = new IgnoreSchemaList();
    private final List<Dependency> additionalDependencies = new ArrayList<>();

    /**
     * The index of what names a column, for the one operation these settings
     * serve, see {@link ISettings#getColumnUsers()}.
     * <p>
     * Final and assigned here, which is the point rather than a detail: every
     * copy of these settings - {@link #copy()} and every {@code shallowCopy()}
     * of every subclass alike - builds a new instance and therefore starts with
     * an empty one. A copy is another operation comparing another pair of
     * databases, and no hand editing a copy method can carry this over by
     * accident.
     */
    private final ColumnUsers columnUsers = ColumnUsers.forOperation();

    /**
     * How many objects the ignore rules kept out of this comparison, see
     * {@link ISettings#getHiddenObjects()}.
     * <p>
     * Final and assigned here for the reason {@link #columnUsers} is: it belongs
     * to one comparison, and a copy of these settings is another comparison
     * whose rules may well take a different amount.
     */
    private final HiddenObjects hiddenObjects = HiddenObjects.forOperation();

    private IMonitor monitor = new NullMonitor();
    private ISupportedVersion version;
    private boolean pgRoutineBodyHashFirst = DEFAULT_PG_ROUTINE_BODY_HASH_FIRST;
    private boolean pgRoutineBodySkipMatchedAnalysis =
            DEFAULT_PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS;
    private int pgRoutineBodyResidualBatchCount =
            DEFAULT_PG_ROUTINE_BODY_RESIDUAL_BATCH_COUNT;
    private long pgRoutineBodyResidualBatchBytes =
            DEFAULT_PG_ROUTINE_BODY_RESIDUAL_BATCH_BYTES;
    private String pgCatalogCacheDir;
    private long pgCatalogCacheMaxMb = DEFAULT_PG_CATALOG_CACHE_MAX_MB;
    private boolean pgCatalogCacheRows = DEFAULT_PG_CATALOG_CACHE_ROWS;
    private boolean pgCatalogCacheFingerprintProbe =
            DEFAULT_PG_CATALOG_CACHE_FINGERPRINT_PROBE;
    private IComparisonTelemetry comparisonTelemetry = IComparisonTelemetry.NO_OP;
    private ParserExecutionPolicy parserExecutionPolicy = ParserExecutionPolicy.SHARED;
    private int pgParallelCatalogReaders = DEFAULT_PG_PARALLEL_CATALOG_READERS;
    private ProjectFileFilter projectFileFilter = ProjectFileFilter.ALLOW_ALL;
    private Set<String> additionalExcludedSchemas = Set.of();
    private boolean migrationTargetOldSide;
    private boolean sortColumnsForDisplay;

    /**
     * Adds caller-scoped exact schema exclusions to the project loader.
     * Project files and command-line settings never populate this set
     * automatically. Names must have an unambiguous representation in both
     * split and legacy flat project layouts, so empty names, dotted names and
     * names rewritten by {@link FileUtils#getValidFilename(String)} are rejected.
     *
     * @param schemaNames exact, case-sensitive schema names
     * @throws NullPointerException if the set or any name is {@code null}
     * @throws IllegalArgumentException if a name cannot be filtered safely
     */
    public void setAdditionalExcludedSchemas(Set<String> schemaNames) {
        Objects.requireNonNull(schemaNames, "schemaNames");
        for (String schemaName : schemaNames) {
            Objects.requireNonNull(schemaName, "schemaName");
            if (schemaName.isEmpty()) {
                throw new IllegalArgumentException(
                        "Additional excluded schema name must not be empty");
            }
            if (schemaName.indexOf('.') >= 0) {
                throw new IllegalArgumentException(
                        "Additional excluded schema name is ambiguous in flat project layout: "
                                + schemaName);
            }
            if (!FileUtils.getValidFilename(schemaName).equals(schemaName)) {
                throw new IllegalArgumentException(
                        "Additional excluded schema name is not filesystem-safe: "
                                + schemaName);
            }
        }
        additionalExcludedSchemas = Set.copyOf(schemaNames);
    }

    @Override
    public boolean isMigrationTargetOldSide() {
        return migrationTargetOldSide;
    }

    /**
     * Declares which compared state the migration script produces.
     *
     * @param migrationTargetOldSide true when the old side of the comparison is
     *                               the state the script produces
     * @see ISettings#isMigrationTargetOldSide()
     */
    public void setMigrationTargetOldSide(boolean migrationTargetOldSide) {
        this.migrationTargetOldSide = migrationTargetOldSide;
    }

    @Override
    public boolean isSortColumnsForDisplay() {
        return sortColumnsForDisplay;
    }

    /**
     * Switches the display-only name order of table columns.
     *
     * @param sortColumnsForDisplay true to render columns in name order
     * @see ISettings#isSortColumnsForDisplay()
     */
    public void setSortColumnsForDisplay(boolean sortColumnsForDisplay) {
        this.sortColumnsForDisplay = sortColumnsForDisplay;
    }

    @Override
    public ProjectFileFilter getProjectFileFilter() {
        return projectFileFilter;
    }

    public void setProjectFileFilter(ProjectFileFilter projectFileFilter) {
        this.projectFileFilter = Objects.requireNonNull(projectFileFilter);
    }

    @Override
    public int getPgParallelCatalogReaders() {
        return pgParallelCatalogReaders;
    }

    public void setPgParallelCatalogReaders(int pgParallelCatalogReaders) {
        if (pgParallelCatalogReaders < 0) {
            throw new IllegalArgumentException(
                    "PostgreSQL parallel catalog reader count must not be negative");
        }
        this.pgParallelCatalogReaders = pgParallelCatalogReaders;
    }

    @Override
    public boolean isPgRoutineBodyHashFirst() {
        return pgRoutineBodyHashFirst;
    }

    public void setPgRoutineBodyHashFirst(boolean pgRoutineBodyHashFirst) {
        this.pgRoutineBodyHashFirst = pgRoutineBodyHashFirst;
    }

    @Override
    public boolean isPgRoutineBodySkipMatchedAnalysis() {
        return pgRoutineBodySkipMatchedAnalysis;
    }

    public void setPgRoutineBodySkipMatchedAnalysis(
            boolean pgRoutineBodySkipMatchedAnalysis) {
        this.pgRoutineBodySkipMatchedAnalysis = pgRoutineBodySkipMatchedAnalysis;
    }

    @Override
    public int getPgRoutineBodyResidualBatchCount() {
        return pgRoutineBodyResidualBatchCount;
    }

    public void setPgRoutineBodyResidualBatchCount(int pgRoutineBodyResidualBatchCount) {
        if (pgRoutineBodyResidualBatchCount <= 0) {
            throw new IllegalArgumentException(
                    "PostgreSQL routine body residual batch count must be positive");
        }
        this.pgRoutineBodyResidualBatchCount = pgRoutineBodyResidualBatchCount;
    }

    @Override
    public long getPgRoutineBodyResidualBatchBytes() {
        return pgRoutineBodyResidualBatchBytes;
    }

    public void setPgRoutineBodyResidualBatchBytes(long pgRoutineBodyResidualBatchBytes) {
        if (pgRoutineBodyResidualBatchBytes <= 0) {
            throw new IllegalArgumentException(
                    "PostgreSQL routine body residual batch bytes must be positive");
        }
        this.pgRoutineBodyResidualBatchBytes = pgRoutineBodyResidualBatchBytes;
    }

    @Override
    public String getPgCatalogCacheDir() {
        return pgCatalogCacheDir;
    }

    public void setPgCatalogCacheDir(String pgCatalogCacheDir) {
        this.pgCatalogCacheDir = pgCatalogCacheDir;
    }

    @Override
    public long getPgCatalogCacheMaxMb() {
        return pgCatalogCacheMaxMb;
    }

    public void setPgCatalogCacheMaxMb(long pgCatalogCacheMaxMb) {
        if (pgCatalogCacheMaxMb <= 0) {
            throw new IllegalArgumentException(
                    "PostgreSQL catalog cache size limit must be positive");
        }
        this.pgCatalogCacheMaxMb = pgCatalogCacheMaxMb;
    }

    @Override
    public boolean isPgCatalogCacheRows() {
        return pgCatalogCacheRows;
    }

    public void setPgCatalogCacheRows(boolean pgCatalogCacheRows) {
        this.pgCatalogCacheRows = pgCatalogCacheRows;
    }

    @Override
    public boolean isPgCatalogCacheFingerprintProbe() {
        return pgCatalogCacheFingerprintProbe;
    }

    public void setPgCatalogCacheFingerprintProbe(boolean pgCatalogCacheFingerprintProbe) {
        this.pgCatalogCacheFingerprintProbe = pgCatalogCacheFingerprintProbe;
    }

    @Override
    public IComparisonTelemetry getComparisonTelemetry() {
        return comparisonTelemetry;
    }

    public void setComparisonTelemetry(IComparisonTelemetry comparisonTelemetry) {
        this.comparisonTelemetry = Objects.requireNonNull(comparisonTelemetry, "telemetry");
    }

    @Override
    public ParserExecutionPolicy getParserExecutionPolicy() {
        return parserExecutionPolicy;
    }

    public void setParserExecutionPolicy(ParserExecutionPolicy parserExecutionPolicy) {
        this.parserExecutionPolicy = Objects.requireNonNull(
                parserExecutionPolicy, "parserExecutionPolicy");
    }

    @Override
    public IMonitor getMonitor() {
        return monitor;
    }

    @Override
    public void setMonitor(IMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public IgnoreList getIgnoreList() {
        return ignoreList;
    }

    @Override
    public void addIgnoreList(Path ignoreListPath) throws IOException {
        IIgnoreList.parseIgnoreList(ignoreListPath, ignoreList);
    }

    @Override
    public void addIgnoreSchemaList(Path ignoreSchemaListPath) throws IOException {
        IIgnoreList.parseIgnoreList(ignoreSchemaListPath, ignoreSchemaList);
    }

    @Override
    public boolean isAllowedSchema(String schemaName) {
        return ignoreSchemaList.getNameStatus(schemaName);
    }

    @Override
    public boolean isAdditionalSchemaExcluded(String schemaName) {
        return additionalExcludedSchemas.contains(schemaName);
    }

    @Override
    public Set<String> getAdditionalExcludedSchemas() {
        return additionalExcludedSchemas;
    }

    @Override
    public List<Dependency> getAdditionalDependencies() {
        return additionalDependencies;
    }

    @Override
    public void addAdditionalDependencies(Collection<Dependency> deps) {
        additionalDependencies.addAll(deps);
    }

    @Override
    public List<Object> getErrors() {
        return errors;
    }

    @Override
    public void addError(Object error) {
        errors.add(error);
    }

    @Override
    public void addErrors(Collection<Object> errors) {
        this.errors.addAll(errors);
    }

    @Override
    public void clearErrors() {
        errors.clear();
    }

    @Override
    public ColumnUsers getColumnUsers() {
        return columnUsers;
    }

    @Override
    public HiddenObjects getHiddenObjects() {
        return hiddenObjects;
    }

    @Override
    public ISupportedVersion getVersion() {
        return version;
    }

    @Override
    public void setVersion(ISupportedVersion version) {
        if (null == this.version || !this.version.isLE(version.getVersion())) {
            this.version = version;
        }
    }

    @Override
    public void resetVersion() {
        this.version = null;
    }

    /**
     * A copy carries over every setting and none of the answers read under them.
     * <p>
     * {@link #columnUsers} and {@link #hiddenObjects} are deliberately absent
     * below and cannot be added: both are final and belong to one operation,
     * and a copy is another one.
     * <hr><br>
     * {@inheritDoc}
     */
    @Override
    public ISettings copy() {
        var copy = shallowCopy();
        copy.monitor = monitor;
        copy.version = version;
        copy.pgRoutineBodyHashFirst = pgRoutineBodyHashFirst;
        copy.pgRoutineBodySkipMatchedAnalysis = pgRoutineBodySkipMatchedAnalysis;
        copy.pgRoutineBodyResidualBatchCount = pgRoutineBodyResidualBatchCount;
        copy.pgRoutineBodyResidualBatchBytes = pgRoutineBodyResidualBatchBytes;
        copy.pgCatalogCacheDir = pgCatalogCacheDir;
        copy.pgCatalogCacheMaxMb = pgCatalogCacheMaxMb;
        copy.pgCatalogCacheRows = pgCatalogCacheRows;
        copy.pgCatalogCacheFingerprintProbe = pgCatalogCacheFingerprintProbe;
        copy.comparisonTelemetry = comparisonTelemetry;
        copy.parserExecutionPolicy = parserExecutionPolicy;
        copy.pgParallelCatalogReaders = pgParallelCatalogReaders;
        copy.projectFileFilter = projectFileFilter;
        copy.additionalExcludedSchemas = additionalExcludedSchemas;
        copy.migrationTargetOldSide = migrationTargetOldSide;
        copy.sortColumnsForDisplay = sortColumnsForDisplay;
        copy.errors.addAll(errors);
        copy.additionalDependencies.addAll(additionalDependencies);
        copyIgnoreList(ignoreList, copy.ignoreList);
        copyIgnoreList(ignoreSchemaList, copy.ignoreSchemaList);
        return copy;
    }

    private static void copyIgnoreList(IIgnoreList source, IIgnoreList target) {
        target.setShow(source.isShow());
        source.getList().stream()
                .map(rule -> rule.copy(rule.getName()))
                .forEach(target::add);
    }

    protected abstract AbstractSettings shallowCopy();
}
