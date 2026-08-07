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
import java.sql.Connection;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.schema.EventType;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgRule;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * What {@link PgRulesReader} does with a {@code pg_get_ruledef()} result this
 * grammar cannot read.
 * <p>
 * The rule reaches the schema container unconditionally, while its condition and
 * all of its action commands are written inside the loader's deferred finalizer,
 * which runs only for a definition that parsed
 * ({@code AbstractJdbcLoader:377}). A rule with no commands is spelled
 * {@code DO NOTHING}, so before this class the reader answered an unreadable
 * definition with {@code CREATE RULE ... DO INSTEAD NOTHING} - a statement the
 * server accepts, and one that makes the table swallow in silence every write
 * the rule fires on. The {@code WHERE} went with it, so the surviving rule also
 * fired on rows the real one never touched.
 * <p>
 * The reader keeps the server's own statement instead and the generator emits
 * it verbatim. That is not a second spelling of the rule: on the successful path
 * the generator assembles its statement out of sub-spans of this very string, so
 * the failing path now emits the same text by a shorter route.
 */
class PgRulesReaderUnreadableDefinitionTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "orders";
    private static final String RULE_NAME = "r_block";

    /**
     * A definition whose action body no SQL grammar will ever read. Deliberately
     * impossible rather than merely unsupported today: a fixture built out of a
     * construct this grammar happens to lag on would quietly stop testing
     * anything the day the grammar caught up.
     * {@link #theFixtureIsOneThisGrammarCannotRead()} holds that property
     * instead of assuming it.
     * <p>
     * The {@code WHERE} is spelled out because it is the second thing the old
     * answer lost, and it has to be visibly present in the input to be missed in
     * the output.
     */
    private static final String UNREADABLE = "CREATE RULE " + RULE_NAME + " AS ON UPDATE TO "
            + SCHEMA_NAME + '.' + TABLE_NAME + " WHERE (old.locked) DO INSTEAD (UPDATE "
            + SCHEMA_NAME + '.' + TABLE_NAME + " SET !!! );";

    /**
     * A definition that does parse, and one that really does say
     * {@code DO NOTHING} - the shape an unreadable rule used to be mistaken for.
     */
    private static final String READABLE = "CREATE RULE " + RULE_NAME + " AS ON UPDATE TO "
            + SCHEMA_NAME + '.' + TABLE_NAME + " WHERE (old.locked) DO INSTEAD NOTHING;";

    @Test
    void theFixtureIsOneThisGrammarCannotRead() throws Exception {
        var settings = new CoreSettings();
        read(UNREADABLE, settings);

        assertFalse(settings.getErrors().isEmpty(),
                "this definition is supposed to fail the grammar, otherwise this whole class is decorative");

        var readableSettings = new CoreSettings();
        read(READABLE, readableSettings);
        assertTrue(readableSettings.getErrors().isEmpty(),
                () -> "and the counterpart is supposed to parse: " + readableSettings.getErrors());
    }

    /**
     * The output side. Nothing here reconstructs a statement out of fields that
     * were never filled: the script carries the server's own definition.
     */
    @Test
    void anUnreadableDefinitionReachesTheScriptAsTheServerWroteIt() throws Exception {
        String ddl = creationScript(read(UNREADABLE, new CoreSettings()));

        assertTrue(ddl.contains("WHERE (old.locked)"),
                () -> "the condition the server wrote must reach the script, got:\n" + ddl);
        assertTrue(ddl.contains("DO INSTEAD (UPDATE " + SCHEMA_NAME + '.' + TABLE_NAME + " SET !!! )"),
                () -> "and so must the action body, got:\n" + ddl);
        assertFalse(ddl.contains("DO INSTEAD NOTHING"),
                () -> "a rule whose body could not be read must never be written out as DO NOTHING, got:\n" + ddl);
        assertFalse(ddl.contains(";;"),
                () -> "the server's own terminator and the script's separator must not both land, got:\n" + ddl);
    }

    /**
     * The comparison side, and the sharper half of the same trap: an unreadable
     * rule must not be mistaken for a rule that genuinely does nothing.
     * <p>
     * Every field {@code compare} and {@code computeHash} used to read - the
     * normalized condition, the command list - carries exactly the same value in
     * both cases, so without the kept definition in the hash a database rule that
     * this grammar cannot read compares <i>equal</i> to a project file whose rule
     * really is {@code DO INSTEAD NOTHING}: no node in the diff tree, no line in
     * the script, and a rewrite rule that stays in the database with nothing in
     * the project answering for it.
     */
    @Test
    void anUnreadableRuleIsNotTheSameAsARuleThatDoesNothing() throws Exception {
        PgRule unreadable = read(UNREADABLE, new CoreSettings());

        PgRule doesNothing = new PgRule(RULE_NAME);
        doesNothing.setEvent(EventType.UPDATE);
        doesNothing.setInstead(true);

        assertDiverge(unreadable, parented(doesNothing));
    }

    /**
     * The successful path is untouched: once the model carries the condition and
     * the commands, the generator builds the statement from them and the kept
     * definition is gone. The line break after {@code AS} belongs to the
     * generator alone - the server writes its statement on one line - so a
     * definition that outlived its parse would be visible right there.
     */
    @Test
    void aReadableDefinitionIsStillWrittenOutOfTheModel() throws Exception {
        PgRule loaded = read(READABLE, new CoreSettings());
        String ddl = creationScript(loaded);

        assertTrue(ddl.contains("CREATE RULE " + RULE_NAME + " AS\n    ON UPDATE TO "),
                () -> "the generator's own layout must be what reaches the script, got:\n" + ddl);

        PgRule handBuilt = new PgRule(RULE_NAME);
        handBuilt.setEvent(EventType.UPDATE);
        handBuilt.setInstead(true);
        handBuilt.setCondition("(old.locked)", "(old.locked)");
        assertConverge(parented(handBuilt), loaded);
    }

    /**
     * The copy trap. {@link PgRule#getCopy()} moves fields one at a time, so a
     * forgotten definition is lost in silence - and a copy that lost it is a
     * rule that says {@code DO NOTHING} again, both in the script and to the
     * comparison.
     */
    @Test
    void copyingCarriesTheKeptDefinition() throws Exception {
        PgRule original = read(UNREADABLE, new CoreSettings());
        PgRule copy = (PgRule) original.deepCopy();
        // a copy comes out of deepCopy unparented, and a statement's hash covers
        // the names of its parents (AbstractStatement.computeNamesHash)
        copy.setParent(original.getParent());

        assertConverge(copy, original);
        String ddl = creationScript(copy);
        assertFalse(ddl.contains("DO INSTEAD NOTHING"),
                () -> "a copy must not turn an unreadable rule into a DO NOTHING one, got:\n" + ddl);
    }

    /**
     * Runs {@link PgRulesReader#processResult} over one mocked catalog row and
     * finishes the loader's parse queue, which runs the deferred finalizer
     * exactly as a real load does. Returns the rule the reader hung off the
     * table.
     */
    private static PgRule read(String ruleString, CoreSettings settings) throws Exception {
        ResultSet res = mock(ResultSet.class);
        when(res.getString("relname")).thenReturn(TABLE_NAME);
        when(res.getString("rulename")).thenReturn(RULE_NAME);
        when(res.getString("ev_type")).thenReturn("2");
        when(res.getBoolean("is_instead")).thenReturn(true);
        when(res.getString("ev_enabled")).thenReturn("O");
        when(res.getString("rule_string")).thenReturn(ruleString);

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);

        try (TestLoader loader = new TestLoader(settings)) {
            new PgRulesReader(loader).processResult(res, schema);
            loader.drain();
        }

        return table.getChildren()
                .filter(PgRule.class::isInstance)
                .map(PgRule.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reader added no rule"));
    }

    /**
     * {@code finishLoaders} is protected on {@code AbstractLoader}, so a subclass
     * reaches it without reflection. Nothing here queries - the reader is handed
     * its row directly - so the connector exists only to satisfy the
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

    private static void assertConverge(PgRule a, PgRule b) {
        assertTrue(a.compare(b), "expected the two rules to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged rules must hash the same");
    }

    /**
     * {@link Comparison#compare} is asked alongside {@code compare} because it is
     * what the diff tree calls.
     * <p>
     * The hash is asked separately, and not as a proxy for either of them: the
     * two do not always cover the same fields, and the tree consults the hash on
     * its own - {@code Comparison.compare} of a table ends in
     * {@code hashChildren() == hashChildren()}, and a rule is a table's child.
     * Measured as mutation M3a: with the kept definition dropped from
     * {@code computeHash} alone, every assertion above still held, so the hash
     * has to be named here or that line is untested.
     */
    private static void assertDiverge(PgRule a, PgRule b) {
        assertFalse(a.compare(b), "an unreadable rule must not compare equal to one that really does nothing");
        assertFalse(b.compare(a), "compare must be symmetric");
        assertFalse(Comparison.compare(new CoreSettings(), a, b),
                "and the entry point the diff tree uses must see the difference too");
        assertNotEquals(a.hashCode(), b.hashCode(), "and the hash the tree consults on its own must differ");
    }

    /**
     * Mirrors the reader's own last line: the rule hangs off the table it is
     * declared on. Both sides of a comparison must be parented the same way,
     * because a statement's hash covers the names of everything above it.
     */
    private static PgRule parented(PgRule rule) {
        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);
        table.addChild(rule);
        return rule;
    }

    private static String creationScript(PgRule rule) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, rule.getSeparator());
        rule.getCreationSQL(script);
        return script.getFullScript();
    }
}
