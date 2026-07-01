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
 * Event marking an explicit no-result outcome.
 * <p>
 * JDBC commonly signals the end of result traversal with an update count of {@code -1}. Most reducers do not need an
 * event for that sentinel, but this event type is available for operation shapes where recording an explicit no-result
 * outcome is more useful than omitting an event.
 *
 * @param step owning transcript step
 * @param ordinal event order within the step
 */
record NoResultEvent(StepRef step, int ordinal) implements JdbcEvent {
}
