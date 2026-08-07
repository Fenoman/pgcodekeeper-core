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

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.utils.Pair;

class MetaUtilsCancellationTest {

    private static final int COLUMN_COUNT = 4096;

    @Test
    void cancellationStopsInsideSingleRelationMaterialization() {
        AtomicInteger columnsRead = new AtomicInteger();
        PgDatabase db = databaseWithWideRelation(columnsRead, -1);
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return columnsRead.get() >= 256;
            }
        };

        assertThrows(InterruptedException.class,
                () -> MetaUtils.createTreeFromDb(
                        db, PgSupportedVersion.VERSION_15, monitor));

        assertAll(
                () -> assertTrue(columnsRead.get() >= 256),
                () -> assertTrue(columnsRead.get() < COLUMN_COUNT,
                        "cancellation was observed only after the whole relation was copied"));
    }

    @Test
    void monitorFailureInsideMaterializationPreservesIdentity() {
        AtomicInteger columnsRead = new AtomicInteger();
        PgDatabase db = databaseWithWideRelation(columnsRead, -1);
        RuntimeException monitorFailure = new IllegalStateException(
                "controlled metadata monitor failure");
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                if (columnsRead.get() >= 256) {
                    throw monitorFailure;
                }
                return false;
            }
        };

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> MetaUtils.createTreeFromDb(
                        db, PgSupportedVersion.VERSION_15, monitor));

        assertAll(
                () -> assertSame(monitorFailure, thrown),
                () -> assertTrue(columnsRead.get() < COLUMN_COUNT,
                        "monitor failure was observed only after full materialization"));
    }

    @Test
    void threadInterruptStopsInsideSingleRelationMaterialization() {
        AtomicInteger columnsRead = new AtomicInteger();
        PgDatabase db = databaseWithWideRelation(columnsRead, 300);

        try {
            assertThrows(InterruptedException.class,
                    () -> MetaUtils.createTreeFromDb(
                            db, PgSupportedVersion.VERSION_15, new NullMonitor()));

            assertAll(
                    () -> assertTrue(Thread.currentThread().isInterrupted()),
                    () -> assertTrue(columnsRead.get() < COLUMN_COUNT,
                            "thread interruption was observed only after full materialization"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void monitoredMaterializationPreservesKnownEmptyRelationColumns()
            throws InterruptedException {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        db.addChild(schema);
        schema.addChild(new PgSimpleTable("empty_relation"));

        MetaContainer unmonitored = MetaUtils.createTreeFromDb(
                db, PgSupportedVersion.VERSION_15);
        MetaContainer monitored = MetaUtils.createTreeFromDb(
                db, PgSupportedVersion.VERSION_15, new NullMonitor());

        var unmonitoredColumns = unmonitored.findRelation(
                "public", "empty_relation").getRelationColumns();
        var monitoredColumns = monitored.findRelation(
                "public", "empty_relation").getRelationColumns();
        assertAll(
                () -> assertNotNull(unmonitoredColumns),
                () -> assertNotNull(monitoredColumns),
                () -> assertEquals(unmonitoredColumns.toList(), monitoredColumns.toList()));
    }

    private static PgDatabase databaseWithWideRelation(
            AtomicInteger columnsRead, int interruptAt) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        var table = new PgSimpleTable("wide_relation") {
            @Override
            public Stream<Pair<String, String>> getRelationColumns() {
                return IntStream.range(0, COLUMN_COUNT)
                        .mapToObj(i -> {
                            int read = columnsRead.incrementAndGet();
                            if (read == interruptAt) {
                                Thread.currentThread().interrupt();
                            }
                            return new Pair<>("column_" + i, "integer");
                        });
            }
        };
        db.addChild(schema);
        schema.addChild(table);
        return db;
    }
}
