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
package org.pgcodekeeper.core.database.base.schema.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.Argument;

class ProjectIndexMetadataApiTest {

    @Test
    void exposesAggregateOrderByAsReadOnlyMetadata() {
        var function = new MetaFunction("public", "agg(integer)", "agg");
        var orderBy = new Argument("sort_value", "integer");
        orderBy.setDefaultExpression("0");
        function.addOrderBy(orderBy);

        assertEquals(java.util.List.of(orderBy), function.getOrderBy());
        assertThrows(UnsupportedOperationException.class,
                () -> function.getOrderBy().add(new Argument("other", "text")));
        function.getOrderBy().get(0).setDefaultExpression("mutated");
        assertEquals("0", function.getOrderBy().get(0).getDefaultExpression());
    }

    @Test
    void builderRestoresExactLengthAndAliasWithoutParserContext() {
        var reference = new ObjectReference("public", "same_name", DbObjType.TABLE);
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath("SCHEMA/public/TABLE/same_name.sql")
                .setOffset(17)
                .setLineNumber(2)
                .setCharPositionInLine(4)
                .setReference(reference)
                .setAlias("same_name")
                .setLength(41)
                .build();

        assertEquals(41, location.getObjLength());
        assertEquals("same_name", location.getAlias());
    }

    @Test
    void explicitLengthMustNotBeNegative() {
        ObjectLocation.Builder builder = new ObjectLocation.Builder();

        assertThrows(IllegalArgumentException.class, () -> builder.setLength(-1));
    }
}
