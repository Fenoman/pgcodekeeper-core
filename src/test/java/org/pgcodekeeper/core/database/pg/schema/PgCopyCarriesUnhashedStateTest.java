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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.ObjectState;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * A copy has to carry the fields that stay out of {@code compare} and out of
 * {@code computeHash}, exactly because nothing else will notice when it does
 * not.
 * <p>
 * Both fields below are read from the catalog and never written in a project
 * file, so keeping them out of the comparison is right - a project must not be
 * told it differs from the server over a property no DDL of it can set. The
 * cost of that is that a copy dropping one of them cannot be told from a
 * faithful copy by any equality check, and the loss only surfaces much later:
 * {@code relocatable} decides between {@code ALTER EXTENSION SET SCHEMA} and a
 * full recreate, {@code returns} decides the type an expression over the
 * operator is given.
 */
final class PgCopyCarriesUnhashedStateTest {

    @Test
    void operatorCopyKeepsItsResultType() {
        PgOperator operator = new PgOperator("+");
        operator.setProcedure("public.add");
        operator.setLeftArg("integer");
        operator.setRightArg("integer");
        operator.setReturns("integer");

        PgOperator copy = (PgOperator) operator.shallowCopy();

        Assertions.assertEquals("integer", copy.getReturns(),
                "the result type of an operator must survive a copy");
    }

    @Test
    void operatorCopyStillHashesLikeItsOriginal() {
        PgOperator operator = new PgOperator("+");
        operator.setProcedure("public.add");
        operator.setReturns("integer");

        PgOperator copy = (PgOperator) operator.shallowCopy();

        // the result type stays out of compare and out of the hash, so carrying
        // it must not make the copy differ from what it is a copy of
        Assertions.assertAll(
                () -> Assertions.assertEquals(operator, copy, "a copy must equal its original"),
                () -> Assertions.assertEquals(operator.hashCode(), copy.hashCode(),
                        "and must hash the same"));
    }

    @Test
    void extensionCopyKeepsItsRelocatability() {
        PgExtension extension = new PgExtension("citext");
        extension.setSchema("public");
        extension.setRelocatable(true);

        PgExtension copy = (PgExtension) extension.shallowCopy();

        PgExtension moved = new PgExtension("citext");
        moved.setSchema("other");

        Assertions.assertAll(
                () -> Assertions.assertEquals(ObjectState.ALTER_WITH_DEP, alter(copy, moved),
                        "a relocatable extension moves with ALTER EXTENSION SET SCHEMA, "
                                + "and a copy that lost the flag would recreate it instead"),
                () -> Assertions.assertEquals(alter(extension, moved), alter(copy, moved),
                        "the copy must answer exactly what the original answers"));
    }

    @Test
    void extensionCopyOfANonRelocatableExtensionStillRecreates() {
        PgExtension extension = new PgExtension("citext");
        extension.setSchema("public");

        PgExtension copy = (PgExtension) extension.shallowCopy();

        PgExtension moved = new PgExtension("citext");
        moved.setSchema("other");

        Assertions.assertEquals(ObjectState.RECREATE, alter(copy, moved),
                "a copy must not invent relocatability either");
    }

    private static ObjectState alter(PgExtension from, PgExtension to) {
        return from.appendAlterSQL(to, new SQLScript(new CoreSettings(), ";"));
    }
}
