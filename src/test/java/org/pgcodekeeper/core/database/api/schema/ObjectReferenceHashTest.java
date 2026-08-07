/*******************************************************************************
 * Copyright 2017-2026 TAXTELECOM, LLC
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
 *******************************************************************************/
package org.pgcodekeeper.core.database.api.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link ObjectReference} is the key of hash containers whose iteration order
 * reaches the generated script, so its hash must not depend on anything the JVM
 * re-randomizes per run. The record-generated hash would mix in
 * {@code DbObjType.hashCode()}, and {@link Enum#hashCode()} is final and returns
 * the identity hash, which is drawn from a per-thread generator.
 */
class ObjectReferenceHashTest {

    /**
     * Pins the hash to the stable {@link DbObjType#ordinal()}. Removing the
     * explicit override brings the record-generated, identity-hash based one
     * back and fails here.
     */
    @Test
    void hashCodeUsesStableTypeOrdinal() {
        var reference = new ObjectReference("public", "alpha", "id", DbObjType.COLUMN);

        int expected = 1;
        expected = 31 * expected + "public".hashCode();
        expected = 31 * expected + "alpha".hashCode();
        expected = 31 * expected + "id".hashCode();
        expected = 31 * expected + DbObjType.COLUMN.ordinal();

        assertEquals(expected, reference.hashCode());
    }

    /**
     * Null components are still hashable and the type still separates otherwise
     * equal references.
     */
    @Test
    void hashCodeSeparatesTypesAndAcceptsNullComponents() {
        var table = new ObjectReference("public", "alpha", DbObjType.TABLE);
        var view = new ObjectReference("public", "alpha", DbObjType.VIEW);
        var schema = new ObjectReference("public", DbObjType.SCHEMA);

        assertNotEquals(table.hashCode(), view.hashCode());
        assertEquals(table.hashCode(), new ObjectReference("public", "alpha", DbObjType.TABLE).hashCode());
        assertEquals(schema.hashCode(), new ObjectReference("public", DbObjType.SCHEMA).hashCode());
    }
}
