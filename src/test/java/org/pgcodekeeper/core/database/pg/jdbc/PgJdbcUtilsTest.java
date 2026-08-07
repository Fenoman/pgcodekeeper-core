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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PgJdbcUtilsTest {

    @Test
    void decodedArrayIsFreedBeforeReturn() throws Exception {
        ResultSet result = mock(ResultSet.class);
        Array array = mock(Array.class);
        String[] decoded = { "first", "second" };
        when(result.getArray("values")).thenReturn(array);
        when(array.getArray()).thenReturn(decoded);

        String[] actual = PgJdbcUtils.getColArray(result, "values");

        assertSame(decoded, actual);
        InOrder order = inOrder(array);
        order.verify(array).getArray();
        order.verify(array).free();
    }

    @Test
    void decodeFailureRemainsPrimaryAndFreeFailureIsSuppressed() throws Exception {
        ResultSet result = mock(ResultSet.class);
        Array array = mock(Array.class);
        SQLException decodeFailure = new SQLException("decode");
        SQLException freeFailure = new SQLException("free");
        when(result.getArray("values")).thenReturn(array);
        when(array.getArray()).thenThrow(decodeFailure);
        doThrow(freeFailure).when(array).free();

        SQLException actual = assertThrows(SQLException.class,
                () -> PgJdbcUtils.getColArray(result, "values"));

        assertSame(decodeFailure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(freeFailure, actual.getSuppressed()[0]);
    }

    @Test
    void sameFailureIdentityIsNeverSelfSuppressed() throws Exception {
        ResultSet result = mock(ResultSet.class);
        Array array = mock(Array.class);
        SQLException shared = new SQLException("shared");
        when(result.getArray("values")).thenReturn(array);
        when(array.getArray()).thenThrow(shared);
        doThrow(shared).when(array).free();

        SQLException actual = assertThrows(SQLException.class,
                () -> PgJdbcUtils.getColArray(result, "values"));

        assertSame(shared, actual);
        assertEquals(0, actual.getSuppressed().length);
        verify(array).free();
    }

    @Test
    void freeFailureAfterSuccessfulDecodeIsPropagated() throws Exception {
        ResultSet result = mock(ResultSet.class);
        Array array = mock(Array.class);
        SQLException freeFailure = new SQLException("free");
        when(result.getArray("values")).thenReturn(array);
        when(array.getArray()).thenReturn(new Long[] { 1L });
        doThrow(freeFailure).when(array).free();

        SQLException actual = assertThrows(SQLException.class,
                () -> PgJdbcUtils.<Long>getColArray(result, "values"));

        assertSame(freeFailure, actual);
    }

    @Test
    void decodeRuntimeFailureRemainsPrimaryAndArrayIsFreed() throws Exception {
        ResultSet result = mock(ResultSet.class);
        Array array = mock(Array.class);
        IllegalStateException decodeFailure = new IllegalStateException("decode");
        when(result.getArray("values")).thenReturn(array);
        when(array.getArray()).thenThrow(decodeFailure);

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> PgJdbcUtils.getColArray(result, "values"));

        assertSame(decodeFailure, actual);
        verify(array).free();
    }

    @Test
    void freeErrorAfterSuccessfulDecodeIsPropagated() throws Exception {
        ResultSet result = mock(ResultSet.class);
        Array array = mock(Array.class);
        AssertionError freeFailure = new AssertionError("free");
        when(result.getArray("values")).thenReturn(array);
        when(array.getArray()).thenReturn(new Long[] { 1L });
        doThrow(freeFailure).when(array).free();

        AssertionError actual = assertThrows(AssertionError.class,
                () -> PgJdbcUtils.<Long>getColArray(result, "values"));

        assertSame(freeFailure, actual);
    }

    @Test
    void allowedNullDoesNotAttemptFree() throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getArray("values")).thenReturn(null);

        assertNull(PgJdbcUtils.getColArray(result, "values", true));

        verify(result).getArray("values");
    }
}
