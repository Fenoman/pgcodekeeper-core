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
package org.pgcodekeeper.core.it.project;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.project.IModelExporter;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.ch.ChDatabaseProvider;
import org.pgcodekeeper.core.database.ch.project.ChModelExporter;
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.database.ms.project.MsModelExporter;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * A column a {@code type=COLUMN} rule hides does not enter a project file of a
 * Microsoft SQL or a ClickHouse project either.
 * <p>
 * The promise, and the reasoning behind every case, is the one PostgreSQL
 * already keeps, see {@link ExportOmitsHiddenColumnsTest}; each dialect owns its
 * columns in a list of its own and writes its files with code of its own, so it
 * has to be asked separately. Each case asserts the bytes of the written file
 * against the same file of a project exported whole out of the state the project
 * must end up in, built by the very same exporter with no ignore list at all - an
 * oracle that cannot agree with the code under test by sharing its mistake.
 */
class ExportOmitsHiddenColumnsDialectTest {

    private static final List<String> AUDIT_COLUMNS = List.of(
            "s_audit_id_create", "s_audit_id_modif", "s_create_date", "s_creator", "s_modif_date", "s_owner");

    static List<Dialect> dialects() {
        return List.of(new MsDialect(), new ChDialect());
    }

    /**
     * The reported shape of the defect: a table applied to the project for the
     * sake of a column it is missing must not bring the audit columns of the
     * database with it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void hiddenColumnsDoNotEnterATableAppliedToTheProject(Dialect dialect, @TempDir Path project,
                                                          @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(dialect, dialect.doc("id", "title"), dialect.doc("id"),
                dialect.doc("id", "title", "s_creator", "s_owner"), auditHidden(), project, oracle);
    }

    /**
     * The same for a whole project exported out of a database, the case with no
     * project side anywhere - {@code oldDb == null}, the CLI {@code --export} and
     * the wizard alike.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void hiddenColumnsDoNotEnterAProjectExportedFromADatabase(Dialect dialect, @TempDir Path project,
                                                              @TempDir Path oracle)
            throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        IDatabase databaseDb = load(dialect, dialect.doc("id", "title", "s_creator", "s_owner"),
                "database", settings);

        TreeElement root = DiffTree.create(settings, null, databaseDb);
        root.setAllChecked();
        List<TreeElement> selected = new TreeFlattener().onlySelected().flatten(root);
        assertFalse(selected.isEmpty(), "the fixture must give the export something to write");

        PgCodeKeeperApi.exportToProject(dialect.provider(), null, databaseDb, selected, project, false, settings);

        assertFileIs(oracleOf(dialect, dialect.doc("id", "title"), oracle),
                project.resolve(dialect.tableFile()));
    }

    /**
     * A table of nothing but hidden columns is written whole.
     * <p>
     * A {@code CREATE TABLE} of either of these dialects states its columns
     * between parentheses that may not be empty, see
     * {@link org.pgcodekeeper.core.database.api.schema.ITable#canCreateWithoutColumns()},
     * and a project file is read back by the loader. Leaving this table with no
     * column at all would write a file no parser can read, so the rule gives way
     * and the table keeps every column it has.
     * <p>
     * This is the one caller of that question left. A migration script writes
     * whatever it is given and asks the rules nothing, so an empty body can no
     * longer arise there; it can still arise here, which is why the guard stays.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void aTableOfNothingButHiddenColumnsIsWrittenWhole(Dialect dialect, @TempDir Path project,
                                                       @TempDir Path oracle)
            throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        String onlyHidden = dialect.doc("s_creator");
        IDatabase databaseDb = load(dialect, onlyHidden, "database", settings);

        TreeElement root = DiffTree.create(settings, null, databaseDb);
        root.setAllChecked();
        List<TreeElement> selected = new TreeFlattener().onlySelected().flatten(root);
        assertFalse(selected.isEmpty(), "the fixture must give the export something to write");

        PgCodeKeeperApi.exportToProject(dialect.provider(), null, databaseDb, selected, project, false, settings);

        assertFileIs(oracleOf(dialect, onlyHidden, oracle), project.resolve(dialect.tableFile()));
    }

    /**
     * A hidden column the project declares stays, and stays as the project
     * declares it. The one the project does not declare is still not added.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void aHiddenColumnTheProjectDeclaresKeepsTheDefinitionOfTheProject(Dialect dialect, @TempDir Path project,
                                                                       @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(dialect, dialect.docWithOwnCreator("id", "title"), dialect.docWithOwnCreator("id"),
                dialect.doc("id", "title", "s_creator", "s_owner"), auditHidden(), project, oracle);
    }

    /**
     * A hidden column only the project declares is not dropped either. The
     * database disagreeing about a column it was told not to manage says nothing
     * about that column, so the export has nothing to act on.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void aHiddenColumnOnlyTheProjectDeclaresIsNotDropped(Dialect dialect, @TempDir Path project,
                                                         @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(dialect, dialect.doc("id", "s_owner", "title"), dialect.doc("id", "s_owner"),
                dialect.doc("id", "title"), auditHidden(), project, oracle);
    }

    /**
     * With no rule naming a column the export writes the state of the database
     * whole, byte for byte as it always has.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void withoutAColumnRuleTheExportIsWhatItAlwaysWas(Dialect dialect, @TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        String database = dialect.doc("id", "title", "s_creator", "s_owner");
        assertExportWrites(dialect, database, dialect.doc("id"), database, new CoreSettings(), project, oracle);
    }

    /**
     * A project rewriting its own files keeps every column it declares, hidden or
     * not: {@code exportFull} writes out whichever model it is handed, and the
     * plugin hands it the project itself when the project is normalized into a new
     * directory layout.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    void aProjectRewritingItsOwnFilesKeepsEveryColumn(Dialect dialect, @TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        String projectSql = dialect.doc("id", "title", "s_creator");
        IDatabase projectDb = load(dialect, projectSql, "project", settings);

        dialect.exporter(project, projectDb, settings).exportFull();

        assertFileIs(oracleOf(dialect, projectSql, oracle), project.resolve(dialect.tableFile()));
    }

    /**
     * Exports the database over a project holding the state given, and demands
     * that the file written be the file a project of the expected state exports.
     */
    private void assertExportWrites(Dialect dialect, String expectedSql, String projectSql, String databaseSql,
                                    CoreSettings settings, Path project, Path oracle)
            throws IOException, InterruptedException {
        IDatabase projectDb = load(dialect, projectSql, "project", settings);
        IDatabase databaseDb = load(dialect, databaseSql, "database", settings);

        dialect.exporter(project, projectDb, settings).exportFull();

        TreeElement root = DiffTree.create(settings, projectDb, databaseDb);
        root.setAllChecked();
        List<TreeElement> selected = new TreeFlattener().onlySelected().flatten(root);
        assertFalse(selected.isEmpty(), "the fixture must give the export something to apply");
        PgCodeKeeperApi.exportToProject(dialect.provider(), projectDb, databaseDb, selected, project, false, settings);

        assertFileIs(oracleOf(dialect, expectedSql, oracle), project.resolve(dialect.tableFile()));
    }

    /**
     * The file of a project exported whole out of the state given, with no ignore
     * list at all: what the export under test has to match, produced by a path
     * that knows nothing of the rules it is used to check.
     */
    private Path oracleOf(Dialect dialect, String sql, Path oracle) throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        IDatabase db = load(dialect, sql, "expected", settings);
        dialect.exporter(oracle, db, settings).exportFull();
        return oracle.resolve(dialect.tableFile());
    }

    private static void assertFileIs(Path expected, Path actual) throws IOException {
        String expectedText = Files.readString(expected, StandardCharsets.UTF_8);
        String actualText = Files.readString(actual, StandardCharsets.UTF_8);
        assertArrayEquals(expectedText.getBytes(StandardCharsets.UTF_8), actualText.getBytes(StandardCharsets.UTF_8),
                () -> "the project file must be byte for byte:\nexpected\n" + expectedText + "\nactual\n" + actualText);
    }

    private IDatabase load(Dialect dialect, String sql, String name, CoreSettings settings)
            throws IOException, InterruptedException {
        settings.clearErrors();
        IDatabase db = dialect.provider().getDumpLoader(source(sql), name, settings).loadAndAnalyze();
        TestUtils.assertErrors(settings.getErrors());
        assertNotNull(db, "fixture must load");
        return db;
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }

    /** The settings of a project that leaves its audit columns to the database. */
    private static CoreSettings auditHidden() {
        CoreSettings settings = new CoreSettings();
        AUDIT_COLUMNS.forEach(column -> settings.getIgnoreList().add(
                new IgnoredObject(column, null, false, false, false, false, EnumSet.of(DbObjType.COLUMN))));
        return settings;
    }

    /**
     * What one dialect needs to be asked the same questions as the others: how to
     * load it, how to export it, where its table lands, and how to spell a table
     * of the given columns.
     */
    interface Dialect {

        IDatabaseProvider provider();

        IModelExporter exporter(Path outDir, IDatabase db, ISettings settings);

        String tableFile();

        /** The state holding the container and one table of the given columns. */
        String doc(String... columns);

        /** The same, with the hidden column the project states its own type for. */
        String docWithOwnCreator(String... columns);
    }

    private static final class MsDialect implements Dialect {

        private static final String COLUMNS = """
                id=[id] [bigint] NOT NULL
                title=[title] [nvarchar](50) NULL
                s_creator=[s_creator] [nvarchar](50) NULL
                s_owner=[s_owner] [nvarchar](50) NULL""";

        @Override
        public IDatabaseProvider provider() {
            return new MsDatabaseProvider();
        }

        @Override
        public IModelExporter exporter(Path outDir, IDatabase db, ISettings settings) {
            return new MsModelExporter(outDir, db, Consts.UTF_8, settings);
        }

        @Override
        public String tableFile() {
            return "Tables/dbo.doc.sql";
        }

        @Override
        public String doc(String... columns) {
            return table(Stream.of(columns).map(MsDialect::column));
        }

        @Override
        public String docWithOwnCreator(String... columns) {
            return table(Stream.concat(Stream.of(columns).map(MsDialect::column),
                    Stream.of("[s_creator] [nvarchar](100) NULL")));
        }

        private static String table(Stream<String> definitions) {
            return """
                    CREATE SCHEMA [dbo]
                    GO
                    CREATE TABLE [dbo].[doc](
                    %s
                    ) ON [PRIMARY]
                    GO""".formatted(definitions.map(definition -> '\t' + definition)
                    .collect(Collectors.joining(",\n")));
        }

        private static String column(String name) {
            return COLUMNS.lines().filter(line -> line.startsWith(name + '='))
                    .map(line -> line.substring(name.length() + 1)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(name));
        }

        @Override
        public String toString() {
            return "MS";
        }
    }

    private static final class ChDialect implements Dialect {

        private static final String COLUMNS = """
                id=`id` Int64
                title=`title` String
                s_creator=`s_creator` String
                s_owner=`s_owner` String""";

        @Override
        public IDatabaseProvider provider() {
            return new ChDatabaseProvider();
        }

        @Override
        public IModelExporter exporter(Path outDir, IDatabase db, ISettings settings) {
            return new ChModelExporter(outDir, db, Consts.UTF_8, settings);
        }

        @Override
        public String tableFile() {
            return "DATABASE/default/TABLE/doc.sql";
        }

        @Override
        public String doc(String... columns) {
            return table(Stream.of(columns).map(ChDialect::column));
        }

        @Override
        public String docWithOwnCreator(String... columns) {
            return table(Stream.concat(Stream.of(columns).map(ChDialect::column),
                    Stream.of("`s_creator` Nullable(String)")));
        }

        private static String table(Stream<String> definitions) {
            return """
                    CREATE DATABASE default;

                    CREATE TABLE default.doc
                    (
                    %s
                    )
                    ENGINE = Log;""".formatted(definitions.map(definition -> '\t' + definition)
                    .collect(Collectors.joining(",\n")));
        }

        private static String column(String name) {
            return COLUMNS.lines().filter(line -> line.startsWith(name + '='))
                    .map(line -> line.substring(name.length() + 1)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(name));
        }

        @Override
        public String toString() {
            return "CH";
        }
    }
}
