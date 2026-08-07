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
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.database.pg.schema.PgTrigger;
import org.pgcodekeeper.core.database.pg.schema.PgTrigger.TgTypes;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * What {@link PgTriggersReader} does with a {@code pg_get_triggerdef()} result
 * this grammar cannot read.
 * <p>
 * The trigger reaches its container unconditionally, while its {@code WHEN}
 * condition is written inside the loader's deferred finalizer, which runs only
 * for a definition that parsed ({@code AbstractJdbcLoader:377}). The sharp edge
 * is that the reader fetches and parses that definition <i>only</i> when
 * {@code pg_trigger.tgqual} is present - so this whole route exists for the
 * {@code WHEN} clause and for nothing else, and a failed parse costs the trigger
 * exactly the thing the parse was run for. Before this class the reader answered
 * an unreadable definition with a complete, valid {@code CREATE TRIGGER} that
 * had no {@code WHEN} at all: a trigger that fires on every row instead of the
 * few its condition names.
 * <p>
 * The reader keeps the server's own statement instead and the generator emits it
 * verbatim, then appends the two parts that statement does not carry - the
 * enabled state and the comment.
 */
class PgTriggersReaderUnreadableWhenTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "orders";
    private static final String TRIGGER_NAME = "t_guard";
    private static final String FUNCTION_NAME = "f_guard";

    /**
     * {@code tgtype} as the catalog spells {@code BEFORE UPDATE ... FOR EACH
     * ROW}: {@code TRIGGER_TYPE_ROW | TRIGGER_TYPE_BEFORE | TRIGGER_TYPE_UPDATE}
     * from {@code pg_trigger.h}.
     */
    private static final int BEFORE_UPDATE_ROW = 1 | 1 << 1 | 1 << 4;

    /**
     * The condition this grammar does not read. It is the SQL-standard
     * {@code UNIQUE} predicate: PostgreSQL's {@code gram.y} carries the
     * production - {@code UNIQUE opt_unique_null_treatment select_with_parens} -
     * and its action is nothing but {@code ereport(ERROR,
     * ERRCODE_FEATURE_NOT_SUPPORTED, "UNIQUE predicate is not yet
     * implemented")}, unchanged at least as far back as 9.6.
     * <p>
     * That is what makes it the right anchor rather than merely a convenient
     * one: a server cannot store the predicate, so no catalog can hold it and
     * {@code ruleutils.c} can never print it, so it can never join the set of
     * text this grammar is obliged to read - and can never be fixed out from
     * under this class, which is what happened to the {@code IS NORMALIZED}
     * anchor it replaces.
     * <p>
     * Named separately from {@link #UNREADABLE} because the assertions below
     * look for the condition inside the emitted statement, and an anchor spelled
     * out in four places is an anchor that moves in three.
     */
    private static final String UNREADABLE_WHEN = "((UNIQUE (SELECT new.n FROM public.orders)))";

    /**
     * The condition inside the statement the server writes around it.
     * <p>
     * What is given up by not drawing the condition from a live server is stated
     * plainly: everything around it is still the shape
     * {@code pg_get_triggerdef(oid, false)} writes - the doubled parentheses, the
     * lower-cased {@code new}, {@code EXECUTE FUNCTION}, and no terminating
     * semicolon - and that shape is what the reader has to cope with; only the
     * condition inside is one no server would hand over. The reader cannot tell
     * the two apart - it sees a non-empty error list either way.
     * <p>
     * {@link #theFixtureIsOneThisGrammarCannotRead()} holds the unreadability
     * itself rather than assuming it.
     */
    private static final String UNREADABLE = triggerDef(UNREADABLE_WHEN);

    /**
     * A second unreadable statement, differing in what it fires on. The reverse
     * half of the defect is total: with nothing kept, two triggers whose
     * conditions could not be read carry byte-identical values in every field
     * the comparison reads.
     */
    private static final String UNREADABLE_OTHER =
            triggerDef("((UNIQUE NULLS NOT DISTINCT (SELECT new.m FROM public.orders)))");

    /** The same statement with a condition the grammar does read. */
    private static final String READABLE = triggerDef("((old.locked IS DISTINCT FROM new.locked))");

    /** What the readable condition looks like once the parse has halved it. */
    private static final String READABLE_WHEN = "(old.locked IS DISTINCT FROM new.locked)";

    private static String triggerDef(String when) {
        return "CREATE TRIGGER " + TRIGGER_NAME + " BEFORE UPDATE ON " + SCHEMA_NAME + '.' + TABLE_NAME
                + " FOR EACH ROW WHEN " + when + " EXECUTE FUNCTION " + SCHEMA_NAME + '.' + FUNCTION_NAME + "()";
    }

    @Test
    void theFixtureIsOneThisGrammarCannotRead() throws Exception {
        var settings = new CoreSettings();
        read(UNREADABLE, settings);
        assertFalse(settings.getErrors().isEmpty(),
                "this definition is supposed to fail the grammar, otherwise this whole class is decorative");

        var otherSettings = new CoreSettings();
        read(UNREADABLE_OTHER, otherSettings);
        assertFalse(otherSettings.getErrors().isEmpty(), "and so is the second one");

        var readableSettings = new CoreSettings();
        read(READABLE, readableSettings);
        assertTrue(readableSettings.getErrors().isEmpty(),
                () -> "and the counterpart is supposed to parse: " + readableSettings.getErrors());
    }

    /**
     * The output side. Nothing here writes a trigger out of fields that were
     * never filled: the script carries the server's own statement, condition
     * included.
     */
    @Test
    void anUnreadableWhenReachesTheScriptAsTheServerWroteIt() throws Exception {
        String ddl = creationScript(read(UNREADABLE, new CoreSettings()));

        assertTrue(ddl.contains("WHEN " + UNREADABLE_WHEN),
                () -> "the condition it was given must reach the script, got:\n" + ddl);
        assertTrue(ddl.contains("EXECUTE FUNCTION"),
                () -> "and so must the rest of the statement, got:\n" + ddl);
        assertFalse(ddl.contains(";;"),
                () -> "the server's own statement and the script's separator must not both terminate it, got:\n"
                        + ddl);
    }

    /**
     * The comparison side, and the sharper half of the same trap: an unreadable
     * trigger must not be mistaken for an unconditional one - which is exactly
     * what it looks like from every field the comparison reads - nor for another
     * trigger whose condition could not be read either.
     */
    @Test
    void anUnreadableWhenIsNotTheSameAsNoWhenAtAll() throws Exception {
        PgTrigger unreadable = read(UNREADABLE, new CoreSettings());

        assertDiverge(unreadable, read(UNREADABLE_OTHER, new CoreSettings()));
        assertDiverge(unreadable, parented(handBuilt(null, null)));
    }

    /**
     * The successful path is untouched: once the model carries the condition,
     * the generator builds the statement from it and the kept one is gone. The
     * generator's own line breaks are what says so - the server writes its
     * statement on one line, so a definition that outlived its parse would be
     * visible right there.
     */
    @Test
    void aReadableDefinitionIsStillWrittenOutOfTheModel() throws Exception {
        PgTrigger loaded = read(READABLE, new CoreSettings());
        String ddl = creationScript(loaded);

        assertTrue(ddl.contains("CREATE TRIGGER " + TRIGGER_NAME + "\n\tBEFORE UPDATE ON "),
                () -> "the generator's own layout must be what reaches the script, got:\n" + ddl);
        assertTrue(ddl.contains("\n\tWHEN (" + READABLE_WHEN + ')'),
                () -> "and the condition must come out of the model, got:\n" + ddl);

        assertConverge(parented(handBuilt(READABLE_WHEN, READABLE_WHEN)), loaded);
    }

    /**
     * The copy trap. {@link PgTrigger#getCopy()} moves fields one at a time, so
     * a forgotten definition is lost in silence - and a copy that lost it is an
     * unconditional trigger again, both in the script and to the comparison.
     */
    @Test
    void copyingCarriesTheKeptDefinition() throws Exception {
        PgTrigger original = read(UNREADABLE, new CoreSettings());
        var copy = (PgTrigger) original.deepCopy();
        // a copy comes out of deepCopy unparented, and a statement's hash covers
        // the names of its parents (AbstractStatement.computeNamesHash)
        copy.setParent(original.getParent());

        assertConverge(copy, original);
        String ddl = creationScript(copy);
        assertTrue(ddl.contains("WHEN " + UNREADABLE_WHEN),
                () -> "a copy must not turn an unreadable trigger into an unconditional one, got:\n" + ddl);
    }

    /**
     * The two parts that live outside the kept statement.
     * {@code pg_get_triggerdef()} carries neither the enabled state nor the
     * comment - they come from other columns of the same catalog row and are
     * written as statements of their own - so a branch that emits the definition
     * and returns would drop both: a disabled trigger would be recreated
     * enabled, and its comment would vanish.
     */
    @Test
    void theEnabledStateAndTheCommentSurviveTheKeptDefinition() throws Exception {
        String ddl = creationScript(read(UNREADABLE, new CoreSettings(), "D", "guards the normalized name"));

        assertTrue(ddl.contains("WHEN " + UNREADABLE_WHEN),
                () -> "the kept statement must still be the one written, got:\n" + ddl);
        assertTrue(ddl.contains("DISABLE TRIGGER " + TRIGGER_NAME),
                () -> "a disabled trigger must not be recreated enabled, got:\n" + ddl);
        assertTrue(ddl.contains("COMMENT ON TRIGGER"),
                () -> "and its comment must be written too, got:\n" + ddl);
    }

    // ------------------------------------------------------------------
    // harness
    // ------------------------------------------------------------------

    private static PgTrigger read(String definition, CoreSettings settings) throws Exception {
        return read(definition, settings, "O", null);
    }

    /**
     * Runs {@link PgTriggersReader#processResult} over one mocked catalog row and
     * finishes the loader's parse queue, which runs the deferred finalizer
     * exactly as a real load does. Returns the trigger the reader hung off the
     * table.
     */
    private static PgTrigger read(String definition, CoreSettings settings, String enabled, String comment)
            throws Exception {
        ResultSet res = mock(ResultSet.class);
        when(res.getString("relname")).thenReturn(TABLE_NAME);
        when(res.getString("tgname")).thenReturn(TRIGGER_NAME);
        when(res.getString("tgenabled")).thenReturn(enabled);
        when(res.getInt("tgtype")).thenReturn(BEFORE_UPDATE_ROW);
        when(res.getString("proname")).thenReturn(FUNCTION_NAME);
        when(res.getString("nspname")).thenReturn(SCHEMA_NAME);
        when(res.getBytes("tgargs")).thenReturn(new byte[0]);
        when(res.getBoolean("has_when")).thenReturn(true);
        when(res.getString("definition")).thenReturn(definition);
        when(res.getString("description")).thenReturn(comment);
        // tgconstraint stays 0 and cols stays null, which is a plain trigger on
        // no particular columns

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);

        try (TestLoader loader = new TestLoader(settings)) {
            new PgTriggersReader(loader).processResult(res, schema);
            loader.drain();
        }

        return table.getChildren()
                .filter(PgTrigger.class::isInstance)
                .map(PgTrigger.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reader added no trigger"));
    }

    /**
     * A twin carrying every value the reader sets from catalog columns rather
     * than from the parse - the firing conditions and the function call among
     * them. A twin missing one of those fails in a way that looks exactly like a
     * lost condition while being none.
     */
    private static PgTrigger handBuilt(String when, String whenNormalized) {
        var trigger = new PgTrigger(TRIGGER_NAME);
        trigger.setType(TgTypes.BEFORE);
        trigger.setOnUpdate(true);
        trigger.setForEachRow(true);
        trigger.setFunction(SCHEMA_NAME + '.' + FUNCTION_NAME + "()");
        trigger.setWhen(when, whenNormalized);
        return trigger;
    }

    /**
     * {@code finishLoaders} is protected on {@code AbstractLoader}, so a subclass
     * reaches it without reflection. Nothing here queries - the reader is handed
     * its row directly - so the connector exists only to satisfy the constructor.
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

    private static void assertConverge(PgTrigger a, PgTrigger b) {
        assertTrue(a.compare(b), "expected the two triggers to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged triggers must hash the same");
    }

    /**
     * {@link Comparison#compare} is asked alongside {@code compare} because it is
     * what the diff tree calls.
     * <p>
     * The hash is asked separately, and not as a proxy for either of them: the
     * two do not always cover the same fields, and the tree consults the hash on
     * its own - {@code Comparison.compare} of a table ends in
     * {@code hashChildren() == hashChildren()}, and a trigger is a table's
     * child. Without that line a mutation that drops the kept definition from
     * {@code computeHash} alone leaves every other assertion here holding.
     */
    private static void assertDiverge(PgTrigger a, PgTrigger b) {
        assertFalse(a.compare(b), "an unreadable trigger must not compare equal to a different one");
        assertFalse(b.compare(a), "compare must be symmetric");
        assertFalse(Comparison.compare(new CoreSettings(), a, b),
                "and the entry point the diff tree uses must see the difference too");
        assertNotEquals(a.hashCode(), b.hashCode(), "and the hash the tree consults on its own must differ");
    }

    /**
     * Mirrors the reader's own last line: the trigger hangs off the table it is
     * declared on. Both sides of a comparison must be parented the same way,
     * because a statement's hash covers the names of everything above it.
     */
    private static PgTrigger parented(PgTrigger trigger) {
        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);
        table.addChild(trigger);
        return trigger;
    }

    private static String creationScript(PgTrigger trigger) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, trigger.getSeparator());
        trigger.getCreationSQL(script);
        return script.getFullScript();
    }
}
