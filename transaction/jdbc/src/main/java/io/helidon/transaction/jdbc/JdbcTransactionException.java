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
package io.helidon.transaction.jdbc;

import java.sql.SQLException;

import io.helidon.transaction.TxException;

final class JdbcTransactionException extends TxException {

    private static final long serialVersionUID = 1L;

    JdbcTransactionException(String action, SQLException cause) {
        super("JDBC transaction failed while " + action + ": " + message(cause), cause);
    }

    private static String message(SQLException cause) {
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getName();
        }
        return message;
    }
}
