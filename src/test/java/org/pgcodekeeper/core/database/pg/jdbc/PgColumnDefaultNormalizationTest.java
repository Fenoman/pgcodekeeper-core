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
package org.pgcodekeeper.core.database.pg.jdbc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * A column {@code DEFAULT} - and the {@code GENERATED ALWAYS AS} expression,
 * which {@link PgColumn} holds in the same field - reaches the model from four
 * writers: the inline {@code DEFAULT} and the generation expression of a
 * {@code CREATE TABLE} ({@code PgTableAbstract}), the {@code SET DEFAULT} of an
 * {@code ALTER TABLE} ({@code PgAlterTable}), and {@link PgTablesReader}, which
 * re-parses the {@code pg_get_expr} text the catalog returns.
 * <p>
 * The first three are driven here through {@link PgDumpLoader}, i.e. the whole
 * project-file route including the listener, rather than by calling the parser
 * classes directly. The fourth is driven by running
 * {@link PgTablesReader#processResult} over a mocked catalog row and finishing
 * the loader's parse queue - the reader's own line is reachable no other way,
 * and a helper that mirrors that line would be a copy of the reader rather than
 * the reader.
 * <p>
 * Every expression below is written so that it <i>differs from its own
 * normalized form</i>. A fixture already spelled canonically would make the raw
 * and the normalized halves byte-identical, every assertion would pass whether
 * or not normalization happened at all, and no mutation of the production code
 * could redden the test. {@link #theFixturesDifferFromTheirOwnNormalizedForm()}
 * asserts that property instead of assuming it.
 */
class PgColumnDefaultNormalizationTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "orders";
    private static final String COLUMN_NAME = "amount";
    private static final String COLUMN_TYPE = "numeric";

    /**
     * How a project file might hold the default: tight around the parenthesis
     * and lower case throughout.
     */
    private static final String TIGHT = "cast(0 as numeric)";

    /**
     * The same default as another branch - or a {@code pg_get_expr()} result -
     * might spell it.
     */
    private static final String SPACED = "CAST(0 AS  numeric)";

    /**
     * What the normalizer makes of both of the above. Pinned as a literal
     * because the normalized field deliberately has no getter: a column built by
     * hand with this text is the only way to observe from outside that the
     * writer stored exactly it.
     */
    private static final String NORMALIZED = "CAST (0 AS numeric)";

    /** The generation expression, in the same three spellings. */
    private static final String GEN_TIGHT = "cast(price*qty as numeric)";
    private static final String GEN_SPACED = "CAST( price * qty AS numeric )";
    private static final String GEN_NORMALIZED = "CAST (price * qty AS numeric)";

    /**
     * A default the expression grammar cannot read. Its only job is to fail the
     * parse, and {@link #aDefaultThatFailsToParseStillReachesTheModel()} asserts
     * that it does rather than assuming it - a fixture that quietly started
     * parsing would turn that test green for the wrong reason.
     */
    private static final String UNPARSABLE = "0 COLLATE";

    /**
     * The line break an author is free to put after the word {@code DEFAULT},
     * and the indent under it. Everything the raw half is about lives in this
     * string: written on one line, the two writers of a default produce the same
     * text whichever helper they take it through, and
     * {@link #aMultiLineSetDefaultHoldsTheTextTheInlineOneHolds()} would assert
     * nothing.
     */
    private static final String BREAK = "\n        ";

    @Test
    void theFixturesDifferFromTheirOwnNormalizedForm() {
        assertNotEquals(TIGHT, NORMALIZED,
                "a canonically written fixture would make this whole test decorative");
        assertNotEquals(SPACED, NORMALIZED, "same for the second spelling");
        assertEquals(NORMALIZED, normalize(TIGHT),
                "the pinned normalized text must be what the normalizer actually produces");
        assertEquals(NORMALIZED, normalize(SPACED), "and both spellings must reach it");

        assertNotEquals(GEN_TIGHT, GEN_NORMALIZED, "the generation expression too");
        assertNotEquals(GEN_SPACED, GEN_NORMALIZED, "and its second spelling");
        assertEquals(GEN_NORMALIZED, normalize(GEN_TIGHT), "pinned against the normalizer");
        assertEquals(GEN_NORMALIZED, normalize(GEN_SPACED), "and both spellings must reach it");
    }

    @Test
    void aRespacedDefaultReadsAsUnchanged() throws Exception {
        assertConverge(columnOf(createTableWithDefault(TIGHT)), columnOf(createTableWithDefault(SPACED)));
    }

    @Test
    void aRespacedGenerationExpressionReadsAsUnchanged() throws Exception {
        assertConverge(columnOf(createTableGenerated(GEN_TIGHT)), columnOf(createTableGenerated(GEN_SPACED)));
    }

    /**
     * The third project-side writer. An {@code ALTER TABLE ... SET DEFAULT}
     * derives its raw text through a different helper than the inline
     * {@code DEFAULT} of a {@code CREATE TABLE} does, so before this change the
     * two could hold the same default and still be compared as two texts; both
     * now normalize through the one funnel.
     */
    @Test
    void theSetDefaultOfAnAlterTableNormalizesTheSameWay() throws Exception {
        PgColumn fromAlter = columnOf(loadProjectFile("""
                CREATE TABLE %s.%s (%s %s);
                ALTER TABLE %s.%s ALTER COLUMN %s SET DEFAULT %s;
                """.formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE,
                SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, SPACED)));

        assertConverge(fromAlter, columnOf(createTableWithDefault(TIGHT)));
    }

    /**
     * The raw half of that same pair, which is a separate question and had a
     * separate answer. The two writers took their text through different
     * helpers: the inline {@code DEFAULT} through {@code getExpressionText},
     * which keeps the line break the author put in front of the expression, and
     * the {@code SET DEFAULT} through {@code getFullCtxTextWithCheckNewLines},
     * which drops it. A default written across two lines therefore held two
     * different texts depending on which statement declared it - and the raw
     * half is the text every script is written from.
     * <p>
     * The comparison could not see this and still cannot: both halves normalize
     * to the same thing, so the pair converges either way, which is asserted
     * below alongside the texts. That is what made it invisible - an
     * empty-script assertion cannot show a difference in the text the script
     * would carry if there were a script.
     */
    @Test
    void aMultiLineSetDefaultHoldsTheTextTheInlineOneHolds() throws Exception {
        PgColumn inline = columnOf(loadProjectFile("""
                CREATE TABLE %s.%s (%s %s DEFAULT%s%s);
                """.formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, BREAK, TIGHT)));
        PgColumn byAlter = columnOf(loadProjectFile("""
                CREATE TABLE %s.%s (%s %s);
                ALTER TABLE %s.%s ALTER COLUMN %s SET DEFAULT%s%s;
                """.formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE,
                SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, BREAK, TIGHT)));

        String inlineDdl = columnScript(inline);
        assertTrue(inlineDdl.contains("SET DEFAULT " + BREAK + TIGHT),
                () -> "the reference spelling has to be genuinely multi-line or this test is decorative, got:\n"
                        + inlineDdl);
        assertEquals(inlineDdl, columnScript(byAlter),
                "one default written two ways must be one text, because that text is what a script carries");

        assertConverge(inline, byAlter);
    }

    /**
     * The normalized text the writer stored, observed without a getter: a column
     * carrying the pinned normalized form compares equal to a parsed one. Were
     * a writer to hand the raw text over as the normalized one, this column
     * would hold {@code NORMALIZED} while the parsed one held {@code TIGHT}, and
     * the two would part.
     */
    @Test
    void theWriterStoresTheNormalizedFormAndNotTheRawOne() throws Exception {
        assertConverge(handBuilt(TIGHT, NORMALIZED), columnOf(createTableWithDefault(TIGHT)));
        assertConverge(handBuilt(SPACED, NORMALIZED), columnOf(createTableWithDefault(SPACED)));
    }

    @Test
    void aGenuinelyDifferentDefaultStillComparesAsChanged() throws Exception {
        PgColumn original = columnOf(createTableWithDefault("cast(0 as numeric)"));
        PgColumn changed = columnOf(createTableWithDefault("cast(1 as numeric)"));

        assertFalse(original.compare(changed), "a genuinely different default must still compare as changed");
        assertFalse(changed.compare(original), "and the other way round too");
    }

    /**
     * The copy trap. {@link PgColumn#getCopy()} moves fields one at a time, so a
     * forgotten normalized half is lost in silence and nothing else in the tree
     * notices - a copy would simply start reading as changed against its own
     * original.
     */
    @Test
    void copyingCarriesTheNormalizedDefault() throws Exception {
        PgColumn original = columnOf(createTableWithDefault(TIGHT));
        PgColumn copy = (PgColumn) original.deepCopy();
        // a copy comes out of deepCopy unparented, and a statement's hash covers
        // the names of its parents (AbstractStatement.computeNamesHash)
        copy.setParent(original.getParent());

        assertConverge(copy, original);
        // and the copy must still converge with a differently spelled parse,
        // which a copy that silently dropped the normalized text could not do
        assertConverge(copy, columnOf(createTableWithDefault(SPACED)));
    }

    /**
     * The output side: every script carries the author's own spelling, never the
     * normalized one. The normalized text exists for comparison only; letting it
     * reach the DDL would rewrite user expressions into migrations.
     * <p>
     * All three emitters are asked here, because they are three separate lines:
     * the inline {@code DEFAULT} of a table definition, the {@code SET DEFAULT}
     * a freshly added column gets, and the {@code GENERATED ALWAYS AS} of a
     * generated one.
     */
    @Test
    void theAuthorsOwnSpellingIsWhatReachesTheScript() throws Exception {
        String tableDdl = tableScript(createTableWithDefault(TIGHT));
        assertTrue(tableDdl.contains("DEFAULT " + TIGHT),
                () -> "the author's spelling must reach the table definition, got:\n" + tableDdl);
        assertFalse(tableDdl.contains(NORMALIZED),
                () -> "the normalized form must never reach the script, got:\n" + tableDdl);

        String columnDdl = columnScript(columnOf(createTableWithDefault(TIGHT)));
        assertTrue(columnDdl.contains("SET DEFAULT " + TIGHT),
                () -> "and the same for a column added on its own, got:\n" + columnDdl);
        assertFalse(columnDdl.contains(NORMALIZED),
                () -> "the normalized form must never reach the script, got:\n" + columnDdl);

        String generatedDdl = tableScript(createTableGenerated(GEN_TIGHT));
        assertTrue(generatedDdl.contains("GENERATED ALWAYS AS (" + GEN_TIGHT + ')'),
                () -> "a generation expression is emitted as written too, got:\n" + generatedDdl);
        assertFalse(generatedDdl.contains(GEN_NORMALIZED),
                () -> "the normalized form must never reach the script, got:\n" + generatedDdl);
    }

    /**
     * The reader itself, over a catalog row - the one test here that names
     * {@link PgTablesReader} by running it rather than by mirroring it.
     * <p>
     * The stream the reader hands to the normalizer is reachable from nowhere
     * else, and in a running program replacing it with {@code null} is not a
     * quiet defect but a failed database read: the normalizer dereferences it
     * and the NPE comes out of the finalizer. The quiet variant - a parse
     * written into an object nobody keeps - is equally invisible from a mirrored
     * helper. Both die here: this drives {@code processResult} over one mocked
     * row, finishes the loader's parse queue exactly as a real load does, and
     * compares what came out with a column built by hand.
     */
    @Test
    void theReaderItselfNormalizesTheDefaultItReparses() throws Exception {
        var settings = new CoreSettings();
        PgColumn loaded = read(SPACED, settings);

        assertTrue(settings.getErrors().isEmpty(),
                () -> "the catalog default must parse, otherwise the finalizer never runs: " + settings.getErrors());

        assertConverge(handBuilt(SPACED, NORMALIZED), loaded);
        // and the catalog spelling a project file holds tight must read as one column
        assertConverge(loaded, columnOf(createTableWithDefault(TIGHT)));

        // the raw half survives the reader path too
        String ddl = columnScript(loaded);
        assertTrue(ddl.contains("SET DEFAULT " + SPACED),
                () -> "the catalog's own spelling must reach the script, got:\n" + ddl);
    }

    /**
     * The finalizer trap, guarded rather than trusted. The loader runs an ANTLR
     * finalizer only for a parse that reported no errors
     * ({@code AbstractJdbcLoader.submitAntlrTask}), so the raw half has to be
     * assigned before the task is submitted and unconditionally - as it already
     * was before it grew a normalized sibling. Folding that assignment into the
     * finalizer would cost the column its default entirely, which for a
     * generated column is the whole definition of it.
     */
    @Test
    void aDefaultThatFailsToParseStillReachesTheModel() throws Exception {
        var settings = new CoreSettings();
        PgColumn loaded = read(UNPARSABLE, settings);

        assertFalse(settings.getErrors().isEmpty(),
                "this default is supposed to fail the expression grammar, otherwise the test proves nothing");
        String ddl = columnScript(loaded);
        assertTrue(ddl.contains("SET DEFAULT " + UNPARSABLE),
                () -> "the default must survive a failed parse, got:\n" + ddl);
    }

    /**
     * The sharper half of the same trap: an unreadable default must not be
     * mistaken for no default at all.
     * <p>
     * {@code PgColumn.compare} and {@code computeHash} read only the normalized
     * half, and an empty normalized half is exactly what a column without a
     * {@code DEFAULT} carries. Leaving that half empty on a failed parse
     * therefore makes a column the database holds compare <i>equal</i> to a
     * project file whose column has no default: no node in the diff tree, no
     * line in the script, and a default left standing in the database with
     * nothing in the project answering for it. So the reader fills both halves
     * with the catalog's own text before it submits the parse, and the finalizer
     * overwrites the normalized one when the parse succeeds.
     * <p>
     * {@link Comparison#compare} is asked alongside {@code compare} because it
     * is what the diff tree calls, and it gates on the hash before it looks at
     * anything else.
     */
    @Test
    void anUnreadableDefaultIsNotTheSameAsNoDefaultAtAll() throws Exception {
        var settings = new CoreSettings();
        PgColumn withDefault = read(UNPARSABLE, settings);

        assertFalse(settings.getErrors().isEmpty(),
                "this default is supposed to fail the expression grammar, otherwise the test proves nothing");

        PgColumn withoutDefault = readWithoutDefault(new CoreSettings());
        assertFalse(withDefault.compare(withoutDefault),
                "an unreadable default must not compare equal to no default at all");
        assertFalse(withoutDefault.compare(withDefault), "compare must be symmetric");
        assertFalse(Comparison.compare(new CoreSettings(), withDefault, withoutDefault),
                "and the entry point the diff tree uses must see the difference too");
    }

    private static void assertConverge(PgColumn a, PgColumn b) {
        assertTrue(a.compare(b), "expected the two columns to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged columns must hash the same");
    }

    private static PgDatabase createTableWithDefault(String expression) throws Exception {
        return loadProjectFile("CREATE TABLE %s.%s (%s %s DEFAULT %s);"
                .formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, expression));
    }

    private static PgDatabase createTableGenerated(String expression) throws Exception {
        return loadProjectFile("CREATE TABLE %s.%s (%s %s GENERATED ALWAYS AS (%s) STORED);"
                .formatted(SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, expression));
    }

    /**
     * The project-file side, whole: {@link PgDumpLoader} over the text, so the
     * listener picks the parser class exactly as it does for a file on disk.
     */
    private static PgDatabase loadProjectFile(String sql) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "column default test", new CoreSettings()).load();
    }

    private static PgColumn columnOf(PgDatabase db) {
        return table(db).getColumn(COLUMN_NAME);
    }

    private static PgSimpleTable table(PgDatabase db) {
        return db.getChildren()
                .filter(PgSchema.class::isInstance)
                .flatMap(s -> s.getChildren())
                .filter(PgSimpleTable.class::isInstance)
                .map(PgSimpleTable.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no table was parsed"));
    }

    /**
     * A twin carrying every value the writers set on this column - the type
     * among them, which on the reader side comes from a catalog column and not
     * from the parse. A twin missing one of those fails in a way that looks
     * exactly like a normalization defect.
     */
    private static PgColumn handBuilt(String expression, String normalized) {
        var column = new PgColumn(COLUMN_NAME);
        column.setType(COLUMN_TYPE);
        column.setDefaultValue(expression, normalized);

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);
        table.addColumn(column);
        return column;
    }

    private static String tableScript(PgDatabase db) {
        var settings = new CoreSettings();
        var table = table(db);
        var script = new SQLScript(settings, table.getSeparator());
        table.getCreationSQL(script);
        return script.getFullScript();
    }

    private static String columnScript(PgColumn column) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, column.getSeparator());
        column.getCreationSQL(script);
        return script.getFullScript();
    }

    private static String normalize(String expression) {
        List<Object> errors = new ArrayList<>();
        SQLParser parser = PgParserUtils.createSqlParser(expression, "column default normalizer probe", errors);
        var vex = parser.vex_eof().vex().get(0);
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        return PgParserUtils.normalizeWhitespaceUnquoted(vex, (CommonTokenStream) parser.getTokenStream());
    }

    /**
     * Runs {@link PgTablesReader#processResult} over one mocked catalog row and
     * finishes the loader's parse queue, which runs the deferred finalizer
     * exactly as a real load does. Returns the column the reader hung off the
     * table.
     * <p>
     * The loader's version is left at its default, which is below every version
     * gate in {@code readColumns} and so asks for the fewest catalog columns.
     * The default-expression lines are outside all of those gates, so nothing
     * under test here depends on that choice.
     */
    private static PgColumn read(String columnDefault, CoreSettings settings) throws Exception {
        return read(columnDefault, true, settings);
    }

    /**
     * The same row with {@code col_has_default} false, which is how a column
     * that simply has no default arrives - the reader never looks at
     * {@code col_defaults} then.
     */
    private static PgColumn readWithoutDefault(CoreSettings settings) throws Exception {
        return read(null, false, settings);
    }

    private static PgColumn read(String columnDefault, boolean hasDefault, CoreSettings settings) throws Exception {
        settings.setIgnorePrivileges(true);

        ResultSet res = mock(ResultSet.class);
        when(res.getString("relname")).thenReturn(TABLE_NAME);
        stubArray(res, "col_names", new String[] { COLUMN_NAME });
        stubArray(res, "col_type_ids", new Long[] { SYSTEM_TYPE_OID });
        stubArray(res, "col_type_name", new String[] { COLUMN_TYPE });
        stubArray(res, "col_has_default", new Boolean[] { hasDefault });
        stubArray(res, "col_defaults", new String[] { columnDefault });
        stubArray(res, "col_comments", new String[] { null });
        stubArray(res, "col_notnull", new Boolean[] { Boolean.FALSE });
        // -1 is the "not set" statistics target, which the reader skips
        stubArray(res, "col_statistics", new Integer[] { -1 });
        stubArray(res, "col_local", new Boolean[] { Boolean.TRUE });
        stubArray(res, "col_collation", new Long[] { 0L });
        stubArray(res, "col_options", new String[] { null });
        stubArray(res, "col_foptions", new String[] { null });
        // equal to the type's own storage, so the reader records no override
        stubArray(res, "col_storages", new String[] { TYPE_STORAGE });

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);

        try (TestLoader loader = new TestLoader(settings)) {
            new PgTablesReader(loader).processResult(res, schema);
            loader.drain();
        }

        return schema.getChildren()
                .filter(PgSimpleTable.class::isInstance)
                .map(PgSimpleTable.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reader added no table"))
                .getColumn(COLUMN_NAME);
    }

    private static final long SYSTEM_TYPE_OID = 1700L;
    private static final long LAST_SYS_OID = 10000L;
    private static final String TYPE_STORAGE = "m";

    private static void stubArray(ResultSet res, String column, Object[] values) throws SQLException {
        // the array is built and stubbed before it is handed to the outer
        // stubbing: stubbing a mock while another stubbing is unfinished is what
        // Mockito reports as UnfinishedStubbingException
        Array array = mock(Array.class);
        when(array.getArray()).thenReturn(values);
        when(res.getArray(column)).thenReturn(array);
    }

    /**
     * {@code finishLoaders} is protected on {@code AbstractLoader}, so a subclass
     * reaches it without reflection, and the type cache is normally filled by a
     * query this test never runs, so the one lookup the reader makes is answered
     * here. Nothing queries - the reader is handed its row directly - so the
     * connector exists only to satisfy the constructor.
     */
    private static final class TestLoader extends PgJdbcLoader {

        private TestLoader(CoreSettings settings) {
            super(offlineConnector(), null, settings);
        }

        @Override
        public PgJdbcType getCachedTypeByOid(Long oid) {
            return new PgJdbcType(oid, COLUMN_TYPE, 0L, 1231L, "pg_catalog", null,
                    LAST_SYS_OID, TYPE_STORAGE, 0L);
        }

        private void drain() throws InterruptedException, IOException {
            finishLoaders();
        }
    }

    private static IJdbcConnector offlineConnector() {
        return new IJdbcConnector() {

            @Override
            public Connection getConnection() throws IOException {
                throw new AssertionError("this test must not open a connection");
            }

            @Override
            public String getBatchDelimiter() {
                return null;
            }

            @Override
            public String getUrl() {
                return "jdbc:test";
            }

            @Override
            public String getDbName() {
                return "test";
            }
        };
    }
}
