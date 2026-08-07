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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.pgcodekeeper.core.database.base.jdbc.JdbcRunner;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.routine.PgJdbcRoutineBodyCatalogMode;

/**
 * Selects the bounded routine catalog protocol without ever invoking a
 * possibly missing digest function. Known capability incompatibilities use
 * the full-body query; SQL, protocol and cancellation failures propagate.
 */
final class PgRoutineBodyFingerprintCapability {

    private static final String LOOKUP_QUERY = """
            SELECT
              c.function_oid IS NOT NULL AS available,
              CASE WHEN c.function_oid IS NULL THEN FALSE
                ELSE pg_catalog.has_function_privilege(c.function_oid, 'EXECUTE') END AS executable,
              pg_catalog.current_setting('server_encoding') = 'UTF8' AS utf8_database
            FROM (
              SELECT pg_catalog.to_regprocedure('pg_catalog.sha256(bytea)')::oid AS function_oid
            ) c""";

    private static final String VECTOR_QUERY = """
            SELECT pg_catalog.sha256(pg_catalog.convert_to('abc', 'UTF8')) =
              pg_catalog.decode('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
                'hex') AS compatible""";

    private PgRoutineBodyFingerprintCapability() {
    }

    static PgJdbcRoutineBodyCatalogMode detect(
            boolean requested, PgJdbcLoader loader, Statement statement)
            throws SQLException, InterruptedException {
        if (!requested || loader.isGreenplumDb()
                || !PgSupportedVersion.VERSION_14.isLE(loader.getVersion())) {
            return PgJdbcRoutineBodyCatalogMode.FULL_BODY;
        }

        loader.checkCatalogReaderCancellation();
        JdbcRunner runner = loader.getRunner();
        boolean available;
        boolean executable;
        boolean utf8Database;
        try (ResultSet result = runner.runScript(statement, LOOKUP_QUERY)) {
            requireRow(result, "fingerprint capability");
            available = requireBoolean(result, "available", "fingerprint capability");
            executable = requireBoolean(result, "executable", "fingerprint capability");
            utf8Database = requireBoolean(result, "utf8_database", "fingerprint capability");
            requireEnd(result, "fingerprint capability");
        }
        loader.checkCatalogReaderCancellation();
        if (!available || !executable) {
            return PgJdbcRoutineBodyCatalogMode.FULL_BODY;
        }

        boolean compatible;
        try (ResultSet result = runner.runScript(statement, VECTOR_QUERY)) {
            requireRow(result, "fingerprint compatibility");
            compatible = requireBoolean(result, "compatible", "fingerprint compatibility");
            requireEnd(result, "fingerprint compatibility");
        }
        loader.checkCatalogReaderCancellation();
        if (!compatible) {
            return PgJdbcRoutineBodyCatalogMode.FULL_BODY;
        }
        return utf8Database
                ? PgJdbcRoutineBodyCatalogMode.FINGERPRINT_UTF8
                : PgJdbcRoutineBodyCatalogMode.FINGERPRINT_CONVERTED;
    }

    private static void requireRow(ResultSet result, String phase) throws SQLException {
        if (!result.next()) {
            throw new SQLException("Missing PostgreSQL routine " + phase + " row");
        }
    }

    private static boolean requireBoolean(ResultSet result, String column, String phase)
            throws SQLException {
        boolean value = result.getBoolean(column);
        if (result.wasNull()) {
            throw new SQLException("NULL PostgreSQL routine " + phase + " column: " + column);
        }
        return value;
    }

    private static void requireEnd(ResultSet result, String phase) throws SQLException {
        if (result.next()) {
            throw new SQLException("Duplicate PostgreSQL routine " + phase + " row");
        }
    }
}
