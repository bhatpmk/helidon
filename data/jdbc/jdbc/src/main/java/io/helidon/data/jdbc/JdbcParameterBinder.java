/*
 * Copyright (c) 2019, 2026 Oracle and/or its affiliates.
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

import java.io.ByteArrayInputStream;
import java.io.CharArrayReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.UUID;

/**
 * Binds Java values to JDBC prepared statement parameters.
 * <p>
 * This is adapted from {@code io.helidon.dbclient.jdbc.JdbcStatement#setParameter}. It is duplicated here so the
 * Data JDBC runtime can stay independent from the DbClient module while reusing the same practical JDBC type
 * handling decisions.
 */
final class JdbcParameterBinder {

    private JdbcParameterBinder() {
    }

    static void bind(PreparedStatement statement,
                     int index,
                     Object parameter,
                     JdbcParametersConfig parametersConfig) throws SQLException {
        if (parameter instanceof String s) {
            if (parametersConfig.useStringBinding() && s.length() > parametersConfig.stringBindingSize()) {
                CharArrayReader reader = new CharArrayReader(s.toCharArray());
                statement.setCharacterStream(index, reader, s.length());
            } else if (parametersConfig.useNString()) {
                statement.setNString(index, s);
            } else {
                statement.setString(index, s);
            }
        } else if (parameter instanceof Number number) {
            bindNumber(statement, index, number);
        } else if (parameter instanceof java.sql.Date d) {
            statement.setDate(index, d);
        } else if (parameter instanceof java.sql.Time t) {
            statement.setTime(index, t);
        } else if (parameter instanceof java.sql.Timestamp ts) {
            statement.setTimestamp(index, ts);
        } else if (parameter instanceof java.time.LocalDate ld) {
            if (parametersConfig.setObjectForJavaTime()) {
                statement.setObject(index, ld);
            } else {
                statement.setDate(index, java.sql.Date.valueOf(ld));
            }
        } else if (parameter instanceof java.time.LocalDateTime ldt) {
            if (parametersConfig.setObjectForJavaTime()) {
                statement.setObject(index, ldt);
            } else {
                statement.setTimestamp(index, Timestamp.valueOf(ldt));
            }
        } else if (parameter instanceof java.time.OffsetDateTime odt) {
            if (parametersConfig.setObjectForJavaTime()) {
                statement.setObject(index, odt);
            } else {
                statement.setTimestamp(index, Timestamp.from(odt.toInstant()));
            }
        } else if (parameter instanceof java.time.LocalTime lt) {
            if (parametersConfig.setObjectForJavaTime()) {
                statement.setObject(index, lt);
            } else if (parametersConfig.timestampForLocalTime()) {
                statement.setTimestamp(index,
                                       Timestamp.valueOf(java.time.LocalDateTime.of(java.time.LocalDate.ofEpochDay(0), lt)));
            } else {
                statement.setTime(index, Time.valueOf(lt));
            }
        } else if (parameter instanceof java.time.OffsetTime ot) {
            if (parametersConfig.setObjectForJavaTime()) {
                statement.setObject(index, ot);
            } else {
                statement.setTimestamp(index,
                                       Timestamp.valueOf(java.time.LocalDateTime.of(java.time.LocalDate.ofEpochDay(0),
                                                                                    ot.toLocalTime())));
            }
        } else if (parameter instanceof Boolean b) {
            statement.setBoolean(index, b);
        } else if (parameter == null) {
            statement.setNull(index, Types.NULL);
        } else if (parameter instanceof byte[] b) {
            if (parametersConfig.useByteArrayBinding()) {
                ByteArrayInputStream inputStream = new ByteArrayInputStream(b);
                statement.setBinaryStream(index, inputStream, b.length);
            } else {
                statement.setBytes(index, b);
            }
        } else if (parameter instanceof Calendar c) {
            statement.setTimestamp(index, timestampFromDate(c.getTime()));
        } else if (parameter instanceof java.util.Date d) {
            statement.setTimestamp(index, timestampFromDate(d));
        } else if (parameter instanceof Character c) {
            statement.setString(index, String.valueOf(c));
        } else if (parameter instanceof char[] c) {
            statement.setString(index, new String(c));
        } else if (parameter instanceof Character[] c) {
            statement.setString(index, String.valueOf(characterArrayToCharArray(c)));
        } else if (parameter instanceof Byte[] b) {
            statement.setBytes(index, byteArrayToByteArray(b));
        } else if (parameter instanceof SQLXML s) {
            statement.setSQLXML(index, s);
        } else if (parameter instanceof UUID uuid) {
            statement.setString(index, uuid.toString());
        } else {
            statement.setObject(index, parameter);
        }
    }

    private static void bindNumber(PreparedStatement statement, int index, Number number) throws SQLException {
        if (number instanceof Integer i) {
            statement.setInt(index, i);
        } else if (number instanceof Long l) {
            statement.setLong(index, l);
        } else if (number instanceof BigDecimal bd) {
            statement.setBigDecimal(index, bd);
        } else if (number instanceof Double d) {
            statement.setDouble(index, d);
        } else if (number instanceof Float f) {
            statement.setFloat(index, f);
        } else if (number instanceof Short s) {
            statement.setShort(index, s);
        } else if (number instanceof Byte b) {
            statement.setByte(index, b);
        } else if (number instanceof BigInteger bi) {
            statement.setBigDecimal(index, new BigDecimal(bi));
        } else {
            statement.setObject(index, number);
        }
    }

    private static java.sql.Timestamp timestampFromLong(long millis) {
        java.sql.Timestamp timestamp = new java.sql.Timestamp(millis);

        if ((millis % 1000) > 0) {
            timestamp.setNanos((int) (millis % 1000) * 1000000);
        } else if ((millis % 1000) < 0) {
            timestamp.setNanos((int) (1000000000 - (Math.abs((millis % 1000) * 1000000))));
        }
        return timestamp;
    }

    private static java.sql.Timestamp timestampFromDate(java.util.Date date) {
        return timestampFromLong(date.getTime());
    }

    private static char[] characterArrayToCharArray(Character[] source) {
        char[] chars = new char[source.length];
        for (int i = 0; i < source.length; i++) {
            chars[i] = source[i];
        }
        return chars;
    }

    private static byte[] byteArrayToByteArray(Byte[] source) {
        byte[] bytes = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            Byte value = source[i];
            if (value != null) {
                bytes[i] = value;
            }
        }
        return bytes;
    }
}
