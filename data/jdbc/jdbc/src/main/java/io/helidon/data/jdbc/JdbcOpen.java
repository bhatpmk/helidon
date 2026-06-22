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

import java.sql.SQLException;

import io.helidon.data.jdbc.function.JdbcAutoCloseable;
import io.helidon.data.jdbc.function.JdbcRunnable;
import io.helidon.data.runtime.Open;

/**
 * An {@link Open} and a {@link JdbcAutoCloseable} that throws {@link SQLException}s.
 *
 * @see #onClose(JdbcRunnable)
 */
@io.helidon.common.Api.Internal
public interface JdbcOpen extends Open, JdbcAutoCloseable {

    @Override // Open
    JdbcOpen onClose(Runnable closeHandler) throws SQLException;

    /**
     * Returns an equivalent {@link JdbcOpen} with an additional close handler.
     *
     * <p>Close handlers are run when the {@link #close()} method is called, and are executed in the order they were
     * added. All close handlers are run, even if earlier close handlers throw exceptions. If any close handler throws
     * an exception, the first exception thrown will be relayed to the caller of {@link #close()}, with any remaining
     * exceptions added to that exception as suppressed exceptions (unless one of the remaining exceptions is the same
     * exception as the first exception, since an exception cannot suppress itself.)</p>
     *
     * @param closeHandler a non-{@code null} {@link JdbcRunnable} representing a task to {@linkplain JdbcRunnable#run()
     * run} when this {@link JdbcOpen} implementation is {@linkplain #close() closed}
     * @return an eqivalent {@link JdbcOpen} with an additional close handler
     * @throws NullPointerException if {@code closeHandler} is {@code null}
     * @throws IllegalStateException if this {@link JdbcOpen} implementation has already been {@linkplain #close() closed}
     * @throws SQLException if a database error occurs
     */
    JdbcOpen onClose(JdbcRunnable closeHandler) throws SQLException;

}
