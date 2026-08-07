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
package org.pgcodekeeper.core.database.base.schema.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.ICast;
import org.pgcodekeeper.core.database.api.schema.ICast.CastContext;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;

/**
 * Pins the answers {@link MetaContainer#containsCastImplicit} gives, which the analyzer asks
 * once per candidate per argument while resolving an overloaded call.
 * <p>
 * The container answers out of an index rather than by walking every cast it holds, and an
 * index has two ways to go wrong that a walk did not have: it can lose a cast whose source it
 * already has under another target, and it can forget which of the two type names was the key.
 * Both are asked about here.
 */
class MetaContainerCastIndexTest {

    @Test
    void findsACastItWasGiven() {
        MetaContainer container = containerOf(implicit("int4", "int8"));

        assertTrue(container.containsCastImplicit("int4", "int8"));
    }

    @Test
    void doesNotInventACastItWasNotGiven() {
        MetaContainer container = containerOf(implicit("int4", "int8"));

        assertFalse(container.containsCastImplicit("int4", "numeric"));
        assertFalse(container.containsCastImplicit("text", "int8"));
        assertFalse(container.containsCastImplicit("text", "numeric"));
    }

    /**
     * The cast goes one way. Answering the reversed question yes would mean the index keeps
     * the pair without keeping which name was the source.
     */
    @Test
    void doesNotAnswerTheReversedPairYes() {
        MetaContainer container = containerOf(implicit("int4", "int8"));

        assertFalse(container.containsCastImplicit("int8", "int4"));
    }

    /**
     * Several casts out of one source type. All of them have to survive being stored under the
     * one key, which is where an index that keeps a single target per source loses them.
     */
    @Test
    void keepsEveryTargetOfOneSourceType() {
        MetaContainer container = containerOf(
                implicit("int4", "int8"),
                implicit("int4", "numeric"),
                implicit("int4", "float8"));

        assertTrue(container.containsCastImplicit("int4", "int8"));
        assertTrue(container.containsCastImplicit("int4", "numeric"));
        assertTrue(container.containsCastImplicit("int4", "float8"));
        assertFalse(container.containsCastImplicit("int4", "text"));
    }

    @Test
    void keepsEverySourceOfOneTargetType() {
        MetaContainer container = containerOf(
                implicit("int2", "numeric"),
                implicit("int4", "numeric"),
                implicit("int8", "numeric"));

        assertTrue(container.containsCastImplicit("int2", "numeric"));
        assertTrue(container.containsCastImplicit("int4", "numeric"));
        assertTrue(container.containsCastImplicit("int8", "numeric"));
        assertFalse(container.containsCastImplicit("float4", "numeric"));
    }

    /**
     * Only an implicit cast counts. An assignment or explicit one is not an answer to this
     * question and must not reach the index.
     */
    @Test
    void ignoresCastsOfAnyOtherContext() {
        MetaContainer container = containerOf(
                cast("int4", "text", CastContext.ASSIGNMENT),
                cast("text", "int4", CastContext.EXPLICIT),
                implicit("int4", "int8"));

        assertFalse(container.containsCastImplicit("int4", "text"));
        assertFalse(container.containsCastImplicit("text", "int4"));
        assertTrue(container.containsCastImplicit("int4", "int8"));
    }

    @Test
    void toleratesTheSameCastTwice() {
        MetaContainer container = containerOf(
                implicit("int4", "int8"),
                implicit("int4", "int8"));

        assertTrue(container.containsCastImplicit("int4", "int8"));
    }

    @Test
    void answersNoWhenItHoldsNoCastAtAll() {
        assertFalse(new MetaContainer().containsCastImplicit("int4", "int8"));
    }

    /**
     * The real question, asked of the real system catalog: every implicit cast the shipped
     * PostgreSQL 17 catalog holds must be found, and its reverse must be answered on its own
     * merits rather than by accident. This is the corpus the analyzer actually queries.
     */
    @Test
    void answersForEveryImplicitCastOfTheShippedCatalog() {
        MetaContainer container = new MetaContainer();
        List<ICast> implicitCasts = new ArrayList<>();
        for (var statement : MetaStorage.getSystemObjects(PgSupportedVersion.VERSION_17)) {
            container.addStatement(statement);
            if (statement instanceof ICast cast && cast.getContext() == CastContext.IMPLICIT) {
                implicitCasts.add(cast);
            }
        }

        assertFalse(implicitCasts.isEmpty(), "the shipped catalog holds no implicit cast");
        for (ICast cast : implicitCasts) {
            assertTrue(container.containsCastImplicit(cast.getSource(), cast.getTarget()),
                    () -> "lost implicit cast " + cast.getSource() + " -> " + cast.getTarget());
        }

        long reversedFound = implicitCasts.stream()
                .filter(c -> container.containsCastImplicit(c.getTarget(), c.getSource()))
                .count();
        long reversedDeclared = implicitCasts.stream()
                .filter(c -> implicitCasts.stream().anyMatch(
                        o -> o.getSource().equals(c.getTarget()) && o.getTarget().equals(c.getSource())))
                .count();
        assertEquals(reversedDeclared, reversedFound,
                "a reversed pair is found only when the catalog declares it");
    }

    private static MetaContainer containerOf(ICast... casts) {
        MetaContainer container = new MetaContainer();
        for (ICast cast : casts) {
            container.addStatement(cast);
        }
        return container;
    }

    private static ICast implicit(String source, String target) {
        return cast(source, target, CastContext.IMPLICIT);
    }

    private static ICast cast(String source, String target, CastContext context) {
        return new MetaCast(source, target, context);
    }
}
