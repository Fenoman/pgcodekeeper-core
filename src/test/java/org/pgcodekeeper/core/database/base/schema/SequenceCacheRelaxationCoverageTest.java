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
package org.pgcodekeeper.core.database.base.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ISequence;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.ms.schema.MsSequence;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;

/**
 * The comparison of a sequence without its cache must overlook the cache and
 * nothing else - and must go on doing so after somebody adds a field.
 * <p>
 * {@code ISettings.isIgnoreSequenceCache} is the one place in the tool where a
 * comparison deciding a diff is deliberately narrower than the state of the
 * object, and it has no children to fall back on the way a table does: the
 * relaxed comparison and the relaxed hash are all that stand between the
 * setting and a lost migration. What guards them here is mechanical rather than
 * careful reading, because careful reading is what failed the last time - see
 * {@code PgConstraintFk.compareUnalterable}, which stopped calling {@code super}
 * and thereby dropped {@code ALTER CONSTRAINT} out of real migration scripts.
 * <p>
 * Two things are pinned. Every field of a sequence is named below, so that a new
 * one cannot appear without somebody deciding what the relaxation does with it;
 * and every field is then changed on its own, so that the relaxed comparison and
 * the relaxed hash are shown to react to all of them but the cache.
 */
class SequenceCacheRelaxationCoverageTest {

    /**
     * The fields of {@code PgSequence}, cache included. Changing this list means
     * deciding what {@code compareIgnoringCache} does about the change.
     */
    private static final Set<String> PG_FIELDS = Set.of(
            "ownedBy", "isLogged", "cache", "dataType", "startWith", "increment", "maxValue", "minValue", "cycle");

    /** The fields of {@code MsSequence}, where the cache is two of them. */
    private static final Set<String> MS_FIELDS = Set.of(
            "isCached", "dataType", "startWith", "increment", "maxValue", "minValue", "cache", "cycle");

    /** The fields the relaxation is allowed to overlook, and only those. */
    private static final Set<String> PG_CACHE_FIELDS = Set.of("cache");
    private static final Set<String> MS_CACHE_FIELDS = Set.of("isCached", "cache");

    @Test
    void everyFieldOfAPostgresSequenceIsAccountedFor() {
        assertEquals(PG_FIELDS, fieldsOf(PgSequence.class),
                "a field was added to PgSequence: decide whether compareIgnoringCache must see it");
    }

    @Test
    void everyFieldOfAnMsSequenceIsAccountedFor() {
        assertEquals(MS_FIELDS, fieldsOf(MsSequence.class),
                "a field was added to MsSequence: decide whether compareIgnoringCache must see it");
    }

    @Test
    void thePostgresRelaxationOverlooksTheCacheAndNothingElse() {
        assertRelaxationCovers(PgSequence.class, PgSequence::new, PG_CACHE_FIELDS);
    }

    @Test
    void theMsRelaxationOverlooksTheCacheClauseAndNothingElse() {
        assertRelaxationCovers(MsSequence.class, MsSequence::new, MS_CACHE_FIELDS);
    }

    /**
     * Changes one field of a sequence at a time and demands the answer the
     * relaxation promises: the cache is overlooked by the relaxed comparison and
     * by the relaxed hash together, everything else is seen by both, and plain
     * equality and the plain hash see all of it.
     */
    private static <T extends ISequence> void assertRelaxationCovers(Class<T> type,
                                                                     Function<String, T> factory,
                                                                     Set<String> cacheFields) {
        for (Field field : instanceFields(type)) {
            T one = factory.apply("s");
            T other = factory.apply("s");
            set(field, other, drifted(field, get(field, other)));

            String what = type.getSimpleName() + '.' + field.getName();
            assertNotEquals(one, other, what + " must be part of plain equality");
            assertNotEquals(one.hashCode(), other.hashCode(), what + " must be part of the plain hash");

            boolean isCache = cacheFields.contains(field.getName());
            assertEquals(isCache, one.compareIgnoringCache(other),
                    what + (isCache ? " must be overlooked by compareIgnoringCache"
                            : " must be seen by compareIgnoringCache"));
            if (isCache) {
                assertEquals(one.hashIgnoringCache(), other.hashIgnoringCache(),
                        what + " must be overlooked by hashIgnoringCache as well");
            } else {
                assertNotEquals(one.hashIgnoringCache(), other.hashIgnoringCache(),
                        what + " must be seen by hashIgnoringCache as well");
            }
        }
    }

    /**
     * A value the given field does not hold, so that setting it is a change.
     */
    private static Object drifted(Field field, Object current) {
        Class<?> type = field.getType();
        if (type == boolean.class) {
            return !(boolean) current;
        }
        if (type == String.class) {
            return "7".equals(current) ? "11" : "7";
        }
        if (type == ObjectReference.class) {
            return current == null
                    ? new ObjectReference("public", "t", "c", DbObjType.COLUMN)
                    : null;
        }
        return fail("no drifted value for " + field.getName() + " of type " + type
                + ": teach this test about it before adding such a field");
    }

    private static Set<String> fieldsOf(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        instanceFields(type).forEach(field -> names.add(field.getName()));
        return names;
    }

    /**
     * @return the declared, non-static fields of the class itself; the inherited
     * state is covered by {@code super.compare}, which both comparisons share
     */
    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                field.setAccessible(true);
                fields.add(field);
            }
        }
        assertFalse(fields.isEmpty(), "no fields found on " + type);
        return fields;
    }

    private static Object get(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            return fail(e);
        }
    }

    private static void set(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            fail(e);
        }
    }
}
