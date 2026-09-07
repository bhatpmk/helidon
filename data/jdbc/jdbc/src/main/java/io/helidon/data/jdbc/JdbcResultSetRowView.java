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

/**
 * A <dfn>view</dfn> of a {@link ResultSet}, representing it only as an <dfn>unmodifiable row</dfn>.
 *
 * <p>Each method in this interface corresponds to a row-related <dfn>accessor method</dfn> in the <a
 * href="https://docs.oracle.com/en/java/javase/26/docs/api/java.sql/java/sql/package-summary.html">JDBC 4.5</a> version
 * of the {@link ResultSet} interface.</p>
 *
 * <p>{@link JdbcResultSetRowView} instances are <dfn>unmodifiable</dfn>, but may not be <dfn>immutable</dfn>.</p>
 *
 * <p>As with all constructs related to JDBC, a {@link JdbcResultSetRowView} is not necessarily safe for concurrent use by
 * multiple threads unless explicitly noted.</p>
 *
 * @see ResultSet
 * @see ResultSet#TYPE_SCROLL_SENSITIVE
 */
public sealed interface JdbcResultSetRowView permits JdbcResultSetRowViewImpl {

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #findColumn(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the index of the first column with the supplied label
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#findColumn(String)
     */
    int findColumn(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getArray(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return an {@link Array} object representing the SQL {@code ARRAY} value in the specified column
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getArray(int)
     */
    Array getArray(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getArray(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return an {@link Array} object representing the SQL {@code ARRAY} value in the specified column
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getArray(String)
     */
    Array getArray(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getAsciiStream(int)} method with the supplied argument and
     * returns the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a Java {@link InputStream} that delivers the database column value as a stream of one-byte ASCII
     * characters
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getAsciiStream(int)
     */
    InputStream getAsciiStream(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getAsciiStream(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return a Java {@link InputStream} that delivers the database column value as a stream of one-byte ASCII
     * characters
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getAsciiStream(String)
     */
    InputStream getAsciiStream(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getBigDecimal(int)} method with the supplied argument and
     * returns the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value as a {@link BigDecimal}, or {@code null} if the value is SQL {@code NULL}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getBigDecimal(int)
     */
    BigDecimal getBigDecimal(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getBigDecimal(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value as a {@link BigDecimal}, or {@code null} if the value is SQL {@code NULL}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getBigDecimal(String)
     */
    BigDecimal getBigDecimal(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getBinaryStream(int)} method with the supplied argument and
     * returns the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a Java {@link InputStream} that delivers the database column value as a stream of uninterpreted bytes
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getBinaryStream(int)
     */
    InputStream getBinaryStream(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getBinaryStream(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return a Java {@link InputStream} that delivers the database column value as a stream of uninterpreted bytes
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getBinaryStream(String)
     */
    InputStream getBinaryStream(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getBlob(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link Blob} object representing the SQL {@code BLOB} value in the specified column
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getBlob(int)
     */
    Blob getBlob(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getBlob(String)} method with the supplied argument and returns
     * the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return a {@link Blob} object representing the SQL {@code BLOB} value in the specified column
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getBlob(String)
     */
    Blob getBlob(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getBoolean(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value, or {@code false} if the value is SQL {@code NULL}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getBoolean(int)
     */
    boolean getBoolean(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getBoolean(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value, or {@code false} if the value is SQL {@code NULL}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getBoolean(String)
     */
    boolean getBoolean(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getByte(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value, or {@code 0} if the value is SQL {@code NULL}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getByte(int)
     */
    byte getByte(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getByte(String)} method with the supplied argument and returns
     * the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code 0}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getByte(String)
     */
    byte getByte(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getBytes(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getBytes(int)
     */
    byte[] getBytes(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getBytes(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getBytes(String)
     */
    byte[] getBytes(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getCharacterStream(int)} method with the supplied argument and
     * returns the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link Reader} object representing the column value; if the value is SQL {@code NULL}, the value
     * returned is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getCharacterStream(int)
     */
    Reader getCharacterStream(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getCharacterStream(String)} method with the supplied argument
     * and returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return a {@link Reader} object representing the column value; if the value is SQL {@code NULL}, the value
     * returned is {@code null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getCharacterStream(String)
     */
    Reader getCharacterStream(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getClob(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link Clob} object representing the SQL {@code CLOB} value in the specified column
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getClob(int)
     */
    Clob getClob(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getClob(String)} method with the supplied argument and returns
     * the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return a {@link Clob} object representing the SQL {@code CLOB} value in the specified column
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getClob(String)
     */
    Clob getClob(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getDate(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value as a {@link Date}; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getDate(int)
     */
    Date getDate(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getDate(String)} method with the supplied argument and returns
     * the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value as a {@link Date}; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getDate(String)
     */
    Date getDate(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getDouble(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code 0}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getDouble(int)
     */
    double getDouble(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getDouble(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code 0}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getDouble(String)
     */
    double getDouble(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getFloat(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code 0}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getFloat(int)
     */
    float getFloat(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getFloat(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code 0}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getFloat(String)
     */
    float getFloat(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getInt(int)} method with the supplied argument and returns the
     * result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code 0}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getInt(int)
     */
    int getInt(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getInt(String)} method with the supplied argument and returns
     * the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code 0}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getInt(String)
     */
    int getInt(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getLong(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code 0}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getLong(int)
     */
    long getLong(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getLong(String)} method with the supplied argument and returns
     * the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code 0}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getLong(String)
     */
    long getLong(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getMetaData()} method and returns the result.
     *
     * @return the description of the underlying {@link ResultSet} object's columns
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     * @see ResultSet#getMetaData()
     */
    ResultSetMetaData getMetaData() throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getNCharacterStream(int)} method with the supplied argument
     * and returns the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link Reader} object that delivers the column value as a stream of national characters; if the value
     * is SQL {@code NULL}, the value returned is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getNCharacterStream(int)
     */
    Reader getNCharacterStream(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getNCharacterStream(String)} method with the supplied argument
     * and returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return a {@link Reader} object that delivers the column value as a stream of national characters; if the value
     * is SQL {@code NULL}, the value returned is {@code null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getNCharacterStream(String)
     */
    Reader getNCharacterStream(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getNClob(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return an {@link NClob} object representing the SQL {@code NCLOB} value in the specified column
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getNClob(int)
     */
    NClob getNClob(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getNClob(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return an {@link NClob} object representing the SQL {@code NCLOB} value in the specified column
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getNClob(String)
     */
    NClob getNClob(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getNString(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getNString(int)
     */
    String getNString(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getNString(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getNString(String)
     */
    String getNString(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getObject(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link Object} holding the column value
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getObject(int)
     */
    Object getObject(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getObject(int, Class)} method with the supplied arguments and
     * returns the result.
     *
     * @param <T> the type of object representing the column value
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param type the Java type to convert the designated column to
     * @return an instance of {@code type} representing the column value; if the value is SQL {@code NULL}, the value
     * returned is {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getObject(int, Class)
     */
    <T> T getObject(int columnIndex, Class<T> type) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getObject(int, Map)} method with the supplied arguments and
     * returns the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param map a {@link Map} of SQL type names to Java classes
     * @return a {@link Object} representing the SQL value in the specified column
     * @throws NullPointerException if {@code map} is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getObject(int, Map)
     */
    Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getObject(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return a {@link Object} holding the column value
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getObject(String)
     */
    Object getObject(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getObject(String, Class)} method with the supplied arguments
     * and returns the result.
     *
     * @param <T> the type of object representing the column value
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @param type the Java type to convert the designated column to
     * @return an instance of {@code type} representing the column value; if the value is SQL {@code NULL}, the value
     * returned is {@code null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws NullPointerException if {@code type} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getObject(String, Class)
     */
    <T> T getObject(String columnLabel, Class<T> type) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getObject(String, Map)} method with the supplied arguments and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @param map a {@link Map} of SQL type names to Java classes
     * @return a {@link Object} representing the SQL value in the specified column
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws NullPointerException if {@code map} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getObject(String, Map)
     */
    Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getRef(int)} method with the supplied argument and returns the
     * result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return a {@link Ref} object representing the SQL {@code REF} value in the specified column
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getRef(int)
     */
    Ref getRef(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getRef(String)} method with the supplied argument and returns
     * the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return a {@link Ref} object representing the SQL {@code REF} value in the specified column
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getRef(String)
     */
    Ref getRef(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getRow()} method and returns the result.
     *
     * @return the current row number; {@code 0} if there is no current row
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getRow()
     */
    int getRow() throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getRowId(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the {@link RowId} object representing the SQL {@code ROWID} value in the specified column; if the value
     * is SQL {@code NULL}, the value returned is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getRowId(int)
     */
    RowId getRowId(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getRowId(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the {@link RowId} object representing the SQL {@code ROWID} value in the specified column; if the value
     * is SQL {@code NULL}, the value returned is {@code null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getRowId(String)
     */
    RowId getRowId(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getShort(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code 0}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getShort(int)
     */
    short getShort(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getShort(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code 0}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getShort(String)
     */
    short getShort(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getSQLXML(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return an {@link SQLXML} object representing the SQL XML value in the specified column
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getSQLXML(int)
     */
    SQLXML getSQLXML(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getSQLXML(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return an {@link SQLXML} object representing the SQL XML value in the specified column
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getSQLXML(String)
     */
    SQLXML getSQLXML(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getString(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getString(int)
     */
    String getString(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getString(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getString(String)
     */
    String getString(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getTime(int)} method with the supplied argument and returns
     * the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value as a {@link Time}; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getTime(int)
     */
    Time getTime(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getTime(int, Calendar)} method with the supplied arguments and
     * returns the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param cal the {@link Calendar} to use in constructing the return value
     * @return the column value as a {@link Time}; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws NullPointerException if {@code cal} is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getTime(int, Calendar)
     */
    Time getTime(int columnIndex, Calendar cal) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getTime(String)} method with the supplied argument and returns
     * the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value as a {@link Time}; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getTime(String)
     */
    Time getTime(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getTime(String, Calendar)} method with the supplied arguments
     * and returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @param cal the {@link Calendar} to use in constructing the return value
     * @return the column value as a {@link Time}; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws NullPointerException if {@code cal} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getTime(String, Calendar)
     */
    Time getTime(String columnLabel, Calendar cal) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getTimestamp(int)} method with the supplied argument and
     * returns the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value as a {@link Timestamp}; if the value is SQL {@code NULL}, the value returned is {@code
     * null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getTimestamp(int)
     */
    Timestamp getTimestamp(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getTimestamp(int, Calendar)} method with the supplied
     * arguments and returns the result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param cal the {@link Calendar} to use in constructing the return value
     * @return the column value as a {@link Timestamp}; if the value is SQL {@code NULL}, the value returned is {@code
     * null}
     * @throws NullPointerException if {@code cal} is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getTimestamp(int, Calendar)
     */
    Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getTimestamp(String)} method with the supplied argument and
     * returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value as a {@link Timestamp}; if the value is SQL {@code NULL}, the value returned is {@code
     * null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getTimestamp(String)
     */
    Timestamp getTimestamp(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getTimestamp(String, Calendar)} method with the supplied
     * arguments and returns the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @param cal the {@link Calendar} to use in constructing the return value
     * @return the column value as a {@link Timestamp}; if the value is SQL {@code NULL}, the value returned is {@code
     * null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws NullPointerException if {@code cal} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getTimestamp(String, Calendar)
     */
    Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getURL(int)} method with the supplied argument and returns the
     * result.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @return the column value as a {@link URL}; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws SQLException if the {@code columnIndex} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getURL(int)
     */
    URL getURL(int columnIndex) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #getURL(String)} method with the supplied argument and returns
     * the result.
     *
     * @param columnLabel the label for the column specified with the SQL {@code AS} clause. If the SQL {@code AS}
     * clause was not specified, then the label is the name of the column
     * @return the column value as a {@link URL}; if the value is SQL {@code NULL}, the value returned is {@code null}
     * @throws NullPointerException if {@code columnLabel} is {@code null}
     * @throws SQLException if the {@code columnLabel} is not valid; if a database access error occurs or this method is
     * called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#getURL(String)
     */
    URL getURL(String columnLabel) throws SQLException;

    /**
     * Invokes the underlying {@link ResultSet}'s {@link #wasNull()} method and returns the result.
     *
     * @return the status of the last column read; {@code true} if it was SQL {@code NULL}; {@code false} otherwise
     * @throws SQLException if a database access error occurs or this method is called on a closed result set
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     * @see ResultSet#wasNull()
     */
    boolean wasNull() throws SQLException;

    /**
     * Returns a non-{@code null} {@link JdbcResultSetRowView} view of the current row of the supplied {@link ResultSet}.
     *
     * <p>Operations on the returned {@link JdbcResultSetRowView} may fail if there is no current row (for example, if
     * an invocation of the {@link ResultSet#next() next()} method on the supplied {@link ResultSet} has returned {@code
     * false}).</p>
     *
     * @param rs the {@link ResultSet} to wrap
     * @return a non-{@code null} {@link JdbcResultSetRowView} view of the supplied {@link ResultSet}
     * @throws NullPointerException if {@code rs} is {@code null}
     */
    static JdbcResultSetRowView of(ResultSet rs) {
        return new JdbcResultSetRowViewImpl(rs);
    }

}
