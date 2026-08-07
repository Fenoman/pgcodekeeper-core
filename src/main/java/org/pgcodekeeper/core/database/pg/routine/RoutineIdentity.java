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

import java.util.Objects;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;

/**
 * Stable cross-loader identity of one PostgreSQL function or procedure.
 */
public record RoutineIdentity(String schemaName, DbObjType kind, String signature) {

    public RoutineIdentity {
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(signature, "signature");
        if (kind != DbObjType.FUNCTION && kind != DbObjType.PROCEDURE) {
            throw new IllegalArgumentException("Only functions and procedures have reusable bodies");
        }
    }

    public static RoutineIdentity from(PgAbstractFunction routine) {
        Objects.requireNonNull(routine, "routine");
        DbObjType kind = routine.getStatementType();
        if (kind != DbObjType.FUNCTION && kind != DbObjType.PROCEDURE) {
            throw new IllegalArgumentException("Only functions and procedures have reusable bodies");
        }
        if (routine.getParent() == null) {
            throw new IllegalArgumentException("Routine must be attached to its final schema");
        }
        return new RoutineIdentity(routine.getSchemaName(), kind, routine.getName());
    }
}
