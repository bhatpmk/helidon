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
package io.helidon.data.declarative;

import java.lang.annotation.Documented;
import java.lang.annotation.Target;

import io.helidon.common.Api.Incubating;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;

/**
 * Indicates that its target annotation is <dfn>statement-bearing</dfn>.
 *
 * <p>An annotation is <dfn>statement-bearing</dfn> if it has a required, {@link String}-typed {@code value} element that is
 * intended to supply a <dfn>statement</dfn> to be executed against some data store.
 */
@Documented
@Incubating
@Target(ANNOTATION_TYPE)
public @interface StatementBearing {

}
