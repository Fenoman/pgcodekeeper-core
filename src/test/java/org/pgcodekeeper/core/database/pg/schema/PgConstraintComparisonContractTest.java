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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A constraint the hash tells apart may not report itself unchanged.
 *
 * <p>{@code AbstractStatement.hashIgnoringChildren} states the rule these cases
 * enforce: "a pair only the hash tells apart must not be called equal". Every
 * constraint hashes {@code deferrable}, {@code initially} and
 * {@code notEnforced}, so a comparison that ignores them breaks the guard the
 * hash is there to give.</p>
 *
 * <p>The foreign key used to break it. Its {@code compareUnalterable} overrode
 * the base one without calling it, and the three fields fell out of the answer
 * while staying in the hash. Nothing downstream noticed, because
 * {@code Comparison.compare} happens to test the hashes first — correctness
 * rested on the order of two checks rather than on the comparison itself, and
 * the relaxed branch added for {@code --ignore-column-order} had already cost a
 * migration its {@code ALTER CONSTRAINT} statements once.</p>
 */
class PgConstraintComparisonContractTest {

    private static Stream<Arguments> constraintsAndTheirHashedFlags() {
        return Stream.of(
                Arguments.of("foreign key", (Consumer<PgConstraint>) c -> { },
                        namedFk()),
                Arguments.of("primary key", (Consumer<PgConstraint>) c -> { },
                        namedPk()),
                Arguments.of("check", (Consumer<PgConstraint>) c -> { },
                        namedCheck()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("constraintsAndTheirHashedFlags")
    void aConstraintTheHashTellsApartIsNotUnchanged(String label,
            Consumer<PgConstraint> ignored, ConstraintFactory factory) {
        for (Flag flag : Flag.values()) {
            PgConstraint plain = factory.create();
            PgConstraint flagged = factory.create();
            flag.set(flagged);

            assertNotEquals(plain.hashCode(), flagged.hashCode(),
                    label + ": the hash must tell " + flag + " apart");
            assertFalse(plain.compare(flagged),
                    label + ": " + flag
                            + " differs, so the two are not the same constraint");
            assertFalse(flagged.compare(plain),
                    label + ": " + flag + " differs, the other way round too");
        }
    }

    private enum Flag {
        DEFERRABLE(c -> c.setDeferrable(true)),
        INITIALLY(c -> c.setInitially(true)),
        NOT_ENFORCED(c -> c.setNotEnforced(true));

        private final Consumer<PgConstraint> setter;

        Flag(Consumer<PgConstraint> setter) {
            this.setter = setter;
        }

        void set(PgConstraint constraint) {
            setter.accept(constraint);
        }
    }

    @FunctionalInterface
    private interface ConstraintFactory {
        PgConstraint create();
    }

    private static ConstraintFactory namedFk() {
        return () -> {
            var fk = new PgConstraintFk("fk_item_owner");
            fk.setForeignSchema("public");
            fk.setForeignTable("owner");
            fk.addColumn("owner_id");
            fk.addForeignColumn("id");
            return fk;
        };
    }

    private static ConstraintFactory namedPk() {
        return () -> {
            var pk = new PgConstraintPk("pk_item", true);
            pk.addColumn("id");
            return pk;
        };
    }

    private static ConstraintFactory namedCheck() {
        return () -> {
            var check = new PgConstraintCheck("ck_item_positive");
            check.setExpression("qty > 0", "qty > 0");
            return check;
        };
    }
}
