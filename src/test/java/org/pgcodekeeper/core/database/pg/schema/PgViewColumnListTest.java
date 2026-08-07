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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectState;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The column list of a view, which the catalog cannot hold as such.
 * <p>
 * {@code CREATE VIEW v (a, b) AS SELECT ...} names the output columns of the
 * query, and that is all it does: the server keeps the names on the columns
 * themselves and {@code pg_get_viewdef} writes them back as aliases inside the
 * query. So a view read over JDBC never carries a column list -
 * {@code PgViewsReader} fills {@link PgAbstractView#addRelationColumn} instead,
 * from {@code column_names} - while a project file that spells one keeps it.
 * Compared as text against text, the two never matched, and the answer to a
 * changed column list is a recreate: a hand-written view with an explicit
 * column list was dropped and created again on every single run, taking its
 * dependants with it. Measured on PostgreSQL 17.10, with a project file whose
 * query text is exactly what {@code pg_get_viewdef} prints - and a control that
 * only removes the list makes the difference vanish, so the list alone was the
 * cause.
 * <p>
 * The names the database side does carry are the answer: a column list that
 * names the very columns the database view has is not a difference, because
 * naming them is all it would do. Anything else still is one, and two project
 * files - neither of which knows the catalog's columns - are still compared
 * list against list.
 */
class PgViewColumnListTest {

    private static final String SCHEMA_NAME = "public";
    private static final String VIEW_NAME = "v1";

    /**
     * The query, spelled the way {@code pg_get_viewdef} prints it for a view
     * declared as {@code CREATE VIEW v1 (a, b) AS SELECT x, y FROM t1}: the
     * column list is gone and its names ride as aliases. Both sides below carry
     * this same text, so the column list is the only thing left that can part
     * them.
     */
    private static final String QUERY = "SELECT x AS a, y AS b FROM public.t1";

    /**
     * The harness guard: a view loaded from a project file carries no catalog
     * columns at all. Were it to carry them, every convergence below would hold
     * for a reason that has nothing to do with the fix.
     */
    @Test
    void aProjectViewCarriesNoCatalogColumns() throws Exception {
        assertNull(projectView("(a, b)").getRelationColumns(),
                "a project-loaded view must leave the catalog columns unset");
        assertNotNull(databaseView("a", "b").getRelationColumns(),
                "and the database side must carry them, or the fix has nothing to read");
    }

    @Test
    void aColumnListTheCatalogColumnsAnswerIsNotAChange() throws Exception {
        assertConverge(databaseView("a", "b"), projectView("(a, b)"));
    }

    /**
     * Quoting is how a column list may spell a name, and the catalog hands the
     * name over bare.
     */
    @Test
    void aQuotedColumnListStillAnswersThem() throws Exception {
        assertConverge(databaseView("a", "b"), projectView("(\"a\", \"b\")"));
    }

    /**
     * The mutation guard. A list that names other columns - or names them in
     * another order - is a genuine difference, and the view still has to be
     * recreated for it.
     */
    @Test
    void aColumnListThatNamesSomethingElseIsStillAChange() throws Exception {
        assertDiverge(databaseView("a", "b"), projectView("(a, c)"));
        assertDiverge(databaseView("a", "b"), projectView("(b, a)"));
        assertDiverge(databaseView("a", "b", "c"), projectView("(a, b)"));
    }

    /**
     * The other mutation guard: with no catalog columns on either side there is
     * nothing to answer the list with, so two project files are still compared
     * list against list. This is the ordinary case - a project against another
     * project, or against a dump.
     */
    @Test
    void twoProjectFilesStillCompareTheirListsStrictly() throws Exception {
        assertDiverge(projectView("(a, b)"), projectView("(a, c)"));
        assertDiverge(projectView("(a, b)"), projectView(""));
    }

    private static void assertConverge(PgAbstractView db, PgAbstractView project) {
        assertEquals(db.hashCode(), project.hashCode(), "views that describe one state must hash the same");
        assertTrue(db.compare(project), "expected the two views to compare as unchanged");
        assertTrue(project.compare(db), "compare must be symmetric");
        assertTrue(Comparison.compare(new CoreSettings(), db, project),
                "and the entry point the diff tree uses must call them unchanged too");

        // needDrop is asked separately: the tree may hold the view for another
        // reason, and a recreate decided there would drop it all the same
        assertEquals(ObjectState.NOTHING, alter(db, project),
                "and no recreate may be decided for it");
        assertEquals(ObjectState.NOTHING, alter(project, db), "in either direction");
    }

    private static void assertDiverge(PgAbstractView a, PgAbstractView b) {
        assertFalse(Comparison.compare(new CoreSettings(), a, b),
                "expected the two views to compare as changed");
        assertFalse(Comparison.compare(new CoreSettings(), b, a), "compare must be symmetric");
        assertEquals(ObjectState.RECREATE, alter(a, b), "and the view must be recreated for it");
    }

    private static ObjectState alter(PgAbstractView oldView, PgAbstractView newView) {
        var settings = new CoreSettings();
        return oldView.appendAlterSQL(newView, new SQLScript(settings, oldView.getSeparator()));
    }

    /**
     * The database side, built the way {@code PgViewsReader} builds one: the
     * query as {@code pg_get_viewdef} prints it, no column list, and the
     * catalog's own column names.
     */
    private static PgAbstractView databaseView(String... columns) throws Exception {
        var view = new PgView(VIEW_NAME);
        view.setQuery(QUERY, QUERY);
        for (String column : columns) {
            view.addRelationColumn(column, "integer");
        }
        parent(view);
        return view;
    }

    /**
     * The project side, driven through {@link PgDumpLoader} so that the column
     * list travels the same route a file on disk does.
     *
     * @param columnList the parenthesized list, or an empty string for a view
     *                   that declares none
     */
    private static PgAbstractView projectView(String columnList) throws Exception {
        var db = new PgDumpLoader(() -> new ByteArrayInputStream("""
                CREATE VIEW %1$s.%2$s %3$s AS
                    %4$s;
                """.formatted(SCHEMA_NAME, VIEW_NAME, columnList, QUERY).getBytes(UTF_8)),
                "view column list test", new CoreSettings()).load();
        var schema = (PgSchema) db.getChild(SCHEMA_NAME, DbObjType.SCHEMA);
        return (PgAbstractView) schema.getChild(VIEW_NAME, DbObjType.VIEW);
    }

    /**
     * A statement's hash covers the names of everything above it, so both sides
     * have to hang off the same names.
     */
    private static void parent(PgAbstractView view) {
        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        schema.addChild(view);
    }
}
