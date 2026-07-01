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

/**
 * Detached copy of one {@link java.sql.SQLWarning}.
 * <p>
 * JDBC warning objects are linked and tied to live statement or connection objects. The runner copies stable warning
 * fields into this value so warnings can be inspected after JDBC resources have been closed.
 *
 * @param sqlState SQL state reported by the driver
 * @param vendorCode database vendor-specific warning code
 * @param message warning message
 */
record JdbcWarningInfo(String sqlState, int vendorCode, String message) {
}
