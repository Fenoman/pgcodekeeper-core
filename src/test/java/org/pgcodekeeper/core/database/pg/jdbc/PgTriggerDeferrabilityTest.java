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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.database.pg.schema.PgTrigger;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * A {@code CREATE CONSTRAINT TRIGGER} takes its deferrability from either word,
 * exactly as a table constraint does, and the model has to apply that implication
 * because PostgreSQL applies it before anything reaches {@code pg_trigger}.
 * <p>
 * Measured on 17.10 over {@code pg_trigger} and {@code pg_get_triggerdef},
 * one constraint trigger per spelling:
 *
 * <pre>
 * written                             tgdeferrable/tginitdeferred  rendered back
 * (neither word)                      f / f                        NOT DEFERRABLE INITIALLY IMMEDIATE
 * NOT DEFERRABLE                      f / f                        NOT DEFERRABLE INITIALLY IMMEDIATE
 * INITIALLY IMMEDIATE                 f / f                        NOT DEFERRABLE INITIALLY IMMEDIATE
 * DEFERRABLE                          t / f                        DEFERRABLE INITIALLY IMMEDIATE
 * INITIALLY DEFERRED                  t / t                        DEFERRABLE INITIALLY DEFERRED
 * DEFERRABLE INITIALLY IMMEDIATE      t / f                        DEFERRABLE INITIALLY IMMEDIATE
 * DEFERRABLE INITIALLY DEFERRED       t / t                        DEFERRABLE INITIALLY DEFERRED
 * NOT DEFERRABLE INITIALLY DEFERRED   refused - constraint declared INITIALLY DEFERRED must be DEFERRABLE
 * </pre>
 *
 * So the server holds three states and not four, and {@link PgTrigger}'s
 * tri-state {@code Boolean} is a total encoding of exactly those three: null is
 * not deferrable, {@code TRUE} is deferrable and initially immediate,
 * {@code FALSE} is deferrable and initially deferred. The illegal fourth cannot
 * be represented, which is why this object needs no answer for it while the
 * constraint's pair of booleans did.
 * <p>
 * Two spellings reached that field wrongly before this change, both measured:
 * {@code INITIALLY DEFERRED} and a lone {@code DEFERRABLE} each left it null and
 * were written back out as {@code NOT DEFERRABLE INITIALLY IMMEDIATE}, while
 * {@link PgTriggersReader} - reading the very catalog columns above - built
 * {@code FALSE} and {@code TRUE}. The trigger read as changed on every run and no
 * run could change it.
 * <p>
 * The database side is driven by running {@link PgTriggersReader} itself over a
 * mocked catalog row rather than by a helper repeating what the reader does: the
 * reader's own reading of {@code tgdeferrable}/{@code tginitdeferred} is part of
 * what is under test.
 */
class PgTriggerDeferrabilityTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "t";
    private static final String TRIGGER_NAME = "tg";
    private static final String FUNCTION = "public.f()";

    /** {@code AFTER INSERT ... FOR EACH ROW}: TRIGGER_TYPE_ROW | TRIGGER_TYPE_INSERT. */
    private static final int TGTYPE_AFTER_INSERT_ROW = 1 | 4;

    /** The short spelling, which is the whole point: a file may write only this. */
    private static final String SHORT = "INITIALLY DEFERRED";

    /** And the long one, which is what the same statement means. */
    private static final String SPELLED_OUT = "DEFERRABLE INITIALLY DEFERRED";

    // ------------------------------------------------------------ both sides

    /**
     * The defect, stated as the two sides it parts. A project file writing
     * {@code INITIALLY DEFERRED} and the catalog row that statement produces have
     * to be one object.
     */
    @Test
    void theCatalogsDeferredTriggerAndTheFilesShortSpellingAreOneTrigger() throws Exception {
        assertConverge(readFromCatalog(true, true), triggerOf(constraintTrigger(SHORT)));
    }

    /**
     * The second spelling, and a different half of the field: a lone
     * {@code DEFERRABLE} is {@code t / f} in the catalog, so the model has to say
     * deferrable and initially immediate rather than nothing at all.
     */
    @Test
    void theCatalogsDeferrableTriggerAndTheFilesLoneDeferrableAreOneTrigger() throws Exception {
        assertConverge(readFromCatalog(true, false), triggerOf(constraintTrigger("DEFERRABLE")));
    }

    /**
     * And the state that really is null, so the implication is not bought by
     * making the field say the same thing everywhere.
     */
    @Test
    void theCatalogsPlainTriggerAndAFileNamingNoClauseAreOneTrigger() throws Exception {
        assertConverge(readFromCatalog(false, false), triggerOf(constraintTrigger("")));
    }

    /**
     * The same pair through the whole pipeline, because an object comparison and
     * a generated script are two different questions: either one can hold while
     * the other does not.
     */
    @Test
    void theTwoSpellingsOfOneTriggerProduceNoMigration() throws Exception {
        String script = pipeline(constraintTrigger(SPELLED_OUT), constraintTrigger(SHORT));
        assertEquals("", script.trim(),
                () -> "the two spellings of one trigger must produce no migration, got:\n" + script);
    }

    // ----------------------------------------------------------- the bounds

    /**
     * The implication fires where the server applies it and nowhere else, which a
     * formula reading either clause alone would get wrong. Every case is measured
     * on 17.10 and observed here through the statement the tool would send,
     * because the field has no getter and the emitted {@code CREATE} is what it is
     * ultimately for.
     */
    @Test
    void theImplicationFiresOnlyWhereTheServerAppliesIt() throws Exception {
        // f / f
        assertEmitted("", "NOT DEFERRABLE INITIALLY IMMEDIATE");
        assertEmitted("NOT DEFERRABLE", "NOT DEFERRABLE INITIALLY IMMEDIATE");
        assertEmitted("INITIALLY IMMEDIATE", "NOT DEFERRABLE INITIALLY IMMEDIATE");
        // t / f
        assertEmitted("DEFERRABLE", "DEFERRABLE INITIALLY IMMEDIATE");
        assertEmitted("DEFERRABLE INITIALLY IMMEDIATE", "DEFERRABLE INITIALLY IMMEDIATE");
        // t / t, the two cases this class exists for
        assertEmitted(SHORT, SPELLED_OUT);
        assertEmitted(SPELLED_OUT, SPELLED_OUT);
    }

    /**
     * A trigger that really is deferred must still read as different from one
     * that is not, or the implication would have been bought by making the field
     * say nothing.
     */
    @Test
    void aGenuineDifferenceInDeferrabilityStillReadsAsChanged() throws Exception {
        PgTrigger deferred = triggerOf(constraintTrigger(SHORT));
        PgTrigger immediate = triggerOf(constraintTrigger(""));

        assertFalse(deferred.compare(immediate), "a deferred trigger is not an immediate one");
        assertFalse(immediate.compare(deferred), "compare must be symmetric");
    }

    // -------------------------------------------------------------- fixtures

    private static PgDatabase constraintTrigger(String clause) throws Exception {
        return load("""
                CREATE TABLE %s.%s (
                \ta integer
                );

                CREATE CONSTRAINT TRIGGER %s
                \tAFTER INSERT ON %s.%s%s
                \tFOR EACH ROW
                \tEXECUTE PROCEDURE %s;"""
                .formatted(SCHEMA_NAME, TABLE_NAME, TRIGGER_NAME, SCHEMA_NAME, TABLE_NAME,
                        clause.isEmpty() ? "" : "\n\t" + clause, FUNCTION));
    }

    // --------------------------------------------------------------- helpers

    private static void assertConverge(PgTrigger a, PgTrigger b) {
        assertTrue(a.compare(b), "expected the two triggers to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged triggers must hash the same");
    }

    /** The clause a file writes, and the clause the tool writes back out of it. */
    private static void assertEmitted(String written, String expected) throws Exception {
        String sql = triggerScript(triggerOf(constraintTrigger(written)));
        assertEquals("""
                CREATE CONSTRAINT TRIGGER %s
                \tAFTER INSERT ON %s.%s
                \t%s
                \tFOR EACH ROW
                \tEXECUTE PROCEDURE %s;"""
                .formatted(TRIGGER_NAME, SCHEMA_NAME, TABLE_NAME, expected, FUNCTION), sql.trim(),
                () -> "written as '" + written + '\'');
    }

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        PgDatabase db = new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "trigger deferrability test", settings).load();
        assertTrue(settings.getErrors().isEmpty(),
                () -> "the fixture must load clean, got: " + settings.getErrors());
        return db;
    }

    private static PgTrigger triggerOf(PgDatabase db) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA_NAME);
        assertNotNull(schema, "no schema was parsed");
        var table = schema.getTable(TABLE_NAME);
        assertNotNull(table, "no table was parsed");
        PgTrigger trigger = (PgTrigger) table.getTrigger(TRIGGER_NAME);
        assertNotNull(trigger, "no trigger was parsed");
        return trigger;
    }

    private static String triggerScript(PgTrigger trigger) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, trigger.getSeparator());
        trigger.getCreationSQL(script);
        return script.getFullScript();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }

    /**
     * The database side, run rather than mirrored: one mocked {@code pg_trigger}
     * row through {@link PgTriggersReader#processResult}. The two booleans this
     * class is about are catalog columns, so nothing here is deferred to the
     * ANTLR queue - the row carries no {@code WHEN} clause, which is the only
     * part of a trigger that is re-parsed.
     */
    private static PgTrigger readFromCatalog(boolean deferrable, boolean initDeferred) throws Exception {
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(true);

        ResultSet res = mock(ResultSet.class);
        when(res.getString("relname")).thenReturn(TABLE_NAME);
        when(res.getString("tgname")).thenReturn(TRIGGER_NAME);
        when(res.getString("tgenabled")).thenReturn("O");
        when(res.getString("tgparentid")).thenReturn("0");
        when(res.getInt("tgtype")).thenReturn(TGTYPE_AFTER_INSERT_ROW);
        when(res.getString("proname")).thenReturn("f");
        when(res.getString("nspname")).thenReturn(SCHEMA_NAME);
        when(res.getBytes("tgargs")).thenReturn(new byte[0]);
        when(res.getLong("tgconstraint")).thenReturn(1L);
        when(res.getBoolean("tgdeferrable")).thenReturn(deferrable);
        when(res.getBoolean("tginitdeferred")).thenReturn(initDeferred);

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);

        try (TestLoader loader = new TestLoader(settings)) {
            new PgTriggersReader(loader).processResult(res, schema);
        }

        PgTrigger trigger = (PgTrigger) table.getTrigger(TRIGGER_NAME);
        assertNotNull(trigger, "the reader added no trigger");
        return trigger;
    }

    /**
     * Nothing queries - the reader is handed its row directly - so the connector
     * exists only to satisfy the constructor. The version is a real one because
     * the reader branches on it twice.
     */
    private static final class TestLoader extends PgJdbcLoader {

        private TestLoader(CoreSettings settings) {
            super(offlineConnector(), null, settings);
            setVersion(170010);
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
