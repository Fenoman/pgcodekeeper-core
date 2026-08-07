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
package org.pgcodekeeper.core.database.pg.routine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.ArgMode;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.pg.schema.PgAggregate;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgProcedure;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;

class RoutineIdentityProfileTest {

    @Test
    void identityUsesFinalCanonicalSignatureAndRoutineKind() {
        var functionSchema = new PgSchema("Mixed Schema");
        var procedureSchema = new PgSchema("Mixed Schema");
        var function = new PgFunction("calc\"value");
        var procedure = new PgProcedure("calc\"value");
        function.addArgument(function.new PgArgument(ArgMode.IN, "value", "integer"));
        procedure.addArgument(procedure.new PgArgument(ArgMode.IN, "value", "integer"));
        functionSchema.addChild(function);
        procedureSchema.addChild(procedure);

        RoutineIdentity functionIdentity = RoutineIdentity.from(function);
        RoutineIdentity procedureIdentity = RoutineIdentity.from(procedure);

        assertAll(
                () -> assertEquals(functionSchema.getName(), functionIdentity.schemaName()),
                () -> assertEquals(DbObjType.FUNCTION, functionIdentity.kind()),
                () -> assertSame(function.getName(), functionIdentity.signature()),
                () -> assertEquals(DbObjType.PROCEDURE, procedureIdentity.kind()),
                () -> assertSame(procedure.getName(), procedureIdentity.signature()),
                () -> assertNotEquals(functionIdentity, procedureIdentity));
    }

    @Test
    void identityRejectsAggregatesAndParentlessRoutines() {
        var aggregate = new PgAggregate("sum_value");
        var parentless = new PgFunction("orphan");
        var schema = new PgSchema("public");
        schema.addChild(aggregate);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RoutineIdentity.from(aggregate)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RoutineIdentity.from(parentless)));
    }

    @Test
    void currentProfilesAreSharedValuesAndKeepNewLinesParticipatesInEquality() {
        RoutineBodyProfile compact = RoutineBodyProfile.current(false);
        RoutineBodyProfile compactAgain = RoutineBodyProfile.current(false);
        RoutineBodyProfile preserving = RoutineBodyProfile.current(true);

        assertAll(
                () -> assertEquals(compact, compactAgain),
                () -> assertSame(compact, compactAgain),
                () -> assertSame(preserving, RoutineBodyProfile.current(true)),
                () -> assertNotEquals(compact, preserving),
                // protocol 2: fingerprints measure the profile-normalized body
                () -> assertEquals(2, compact.protocolVersion()),
                () -> assertEquals(1, compact.parserVersion()),
                () -> assertEquals(1, compact.canonicalizerVersion()),
                () -> assertEquals(RoutineBodyProfile.HashAlgorithm.SHA_256,
                        compact.hashAlgorithm()));
    }

    @Test
    void onlyRawSqlAndPlpgsqlTextRepresentationsAreExchangeEligible() {
        assertAll(
                () -> assertTrue(RoutineBodyRepresentation.SQL_TEXT.isExchangeEligible()),
                () -> assertTrue(RoutineBodyRepresentation.PLPGSQL_TEXT.isExchangeEligible()),
                () -> assertFalse(RoutineBodyRepresentation.SQL_STATEMENT_BODY.isExchangeEligible()));
    }

    @Test
    void profileRejectsInvalidVersionsAndNullAlgorithm() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RoutineBodyProfile(0, false, 1,
                                RoutineBodyProfile.HashAlgorithm.SHA_256, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RoutineBodyProfile(1, false, -1,
                                RoutineBodyProfile.HashAlgorithm.SHA_256, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RoutineBodyProfile(1, false, 1,
                                RoutineBodyProfile.HashAlgorithm.SHA_256, 0)),
                () -> assertThrows(NullPointerException.class,
                        () -> new RoutineBodyProfile(1, false, 1, null, 1)));
    }
}
