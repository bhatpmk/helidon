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
package io.helidon.data.jdbc.tests.declarative.repository;

import java.util.List;

import io.helidon.data.Data;
import io.helidon.data.jdbc.Jdbc;
import io.helidon.data.jdbc.tests.application.ContactView;

/**
 * Generated repository methods with supplementary-plane Java bind-marker identifiers.
 */
@Data.Repository
@Data.Provider("jdbc")
public interface UnicodeMarkerRepository {

    /**
     * Selects by a marker whose first code point is supplementary-plane Java identifier text.
     *
     * @param 𐐀name contact name
     * @return matching contact
     */
    @Jdbc.Statement("SELECT ID, NAME, EMAIL FROM CONTACT WHERE NAME = :𐐀name")
    ContactView findBySupplementaryStart(String 𐐀name);

    /**
     * Selects by a repeated marker containing a supplementary-plane Java identifier code point.
     *
     * @param na𐐀me contact name or email
     * @return matching contacts
     */
    @Jdbc.Statement("""
            SELECT ID, NAME, EMAIL
            FROM CONTACT
            WHERE NAME = :na𐐀me OR EMAIL = :na𐐀me
            ORDER BY ID
            """)
    List<ContactView> findByRepeatedSupplementaryPart(String na𐐀me);
}
