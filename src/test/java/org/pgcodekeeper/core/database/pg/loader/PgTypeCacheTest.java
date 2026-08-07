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
package org.pgcodekeeper.core.database.pg.loader;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.pg.jdbc.PgJdbcType;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader.CachedCollation;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader.CachedCollationRow;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader.CachedTypeRow;

class PgTypeCacheTest {

    private static final long LAST_SYS_OID = 16383L;

    @Test
    void typeCacheQueryHasNoElementNameSelfJoin() {
        String sql = PgJdbcLoader.QUERY_TYPES_FOR_CACHE_ALL;

        assertAll(
                () -> assertEquals(0, countOccurrences(sql, "elemname"), sql),
                () -> assertEquals(1, countOccurrences(sql, "pg_catalog.pg_type"), sql),
                () -> assertEquals(1, countOccurrences(sql, "LEFT JOIN"), sql),
                () -> assertTrue(sql.contains("t.oid"), sql),
                () -> assertTrue(sql.contains("t.typname"), sql),
                () -> assertTrue(sql.contains("t.typelem"), sql),
                () -> assertTrue(sql.contains("t.typarray"), sql),
                () -> assertTrue(sql.contains("t.typstorage"), sql),
                () -> assertTrue(sql.contains("t.typcollation::bigint"), sql),
                () -> assertTrue(sql.contains("n.nspname"), sql),
                () -> assertTrue(sql.contains(
                        "LEFT JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"), sql));
    }

    @Test
    void arrayTypeResolvesElementNameFromSameSnapshotRows() {
        Map<Long, PgJdbcType> cache = PgJdbcLoader.buildTypeCache(List.of(
                new CachedTypeRow(25L, "text", 0L, 1009L, "x", 100L, "pg_catalog"),
                new CachedTypeRow(1009L, "_text", 25L, 0L, "x", 0L, "pg_catalog"),
                new CachedTypeRow(20000L, "mood", 0L, 20001L, "p", 0L, "app"),
                new CachedTypeRow(20001L, "_mood", 20000L, 0L, "x", 0L, "app")),
                LAST_SYS_OID);

        assertAll(
                () -> assertEquals("text", cache.get(25L).getFullName()),
                () -> assertEquals("text[]", cache.get(1009L).getFullName()),
                () -> assertEquals("app.mood", cache.get(20000L).getFullName()),
                () -> assertEquals("app.mood[]", cache.get(20001L).getFullName()),
                () -> assertEquals("x", cache.get(25L).getStorage()),
                () -> assertEquals(100L, cache.get(25L).getCollation()),
                () -> assertEquals("x", cache.get(1009L).getStorage()),
                () -> assertEquals(0L, cache.get(1009L).getCollation()));
    }

    @Test
    void domainLikeTypeKeepsItsOwnRawStorageAndCollation() {
        Map<Long, PgJdbcType> cache = PgJdbcLoader.buildTypeCache(List.of(
                new CachedTypeRow(25L, "text", 0L, 1009L, "x", 100L, "pg_catalog"),
                new CachedTypeRow(20000L, "label", 0L, 20001L, "m", 90000L, "app")),
                LAST_SYS_OID);

        PgJdbcType domainLikeType = cache.get(20000L);

        assertAll(
                () -> assertEquals("app.label", domainLikeType.getFullName()),
                () -> assertEquals("m", domainLikeType.getStorage()),
                () -> assertEquals(90000L, domainLikeType.getCollation()));
    }

    @Test
    void vectorLikeTypeWithNonZeroTypArrayKeepsOwnName() {
        // int2vector: typelem != 0 but typarray != 0 -> not an array type
        Map<Long, PgJdbcType> cache = PgJdbcLoader.buildTypeCache(List.of(
                new CachedTypeRow(21L, "int2", 0L, 1005L, "p", 0L, "pg_catalog"),
                new CachedTypeRow(22L, "int2vector", 21L, 1006L, "p", 0L, "pg_catalog")),
                LAST_SYS_OID);

        assertEquals("int2vector", cache.get(22L).getFullName());
    }

    @Test
    void userArrayTypeAddsElementDependencyLikeReferenceJoin() {
        Map<Long, PgJdbcType> cache = PgJdbcLoader.buildTypeCache(List.of(
                new CachedTypeRow(20000L, "mood", 0L, 20001L, "p", 0L, "app"),
                new CachedTypeRow(20001L, "_mood", 20000L, 0L, "x", 0L, "app")),
                LAST_SYS_OID);

        var statement = new org.pgcodekeeper.core.database.pg.schema.PgSchema("other");
        cache.get(20001L).addTypeDepcy(statement);

        assertFalse(statement.getDependencies().isEmpty());
        assertTrue(statement.getDependencies().stream()
                .anyMatch(ref -> "mood".equals(ref.table())
                        && "app".equals(ref.schema())));
    }

    @Test
    void collationCacheContainsSystemAndUserSchemaEntries() {
        String sql = PgJdbcLoader.QUERY_COLLATIONS_FOR_CACHE_ALL;
        Map<Long, CachedCollation> cache = PgJdbcLoader.buildCollationCache(List.of(
                new CachedCollationRow(100L, "pg_catalog", "default"),
                new CachedCollationRow(90000L, "app", "quoted Name")));

        assertAll(
                () -> assertTrue(sql.contains("c.oid"), sql),
                () -> assertTrue(sql.contains("c.collname"), sql),
                () -> assertTrue(sql.contains("n.nspname"), sql),
                () -> assertTrue(sql.contains("FROM pg_catalog.pg_collation c"), sql),
                () -> assertTrue(sql.contains(
                        "LEFT JOIN pg_catalog.pg_namespace n ON c.collnamespace = n.oid"), sql),
                () -> assertEquals(0, countOccurrences(sql, "WHERE"), sql),
                () -> assertEquals(new CachedCollation("pg_catalog", "default"), cache.get(100L)),
                () -> assertEquals(new CachedCollation("app", "quoted Name"), cache.get(90000L)));
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int fromIndex = 0;
        while ((fromIndex = text.indexOf(fragment, fromIndex)) >= 0) {
            count++;
            fromIndex += fragment.length();
        }
        return count;
    }
}
