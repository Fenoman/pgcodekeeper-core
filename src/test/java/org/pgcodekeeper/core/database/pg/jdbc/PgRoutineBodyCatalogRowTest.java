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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgcodekeeper.core.database.pg.routine.RoutineFingerprint;

class PgRoutineBodyCatalogRowTest {

    @Test
    void completeTupleBecomesPrimitiveFingerprintWithoutReadingFullBody() throws Exception {
        ResultSet result = mock(ResultSet.class);
        byte[] digest = digest();
        when(result.getObject("body_oid", Long.class)).thenReturn(42L);
        when(result.getObject("body_utf8_length", Long.class))
                .thenReturn((long) Integer.MAX_VALUE + 10L);
        when(result.getBytes("body_sha256")).thenReturn(digest);

        PgRoutineBodyCatalogRow row = PgRoutineBodyCatalogRow.readFingerprint(result);
        RoutineFingerprint fingerprint = row.fingerprint();
        long expectedFirstWord = fingerprint.hash0();
        digest[0] ^= 0x7f;

        assertEquals(42L, row.bodyOid());
        assertEquals((long) Integer.MAX_VALUE + 10L, fingerprint.utf8Length());
        assertEquals(expectedFirstWord, fingerprint.hash0());
        assertNull(row.fullBodyProsrc());
        assertNull(row.probin());
        assertNull(row.prosqlbody());
        verify(result, never()).getString("prosrc");
    }

    @Test
    void allNullTupleReturnsFullBodyColumns() throws Exception {
        ResultSet result = mock(ResultSet.class);
        String raw = new String("SELECT 1");
        when(result.getString("full_body_prosrc")).thenReturn(raw);
        when(result.getString("probin")).thenReturn("library");
        when(result.getString("prosqlbody")).thenReturn("BEGIN ATOMIC SELECT 1; END");

        PgRoutineBodyCatalogRow row = PgRoutineBodyCatalogRow.readFingerprint(result);

        assertSame(raw, row.fullBodyProsrc());
        assertEquals("library", row.probin());
        assertEquals("BEGIN ATOMIC SELECT 1; END", row.prosqlbody());
        assertNull(row.fingerprint());
        assertEquals(0L, row.bodyOid());
    }

    @ParameterizedTest
    @MethodSource("partialTuples")
    void partialFingerprintTupleFailsClosed(Long oid, Long length, byte[] digest) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("body_oid", Long.class)).thenReturn(oid);
        when(result.getObject("body_utf8_length", Long.class)).thenReturn(length);
        when(result.getBytes("body_sha256")).thenReturn(digest);

        assertThrows(SQLException.class,
                () -> PgRoutineBodyCatalogRow.readFingerprint(result));
    }

    @ParameterizedTest
    @MethodSource("invalidCompleteTuples")
    void invalidCompleteTupleFailsClosed(long oid, long length, byte[] digest) throws Exception {
        ResultSet result = completeTuple(oid, length, digest);

        assertThrows(SQLException.class,
                () -> PgRoutineBodyCatalogRow.readFingerprint(result));
    }

    @Test
    void fingerprintTupleRejectsAnyFullBodyPayload() throws Exception {
        ResultSet withProsrc = completeTuple(1L, 1L, digest());
        when(withProsrc.getString("full_body_prosrc")).thenReturn("");
        ResultSet withProbin = completeTuple(1L, 1L, digest());
        when(withProbin.getString("probin")).thenReturn("library");
        ResultSet withSqlBody = completeTuple(1L, 1L, digest());
        when(withSqlBody.getString("prosqlbody")).thenReturn("SELECT 1");

        assertThrows(SQLException.class,
                () -> PgRoutineBodyCatalogRow.readFingerprint(withProsrc));
        assertThrows(SQLException.class,
                () -> PgRoutineBodyCatalogRow.readFingerprint(withProbin));
        assertThrows(SQLException.class,
                () -> PgRoutineBodyCatalogRow.readFingerprint(withSqlBody));
    }

    @Test
    void getterFailurePropagatesByIdentity() throws Exception {
        ResultSet result = mock(ResultSet.class);
        SQLException failure = new SQLException("controlled tuple failure");
        when(result.getObject("body_oid", Long.class)).thenThrow(failure);

        SQLException thrown = assertThrows(SQLException.class,
                () -> PgRoutineBodyCatalogRow.readFingerprint(result));

        assertSame(failure, thrown);
    }

    private static Stream<Arguments> partialTuples() {
        return Stream.of(
                Arguments.of(1L, null, null),
                Arguments.of(null, 1L, null),
                Arguments.of(null, null, digest()),
                Arguments.of(1L, 1L, null),
                Arguments.of(1L, null, digest()),
                Arguments.of(null, 1L, digest()));
    }

    private static Stream<Arguments> invalidCompleteTuples() {
        return Stream.of(
                Arguments.of(0L, 1L, digest()),
                Arguments.of(-1L, 1L, digest()),
                Arguments.of(1L, -1L, digest()),
                Arguments.of(1L, 1L, new byte[31]),
                Arguments.of(1L, 1L, new byte[33]));
    }

    private static ResultSet completeTuple(long oid, long length, byte[] digest)
            throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("body_oid", Long.class)).thenReturn(oid);
        when(result.getObject("body_utf8_length", Long.class)).thenReturn(length);
        when(result.getBytes("body_sha256")).thenReturn(digest);
        return result;
    }

    private static byte[] digest() {
        byte[] digest = new byte[32];
        for (int i = 0; i < digest.length; i++) {
            digest[i] = (byte) i;
        }
        return digest;
    }
}
