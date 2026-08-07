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
package org.pgcodekeeper.core.database.pg.schema;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The {@code STORAGE} of a column, and the two states of it that are one state:
 * the same word in another case, and the type's own default said out loud.
 * <p>
 * {@code PgTablesReader} writes the word in upper case ({@code EXTENDED},
 * {@code MAIN}, {@code EXTERNAL}, {@code PLAIN}) and only when
 * {@code attstorage} differs from the {@code typstorage} of the column's type -
 * a column left at its type's default carries no storage at all on the database
 * side. A project file, meanwhile, is stored as written: the grammar spells
 * {@code storage_option} out of keywords, and the lexer folds identifiers only,
 * so {@code SET STORAGE external} reaches the model in lower case and
 * {@code SET STORAGE DEFAULT} reaches it as the word {@code DEFAULT}.
 * <p>
 * Both differences used to be read as a changed column, and neither could ever
 * be migrated away:
 * <ul>
 * <li>the comparison asked {@code Objects.equals} while the generator asked
 * {@code equalsIgnoreCase}, so a re-cased storage made the diff tree show the
 * table as changed while the script came out <i>empty</i> - measured on
 * PostgreSQL 17.10, and there is no statement that could have closed it;</li>
 * <li>{@code SET STORAGE DEFAULT} against a database that says nothing produced
 * that statement on every single run: the server sets {@code attstorage} back
 * to the type's default, which is where it already was, so the next read says
 * nothing again.</li>
 * </ul>
 * <p>
 * So the comparison, the hash and the generator now ask one and the same
 * question - {@code PgColumn.normalizeStorage} - while the script keeps writing
 * the author's own spelling.
 */
class PgColumnStorageTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "t1";
    private static final String COLUMN_NAME = "c1";

    /**
     * The case half. The upper-case side is what a database read produces, so
     * this pair is the project file against its own database - and the
     * assertion that bites is the tree, not the script: the script was already
     * empty before, which is exactly what made the difference unfixable.
     */
    @Test
    void aReCasedStorageIsNotAChange() throws Exception {
        assertNoDifference(file("SET STORAGE EXTERNAL"), file("SET STORAGE external"));
        assertNoDifference(file("SET STORAGE external"), file("SET STORAGE EXTERNAL"));
        assertNoDifference(file("SET STORAGE MAIN"), file("SET STORAGE Main"));
    }

    /**
     * The default half. A file that spells {@code DEFAULT} describes the column
     * a database with no storage of its own hands over.
     */
    @Test
    void anExplicitDefaultIsTheSameAsNoStorageAtAll() throws Exception {
        assertNoDifference(file(null), file("SET STORAGE DEFAULT"));
        assertNoDifference(file("SET STORAGE DEFAULT"), file(null));
        assertNoDifference(file("SET STORAGE default"), file(null));
    }

    /**
     * The mutation guard for both halves: a genuinely different storage must
     * still be seen and still be written, or the fix would have been "stop
     * comparing storage".
     */
    @Test
    void aGenuinelyDifferentStorageIsStillWritten() throws Exception {
        String script = pipeline(file("SET STORAGE MAIN"), file("SET STORAGE EXTERNAL"));
        assertTrue(script.contains("SET STORAGE EXTERNAL"),
                () -> "a changed storage must still reach the script, got:\n" + script);
        assertFalse(treeOf(file("SET STORAGE MAIN"), file("SET STORAGE EXTERNAL")).getChildren().isEmpty(),
                "and the diff tree must still show the table");

        String fromDefault = pipeline(file(null), file("SET STORAGE EXTERNAL"));
        assertTrue(fromDefault.contains("SET STORAGE EXTERNAL"),
                () -> "and so must a column moved off its type's default, got:\n" + fromDefault);
    }

    /**
     * The other end of the {@code DEFAULT} rule: it is a value, not an absence.
     * A column that really is moved back to its type's default must get the
     * statement that moves it, which a fix that simply dropped the word at
     * parse time could not produce - it would fall into the "new side has no
     * storage" branch and write a warning comment instead.
     */
    @Test
    void movingAColumnBackToItsDefaultIsStillWritten() throws Exception {
        String script = pipeline(file("SET STORAGE EXTERNAL"), file("SET STORAGE DEFAULT"));
        assertTrue(script.contains("SET STORAGE DEFAULT"),
                () -> "a reset to the type's default must reach the script, got:\n" + script);
        assertFalse(script.contains("WARNING"),
                () -> "and it is a statement, not a warning about a missing storage, got:\n" + script);
    }

    /**
     * The seam between the comparison and the generator, which the tests above
     * cannot reach: a pair the comparison calls equal never gets a tree element,
     * so nothing ever asks the generator about it. It is asked as soon as the
     * table differs in something else - and then a storage that is not a
     * difference must add nothing to the {@code ALTER} that difference earned.
     * <p>
     * This is what makes the third call site part of the fix rather than
     * decoration: with the generator still comparing raw text, the added column
     * below would drag a {@code SET STORAGE DEFAULT} along with it, on every
     * run, forever.
     */
    @Test
    void aFoldedStorageAddsNothingToAnAlterThatWasNeededAnyway() throws Exception {
        // the difference has to be on this very column: a column the comparison
        // calls equal gets no tree element of its own, so its generator is never
        // asked, whatever else the table does
        String withDefault = pipeline(file(null), file("SET STORAGE DEFAULT", "character varying(20)"));
        assertTrue(withDefault.contains("TYPE character varying(20)"),
                () -> "the change that had to be written must be there, got:\n" + withDefault);
        assertFalse(withDefault.contains("SET STORAGE"),
                () -> "and the storage that is not a change must not ride along, got:\n" + withDefault);

        String reCased = pipeline(file("SET STORAGE EXTERNAL"),
                file("SET STORAGE external", "character varying(20)"));
        assertTrue(reCased.contains("TYPE character varying(20)"),
                () -> "the change that had to be written must be there, got:\n" + reCased);
        assertFalse(reCased.contains("SET STORAGE"),
                () -> "and neither may a re-cased one, got:\n" + reCased);
    }

    /**
     * The normalized form is for comparing, never for writing: the script
     * carries the spelling the project file used.
     */
    @Test
    void theScriptKeepsTheAuthorsOwnSpelling() throws Exception {
        String script = pipeline(file(null), file("SET STORAGE external"));
        assertTrue(script.contains("SET STORAGE external"),
                () -> "the author's own spelling must reach the script, got:\n" + script);
        assertFalse(script.contains("SET STORAGE EXTERNAL"),
                () -> "and the normalized one must not, got:\n" + script);
    }

    /**
     * The warning a project that says nothing about storage still earns, pinned
     * because {@code DEFAULT} now shares the branch that used to be the only
     * way into it. A file with no storage clause cannot be told from one whose
     * column never had any, so the old behaviour stands.
     */
    @Test
    void aProjectSilentAboutStorageStillWarns() throws Exception {
        String script = pipeline(file("SET STORAGE EXTERNAL"), file(null));
        assertTrue(script.contains("WARNING"),
                () -> "a project that says nothing must still warn, got:\n" + script);
    }

    private static void assertNoDifference(PgDatabase oldDb, PgDatabase newDb) throws Exception {
        PgColumn oldColumn = columnOf(oldDb);
        PgColumn newColumn = columnOf(newDb);
        assertNotEquals(oldColumn.getStorage(), newColumn.getStorage(),
                "the two spellings must really differ, or this test proves nothing");

        assertEquals(oldColumn.hashCode(), newColumn.hashCode(),
                "columns that describe one state must hash the same");
        assertTrue(Comparison.compare(new CoreSettings(), oldColumn, newColumn),
                "and the entry point the diff tree uses must call them unchanged");

        assertTrue(treeOf(oldDb, newDb).getChildren().isEmpty(),
                "the diff tree must not show the table as changed");
        String script = pipeline(oldDb, newDb);
        assertEquals("", script.trim(), () -> "and no statement may be written, got:\n" + script);
    }

    private static TreeElement treeOf(PgDatabase oldDb, PgDatabase newDb) throws InterruptedException {
        return DiffTree.create(new CoreSettings(), oldDb, newDb, null);
    }

    private static PgColumn columnOf(PgDatabase db) {
        var schema = (PgSchema) db.getChild(SCHEMA_NAME, org.pgcodekeeper.core.database.api.schema.DbObjType.SCHEMA);
        var table = (PgAbstractTable) schema.getStatementContainer(TABLE_NAME);
        return (PgColumn) table.getColumn(COLUMN_NAME);
    }

    private static PgDatabase file(String storageAction) throws Exception {
        return file(storageAction, null);
    }

    /**
     * @param storageAction the {@code ALTER COLUMN} action that sets the
     *                      storage, or null for a file that says nothing about
     *                      it
     * @param type          the type of the column, for the pairs that need it to
     *                      differ in something besides the storage
     */
    private static PgDatabase file(String storageAction, String type) throws Exception {
        return loadProjectFile("""
                CREATE TABLE %1$s.%2$s (%3$s %5$s);
                %4$s
                """.formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME,
                storageAction == null ? ""
                        : "ALTER TABLE ONLY %1$s.%2$s ALTER COLUMN %3$s %4$s;"
                                .formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, storageAction),
                type == null ? "text" : type));
    }

    private static PgDatabase loadProjectFile(String sql) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "column storage test", new CoreSettings()).load();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
