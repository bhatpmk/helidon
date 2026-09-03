/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
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
 */
package io.helidon.data.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

import static java.util.Objects.requireNonNull;

final class JdbcResultSetRowViewImpl implements JdbcResultSetRowView {

    private final ResultSet rs;

    JdbcResultSetRowViewImpl(ResultSet rs) {
        this.rs = requireNonNull(rs, "rs");
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        return this.rs.findColumn(columnLabel);
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        return this.rs.getArray(columnIndex);
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return this.rs.getArray(columnLabel);
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        return this.rs.getAsciiStream(columnIndex);
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return this.rs.getAsciiStream(columnLabel);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return this.rs.getBigDecimal(columnIndex);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return this.rs.getBigDecimal(columnLabel);
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        return this.rs.getBinaryStream(columnIndex);
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return this.rs.getBinaryStream(columnLabel);
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        return this.rs.getBlob(columnIndex);
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return this.rs.getBlob(columnLabel);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return this.rs.getBoolean(columnIndex);
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return this.rs.getBoolean(columnLabel);
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return this.rs.getByte(columnIndex);
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return this.rs.getByte(columnLabel);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return this.rs.getBytes(columnIndex);
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return this.rs.getBytes(columnLabel);
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        return this.rs.getCharacterStream(columnIndex);
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return this.rs.getCharacterStream(columnLabel);
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        return this.rs.getClob(columnIndex);
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return this.rs.getClob(columnLabel);
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return this.rs.getDate(columnIndex);
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return this.rs.getDate(columnLabel);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return this.rs.getDouble(columnIndex);
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return this.rs.getDouble(columnLabel);
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return this.rs.getFloat(columnIndex);
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return this.rs.getFloat(columnLabel);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return this.rs.getInt(columnIndex);
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return this.rs.getInt(columnLabel);
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return this.rs.getLong(columnIndex);
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return this.rs.getLong(columnLabel);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return this.rs.getMetaData();
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return this.rs.getNCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return this.rs.getNCharacterStream(columnLabel);
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        return this.rs.getNClob(columnIndex);
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return this.rs.getNClob(columnLabel);
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return this.rs.getNString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return this.rs.getNString(columnLabel);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return this.rs.getObject(columnIndex);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        return this.rs.getObject(columnIndex, type);
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return this.rs.getObject(columnIndex, map);
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return this.rs.getObject(columnLabel);
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return this.rs.getObject(columnLabel, type);
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return this.rs.getObject(columnLabel, map);
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        return this.rs.getRef(columnIndex);
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return this.rs.getRef(columnLabel);
    }

    @Override
    public int getRow() throws SQLException {
        return this.rs.getRow();
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        return this.rs.getRowId(columnIndex);
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return this.rs.getRowId(columnLabel);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return this.rs.getShort(columnIndex);
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return this.rs.getShort(columnLabel);
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        return this.rs.getSQLXML(columnIndex);
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return this.rs.getSQLXML(columnLabel);
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        return this.rs.getString(columnIndex);
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return this.rs.getString(columnLabel);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return this.rs.getTime(columnIndex);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        return this.rs.getTime(columnIndex, cal);
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return this.rs.getTime(columnLabel);
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return this.rs.getTime(columnLabel, cal);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return this.rs.getTimestamp(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        return this.rs.getTimestamp(columnIndex, cal);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return this.rs.getTimestamp(columnLabel);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return this.rs.getTimestamp(columnLabel, cal);
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        return this.rs.getURL(columnIndex);
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return this.rs.getURL(columnLabel);
    }

    @Override
    public String toString() {
        return this.rs.toString();
    }

    @Override
    public boolean wasNull() throws SQLException {
        return this.rs.wasNull();
    }

}
