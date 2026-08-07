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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.InputStreamProvider;

/**
 * {@code CREATE INDEX ... ON ONLY <partitioned parent>} is half of a protocol:
 * the index stays invalid until an index on every partition has been attached to
 * it. A project that keeps its partitions writes the other half and the protocol
 * completes. A project that does not - because partitions are created on the
 * target database, not in the project - leaves an index that no plan will ever
 * use. The word is therefore written only where the attaches follow it.
 */
class PartitionedIndexOnlyTest {

    private static final String NOTHING = "";

    private static final String PARENT_ONLY = """
            CREATE TABLE public.p (id integer, d date) PARTITION BY RANGE (d);
            CREATE INDEX p_id_idx ON ONLY public.p USING btree (id);
            """;

    private static final String PARENT_WITH_ATTACHED_PARTITION = """
            CREATE TABLE public.p (id integer, d date) PARTITION BY RANGE (d);
            CREATE INDEX p_id_idx ON ONLY public.p USING btree (id);
            CREATE TABLE public.p_2026 PARTITION OF public.p
                FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
            CREATE INDEX p_2026_id_idx ON public.p_2026 USING btree (id);
            ALTER INDEX public.p_id_idx ATTACH PARTITION public.p_2026_id_idx;
            """;

    private static final String PARENT_WITH_BARE_PARTITION = """
            CREATE TABLE public.p (id integer, d date) PARTITION BY RANGE (d);
            CREATE INDEX p_id_idx ON ONLY public.p USING btree (id);
            CREATE TABLE public.p_2026 PARTITION OF public.p
                FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
            """;

    @Test
    void keepsOnlyWhenAPartitionIndexAttaches() throws Exception {
        String sql = script(NOTHING, PARENT_WITH_ATTACHED_PARTITION);
        assertTrue(sql.contains("CREATE INDEX p_id_idx ON ONLY public.p"), sql);
        assertTrue(sql.contains("ALTER INDEX public.p_id_idx ATTACH PARTITION public.p_2026_id_idx"), sql);
    }

    @Test
    void dropsOnlyWhenNoPartitionIsInTheProject() throws Exception {
        String sql = script(NOTHING, PARENT_ONLY);
        assertFalse(sql.contains("ON ONLY"), sql);
        assertTrue(sql.contains("CREATE INDEX p_id_idx ON public.p"), sql);
    }

    /**
     * Partitions present but carrying no index of their own: nothing will ever
     * attach, so ONLY would leave the parent index invalid just the same.
     */
    @Test
    void dropsOnlyWhenPartitionsCarryNoIndex() throws Exception {
        assertFalse(script(NOTHING, PARENT_WITH_BARE_PARTITION).contains("ON ONLY"));
    }

    private String script(String oldSql, String newSql) throws IOException, InterruptedException {
        return script(oldSql, newSql, new CoreSettings());
    }

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    private String script(String oldSql, String newSql, CoreSettings settings)
            throws IOException, InterruptedException {
        settings.clearErrors();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> provider.getDumpLoader(source(oldSql), "old", sideSettings),
                        sideSettings -> provider.getDumpLoader(source(newSql), "new", sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings);
    }

    private static InputStreamProvider source(String sql) {
        return () -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8));
    }
}
