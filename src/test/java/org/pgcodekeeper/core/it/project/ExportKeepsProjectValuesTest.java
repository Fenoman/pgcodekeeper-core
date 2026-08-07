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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.ms.schema.MsSequence;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.project.PgModelExporter;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * An export to the project writes the database over the project's own file, and
 * every value the active settings declare the project's own survives that.
 * <p>
 * The settings named here take a property out of the comparison, which also
 * takes it out of the difference tree - and with it goes the one thing that used
 * to tell the user what "apply to project" was about to overwrite. The property
 * is therefore carried over from the project side rather than taken from the
 * database, while everything else still comes from the database, exactly as
 * before.
 * <p>
 * Each case below gives the two states one difference the settings hide and one
 * they do not, so that a single export can show that exactly one of the two
 * moves. The result is asserted byte for byte against a project exported from a
 * third state, the state the project is supposed to end up in - an oracle built
 * by the very same exporter, but never through the code path under test.
 *
 * @see org.pgcodekeeper.core.it.difftree.SequenceCacheComparisonTest for the
 * comparison the settings relax
 */
class ExportKeepsProjectValuesTest {

    private static final String SEQUENCE_FILE = "SCHEMA/dbo/SEQUENCE/counter.sql";
    private static final String TABLE_FILE = "SCHEMA/dbo/TABLE/fd_bank_accounts.sql";
    private static final String DOC_FILE = "SCHEMA/dbo/TABLE/doc.sql";

    private static final String SCHEMA = "CREATE SCHEMA dbo;";

    /** The state of a standalone sequence the project states on purpose. */
    private static final String SEQUENCE_PROJECT = SCHEMA + """


            CREATE SEQUENCE dbo.counter
            \tSTART WITH 1
            \tINCREMENT BY 1
            \tNO MAXVALUE
            \tNO MINVALUE
            \tCACHE 10;""";

    /** The same sequence as the database holds it: another cache, another increment. */
    private static final String SEQUENCE_DATABASE = SCHEMA + """


            CREATE SEQUENCE dbo.counter
            \tSTART WITH 1
            \tINCREMENT BY 2
            \tNO MAXVALUE
            \tNO MINVALUE
            \tCACHE 1;""";

    /** The increment of the database, the cache of the project. */
    private static final String SEQUENCE_ADOPTED = SCHEMA + """


            CREATE SEQUENCE dbo.counter
            \tSTART WITH 1
            \tINCREMENT BY 2
            \tNO MAXVALUE
            \tNO MINVALUE
            \tCACHE 10;""";

    /** The trigger function both states hold, so that the trigger below resolves. */
    private static final String AUDIT_FUNCTION = """


            CREATE FUNCTION dbo.audit() RETURNS trigger
                LANGUAGE plpgsql
                AS $$BEGIN RETURN NEW; END;$$;""";

    private static final String TRIGGER = """


            CREATE TRIGGER audit_accounts
            \tBEFORE INSERT OR UPDATE ON dbo.fd_bank_accounts
            \tFOR EACH ROW
            \tEXECUTE PROCEDURE dbo.audit();""";

    /**
     * The table of the report: an identity column whose sequence renders its
     * cache inside the table's own file, and no trigger yet.
     */
    private static final String TABLE_PROJECT = SCHEMA + AUDIT_FUNCTION + identityTable("10");

    /** The same table as the database holds it: the server's cache, and a trigger. */
    private static final String TABLE_DATABASE = SCHEMA + AUDIT_FUNCTION + identityTable("1") + TRIGGER;

    /** The trigger of the database, the cache of the project. */
    private static final String TABLE_ADOPTED = SCHEMA + AUDIT_FUNCTION + identityTable("10") + TRIGGER;

    private static final String DOC_PROJECT = SCHEMA + doc("500", "project");
    private static final String DOC_DATABASE = SCHEMA + doc("100", "database");
    private static final String DOC_ADOPTED = SCHEMA + doc("500", "database");

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    /**
     * A standalone sequence keeps the cache the project states while every other
     * difference is taken from the database.
     */
    @Test
    void sequenceKeepsTheCacheOfTheProject(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(SEQUENCE_ADOPTED, SEQUENCE_PROJECT, SEQUENCE_DATABASE,
                SEQUENCE_FILE, cacheLeftAlone(), project, oracle);
    }

    /**
     * The reported regression. A table applied to the project for the sake of a
     * trigger it is missing must not bring the cache of the database with it: the
     * cache of an identity sequence is written inside the table's own file, and
     * the project states it on purpose.
     */
    @Test
    void identityCacheSurvivesATableAppliedForItsTrigger(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(TABLE_ADOPTED, TABLE_PROJECT, TABLE_DATABASE,
                TABLE_FILE, cacheLeftAlone(), project, oracle);
    }

    /**
     * The statistics target of a column is the project's while the setting says
     * so; the comment of that same column is the database's, as everything else.
     */
    @Test
    void columnKeepsTheStatisticsTargetOfTheProject(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(DOC_ADOPTED, DOC_PROJECT, DOC_DATABASE,
                DOC_FILE, statisticsLeftAlone(), project, oracle);
    }

    /**
     * With the settings off the export writes the state of the database whole,
     * byte for byte as it always has.
     */
    @Test
    void nothingIsKeptWhileTheSettingsAreOff(@TempDir Path sequence, @TempDir Path sequenceOracle,
                                             @TempDir Path table, @TempDir Path tableOracle)
            throws IOException, InterruptedException {
        assertExportWrites(SEQUENCE_DATABASE, SEQUENCE_PROJECT, SEQUENCE_DATABASE,
                SEQUENCE_FILE, new CoreSettings(), sequence, sequenceOracle);
        assertExportWrites(TABLE_DATABASE, TABLE_PROJECT, TABLE_DATABASE,
                TABLE_FILE, new CoreSettings(), table, tableOracle);
    }

    @Test
    void statisticsMoveWhileTheSettingIsOff(@TempDir Path project, @TempDir Path oracle)
            throws IOException, InterruptedException {
        assertExportWrites(DOC_DATABASE, DOC_PROJECT, DOC_DATABASE,
                DOC_FILE, new CoreSettings(), project, oracle);
    }

    /**
     * The object of the database is handed over as it is when the project owns
     * nothing of it. The identity of the answer is the point: it is what keeps
     * the common case free of an allocation, and it is asked for below rather
     * than argued about.
     */
    @Test
    void withNothingToAdoptTheSameObjectComesBack() throws IOException, InterruptedException {
        CoreSettings on = cacheLeftAlone();
        IStatement project = statement(load(SEQUENCE_PROJECT, "project", on), "counter", DbObjType.SEQUENCE);
        IStatement database = statement(load(SEQUENCE_DATABASE, "database", on), "counter", DbObjType.SEQUENCE);

        assertSame(database, database.adoptUnmanaged(project, new CoreSettings()),
                "with the setting off there is nothing the project owns");
        assertSame(database, database.adoptUnmanaged(database, on),
                "with the two caches equal there is nothing to carry over");

        IStatement table = statement(load(TABLE_DATABASE, "database", on), "fd_bank_accounts", DbObjType.TABLE);
        assertSame(table, table.adoptUnmanaged(table, on),
                "a table that agrees with the project keeps its identity too");
        assertSame(table, table.adoptUnmanaged(table, statisticsLeftAlone()),
                "and does so for the statistics target as well");
    }

    /**
     * Both objects the adoption reads belong to models the caller may still be
     * using, so it reads them, copies what it must, and writes into neither.
     */
    @Test
    void bothModelsAreLeftAlone(@TempDir Path project) throws IOException, InterruptedException {
        CoreSettings settings = cacheLeftAlone();
        IDatabase projectDb = load(TABLE_PROJECT, "project", settings);
        IDatabase databaseDb = load(TABLE_DATABASE, "database", settings);
        IStatement inDatabase = statement(databaseDb, "fd_bank_accounts", DbObjType.TABLE);
        IStatement inProject = statement(projectDb, "fd_bank_accounts", DbObjType.TABLE);
        String database = inDatabase.getSQL(false, settings);
        String beforeExport = inProject.getSQL(false, settings);

        new PgModelExporter(project, projectDb, Consts.UTF_8, settings).exportFull();
        applyToProject(projectDb, databaseDb, project, settings);

        assertEquals(database, inDatabase.getSQL(false, settings),
                "the export must not have written the project's value into the database's object");
        assertEquals(beforeExport, inProject.getSQL(false, settings),
                "nor anything of the database into the project's object");
        assertSame(inDatabase, statement(databaseDb, "fd_bank_accounts", DbObjType.TABLE),
                "and must have replaced neither");
        assertSame(inProject, statement(projectDb, "fd_bank_accounts", DbObjType.TABLE));
    }

    /**
     * MS SQL states the absence of caching as a clause of its own, so what the
     * project owns there is the whole of {@code CACHE [n] | NO CACHE} rather
     * than a number.
     */
    @Test
    void msSequenceCarriesTheWholeCacheClause() {
        MsSequence project = msSequence(true, "10");
        MsSequence database = msSequence(false, null);

        IStatement adopted = database.adoptUnmanaged(project, cacheLeftAlone());

        assertNotSame(database, adopted, "the adoption must copy rather than write into the database's object");
        assertEquals(msSequence(true, "10"), adopted, "the cache clause of the project must be the one written");
        assertEquals(msSequence(false, null), database, "the object of the database must be untouched");
        assertEquals(msSequence(true, "10"), project, "and so must the object of the project");
        assertSame(database, database.adoptUnmanaged(project, new CoreSettings()),
                "with the setting off the object of the database is handed over as it is");
    }

    private static MsSequence msSequence(boolean cached, String cache) {
        MsSequence sequence = new MsSequence("counter");
        sequence.setCached(cached);
        sequence.setCache(cache);
        sequence.setMinMaxInc(1, null, null, "bigint", 0);
        sequence.setStartWith("1");
        return sequence;
    }

    /**
     * Exports the database over a project holding the state given, and demands
     * that the file written be the file a project of the expected state exports.
     *
     * @param expectedSql the state the project must end up in
     * @param projectSql  the state of the project before the export
     * @param databaseSql the state of the database being applied to it
     * @param file        the project file both states write
     * @param settings    the settings of the export
     * @param project     the project directory
     * @param oracle      a directory for the project of the expected state
     */
    private void assertExportWrites(String expectedSql, String projectSql, String databaseSql, String file,
                                    CoreSettings settings, Path project, Path oracle)
            throws IOException, InterruptedException {
        IDatabase projectDb = load(projectSql, "project", settings);
        IDatabase databaseDb = load(databaseSql, "database", settings);

        new PgModelExporter(project, projectDb, Consts.UTF_8, settings).exportFull();
        applyToProject(projectDb, databaseDb, project, settings);

        assertFileIs(sameFileOf(expectedSql, settings, oracle).resolve(file), project.resolve(file));
    }

    /**
     * The same file of a project exported whole out of the state given, which is
     * what the export under test has to match.
     */
    private Path sameFileOf(String sql, CoreSettings settings, Path oracle) throws IOException, InterruptedException {
        IDatabase db = load(sql, "expected", settings);
        new PgModelExporter(oracle, db, Consts.UTF_8, settings).exportFull();
        return oracle;
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

    private static IStatement statement(IDatabase db, String name, DbObjType type) {
        IStatement statement = db.getStatement(new ObjectReference("dbo", name, type));
        assertNotNull(statement, "fixture must hold " + name);
        return statement;
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }

    private static CoreSettings cacheLeftAlone() {
        CoreSettings settings = new CoreSettings();
        settings.setIgnoreSequenceCache(true);
        return settings;
    }

    private static CoreSettings statisticsLeftAlone() {
        CoreSettings settings = new CoreSettings();
        settings.setIgnoreColumnStatistics(true);
        return settings;
    }

    private static String identityTable(String cache) {
        return """


                CREATE TABLE dbo.fd_bank_accounts (
                \tid bigint NOT NULL,
                \taccount text
                );

                ALTER TABLE dbo.fd_bank_accounts ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
                \tSEQUENCE NAME dbo.fd_bank_accounts_id_seq
                \tSTART WITH 1
                \tINCREMENT BY 1
                \tNO MAXVALUE
                \tNO MINVALUE
                \tCACHE %s
                );""".formatted(cache);
    }

    private static String doc(String statistics, String comment) {
        return """


                CREATE TABLE dbo.doc (
                \tid bigint NOT NULL,
                \ttitle text
                );

                ALTER TABLE ONLY dbo.doc ALTER COLUMN title SET STATISTICS %s;

                COMMENT ON COLUMN dbo.doc.title IS '%s';""".formatted(statistics, comment);
    }
}
