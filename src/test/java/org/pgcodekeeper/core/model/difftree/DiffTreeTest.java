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
package org.pgcodekeeper.core.model.difftree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;

class DiffTreeTest {

    @Test
    void addColumnsPreservesLeftThenRightIterationOrder() {
        List<IColumn> left = List.of(
                column("z_same", "integer"),
                column("m_changed", "integer"),
                column("b_left", "text"));
        List<IColumn> right = List.of(
                column("a_right", "boolean"),
                column("z_same", "integer"),
                column("m_changed", "bigint"),
                column("c_right", "date"));
        var parent = new TreeElement("table", DbObjType.TABLE, DiffSide.BOTH);
        var result = new ArrayList<TreeElement>();

        DiffTree.addColumns(left, right, parent, result);

        assertEquals(List.of(
                "m_changed|BOTH",
                "b_left|LEFT",
                "a_right|RIGHT",
                "c_right|RIGHT"), snapshot(result));
        result.forEach(element -> assertSame(parent, element.getParent()));
    }

    @Test
    void addColumnsUsesFirstOppositeColumnWhenNamesAreDuplicated() {
        List<IColumn> left = List.of(
                column("duplicate_match", "integer"),
                column("duplicate_left", "integer"),
                column("duplicate_left", "text"));
        List<IColumn> right = List.of(
                column("duplicate_match", "text"),
                column("duplicate_match", "integer"),
                column("duplicate_right", "date"),
                column("duplicate_right", "boolean"));
        var result = new ArrayList<TreeElement>();

        DiffTree.addColumns(left, right,
                new TreeElement("table", DbObjType.TABLE, DiffSide.BOTH), result);

        assertEquals(List.of(
                "duplicate_match|BOTH",
                "duplicate_left|LEFT",
                "duplicate_left|LEFT",
                "duplicate_right|RIGHT",
                "duplicate_right|RIGHT"), snapshot(result));
    }

    private static PgColumn column(String name, String type) {
        var column = new PgColumn(name);
        column.setType(type);
        return column;
    }

    private static List<String> snapshot(List<TreeElement> elements) {
        return elements.stream()
                .map(element -> element.getName() + '|' + element.getSide())
                .toList();
    }
}
