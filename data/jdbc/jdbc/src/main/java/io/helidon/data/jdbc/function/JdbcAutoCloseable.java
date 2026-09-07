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
package io.helidon.data.jdbc.function;

import java.sql.SQLException;

/**
 * An {@link AutoCloseable} that throws only {@link SQLException}s (and {@link RuntimeException}s).
 *
 * @see #close()
 * @see AutoCloseable
 */
@FunctionalInterface
public interface JdbcAutoCloseable extends AutoCloseable {

    /**
     * Closes this resource, relinquishing any underlying resources.
     *
     * <p>This method is invoked automatically on objects managed by the try-with-resources statement.</p>
     *
     * @throws SQLException if a datbaase error occurs
     */
    void close() throws SQLException;

}
