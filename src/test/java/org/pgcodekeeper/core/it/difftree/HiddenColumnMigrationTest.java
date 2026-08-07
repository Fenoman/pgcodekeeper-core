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
package org.pgcodekeeper.core.it.difftree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * A column named by a {@code type=COLUMN} rule leaves the comparison: the two
 * states of the database are compared as if that column were in neither of them.
 * <p>
 * The cases below pin three faces of that one sentence - the column is never
 * altered, added or dropped; a table that differs in nothing else is not a
 * difference at all; and the script the rest of the schema produces does not
 * move by a byte. The last one is the point of the whole exercise, so it is
 * asserted against the very same script the same fixture produces with no
 * ignore list at all.
 * <p>
 * The fourth face is the one the rule does <em>not</em> have. Generation is not
 * told about the rules at all: a table written from the project is written
 * whole, see {@link CreateScriptWritesEveryColumnTest}. What keeps that safe is
 * the first face - nothing ever asks the generator for a statement about a
 * hidden column, see {@link HiddenColumnIsNeverDroppedTest}.
 *
 * @see DiffTreeIgnoreListParityTest for the other half of the promise, that a
 * list without a single {@code type=COLUMN} rule changes nothing anywhere
 */
class HiddenColumnMigrationTest {

    private static final String AUDIT_LIST = "audit_columns.pgcodekeeperignore";

    private static final List<String> AUDIT_COLUMNS = List.of(
            "s_audit_id_create", "s_audit_id_modif", "s_create_date", "s_creator", "s_modif_date", "s_owner");

    /**
     * A table whose business column and whose audit columns both differ from the
     * state below, so that one run can tell that exactly one of the two kinds of
     * difference survives.
     */
    private static final String DOC_OLD = """
            CREATE TABLE public.doc (
                id bigint NOT NULL,
                title text,
                s_creator text,
                s_create_date timestamp without time zone
            );""";

    private static final String DOC_NEW = """
            CREATE TABLE public.doc (
                id bigint NOT NULL,
                title character varying(200),
                s_creator character varying(100),
                s_create_date timestamp with time zone,
                s_owner text NOT NULL
            );""";

    /** The same table, differing in its audit columns alone. */
    private static final String DOC_AUDIT_ONLY = """
            CREATE TABLE public.doc (
                id bigint NOT NULL,
                title text,
                s_creator character varying(100),
                s_create_date timestamp with time zone,
                s_owner text NOT NULL
            );""";

    private static final String NOTHING = "CREATE SCHEMA public;";

    /**
     * The audited table beside a table the ignore list says nothing about, so
     * that "only the hidden columns move" can be measured rather than argued.
     */
    private static final String TWO_TABLES_OLD = DOC_OLD + """


            CREATE TABLE public.plain (
                id bigint NOT NULL,
                note text
            );""";

    private static final String TWO_TABLES_NEW = DOC_NEW + """


            CREATE TABLE public.plain (
                id bigint NOT NULL,
                note character varying(50)
            );""";

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    /**
     * The heart of the matter: the audit columns produce no {@code ALTER},
     * no {@code ADD} and no {@code DROP}, and what is left is byte for byte the
     * script the business column produces on its own.
     */
    @Test
    void hiddenColumnsProduceNoAlterAddOrDrop() throws IOException, InterruptedException {
        String withoutAudit = script(DOC_OLD, DOC_NEW, auditHidden());

        assertScriptIs("""
                SET search_path = pg_catalog;

                ALTER TABLE public.doc
                \tALTER COLUMN title TYPE character varying(200) USING title::character varying(200); /* %s */"""
                .formatted(typeChangeWarning("public.doc", "text", "character varying(200)")), withoutAudit);

        for (String column : AUDIT_COLUMNS) {
            assertFalse(withoutAudit.contains(column), "no statement may name the hidden column " + column);
        }
    }

    /**
     * The other half of the same promise, stated as the operator would state it:
     * hiding a column removes the statements about that column and rewrites
     * nothing else. What is left is not merely equivalent to what the unhidden
     * run produced for the visible column - it is the same bytes.
     */
    @Test
    void nothingButTheHiddenColumnsMovesInTheScript() throws IOException, InterruptedException {
        String full = script(TWO_TABLES_OLD, TWO_TABLES_NEW, new CoreSettings());
        String hidden = script(TWO_TABLES_OLD, TWO_TABLES_NEW, auditHidden());

        assertEquals(statementAbout("public.plain", full), statementAbout("public.plain", hidden),
                "a table with nothing hidden in it must not move by a byte");
        assertTrue(statements(full).size() > statements(hidden).size(),
                "and the hidden columns must really have produced statements of their own");

        assertScriptIs("""
                SET search_path = pg_catalog;

                ALTER TABLE public.doc
                \tALTER COLUMN title TYPE character varying(200) USING title::character varying(200); /* %s */

                ALTER TABLE public.plain
                \tALTER COLUMN note TYPE character varying(50) USING note::character varying(50); /* %s */"""
                .formatted(typeChangeWarning("public.doc", "text", "character varying(200)"),
                        typeChangeWarning("public.plain", "text", "character varying(50)")), hidden);
    }

    /**
     * The same pair with nothing hidden, so that the case above is known to
     * remove something rather than to have found an empty diff.
     */
    @Test
    void theSameColumnsAreMigratedWhenNothingIsHidden() throws IOException, InterruptedException {
        String full = script(DOC_OLD, DOC_NEW, new CoreSettings());

        assertTrue(full.contains("ALTER COLUMN s_creator TYPE character varying(100)"), full);
        assertTrue(full.contains("ALTER COLUMN s_create_date TYPE timestamp with time zone"), full);
        assertTrue(full.contains("ADD COLUMN s_owner text NOT NULL"), full);
        assertTrue(full.contains("ALTER COLUMN title TYPE character varying(200)"), full);
    }

    /**
     * A table whose whole difference is hidden is not a changed table. It leaves
     * the tree, so the operator is not offered a change that can never be
     * migrated, and it leaves the script empty.
     */
    @Test
    void aTableDifferingOnlyInHiddenColumnsLeavesTheTree() throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        LoadedComparison loaded = load(DOC_OLD, DOC_AUDIT_ONLY, settings);

        assertEquals(List.of(), tableNames(
                        DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase())),
                "a table whose only differences are hidden is unchanged");
        assertScriptIs("", PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings));
    }

    /**
     * The same pair keeps its table when nothing is hidden, so the case above
     * measures the hiding and not an accidental equality.
     */
    @Test
    void theSameTableStaysInTheTreeWhenNothingIsHidden() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        LoadedComparison loaded = load(DOC_OLD, DOC_AUDIT_ONLY, settings);
        assertEquals(List.of("doc"),
                tableNames(DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase())),
                "the audit columns really do differ");
    }

    /**
     * Where the rule stops: a table created from scratch is created with every
     * column the project declares. The rule keeps a column out of a comparison,
     * not out of a definition, so what is written is the project.
     */
    @Test
    void aNewTableIsCreatedWithEveryColumnTheProjectDeclares() throws IOException, InterruptedException {
        String created = script(NOTHING, DOC_NEW, auditHidden());

        assertScriptIs("""
                SET search_path = pg_catalog;

                CREATE TABLE public.doc (
                \tid bigint NOT NULL,
                \ttitle character varying(200),
                \ts_creator character varying(100),
                \ts_create_date timestamp with time zone,
                \ts_owner text NOT NULL
                );""", created);
        assertScriptIs(script(NOTHING, DOC_NEW, new CoreSettings()), created);
    }

    /**
     * The rule the operator actually writes, read from a
     * {@code .pgcodekeeperignore} file, so that the grammar and the effect are
     * proven to meet.
     */
    @Test
    void theRuleWorksAsWrittenInAnIgnoreFile() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        settings.addIgnoreList(path(AUDIT_LIST));

        assertScriptIs(script(DOC_OLD, DOC_NEW, auditHidden()), script(DOC_OLD, DOC_NEW, settings));
    }

    /**
     * A qualified rule names the column through its table, which is how two
     * tables sharing a column name are told apart.
     */
    @Test
    void aQualifiedRuleHidesOnlyTheColumnItNames() throws IOException, InterruptedException {
        CoreSettings hidden = new CoreSettings();
        hidden.getIgnoreList().add(rule("public.doc.s_creator", true));

        String script = script(DOC_OLD, DOC_NEW, hidden);
        assertFalse(script.contains("s_creator"), "the qualified rule must hide its column: " + script);
        assertTrue(script.contains("s_create_date"), "and hide nothing else: " + script);

        CoreSettings other = new CoreSettings();
        other.getIgnoreList().add(rule("public.other.s_creator", true));
        assertTrue(script(DOC_OLD, DOC_NEW, other).contains("s_creator"),
                "a qualified rule naming another table must not reach this one");
    }

    /**
     * A white list hides everything it does not name - except columns. A column
     * is a part of the definition of its table rather than an object of its own,
     * so leaving it unnamed cannot be what strips it out of a {@code CREATE}:
     * only a rule that hides hides a column.
     */
    @Test
    void aWhiteListHidesNoColumnItDoesNotName() throws IOException, InterruptedException {
        CoreSettings whitelist = new CoreSettings();
        IgnoreList list = whitelist.getIgnoreList();
        list.setShow(false);
        list.add(new IgnoredObject("doc", null, true, false, true, false, EnumSet.of(DbObjType.TABLE)));
        list.add(new IgnoredObject("public", null, true, false, true, false, EnumSet.of(DbObjType.SCHEMA)));

        assertScriptIs(script(DOC_OLD, DOC_NEW, new CoreSettings()), script(DOC_OLD, DOC_NEW, whitelist));
    }

    /**
     * The hiding rule bites inside a white list just as it does inside a black
     * one: the mode of the list decides what an unnamed object defaults to, and
     * a column has no default to change.
     */
    @Test
    void aHidingRuleWorksInsideAWhiteList() throws IOException, InterruptedException {
        CoreSettings whitelist = new CoreSettings();
        IgnoreList list = whitelist.getIgnoreList();
        list.setShow(false);
        list.add(new IgnoredObject("doc", null, true, false, true, false, EnumSet.of(DbObjType.TABLE)));
        list.add(new IgnoredObject("public", null, true, false, true, false, EnumSet.of(DbObjType.SCHEMA)));
        AUDIT_COLUMNS.forEach(column -> list.add(rule(column, false)));

        assertScriptIs(script(DOC_OLD, DOC_NEW, auditHidden()), script(DOC_OLD, DOC_NEW, whitelist));
    }

    /**
     * A rule without a {@code type=} attribute matches every kind of object, and
     * a column is the one kind it must leave alone. Such a rule was written
     * while columns could not be hidden at all, and reading it as a column rule
     * now would silently strip columns out of tables it was never aimed at.
     */
    @Test
    void aRuleWithoutAnObjectTypeLeavesColumnsAlone() throws IOException, InterruptedException {
        CoreSettings untyped = new CoreSettings();
        AUDIT_COLUMNS.forEach(column -> untyped.getIgnoreList()
                .add(new IgnoredObject(column, null, false, false, false, false, EnumSet.noneOf(DbObjType.class))));

        assertScriptIs(script(DOC_OLD, DOC_NEW, new CoreSettings()), script(DOC_OLD, DOC_NEW, untyped));
    }

    /**
     * An empty black list and a missing one are the same thing, and neither
     * touches a column.
     */
    @Test
    void anEmptyOrMissingListChangesNothing() throws IOException, InterruptedException {
        String plain = script(DOC_OLD, DOC_NEW, new CoreSettings());

        CoreSettings empty = new CoreSettings();
        empty.getIgnoreList().clearList();
        assertScriptIs(plain, script(DOC_OLD, DOC_NEW, empty));

        CoreSettings showing = new CoreSettings();
        showing.getIgnoreList().add(rule("s_creator", false, true));
        assertScriptIs(plain, script(DOC_OLD, DOC_NEW, showing));
    }

    /**
     * The rule applies where the column is inert, and nowhere else. A key over
     * the column is the plainest way for a table to need it, and the real shape
     * of the case this was written for.
     */
    @Test
    void aColumnUnderAUniqueKeyStaysManaged() throws IOException, InterruptedException {
        assertColumnStaysManaged("""
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    f_groups bigint,
                    c_table text,
                    j_modif jsonb,
                    s_create_date timestamp NOT NULL,
                    CONSTRAINT chk_wd_changes_triggers_unique UNIQUE (f_groups, c_table, j_modif, s_create_date)
                );""", "s_create_date");
    }

    /**
     * A {@code CHECK} publishes none of the columns of its condition, so this
     * one is held by the references the loader resolved and by nothing else.
     */
    @Test
    void aColumnInsideACheckConditionStaysManaged() throws IOException, InterruptedException {
        assertColumnStaysManaged("""
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_create_date timestamp,
                    s_modif_date timestamp,
                    CONSTRAINT chk_dates CHECK (s_modif_date >= s_create_date)
                );""", "s_modif_date");
    }

    @Test
    void aColumnUnderAnIndexStaysManaged() throws IOException, InterruptedException {
        assertColumnStaysManaged("""
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_creator text
                );

                CREATE INDEX doc_creator_idx ON public.doc (s_creator);""", "s_creator");
    }

    /**
     * An index need not name a column plainly to need it: the real case that
     * prompted this was an index over an expression of two audit columns.
     */
    @Test
    void aColumnInsideAnIndexExpressionStaysManaged() throws IOException, InterruptedException {
        String indexed = """
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_create_date timestamp,
                    s_modif_date timestamp
                );

                CREATE INDEX doc_dates_idx ON public.doc USING btree (COALESCE(s_modif_date, s_create_date));""";

        assertColumnStaysManaged(indexed, "s_create_date");
        assertColumnStaysManaged(indexed, "s_modif_date");
    }

    @Test
    void aColumnReadByAGeneratedColumnStaysManaged() throws IOException, InterruptedException {
        assertColumnStaysManaged("""
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_creator text,
                    creator_upper text GENERATED ALWAYS AS (upper(s_creator)) STORED
                );""", "s_creator");
    }

    /**
     * A partition key names its columns in text that no dependency records, so
     * it is read as text. Nothing else in this table needs the column, which is
     * exactly the case that would have gone wrong quietly.
     */
    @Test
    void aColumnUnderAPartitionKeyStaysManaged() throws IOException, InterruptedException {
        assertColumnStaysManaged("""
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_create_date timestamp NOT NULL
                ) PARTITION BY RANGE (s_create_date);""", "s_create_date");
    }

    /**
     * The point of the whole inversion, in one case: one rule, two tables, two
     * different answers. The column is migrated where its table needs it and
     * left alone where it does not.
     * <p>
     * Both tables change the column in exactly the same way, so the two answers
     * are the rule speaking and nothing else.
     */
    @Test
    void theSameRuleHidesTheColumnOnlyOnTheTableThatCanSpareIt() throws IOException, InterruptedException {
        String twoTables = """
                CREATE TABLE public.needs_it (
                    id bigint NOT NULL,
                    s_creator %1$s
                );

                CREATE INDEX needs_it_creator_idx ON public.needs_it (s_creator);

                CREATE TABLE public.spares_it (
                    id bigint NOT NULL,
                    s_creator %1$s
                );""";

        String migrated = script(twoTables.formatted("text"),
                twoTables.formatted("character varying(100)"), auditHidden());

        assertTrue(migrated.contains("ALTER TABLE public.needs_it"),
                "the table that needs the column migrates it:\n" + migrated);
        assertFalse(migrated.contains("public.spares_it"),
                "the table that can spare it says nothing about it:\n" + migrated);
    }

    /**
     * A dependency in either state holds the column, because the two states must
     * agree: a column kept on one side and dropped on the other would make the
     * two column lists differ in length and report a table as changed while it
     * holds no change a script could carry.
     */
    @Test
    void aDependencyOnOneSideAloneHoldsTheColumn() throws IOException, InterruptedException {
        String withIndex = """
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_creator %s
                );

                CREATE INDEX doc_creator_idx ON public.doc (s_creator);""";
        String withoutIndex = """
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_creator %s
                );""";

        for (boolean indexOnTheOldSide : new boolean[] { true, false }) {
            String oldSql = (indexOnTheOldSide ? withIndex : withoutIndex).formatted("text");
            String newSql = (indexOnTheOldSide ? withoutIndex : withIndex)
                    .formatted("character varying(100)");

            String script = script(oldSql, newSql, auditHidden());
            assertTrue(script.contains("ALTER COLUMN s_creator TYPE character varying(100)"),
                    "an index on either side holds the column under management:\n" + script);
        }
    }

    /**
     * The contrast that gives the case above its meaning: the very same change
     * to the very same column, with no index on either side, is not migrated at
     * all.
     */
    @Test
    void withoutAnyDependencyTheSameColumnChangeIsNotMigrated() throws IOException, InterruptedException {
        String table = """
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    s_creator %s
                );""";

        assertScriptIs("", script(table.formatted("text"), table.formatted("character varying(100)"),
                auditHidden()));
    }

    /**
     * A rule that applies here and not there has to say so. The reason is
     * carried per column and per table, and it names the object that holds the
     * column, so that reading the log is enough to settle why one table kept it.
     */
    @Test
    void aColumnKeptByADependencyIsReported() throws IOException, InterruptedException {
        String twoTables = """
                CREATE TABLE public.needs_it (
                    id bigint NOT NULL,
                    s_creator text
                );

                CREATE INDEX needs_it_creator_idx ON public.needs_it (s_creator);

                CREATE TABLE public.spares_it (
                    id bigint NOT NULL,
                    s_creator text
                );""";

        CoreSettings settings = auditHidden();
        LoadedComparison loaded = load(NOTHING, twoTables, settings);
        ColumnVisibility managed = ColumnVisibility.of(settings);

        assertEquals(Map.of("s_creator", "public.needs_it_creator_idx"),
                managed.pinnedColumns(table(loaded, "needs_it")),
                "the object that holds the column must be named");
        assertEquals(Map.of(), managed.pinnedColumns(table(loaded, "spares_it")),
                "a table that spares the column has nothing to report");

        assertFalse(Messages.ColumnVisibility_log_column_kept
                .formatted("s_creator", "public.needs_it", "public.needs_it_creator_idx").isBlank(),
                "the reason must be sayable in the language of the run");
    }

    /**
     * An existing table is not rewritten by an {@code ALTER}, so a hidden column
     * it can spare stays out of the migration of its neighbours.
     */
    @Test
    void anExistingTableMigratesItsVisibleColumnsAsUsual() throws IOException, InterruptedException {
        String table = """
                CREATE TABLE public.doc (
                    id bigint NOT NULL,
                    title %s,
                    s_creator text
                );""";

        String script = script(table.formatted("text"), table.formatted("character varying(200)"), auditHidden());

        assertTrue(script.contains("ALTER COLUMN title TYPE character varying(200)"), script);
        assertFalse(script.contains("s_creator"), script);
    }

    /**
     * A {@code COLUMN} rule is about the columns of a table. The attributes of a
     * composite type are {@code PgColumn}s of the same type as well, but they
     * are diffed as a part of the definition of their type and never become a
     * node of a tree; hiding one would rewrite a {@code CREATE TYPE} with
     * nothing to warn its author.
     */
    @Test
    void aCompositeTypeAttributeIsNotAColumnRuleCanHide() throws IOException, InterruptedException {
        String type = """
                CREATE TYPE public.audit_stamp AS (
                    s_creator %s,
                    id bigint
                );""";
        String oldSql = type.formatted("text");
        String newSql = type.formatted("character varying(100)");

        String hidden = script(oldSql, newSql, auditHidden());
        assertTrue(hidden.contains("s_creator"), "the attribute must be migrated all the same:\n" + hidden);
        assertScriptIs(script(oldSql, newSql, new CoreSettings()), hidden);
    }

    private static CoreSettings auditHidden() {
        CoreSettings settings = new CoreSettings();
        AUDIT_COLUMNS.forEach(column -> settings.getIgnoreList().add(rule(column, false)));
        return settings;
    }

    private static IgnoredObject rule(String name, boolean qualified) {
        return rule(name, qualified, false);
    }

    private static IgnoredObject rule(String name, boolean qualified, boolean show) {
        return new IgnoredObject(name, null, show, false, false, qualified, EnumSet.of(DbObjType.COLUMN));
    }

    private String script(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        LoadedComparison loaded = load(oldSql, newSql, settings);
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings);
    }

    private static void assertScriptIs(String expected, String actual) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8),
                () -> "script must be byte for byte:\nexpected\n" + expected + "\nactual\n" + actual);
    }

    private static ITable table(LoadedComparison loaded, String name) {
        return (ITable) loaded.newDatabase().getStatement(
                new ObjectReference("public", name, DbObjType.TABLE));
    }

    private static List<String> tableNames(TreeElement root) {
        List<String> names = new ArrayList<>();
        collectTables(root, names);
        return names;
    }

    private static void collectTables(TreeElement el, List<String> names) {
        if (el.getType() == DbObjType.TABLE) {
            names.add(el.getName());
        }
        for (TreeElement child : el.getChildren()) {
            collectTables(child, names);
        }
    }

    /**
     * The script split into the statements it is built from, so that removing
     * one of them can be told apart from rewriting the rest.
     */
    private static List<String> statements(String script) {
        return List.of(script.split("\n\n"));
    }

    /**
     * A table that needs one of its audit columns keeps that column under
     * management although a rule names it, so the comparison goes on migrating
     * it as it always did.
     * <p>
     * Asked of the rules directly rather than of a script. A script is written
     * from the project whatever the rules say, so it cannot tell a column the
     * rules kept from one they let go; the question of whether a column is still
     * managed is a question about the comparison and is put to the comparison.
     */
    private void assertColumnStaysManaged(String tableSql, String column) throws IOException, InterruptedException {
        CoreSettings settings = auditHidden();
        LoadedComparison loaded = load(NOTHING, tableSql, settings);

        assertTrue(ColumnVisibility.of(settings).pinnedColumns(table(loaded, "doc")).containsKey(column),
                "the column its table needs must stay managed although a rule names it");
    }

    private static String statementAbout(String table, String script) {
        List<String> found = statements(script).stream().filter(st -> st.contains(table)).toList();
        assertEquals(1, found.size(), "expected exactly one statement about " + table + " in:\n" + script);
        return found.get(0);
    }

    private static String typeChangeWarning(String table, String oldType, String newType) {
        return org.pgcodekeeper.core.localizations.Messages.Table_TypeParameterChange
                .formatted(table, oldType, newType);
    }

    /**
     * Loads both states the way a real comparison loads them, analysis and all.
     * The dependencies an index records on the columns it indexes are written by
     * that analysis, and the refusal below reads them.
     */
    private LoadedComparison load(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        settings.clearErrors();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> provider.getDumpLoader(source(oldSql), "old", sideSettings),
                        sideSettings -> provider.getDumpLoader(source(newSql), "new", sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        assertNotNull(loaded.newDatabase(), "fixture must load");
        return loaded;
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }

    private Path path(String fileName) {
        return TestUtils.getFilePath(fileName, getClass());
    }
}
