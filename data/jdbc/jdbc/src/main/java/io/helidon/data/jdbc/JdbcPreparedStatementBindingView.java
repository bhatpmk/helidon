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
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;

/**
 * A <dfn>view</dfn> of a {@link PreparedStatement}, representing it as a target for argument binding.
 *
 * <p>Each method in this interface corresponds to a binding-related <dfn>mutator method</dfn> in the <a
 * href="https://docs.oracle.com/en/java/javase/26/docs/api/java.sql/java/sql/package-summary.html">JDBC 4.5</a> version
 * of the {@link PreparedStatement} interface.</p>
 *
 * <p>As with all constructs related to JDBC, a {@link JdbcPreparedStatementBindingView} is not necessarily safe for
 * concurrent use by multiple threads unless explicitly noted.</p>
 *
 * @see PreparedStatement
 */
public interface JdbcPreparedStatementBindingView {

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#addBatch()} method.
     *
     * @throws SQLException if a database access error occurs or this method is called on a closed {@link
     * PreparedStatement}
     */
    void addBatch() throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#clearParameters()} method.
     *
     * @throws SQLException if a database access error occurs or this method is called on a closed {@link
     * PreparedStatement}
     */
    void clearParameters() throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setNull(int, int)} method with the supplied
     * arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param sqlType the SQL type code defined in {@link java.sql.Types}
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if {@code sqlType} is a {@code ARRAY}, {@code BLOB}, {@code
     * CLOB}, {@code DATALINK}, {@code JAVA_OBJECT}, {@code NCHAR}, {@code NCLOB}, {@code NVARCHAR}, {@code
     * LONGNVARCHAR}, {@code REF}, {@code ROWID}, {@code SQLXML} or {@code STRUCT} data type and the JDBC driver does
     * not support this data type
     */
    void setNull(int parameterIndex, int sqlType) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setBoolean(int, boolean)} method with
     * the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setBoolean(int parameterIndex, boolean x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setByte(int, byte)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setByte(int parameterIndex, byte x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setShort(int, short)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setShort(int parameterIndex, short x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setInt(int, int)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setInt(int parameterIndex, int x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setLong(int, long)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setLong(int parameterIndex, long x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setFloat(int, float)} method with the supplied
     * arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setFloat(int parameterIndex, float x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setDouble(int, double)} method with
     * the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setDouble(int parameterIndex, double x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setBigDecimal(int, BigDecimal)}
     * method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setString(int, String)} method with
     * the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setString(int parameterIndex, String x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setBytes(int, byte[])} method with
     * the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setBytes(int parameterIndex, byte[] x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setDate(int, Date)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setDate(int parameterIndex, Date x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setTime(int, Time)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setTime(int parameterIndex, Time x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setTimestamp(int, Timestamp)} method
     * with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setTimestamp(int parameterIndex, Timestamp x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setAsciiStream(int, InputStream,
     * int)} method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the Java input stream that contains the ASCII parameter value
     * @param length the number of bytes in the stream
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setBinaryStream(int, InputStream,
     * int)} method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @param length the number of bytes in the stream
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setObject(int, Object, int)} method
     * with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the object containing the input parameter value
     * @param targetSqlType the SQL type (as defined in java.sql.Types) to be sent to the database
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed PreparedStatement
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support the specified targetSqlType
     * @see java.sql.Types
     */
    void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setObject(int, Object)} method with
     * the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the object containing the input parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs; this method is called on a closed {@link PreparedStatement} or the type of the
     * given object is ambiguous
     */
    void setObject(int parameterIndex, Object x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setCharacterStream(int, Reader, int)}
     * method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param reader the {@link Reader} object that contains the Unicode data
     * @param length the number of characters in the stream
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setRef(int, Ref)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x an SQL {@code REF} value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setRef(int parameterIndex, Ref x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setBlob(int, Blob)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x a {@link Blob} object that maps an SQL {@code BLOB} value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setBlob(int parameterIndex, Blob x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setClob(int, Clob)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x a {@link Clob} object that maps an SQL {@code CLOB} value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setClob(int parameterIndex, Clob x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setArray(int, Array)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x an {@link Array} object that maps an SQL {@code ARRAY} value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setArray(int parameterIndex, Array x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setDate(int, Date, Calendar)} method
     * with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @param cal the {@link Calendar} object the driver will use to construct the date
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setTime(int, Time, Calendar)} method
     * with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @param cal the {@link Calendar} object the driver will use to construct the time
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setTimestamp(int, Timestamp,
     * Calendar)} method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @param cal the {@link Calendar} object the driver will use to construct the timestamp
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setNull(int, int, String)} method
     * with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param sqlType a value from {@link java.sql.Types}
     * @param typeName the fully-qualified name of an SQL user-defined type; ignored if the parameter is not a
     *  user-defined type or REF
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if {@code sqlType} is a {@code ARRAY}, {@code BLOB}, {@code
     * CLOB}, {@code DATALINK}, {@code JAVA_OBJECT}, {@code NCHAR}, {@code NCLOB}, {@code NVARCHAR}, {@code
     * LONGNVARCHAR}, {@code REF}, {@code ROWID}, {@code SQLXML} or {@code STRUCT} data type and the JDBC driver does
     * not support this data type or if the JDBC driver does not support this method
     */
    void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setURL(int, URL)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the {@link URL} object to be set
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setURL(int parameterIndex, URL x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setRowId(int, RowId)} method with the
     * supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setRowId(int parameterIndex, RowId x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setNString(int, String)} method with
     * the supplied arguments.
     *
     * @param parameterIndex of the first parameter is 1, the second is 2, ...
     * @param value the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if the
     * driver does not support national character sets; if the driver can detect that a data conversion error could
     * occur; if a database access error occurs; or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setNString(int parameterIndex, String value) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setNCharacterStream(int, Reader,
     * long)} method with the supplied arguments.
     *
     * @param parameterIndex of the first parameter is 1, the second is 2, ...
     * @param value the parameter value
     * @param length the number of characters in the parameter data.
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if the
     * driver does not support national character sets; if the driver can detect that a data conversion error could
     * occur; if a database access error occurs; or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setNClob(int, NClob)} method with the
     * supplied arguments.
     *
     * @param parameterIndex of the first parameter is 1, the second is 2, ...
     * @param value the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if the
     * driver does not support national character sets; if the driver can detect that a data conversion error could
     * occur; if a database access error occurs; or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setNClob(int parameterIndex, NClob value) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setClob(int, Reader, long)} method
     * with the supplied arguments.
     *
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param reader An object that contains the data to set the parameter value to.
     * @param length the number of characters in the parameter data.
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs; this method is called on a closed {@link PreparedStatement} or if the length
     * specified is less than zero.
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setClob(int parameterIndex, Reader reader, long length) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setBlob(int, InputStream, long)}
     * method with the supplied arguments.
     *
     * @param parameterIndex index of the first parameter is 1,
     * the second is 2, ...
     * @param inputStream An object that contains the data to set the parameter value to.
     * @param length the number of bytes in the parameter data.
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs; this method is called on a closed {@link PreparedStatement}; if the length
     * specified is less than zero or if the number of bytes in the {@link InputStream} does not match the specified
     * length.
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setNClob(int, Reader, long)} method
     * with the supplied arguments.
     *
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param reader An object that contains the data to set the parameter value to.
     * @param length the number of characters in the parameter data.
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if the
     * length specified is less than zero; if the driver does not support national character sets; if the driver can
     * detect that a data conversion error could occur; if a database access error occurs or this method is called on a
     * closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setNClob(int parameterIndex, Reader reader, long length) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setSQLXML(int, SQLXML)} method with
     * the supplied arguments.
     *
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param xmlObject a {@link SQLXML} object that maps an SQL {@code XML} value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs; this method is called on a closed {@link PreparedStatement} or the {@code
     * java.xml.transform.Result}, {@link java.io.Writer} or {@link java.io.OutputStream} has not been closed for the
     * {@link SQLXML} object
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setObject(int, Object, int, int)}
     * method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the object containing the input parameter value
     * @param targetSqlType the SQL type (as defined in java.sql.Types) to be sent to the database. The scale argument
     * may further qualify this type.
     * @param scaleOrLength for {@link java.sql.Types#DECIMAL} or {@link java.sql.Types#NUMERIC} types, this is the
     * number of digits after the decimal point. For Java Object types {@link InputStream} and {@link Reader}, this is
     * the length of the data in the stream or reader.  For all other types, this value will be ignored.
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs; this method is called on a closed {@link PreparedStatement} or if the Java Object
     * specified by x is an InputStream or Reader object and the value of the scale parameter is less than zero
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support the specified targetSqlType
     * @see java.sql.Types
     */
    void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setAsciiStream(int, InputStream,
     * long)} method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the Java input stream that contains the ASCII parameter value
     * @param length the number of bytes in the stream
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setBinaryStream(int, InputStream,
     * long)} method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @param length the number of bytes in the stream
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setCharacterStream(int, Reader,
     * long)} method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param reader the {@link Reader} object that contains the Unicode data
     * @param length the number of characters in the stream
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     */
    void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setAsciiStream(int, InputStream)}
     * method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the Java input stream that contains the ASCII parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setAsciiStream(int parameterIndex, InputStream x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setBinaryStream(int, InputStream)}
     * method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter
     * marker in the SQL statement; if a database access error occurs or
     * this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     */
    void setBinaryStream(int parameterIndex, InputStream x) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setCharacterStream(int, Reader)}
     * method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param reader the {@link Reader} object that contains the Unicode data
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     */
    void setCharacterStream(int parameterIndex, Reader reader) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setNCharacterStream(int, Reader)}
     * method with the supplied arguments.
     *
     * @param parameterIndex of the first parameter is 1, the second is 2, ...
     * @param value the parameter value
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if the
     * driver does not support national character sets; if the driver can detect that a data conversion error could
     * occur; if a database access error occurs; or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setNCharacterStream(int parameterIndex, Reader value) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setClob(int, Reader)} method with the
     * supplied arguments.
     *
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param reader An object that contains the data to set the parameter value to.
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs; this method is called on a closed {@link PreparedStatement}or if parameterIndex
     * does not correspond to a parameter marker in the SQL statement
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setClob(int parameterIndex, Reader reader) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setBlob(int, InputStream)} method
     * with the supplied arguments.
     *
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param inputStream An object that contains the data to set the parameter value to.
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs; this method is called on a closed {@link PreparedStatement} or if parameterIndex
     * does not correspond to a parameter marker in the SQL statement,
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setBlob(int parameterIndex, InputStream inputStream) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setNClob(int, Reader)} method with
     * the supplied arguments.
     *
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param reader An object that contains the data to set the parameter value to.
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if the
     * driver does not support national character sets; if the driver can detect that a data conversion error could
     * occur; if a database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    void setNClob(int parameterIndex, Reader reader) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setObject(int, Object, SQLType, int)}
     * method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the object containing the input parameter value
     * @param targetSqlType the SQL type to be sent to the database. The scale argument may further qualify this type.
     * @param scaleOrLength for {@code java.sql.JDBCType.DECIMAL} or {@code java.sql.JDBCType.NUMERIC types}, this is
     * the number of digits after the decimal point. For Java Object types {@link InputStream} and {@link Reader}, this
     * is the length of the data in the stream or reader.  For all other types, this value will be ignored.
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement} or if the Java Object
     * specified by x is an InputStream or Reader object and the value of the scale parameter is less than zero
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support the specified targetSqlType
     * @see java.sql.JDBCType
     * @see SQLType
     */
    void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#setObject(int, Object, SQLType)}
     * method with the supplied arguments.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the object containing the input parameter value
     * @param targetSqlType the SQL type to be sent to the database
     * @throws SQLException if parameterIndex does not correspond to a parameter marker in the SQL statement; if a
     * database access error occurs or this method is called on a closed {@link PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support the specified targetSqlType
     * @see java.sql.JDBCType
     * @see SQLType
     */
    void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#getMetaData()} method and returns the
     * result.
     *
     * @return the description of a {@link java.sql.ResultSet} object's columns or {@code null} if the driver cannot
     * return a {@link ResultSetMetaData} object
     * @throws SQLException if a database access error occurs or this method is called on a closed {@link
     * PreparedStatement}
     * @throws java.sql.SQLFeatureNotSupportedException if the JDBC driver does not support this method
     */
    ResultSetMetaData getMetaData() throws SQLException;

    /**
     * Invokes the underlying {@link PreparedStatement}'s {@link PreparedStatement#getParameterMetaData()} method and
     * returns the result.
     *
     * @return a {@link ParameterMetaData} object that contains information about the number, types and properties for
     * each parameter marker of this {@link PreparedStatement} object
     * @throws SQLException if a database access error occurs or this method is called on a closed {@link
     * PreparedStatement}
     */
    ParameterMetaData getParameterMetaData() throws SQLException;

}
