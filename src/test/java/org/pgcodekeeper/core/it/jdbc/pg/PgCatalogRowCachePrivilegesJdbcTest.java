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
package org.pgcodekeeper.core.it.jdbc.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.pgcodekeeper.core.FILES_POSTFIX;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.database.base.parser.ScriptParser;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.jdbc.PgJdbcConnector;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.telemetry.IComparisonTelemetry;
import org.pgcodekeeper.core.telemetry.PgCatalogCacheMode;
import org.pgcodekeeper.core.telemetry.PgCatalogReaderCacheTelemetry;
import org.pgcodekeeper.core.utils.testcontainer.TestContainerType;

/**
 * Proves the persistent catalog row cache against a live PostgreSQL with
 * privileges INCLUDED, the configuration the Eclipse plugin uses by default.
 * <p>
 * Every non-null {@code nspacl} / {@code relacl} / {@code proacl} reaches its
 * reader as an {@code aclitem[]} column that is read as text while pgJDBC
 * hands {@code getObject} a {@link java.sql.Array}, so this is the exact path
 * the cache has to reproduce. Before the array text was captured, a cold
 * cached load simply threw on the first privileged object.
 * <p>
 * Statement <em>order</em> is deliberately compared canonically: a catalog
 * reader query without an explicit ORDER BY has no guaranteed row order, and
 * the warm hash pass may legitimately see the same rows in another order than
 * the plain query did. The privilege statements themselves are compared
 * exactly, which is what this fix is about.
 */
@ResourceLock(AbstractPgGpJdbcLoaderTest.SHARED_PG_TEST_DATABASE)
class PgCatalogRowCachePrivilegesJdbcTest extends AbstractPgGpJdbcLoaderTest {

    private static final String CONTAINER = "PG_16";
    private static final String DUMP_FILE = "pg_16_dump_test" + FILES_POSTFIX.SQL;

    @TempDir
    private Path cacheDirectory;

    @Test
    void creationScriptKeepsEveryPrivilegeOffColdAndWarm() throws Exception {
        String url = TestContainerType.valueOf(CONTAINER).getUrl();
        var provider = new PgDatabaseProvider();
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(false);

        IDatabase startConfDb = loadStartConfDb(provider, url, settings);
        IJdbcConnector connector = new PgJdbcConnector(url);
        IDatabase remoteDb = null;
        try {
            applyDump(connector, settings);

            var offTelemetry = new RecordingTelemetry();
            var coldTelemetry = new RecordingTelemetry();
            var warmTelemetry = new RecordingTelemetry();
            IDatabase off = load(provider, url, null, offTelemetry);
            IDatabase cold = load(provider, url, cacheDirectory, coldTelemetry);
            IDatabase warm = load(provider, url, cacheDirectory, warmTelemetry);
            remoteDb = off;

            IDatabase empty = provider.getDumpLoader(
                    () -> new ByteArrayInputStream(new byte[0]),
                    "empty", settings).loadAndAnalyze();
            String offScript = PgCodeKeeperApi.diff(provider, empty, off,
                    settings);
            String coldScript = PgCodeKeeperApi.diff(provider, empty, cold,
                    settings);
            String warmScript = PgCodeKeeperApi.diff(provider, empty, warm,
                    settings);

            assertFalse(privileges(offScript).isEmpty(),
                    "the privilege-enabled creation script must carry grants");
            assertTrue(offScript.contains("OWNER TO "),
                    "the privilege-enabled creation script must carry owners");
            assertEquals(privileges(offScript), privileges(coldScript),
                    "a cold cached load must keep every privilege statement");
            assertEquals(privileges(offScript), privileges(warmScript),
                    "a warm cached load must keep every privilege statement");
            assertEquals(canonical(offScript), canonical(coldScript),
                    "a cold cached load must produce the same statements");
            assertEquals(canonical(offScript), canonical(warmScript),
                    "a warm cached load must produce the same statements");

            assertTrue(offTelemetry.events.isEmpty(),
                    "the uncached load must not engage the row cache");
            assertFalse(coldTelemetry.events.isEmpty(),
                    "the cold load must engage the row cache");
            assertTrue(coldTelemetry.published() > 0,
                    "an aclitem column must not stop the cold load from"
                            + " publishing packs");
            assertTrue(warmTelemetry.hits() > 0,
                    "the warm load must replay cached rows");
            assertEquals(0L, warmTelemetry.bypassed(),
                    "no reader may fall back to a plain read when warm");
        } finally {
            clearDb(startConfDb, remoteDb, connector, url,
                    new PgDatabaseProvider(), settings);
        }
    }

    /** Every privilege statement of a script, order-independent. */
    private static List<String> privileges(String script) {
        return script.lines()
                .filter(line -> line.startsWith("GRANT ")
                        || line.startsWith("REVOKE ")
                        || line.contains(" OWNER TO "))
                .sorted().toList();
    }

    /** Every script line, order-independent. */
    private static List<String> canonical(String script) {
        return script.lines().sorted().toList();
    }

    private void applyDump(IJdbcConnector connector, CoreSettings settings)
            throws Exception {
        String script = Files.readString(
                TestUtils.getFilePath(DUMP_FILE, getClass()));
        var loader = new PgDumpLoader(
                () -> new ByteArrayInputStream(
                        script.getBytes(StandardCharsets.UTF_8)),
                DUMP_FILE, settings);
        new JdbcRunner(new NullMonitor()).runBatches(connector,
                new ScriptParser(loader, DUMP_FILE, script).batch(), null);
    }

    private static IDatabase load(PgDatabaseProvider provider, String url,
            Path cacheDirectory, IComparisonTelemetry telemetry)
            throws Exception {
        var settings = new CoreSettings();
        settings.setIgnorePrivileges(false);
        settings.setComparisonTelemetry(telemetry);
        if (cacheDirectory != null) {
            settings.setPgCatalogCacheDir(cacheDirectory.toString());
            settings.setPgCatalogCacheRows(true);
        }
        return provider.getJdbcLoader(url, settings).loadAndAnalyze();
    }

    private static final class RecordingTelemetry
            implements IComparisonTelemetry {

        private final List<PgCatalogReaderCacheTelemetry> events =
                new ArrayList<>();

        @Override
        public synchronized void pgCatalogReaderFinished(
                PgCatalogReaderCacheTelemetry event) {
            events.add(event);
        }

        private synchronized long published() {
            return events.stream()
                    .mapToLong(PgCatalogReaderCacheTelemetry::publishedRows)
                    .sum();
        }

        private synchronized long hits() {
            return events.stream()
                    .mapToLong(PgCatalogReaderCacheTelemetry::hits).sum();
        }

        private synchronized long bypassed() {
            return events.stream()
                    .filter(event -> event.mode() == PgCatalogCacheMode.BYPASS)
                    .count();
        }
    }
}
