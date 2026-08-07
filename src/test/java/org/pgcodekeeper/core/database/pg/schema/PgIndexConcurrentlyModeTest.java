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
import org.pgcodekeeper.core.database.api.schema.ObjectState;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * What {@code --concurrently-mode} does to an index that has to be rebuilt.
 *
 * <p>
 * The setting means one thing and one thing only, the thing its help text
 * states: the {@code CREATE INDEX} of the migration is written with
 * {@code CONCURRENTLY}, so the build takes no write lock on the table. The
 * index is still dropped first, because a rebuild is a {@code DROP} and a
 * {@code CREATE} like any other.
 *
 * <p>
 * It used to look as though it meant something more. {@code appendAlterSQL}
 * carried a branch writing the sequence a careful operator would write by hand -
 * build a second index under a temporary name, then swap the two inside a
 * transaction - and then returned {@link ObjectState#RECREATE} all the same. A
 * {@code RECREATE} is answered with a {@code DROP} action and a {@code CREATE}
 * action; the script the branch had written was the resolver's own scratch
 * buffer, kept only for {@code ALTER} states ({@code DepcyResolver.getObjectState})
 * and dropped on the floor for this one. So the sequence never reached a
 * migration, and no test noticed, there being none for the setting at all.
 *
 * <p>
 * Nor would it have worked had it reached one. The second index is created
 * through the same writer as any other, which writes the
 * {@code ALTER TABLE ... CLUSTER ON} and the {@code ATTACH PARTITION} of the
 * index under its <em>real</em> name - the name the old index still carries at
 * that point in the script. Measured: for a clustered index the branch built
 * {@code CREATE INDEX CONCURRENTLY "tmp..._i1"; ALTER TABLE public.t CLUSTER ON
 * i1; BEGIN; DROP INDEX public.i1; ALTER INDEX public."tmp..._i1" RENAME TO i1;
 * COMMIT}, and run verbatim on PostgreSQL 17.10 that sequence left
 * {@code indisclustered} false - the {@code CLUSTER} the project states, lost.
 * The temporary name came from {@code Utils.getRandom()} besides, so the same
 * pair of databases compared to a different script on every run.
 *
 * <p>
 * These cases hold the setting to what it does. The first is the general rule
 * the branch broke; the second and third are the script itself.
 */
class PgIndexConcurrentlyModeTest {

    /**
     * The rule, stated where it can be measured: an object answering
     * {@code RECREATE} has written nothing into the script it was handed.
     *
     * <p>
     * It cannot have, because nobody reads it. {@code DepcyResolver} keeps the
     * script of an {@code ALTER} and an {@code ALTER_WITH_DEP} and no other,
     * and the one fallback that rebuilds it ({@code ActionsToScriptConverter},
     * the {@code ALTER} case) throws away what it built for the same reason.
     * Anything written under a {@code RECREATE} is therefore lost, and a
     * statement that is lost is worse than one never written: it reads, to
     * anyone changing this code, as a statement the migration carries.
     */
    @Test
    void aRecreateWritesNothingIntoTheScriptItWasHanded() throws Exception {
        var settings = new CoreSettings();
        settings.setConcurrentlyMode(true);

        PgIndex oldIndex = indexOf(load(index("a")));
        PgIndex newIndex = indexOf(load(index("b")));

        var script = new SQLScript(settings, oldIndex.getSeparator());
        ObjectState state = oldIndex.appendAlterSQL(newIndex, script);

        assertEquals(ObjectState.RECREATE, state, "a changed index key is not alterable");
        assertEquals("", script.getFullScript().trim(),
                "the resolver keeps this script only for ALTER states, so whatever is written here is lost");
    }

    /**
     * And the script the setting does produce, in full - the pair the branch
     * claimed to replace.
     */
    @Test
    void aRebuiltIndexIsDroppedAndBuiltAgainConcurrently() throws Exception {
        assertEquals("""
                SET search_path = pg_catalog;

                DROP INDEX public.i1;

                CREATE INDEX CONCURRENTLY i1 ON public.t USING btree (b);""",
                pipeline(true).trim());
    }

    /** The same pair without the setting, so that the one difference is visible. */
    @Test
    void theSettingChangesNothingButTheWordConcurrently() throws Exception {
        assertEquals("""
                SET search_path = pg_catalog;

                DROP INDEX public.i1;

                CREATE INDEX i1 ON public.t USING btree (b);""",
                pipeline(false).trim());
    }

    /**
     * The setting is not a licence to leave the table without an index for the
     * length of the build - it is what it says. Stated as an assertion so that
     * a future attempt to write the swap sequence has to take this case with
     * it, rather than leaving a second dead branch behind.
     */
    @Test
    void noTemporaryIndexIsBuiltUnderAnyName() throws Exception {
        String script = pipeline(true);
        assertTrue(!script.contains("RENAME TO") && !script.contains("BEGIN TRANSACTION"),
                () -> "the swap sequence is not what this setting does, got:\n" + script);
    }

    // ------------------------------------------------------------ fixtures

    private static String index(String column) {
        return """
                CREATE TABLE public.t (
                \ta integer,
                \tb integer
                );

                CREATE INDEX i1 ON public.t USING btree (%s);""".formatted(column);
    }

    // -------------------------------------------------------------- helpers

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        PgDatabase db = new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "concurrently mode test", settings).load();
        assertTrue(settings.getErrors().isEmpty(),
                () -> "the fixture must load clean, got: " + settings.getErrors());
        return db;
    }

    private static PgIndex indexOf(PgDatabase db) {
        PgSchema schema = (PgSchema) db.getSchema("public");
        PgIndex index = schema == null ? null : schema.getIndexByName("i1");
        assertNotNull(index, "no index was parsed");
        return index;
    }

    private static String pipeline(boolean concurrently) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        settings.setConcurrentlyMode(concurrently);
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), load(index("a")), load(index("b")), settings);
    }
}
