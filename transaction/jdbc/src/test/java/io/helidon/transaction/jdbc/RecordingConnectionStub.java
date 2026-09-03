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

final class RecordingConnectionStub extends ConnectionStub {

    private boolean autoCommit;

    private boolean committed;

    private boolean rolledBack;

    private boolean closed;

    RecordingConnectionStub() {
        super();
        this.autoCommit = true;
        this.committed = false;
        this.rolledBack = false;
        this.closed = false;
    }

    @Override
    public boolean getAutoCommit() {
        return this.autoCommit;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    @Override
    public void commit() {
        this.committed = true;
    }

    @Override
    public void rollback() {
        this.rolledBack = true;
    }

    @Override
    public void close() {
        this.closed = true;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    boolean committed() {
        return this.committed;
    }

    boolean rolledBack() {
        return this.rolledBack;
    }

}
