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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Verifies the strict accessor subset of the catalog row cache ResultSet
 * view: supported getters with pgJDBC-compatible conversions, wasNull
 * semantics, array materialization and loud failures for everything else.
 */
class PgCachedRowResultSetTest {

    private static final String[] LABELS = {
            "text_col", "long_col", "int_col", "short_col", "bool_col",
            "float_col", "double_col", "bytes_col", "array_col", "null_col"
    };

    private static PgCachedRowResultSet row() {
        return PgCachedRowResultSet.positioned(new PgCachedCatalogRow(LABELS, new Object[] {
                "text", 42L, 7, (short) 3, Boolean.TRUE,
                1.5F, 2.5D, new byte[] {1, 2}, new String[] {"a", null, "c"}, null
        }));
    }

    @Test
    void supportedGettersReturnCapturedValues() throws SQLException {
        PgCachedRowResultSet result = row();

        assertEquals("text", result.getString("text_col"));
        assertEquals(42L, result.getLong("long_col"));
        assertEquals(7, result.getInt("int_col"));
        assertEquals((short) 3, result.getShort("short_col"));
        assertTrue(result.getBoolean("bool_col"));
        assertEquals(1.5F, result.getFloat("float_col"));
        assertEquals(2.5D, result.getDouble("double_col"));
        assertArrayEquals(new byte[] {1, 2}, result.getBytes("bytes_col"));
        assertEquals("text", result.getString(1));
        assertEquals(42L, result.getObject("long_col"));
    }

    @Test
    void numericGettersWidenAndNarrowDeterministically() throws SQLException {
        var result = PgCachedRowResultSet.positioned(new PgCachedCatalogRow(
                new String[] {"i", "s", "l", "f"},
                new Object[] {7, (short) 3, 42L, 1.5F}));

        assertEquals(7L, result.getLong("i"));
        assertEquals(3L, result.getLong("s"));
        assertEquals(3, result.getInt("s"));
        assertEquals(42, result.getInt("l"));
        assertEquals((short) 7, result.getShort("i"));
        assertEquals(1.5D, result.getDouble("f"));
    }

    @Test
    void outOfRangeNarrowingFailsLoudly() {
        var result = PgCachedRowResultSet.positioned(new PgCachedCatalogRow(
                new String[] {"l", "i"},
                new Object[] {Long.MAX_VALUE, Integer.MAX_VALUE}));

        assertThrows(UnsupportedOperationException.class, () -> result.getInt("l"));
        assertThrows(UnsupportedOperationException.class, () -> result.getShort("i"));
    }

    @Test
    void wasNullTracksEveryGetterPerColumnRead() throws SQLException {
        PgCachedRowResultSet result = row();

        assertNull(result.getString("null_col"));
        assertTrue(result.wasNull());
        assertEquals("text", result.getString("text_col"));
        assertFalse(result.wasNull());
        assertEquals(0L, result.getLong("null_col"));
        assertTrue(result.wasNull());
        assertEquals(0, result.getInt("null_col"));
        assertTrue(result.wasNull());
        assertFalse(result.getBoolean("null_col"));
        assertTrue(result.wasNull());
        assertEquals(0F, result.getFloat("null_col"));
        assertTrue(result.wasNull());
        assertNull(result.getBytes("null_col"));
        assertTrue(result.wasNull());
        assertNull(result.getArray("null_col"));
        assertTrue(result.wasNull());
    }

    @Test
    void arraysMaterializeWithExactComponentTypeAndFreeIsIdempotent() throws SQLException {
        PgCachedRowResultSet result = row();

        Array array = result.getArray("array_col");
        Object content = array.getArray();
        assertSame(String[].class, content.getClass());
        assertArrayEquals(new String[] {"a", null, "c"}, (String[]) content);
        array.free();
        array.free();

        String[] viaUtils = PgJdbcUtils.getColArray(result, "array_col");
        assertArrayEquals(new String[] {"a", null, "c"}, viaUtils);
    }

    @Test
    void getObjectReturnsSqlArrayForArrayColumnsLikePgJdbc() throws SQLException {
        PgCachedRowResultSet result = row();

        Object object = result.getObject("array_col");
        assertTrue(object instanceof Array);
        assertArrayEquals(new String[] {"a", null, "c"}, (String[]) ((Array) object).getArray());
    }

    /**
     * A privilege column is read as text while an option column is read as
     * elements, so a captured array serves both from one value.
     */
    @Test
    void capturedArrayServesTextAndElementsFromOneValue() throws SQLException {
        String text = "{\"a\\\"b\",NULL,\"c,d\"}";
        var result = PgCachedRowResultSet.positioned(new PgCachedCatalogRow(
                new String[] {"acl"},
                new Object[] {new PgCachedCatalogArray(
                        new String[] {"a\"b", null, "c,d"}, text)}));

        assertEquals(text, result.getString("acl"));
        assertFalse(result.wasNull());
        assertEquals(text, result.getString(1));
        assertArrayEquals(new String[] {"a\"b", null, "c,d"},
                (String[]) result.getArray("acl").getArray());
        assertArrayEquals(new String[] {"a\"b", null, "c,d"},
                (String[]) ((Array) result.getObject("acl")).getArray());
        assertArrayEquals(new String[] {"a\"b", null, "c,d"},
                PgJdbcUtils.<String>getColArray(result, "acl"));
        assertArrayEquals(new String[] {"a\"b", null, "c,d"},
                result.getObject("acl", String[].class));
    }

    @Test
    void typedGetObjectCastsOrFailsLoudly() throws SQLException {
        PgCachedRowResultSet result = row();

        assertEquals(Long.valueOf(42L), result.getObject("long_col", Long.class));
        assertNull(result.getObject("null_col", Long.class));
        assertThrows(UnsupportedOperationException.class,
                () -> result.getObject("text_col", Long.class));
    }

    @Test
    void getStringRendersIntegerTypesLikePostgresText() throws SQLException {
        PgCachedRowResultSet result = row();

        assertEquals("42", result.getString("long_col"));
        assertEquals("7", result.getString("int_col"));
        assertEquals("3", result.getString("short_col"));
    }

    @Test
    void crossTypeConversionsFailLoudly() {
        PgCachedRowResultSet result = row();

        // bool and floating types render differently in PostgreSQL text
        // output than in Java, so getString on them must stay loud
        assertThrows(UnsupportedOperationException.class, () -> result.getString("bool_col"));
        assertThrows(UnsupportedOperationException.class, () -> result.getString("float_col"));
        assertThrows(UnsupportedOperationException.class, () -> result.getLong("text_col"));
        assertThrows(UnsupportedOperationException.class, () -> result.getBoolean("int_col"));
        assertThrows(UnsupportedOperationException.class, () -> result.getFloat("double_col"));
        assertThrows(UnsupportedOperationException.class, () -> result.getBytes("text_col"));
        assertThrows(UnsupportedOperationException.class, () -> result.getArray("text_col"));
    }

    @Test
    void unsupportedResultSetMethodsFailLoudly() {
        PgCachedRowResultSet result = row();

        assertThrows(UnsupportedOperationException.class, () -> result.getBigDecimal(1));
        assertThrows(UnsupportedOperationException.class, () -> result.getTimestamp("text_col"));
        assertThrows(UnsupportedOperationException.class, result::getStatement);
        assertThrows(UnsupportedOperationException.class, result::first);
        assertThrows(UnsupportedOperationException.class, () -> result.updateString(1, "x"));
    }

    @Test
    void missingColumnMatchesPgJdbcContract() {
        PgCachedRowResultSet result = row();

        SQLException ex = assertThrows(SQLException.class, () -> result.getString("no_such"));
        assertTrue(ex.getMessage().contains("no_such"));
    }

    @Test
    void findColumnIsCaseInsensitiveLikePgJdbc() throws SQLException {
        PgCachedRowResultSet result = row();

        assertEquals(1, result.findColumn("TEXT_COL"));
        assertEquals("text", result.getString("Text_Col"));
    }

    @Test
    void cursorIteratesRowsInOrderAndExposesMetadata() throws SQLException {
        var first = new PgCachedCatalogRow(new String[] {"n"}, new Object[] {"one"});
        var second = new PgCachedCatalogRow(new String[] {"n"}, new Object[] {"two"});
        ResultSet cursor = PgCachedRowResultSet.cursor(new String[] {"n"}, List.of(first, second));

        assertEquals(1, cursor.getMetaData().getColumnCount());
        assertEquals("n", cursor.getMetaData().getColumnLabel(1));
        assertThrows(SQLException.class, () -> cursor.getString("n"));
        assertTrue(cursor.next());
        assertEquals("one", cursor.getString("n"));
        assertTrue(cursor.next());
        assertEquals("two", cursor.getString("n"));
        assertFalse(cursor.next());
        assertThrows(SQLException.class, () -> cursor.getString("n"));
    }

    @Test
    void positionedRowNeedsNoNextCall() throws SQLException {
        PgCachedRowResultSet result = row();

        assertEquals("text", result.getString("text_col"));
        assertFalse(result.next());
    }

    @Test
    void positionedValuesShareOneLabelsArrayWithoutCopyingIt() throws SQLException {
        String[] sharedLabels = {"id", "value"};
        PgCachedRowResultSet first = PgCachedRowResultSet.positioned(
                sharedLabels, new Object[] {1, "one"});
        PgCachedRowResultSet second = PgCachedRowResultSet.positioned(
                sharedLabels, new Object[] {2, "two"});

        assertEquals(2, first.getMetaData().getColumnCount());
        assertEquals("value", first.getMetaData().getColumnLabel(2));
        assertEquals(2, second.findColumn("VALUE"));
        assertEquals("one", first.getString("value"));
        assertEquals("two", second.getString("value"));

        sharedLabels[1] = "renamed";
        assertEquals(2, first.findColumn("renamed"));
        assertEquals(2, second.findColumn("renamed"));
        assertEquals("renamed", second.getMetaData().getColumnLabel(2));
    }

    @Test
    void positionedValuesUseDirectStateWithoutRowOrListAllocation() throws Exception {
        String[] labels = {"payload"};
        Object[] values = {new byte[] {1, 2, 3}};

        PgCachedRowResultSet result = PgCachedRowResultSet.positioned(labels, values);

        assertNull(field(result, "rows"));
        assertSame(values, field(result, "currentValues"));
        assertSame(values[0], result.getBytes(1));
    }

    private static Object field(PgCachedRowResultSet result, String name)
            throws ReflectiveOperationException {
        Field field = PgCachedRowResultSet.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(result);
    }
}
