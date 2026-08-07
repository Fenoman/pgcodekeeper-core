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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.exception.ObjectCreationException;

/**
 * Verifies the name-based column lookup of {@link PgAbstractTable}: it must
 * stay consistent with the ordered column list across additions, duplicate
 * rejection, sorting and deep copies.
 */
class PgTableColumnLookupTest {

    static {
        // explicit locale for localized Messages bound at class init;
        // prevents this class from racing TestUtils/IntegrationTestUtils
        Locale.setDefault(Locale.ENGLISH);
    }

    @Test
    void getColumnFindsAddedColumnsAndMissesAbsentOnes() {
        var table = new PgSimpleTable("tbl");
        var first = column("first", "integer");
        var second = column("second", "text");
        table.addColumn(first);
        table.addColumn(second);

        assertSame(first, table.getColumn("first"));
        assertSame(second, table.getColumn("second"));
        assertNull(table.getColumn("missing"));
        assertNull(table.getColumn("FIRST"));
    }

    @Test
    void lookupSeesColumnsAddedAfterPreviousLookups() {
        var table = new PgSimpleTable("tbl");
        table.addColumn(column("first", "integer"));
        assertNull(table.getColumn("late"));

        var late = column("late", "date");
        table.addColumn(late);

        assertSame(late, table.getColumn("late"));
        assertEquals(List.of("first", "late"), names(table.getColumns()));
    }

    @Test
    void duplicateColumnIsRejectedWithoutCorruptingLookupOrOrder() {
        var table = new PgSimpleTable("tbl");
        var original = column("dup", "integer");
        table.addColumn(original);

        assertThrows(ObjectCreationException.class, () -> table.addColumn(column("dup", "text")));

        assertSame(original, table.getColumn("dup"));
        assertEquals(List.of("dup"), names(table.getColumns()));
    }

    @Test
    void sortColumnsKeepsLookupIntactAndReordersOnlyIteration() {
        var table = new PgSimpleTable("tbl");
        table.addInherits("public", "parent");
        var inheritedZ = column("z_inherited", "integer");
        inheritedZ.setInherit(true);
        var inheritedA = column("a_inherited", "integer");
        inheritedA.setInherit(true);
        var own = column("own", "text");
        table.addColumn(inheritedZ);
        table.addColumn(inheritedA);
        table.addColumn(own);

        table.sortColumns();

        assertEquals(List.of("a_inherited", "z_inherited", "own"), names(table.getColumns()));
        assertSame(own, table.getColumn("own"));
        assertSame(inheritedA, table.getColumn("a_inherited"));
        assertSame(inheritedZ, table.getColumn("z_inherited"));
    }

    @Test
    void deepCopyBuildsIndependentLookup() {
        var table = new PgSimpleTable("tbl");
        var original = column("col", "integer");
        table.addColumn(original);

        var copy = (PgSimpleTable) table.deepCopy();

        assertNotSame(original, copy.getColumn("col"));
        assertEquals("col", copy.getColumn("col").getName());
        assertSame(original, table.getColumn("col"));
    }

    private static PgColumn column(String name, String type) {
        var column = new PgColumn(name);
        column.setType(type);
        return column;
    }

    private static List<String> names(List<IColumn> columns) {
        return columns.stream().map(IColumn::getName).toList();
    }
}
