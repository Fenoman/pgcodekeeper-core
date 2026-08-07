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
package org.pgcodekeeper.core.database.base.project;

import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.database.api.project.IDirRule;
import org.pgcodekeeper.core.database.api.project.IModelExporter;
import org.pgcodekeeper.core.database.api.project.IWorkDirs;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.exception.DirectoryException;
import org.pgcodekeeper.core.exception.PgCodeKeeperException;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.FileUtils;
import org.pgcodekeeper.core.utils.UnixPrintWriter;
import org.pgcodekeeper.core.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Abstract base class for database model exporters that provides common export functionality
 * for different database types (PostgreSQL, MS SQL, ClickHouse).
 * <p>
 * Subclasses must implement database-specific methods for directory structure and file paths.
 */
public abstract class AbstractModelExporter implements IModelExporter {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractModelExporter.class);

    public static final String GROUP_DELIMITER =
            "\n\n--------------------------------------------------------------------------------\n\n"; //$NON-NLS-1$

    /**
     * Objects of the export directory
     */
    protected final Path outDir;

    /**
     * Database to export
     */
    protected final IDatabase newDb;

    /**
     * Old state db to fetch filenames from
     */
    protected final IDatabase oldDb;

    /**
     * SQL files encoding
     */
    private final String sqlEncoding;

    /**
     * Objects that we need to operate on
     */
    protected final Collection<TreeElement> changeList;

    protected final ISettings settings;

    /**
     * The settings the caller handed over.
     * <p>
     * {@link #settings} above deliberately is not them: it is a bare
     * {@link CoreSettings} carrying the formatting alone, so that a project file
     * holds the whole definition of an object rather than whatever a migration
     * between two states of it would have written. The question asked of the
     * settings below is of the other kind - which values of an object the
     * project owns rather than the database - and it can only be asked of what
     * the caller actually set.
     */
    private final ISettings callerSettings;

    /**
     * Whether {@link #callerSettings} name anything at all the project owns, so
     * that an export under settings that relax nothing asks no object anything
     * and writes the bytes it has always written.
     */
    private final boolean adoptsUnmanagedValues;

    protected final IWorkDirs workDirs;

    /**
     * When {@code true}, the current directory layout is persisted to the exported
     * project at the end of the export. When {@code false}, layout defaults were
     * used and no layout configuration is written.
     */
    private final boolean saveLayout;

    /**
     * Notified just before this exporter first touches each affected path
     * during {@link #exportPartial()}, or {@code null} when nobody asked to
     * be told.
     * <p>
     * WHY a field instead of a constructor parameter: every existing caller
     * of {@code exportPartial()} - direct callers in tests among them - has
     * to keep working unchanged when no one is watching, and a constructor
     * parameter would force all of them to pass something. Setting this is
     * opt-in, and {@link AbstractProjectUpdater} is presently the only one
     * who opts in.
     */
    private PartialExportPathListener partialExportListener;

    /**
     * Relative paths already reported to {@link #partialExportListener}
     * during the current {@link #exportPartial()} call, so a path shared by
     * several statements - a table and its own indexes, triggers and
     * constraints, for instance - is reported exactly once: at the moment it
     * is first about to change, before anything has touched it.
     */
    private final Set<Path> pathsReportedForBackup = new HashSet<>();

    /**
     * Creates a new AbstractModelExporter for full database export.
     *
     * @param outDir      output directory, should be empty or not exist
     * @param db          database to export
     * @param sqlEncoding SQL file encoding
     * @param settings    export settings
     * @param workDirs    directory layout to use for this export
     */
    protected AbstractModelExporter(Path outDir, IDatabase db, String sqlEncoding, ISettings settings,
                                    IWorkDirs workDirs) {
        this(outDir, db, null, null, sqlEncoding, settings, workDirs, false);
    }

    /**
     * Creates a new AbstractModelExporter for partial or project export.
     *
     * @param outDir         output directory
     * @param newDb          new database schema
     * @param oldDb          old database schema, can be null for project export
     * @param changedObjects collection of changed objects
     * @param sqlEncoding    SQL file encoding
     * @param settings       export settings
     * @param workDirs       directory layout to use for this export
     */
    protected AbstractModelExporter(Path outDir, IDatabase newDb, IDatabase oldDb,
                                    Collection<TreeElement> changedObjects,
                                    String sqlEncoding, ISettings settings,
                                    IWorkDirs workDirs) {
        this(outDir, newDb, oldDb, changedObjects, sqlEncoding, settings, workDirs, false);
    }

    /**
     * Creates a new AbstractModelExporter with an externally supplied directory layout.
     *
     * @param outDir         output directory
     * @param newDb          new database schema
     * @param oldDb          old database schema, can be null for project export
     * @param changedObjects collection of changed objects
     * @param sqlEncoding    SQL file encoding
     * @param settings       export settings
     * @param workDirs       directory layout to use for this export
     * @param saveLayout     when {@code true}, the layout is persisted to the exported project
     */
    protected AbstractModelExporter(Path outDir, IDatabase newDb, IDatabase oldDb,
                                    Collection<TreeElement> changedObjects,
                                    String sqlEncoding, ISettings settings,
                                    IWorkDirs workDirs, boolean saveLayout) {
        // we should create new settings to get correct script in project files
        var set = new CoreSettings();
        set.setFormatConfiguration(settings.getFormatConfiguration());
        set.setAutoFormatObjectCode(settings.isAutoFormatObjectCode());

        this.outDir = outDir;
        this.newDb = newDb;
        this.oldDb = oldDb;
        this.sqlEncoding = sqlEncoding;
        this.changeList = changedObjects;
        this.settings = set;
        this.callerSettings = settings;
        this.adoptsUnmanagedValues = settings.isIgnoreSequenceCache() || settings.isIgnoreColumnStatistics()
                || ColumnVisibility.of(settings).hidesAnything();
        this.workDirs = workDirs;
        this.saveLayout = saveLayout;
    }

    public Path getRelativeFilePath(IStatement st) {
        return workDirs.getRelativeFilePath(st);
    }

    /**
     * Returns the directory layout used by this exporter.
     *
     * @return the work directories layout
     */
    public IWorkDirs getWorkDirs() {
        return workDirs;
    }

    /**
     * Registers a listener to notify just before {@link #exportPartial()}
     * first touches each affected path.
     * <p>
     * WHY this exists: see {@link PartialExportPathListener}.
     * {@link AbstractProjectUpdater} uses it to snapshot pre-write state for
     * an all-or-nothing rollback, without recomputing on its own which paths
     * a partial export is going to touch.
     *
     * @param listener the listener to notify, or {@code null} (the default) to notify none
     */
    public void setPartialExportPathListener(PartialExportPathListener listener) {
        this.partialExportListener = listener;
    }

    /**
     * Writes out the whole of the model handed over, exactly as it stands.
     * <p>
     * Alone among the exports this one asks nothing of the settings about what
     * the project owns, because it is the one that is not told a database from a
     * project: it writes whichever model it is given, and the model is the
     * project itself whenever a project is rewritten into another directory
     * layout. Leaving out the columns an ignore list hides would then delete
     * them from the files of the project that declares them - the damage the
     * whole of {@code adoptUnmanaged} exists to stop. A database is exported
     * into an empty project through {@link #exportProject()}, which does apply
     * the rules.
     * <hr><br>
     * {@inheritDoc}
     */
    @Override
    public void exportFull() throws IOException {
        createOutDir();

        // insertion-ordered so a caller stepping through failures (e.g. a
        // partial-export rollback test) sees a fixed, documented write order
        // (ExportTableOrder's sort order) rather than HashMap's unspecified one
        Map<Path, StringBuilder> dumps = new LinkedHashMap<>();
        newDb.getDescendants()
                .filter(st -> !st.isLib())
                .sorted(ExportTableOrder.INSTANCE)
                .forEach(st -> dumpStatement(st, dumps));

        writeDumps(dumps);
    }

    private void createOutDir() throws IOException {
        LOG.info(Messages.ModelExporter_log_create_dirs);
        if (!Files.exists(outDir)) {
            Files.createDirectories(outDir);
            return;
        }
        if (!Files.isDirectory(outDir)) {
            var msg = Messages.ModelExporter_log_create_dir_err_no_dir.formatted(outDir);
            LOG.error(msg);
            throw new NotDirectoryException(outDir.toString());
        }
        for (IDirRule rule : workDirs.getDirMapping().values()) {
            String dirName = rule.getDirName().split("/")[0];
            if (Files.exists(outDir.resolve(dirName))) {
                String msg = Messages.ModelExporter_log_create_dir_err_contains_dir.formatted(dirName);
                LOG.error(msg);
                throw new DirectoryException(msg);
            }
        }
    }

    @Override
    public void exportPartial() throws IOException, PgCodeKeeperException {
        if (oldDb == null) {
            String msg = Messages.ModelExporter_log_old_database_not_null;
            LOG.error(msg);
            throw new PgCodeKeeperException(msg);
        }
        if (Files.notExists(outDir) || !Files.isDirectory(outDir)) {
            throw new DirectoryException(Messages.ModelExporter_log_output_dir_no_exist_err.formatted(
                    outDir.toAbsolutePath()));
        }

        List<IStatement> list = oldDb.getDescendants().collect(Collectors.toList());
        Set<Path> paths = new HashSet<>();

        for (TreeElement el : changeList) {
            if (el.getType() == DbObjType.DATABASE) {
                continue;
            }
            switch (el.getSide()) {
                case LEFT:
                    var stInOld = el.getStatement(oldDb);
                    list.remove(stInOld);
                    for (var child : Utils.streamIterator(stInOld.getChildren())) {
                        list.remove(child);
                        deleteStatementIfExists(child);
                    }
                    paths.add(getRelativeFilePath(stInOld));
                    deleteStatementIfExists(stInOld);
                    break;
                case RIGHT:
                    var stInNew = el.getStatement(newDb);
                    // nothing to carry over from a project that does not hold
                    // this object, but what the project does not manage is still
                    // not written into it
                    list.add(forProjectFile(stInNew, null));
                    paths.add(getRelativeFilePath(stInNew));
                    deleteStatementIfExists(stInNew);
                    break;
                case BOTH:
                    stInNew = el.getStatement(newDb);
                    stInOld = el.getStatement(oldDb);
                    // the object of the database takes the place of the object
                    // of the project, carrying over from it every value the
                    // settings declare the project's own
                    list.set(list.indexOf(stInOld), forProjectFile(stInNew, stInOld));
                    paths.add(getRelativeFilePath(stInNew));
                    deleteStatementIfExists(stInNew);
                    break;
            }
        }

        // insertion-ordered so a caller stepping through failures (e.g. a
        // partial-export rollback test) sees a fixed, documented write order
        // (ExportTableOrder's sort order) rather than HashMap's unspecified one
        Map<Path, StringBuilder> dumps = new LinkedHashMap<>();
        list.stream().filter(st -> paths.contains(getRelativeFilePath(st)))
                .sorted(ExportTableOrder.INSTANCE)
                .forEach(st -> dumpStatement(st, dumps));

        writeDumps(dumps);
    }

    @Override
    public void exportProject() throws IOException {
        createOutDir();

        List<IStatement> list = new ArrayList<>();
        changeList.stream().filter(el -> el.getType() != DbObjType.DATABASE)
                .forEach(el -> list.add(forProjectFile(el.getStatement(newDb), null)));

        // insertion-ordered so a caller stepping through failures (e.g. a
        // partial-export rollback test) sees a fixed, documented write order
        // (ExportTableOrder's sort order) rather than HashMap's unspecified one
        Map<Path, StringBuilder> dumps = new LinkedHashMap<>();
        list.stream()
                .sorted(ExportTableOrder.INSTANCE)
                .forEach(st -> dumpStatement(st, dumps));

        writeDumps(dumps);
    }

    /**
     * The state of an object a project file is to hold, which is the state of
     * the database with every value the settings declare the project's own taken
     * over from the project.
     * <p>
     * The settings asked are the ones the caller handed over, never
     * {@link #settings}: the latter is a bare {@link CoreSettings} built in the
     * constructor for the sake of the rendering and reports no relaxation and an
     * empty ignore list, so an export reading it would quietly carry nothing
     * over - in the CLI as much as in the IDE.
     *
     * @param fromDatabase the object of the database, about to be written
     * @param inProject    the state of it the project holds, {@code null} when
     *                     the project holds none - an object being added to it,
     *                     or a whole project written out of a database
     * @return the object to write, which is the given one whenever the settings
     * leave everything about it to the database
     */
    private IStatement forProjectFile(IStatement fromDatabase, IStatement inProject) {
        return adoptsUnmanagedValues ? fromDatabase.adoptUnmanaged(inProject, callerSettings) : fromDatabase;
    }

    /**
     * Writes every dumped file, then the project version marker.
     * <p>
     * The marker is not selected by any {@link TreeElement} and never goes
     * through {@link #deleteStatementIfExists}: {@code exportPartial()} writes
     * it unconditionally on every call. It is therefore not a second
     * <em>computation</em> of what changed - unlike every other path, the
     * marker does not depend on {@code changeList} at all - but it is still a
     * path that gets touched, so it is announced here as well. That keeps the
     * invariant exact: nothing touches a path without going through
     * {@link #partialExportListener}.
     */
    private void writeDumps(Map<Path, StringBuilder> dumps) throws IOException {
        for (var dump : dumps.entrySet()) {
            dumpSQL(dump.getValue(), dump.getKey());
        }

        Path markerRelative = Path.of(Consts.FILENAME_WORKING_DIR_MARKER);
        notifyBeforeTouch(markerRelative);
        writeProjVersion(outDir.resolve(markerRelative));
        if (saveLayout) {
            // the layout file is the one path here that a failed export can
            // lose outright rather than merely rewrite: saveAltDirs deletes it
            // whenever the layout has come back to the default, and a project
            // whose layout file is gone reads as a default-layout project, so
            // every directory it named stops being found. Nothing else in an
            // export destroys that much on its own, which is exactly why it
            // may not be the one path the rollback was never told about.
            notifyBeforeTouch(Path.of(AbstractWorkDirs.ALT_DIRS_FILENAME));
            workDirs.saveAltDirs(outDir);
        }
    }

    protected void dumpStatement(IStatement st, Map<Path, StringBuilder> dumps) {
        Path path = outDir.resolve(getRelativeFilePath(st));
        StringBuilder sb = dumps.computeIfAbsent(path, e -> new StringBuilder());
        String dump = getDumpSql(st);

        if (dump.isEmpty()) {
            return;
        }

        if (!sb.isEmpty()) {
            sb.append(GROUP_DELIMITER);
        }

        sb.append(dump);
    }

    protected void dumpSQL(CharSequence sql, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (PrintWriter outFile = new UnixPrintWriter(Files.newOutputStream(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), sqlEncoding)) {
            outFile.println(sql);
        }
    }

    public String getDumpSql(IStatement statement) {
        return statement.getSQL(true, settings);
    }

    /**
     * Removes file if it exists for the given statement.
     * <p>
     * This is also the one place {@code exportPartial()} decides that an
     * object-driven path is about to change - every path tied to a
     * {@code changeList} element passes through here, deleted outright (an
     * object removed) or deleted ahead of being recreated (an object added or
     * changed) - which is why {@link #partialExportListener}, when one is
     * registered, is notified from here rather than from some separately
     * maintained list of affected paths. See {@link PartialExportPathListener}
     * for why that distinction matters. The one path this does not cover is
     * the project version marker, which {@link #writeDumps} notifies about
     * directly, since it is written unconditionally rather than decided by
     * any element of {@code changeList}.
     *
     * @param st the statement whose file should be deleted
     * @throws IOException if deletion fails
     */
    protected void deleteStatementIfExists(IStatement st) throws IOException {
        Path relative = getRelativeFilePath(st);
        notifyBeforeTouch(relative);

        Path toDelete = outDir.resolve(relative);
        if (Files.deleteIfExists(toDelete)) {
            var msg = Messages.ModelExporter_log_delete_file.formatted(toDelete, st.getStatementType(), st.getName());
            LOG.info(msg);
        }
    }

    /**
     * Tells {@link #partialExportListener}, if any, that {@code relativePath}
     * is about to change - once per path per {@link #exportPartial()} call,
     * regardless of how many statements happen to share that path.
     *
     * @param relativePath the path about to be deleted, overwritten, or created
     * @throws IOException if the listener fails to record the path's current state
     */
    private void notifyBeforeTouch(Path relativePath) throws IOException {
        if (partialExportListener != null && pathsReportedForBackup.add(relativePath)) {
            partialExportListener.beforeTouch(relativePath);
        }
    }

    /**
     * Gets the SQL filename with .sql extension.
     *
     * @param name the base name
     * @return filename with .sql extension
     */
    public static String getExportedFilenameSql(String name) {
        return FileUtils.getValidFilename(name) + Consts.SQL_POSTFIX;
    }

    /**
     * Writes project version marker file.
     * <p>
     * The marker is left untouched when it already holds exactly the bytes that would be written. The marker is a
     * configuration input of the Eclipse project index: rewriting it with identical content still bumps the resource
     * timestamp, which downgrades the following build to a full project reindex. Skipping the redundant write keeps the
     * produced bytes identical while letting an otherwise incremental delta stay incremental.
     *
     * @param path the path to write version file
     * @throws IOException if writing fails
     */
    public static void writeProjVersion(Path path) throws IOException {
        byte[] expected = currentProjVersionBytes();
        if (isProjVersionCurrent(path, expected)) {
            return;
        }
        Files.write(path, expected);
    }

    private static byte[] currentProjVersionBytes() {
        return (Consts.VERSION_PROP_NAME + " = " //$NON-NLS-1$
                + Consts.EXPORT_CURRENT_VERSION + '\n').getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isProjVersionCurrent(Path path, byte[] expected) {
        try {
            return Files.isRegularFile(path) && Files.size(path) == expected.length
                    && Arrays.equals(Files.readAllBytes(path), expected);
        } catch (IOException ex) {
            // unreadable marker: fall through and rewrite it
            return false;
        }
    }

    /**
     * Comparator for ordering database statements during export.
     * Orders table sub-elements (indexes, triggers, rules, constraints, policies, statistics)
     * in a consistent order to ensure deterministic output.
     */
    private static class ExportTableOrder implements Comparator<IStatement> {

        static final ExportTableOrder INSTANCE = new ExportTableOrder();

        @Override
        public int compare(IStatement o1, IStatement o2) {
            int result = Integer.compare(getTableSubElementRank(o1), getTableSubElementRank(o2));
            if (result != 0) {
                return result;
            }

            return o1.getBareName().compareTo(o2.getBareName());
        }

        private int getTableSubElementRank(IStatement el) {
            return switch (el.getStatementType()) {
                case INDEX -> 1;
                case TRIGGER -> 2;
                case RULE -> 3;
                case CONSTRAINT -> 4;
                case POLICY -> 5;
                case STATISTICS -> 6;
                default -> 0;
            };
        }

        private ExportTableOrder() {
        }
    }
}
