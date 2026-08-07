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
package org.pgcodekeeper.core.database.pg.loader;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.pg.jdbc.PgCatalogCacheNamespace;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

class PgJdbcSessionSetupTest {

    @Test
    void sessionSetupScriptCombinesThreeSetCommandsInOneRoundTrip() {
        String script = PgJdbcLoader.buildSessionSetupScript("Europe/Moscow");

        assertAll(
                () -> assertEquals("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY; "
                        + "SET search_path TO pg_catalog; "
                        + "SET timezone = 'Europe/Moscow'", script),
                () -> assertTrue(script.startsWith("SET TRANSACTION"), script));
    }

    @Test
    void cacheIdentityReadsTheOutputSettingsOfTheReadOnlySession() {
        String setup = PgJdbcLoader.buildSessionSetupScript("Europe/Moscow");
        String identity = PgCatalogCacheNamespace.IDENTITY_QUERY;

        assertAll(
                () -> assertTrue(setup.startsWith(
                        "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"), setup),
                () -> assertTrue(setup.contains("SET timezone = 'Europe/Moscow'"), setup),
                () -> assertTrue(identity.contains("current_setting('TimeZone')"), identity),
                () -> assertTrue(identity.contains("current_setting('DateStyle')"), identity),
                () -> assertTrue(identity.contains("current_setting('IntervalStyle')"), identity),
                () -> assertTrue(identity.contains("current_setting('extra_float_digits')"),
                        identity),
                () -> assertTrue(identity.contains("current_setting('bytea_output')"), identity),
                () -> assertFalse(identity.toLowerCase().contains("password"), identity));
    }

    @Test
    void serverVersionProbeIsOneQueryWithBothColumns() {
        String sql = PgJdbcLoader.QUERY_CHECK_SERVER_VERSION;

        assertAll(
                () -> assertTrue(sql.contains("version() AS version_string"), sql),
                () -> assertTrue(sql.contains(
                        "CAST (pg_catalog.current_setting('server_version_num') AS INT)"
                                + " AS version_num"), sql),
                () -> assertEquals(0, countOccurrences(sql, ";"), sql));
    }

    @Test
    void postgresVersionStringDoesNotEnableGreenplumMode() throws Exception {
        var loader = versionCheckedLoader("PostgreSQL 16.14 on x86_64-pc-linux-gnu", 160000);

        assertAll(
                () -> assertFalse(loader.isGreenplumDb()),
                () -> assertEquals(160000, loader.getVersion()));
    }

    @Test
    void greenplumVersionStringEnablesGreenplumMode() throws Exception {
        var loader = versionCheckedLoader(
                "PostgreSQL 9.4.26 (Greenplum Database 6.25.3 build commit:xxx)",
                PgSupportedVersion.GP_VERSION_6.getVersion());

        assertAll(
                () -> assertTrue(loader.isGreenplumDb()),
                () -> assertEquals(PgSupportedVersion.GP_VERSION_6.getVersion(),
                        loader.getVersion()));
    }

    @Test
    void unsupportedPostgresVersionFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> versionCheckedLoader("PostgreSQL 13.9", 130000));
    }

    private static ExposedPgJdbcLoader versionCheckedLoader(
            String versionString, int versionNum) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getString("version_string")).thenReturn(versionString);
        when(result.getInt("version_num")).thenReturn(versionNum);
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenReturn(result);
        var loader = new ExposedPgJdbcLoader(new CoreSettings(), statement);
        loader.queryCheckServerVersion(statement);
        return loader;
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int fromIndex = 0;
        while ((fromIndex = text.indexOf(fragment, fromIndex)) >= 0) {
            count++;
            fromIndex += fragment.length();
        }
        return count;
    }

    private static final class ExposedPgJdbcLoader extends PgJdbcLoader {

        private ExposedPgJdbcLoader(ISettings settings, Statement statement) {
            super(mockConnector(), "UTC", settings);
            this.statement = statement;
        }

        private static IJdbcConnector mockConnector() {
            IJdbcConnector connector = mock(IJdbcConnector.class);
            when(connector.getDbName()).thenReturn("test");
            return connector;
        }
    }
}
