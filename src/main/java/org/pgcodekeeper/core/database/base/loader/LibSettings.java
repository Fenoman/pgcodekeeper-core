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
package org.pgcodekeeper.core.database.base.loader;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Queue;

import org.pgcodekeeper.core.database.api.formatter.IFormatConfiguration;
import org.pgcodekeeper.core.database.api.jdbc.ISupportedVersion;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.base.parser.ParserExecutionPolicy;
import org.pgcodekeeper.core.dependencieslist.Dependency;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.model.difftree.ColumnUsers;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.AbstractSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.settings.ProjectFileFilter;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;

/**
 * View of the parent settings for library loading that prevents libraries
 * from adding their own ignore lists and additional dependencies.
 * <p>
 * All state (monitor, ignore lists, errors, version) is shared with the parent
 * settings; only the isIgnorePrivileges flag may differ per library. When loading
 * library schemas, any {@code .pgcodekeeperignore}, {@code .pgcodekeeperignoreschema}
 * or {@code .pgcodekeeperdependencies} files found in library sources are ignored.
 * Libraries inherit these settings from the main database configuration instead.
 */
class LibSettings implements ISettings, ParserTaskQueueProvider {

    private final ISettings parent;
    private final boolean isIgnorePrivileges;
    private final Queue<AntlrTask<?>> parserTasks;

    public LibSettings(ISettings parent, boolean isIgnorePrivileges) {
        this(parent, isIgnorePrivileges,
                parent instanceof ParserTaskQueueProvider provider
                        ? provider.getParserTaskQueue()
                        : null);
    }

    LibSettings(ISettings parent, boolean isIgnorePrivileges,
            Queue<AntlrTask<?>> parserTasks) {
        this.parent = parent;
        this.isIgnorePrivileges = isIgnorePrivileges;
        this.parserTasks = parserTasks;
    }

    @Override
    public Queue<AntlrTask<?>> getParserTaskQueue() {
        return parserTasks;
    }

    @Override
    public ProjectFileFilter getProjectFileFilter() {
        return ProjectFileFilter.ALLOW_ALL;
    }

    @Override
    public boolean isIgnorePrivileges() {
        return isIgnorePrivileges;
    }

    @Override
    public void setIgnorePrivileges(boolean ignorePrivileges) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addIgnoreList(Path ignoreListPath) {
        // no impl, libraries must not add their own ignore lists
    }

    @Override
    public void addIgnoreSchemaList(Path ignoreSchemaListPath) {
        // no impl, libraries must not add their own ignore schema lists
    }

    @Override
    public void addAdditionalDependencies(Collection<Dependency> deps) {
        // no impl, libraries must not add their own dependencies
    }

    @Override
    public ISettings copy() {
        ISettings copy = unwrapLibrarySettings(parent.copy());
        copy.setIgnorePrivileges(isIgnorePrivileges);
        if (copy.getProjectFileFilter() != ProjectFileFilter.ALLOW_ALL
                && copy instanceof AbstractSettings mutableCopy) {
            mutableCopy.setProjectFileFilter(ProjectFileFilter.ALLOW_ALL);
        }
        boolean requiresWrapper = parserTasks != null
                || copy.getProjectFileFilter() != ProjectFileFilter.ALLOW_ALL;
        return requiresWrapper
                ? new LibSettings(copy, isIgnorePrivileges, parserTasks)
                : copy;
    }

    private static ISettings unwrapLibrarySettings(ISettings settings) {
        ISettings unwrapped = settings;
        while (unwrapped instanceof LibSettings librarySettings) {
            unwrapped = librarySettings.parent;
        }
        return unwrapped;
    }

    @Override
    public IMonitor getMonitor() {
        return parent.getMonitor();
    }

    @Override
    public void setMonitor(IMonitor monitor) {
        // no impl, monitor is inherited from the parent settings
    }

    @Override
    public void clearErrors() {
        // no impl
    }

    @Override
    public void resetVersion() {
        // no impl
    }

    @Override
    public IgnoreList getIgnoreList() {
        return parent.getIgnoreList();
    }

    /**
     * The library shares the operation of its parent, and therefore its index of
     * what names a column, exactly as it shares the ignore list that index is
     * asked about. Falling back to the default here would leave a library with
     * no operation to scope one to, which is not true of it: it is loaded by one.
     */
    @Override
    public ColumnUsers getColumnUsers() {
        return parent.getColumnUsers();
    }

    @Override
    public boolean isAllowedSchema(String schemaName) {
        return parent.isAllowedSchema(schemaName);
    }

    @Override
    public List<Dependency> getAdditionalDependencies() {
        return parent.getAdditionalDependencies();
    }

    @Override
    public List<Object> getErrors() {
        return parent.getErrors();
    }

    @Override
    public void addError(Object error) {
        parent.addError(error);
    }

    @Override
    public void addErrors(Collection<Object> errors) {
        parent.addErrors(errors);
    }

    @Override
    public ISupportedVersion getVersion() {
        return parent.getVersion();
    }

    @Override
    public void setVersion(ISupportedVersion version) {
        parent.setVersion(version);
    }

    @Override
    public boolean isConcurrentlyMode() {
        return parent.isConcurrentlyMode();
    }

    @Override
    public boolean isAddTransaction() {
        return parent.isAddTransaction();
    }

    @Override
    public boolean isGenerateExists() {
        return parent.isGenerateExists();
    }

    @Override
    public boolean isGenerateConstraintNotValid() {
        return parent.isGenerateConstraintNotValid();
    }

    @Override
    public boolean isGenerateExistDoBlock() {
        return parent.isGenerateExistDoBlock();
    }

    @Override
    public boolean isPrintUsing() {
        return parent.isPrintUsing();
    }

    @Override
    public boolean isKeepNewlines() {
        return parent.isKeepNewlines();
    }

    @Override
    public boolean isCommentsToEnd() {
        return parent.isCommentsToEnd();
    }

    @Override
    public boolean isAutoFormatObjectCode() {
        return parent.isAutoFormatObjectCode();
    }

    @Override
    public boolean isIgnoreColumnOrder() {
        return parent.isIgnoreColumnOrder();
    }

    @Override
    public boolean isIgnoreSequenceCache() {
        return parent.isIgnoreSequenceCache();
    }

    @Override
    public boolean isNoAlterTableOnly() {
        return parent.isNoAlterTableOnly();
    }

    @Override
    public boolean isIgnoreColumnStatistics() {
        return parent.isIgnoreColumnStatistics();
    }

    @Override
    public boolean isMigrationTargetOldSide() {
        return parent.isMigrationTargetOldSide();
    }

    @Override
    public boolean isSortColumnsForDisplay() {
        return parent.isSortColumnsForDisplay();
    }

    @Override
    public boolean isEnableFunctionBodiesDependencies() {
        return parent.isEnableFunctionBodiesDependencies();
    }

    @Override
    public boolean isDataMovementMode() {
        return parent.isDataMovementMode();
    }

    @Override
    public boolean isDropBeforeCreate() {
        return parent.isDropBeforeCreate();
    }

    @Override
    public boolean isStopNotAllowed() {
        return parent.isStopNotAllowed();
    }

    @Override
    public boolean isSelectedOnly() {
        return parent.isSelectedOnly();
    }

    @Override
    public boolean isIgnoreConcurrentModification() {
        return parent.isIgnoreConcurrentModification();
    }

    @Override
    public boolean isSimplifyView() {
        return parent.isSimplifyView();
    }

    @Override
    public boolean isSimplifyNotNull() {
        return parent.isSimplifyNotNull();
    }

    @Override
    public boolean isDisableCheckFunctionBodies() {
        return parent.isDisableCheckFunctionBodies();
    }

    @Override
    public boolean isParallelLoad() {
        return parent.isParallelLoad();
    }

    @Override
    public boolean isPgRoutineBodyHashFirst() {
        return parent.isPgRoutineBodyHashFirst();
    }

    @Override
    public boolean isPgRoutineBodySkipMatchedAnalysis() {
        return parent.isPgRoutineBodySkipMatchedAnalysis();
    }

    @Override
    public int getPgRoutineBodyResidualBatchCount() {
        return parent.getPgRoutineBodyResidualBatchCount();
    }

    @Override
    public long getPgRoutineBodyResidualBatchBytes() {
        return parent.getPgRoutineBodyResidualBatchBytes();
    }

    @Override
    public String getPgCatalogCacheDir() {
        return parent.getPgCatalogCacheDir();
    }

    @Override
    public long getPgCatalogCacheMaxMb() {
        return parent.getPgCatalogCacheMaxMb();
    }

    @Override
    public boolean isPgCatalogCacheRows() {
        return parent.isPgCatalogCacheRows();
    }

    @Override
    public boolean isPgCatalogCacheFingerprintProbe() {
        return parent.isPgCatalogCacheFingerprintProbe();
    }

    @Override
    public IComparisonTelemetry getComparisonTelemetry() {
        return parent.getComparisonTelemetry();
    }

    @Override
    public boolean requiresComparisonLoaderFactories() {
        return parent.requiresComparisonLoaderFactories();
    }

    @Override
    public int getJdbcFetchSize() {
        return parent.getJdbcFetchSize();
    }

    @Override
    public ParserExecutionPolicy getParserExecutionPolicy() {
        return parent.getParserExecutionPolicy();
    }

    @Override
    public boolean isReadAuthors() {
        return parent.isReadAuthors();
    }

    @Override
    public boolean isCollectObjectReferences() {
        return parent.isCollectObjectReferences();
    }

    @Override
    public boolean isDisableAutoLoad() {
        return parent.isDisableAutoLoad();
    }

    @Override
    public String getInCharsetName() {
        return parent.getInCharsetName();
    }

    @Override
    public String getTimeZone() {
        return parent.getTimeZone();
    }

    @Override
    public IFormatConfiguration getFormatConfiguration() {
        return parent.getFormatConfiguration();
    }

    @Override
    public Collection<DbObjType> getAllowedTypes() {
        return parent.getAllowedTypes();
    }

    @Override
    public Collection<String> getPreFilePath() {
        return parent.getPreFilePath();
    }

    @Override
    public Collection<String> getPostFilePath() {
        return parent.getPostFilePath();
    }

    @Override
    public String getClusterName() {
        return parent.getClusterName();
    }

    @Override
    public boolean isUseActualVersionSyntax() {
        return parent.isUseActualVersionSyntax();
    }
}
