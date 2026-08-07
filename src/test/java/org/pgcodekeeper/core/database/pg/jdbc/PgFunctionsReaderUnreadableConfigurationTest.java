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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * What {@link PgFunctionsReader} does with a {@code pg_proc.proconfig} value
 * this grammar cannot read.
 * <p>
 * For the five list-valued GUCs the reader splits the value into its elements
 * with a parse of its own, and the elements are written in one place - the
 * loader's deferred finalizer, which runs only for a value that parsed
 * ({@code AbstractJdbcLoader:377}). The configuration entry itself, however, is
 * added to the model unconditionally before that parse is submitted, and
 * {@code PgAbstractFunction.appendFunctionFullSQL} spells out
 * {@code SET } + the name + {@code TO } + the value with no null check. A value
 * that failed the parse must therefore never reach that writer as a null, or it
 * is spelled out as
 *
 * <pre>SET search_path TO null</pre>
 *
 * a statement the server accepts and which points the setting at a schema
 * called {@code null} - on a {@code SECURITY DEFINER} routine, the one setting
 * that must never be quietly wrong.
 * <p>
 * The value the catalog holds is kept instead. Unlike the rule and the CHECK
 * constraint there is a raw half here: {@code proconfig}
 * carries {@code name=value}, and the value is the whole of what the parse was
 * meant to split. It is carried the way every other GUC on the same method's
 * {@code default} branch is carried - as one string literal - because
 * PostgreSQL applies its own list splitting to that string, so the entry means
 * exactly what the catalog says even when this parser could not take it apart.
 */
class PgFunctionsReaderUnreadableConfigurationTest {

    private static final String SCHEMA_NAME = "public";
    private static final String FUNC_NAME = "probe_fn";

    /**
     * A {@code proconfig} value this grammar does not read. {@code vex_eof}
     * wants an expression after every comma, so a trailing separator leaves it
     * with nothing to read and the task reports an error - which is all the
     * defect needs, whatever produced the value.
     * <p>
     * {@link #theFixtureIsOneThisGrammarCannotRead()} holds the unreadability
     * itself rather than assuming it: the day the grammar starts accepting this
     * shape, that test goes red and says so, instead of this whole class
     * quietly ceasing to test anything.
     */
    private static final String UNREADABLE_VALUE = "public,";

    /**
     * A second unreadable value, differing from the first in what it names. The
     * reverse half of the defect is total: with nothing kept, two functions
     * carrying different unreadable search paths are byte-identical in every
     * field the comparison reads.
     */
    private static final String UNREADABLE_OTHER_VALUE = "admin,";

    /** The same setting, in a spelling the grammar does read. */
    private static final String READABLE_VALUE = "public, pg_temp";

    private static final String PARAM = "search_path";

    @Test
    void theFixtureIsOneThisGrammarCannotRead() throws Exception {
        var settings = new CoreSettings();
        read(UNREADABLE_VALUE, settings);
        assertFalse(settings.getErrors().isEmpty(),
                "this value is supposed to fail the grammar, otherwise this whole class is decorative");

        var otherSettings = new CoreSettings();
        read(UNREADABLE_OTHER_VALUE, otherSettings);
        assertFalse(otherSettings.getErrors().isEmpty(), "and so is the second one");

        var readableSettings = new CoreSettings();
        read(READABLE_VALUE, readableSettings);
        assertTrue(readableSettings.getErrors().isEmpty(),
                () -> "and the counterpart is supposed to parse: " + readableSettings.getErrors());
    }

    /**
     * The output side. Nothing here writes a setting out of a value that was
     * never filled: the script carries what the catalog holds.
     */
    @Test
    void anUnreadableValueReachesTheScriptAsTheCatalogHoldsIt() throws Exception {
        String ddl = creationScript(read(UNREADABLE_VALUE, new CoreSettings()));

        assertFalse(ddl.contains("TO null"),
                () -> "a value that could not be read must never be written out as the word null, got:\n" + ddl);
        assertTrue(ddl.contains("SET " + PARAM + " TO '" + UNREADABLE_VALUE + '\''),
                () -> "the value the catalog holds must reach the script, got:\n" + ddl);
    }

    /**
     * The comparison side, and the sharper half of the same trap. The
     * configuration map is the only field either value reaches, so with both of
     * them left null two functions whose search paths differ carry byte
     * identical models: no node in the diff tree, no line in the script, and a
     * routine that keeps running under the wrong search path.
     */
    @Test
    void twoUnreadableValuesDoNotCompareEqual() throws Exception {
        PgAbstractFunction one = read(UNREADABLE_VALUE, new CoreSettings());
        PgAbstractFunction other = read(UNREADABLE_OTHER_VALUE, new CoreSettings());

        assertFalse(one.compare(other),
                "two functions whose search paths differ must not compare equal because neither value could be read");
        assertFalse(other.compare(one), "compare must be symmetric");
        assertFalse(Comparison.compare(new CoreSettings(), one, other),
                "and the entry point the diff tree uses must see the difference too");
        assertNotEquals(one.hashCode(), other.hashCode(),
                "and the hash the tree consults on its own must differ");
    }

    /**
     * The successful path is untouched: once the parse has split the value, the
     * finalizer overwrites what was kept and the generator writes the elements
     * one by one, exactly as before.
     */
    @Test
    void aReadableValueIsStillWrittenOutOfTheParsedElements() throws Exception {
        String ddl = creationScript(read(READABLE_VALUE, new CoreSettings()));

        assertTrue(ddl.contains("SET " + PARAM + " TO 'public', 'pg_temp'"),
                () -> "the parsed elements must be what reaches the script, got:\n" + ddl);
    }

    /**
     * The copy trap. A copy that lost the kept value is a function that says
     * {@code TO null} again, both in the script and to the comparison.
     */
    @Test
    void copyingCarriesTheKeptValue() throws Exception {
        PgAbstractFunction original = read(UNREADABLE_VALUE, new CoreSettings());
        var copy = (PgAbstractFunction) original.deepCopy();
        // a copy comes out of deepCopy unparented, and a statement's hash covers
        // the names of its parents (AbstractStatement.computeNamesHash)
        copy.setParent(original.getParent());

        assertTrue(copy.compare(original), "a copy must compare equal to what it was copied from");
        assertEquals(copy.hashCode(), original.hashCode(), "and hash the same");
        String ddl = creationScript(copy);
        assertFalse(ddl.contains("TO null"),
                () -> "a copy must not turn an unreadable value into the word null, got:\n" + ddl);
    }

    /**
     * Runs {@link PgFunctionsReader#processResult} over one mocked catalog row
     * and finishes the loader's parse queue, which runs the deferred finalizer
     * exactly as a real load does. Returns the routine the reader hung off the
     * schema.
     * <p>
     * The row is a {@code PROCEDURE} written in a language that is neither
     * {@code SQL} nor {@code PLPGSQL}: that is the shortest row this reader
     * accepts without a type cache and without a body analysis launcher, and
     * neither has anything to do with what is under test here.
     */
    private static PgAbstractFunction read(String configValue, CoreSettings settings) throws Exception {
        settings.setIgnorePrivileges(true);

        // both arrays are built before the row is stubbed: building one inside a
        // when(...) argument leaves Mockito with an unfinished stubbing
        Array noArguments = sqlArray(new Long[0]);
        Array proconfig = sqlArray(new String[]{PARAM + '=' + configValue});

        ResultSet res = mock(ResultSet.class);
        when(res.getString("proname")).thenReturn(FUNC_NAME);
        when(res.getBoolean("proisproc")).thenReturn(true);
        when(res.getString("lang_name")).thenReturn("c");
        when(res.getString("support_func")).thenReturn("-");
        when(res.getString("proparallel")).thenReturn("u");
        when(res.getString("provolatile")).thenReturn("v");
        when(res.getString("prosrc")).thenReturn("probe_body");
        when(res.getArray("argtypes")).thenReturn(noArguments);
        when(res.getArray("proconfig")).thenReturn(proconfig);

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);

        try (TestLoader loader = new TestLoader(settings)) {
            loader.setVersion(PgSupportedVersion.VERSION_17.getVersion());
            new PgFunctionsReader(loader).processResult(res, schema);
            loader.drain();
        }

        return schema.getChildren()
                .filter(PgAbstractFunction.class::isInstance)
                .map(PgAbstractFunction.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reader added no routine"));
    }

    private static Array sqlArray(Object values) throws Exception {
        Array array = mock(Array.class);
        when(array.getArray()).thenReturn(values);
        return array;
    }

    /**
     * {@code finishLoaders} is protected on {@code AbstractLoader}, so a
     * subclass reaches it without reflection. Nothing here queries - the reader
     * is handed its row directly - so the connector exists only to satisfy the
     * constructor.
     */
    private static final class TestLoader extends PgJdbcLoader {

        private TestLoader(CoreSettings settings) {
            super(offlineConnector(), null, settings);
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

    private static String creationScript(PgAbstractFunction function) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, function.getSeparator());
        function.getCreationSQL(script);
        return script.getFullScript();
    }
}
