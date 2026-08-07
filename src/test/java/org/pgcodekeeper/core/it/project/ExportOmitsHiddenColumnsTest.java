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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.project.PgModelExporter;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * A column a {@code type=COLUMN} rule hides is not written into a project file:
 * not when an object of the project is updated from the database, not when one
 * is added to it, and not when a whole project is exported out of a database.
 * <p>
 * The rule says pgCodeKeeper does not manage that column, and a project file is
 * the statement of what it does manage. The comparison already leaves such a
 * column out of both states, which is exactly why the export has to be told
 * about it as well: with the difference gone from the tree, nothing is left to
 * show whoever pressed "apply to project" that six columns of the database were
 * about to be written into their file.
 * <p>
 * What the project declares is the project's, though, so an update never drops a
 * hidden column the project holds - it keeps it with the definition the project
 * gives it. And a column something inside the table needs is not hidden in the
 * first place, so it is written like any other; the case is pinned below because
 * it is the one that would break a file rather than merely fill it.
 * <p>
 * Each case asserts the bytes of the written file against the same file of a
 * project exported whole out of the state the project must end up in, built by
 * the very same exporter with no ignore list at all - an oracle that cannot
 * agree with the code under test by sharing its mistake.
 *
 * @see org.pgcodekeeper.core.it.difftree.HiddenColumnMigrationTest for the same
 * rule in the migration script
 * @see ExportKeepsProjectValuesTest for the values, rather than the columns, an
 * export carries over from the project
 */
class ExportOmitsHiddenColumnsTest {

    private static final String TABLE_FILE = "SCHEMA/dbo/TABLE/doc.sql";

    private static final List<String> AUDIT_COLUMNS = List.of(
            "s_audit_id_create", "s_audit_id_modif", "s_create_date", "s_creator", "s_modif_date", "s_owner");

    private static final String SCHEMA = "CREATE SCHEMA dbo;";

    /** The trigger function both states hold, so that the trigger below resolves. */
    private static final String AUDIT_FUNCTION = """


            CREATE FUNCTION dbo.audit() RETURNS trigger
                LANGUAGE plpgsql
                AS $$BEGIN RETURN NEW; END;$$;""";

    /** The one difference the settings do not hide, so that the table is really exported. */
    private static final String TRIGGER = """


            CREATE TRIGGER audit_doc
            \tBEFORE INSERT OR UPDATE ON dbo.doc
            \tFOR EACH ROW
            \tEXECUTE PROCEDURE dbo.audit();""";

    private static final String INDEX = """


            CREATE INDEX doc_create_date_idx ON dbo.doc USING btree (s_create_date);""";

    private static final String CONSTRAINT = """


            ALTER TABLE ONLY dbo.doc
            \tADD CONSTRAINT doc_owner_key UNIQUE (s_owner);""";

    /** The table as the project declares it: the business columns and nothing else. */
    private static final String PROJECT = SCHEMA + AUDIT_FUNCTION + doc("id bigint NOT NULL", "title text");

    /** The same table as the database holds it: six audit columns of its own, and a trigger. */
    private static final String DATABASE = SCHEMA + AUDIT_FUNCTION + doc(
            "id bigint NOT NULL", "title text",
            "s_audit_id_create bigint", "s_audit_id_modif bigint",
            "s_create_date timestamp without time zone", "s_creator text",
            "s_modif_date timestamp without time zone", "s_owner text") + TRIGGER;

    /** The trigger of the database, the columns of the project. */
    private static final String KEPT = PROJECT + TRIGGER;

    /** A project that declares one of the hidden columns, and states its own type for it. */
    private static final String PROJECT_DECLARING_CREATOR = SCHEMA + AUDIT_FUNCTION + doc(
            "id bigint NOT NULL", "title text",
            "s_creator character varying(100) DEFAULT 'project'::character varying");

    private static final String DATABASE_WITH_CREATOR = SCHEMA + AUDIT_FUNCTION + doc(
            "id bigint NOT NULL", "title text", "s_creator text", "s_owner text") + TRIGGER;

    /** The column of the project survives with its own definition; the other one is not added. */
    private static final String CREATOR_KEPT = PROJECT_DECLARING_CREATOR + TRIGGER;

    /** A project that declares a hidden column the database does not hold at all. */
    private static final String PROJECT_DECLARING_OWNER = SCHEMA + AUDIT_FUNCTION + doc(
            "id bigint NOT NULL", "title text", "s_owner text");

    /** The database holds no hidden column at all: it simply does not have this one. */
    private static final String DATABASE_WITHOUT_OWNER = SCHEMA + AUDIT_FUNCTION + doc(
            "id bigint NOT NULL", "title text") + TRIGGER;

    private static final String OWNER_KEPT = PROJECT_DECLARING_OWNER + TRIGGER;

    /**
     * A database whose table cannot spare two of the columns the rule names: an
     * index reads one and a unique constraint the other.
     */
    private static final String DATABASE_NEEDING_AUDIT = SCHEMA + AUDIT_FUNCTION + doc(
            "id bigint NOT NULL", "title text",
            "s_create_date timestamp without time zone", "s_creator text", "s_owner text")
            + INDEX + CONSTRAINT + TRIGGER;

    /** Everything the table needs is written; only the column nothing needs is left out. */
    private static final String NEEDED_WRITTEN = SCHEMA + AUDIT_FUNCTION + doc(
            "id bigint NOT NULL", "title text",
            "s_create_date timestamp without time zone", "s_owner text")
            + INDEX + CONSTRAINT + TRIGGER;

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    /**
     * The reported shape of the defect: a table applied to the project for the
     * sake of a trigger it is missing must not bring the audit columns of the
     * database with it.
     */
    @Test
    void hiddenColumnsDoNotEnterATableAppliedForItsTrigger(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(KEPT, PROJECT, DATABASE, auditHidden(), project, oracle);
    }

    /**
     * A table the project does not hold at all is added without them: the
     * project states that it does not manage those columns, and a file it writes
     * from scratch says the same.
     */
    @Test
    void hiddenColumnsDoNotEnterATableAddedToTheProject(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(KEPT, SCHEMA + AUDIT_FUNCTION, DATABASE, auditHidden(), project, oracle);
    }

    /**
     * The same for a whole project exported out of a database, the case with no
     * project side anywhere - {@code oldDb == null}, the CLI {@code --export}
     * and the wizard alike.
     */
    @Test
    void hiddenColumnsDoNotEnterAProjectExportedFromADatabase(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        IDatabase databaseDb = load(DATABASE, "database", settings);

        TreeElement root = DiffTree.create(settings, null, databaseDb);
        root.setAllChecked();
        List<TreeElement> selected = new TreeFlattener().onlySelected().flatten(root);
        assertFalse(selected.isEmpty(), "the fixture must give the export something to write");

        PgCodeKeeperApi.exportToProject(provider, null, databaseDb, selected, project, false, settings);

        assertFileIs(oracleOf(KEPT, oracle), project.resolve(TABLE_FILE));
    }

    /**
     * A hidden column the project declares stays, and stays as the project
     * declares it - a different type and a default the database does not have.
     * The one the project does not declare is still not added.
     */
    @Test
    void aHiddenColumnTheProjectDeclaresKeepsTheDefinitionOfTheProject(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(CREATOR_KEPT, PROJECT_DECLARING_CREATOR, DATABASE_WITH_CREATOR,
                auditHidden(), project, oracle);
    }

    /**
     * A hidden column only the project declares is not dropped either. The
     * database disagreeing about a column it was told not to manage says nothing
     * about that column, so the export has nothing to act on.
     */
    @Test
    void aHiddenColumnOnlyTheProjectDeclaresIsNotDropped(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(OWNER_KEPT, PROJECT_DECLARING_OWNER, DATABASE_WITHOUT_OWNER,
                auditHidden(), project, oracle);
    }

    /**
     * A column named by a rule while something inside its table needs it is not
     * hidden at all, so it is written like any other column. This is the case
     * that would leave a broken file rather than an incomplete one, which is why
     * it is asked of the export as well as of the script.
     */
    @Test
    void theColumnsTheTableNeedsAreWrittenAlthoughARuleNamesThem(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(NEEDED_WRITTEN, PROJECT, DATABASE_NEEDING_AUDIT, auditHidden(), project, oracle);
    }

    /**
     * With no rule naming a column the export writes the state of the database
     * whole, byte for byte as it always has.
     */
    @Test
    void withoutAColumnRuleTheExportIsWhatItAlwaysWas(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(DATABASE, PROJECT, DATABASE, new CoreSettings(), project, oracle);
    }

    /**
     * A project rewriting its own files keeps every column it declares, hidden
     * or not.
     * <p>
     * This is not the same operation with a shorter name: {@code exportFull}
     * writes out whichever model it is handed, and the plugin hands it the
     * project itself when the project is normalized into a new directory layout
     * ({@code NormalizeProject}). Were the rule applied there, re-laying-out a
     * project would silently delete from its files every column an ignore list
     * names - the very damage this whole change exists to stop.
     */
    @Test
    void aProjectRewritingItsOwnFilesKeepsEveryColumn(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        IDatabase projectDb = load(PROJECT_DECLARING_CREATOR, "project", settings);

        new PgModelExporter(project, projectDb, Consts.UTF_8, settings).exportFull();

        assertFileIs(oracleOf(PROJECT_DECLARING_CREATOR, oracle), project.resolve(TABLE_FILE));
    }

    /**
     * With nothing hidden the object of the database is handed over as it is.
     * The identity of the answer is what keeps the common case free of an
     * allocation, so it is asked for rather than argued about.
     */
    @Test
    void withNothingHiddenTheSameObjectComesBack() throws IOException, InterruptedException {
        CoreSettings hiding = auditHidden();
        IStatement database = statement(load(DATABASE, "database", hiding), "doc");
        IStatement project = statement(load(PROJECT, "project", hiding), "doc");

        assertSame(database, database.adoptUnmanaged(project, new CoreSettings()),
                "with no rule naming a column there is nothing the project owns");
        assertSame(database, database.adoptUnmanaged(null, new CoreSettings()),
                "and nothing changes when there is no project side either");
        assertNotSame(database, database.adoptUnmanaged(null, hiding),
                "while the rules do hide a column of it, the table must be copied rather than written into");
    }

    /**
     * Both objects the export reads belong to models the caller may still be
     * using, so it reads them, copies what it must, and writes into neither.
     */
    @Test
    void bothModelsAreLeftAlone(@TempDir Path project) throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        IDatabase projectDb = load(PROJECT_DECLARING_CREATOR, "project", settings);
        IDatabase databaseDb = load(DATABASE_WITH_CREATOR, "database", settings);

        new PgModelExporter(project, projectDb, Consts.UTF_8, settings).exportFull();
        applyToProject(projectDb, databaseDb, project, settings);

        assertEquals(List.of("id", "title", "s_creator", "s_owner"), columnNames(databaseDb),
                "the export must not have taken a column out of the database's own table");
        assertEquals(List.of("id", "title", "s_creator"), columnNames(projectDb),
                "nor written anything of the database into the project's");
    }

    /**
     * Exports the database over a project holding the state given, and demands
     * that the file written be the file a project of the expected state exports.
     *
     * @param expectedSql the state the project must end up in
     * @param projectSql  the state of the project before the export
     * @param databaseSql the state of the database being applied to it
     * @param settings    the settings of the export
     * @param project     the project directory
     * @param oracle      a directory for the project of the expected state
     */
    private void assertExportWrites(String expectedSql, String projectSql, String databaseSql,
                                    CoreSettings settings, Path project, Path oracle)
            throws IOException, InterruptedException {
        IDatabase projectDb = load(projectSql, "project", settings);
        IDatabase databaseDb = load(databaseSql, "database", settings);

        new PgModelExporter(project, projectDb, Consts.UTF_8, settings).exportFull();
        applyToProject(projectDb, databaseDb, project, settings);

        assertFileIs(oracleOf(expectedSql, oracle), project.resolve(TABLE_FILE));
    }

    /**
     * The file of a project exported whole out of the state given, with no
     * ignore list at all: what the export under test has to match, produced by a
     * path that knows nothing of the rules it is used to check.
     */
    private Path oracleOf(String sql, Path oracle) throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        IDatabase db = load(sql, "expected", settings);
        new PgModelExporter(oracle, db, Consts.UTF_8, settings).exportFull();
        return oracle.resolve(TABLE_FILE);
    }

    /**
     * Applies the whole of the difference to the project, the way the commit
     * dialog and the CLI both do.
     */
    private void applyToProject(IDatabase projectDb, IDatabase databaseDb, Path project, CoreSettings settings)
            throws IOException, InterruptedException {
        TreeElement root = DiffTree.create(settings, projectDb, databaseDb);
        root.setAllChecked();
        List<TreeElement> selected = new TreeFlattener().onlySelected().flatten(root);
        assertFalse(selected.isEmpty(), "the fixture must give the export something to apply");

        PgCodeKeeperApi.exportToProject(provider, projectDb, databaseDb, selected, project, false, settings);
    }

    private static void assertFileIs(Path expected, Path actual) throws IOException {
        String expectedText = Files.readString(expected, StandardCharsets.UTF_8);
        String actualText = Files.readString(actual, StandardCharsets.UTF_8);
        assertArrayEquals(expectedText.getBytes(StandardCharsets.UTF_8), actualText.getBytes(StandardCharsets.UTF_8),
                () -> "the project file must be byte for byte:\nexpected\n" + expectedText + "\nactual\n" + actualText);
    }

    private IDatabase load(String sql, String name, CoreSettings settings) throws IOException, InterruptedException {
        settings.clearErrors();
        IDatabase db = provider.getDumpLoader(source(sql), name, settings).loadAndAnalyze();
        TestUtils.assertErrors(settings.getErrors());
        assertNotNull(db, "fixture must load");
        return db;
    }

    private static List<String> columnNames(IDatabase db) {
        return ((ITable) statement(db, "doc")).getColumns().stream().map(IColumn::getName).toList();
    }

    private static IStatement statement(IDatabase db, String name) {
        IStatement statement = db.getStatement(new ObjectReference("dbo", name, DbObjType.TABLE));
        assertNotNull(statement, "fixture must hold " + name);
        return statement;
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

    private static String doc(String... columns) {
        return """


                CREATE TABLE dbo.doc (
                %s
                );""".formatted(Stream.of(columns).map(column -> '\t' + column)
                .collect(Collectors.joining(",\n")));
    }
}
