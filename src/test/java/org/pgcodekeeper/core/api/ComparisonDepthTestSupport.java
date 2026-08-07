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
package org.pgcodekeeper.core.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Builds the on-disk project fixtures {@link ComparisonDepthTest} and
 * {@link StructuralComparisonParityTest} compare a structural load against a
 * full one with: a view over a table, so a full load has exactly one
 * dependency to resolve and a structural load has none.
 */
final class ComparisonDepthTestSupport {

    private ComparisonDepthTestSupport() {
    }

    /**
     * Writes a schema, a table and a view selecting from that table, then wires
     * up loader factories for the project. Both comparison sides load the same
     * directory: only {@code oldDatabase()} is inspected by the test, so the
     * NEW side only has to load without error, same as OLD.
     *
     * @param projectDir directory to write the project into; normally a JUnit
     *                   {@code @TempDir}
     * @return a fixture backing one {@code loadForComparison} run; call
     *         {@link Fixture#freshSettings()} to get settings for a second,
     *         independent run over the same factories
     * @throws IOException if the fixture files cannot be written
     */
    static Fixture projectWithAViewOverATable(Path projectDir) throws IOException {
        writeFile(projectDir, "SCHEMA/app/app.sql", "CREATE SCHEMA app;\n");
        writeFile(projectDir, "SCHEMA/app/TABLE/item.sql", "CREATE TABLE app.item (id integer);\n");
        writeFile(projectDir, "SCHEMA/app/VIEW/pick.sql",
                "CREATE VIEW app.pick AS SELECT id FROM app.item;\n");

        var provider = new PgDatabaseProvider();
        var factories = new ComparisonLoaderFactories(
                sideSettings -> provider.getProjectLoader(projectDir, sideSettings),
                sideSettings -> provider.getProjectLoader(projectDir, sideSettings));
        return new Fixture(factories, new CoreSettings());
    }

    private static void writeFile(Path projectDir, String relativePath, String content)
            throws IOException {
        Path file = projectDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * Writes a base project and a changed copy of it - one table gains a
     * column, a view's body is rewritten, a function is added and a table is
     * dropped - then wires up loader factories with the OLD side pointed at
     * the base project and the NEW side at the changed one.
     * <p>
     * Unlike {@link #projectWithAViewOverATable(Path)}, the two sides read
     * different directories, so the resulting diff tree is never trivially
     * empty: it is built to carry every node state a {@code TreeElement} can
     * hold - an object added (the function), one removed (the table), two
     * changed (the table that gained a column and the view), and the
     * enclosing schema itself, unchanged in its own attributes yet present
     * in the tree for no reason other than that some of its children differ.
     * A fifth table is left identical on both sides on purpose, so the tree
     * also proves an object that never changes produces no node at all.
     *
     * @param dir directory to write both projects into; normally a JUnit
     *            {@code @TempDir}
     * @return a fixture backing one {@code loadForComparison} run; call
     *         {@link Fixture#freshSettings()} to get settings for a second,
     *         independent run over the same factories
     * @throws IOException if the fixture files cannot be written
     */
    static Fixture projectAgainstChangedProject(Path dir) throws IOException {
        Path oldProject = dir.resolve("old");
        Path newProject = dir.resolve("new");

        writeFile(oldProject, "SCHEMA/app/app.sql", "CREATE SCHEMA app;\n");
        writeFile(oldProject, "SCHEMA/app/TABLE/item.sql",
                "CREATE TABLE app.item (id integer);\n");
        writeFile(oldProject, "SCHEMA/app/TABLE/gone.sql",
                "CREATE TABLE app.gone (id integer);\n");
        writeFile(oldProject, "SCHEMA/app/TABLE/other.sql",
                "CREATE TABLE app.other (id integer);\n");
        writeFile(oldProject, "SCHEMA/app/VIEW/summary.sql",
                "CREATE VIEW app.summary AS SELECT id FROM app.other;\n");

        writeFile(newProject, "SCHEMA/app/app.sql", "CREATE SCHEMA app;\n");
        writeFile(newProject, "SCHEMA/app/TABLE/item.sql",
                "CREATE TABLE app.item (id integer, extra_col text);\n");
        writeFile(newProject, "SCHEMA/app/TABLE/other.sql",
                "CREATE TABLE app.other (id integer);\n");
        writeFile(newProject, "SCHEMA/app/VIEW/summary.sql",
                "CREATE VIEW app.summary AS SELECT id FROM app.other WHERE id > 0;\n");
        writeFile(newProject, "SCHEMA/app/FUNCTION/compute.sql",
                "CREATE FUNCTION app.compute() RETURNS integer\n"
                        + "    LANGUAGE sql\n"
                        + "    AS $$SELECT 1;$$;\n");

        var provider = new PgDatabaseProvider();
        var factories = new ComparisonLoaderFactories(
                sideSettings -> provider.getProjectLoader(oldProject, sideSettings),
                sideSettings -> provider.getProjectLoader(newProject, sideSettings));
        return new Fixture(factories, new CoreSettings());
    }

    /**
     * One comparison's loader factories, plus settings for its first run.
     * <p>
     * Loading mutates settings (version gets published, error lists fill in),
     * so a second, independent run over the same {@link #factories()} must not
     * reuse {@link #settings()} - it must ask {@link #freshSettings()} for its
     * own instance instead.
     *
     * @param factories loader factories for both comparison sides
     * @param settings  settings for the first run over {@code factories}
     */
    record Fixture(ComparisonLoaderFactories factories, ISettings settings) {

        ISettings freshSettings() {
            return new CoreSettings();
        }
    }
}
