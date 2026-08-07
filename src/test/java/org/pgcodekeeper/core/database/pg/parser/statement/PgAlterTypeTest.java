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
package org.pgcodekeeper.core.database.pg.parser.statement;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractType;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgEnumType;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The alternatives of {@code ALTER TYPE} that state something a
 * {@code CREATE TYPE} could have stated. Every one of them has to reach a
 * writer: an alternative that falls into {@code alterType}'s early return drops
 * the content it states.
 *
 * <p>
 * The tool writes three of them itself: {@code PgEnumType.compareType} emits
 * {@code ADD VALUE} and {@code PgCompositeType.compareType} emits
 * {@code ADD}/{@code DROP}/{@code ALTER ATTRIBUTE}. So left unread they are
 * migrations pgcodekeeper generates and cannot read back.
 *
 * <p>
 * The criterion: the model an {@code ALTER} builds must be the model the
 * equivalent {@code CREATE} builds, because the database side has one answer for
 * both spellings. An enum is the sharpest case of it - the type has
 * no {@code DROP VALUE}, so a value missing from the project's model makes the
 * comparison drop and recreate the type, which cascades to every column that
 * uses it.
 */
class PgAlterTypeTest {

    private static final String SCHEMA = "public";
    private static final String TYPE = "status";

    // -------------------------------------------------------- ADD VALUE

    /**
     * The defect, stated directly: {@code ADD VALUE} is the canonical way to
     * extend an enum, and a file that uses it must reach the same model the
     * full {@code CREATE} reaches.
     */
    @Test
    void anEnumValueAddedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load(enumOf("'new', 'done'") + """

                ALTER TYPE public.status ADD VALUE 'archived';""");

        assertEquals("", pipeline(load(enumOf("'new', 'done', 'archived'")), byAlter).trim(),
                "a value added by ALTER must be the value the CREATE lists");
    }

    /**
     * The position matters, because an enum's order is its comparison order and
     * {@code PgEnumType.compareUnalterable} reads the two lists as sequences: a
     * value put in the wrong place makes the tool drop and recreate the type.
     */
    @Test
    void anEnumValueAddedBeforeAnotherLandsThere() throws Exception {
        PgDatabase byAlter = load(enumOf("'new', 'done'") + """

                ALTER TYPE public.status ADD VALUE 'archived' BEFORE 'done';""");

        assertEquals("", pipeline(load(enumOf("'new', 'archived', 'done'")), byAlter).trim(),
                "BEFORE must put the value where the CREATE lists it");
    }

    /** The other side of the same clause. */
    @Test
    void anEnumValueAddedAfterAnotherLandsThere() throws Exception {
        PgDatabase byAlter = load(enumOf("'new', 'done'") + """

                ALTER TYPE public.status ADD VALUE 'archived' AFTER 'new';""");

        assertEquals("", pipeline(load(enumOf("'new', 'archived', 'done'")), byAlter).trim(),
                "AFTER must put the value where the CREATE lists it");
    }

    /**
     * {@code IF NOT EXISTS} is the author's own word that the value may already
     * be there, so a repeat states nothing rather than duplicating the label.
     *
     * <p>
     * Asserted on the list rather than on a script, because a script cannot see
     * it: {@code PgEnumType.compareType} adds a value the other side lacks and
     * {@code compareUnalterable} reads the two lists as sequences, so a
     * duplicate label passes both - measured. The damage of a duplicate is in
     * the {@code CREATE} this model writes, which PostgreSQL refuses.
     */
    @Test
    void anEnumValueAlreadyPresentUnderIfNotExistsIsLeftAlone() throws Exception {
        PgDatabase byAlter = load(enumOf("'new', 'done'") + """

                ALTER TYPE public.status ADD VALUE IF NOT EXISTS 'done';""");

        assertEquals(List.of("'new'", "'done'"), valuesOf(byAlter),
                "a value the type already has must not be added twice");
    }

    /**
     * The round trip. {@code PgEnumType.compareType} writes this very statement
     * - {@code alter_type_add_value_diff.sql} carries it as expected output -
     * so the tool's own migration, appended to the project it was written
     * against, has to reach the project it was written for.
     */
    @Test
    void theAddValueTheToolWritesIsReadBack() throws Exception {
        String from = enumOf("'new', 'done'");
        PgDatabase target = load(enumOf("'new', 'done', 'archived'"));

        String migration = pipeline(load(from), target);
        assertTrue(migration.contains("ADD VALUE"),
                () -> "the tool writes ADD VALUE to extend an enum, got:\n" + migration);

        String script = pipeline(target, load(from + "\n\n" + migration));
        assertEquals("", script.trim(),
                () -> "applying the tool's own migration must reach the project it was written for, got:\n"
                        + script);
    }

    /**
     * A neighbour the type has not is reported, the way an unknown constraint is
     * for {@code RENAME CONSTRAINT}: the clause has no word with which to say
     * the value may not be there, and the server refuses the statement too.
     */
    @Test
    void addingAValueBesideOneTheTypeHasNotIsReported() throws Exception {
        var settings = new CoreSettings();
        load(enumOf("'new', 'done'") + """

                ALTER TYPE public.status ADD VALUE 'archived' AFTER 'nosuch';""", settings);
        assertFalse(settings.getErrors().isEmpty(),
                "a value the enum does not carry cannot be a neighbour");
    }

    // ----------------------------------------------------- RENAME VALUE

    /**
     * States content and not identity - the type's own name is untouched while
     * its {@code CREATE} goes on listing the old label - which is the reading
     * {@code RENAME COLUMN} and {@code RENAME CONSTRAINT} get.
     *
     * <p>
     * The fixture carries three values and renames the middle one, so that a
     * rename which appended instead of replacing in place would show: with two
     * values, renaming the last one comes out identical either way.
     */
    @Test
    void anEnumValueRenamedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load(enumOf("'new', 'done', 'archived'") + """

                ALTER TYPE public.status RENAME VALUE 'done' TO 'closed';""");

        assertEquals(List.of("'new'", "'closed'", "'archived'"), valuesOf(byAlter),
                "a renamed value must keep its place, which is its comparison order");
        assertEquals("", pipeline(load(enumOf("'new', 'closed', 'archived'")), byAlter).trim(),
                "a renamed value must be the value the CREATE lists, in its place");
    }

    /** And a label that matches nothing is reported, as the neighbour is. */
    @Test
    void renamingAValueTheTypeHasNotIsReported() throws Exception {
        var settings = new CoreSettings();
        load(enumOf("'new', 'done'") + """

                ALTER TYPE public.status RENAME VALUE 'nosuch' TO 'closed';""", settings);
        assertFalse(settings.getErrors().isEmpty(),
                "a rename has no way to say the value may not be there");
    }

    // -------------------------------------------------------- ATTRIBUTES

    /**
     * A composite type's attributes, the second thing the tool writes itself:
     * {@code PgCompositeType.compareType} emits {@code ADD ATTRIBUTE}.
     */
    @Test
    void anAttributeAddedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load(composite("\tc1 integer") + """

                ALTER TYPE public.status ADD ATTRIBUTE c2 text;""");

        assertEquals("", pipeline(load(composite("\tc1 integer,\n\tc2 text")), byAlter).trim(),
                "an attribute added by ALTER must be the attribute the CREATE lists");
    }

    /** {@code DROP ATTRIBUTE}, the half that takes one away. */
    @Test
    void anAttributeDroppedByAlterLeavesTheModel() throws Exception {
        PgDatabase byAlter = load(composite("\tc1 integer,\n\tc2 text") + """

                ALTER TYPE public.status DROP ATTRIBUTE c2;""");

        assertEquals("", pipeline(load(composite("\tc1 integer")), byAlter).trim(),
                "an attribute dropped by ALTER must leave the model");
    }

    /**
     * {@code IF EXISTS} is the author's word that the attribute may not be
     * there; without it an unknown name is reported, as a dropped column's is.
     */
    @Test
    void droppingAnAttributeTheTypeHasNotIsReported() throws Exception {
        var settings = new CoreSettings();
        load(composite("\tc1 integer") + """

                ALTER TYPE public.status DROP ATTRIBUTE nosuch;""", settings);
        assertFalse(settings.getErrors().isEmpty(),
                "an attribute the type has not cannot be dropped silently");

        var lenient = new CoreSettings();
        load(composite("\tc1 integer") + """

                ALTER TYPE public.status DROP ATTRIBUTE IF EXISTS nosuch;""", lenient);
        assertTrue(lenient.getErrors().isEmpty(),
                "IF EXISTS is the author's own word that it may not be there");
    }

    /** {@code ALTER ATTRIBUTE ... TYPE}, the third form the tool writes itself. */
    @Test
    void anAttributeRetypedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load(composite("\tc1 integer") + """

                ALTER TYPE public.status ALTER ATTRIBUTE c1 TYPE text;""");

        assertEquals("", pipeline(load(composite("\tc1 text")), byAlter).trim(),
                "an attribute retyped by ALTER must be the attribute the CREATE lists");
    }

    /**
     * {@code RENAME ATTRIBUTE}. The name of a statement is final, so the rename
     * is a new object built from a copy - the same shape
     * {@code PgColumn.renamedCopy} was added for.
     *
     * <p>
     * Two attributes, and the first one renamed, so that a rename which
     * appended instead of replacing in place would show:
     * {@code compareUnalterable} reads the attributes as an ordered list, and
     * with one attribute both orders are the same order.
     */
    @Test
    void anAttributeRenamedByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load(composite("\tc1 integer,\n\tc2 text") + """

                ALTER TYPE public.status RENAME ATTRIBUTE c1 TO c3;""");

        assertEquals("", pipeline(load(composite("\tc3 integer,\n\tc2 text")), byAlter).trim(),
                "a renamed attribute must be the attribute the CREATE lists, in its place");
    }

    /** More than one action in a statement, applied left to right. */
    @Test
    void everyActionOfOneStatementReachesTheModel() throws Exception {
        PgDatabase byAlter = load(composite("\tc1 integer,\n\tc2 text") + """

                ALTER TYPE public.status DROP ATTRIBUTE c2, ADD ATTRIBUTE c3 boolean;""");

        assertEquals("", pipeline(load(composite("\tc1 integer,\n\tc3 boolean")), byAlter).trim(),
                "both actions of one statement must reach the model");
    }

    // ------------------------------------------------------- SET (...)

    /**
     * The property list of a base type. Each name here writes the field the
     * matching clause of the {@code CREATE} writes, so the two spellings build
     * one model.
     */
    @Test
    void aBaseTypePropertySetByAlterReachesTheModel() throws Exception {
        PgDatabase byAlter = load("""
                CREATE TYPE public.status (
                \tINPUT = public.f_in,
                \tOUTPUT = public.f_out
                );

                ALTER TYPE public.status SET (RECEIVE = public.f_recv, STORAGE = external);""");

        assertEquals("", pipeline(load("""
                CREATE TYPE public.status (
                \tINPUT = public.f_in,
                \tOUTPUT = public.f_out,
                \tRECEIVE = public.f_recv,
                \tSTORAGE = external
                );"""), byAlter).trim(),
                "a property set by ALTER must be the property the CREATE states inline");
    }

    // ------------------------------------------------------------ guards

    /**
     * {@code RENAME TO} and {@code SET SCHEMA} state the identity of the type
     * rather than its content and are deliberately not applied, as they are for
     * a table, a domain and a sequence: a project file writes the name and the
     * schema it means in the {@code CREATE} itself.
     */
    @Test
    void theIdentityAlternativesStateNothing() throws Exception {
        String create = enumOf("'new', 'done'");
        assertEquals("", pipeline(load(create), load(create + """

                ALTER TYPE public.status RENAME TO other;""")).trim(),
                "a rename of the type itself states nothing about its content");

        assertEquals("", pipeline(load(create), load(create + """

                ALTER TYPE public.status SET SCHEMA other;""")).trim(),
                "a schema change states nothing about the type's content");
    }

    /** A type the schema has not is reported, as every other ALTER reports one. */
    @Test
    void alteringATypeTheSchemaHasNotIsReported() throws Exception {
        var settings = new CoreSettings();
        load(enumOf("'new'") + """

                ALTER TYPE public.nosuch ADD VALUE 'done';""", settings);
        assertFalse(settings.getErrors().isEmpty(), "an unknown type has to be reported");
    }

    // ----------------------------------------------------------- fixtures

    private static String enumOf(String values) {
        return """
                CREATE TYPE public.status AS ENUM (
                \t%s
                );""".formatted(values);
    }

    private static String composite(String attrs) {
        return """
                CREATE TYPE public.status AS (
                %s
                );""".formatted(attrs);
    }

    // ------------------------------------------------------------ helpers

    private static PgDatabase load(String sql) throws IOException, InterruptedException {
        return load(sql, new CoreSettings());
    }

    private static PgDatabase load(String sql, CoreSettings settings) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "alter type test", settings).load();
    }

    private static PgAbstractType typeOf(PgDatabase db) {
        PgSchema schema = (PgSchema) db.getSchema(SCHEMA);
        PgAbstractType type = schema == null ? null : schema.getType(TYPE);
        assertNotNull(type, "no type was parsed");
        return type;
    }

    /** The enum's labels in declaration order, which is comparison order. */
    private static List<String> valuesOf(PgDatabase db) {
        return ((PgEnumType) typeOf(db)).getEnums();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }
}
