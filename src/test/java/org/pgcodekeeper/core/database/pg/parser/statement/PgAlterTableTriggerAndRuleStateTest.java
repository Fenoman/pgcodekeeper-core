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
package org.pgcodekeeper.core.database.pg.parser.statement;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractTable;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The two alternatives of {@code table_action} that state the firing state of a
 * child rather than anything about the table:
 * {@code {ENABLE|DISABLE} TRIGGER ALL|USER} ({@code SQLParser.g4:460}) and the
 * bare {@code ENABLE RULE} ({@code SQLParser.g4:462}).
 *
 * <p>
 * Both write into the child - {@code PgTrigger.triggerState} and
 * {@code PgRule.enabledState} - and both already had that path: the named
 * trigger form and the {@code DISABLE}/{@code REPLICA}/{@code ALWAYS} rule
 * forms have used it all along. What was missing was the branch, not the way
 * in.
 *
 * <p>
 * <b>{@code null} is the enabled state, in both models.</b> That is not a
 * choice made here but the one the readers already make:
 * {@code PgTriggersReader.readEnabledState} answers {@code null} for a trigger
 * the catalog reports as {@code 'O'}, and {@code PgRulesReader} simply does not
 * call {@code setEnabledState} for the same value. So a project file has to
 * produce {@code null} too, or every file carrying an {@code ENABLE} reads as
 * changed against the database it was exported from - and both generators write
 * exactly that word for a child whose state is {@code null}
 * ({@code PgTrigger.appendAlterSQL}, {@code PgRule.appendAlterSQL}), so the
 * statement is one this tool produces itself.
 *
 * <p>
 * The one place the default is spelled {@code ENABLE} instead is the inherited
 * map on the table, {@code PgAbstractTable.putTriggerState} - and that too is
 * the reader's own split, the {@code isChild} argument of the very same method.
 */
class PgAlterTableTriggerAndRuleStateTest {

    private static final String SCHEMA = "public";
    private static final String TABLE = "t";

    // -------------------------------------------------- TRIGGER ALL | USER

    /**
     * The defect, stated directly. {@code createTrigger} left at once when the
     * statement named no trigger, so {@code ALL} and {@code USER} reached no
     * writer - and what the model kept was the state the statements before them
     * had set, which is the state the file had just left.
     *
     * <p>
     * Measured before the fix: against a database whose trigger the file had
     * disabled and then enabled again with {@code ALL}, the tool emitted
     * {@code ALTER TABLE public.t ENABLE TRIGGER tg} - and the other way round,
     * a file saying {@code DISABLE TRIGGER ALL} left the model enabled.
     */
    @Test
    void disableTriggerAllReachesEveryTrigger() throws Exception {
        PgDatabase byAll = load(triggers() + """

                ALTER TABLE public.t DISABLE TRIGGER ALL;""");
        assertEquals("ALTER TABLE public.t DISABLE TRIGGER tg1", stateOf(byAll, "tg1"));
        assertEquals("ALTER TABLE public.t DISABLE TRIGGER tg2", stateOf(byAll, "tg2"),
                "ALL names every trigger, not the first one");

        PgDatabase named = load(triggers() + """

                ALTER TABLE public.t DISABLE TRIGGER tg1;
                ALTER TABLE public.t DISABLE TRIGGER tg2;""");
        assertEquals("", pipeline(named, byAll).trim(),
                "ALL must build what naming every trigger builds");
    }

    /**
     * {@code USER} is read the same way, and here that is a statement about
     * this model rather than about the server. PostgreSQL tells the two apart
     * by whether a trigger was generated internally - and no such trigger is
     * ever in this model: {@code PgTriggersReader} filters on
     * {@code res.tgisinternal = FALSE}, so the set {@code USER} would exclude
     * is empty by construction. A constraint trigger the model does carry is
     * one an author wrote with {@code CREATE CONSTRAINT TRIGGER}, which is a
     * user trigger to the server too.
     *
     * <p>
     * The grammar also admits the word-less form ({@code SQLParser.g4:460}
     * spells the choice {@code ?}), and it is read the same way for want of
     * anything else it could mean.
     */
    @Test
    void triggerUserNamesTheSameSetAsAll() throws Exception {
        PgDatabase byUser = load(triggers() + """

                ALTER TABLE public.t DISABLE TRIGGER USER;""");
        PgDatabase byAll = load(triggers() + """

                ALTER TABLE public.t DISABLE TRIGGER ALL;""");
        assertEquals("", pipeline(byAll, byUser).trim(),
                "no trigger in this model is an internally generated one, so the two name one set");
    }

    /**
     * The actions are applied in the order they are written, which is what makes
     * {@code ALL} worth reading at all: the file states a per-trigger state and
     * then sweeps it away, or sweeps first and then states one.
     */
    @Test
    void aLaterAllOverridesAnEarlierNamedStateAndTheOtherWayRound() throws Exception {
        PgDatabase sweptLast = load(triggers() + """

                ALTER TABLE public.t ENABLE ALWAYS TRIGGER tg1;
                ALTER TABLE public.t DISABLE TRIGGER ALL;""");
        assertEquals("ALTER TABLE public.t DISABLE TRIGGER tg1", stateOf(sweptLast, "tg1"),
                "the last statement is the one that stands");

        PgDatabase sweptFirst = load(triggers() + """

                ALTER TABLE public.t DISABLE TRIGGER ALL;
                ALTER TABLE public.t ENABLE ALWAYS TRIGGER tg1;""");
        assertEquals("ALTER TABLE public.t ENABLE ALWAYS TRIGGER tg1", stateOf(sweptFirst, "tg1"));
        assertEquals("ALTER TABLE public.t DISABLE TRIGGER tg2", stateOf(sweptFirst, "tg2"),
                "and the sweep still stands for the trigger the later statement does not name");
    }

    /**
     * The three trigger states that already had a writer must keep it, the same
     * guard the rule gets at the bottom of this class - a branch reading the
     * sweeping form could otherwise be written so that it swallows the named
     * one, which no assertion above would notice.
     *
     * <p>
     * This case also holds coverage that used to live in the corpus. The
     * fixture pair {@code alter_table_trigger} was the only place
     * {@code ENABLE ALWAYS TRIGGER} and {@code ENABLE REPLICA TRIGGER} appeared
     * in a generated script, and its {@code _new.sql} ends with
     * {@code ENABLE TRIGGER ALL} - which, now that the statement is read, sweeps
     * both of them away, so its expected output no longer carries either word.
     * That fixture is what proves the sweep is read at all, since an inert
     * branch reproduces its old output exactly; the two words are asserted here
     * instead.
     */
    @Test
    void theThreeTriggerStatesThatAlreadyHadAWriterStillLand() throws Exception {
        PgDatabase byAlter = load(triggers() + """

                ALTER TABLE public.t ENABLE ALWAYS TRIGGER tg1;
                ALTER TABLE public.t ENABLE REPLICA TRIGGER tg2;""");
        assertEquals("ALTER TABLE public.t ENABLE ALWAYS TRIGGER tg1", stateOf(byAlter, "tg1"));
        assertEquals("ALTER TABLE public.t ENABLE REPLICA TRIGGER tg2", stateOf(byAlter, "tg2"));

        String script = pipeline(load(triggers()), byAlter);
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t ENABLE ALWAYS TRIGGER tg1;

                ALTER TABLE public.t ENABLE REPLICA TRIGGER tg2;""", script.trim(),
                "and both must reach the migration");
    }

    // ------------------------------------------------------- bare ENABLE

    /**
     * A bare {@code ENABLE TRIGGER} is the default state, and the model spells
     * that {@code null}. Read as {@code PgTriggerState.ENABLE} it does not
     * compare equal to what {@code PgTriggersReader} returns for the same
     * trigger, so a project file carrying the statement reads as changed
     * against the database it came from - measured before the fix, the pair
     * emitted {@code ALTER TABLE public.t ENABLE TRIGGER tg1} against a
     * database in exactly that state.
     */
    @Test
    void aBareEnableTriggerIsTheDefaultStateAndNotAStateOfItsOwn() throws Exception {
        PgDatabase byAlter = load(triggers() + """

                ALTER TABLE public.t DISABLE TRIGGER tg1;
                ALTER TABLE public.t ENABLE TRIGGER tg1;""");
        assertNull(stateOf(byAlter, "tg1"),
                "the default state is the one the reader answers with, which is null");

        assertEquals("", pipeline(load(triggers()), byAlter).trim(),
                "so a trigger the file enables must equal one no statement mentions");
    }

    /** And the same word in the sweeping form. */
    @Test
    void aBareEnableTriggerAllIsTheDefaultStateToo() throws Exception {
        PgDatabase byAll = load(triggers() + """

                ALTER TABLE public.t DISABLE TRIGGER ALL;
                ALTER TABLE public.t ENABLE TRIGGER ALL;""");
        assertNull(stateOf(byAll, "tg1"));
        assertNull(stateOf(byAll, "tg2"));
        assertEquals("", pipeline(load(triggers()), byAll).trim(),
                "a swept-then-enabled trigger must equal one no statement mentions");
    }

    /**
     * The one place the default keeps the word {@code ENABLE}: the map of
     * inherited trigger states a partition carries, which has no spelling for
     * {@code null} at all - its values go straight into the emitted statement.
     * The reader makes the same split, through the {@code isChild} argument of
     * {@code readEnabledState}.
     */
    @Test
    void theInheritedStateMapSpellsTheDefaultEnable() throws Exception {
        PgDatabase byAlter = load("""
                CREATE TABLE public.p (c1 integer) PARTITION BY RANGE (c1);
                CREATE TABLE public.t PARTITION OF public.p FOR VALUES FROM (1) TO (10);
                ALTER TABLE public.t ENABLE TRIGGER inherited_tg;""");
        assertTrue(creationSql(byAlter).contains("ALTER TABLE public.t ENABLE TRIGGER inherited_tg"),
                () -> "an inherited state has no null to write, got:\n" + creationSql(byAlter));
    }

    // ------------------------------------------------------- ENABLE RULE

    /**
     * The rule's own bare {@code ENABLE}, which {@code createRule} knew
     * {@code DISABLE}, {@code REPLICA} and {@code ALWAYS} but not the way back.
     *
     * <p>
     * Unread it was not merely dropped: the state the earlier statement had set
     * stayed, so against a database whose rule was enabled the tool emitted
     * {@code ALTER TABLE public.t DISABLE RULE r} - measured, the statement the
     * file had just taken back.
     */
    @Test
    void aBareEnableRuleTakesTheEarlierStateBack() throws Exception {
        PgDatabase byAlter = load(rule() + """

                ALTER TABLE public.t DISABLE RULE r;
                ALTER TABLE public.t ENABLE RULE r;""");
        assertEquals("", pipeline(load(rule()), byAlter).trim(),
                "a rule the file enables again must equal one no statement mentions");
    }

    /** The other direction: against a database whose rule is still disabled. */
    @Test
    void aBareEnableRuleReachesTheDatabase() throws Exception {
        String disabled = rule() + """

                ALTER TABLE public.t DISABLE RULE r;""";
        String script = pipeline(load(disabled), load(disabled + """


                ALTER TABLE public.t ENABLE RULE r;"""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t ENABLE RULE r;""", script.trim());
    }

    /**
     * The three forms that already had a writer must keep it - a branch reading
     * the bare word could otherwise be written so that it swallows the other
     * three, which no assertion above would notice.
     */
    @Test
    void theThreeStatesThatAlreadyHadAWriterStillLand() throws Exception {
        String script = pipeline(load(rule()), load(rule() + """

                ALTER TABLE public.t ENABLE ALWAYS RULE r;"""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t ENABLE ALWAYS RULE r;""", script.trim());
    }

    // ------------------------------------------------------------ fixtures

    private static String triggers() {
        return """
                CREATE TABLE public.t (
                \tc1 integer
                );

                CREATE FUNCTION public.f() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RETURN NEW; END;$$;

                CREATE TRIGGER tg1 BEFORE INSERT ON public.t FOR EACH ROW EXECUTE PROCEDURE public.f();

                CREATE TRIGGER tg2 BEFORE UPDATE ON public.t FOR EACH ROW EXECUTE PROCEDURE public.f();""";
    }

    private static String rule() {
        return """
                CREATE TABLE public.t (
                \tc1 integer
                );

                CREATE RULE r AS ON INSERT TO public.t DO INSTEAD NOTHING;""";
    }

    // -------------------------------------------------------------- helpers

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        PgDatabase db = new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "trigger and rule state test", settings).load();
        assertTrue(settings.getErrors().isEmpty(),
                () -> "the fixture must load clean, got: " + settings.getErrors());
        return db;
    }

    private static PgAbstractTable tableOf(PgDatabase db) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA);
        PgAbstractTable table = schema == null ? null : schema.getTable(TABLE);
        assertNotNull(table, "no table was parsed");
        return table;
    }

    /**
     * The statement the trigger's own {@code CREATE} writes for its state, or
     * {@code null} when it writes none - which is what a state of {@code null}
     * means, and the only way to read the field back, there being no getter for
     * it.
     */
    private static String stateOf(PgDatabase db, String name) {
        var trigger = tableOf(db).getTrigger(name);
        assertNotNull(trigger, "no trigger " + name + " was parsed");
        var script = new org.pgcodekeeper.core.script.SQLScript(new CoreSettings(), "\n");
        trigger.getCreationSQL(script);
        return script.getFullScript().lines()
                .filter(line -> line.startsWith("ALTER TABLE"))
                .map(line -> line.endsWith(";") ? line.substring(0, line.length() - 1) : line)
                .findFirst()
                .orElse(null);
    }

    private static String creationSql(PgDatabase db) {
        var settings = new CoreSettings();
        var script = new org.pgcodekeeper.core.script.SQLScript(settings, "\n");
        tableOf(db).getCreationSQL(script);
        return script.getFullScript();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
