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
package org.pgcodekeeper.core.database.pg.schema;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Verifies the schema-level index lookup of {@link PgSchema}: it must stay
 * consistent when tables, views or indexes are added after previous lookups
 * (the load phase interleaves lookups and model mutation, e.g. ALTER INDEX
 * statements), and must keep the tables-then-views first-match semantics.
 */
class PgSchemaIndexLookupTest {

    static {
        // explicit locale for localized Messages bound at class init;
        // prevents this class from racing TestUtils/IntegrationTestUtils
        Locale.setDefault(Locale.ENGLISH);
    }

    @Test
    void findsIndexesOnTablesAndViews() {
        var schema = new PgSchema("public");
        var table = new PgSimpleTable("tbl");
        schema.addChild(table);
        var view = new PgMaterializedView("mat_view");
        schema.addChild(view);

        var tableIndex = new PgIndex("tbl_idx");
        table.addChild(tableIndex);
        var viewIndex = new PgIndex("view_idx");
        view.addChild(viewIndex);

        assertSame(tableIndex, schema.getIndexByName("tbl_idx"));
        assertSame(viewIndex, schema.getIndexByName("view_idx"));
        assertNull(schema.getIndexByName("missing"));
    }

    @Test
    void lookupSeesIndexAddedToAttachedContainerAfterPreviousLookup() {
        var schema = new PgSchema("public");
        var table = new PgSimpleTable("tbl");
        schema.addChild(table);
        assertNull(schema.getIndexByName("late_idx"));

        var late = new PgIndex("late_idx");
        table.addChild(late);

        assertSame(late, schema.getIndexByName("late_idx"));
    }

    @Test
    void lookupSeesContainerAttachedAfterPreviousLookup() {
        var schema = new PgSchema("public");
        assertNull(schema.getIndexByName("carried_idx"));

        var table = new PgSimpleTable("tbl");
        var carried = new PgIndex("carried_idx");
        table.addChild(carried);
        schema.addChild(table);

        assertSame(carried, schema.getIndexByName("carried_idx"));
    }

    @Test
    void duplicateIndexNamesResolveToFirstContainerInIterationOrder() {
        var schema = new PgSchema("public");
        var firstTable = new PgSimpleTable("a_tbl");
        schema.addChild(firstTable);
        var secondTable = new PgSimpleTable("b_tbl");
        schema.addChild(secondTable);

        var firstIndex = new PgIndex("dup_idx");
        firstTable.addChild(firstIndex);
        var secondIndex = new PgIndex("dup_idx");
        secondTable.addChild(secondIndex);

        assertSame(firstIndex, schema.getIndexByName("dup_idx"));
    }

    @Test
    void deepCopyLookupIsIndependent() {
        var schema = new PgSchema("public");
        var table = new PgSimpleTable("tbl");
        schema.addChild(table);
        var index = new PgIndex("idx");
        table.addChild(index);
        assertSame(index, schema.getIndexByName("idx"));

        var copy = (PgSchema) schema.deepCopy();

        assertNotSame(index, copy.getIndexByName("idx"));
        assertSame(index, schema.getIndexByName("idx"));
    }
}
