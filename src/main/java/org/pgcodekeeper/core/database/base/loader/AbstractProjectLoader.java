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

import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.loader.IProjectInputFingerprintCapture;
import org.pgcodekeeper.core.database.api.loader.IProjectLoader;
import org.pgcodekeeper.core.database.api.loader.ProjectInputFingerprint;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.project.IWorkDirs;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IPrivilege;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.base.schema.StatementOverride;
import org.pgcodekeeper.core.dependencieslist.DependenciesReader;
import org.pgcodekeeper.core.library.LibraryXmlStore;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.settings.ProjectFileFilter;
import org.pgcodekeeper.core.utils.PhaseTimer;
import org.pgcodekeeper.core.utils.Utils;

import java.util.Queue;
import java.util.ArrayDeque;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Base project loader for loading database schemas from project directory structures.
 *
 * @param <T> the type of database this loader produces
 */
public abstract class AbstractProjectLoader<T extends IDatabase> extends AbstractLoader<T>
        implements IProjectLoader, IProjectInputFingerprintCapture {

    public static final String IGNORE_FILE = ".pgcodekeeperignore";
    public static final String IGNORE_SCHEMA_FILE = ".pgcodekeeperignoreschema";
    public static final String OVERRIDES_DIR = "OVERRIDES";
    public static final String ADDITIONAL_DEPENDENCIES_FILE = ".pgcodekeeperdependencies";

    protected final Path metaPath;
    protected final Map<AbstractStatement, StatementOverride> overrides = new LinkedHashMap<>();
    protected final Queue<AbstractDumpLoader<T>> dumpLoaders = new ArrayDeque<>();
    protected final IWorkDirs workDirs;

    protected boolean isOverrideMode;

    private boolean isLib;
    private final Path dirPath;
    private final Collection<String> libXmls;
    private final Collection<String> libs;
    private final Collection<String> libsWithoutPriv;
    private final Map<Path, ProjectInputFingerprint> capturedInputFingerprints =
            new ConcurrentHashMap<>();
    private final AtomicBoolean inputFingerprintCaptureValid =
            new AtomicBoolean(true);
    private volatile boolean inputFingerprintCaptureEnabled;
    private volatile boolean inputFingerprintCaptureComplete;

    protected AbstractProjectLoader(Path dirPath, ISettings settings, IWorkDirs workDirs) {
        this(dirPath, settings, workDirs, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), null);
    }

    protected AbstractProjectLoader(Path dirPath, ISettings settings, IWorkDirs workDirs,
                                    Collection<String> libXmls, Collection<String> libs,
                                    Collection<String> libsWithoutPriv, Path metaPath) {
        super(settings, dirPath.getFileName().toString());
        this.dirPath = dirPath;
        this.workDirs = workDirs;
        this.libXmls = libXmls;
        this.libs = libs;
        this.libsWithoutPriv = libsWithoutPriv;
        this.metaPath = metaPath;
    }

    @Override
    public T loadInternal() throws InterruptedException, IOException {
        if (inputFingerprintCaptureEnabled) {
            capturedInputFingerprints.clear();
            inputFingerprintCaptureValid.set(true);
            inputFingerprintCaptureComplete = false;
        }
        boolean loaded = false;
        try {
            T db = createDatabase();
            long walkStart = PhaseTimer.start();
            loadStructure(dirPath, db);
            PhaseTimer.end("project_walk", walkStart,
                    getClass().getSimpleName());
            IMonitor.checkCancelled(getMonitor());
            long drainStart = PhaseTimer.start();
            finishLoaders();
            PhaseTimer.end("project_drain", drainStart,
                    getClass().getSimpleName());
            IMonitor.checkCancelled(getMonitor());
            if (!isLib) {
                loadLibraries(db);
                IMonitor.checkCancelled(getMonitor());
                loadOverrides(db);
                IMonitor.checkCancelled(getMonitor());
            }
            loaded = true;
            return db;
        } finally {
            if (inputFingerprintCaptureEnabled) {
                inputFingerprintCaptureComplete =
                        loaded
                        && inputFingerprintCaptureValid.get();
                if (!inputFingerprintCaptureComplete) {
                    capturedInputFingerprints.clear();
                }
            }
        }
    }

    @Override
    public void enableInputFingerprintCapture() {
        capturedInputFingerprints.clear();
        inputFingerprintCaptureValid.set(true);
        inputFingerprintCaptureComplete = false;
        inputFingerprintCaptureEnabled = true;
    }

    @Override
    public List<ProjectInputFingerprint>
            getCapturedInputFingerprints() {
        if (!inputFingerprintCaptureEnabled
                || !inputFingerprintCaptureComplete) {
            return List.of();
        }
        return capturedInputFingerprints.values().stream()
                .sorted(Comparator.comparing(
                        fingerprint -> fingerprint.path()
                                .toString()))
                .toList();
    }

    @Override
    public T loadFiles(Collection<Path> files) throws InterruptedException, IOException {
        T db = createDatabase();
        Path overridesDir = dirPath.resolve(OVERRIDES_DIR);
        List<Path> overrideFiles = new ArrayList<>();
        for (Path file : files) {
            IMonitor.checkCancelled(getMonitor());
            if (file.startsWith(overridesDir)) {
                if (!getSettings().isIgnorePrivileges()) {
                    overrideFiles.add(file);
                }
                continue;
            }
            AbstractDumpLoader<T> loader = createDumpLoader(file);
            loader.setWorkDirs(workDirs);
            loader.setMode(ParserListenerMode.SINGLE);
            loader.loadWithoutAnalyze(db, antlrTasks);
            dumpLoaders.add(loader);
        }
        finishLoaders();
        loadFileOverrides(overrideFiles, db);
        return db;
    }

    private void loadFileOverrides(List<Path> overrideFiles, T db) throws InterruptedException, IOException {
        if (overrideFiles.isEmpty()) {
            return;
        }

        for (Path file : overrideFiles) {
            IMonitor.checkCancelled(getMonitor());
            AbstractDumpLoader<T> loader = createDumpLoader(file);
            loader.setOverridesMap(overrides);
            loader.setMode(ParserListenerMode.REF);
            loader.loadWithoutAnalyze(db, antlrTasks);
            dumpLoaders.add(loader);
        }
        finishLoaders();
        IMonitor.checkCancelled(getMonitor());
        replaceOverrides();
    }

    public void setLib(boolean isLib) {
        this.isLib = isLib;
    }

    protected abstract AbstractDumpLoader<T> createDumpLoader(Path file);

    protected abstract AbstractLibraryLoader<T> createLibraryLoader(T db);

    /**
     * Loads the project structure from the given directory, dispatching to the
     * split-by-schema or flat layout based on {@link IWorkDirs#isSplitBySchema()}.
     *
     * @param dir project root directory
     * @param db  target database to populate
     */
    protected void loadStructure(Path dir, T db) throws InterruptedException, IOException {
        if (workDirs.isSplitBySchema()) {
            loadSplitBySchema(dir, db);
        } else {
            loadFlat(dir, db);
        }
    }

    @Override
    public List<Path> listInputFiles() throws IOException, InterruptedException {
        checkProjectWalkCancelled();
        preLoad();
        checkProjectWalkCancelled();
        List<Path> files = new ArrayList<>();
        listStructureFiles(dirPath, files);
        if (!isLib && !settings.isIgnorePrivileges()) {
            listStructureFiles(dirPath.resolve(OVERRIDES_DIR), files);
        }
        checkProjectWalkCancelled();
        return List.copyOf(files);
    }

    private void listStructureFiles(Path dir, List<Path> files)
            throws IOException, InterruptedException {
        checkProjectWalkCancelled();
        if (workDirs.isSplitBySchema()) {
            listSplitBySchemaFiles(dir, files);
        } else {
            listFlatFiles(dir, files);
        }
    }

    private void listSplitBySchemaFiles(Path dir, List<Path> files)
            throws IOException, InterruptedException {
        var dirMapping = workDirs.getDirMapping();
        var schemaDirName = dirMapping.get(IWorkDirs.SCHEMA_KEY).getDirName();
        List<Path> schemas = listSchemaDirs(dir.resolve(schemaDirName));
        Set<String> loadedDirs = new HashSet<>();

        for (var entry : dirMapping.entrySet()) {
            checkProjectWalkCancelled();
            var typeName = entry.getKey();
            var rule = entry.getValue();
            if (rule.isSubElement()) {
                if (loadedDirs.add(rule.getDirName())) {
                    for (Path schema : schemas) {
                        checkProjectWalkCancelled();
                        listSubdirFiles(schema, rule.getDirName(), null, files);
                    }
                }
            } else if (IWorkDirs.SCHEMA_KEY.equals(typeName)) {
                for (Path schema : schemas) {
                    checkProjectWalkCancelled();
                    listSubdirFiles(schema, null, null, files);
                }
            } else if (loadedDirs.add(rule.getDirName())) {
                listSubdirFiles(dir, rule.getDirName(), null, files);
            }
        }
    }

    private void listFlatFiles(Path dir, List<Path> files)
            throws IOException, InterruptedException {
        var dirMapping = workDirs.getDirMapping();
        Predicate<String> schemaFilter = this::isFlatSchemaFileAllowed;
        Predicate<String> schemaObjectFilter = this::isFlatSchemaObjectFileAllowed;
        Set<String> loadedDirs = new HashSet<>();

        for (var entry : dirMapping.entrySet()) {
            checkProjectWalkCancelled();
            var typeName = entry.getKey();
            var rule = entry.getValue();
            if (!loadedDirs.add(rule.getDirName())) {
                continue;
            }
            Predicate<String> fileFilter;
            if (rule.isSubElement()) {
                fileFilter = schemaObjectFilter;
            } else if (IWorkDirs.SCHEMA_KEY.equals(typeName)) {
                fileFilter = schemaFilter;
            } else {
                fileFilter = null;
            }
            listSubdirFiles(dir, rule.getDirName(), fileFilter, files);
        }
    }

    private void listSubdirFiles(Path dir, String sub,
            Predicate<String> fileFilter, List<Path> files)
            throws IOException, InterruptedException {
        checkProjectWalkCancelled();
        Path subDir = sub == null ? dir : dir.resolve(sub);
        if (!Files.isDirectory(subDir)) {
            return;
        }
        List<Path> accepted = new ArrayList<>();
        try (Stream<Path> stream = Files.list(subDir)) {
            for (Path file : Utils.streamIterator(stream)) {
                checkProjectWalkCancelled();
                if (filterFile(file, fileFilter)) {
                    accepted.add(file);
                }
            }
        }
        accepted.sort(Comparator.naturalOrder());
        for (Path file : accepted) {
            checkProjectWalkCancelled();
            files.add(file);
        }
    }

    private void checkProjectWalkCancelled() throws InterruptedException {
        requireOpenForLoad();
        IMonitor.checkCancelled(getMonitor());
    }

    /**
     * Loads the project using the split-by-schema layout: each schema has its own
     * subdirectory under the schema container, and sub-element types (tables,
     * views, etc.) live inside per-schema directories.
     *
     * @param dir project root directory
     * @param db  target database to populate
     */
    private void loadSplitBySchema(Path dir, T db) throws InterruptedException, IOException {
        var dirMapping = workDirs.getDirMapping();
        var schemaDirName = dirMapping.get(IWorkDirs.SCHEMA_KEY).getDirName();
        List<Path> schemas = listSchemaDirs(dir.resolve(schemaDirName));
        Set<String> loadedDirs = new HashSet<>();

        for (var entry : dirMapping.entrySet()) {
            var typeName = entry.getKey();
            var rule = entry.getValue();
            if (rule.isSubElement()) {
                if (loadedDirs.add(rule.getDirName())) {
                    for (var s : schemas) {
                        loadSubdir(s, rule.getDirName(), db, null);
                    }
                }
            } else if (IWorkDirs.SCHEMA_KEY.equals(typeName)) {
                for (var s : schemas) {
                    loadSubdir(s, db, null);
                }
                afterSchemaLoad(db);
            } else if (loadedDirs.add(rule.getDirName())) {
                loadSubdir(dir, rule.getDirName(), db, null);
            }
        }
    }

    /**
     * Loads the project using the flat layout: all object files sit directly
     * under per-type directories, and the schema name is encoded in the filename
     * rather than in a containing directory.
     *
     * @param dir project root directory
     * @param db  target database to populate
     */
    private void loadFlat(Path dir, T db) throws InterruptedException, IOException {
        var dirMapping = workDirs.getDirMapping();
        Predicate<String> schemaFilter = this::isFlatSchemaFileAllowed;
        Predicate<String> schemaObjectFilter = this::isFlatSchemaObjectFileAllowed;
        Set<String> loadedDirs = new HashSet<>();

        for (var entry : dirMapping.entrySet()) {
            var typeName = entry.getKey();
            var rule = entry.getValue();
            if (!loadedDirs.add(rule.getDirName())) {
                continue;
            }
            if (rule.isSubElement()) {
                loadSubdir(dir, rule.getDirName(), db, schemaObjectFilter);
            } else if (IWorkDirs.SCHEMA_KEY.equals(typeName)) {
                loadSubdir(dir, rule.getDirName(), db, schemaFilter);
                afterSchemaLoad(db);
            } else {
                loadSubdir(dir, rule.getDirName(), db, null);
            }
        }
    }

    private boolean isFlatSchemaFileAllowed(String fileName) {
        String schemaName = removeSqlPostfix(fileName);
        int separator = schemaName.indexOf('.');
        String legacySchemaName = separator < 0
                ? schemaName : schemaName.substring(0, separator);
        return isAllowedSchema(legacySchemaName)
                && !settings.isAdditionalSchemaExcluded(schemaName);
    }

    private boolean isFlatSchemaObjectFileAllowed(String fileName) {
        String objectName = removeSqlPostfix(fileName);
        int separator = objectName.indexOf('.');
        String schemaName = separator < 0
                ? objectName : objectName.substring(0, separator);
        if (!isAllowedSchema(schemaName)) {
            return false;
        }
        if (!settings.isAdditionalSchemaExcluded(schemaName)) {
            return true;
        }

        // A second dot can belong either to a quoted schema or to the object
        // name/signature. A flat filename cannot distinguish those cases, so
        // keep the file and let the SQL parser resolve it without data loss.
        // The parser finishes the exclusion: a statement that really belongs
        // to an excluded schema is dropped without an error, see
        // ParserAbstract#getSchemaSafe and ExcludedSchemaException.
        return separator >= 0 && objectName.indexOf('.', separator + 1) >= 0;
    }

    private static String removeSqlPostfix(String fileName) {
        return fileName.substring(0,
                fileName.length() - Consts.SQL_POSTFIX.length());
    }

    private boolean isProjectSchemaAllowed(String schemaName) {
        return isAllowedSchema(schemaName)
                && !settings.isAdditionalSchemaExcluded(schemaName);
    }

    /**
     * Lists the per-schema subdirectories under the given schema container,
     * filtering out ones excluded by {@link #isAllowedSchema(String)}. Returns
     * an empty list if the container directory does not exist.
     *
     * @param schemaDir container directory holding per-schema subdirectories
     * @return matching schema subdirectories, sorted by path
     */
    private List<Path> listSchemaDirs(Path schemaDir)
            throws IOException, InterruptedException {
        checkProjectWalkCancelled();
        if (!Files.isDirectory(schemaDir)) {
            return Collections.emptyList();
        }
        List<Path> schemas = new ArrayList<>();
        try (Stream<Path> stream = Files.list(schemaDir)) {
            for (Path schema : Utils.streamIterator(stream)) {
                checkProjectWalkCancelled();
                if (Files.isDirectory(schema)
                        && isProjectSchemaAllowed(
                                schema.getFileName().toString())) {
                    schemas.add(schema);
                }
            }
        }
        schemas.sort(Comparator.naturalOrder());
        return List.copyOf(schemas);
    }

    /**
     * Additional actions after schemas load
     *
     * @param db - current database
     *
     */
    protected void afterSchemaLoad(T db) throws InterruptedException, IOException {
        // do nothing by default
    }

    protected void loadSubdir(Path dir, String sub, T db, Predicate<String> checkFilename)
            throws IOException, InterruptedException {
        Path subDir = dir.resolve(sub);
        if (Files.isDirectory(subDir)) {
            loadSubdir(subDir, db, checkFilename);
        }
    }

    private void loadSubdir(Path subDir, T db, Predicate<String> checkFilename)
            throws IOException, InterruptedException {
        try (Stream<Path> files = Files.list(subDir)
                .filter(f -> filterFile(f, checkFilename))
                .sorted()) {
            for (Path f : Utils.streamIterator(files)) {
                IMonitor.checkCancelled(getMonitor());
                try (var loader = createDumpLoader(f)) {
                    if (inputFingerprintCaptureEnabled) {
                        loader.captureInputFingerprint(
                                f, this::recordInputFingerprint);
                    }
                    if (isOverrideMode) {
                        loader.setOverridesMap(overrides);
                    } else {
                        loader.setWorkDirs(workDirs);
                    }
                    loader.loadWithoutAnalyze(db, antlrTasks);
                }
            }
        }
    }

    private void recordInputFingerprint(
            ProjectInputFingerprint fingerprint) {
        if (capturedInputFingerprints.putIfAbsent(
                fingerprint.path(), fingerprint) != null) {
            inputFingerprintCaptureValid.set(false);
        }
    }

    protected boolean filterFile(Path f, Predicate<String> checkFilename) {
        String fileName = f.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(Consts.SQL_POSTFIX)
                || !Files.isRegularFile(f)) {
            return false;
        }
        if (checkFilename != null && !checkFilename.test(fileName)) {
            return false;
        }
        ProjectFileFilter projectFileFilter = settings.getProjectFileFilter();
        if (projectFileFilter == ProjectFileFilter.ALLOW_ALL) {
            return true;
        }
        String relativePath = dirPath.relativize(f).normalize().toString();
        return projectFileFilter.isAllowed(relativePath);
    }

    /**
     * This method loads common settings {@link ISettings}, if it's need,
     * before comparing database instances{@link IDatabase}.
     */
    @Override
    public void preLoad() throws IOException {
        if (isPreloaded) {
            return;
        }

        if (!settings.isDisableAutoLoad()) {
            contributeCommonConfiguration(dirPath, settings);
        }
        isPreloaded = true;
    }

    @Override
    public void markCommonConfigurationContributed() {
        isPreloaded = true;
    }

    /**
     * Adds the three root-level project files whose contents are common to both
     * comparison sides. Library descriptors, directory-layout files, SQL and
     * overrides remain side-local and are deliberately not scanned here.
     */
    static void contributeCommonConfiguration(Path dirPath, ISettings settings) throws IOException {
        Objects.requireNonNull(dirPath, "dirPath");
        Objects.requireNonNull(settings, "settings");

        // load ignored lists
        Path ignoreFile = dirPath.resolve(IGNORE_FILE);
        if (Files.isRegularFile(ignoreFile)) {
            settings.addIgnoreList(ignoreFile);
        }

        Path ignoreSchemaFile = dirPath.resolve(IGNORE_SCHEMA_FILE);
        if (Files.isRegularFile(ignoreSchemaFile)) {
            settings.addIgnoreSchemaList(ignoreSchemaFile);
        }

        // load additional dependencies
        Path depsPath = dirPath.resolve(ADDITIONAL_DEPENDENCIES_FILE);
        settings.addAdditionalDependencies(DependenciesReader.getDependencies(depsPath));
    }

    private void loadOverrides(T db) throws IOException, InterruptedException {
        Path overridesDir = dirPath.resolve(OVERRIDES_DIR);
        if (getSettings().isIgnorePrivileges() || !Files.isDirectory(overridesDir)) {
            return;
        }

        isOverrideMode = true;
        try {
            loadStructure(overridesDir, db);
            finishLoaders();
            IMonitor.checkCancelled(getMonitor());
            replaceOverrides();
        } finally {
            isOverrideMode = false;
        }
    }

    private void replaceOverrides() {
        Iterator<Map.Entry<AbstractStatement, StatementOverride>> iterator = overrides.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AbstractStatement, StatementOverride> entry = iterator.next();
            iterator.remove();

            AbstractStatement st = entry.getKey();
            StatementOverride override = entry.getValue();
            if (override.getOwner() != null) {
                st.setOwner(override.getOwner());
            }

            if (!override.getPrivileges().isEmpty()) {
                replacePrivileges(st, override);
            }
        }
    }

    private void replacePrivileges(AbstractStatement st, StatementOverride override) {
        st.clearPrivileges();
        if (st instanceof ITable table) {
            for (var col : table.getColumns()) {
                col.clearPrivileges();
            }
        }
        for (IPrivilege privilege : override.getPrivileges()) {
            st.addPrivilege(privilege);
        }
    }

    private void loadLibraries(T db) throws IOException, InterruptedException {
        try (var libraryLoader = createLibraryLoader(db)) {

            if (!settings.isDisableAutoLoad()) {
                // check project libraries
                Path depsFile = dirPath.resolve(LibraryXmlStore.FILE_NAME);
                if (Files.isRegularFile(depsFile)) {
                    libraryLoader.loadXml(new LibraryXmlStore(depsFile));
                }
            }
            for (String xml : libXmls) {
                IMonitor.checkCancelled(getMonitor());
                libraryLoader.loadXml(new LibraryXmlStore(Path.of(xml)));
            }
            libraryLoader.loadLibraries(false, libs);
            libraryLoader.loadLibraries(true, libsWithoutPriv);
        }
    }
}
