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

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client-side {@link ResultSet} view over materialized catalog rows of the
 * PostgreSQL row-level catalog cache. Supports exactly the accessor subset
 * the PostgreSQL catalog readers use ({@code getString}, {@code getLong},
 * {@code getInt}, {@code getShort}, {@code getBoolean}, {@code getFloat},
 * {@code getDouble}, {@code getBytes}, {@code getObject}, {@code getArray},
 * {@code wasNull}, {@code findColumn}, {@code getMetaData}) with strict,
 * deterministic type conversions matching what pgJDBC returns for the
 * captured value types. Every other method and every unsupported conversion
 * throws a clear {@link UnsupportedOperationException} so that coverage gaps
 * fail loudly in tests instead of silently corrupting the loaded model.
 */
public final class PgCachedRowResultSet implements ResultSet {

    private final String[] labels;
    private final List<PgCachedCatalogRow> rows;

    private int rowIndex;
    private Object[] currentValues;
    private boolean lastWasNull;

    private PgCachedRowResultSet(String[] labels, List<PgCachedCatalogRow> rows,
            int rowIndex, Object[] currentValues) {
        this.labels = Objects.requireNonNull(labels, "labels");
        this.rows = rows;
        this.rowIndex = rowIndex;
        this.currentValues = currentValues;
    }

    /**
     * Creates a single-row view already positioned on its row, as handed to
     * a reader's {@code processResult} during replay.
     */
    public static PgCachedRowResultSet positioned(PgCachedCatalogRow row) {
        Objects.requireNonNull(row, "row");
        return new PgCachedRowResultSet(row.labels(), null, 0, row.values());
    }

    /**
     * Creates a positioned row using the reader-wide labels array directly.
     * No labels copy is made, so every replayed row can share one instance.
     */
    public static PgCachedRowResultSet positioned(String[] sharedLabels, Object[] values) {
        Objects.requireNonNull(sharedLabels, "sharedLabels");
        Objects.requireNonNull(values, "values");
        if (sharedLabels.length != values.length) {
            throw new IllegalArgumentException(
                    "Catalog row labels and values must have equal length");
        }
        return new PgCachedRowResultSet(sharedLabels, null, 0, values);
    }

    /**
     * Creates a multi-row cursor positioned before the first row; callers
     * iterate with {@link #next()}. Labels are passed explicitly so an empty
     * result keeps its metadata.
     */
    public static PgCachedRowResultSet cursor(String[] labels, List<PgCachedCatalogRow> rows) {
        return new PgCachedRowResultSet(labels, List.copyOf(rows), -1, null);
    }

    @Override
    public boolean next() {
        if (rows != null && rowIndex + 1 < rows.size()) {
            rowIndex++;
            currentValues = rows.get(rowIndex).values();
            return true;
        }
        rowIndex = rows == null ? 1 : rows.size();
        currentValues = null;
        return false;
    }

    @Override
    public void close() {
        // client-side view holds no resources
    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public boolean wasNull() {
        return lastWasNull;
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        Objects.requireNonNull(columnLabel, "columnLabel");
        for (int i = 0; i < labels.length; i++) {
            if (columnLabel.equals(labels[i])) {
                return i + 1;
            }
        }
        for (int i = 0; i < labels.length; i++) {
            if (columnLabel.equalsIgnoreCase(labels[i])) {
                return i + 1;
            }
        }
        throw new SQLException("The column name " + columnLabel + " was not found in this ResultSet.");
    }

    @Override
    public ResultSetMetaData getMetaData() {
        return new CachedRowMetaData(labels);
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof PgCachedCatalogArray array) {
            // the exact text pgJDBC returned for this array column, so
            // privilege columns replay byte for byte
            return array.text();
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short) {
            // PostgreSQL renders integer types as plain decimal text, so
            // this matches pgJDBC getString on int2/int4/int8/oid exactly;
            // bool and floating types render differently and stay strict
            return value.toString();
        }
        throw conversion(columnIndex, value, "getString");
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw conversion(columnIndex, value, "getBoolean");
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return 0;
        }
        if (value instanceof Short shortValue) {
            return shortValue;
        }
        if (value instanceof Integer intValue
                && intValue >= Short.MIN_VALUE && intValue <= Short.MAX_VALUE) {
            return intValue.shortValue();
        }
        throw conversion(columnIndex, value, "getShort");
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return 0;
        }
        if (value instanceof Integer intValue) {
            return intValue;
        }
        if (value instanceof Short shortValue) {
            return shortValue;
        }
        if (value instanceof Long longValue
                && longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
            return longValue.intValue();
        }
        throw conversion(columnIndex, value, "getInt");
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Integer intValue) {
            return intValue;
        }
        if (value instanceof Short shortValue) {
            return shortValue;
        }
        throw conversion(columnIndex, value, "getLong");
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return 0F;
        }
        if (value instanceof Float floatValue) {
            return floatValue;
        }
        throw conversion(columnIndex, value, "getFloat");
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return 0D;
        }
        if (value instanceof Double doubleValue) {
            return doubleValue;
        }
        if (value instanceof Float floatValue) {
            return floatValue;
        }
        throw conversion(columnIndex, value, "getDouble");
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        throw conversion(columnIndex, value, "getBytes");
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value instanceof PgCachedCatalogArray array) {
            // pgJDBC returns java.sql.Array for array columns
            return new CachedArray(array.elements());
        }
        if (value instanceof Object[] array) {
            return new CachedArray(array);
        }
        return value;
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        Objects.requireNonNull(type, "type");
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof PgCachedCatalogArray array) {
            if (type.isInstance(array.elements())) {
                return type.cast(array.elements());
            }
            throw conversion(columnIndex, array.elements(),
                    "getObject(" + type.getSimpleName() + ")");
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        throw conversion(columnIndex, value, "getObject(" + type.getSimpleName() + ")");
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return getArray(findColumn(columnLabel));
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        Object value = value(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof PgCachedCatalogArray array) {
            return new CachedArray(array.elements());
        }
        if (value instanceof Object[] array) {
            return new CachedArray(array);
        }
        throw conversion(columnIndex, value, "getArray");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    private Object value(int columnIndex) throws SQLException {
        if (currentValues == null) {
            throw new SQLException("ResultSet not positioned properly, perhaps you need to call next.");
        }
        if (columnIndex < 1 || columnIndex > labels.length) {
            throw new SQLException("The column index " + columnIndex + " is out of range.");
        }
        Object value = currentValues[columnIndex - 1];
        lastWasNull = value == null;
        return value;
    }

    private UnsupportedOperationException conversion(int columnIndex, Object value, String getter) {
        return new UnsupportedOperationException(
                "PostgreSQL catalog row cache cannot convert column " + labels[columnIndex - 1]
                        + " value of type " + value.getClass().getName() + " via " + getter);
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(
                "PostgreSQL catalog row cache ResultSet does not support " + method);
    }

    // ---- everything below is outside the accessor subset used by the
    // ---- PostgreSQL catalog readers and fails loudly by design

    @Override
    public BigDecimal getBigDecimal(String p0) throws SQLException {
        throw unsupported("getBigDecimal");
    }

    @Override
    public BigDecimal getBigDecimal(String p0, int p1) throws SQLException {
        throw unsupported("getBigDecimal");
    }

    @Override
    public BigDecimal getBigDecimal(int p0) throws SQLException {
        throw unsupported("getBigDecimal");
    }

    @Override
    public BigDecimal getBigDecimal(int p0, int p1) throws SQLException {
        throw unsupported("getBigDecimal");
    }

    @Override
    public Blob getBlob(String p0) throws SQLException {
        throw unsupported("getBlob");
    }

    @Override
    public Blob getBlob(int p0) throws SQLException {
        throw unsupported("getBlob");
    }

    @Override
    public Clob getClob(String p0) throws SQLException {
        throw unsupported("getClob");
    }

    @Override
    public Clob getClob(int p0) throws SQLException {
        throw unsupported("getClob");
    }

    @Override
    public Date getDate(String p0) throws SQLException {
        throw unsupported("getDate");
    }

    @Override
    public Date getDate(String p0, Calendar p1) throws SQLException {
        throw unsupported("getDate");
    }

    @Override
    public Date getDate(int p0) throws SQLException {
        throw unsupported("getDate");
    }

    @Override
    public Date getDate(int p0, Calendar p1) throws SQLException {
        throw unsupported("getDate");
    }

    @Override
    public InputStream getAsciiStream(String p0) throws SQLException {
        throw unsupported("getAsciiStream");
    }

    @Override
    public InputStream getAsciiStream(int p0) throws SQLException {
        throw unsupported("getAsciiStream");
    }

    @Override
    public InputStream getBinaryStream(String p0) throws SQLException {
        throw unsupported("getBinaryStream");
    }

    @Override
    public InputStream getBinaryStream(int p0) throws SQLException {
        throw unsupported("getBinaryStream");
    }

    @Override
    public InputStream getUnicodeStream(String p0) throws SQLException {
        throw unsupported("getUnicodeStream");
    }

    @Override
    public InputStream getUnicodeStream(int p0) throws SQLException {
        throw unsupported("getUnicodeStream");
    }

    @Override
    public NClob getNClob(String p0) throws SQLException {
        throw unsupported("getNClob");
    }

    @Override
    public NClob getNClob(int p0) throws SQLException {
        throw unsupported("getNClob");
    }

    @Override
    public Object getObject(String p0, Map<String, Class<?>> p1) throws SQLException {
        throw unsupported("getObject");
    }

    @Override
    public Object getObject(int p0, Map<String, Class<?>> p1) throws SQLException {
        throw unsupported("getObject");
    }

    @Override
    public Reader getCharacterStream(String p0) throws SQLException {
        throw unsupported("getCharacterStream");
    }

    @Override
    public Reader getCharacterStream(int p0) throws SQLException {
        throw unsupported("getCharacterStream");
    }

    @Override
    public Reader getNCharacterStream(String p0) throws SQLException {
        throw unsupported("getNCharacterStream");
    }

    @Override
    public Reader getNCharacterStream(int p0) throws SQLException {
        throw unsupported("getNCharacterStream");
    }

    @Override
    public Ref getRef(String p0) throws SQLException {
        throw unsupported("getRef");
    }

    @Override
    public Ref getRef(int p0) throws SQLException {
        throw unsupported("getRef");
    }

    @Override
    public RowId getRowId(String p0) throws SQLException {
        throw unsupported("getRowId");
    }

    @Override
    public RowId getRowId(int p0) throws SQLException {
        throw unsupported("getRowId");
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        throw unsupported("getWarnings");
    }

    @Override
    public SQLXML getSQLXML(String p0) throws SQLException {
        throw unsupported("getSQLXML");
    }

    @Override
    public SQLXML getSQLXML(int p0) throws SQLException {
        throw unsupported("getSQLXML");
    }

    @Override
    public Statement getStatement() throws SQLException {
        throw unsupported("getStatement");
    }

    @Override
    public String getCursorName() throws SQLException {
        throw unsupported("getCursorName");
    }

    @Override
    public String getNString(String p0) throws SQLException {
        throw unsupported("getNString");
    }

    @Override
    public String getNString(int p0) throws SQLException {
        throw unsupported("getNString");
    }

    @Override
    public Time getTime(String p0) throws SQLException {
        throw unsupported("getTime");
    }

    @Override
    public Time getTime(String p0, Calendar p1) throws SQLException {
        throw unsupported("getTime");
    }

    @Override
    public Time getTime(int p0) throws SQLException {
        throw unsupported("getTime");
    }

    @Override
    public Time getTime(int p0, Calendar p1) throws SQLException {
        throw unsupported("getTime");
    }

    @Override
    public Timestamp getTimestamp(String p0) throws SQLException {
        throw unsupported("getTimestamp");
    }

    @Override
    public Timestamp getTimestamp(String p0, Calendar p1) throws SQLException {
        throw unsupported("getTimestamp");
    }

    @Override
    public Timestamp getTimestamp(int p0) throws SQLException {
        throw unsupported("getTimestamp");
    }

    @Override
    public Timestamp getTimestamp(int p0, Calendar p1) throws SQLException {
        throw unsupported("getTimestamp");
    }

    @Override
    public URL getURL(String p0) throws SQLException {
        throw unsupported("getURL");
    }

    @Override
    public URL getURL(int p0) throws SQLException {
        throw unsupported("getURL");
    }

    @Override
    public boolean absolute(int p0) throws SQLException {
        throw unsupported("absolute");
    }

    @Override
    public boolean first() throws SQLException {
        throw unsupported("first");
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        throw unsupported("isAfterLast");
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        throw unsupported("isBeforeFirst");
    }

    @Override
    public boolean isFirst() throws SQLException {
        throw unsupported("isFirst");
    }

    @Override
    public boolean isLast() throws SQLException {
        throw unsupported("isLast");
    }

    @Override
    public boolean last() throws SQLException {
        throw unsupported("last");
    }

    @Override
    public boolean previous() throws SQLException {
        throw unsupported("previous");
    }

    @Override
    public boolean relative(int p0) throws SQLException {
        throw unsupported("relative");
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        throw unsupported("rowDeleted");
    }

    @Override
    public boolean rowInserted() throws SQLException {
        throw unsupported("rowInserted");
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        throw unsupported("rowUpdated");
    }

    @Override
    public byte getByte(String p0) throws SQLException {
        throw unsupported("getByte");
    }

    @Override
    public byte getByte(int p0) throws SQLException {
        throw unsupported("getByte");
    }

    @Override
    public int getConcurrency() throws SQLException {
        throw unsupported("getConcurrency");
    }

    @Override
    public int getFetchDirection() throws SQLException {
        throw unsupported("getFetchDirection");
    }

    @Override
    public int getFetchSize() throws SQLException {
        throw unsupported("getFetchSize");
    }

    @Override
    public int getHoldability() throws SQLException {
        throw unsupported("getHoldability");
    }

    @Override
    public int getRow() throws SQLException {
        throw unsupported("getRow");
    }

    @Override
    public int getType() throws SQLException {
        throw unsupported("getType");
    }

    @Override
    public void afterLast() throws SQLException {
        throw unsupported("afterLast");
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw unsupported("beforeFirst");
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw unsupported("cancelRowUpdates");
    }

    @Override
    public void clearWarnings() throws SQLException {
        throw unsupported("clearWarnings");
    }

    @Override
    public void deleteRow() throws SQLException {
        throw unsupported("deleteRow");
    }

    @Override
    public void insertRow() throws SQLException {
        throw unsupported("insertRow");
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw unsupported("moveToCurrentRow");
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw unsupported("moveToInsertRow");
    }

    @Override
    public void refreshRow() throws SQLException {
        throw unsupported("refreshRow");
    }

    @Override
    public void setFetchDirection(int p0) throws SQLException {
        throw unsupported("setFetchDirection");
    }

    @Override
    public void setFetchSize(int p0) throws SQLException {
        throw unsupported("setFetchSize");
    }

    @Override
    public void updateArray(String p0, Array p1) throws SQLException {
        throw unsupported("updateArray");
    }

    @Override
    public void updateArray(int p0, Array p1) throws SQLException {
        throw unsupported("updateArray");
    }

    @Override
    public void updateAsciiStream(String p0, InputStream p1) throws SQLException {
        throw unsupported("updateAsciiStream");
    }

    @Override
    public void updateAsciiStream(String p0, InputStream p1, int p2) throws SQLException {
        throw unsupported("updateAsciiStream");
    }

    @Override
    public void updateAsciiStream(String p0, InputStream p1, long p2) throws SQLException {
        throw unsupported("updateAsciiStream");
    }

    @Override
    public void updateAsciiStream(int p0, InputStream p1) throws SQLException {
        throw unsupported("updateAsciiStream");
    }

    @Override
    public void updateAsciiStream(int p0, InputStream p1, int p2) throws SQLException {
        throw unsupported("updateAsciiStream");
    }

    @Override
    public void updateAsciiStream(int p0, InputStream p1, long p2) throws SQLException {
        throw unsupported("updateAsciiStream");
    }

    @Override
    public void updateBigDecimal(String p0, BigDecimal p1) throws SQLException {
        throw unsupported("updateBigDecimal");
    }

    @Override
    public void updateBigDecimal(int p0, BigDecimal p1) throws SQLException {
        throw unsupported("updateBigDecimal");
    }

    @Override
    public void updateBinaryStream(String p0, InputStream p1) throws SQLException {
        throw unsupported("updateBinaryStream");
    }

    @Override
    public void updateBinaryStream(String p0, InputStream p1, int p2) throws SQLException {
        throw unsupported("updateBinaryStream");
    }

    @Override
    public void updateBinaryStream(String p0, InputStream p1, long p2) throws SQLException {
        throw unsupported("updateBinaryStream");
    }

    @Override
    public void updateBinaryStream(int p0, InputStream p1) throws SQLException {
        throw unsupported("updateBinaryStream");
    }

    @Override
    public void updateBinaryStream(int p0, InputStream p1, int p2) throws SQLException {
        throw unsupported("updateBinaryStream");
    }

    @Override
    public void updateBinaryStream(int p0, InputStream p1, long p2) throws SQLException {
        throw unsupported("updateBinaryStream");
    }

    @Override
    public void updateBlob(String p0, Blob p1) throws SQLException {
        throw unsupported("updateBlob");
    }

    @Override
    public void updateBlob(String p0, InputStream p1) throws SQLException {
        throw unsupported("updateBlob");
    }

    @Override
    public void updateBlob(String p0, InputStream p1, long p2) throws SQLException {
        throw unsupported("updateBlob");
    }

    @Override
    public void updateBlob(int p0, Blob p1) throws SQLException {
        throw unsupported("updateBlob");
    }

    @Override
    public void updateBlob(int p0, InputStream p1) throws SQLException {
        throw unsupported("updateBlob");
    }

    @Override
    public void updateBlob(int p0, InputStream p1, long p2) throws SQLException {
        throw unsupported("updateBlob");
    }

    @Override
    public void updateBoolean(String p0, boolean p1) throws SQLException {
        throw unsupported("updateBoolean");
    }

    @Override
    public void updateBoolean(int p0, boolean p1) throws SQLException {
        throw unsupported("updateBoolean");
    }

    @Override
    public void updateByte(String p0, byte p1) throws SQLException {
        throw unsupported("updateByte");
    }

    @Override
    public void updateByte(int p0, byte p1) throws SQLException {
        throw unsupported("updateByte");
    }

    @Override
    public void updateBytes(String p0, byte[] p1) throws SQLException {
        throw unsupported("updateBytes");
    }

    @Override
    public void updateBytes(int p0, byte[] p1) throws SQLException {
        throw unsupported("updateBytes");
    }

    @Override
    public void updateCharacterStream(String p0, Reader p1) throws SQLException {
        throw unsupported("updateCharacterStream");
    }

    @Override
    public void updateCharacterStream(String p0, Reader p1, int p2) throws SQLException {
        throw unsupported("updateCharacterStream");
    }

    @Override
    public void updateCharacterStream(String p0, Reader p1, long p2) throws SQLException {
        throw unsupported("updateCharacterStream");
    }

    @Override
    public void updateCharacterStream(int p0, Reader p1) throws SQLException {
        throw unsupported("updateCharacterStream");
    }

    @Override
    public void updateCharacterStream(int p0, Reader p1, int p2) throws SQLException {
        throw unsupported("updateCharacterStream");
    }

    @Override
    public void updateCharacterStream(int p0, Reader p1, long p2) throws SQLException {
        throw unsupported("updateCharacterStream");
    }

    @Override
    public void updateClob(String p0, Clob p1) throws SQLException {
        throw unsupported("updateClob");
    }

    @Override
    public void updateClob(String p0, Reader p1) throws SQLException {
        throw unsupported("updateClob");
    }

    @Override
    public void updateClob(String p0, Reader p1, long p2) throws SQLException {
        throw unsupported("updateClob");
    }

    @Override
    public void updateClob(int p0, Clob p1) throws SQLException {
        throw unsupported("updateClob");
    }

    @Override
    public void updateClob(int p0, Reader p1) throws SQLException {
        throw unsupported("updateClob");
    }

    @Override
    public void updateClob(int p0, Reader p1, long p2) throws SQLException {
        throw unsupported("updateClob");
    }

    @Override
    public void updateDate(String p0, Date p1) throws SQLException {
        throw unsupported("updateDate");
    }

    @Override
    public void updateDate(int p0, Date p1) throws SQLException {
        throw unsupported("updateDate");
    }

    @Override
    public void updateDouble(String p0, double p1) throws SQLException {
        throw unsupported("updateDouble");
    }

    @Override
    public void updateDouble(int p0, double p1) throws SQLException {
        throw unsupported("updateDouble");
    }

    @Override
    public void updateFloat(String p0, float p1) throws SQLException {
        throw unsupported("updateFloat");
    }

    @Override
    public void updateFloat(int p0, float p1) throws SQLException {
        throw unsupported("updateFloat");
    }

    @Override
    public void updateInt(String p0, int p1) throws SQLException {
        throw unsupported("updateInt");
    }

    @Override
    public void updateInt(int p0, int p1) throws SQLException {
        throw unsupported("updateInt");
    }

    @Override
    public void updateLong(String p0, long p1) throws SQLException {
        throw unsupported("updateLong");
    }

    @Override
    public void updateLong(int p0, long p1) throws SQLException {
        throw unsupported("updateLong");
    }

    @Override
    public void updateNCharacterStream(String p0, Reader p1) throws SQLException {
        throw unsupported("updateNCharacterStream");
    }

    @Override
    public void updateNCharacterStream(String p0, Reader p1, long p2) throws SQLException {
        throw unsupported("updateNCharacterStream");
    }

    @Override
    public void updateNCharacterStream(int p0, Reader p1) throws SQLException {
        throw unsupported("updateNCharacterStream");
    }

    @Override
    public void updateNCharacterStream(int p0, Reader p1, long p2) throws SQLException {
        throw unsupported("updateNCharacterStream");
    }

    @Override
    public void updateNClob(String p0, NClob p1) throws SQLException {
        throw unsupported("updateNClob");
    }

    @Override
    public void updateNClob(String p0, Reader p1) throws SQLException {
        throw unsupported("updateNClob");
    }

    @Override
    public void updateNClob(String p0, Reader p1, long p2) throws SQLException {
        throw unsupported("updateNClob");
    }

    @Override
    public void updateNClob(int p0, NClob p1) throws SQLException {
        throw unsupported("updateNClob");
    }

    @Override
    public void updateNClob(int p0, Reader p1) throws SQLException {
        throw unsupported("updateNClob");
    }

    @Override
    public void updateNClob(int p0, Reader p1, long p2) throws SQLException {
        throw unsupported("updateNClob");
    }

    @Override
    public void updateNString(String p0, String p1) throws SQLException {
        throw unsupported("updateNString");
    }

    @Override
    public void updateNString(int p0, String p1) throws SQLException {
        throw unsupported("updateNString");
    }

    @Override
    public void updateNull(String p0) throws SQLException {
        throw unsupported("updateNull");
    }

    @Override
    public void updateNull(int p0) throws SQLException {
        throw unsupported("updateNull");
    }

    @Override
    public void updateObject(String p0, Object p1) throws SQLException {
        throw unsupported("updateObject");
    }

    @Override
    public void updateObject(String p0, Object p1, SQLType p2) throws SQLException {
        throw unsupported("updateObject");
    }

    @Override
    public void updateObject(String p0, Object p1, SQLType p2, int p3) throws SQLException {
        throw unsupported("updateObject");
    }

    @Override
    public void updateObject(String p0, Object p1, int p2) throws SQLException {
        throw unsupported("updateObject");
    }

    @Override
    public void updateObject(int p0, Object p1) throws SQLException {
        throw unsupported("updateObject");
    }

    @Override
    public void updateObject(int p0, Object p1, SQLType p2) throws SQLException {
        throw unsupported("updateObject");
    }

    @Override
    public void updateObject(int p0, Object p1, SQLType p2, int p3) throws SQLException {
        throw unsupported("updateObject");
    }

    @Override
    public void updateObject(int p0, Object p1, int p2) throws SQLException {
        throw unsupported("updateObject");
    }

    @Override
    public void updateRef(String p0, Ref p1) throws SQLException {
        throw unsupported("updateRef");
    }

    @Override
    public void updateRef(int p0, Ref p1) throws SQLException {
        throw unsupported("updateRef");
    }

    @Override
    public void updateRow() throws SQLException {
        throw unsupported("updateRow");
    }

    @Override
    public void updateRowId(String p0, RowId p1) throws SQLException {
        throw unsupported("updateRowId");
    }

    @Override
    public void updateRowId(int p0, RowId p1) throws SQLException {
        throw unsupported("updateRowId");
    }

    @Override
    public void updateSQLXML(String p0, SQLXML p1) throws SQLException {
        throw unsupported("updateSQLXML");
    }

    @Override
    public void updateSQLXML(int p0, SQLXML p1) throws SQLException {
        throw unsupported("updateSQLXML");
    }

    @Override
    public void updateShort(String p0, short p1) throws SQLException {
        throw unsupported("updateShort");
    }

    @Override
    public void updateShort(int p0, short p1) throws SQLException {
        throw unsupported("updateShort");
    }

    @Override
    public void updateString(String p0, String p1) throws SQLException {
        throw unsupported("updateString");
    }

    @Override
    public void updateString(int p0, String p1) throws SQLException {
        throw unsupported("updateString");
    }

    @Override
    public void updateTime(String p0, Time p1) throws SQLException {
        throw unsupported("updateTime");
    }

    @Override
    public void updateTime(int p0, Time p1) throws SQLException {
        throw unsupported("updateTime");
    }

    @Override
    public void updateTimestamp(String p0, Timestamp p1) throws SQLException {
        throw unsupported("updateTimestamp");
    }

    @Override
    public void updateTimestamp(int p0, Timestamp p1) throws SQLException {
        throw unsupported("updateTimestamp");
    }

    /** Metadata view exposing only column count and labels. */
    private static final class CachedRowMetaData implements ResultSetMetaData {

        private final String[] labels;

        private CachedRowMetaData(String[] labels) {
            this.labels = labels;
        }

        @Override
        public int getColumnCount() {
            return labels.length;
        }

        @Override
        public String getColumnLabel(int column) throws SQLException {
            return columnAt(column);
        }

        @Override
        public String getColumnName(int column) throws SQLException {
            return columnAt(column);
        }

        private String columnAt(int column) throws SQLException {
            if (column < 1 || column > labels.length) {
                throw new SQLException("The column index " + column + " is out of range.");
            }
            return labels[column - 1];
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Cannot unwrap to " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }

        @Override
        public boolean isAutoIncrement(int column) {
            throw unsupported("isAutoIncrement");
        }

        @Override
        public boolean isCaseSensitive(int column) {
            throw unsupported("isCaseSensitive");
        }

        @Override
        public boolean isSearchable(int column) {
            throw unsupported("isSearchable");
        }

        @Override
        public boolean isCurrency(int column) {
            throw unsupported("isCurrency");
        }

        @Override
        public int isNullable(int column) {
            throw unsupported("isNullable");
        }

        @Override
        public boolean isSigned(int column) {
            throw unsupported("isSigned");
        }

        @Override
        public int getColumnDisplaySize(int column) {
            throw unsupported("getColumnDisplaySize");
        }

        @Override
        public String getSchemaName(int column) {
            throw unsupported("getSchemaName");
        }

        @Override
        public int getPrecision(int column) {
            throw unsupported("getPrecision");
        }

        @Override
        public int getScale(int column) {
            throw unsupported("getScale");
        }

        @Override
        public String getTableName(int column) {
            throw unsupported("getTableName");
        }

        @Override
        public String getCatalogName(int column) {
            throw unsupported("getCatalogName");
        }

        @Override
        public int getColumnType(int column) {
            throw unsupported("getColumnType");
        }

        @Override
        public String getColumnTypeName(int column) {
            throw unsupported("getColumnTypeName");
        }

        @Override
        public boolean isReadOnly(int column) {
            throw unsupported("isReadOnly");
        }

        @Override
        public boolean isWritable(int column) {
            throw unsupported("isWritable");
        }

        @Override
        public boolean isDefinitelyWritable(int column) {
            throw unsupported("isDefinitelyWritable");
        }

        @Override
        public String getColumnClassName(int column) {
            throw unsupported("getColumnClassName");
        }
    }

    /**
     * Eagerly materialized {@link Array} replacement. The decoded content is
     * captured once at read time, so no connection is retained and
     * {@link #free()} has nothing to release.
     */
    private static final class CachedArray implements Array {

        private final Object content;

        private CachedArray(Object content) {
            this.content = content;
        }

        @Override
        public Object getArray() {
            return content;
        }

        @Override
        public void free() {
            // content is heap-only, nothing to release
        }

        @Override
        public String getBaseTypeName() {
            throw unsupported("Array.getBaseTypeName");
        }

        @Override
        public int getBaseType() {
            throw unsupported("Array.getBaseType");
        }

        @Override
        public Object getArray(Map<String, Class<?>> map) {
            throw unsupported("Array.getArray(Map)");
        }

        @Override
        public Object getArray(long index, int count) {
            throw unsupported("Array.getArray(long, int)");
        }

        @Override
        public Object getArray(long index, int count, Map<String, Class<?>> map) {
            throw unsupported("Array.getArray(long, int, Map)");
        }

        @Override
        public ResultSet getResultSet() {
            throw unsupported("Array.getResultSet");
        }

        @Override
        public ResultSet getResultSet(Map<String, Class<?>> map) {
            throw unsupported("Array.getResultSet(Map)");
        }

        @Override
        public ResultSet getResultSet(long index, int count) {
            throw unsupported("Array.getResultSet(long, int)");
        }

        @Override
        public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) {
            throw unsupported("Array.getResultSet(long, int, Map)");
        }
    }
}
