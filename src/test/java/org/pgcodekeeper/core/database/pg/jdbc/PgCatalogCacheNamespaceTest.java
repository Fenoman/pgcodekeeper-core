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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class PgCatalogCacheNamespaceTest {

    private static final String EXPECTED_IDENTITY_QUERY =
            "SELECT COALESCE(pg_catalog.inet_server_addr()::text, 'local') AS server_address,\n"
            + "       COALESCE(pg_catalog.inet_server_port(), -1) AS server_port,\n"
            + "       pg_catalog.current_database() AS database_name,\n"
            + "       (SELECT d.oid::text FROM pg_catalog.pg_database d WHERE d.datname = pg_catalog.current_database()) AS database_oid,\n"
            + "       ((pg_catalog.pg_control_system()).system_identifier)::text AS system_identifier,\n"
            + "       session_user AS session_user_name,\n"
            + "       current_user AS current_role_name,\n"
            + "       pg_catalog.current_setting('server_version_num') AS server_version_num,\n"
            + "       pg_catalog.current_setting('TimeZone') AS timezone,\n"
            + "       pg_catalog.current_setting('DateStyle') AS date_style,\n"
            + "       pg_catalog.current_setting('IntervalStyle') AS interval_style,\n"
            + "       pg_catalog.current_setting('extra_float_digits') AS extra_float_digits,\n"
            + "       pg_catalog.current_setting('bytea_output') AS bytea_output,\n"
            + "       pg_catalog.current_setting('quote_all_identifiers') AS quote_all_identifiers,\n"
            + "       NULL::text AS snapshot_token";

    private static final String[] COLUMN_NAMES = {
            "server_address", "server_port", "database_name", "database_oid",
            "system_identifier", "session_user_name", "current_role_name",
            "server_version_num", "timezone", "date_style", "interval_style",
            "extra_float_digits", "bytea_output", "quote_all_identifiers",
            "snapshot_token"
    };

    private static final String[] BASE_VALUES = {
            "192.0.2.10", "5432", "application", "16384", "7504815387372040237",
            "reader_user", "reader_role", "170009", "Asia/Yekaterinburg",
            "ISO, DMY", "postgres", "1", "hex", "off", "100:200:"
    };

    private static final String[] ALTERNATE_VALUES = {
            "192.0.2.11", "5433", "application_test", "32768", "7504815803417604140",
            "other_user", "other_role", "180003", "UTC", "SQL, MDY",
            "sql_standard", "0", "escape", "on", "201:300:250"
    };

    @Test
    void sameIdentityProducesStableTargetDirectory() throws Exception {
        PgCatalogCacheNamespace first = namespace(PgCatalogCacheNamespace.CACHE_ABI,
                BASE_VALUES);
        PgCatalogCacheNamespace second = namespace(PgCatalogCacheNamespace.CACHE_ABI,
                BASE_VALUES.clone());
        ResultSet result = resultSet(BASE_VALUES);
        PgCatalogCacheNamespace fromResult = PgCatalogCacheNamespace.fromResultSet(result);
        Path base = Path.of("cache-base");

        assertEquals(first.resolveUnder(base), second.resolveUnder(base));
        assertEquals(first.resolveUnder(base), fromResult.resolveUnder(base));
        for (String column : COLUMN_NAMES) {
            verify(result).getString(column);
        }
        verifyNoMoreInteractions(result);
    }

    @Test
    void hashAbiMatchesGoldenVector() {
        String[] unicodeValues = BASE_VALUES.clone();
        unicodeValues[2] = "тестовая_база";
        PgCatalogCacheNamespace namespace = namespace(
                PgCatalogCacheNamespace.CACHE_ABI, unicodeValues);

        assertEquals(
                "target-v2-80a7732d774eecf29bef233a06a12899316ca305f16145315ba319a453a25ee7",
                namespace.resolveUnder(Path.of("cache")).getFileName().toString());
        assertEquals(
                "n02329f0f8ca86f800b44481a85e708af7e383b7f36ada199bfdbb33b6a710c27",
                namespace.readerQualifier(
                        "Читатель", "SELECT 'ёж'", (byte) 0xFF));
    }

    @Test
    void everyIdentityFieldSeparatesTargets() {
        Path base = Path.of("cache-base");
        Path original = namespace(PgCatalogCacheNamespace.CACHE_ABI, BASE_VALUES)
                .resolveUnder(base);

        for (int i = 0; i < BASE_VALUES.length - 1; i++) {
            String[] changed = BASE_VALUES.clone();
            changed[i] = ALTERNATE_VALUES[i];
            assertNotEquals(original,
                    namespace(PgCatalogCacheNamespace.CACHE_ABI, changed).resolveUnder(base),
                    COLUMN_NAMES[i]);

            String[] nullField = BASE_VALUES.clone();
            nullField[i] = null;
            assertThrows(IllegalArgumentException.class,
                    () -> namespace(PgCatalogCacheNamespace.CACHE_ABI, nullField),
                    COLUMN_NAMES[i] + " null");

            String[] blankField = BASE_VALUES.clone();
            blankField[i] = " \t";
            assertThrows(IllegalArgumentException.class,
                    () -> namespace(PgCatalogCacheNamespace.CACHE_ABI, blankField),
                    COLUMN_NAMES[i] + " blank");
        }
        String[] changedSnapshot = BASE_VALUES.clone();
        changedSnapshot[14] = ALTERNATE_VALUES[14];
        assertEquals(original,
                namespace(PgCatalogCacheNamespace.CACHE_ABI,
                        changedSnapshot).resolveUnder(base),
                "snapshot token must not enter the target namespace");
        assertNotEquals(original,
                namespace(PgCatalogCacheNamespace.CACHE_ABI + 1, BASE_VALUES)
                        .resolveUnder(base),
                "cache ABI");
    }

    @Test
    void lengthPrefixesPreventConcatenationAmbiguity() {
        String[] firstValues = BASE_VALUES.clone();
        firstValues[2] = "ab";
        firstValues[3] = "c";
        String[] secondValues = BASE_VALUES.clone();
        secondValues[2] = "a";
        secondValues[3] = "bc";

        PgCatalogCacheNamespace first = namespace(PgCatalogCacheNamespace.CACHE_ABI,
                firstValues);
        PgCatalogCacheNamespace second = namespace(PgCatalogCacheNamespace.CACHE_ABI,
                secondValues);

        assertNotEquals(first.resolveUnder(Path.of("cache")),
                second.resolveUnder(Path.of("cache")));
        assertNotEquals(first.readerQualifier("ab", "c", (byte) 1),
                first.readerQualifier("a", "bc", (byte) 1));
    }

    @Test
    void targetDirectoryContainsOnlyPrefixAndLowercaseSha256() {
        Path base = Path.of("cache-base");
        Path target = namespace(PgCatalogCacheNamespace.CACHE_ABI, BASE_VALUES)
                .resolveUnder(base);

        assertEquals(2, PgCatalogCacheNamespace.CACHE_ABI);
        assertEquals("target-v2-", PgCatalogCacheNamespace.TARGET_PREFIX);
        assertEquals(base, target.getParent());
        assertTrue(target.getFileName().toString().matches("target-v2-[0-9a-f]{64}"));
        assertTrue(Modifier.isFinal(PgCatalogCacheNamespace.class.getModifiers()));
        assertTrue(Arrays.stream(PgCatalogCacheNamespace.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(Arrays.stream(PgCatalogCacheNamespace.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .allMatch(field -> Modifier.isPrivate(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers())));
    }

    @Test
    void passwordAndCompleteJdbcUrlAreNeverInputsOrOutput() {
        String password = "raw-password-never-hashed";
        String jdbcUrl = "jdbc:postgresql://db.example.test:5432/application?password=" + password;
        String[] distinctiveValues = BASE_VALUES.clone();
        distinctiveValues[2] = "database-raw-identity";
        distinctiveValues[5] = "session-raw-identity";
        distinctiveValues[6] = "role-raw-identity";
        PgCatalogCacheNamespace namespace = namespace(
                PgCatalogCacheNamespace.CACHE_ABI, distinctiveValues);
        String targetName = namespace.resolveUnder(Path.of("cache")).getFileName().toString();
        String qualifier = namespace.readerQualifier(
                "reader-raw-identity", "SELECT 'query-raw-identity'", (byte) 1);

        assertEquals(EXPECTED_IDENTITY_QUERY, PgCatalogCacheNamespace.IDENTITY_QUERY);
        assertFalse(PgCatalogCacheNamespace.IDENTITY_QUERY.contains(
                "txid_current_snapshot"),
                "the default identity query must not probe a snapshot");
        assertFalse(PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK.contains(
                "txid_current_snapshot"));
        assertTrue(PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT
                .contains("txid_current_snapshot()"));
        assertTrue(PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT
                .contains("pg_control_system()"));
        assertTrue(PgCatalogCacheNamespace.IDENTITY_QUERY
                .contains("pg_control_system()"));
        assertFalse(PgCatalogCacheNamespace.IDENTITY_QUERY
                .contains("txid_current_snapshot()"));
        assertTrue(PgCatalogCacheNamespace
                .IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK
                .contains("txid_current_snapshot()"));
        assertFalse(PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK
                .contains("pg_control_system()"));
        assertFalse(PgCatalogCacheNamespace
                .IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK
                .contains("pg_control_system()"));
        for (String query : new String[] {
                PgCatalogCacheNamespace.IDENTITY_QUERY,
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT,
                PgCatalogCacheNamespace.IDENTITY_QUERY_FALLBACK,
                PgCatalogCacheNamespace.IDENTITY_QUERY_WITH_SNAPSHOT_FALLBACK
        }) {
            assertFalse(query.contains("has_function_privilege("));
        }
        assertFalse(PgCatalogCacheNamespace.IDENTITY_QUERY.toLowerCase().contains("password"));
        assertFalse(PgCatalogCacheNamespace.IDENTITY_QUERY.toLowerCase().contains("jdbc"));
        for (String forbidden : new String[] {
                password, jdbcUrl, distinctiveValues[2], distinctiveValues[5],
                distinctiveValues[6], "reader-raw-identity", "query-raw-identity"
        }) {
            assertFalse(targetName.contains(forbidden), forbidden);
            assertFalse(qualifier.contains(forbidden), forbidden);
        }
    }

    @Test
    void sameReaderAndQueryProduceStableQualifier() {
        PgCatalogCacheNamespace first = namespace(PgCatalogCacheNamespace.CACHE_ABI,
                BASE_VALUES);
        PgCatalogCacheNamespace second = namespace(PgCatalogCacheNamespace.CACHE_ABI,
                BASE_VALUES);

        assertEquals(first.readerQualifier("PgTablesReader", "SELECT * FROM pg_class", (byte) 1),
                second.readerQualifier("PgTablesReader", "SELECT * FROM pg_class", (byte) 1));
    }

    @Test
    void readerNameExactQueryCodecAndAbiSeparateQualifiers() {
        PgCatalogCacheNamespace namespace = namespace(
                PgCatalogCacheNamespace.CACHE_ABI, BASE_VALUES);
        String original = namespace.readerQualifier(
                "PgTablesReader", "SELECT * FROM pg_class", (byte) 0xFF);

        assertNotEquals(original, namespace.readerQualifier(
                "PgViewsReader", "SELECT * FROM pg_class", (byte) 0xFF));
        assertNotEquals(original, namespace.readerQualifier(
                "PgTablesReader", "SELECT * FROM pg_class ", (byte) 0xFF));
        assertNotEquals(original, namespace.readerQualifier(
                "PgTablesReader", "SELECT * FROM pg_class", (byte) 0));
        assertNotEquals(original,
                namespace(PgCatalogCacheNamespace.CACHE_ABI + 1, BASE_VALUES)
                        .readerQualifier(
                                "PgTablesReader", "SELECT * FROM pg_class", (byte) 0xFF));
        assertThrows(IllegalArgumentException.class,
                () -> namespace.readerQualifier(" ", "SELECT 1", (byte) 1));
        assertThrows(IllegalArgumentException.class,
                () -> namespace.readerQualifier("reader", " ", (byte) 1));
    }

    @Test
    void qualifierUsesOneCompleteSha256WithoutTruncation() {
        String qualifier = namespace(PgCatalogCacheNamespace.CACHE_ABI, BASE_VALUES)
                .readerQualifier("PgTablesReader", "SELECT * FROM pg_class", (byte) 1);

        assertEquals(65, qualifier.length());
        assertTrue(qualifier.matches("n[0-9a-f]{64}"));
    }

    private static PgCatalogCacheNamespace namespace(int cacheAbi, String[] values) {
        assertEquals(COLUMN_NAMES.length, values.length);
        return PgCatalogCacheNamespace.fromValues(cacheAbi,
                values[0], values[1], values[2], values[3], values[4], values[5],
                values[6], values[7], values[8], values[9], values[10], values[11],
                values[12], values[13]);
    }

    private static ResultSet resultSet(String[] values) throws Exception {
        ResultSet result = mock(ResultSet.class);
        for (int i = 0; i < COLUMN_NAMES.length; i++) {
            when(result.getString(COLUMN_NAMES[i])).thenReturn(values[i]);
        }
        return result;
    }
}
