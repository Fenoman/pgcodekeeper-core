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
import org.pgcodekeeper.core.database.api.schema.IDatabase;

/**
 * Every database level child a {@link PgDatabase} publishes must take part in
 * its comparison and in its hash.
 * <p>
 * A collection left out of both makes an incomplete database compare equal to
 * an honestly empty one, and leaving it out of one of the two breaks the guard
 * the hash gives the comparison. User mappings used to be the one such
 * collection: {@code fillChildrenList} and {@code getChild} served them while
 * {@code compareChildren} and {@code computeChildrenHash} did not.
 * <p>
 * Schemas are the deliberate exception and are checked below to stay one, so
 * that the omission stays a documented decision rather than another oversight.
 */
final class PgDatabaseChildrenComparisonTest {

    @Test
    void aUserMappingIsNotInvisibleToTheComparison() {
        PgDatabase withMapping = databaseWithServer();
        withMapping.addChild(new PgUserMapping("bob", "srv"));
        PgDatabase withoutMapping = databaseWithServer();

        Assertions.assertAll(
                () -> Assertions.assertNotEquals(withMapping, withoutMapping,
                        "a database holding a user mapping must not equal one that holds none"),
                () -> Assertions.assertNotEquals(withMapping.hashCode(), withoutMapping.hashCode(),
                        "and must not hash the same either"));
    }

    @Test
    void aChangedUserMappingIsNotInvisibleToTheComparison() {
        PgDatabase one = databaseWithServer();
        PgUserMapping mapping = new PgUserMapping("bob", "srv");
        mapping.addOption("user", "bob");
        one.addChild(mapping);

        PgDatabase other = databaseWithServer();
        PgUserMapping changed = new PgUserMapping("bob", "srv");
        changed.addOption("user", "alice");
        other.addChild(changed);

        Assertions.assertAll(
                () -> Assertions.assertNotEquals(one, other, "a changed mapping option must be seen"),
                () -> Assertions.assertNotEquals(one.hashCode(), other.hashCode(),
                        "and must reach the hash"));
    }

    @Test
    void identicalUserMappingsStillCompareEqual() {
        PgDatabase one = databaseWithServer();
        one.addChild(new PgUserMapping("bob", "srv"));
        PgDatabase other = databaseWithServer();
        other.addChild(new PgUserMapping("bob", "srv"));

        Assertions.assertAll(
                () -> Assertions.assertEquals(one, other, "equal databases must stay equal"),
                () -> Assertions.assertEquals(one.hashCode(), other.hashCode(),
                        "and must keep hashing the same"));
    }

    @Test
    void aDeepCopyStillEqualsItsOriginal() {
        PgDatabase original = databaseWithServer();
        PgUserMapping mapping = new PgUserMapping("bob", "srv");
        mapping.addOption("user", "bob");
        original.addChild(mapping);

        IDatabase copy = (IDatabase) original.deepCopy();

        // the comparison is only allowed to get stricter where a copy can
        // follow it: a graph looks its vertices up by value, and a copy that
        // dropped a field the comparison now reads would stop being found
        Assertions.assertAll(
                () -> Assertions.assertEquals(original, copy, "a deep copy must equal its original"),
                () -> Assertions.assertEquals(original.hashCode(), copy.hashCode(),
                        "and must hash the same"));
    }

    /**
     * Schemas are the one child collection the database comparison leaves out
     * on purpose. Pinned here so that the omission stays a decision somebody
     * made rather than another oversight nobody noticed.
     */
    @Test
    void schemasStayTheOnlyChildLeftOutOfTheComparison() {
        PgDatabase withSchema = databaseWithServer();
        withSchema.addChild(new PgSchema("extra"));
        PgDatabase withoutSchema = databaseWithServer();

        Assertions.assertEquals(withSchema, withoutSchema,
                "schemas are deliberately not compared at the database level, "
                        + "which is why hashes in the loader tests are taken per schema");
    }

    private static PgDatabase databaseWithServer() {
        PgDatabase db = new PgDatabase(false);
        db.addChild(new PgServer("srv"));
        return db;
    }
}
