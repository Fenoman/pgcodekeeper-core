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
package org.pgcodekeeper.core.database.base.schema;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.ch.ChDatabaseProvider;
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.Utils;

/**
 * The name order of table columns is for a human eye and for nothing else.
 * <p>
 * Two states of one table are stored in the order each of them was read in -
 * the catalog order on one side, the order of the file on the other - so a side
 * by side rendering shows every moved column as a difference. Rendering both
 * sides in the order of the column names collapses that noise, which is only
 * honest while the order of the columns is not a difference in the first place.
 * A migration script must never be rendered this way: there the order of the
 * columns is the order the table is created with.
 */
class ColumnDisplayOrderTest {

    private static final Pattern COLUMN_NAME =
            Pattern.compile("(?mU)^\\s*\\W*(\\w+)\\W*\\s+(?:text|String|\\[int])");

    private static final String PG_TABLE = """
            CREATE TABLE public.t (
                c_zebra text,
                c_alpha text,
                c_middle text
            );""";

    private static final String PG_TABLE_OTHER_ORDER = """
            CREATE TABLE public.t (
                c_middle text,
                c_zebra text,
                c_alpha text
            );""";

    private static final String MS_TABLE = """
            SET QUOTED_IDENTIFIER ON
            GO
            SET ANSI_NULLS ON
            GO
            CREATE TABLE [dbo].[t] (
                [c_zebra] [int],
                [c_alpha] [int],
                [c_middle] [int]
            )
            GO""";

    private static final String CH_TABLE = """
            CREATE TABLE default.t
            (
            	`c_zebra` String,
            	`c_alpha` String,
            	`c_middle` String
            )
            ENGINE = MergeTree
            ORDER BY c_zebra;""";

    @Test
    void generationKeepsTheStoredOrderOfTheColumns() throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        assertFalse(settings.isSortColumnsForDisplay(),
                "the display order must never be the default: it would change every generated script");

        IDatabase empty = load(new PgDatabaseProvider(), "", settings);
        IDatabase withTable = load(new PgDatabaseProvider(), PG_TABLE, settings);
        String script = PgCodeKeeperApi.diff(new PgDatabaseProvider(), empty, withTable, settings);

        assertEquals(List.of("c_zebra", "c_alpha", "c_middle"), columnNames(script),
                "a created table keeps the order of its definition");
    }

    @Test
    void theRenderedOrderOfTheColumnsIsTheirNameOrder() throws IOException, InterruptedException {
        assertEquals(List.of("c_zebra", "c_alpha", "c_middle"), renderedColumns(PG_TABLE, false));
        assertEquals(List.of("c_alpha", "c_middle", "c_zebra"), renderedColumns(PG_TABLE, true));
    }

    /**
     * The reason the order exists: the two sides of a comparison meet.
     */
    @Test
    void twoStoredOrdersOfOneTableRenderTheSame() throws IOException, InterruptedException {
        assertFalse(renderTable(PG_TABLE, false).equals(renderTable(PG_TABLE_OTHER_ORDER, false)),
                "the fixture must really store the columns in two orders");

        byte[] one = renderTable(PG_TABLE, true).getBytes(StandardCharsets.UTF_8);
        byte[] other = renderTable(PG_TABLE_OTHER_ORDER, true).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(one, other, "both sides must be rendered in one order");
    }

    /**
     * The order is by Unicode code point, not by any collation: an upper case
     * name sorts before every lower case one, which no case-insensitive or
     * locale-aware order would produce. The comparison itself is proven
     * locale-independent below, without moving the default locale of the whole
     * process - test classes here run concurrently, and one class changing the
     * default locale breaks the parsers of every other class that runs beside
     * it.
     */
    @Test
    void theRenderedOrderIsByCodePointAndNotByCollation() throws IOException, InterruptedException {
        String table = """
                CREATE TABLE public.t (
                    "c_zebra" text,
                    "C_omega" text,
                    "c_alpha" text
                );""";

        assertEquals(List.of("C_omega", "c_alpha", "c_zebra"), renderedColumns(table, true));
    }

    /**
     * U+0130 is the Turkish dotted capital I, U+0131 the dotless small i. Case
     * mapping and collation of these letters differ between locales, code point
     * order does not, and the same answer is reached without asking the running
     * JVM what its locale is.
     */
    @Test
    void theOrderOfNamesIsDecidedByCodePointsAlone() {
        assertTrue(Utils.compareByCodePoints("ID_col", "id_col") < 0, "U+0049 precedes U+0069");
        assertTrue(Utils.compareByCodePoints("id_col", "İD_col") < 0, "U+0069 precedes U+0130");
        assertTrue(Utils.compareByCodePoints("İD_col", "ıd_col") < 0, "U+0130 precedes U+0131");
        assertEquals(0, Utils.compareByCodePoints("id_col", "id_col"));

        String supplementary = new String(Character.toChars(0x1F389));
        String basicPlane = "�";
        assertTrue(Utils.compareByCodePoints(supplementary, basicPlane) > 0,
                "a supplementary character follows every character of the basic plane");
        assertTrue(supplementary.compareTo(basicPlane) < 0, "String.compareTo orders by UTF-16 unit");
    }

    @Test
    void everyDialectRendersItsColumnsInTheSameOrder() throws IOException, InterruptedException {
        assertEquals(List.of("c_alpha", "c_middle", "c_zebra"),
                columnNames(render(new MsDatabaseProvider(), MS_TABLE, "dbo", true)),
                "MS SQL renders the columns of a table itself");
        assertEquals(List.of("c_alpha", "c_middle", "c_zebra"),
                columnNames(render(new ChDatabaseProvider(), CH_TABLE, "default", true)),
                "ClickHouse renders the columns of a table itself");

        assertEquals(List.of("c_zebra", "c_alpha", "c_middle"),
                columnNames(render(new MsDatabaseProvider(), MS_TABLE, "dbo", false)));
        assertEquals(List.of("c_zebra", "c_alpha", "c_middle"),
                columnNames(render(new ChDatabaseProvider(), CH_TABLE, "default", false)));
    }

    private List<String> renderedColumns(String sql, boolean sortForDisplay)
            throws IOException, InterruptedException {
        return columnNames(renderTable(sql, sortForDisplay));
    }

    private String renderTable(String sql, boolean sortForDisplay) throws IOException, InterruptedException {
        return render(new PgDatabaseProvider(), sql, "public", sortForDisplay);
    }

    private String render(IDatabaseProvider provider, String sql, String schema, boolean sortForDisplay)
            throws IOException, InterruptedException {
        CoreSettings settings = new CoreSettings();
        settings.setSortColumnsForDisplay(sortForDisplay);
        IStatement table = load(provider, sql, settings)
                .getStatement(new ObjectReference(schema, "t", DbObjType.TABLE));
        return table.getSQL(false, settings);
    }

    private static List<String> columnNames(String sql) {
        List<String> names = new ArrayList<>();
        Matcher matcher = COLUMN_NAME.matcher(sql);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private IDatabase load(IDatabaseProvider provider, String sql, CoreSettings settings)
            throws IOException, InterruptedException {
        settings.clearErrors();
        return provider.getDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)),
                "test/" + getClass().getName(), settings).load();
    }
}
