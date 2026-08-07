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
package org.pgcodekeeper.core.it.loader.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.routine.DeferredRoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCandidate;
import org.pgcodekeeper.core.database.pg.routine.ProjectRoutineBodyCatalog;
import org.pgcodekeeper.core.database.pg.routine.RoutineBody;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyProfile;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyRepresentation;
import org.pgcodekeeper.core.database.pg.routine.RoutineIdentity;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.settings.CoreSettings;

class PgProjectRoutineBodyCatalogTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parserPublishesExactRawAndCanonicalReferences(boolean keepNewLines,
                                                       @TempDir Path dir)
            throws IOException, InterruptedException {
        String sqlRaw = "SELECT\r\n'$body$'::text";
        String plpgsqlRaw = "BEGIN\r\n  NULL;\r\nEND";
        Path dump = dir.resolve("routines.sql");
        Files.writeString(dump, """
                CREATE SCHEMA "Mixed Schema";

                CREATE FUNCTION "Mixed Schema"."calc""value"(input int4)
                RETURNS text
                LANGUAGE sql
                AS $outer$%s$outer$;

                CREATE PROCEDURE "Mixed Schema".refresh(input text)
                LANGUAGE plpgsql
                AS $procedure$%s$procedure$;

                CREATE FUNCTION "Mixed Schema".statement_body()
                RETURNS integer
                LANGUAGE sql
                RETURN 4;

                CREATE FUNCTION "Mixed Schema".native_body()
                RETURNS integer
                LANGUAGE c
                AS 'native_lib', 'native_symbol';
                """.formatted(sqlRaw, plpgsqlRaw));
        var settings = new CoreSettings();
        settings.setKeepNewlines(keepNewLines);

        PgDatabase database = new PgDumpLoader(dump, settings).load();
        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(database);
        PgSchema schema = database.getSchema("Mixed Schema");
        PgAbstractFunction sqlFunction = find(schema, "calc\"value", DbObjType.FUNCTION);
        PgAbstractFunction procedure = find(schema, "refresh", DbObjType.PROCEDURE);

        assertEquals(2, catalog.candidateCount());
        assertEquals(0, catalog.ambiguousCount());
        assertTrue(sqlFunction.getName().contains("integer"), sqlFunction.getName());
        assertCandidate(catalog.removeCandidate(RoutineIdentity.from(sqlFunction)),
                sqlFunction, sqlRaw, keepNewLines, RoutineBodyRepresentation.SQL_TEXT);
        assertCandidate(catalog.removeCandidate(RoutineIdentity.from(procedure)),
                procedure, plpgsqlRaw, keepNewLines,
                RoutineBodyRepresentation.PLPGSQL_TEXT);
    }

    @Test
    void duplicateCreateFromRealParserPoisonsOtherwiseValidIdentity(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path dump = dir.resolve("duplicates.sql");
        Files.writeString(dump, """
                CREATE SCHEMA public;
                CREATE FUNCTION public.duplicate_body(value integer)
                RETURNS integer LANGUAGE sql AS $first$SELECT value$first$;
                CREATE FUNCTION public.duplicate_body(value integer)
                RETURNS integer LANGUAGE sql AS $second$SELECT value + 1$second$;
                """);
        var settings = new CoreSettings();

        PgDatabase database = new PgDumpLoader(dump, settings).load();
        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(database);
        PgAbstractFunction function = find(database.getSchema("public"),
                "duplicate_body", DbObjType.FUNCTION);
        RoutineIdentity identity = RoutineIdentity.from(function);

        assertFalse(settings.getErrors().isEmpty());
        assertEquals(0, catalog.candidateCount());
        assertEquals(1, catalog.ambiguousCount());
        assertTrue(catalog.removeAmbiguous(identity));
    }

    @Test
    void duplicateIdentityIsAmbiguousEvenWhenOnlyOneBodyIsEligible(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path dump = dir.resolve("mixed-eligibility-duplicates.sql");
        Files.writeString(dump, """
                CREATE SCHEMA public;
                CREATE FUNCTION public.eligible_then_native()
                RETURNS integer LANGUAGE sql AS $sql$SELECT 1$sql$;
                CREATE FUNCTION public.eligible_then_native()
                RETURNS integer LANGUAGE c AS 'native_lib', 'native_symbol';

                CREATE FUNCTION public.native_then_eligible()
                RETURNS integer LANGUAGE c AS 'native_lib', 'native_symbol';
                CREATE FUNCTION public.native_then_eligible()
                RETURNS integer LANGUAGE sql AS $sql$SELECT 2$sql$;
                """);
        var settings = new CoreSettings();

        PgDatabase database = new PgDumpLoader(dump, settings).load();
        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(database);
        PgSchema schema = database.getSchema("public");
        RoutineIdentity eligibleFirst = RoutineIdentity.from(
                find(schema, "eligible_then_native", DbObjType.FUNCTION));
        RoutineIdentity nativeFirst = RoutineIdentity.from(
                find(schema, "native_then_eligible", DbObjType.FUNCTION));

        assertFalse(settings.getErrors().isEmpty());
        assertEquals(0, catalog.candidateCount());
        assertEquals(2, catalog.ambiguousCount());
        assertTrue(catalog.removeAmbiguous(eligibleFirst));
        assertTrue(catalog.removeAmbiguous(nativeFirst));
    }

    @Test
    void finalNativeAsActionDoesNotPublishEarlierTextBody(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path dump = dir.resolve("multiple-as.sql");
        Files.writeString(dump, """
                CREATE SCHEMA public;
                CREATE FUNCTION public.multiple_as()
                RETURNS integer
                LANGUAGE sql
                AS $text$SELECT 1$text$
                AS 'native_lib', 'native_symbol';
                """);

        PgDatabase database = new PgDumpLoader(dump, new CoreSettings()).load();
        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(database);

        assertEquals(0, catalog.candidateCount());
        assertEquals(0, catalog.ambiguousCount());
    }

    private static void assertCandidate(ProjectRoutineBodyCandidate candidate,
                                        PgAbstractFunction function, String expectedRaw,
                                        boolean keepNewLines,
                                        RoutineBodyRepresentation representation) {
        assertEquals(RoutineBodyProfile.current(keepNewLines),
                candidate.authorization().profile());
        assertSame(representation, candidate.authorization().representation());
        var deferred = new DeferredRoutineBodySource(candidate.authorization());

        RoutineBody shared = candidate.shareTo(deferred);

        assertEquals(expectedRaw, shared.raw());
        assertTrue(function.hasBodyReference(shared.canonical()));
        assertSame(shared, deferred.take());
        assertEquals(keepNewLines, shared.canonical().indexOf('\r') >= 0);
    }

    private static PgAbstractFunction find(PgSchema schema, String bareName, DbObjType kind) {
        return schema.getFunctions().stream()
                .map(PgAbstractFunction.class::cast)
                .filter(function -> function.getStatementType() == kind)
                .filter(function -> bareName.equals(function.getBareName()))
                .findFirst()
                .orElseThrow();
    }
}
