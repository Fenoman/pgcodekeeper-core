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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The state of a trigger a table inherits is kept in a map of its own,
 * {@code PgAbstractTable.triggerStates}, rather than on a {@link PgTrigger} -
 * the trigger itself belongs to the parent, and a partition holds nothing but
 * the word its own {@code ALTER TABLE ... {ENABLE|DISABLE} TRIGGER} states.
 *
 * <p>
 * That map keys on the bare name, because that is what both writers hand it:
 * the catalog answers {@code tgname} unquoted ({@code PgTriggersReader}), and
 * the lexer takes the quotes off a {@code QuotedIdentifier} at the token level
 * ({@code SQLLexer.g4}, {@code CodeUnitLexer.removeQuotes}), so
 * {@code PgAlterTable} stores an unquoted name too. A name written back as it
 * is stored is therefore an unquoted one - and PostgreSQL folds an unquoted
 * identifier to lower case, so anything with a capital in it, or anything that
 * is a keyword, names a trigger that does not exist - or, for the two words the
 * statement itself spells a sweeping form with, names every trigger there is.
 *
 * <p>
 * Both halves measured on PostgreSQL 17.10, on a partition of a partitioned
 * table whose parent carries the triggers {@code "MyTrig"} and {@code "user"}:
 * {@code ALTER TABLE public.t DISABLE TRIGGER MyTrig} raised
 * {@code триггер "mytrig" для таблицы "t" не существует}, and
 * {@code ... DISABLE TRIGGER user} reported {@code ALTER TABLE} and disabled
 * both of them - a statement about one trigger, silently answered for all.
 *
 * <p>
 * The trigger's own path has always quoted ({@code PgTrigger.addAlterTable}).
 * These cases hold the map's path to the same rule.
 */
class PgTriggerStateQuotingTest {

    /**
     * The create path, {@code appendTriggerStates}: a partition carrying the
     * state of an inherited trigger writes the statement out with every
     * {@code CREATE} of itself.
     */
    @Test
    void theCreatePathQuotesAnInheritedTriggerName() throws Exception {
        String sql = creationSql(partition("""
                ALTER TABLE public.t DISABLE TRIGGER "MyTrig";"""));
        assertTrue(sql.contains("ALTER TABLE public.t DISABLE TRIGGER \"MyTrig\""),
                () -> "an unquoted MyTrig is folded to mytrig by the server, got:\n" + sql);
    }

    /**
     * A name that is a reserved word rather than a mixed-case one, and the
     * worse half of the defect: this one does not fail.
     * <p>
     * {@code USER} is one of the two words the same statement spells the
     * sweeping form with - {@code ALTER TABLE ... DISABLE TRIGGER USER} means
     * every trigger of the table. So a trigger named {@code "user"}, written
     * without its quotes, is not a statement the server rejects; it is a
     * different statement that the server accepts. Measured on PostgreSQL
     * 17.10 against a partition carrying two triggers: the unquoted form
     * disabled both and reported {@code ALTER TABLE}, the quoted form disabled
     * the one it names.
     */
    @Test
    void theCreatePathQuotesATriggerNamedAfterAKeyword() throws Exception {
        String sql = creationSql(partition("""
                ALTER TABLE public.t DISABLE TRIGGER "user";"""));
        assertTrue(sql.contains("ALTER TABLE public.t DISABLE TRIGGER \"user\""),
                () -> "the bare word user names every trigger of the table, not the one called user, got:\n" + sql);
    }

    /**
     * The alter path, {@code compareTriggerStates}: the same statement, reached
     * from a comparison rather than from a {@code CREATE}. Two writers, one
     * statement - so both are held to the rule.
     */
    @Test
    void theAlterPathQuotesAnInheritedTriggerName() throws Exception {
        String script = pipeline(partition(""), partition("""
                ALTER TABLE public.t DISABLE TRIGGER "MyTrig";"""));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER TABLE public.t DISABLE TRIGGER "MyTrig";""", script.trim());
    }

    /**
     * A name needing no quotes must not gain any - the statement a project
     * already carries has to keep its spelling, or every existing expected
     * script drifts.
     */
    @Test
    void aPlainNameIsLeftAlone() throws Exception {
        String sql = creationSql(partition("""
                ALTER TABLE public.t DISABLE TRIGGER plain_tg;"""));
        assertTrue(sql.contains("ALTER TABLE public.t DISABLE TRIGGER plain_tg"),
                () -> "a valid lower-case identifier is written as it stands, got:\n" + sql);
    }

    // ------------------------------------------------------------ fixtures

    /**
     * A partition of a partitioned parent - the one shape whose trigger state
     * lands in the map instead of on a trigger of its own, since the model
     * holds no {@link PgTrigger} for a trigger the parent owns.
     */
    private static String partition(String tail) {
        return """
                CREATE TABLE public.p (
                \tc1 integer
                ) PARTITION BY RANGE (c1);

                CREATE TABLE public.t PARTITION OF public.p FOR VALUES FROM (1) TO (10);

                """ + tail;
    }

    // -------------------------------------------------------------- helpers

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        PgDatabase db = new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "trigger state quoting test", settings).load();
        assertTrue(settings.getErrors().isEmpty(),
                () -> "the fixture must load clean, got: " + settings.getErrors());
        return db;
    }

    private static String creationSql(String sql) throws IOException, InterruptedException {
        PgDatabase db = load(sql);
        PgSchema schema = (PgSchema) db.getSchema("public");
        PgAbstractTable table = schema == null ? null : schema.getTable("t");
        assertNotNull(table, "no partition was parsed");
        var script = new SQLScript(new CoreSettings(), "\n");
        table.getCreationSQL(script);
        return script.getFullScript();
    }

    private static String pipeline(String oldSql, String newSql) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), load(oldSql), load(newSql), new CoreSettings());
    }
}
