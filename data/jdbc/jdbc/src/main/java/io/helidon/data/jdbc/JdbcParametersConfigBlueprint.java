/*
 * Copyright (c) 2023, 2026 Oracle and/or its affiliates.
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

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * JDBC parameter binding configuration for Data JDBC repositories.
 * <p>
 * This mirrors the useful options from {@code io.helidon.dbclient.jdbc.JdbcParametersConfigBlueprint} without
 * depending on the DbClient module. The defaults are intentionally aligned with DbClient so applications get
 * predictable JDBC binding behavior when no {@code parameters} block is configured.
 */
@Prototype.Blueprint
@Prototype.Configured(value = "parameters", root = false)
interface JdbcParametersConfigBlueprint {

    /**
     * Use SQL {@code NCHAR}, {@code NVARCHAR}, or {@code LONGNVARCHAR} conversion for {@link String} values.
     *
     * @return whether national character string binding is used
     */
    @Option.Configured
    @Option.DefaultBoolean(false)
    boolean useNString();

    /**
     * Use {@link java.sql.PreparedStatement#setCharacterStream(int, java.io.Reader, int)} for large strings.
     *
     * @return whether large string stream binding is used
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean useStringBinding();

    /**
     * String length threshold for character stream binding.
     *
     * @return string binding size threshold
     */
    @Option.Configured
    @Option.DefaultInt(1024)
    int stringBindingSize();

    /**
     * Use {@link java.sql.PreparedStatement#setBinaryStream(int, java.io.InputStream, int)} for {@code byte[]} values.
     *
     * @return whether byte array stream binding is used
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean useByteArrayBinding();

    /**
     * Use {@link java.sql.PreparedStatement#setTimestamp(int, java.sql.Timestamp)} for {@link java.time.LocalTime}
     * when {@link #setObjectForJavaTime()} is disabled.
     *
     * @return whether timestamp binding is used for local time values
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean timestampForLocalTime();

    /**
     * Set {@code java.time} values directly through {@link java.sql.PreparedStatement#setObject(int, Object)}.
     *
     * @return whether {@code setObject} is used for {@code java.time} values
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean setObjectForJavaTime();
}
