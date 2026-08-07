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

import java.sql.ResultSet;
import java.sql.SQLException;

import org.pgcodekeeper.core.database.pg.routine.RoutineFingerprint;

/**
 * One validated fingerprint-mode body projection. The fixed-size JDBC digest
 * array is converted immediately to primitive fingerprint words and is never
 * retained.
 */
record PgRoutineBodyCatalogRow(String fullBodyProsrc, String probin,
                               String prosqlbody, long bodyOid,
                               RoutineFingerprint fingerprint) {

    static PgRoutineBodyCatalogRow readFingerprint(ResultSet result) throws SQLException {
        Long oid = result.getObject("body_oid", Long.class);
        Long utf8Length = result.getObject("body_utf8_length", Long.class);
        byte[] digest = result.getBytes("body_sha256");

        boolean anyFingerprint = oid != null || utf8Length != null || digest != null;
        boolean completeFingerprint = oid != null && utf8Length != null && digest != null;
        if (anyFingerprint && !completeFingerprint) {
            throw new SQLException("Partial PostgreSQL routine fingerprint tuple");
        }

        String fullBodyProsrc = result.getString("full_body_prosrc");
        String probin = result.getString("probin");
        String prosqlbody = result.getString("prosqlbody");
        if (!completeFingerprint) {
            return new PgRoutineBodyCatalogRow(
                    fullBodyProsrc, probin, prosqlbody, 0L, null);
        }
        if (oid <= 0L) {
            throw new SQLException("Invalid PostgreSQL routine body OID: " + oid);
        }
        if (fullBodyProsrc != null || probin != null || prosqlbody != null) {
            throw new SQLException(
                    "Fingerprint routine row contains a full-body payload: " + oid);
        }

        RoutineFingerprint fingerprint;
        try {
            fingerprint = RoutineFingerprint.fromSha256(utf8Length, digest);
        } catch (IllegalArgumentException ex) {
            throw new SQLException("Invalid PostgreSQL routine fingerprint: " + oid, ex);
        }
        return new PgRoutineBodyCatalogRow(null, null, null, oid, fingerprint);
    }
}
