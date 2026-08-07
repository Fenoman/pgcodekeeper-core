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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PgRoutineBodyResidualBatchPlannerTest {

    @Test
    void emptyAndInvalidRangesAreHandledDeterministically() {
        var limits = new PgRoutineBodyBatchLimits(64, 8L << 20);

        assertEquals(0, nextEnd(List.of(), 0, limits));
        assertThrows(IndexOutOfBoundsException.class,
                () -> nextEnd(List.of(1L), -1, limits));
        assertThrows(IndexOutOfBoundsException.class,
                () -> nextEnd(List.of(1L), 2, limits));
        assertThrows(IllegalArgumentException.class,
                () -> new PgRoutineBodyBatchLimits(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PgRoutineBodyBatchLimits(1, 0));
    }

    @Test
    void exactCountAndByteBoundariesRemainInOneBatch() {
        var countLimits = new PgRoutineBodyBatchLimits(3, 100);
        var byteLimits = new PgRoutineBodyBatchLimits(10, 6);

        assertEquals(3, nextEnd(List.of(1L, 1L, 1L, 1L), 0, countLimits));
        assertEquals(3, nextEnd(List.of(1L, 2L, 3L, 1L), 0, byteLimits));
    }

    @Test
    void countAndBytesChooseTheEarlierBoundary() {
        assertEquals(2, nextEnd(List.of(4L, 4L, 1L), 0,
                new PgRoutineBodyBatchLimits(10, 8)));
        assertEquals(2, nextEnd(List.of(1L, 1L, 1L), 0,
                new PgRoutineBodyBatchLimits(2, 100)));
    }

    @Test
    void oversizedBodiesAlwaysRunAloneWithoutLosingPriorRows() {
        var limits = new PgRoutineBodyBatchLimits(64, 8);
        List<Long> sizes = List.of(3L, 3L, 20L, 2L, 30L);

        int first = nextEnd(sizes, 0, limits);
        int oversizedMiddle = nextEnd(sizes, first, limits);
        int beforeLast = nextEnd(sizes, oversizedMiddle, limits);
        int oversizedLast = nextEnd(sizes, beforeLast, limits);

        assertEquals(2, first);
        assertEquals(3, oversizedMiddle);
        assertEquals(4, beforeLast);
        assertEquals(5, oversizedLast);
    }

    @Test
    void productionCountBoundaryDoesNotAllocateBatchLists() {
        List<Long> sizes = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            sizes.add(1L);
        }
        var limits = new PgRoutineBodyBatchLimits(64, 8L << 20);

        assertEquals(64, nextEnd(sizes, 0, limits));
        assertEquals(65, nextEnd(sizes, 64, limits));
    }

    @Test
    void arithmeticIsOverflowSafeAndZeroLengthIsAllowed() {
        var limits = new PgRoutineBodyBatchLimits(10, Long.MAX_VALUE);

        assertEquals(2, nextEnd(List.of(0L, Long.MAX_VALUE, 1L), 0, limits));
        assertThrows(IllegalArgumentException.class,
                () -> nextEnd(List.of(-1L), 0, limits));
    }

    @Test
    void matchedRowsDoNotConsumeResidualCountOrByteBudget() {
        List<Long> sizes = List.of(-1L, 1L, -1L, 2L, -1L, 3L);
        var limits = new PgRoutineBodyBatchLimits(2, 3);

        int first = PgRoutineBodyResidualBatchPlanner.nextEnd(
                sizes.size(), 0, limits, index -> index % 2 == 1, sizes::get);
        int second = PgRoutineBodyResidualBatchPlanner.nextEnd(
                sizes.size(), first, limits, index -> index % 2 == 1, sizes::get);
        int noResiduals = PgRoutineBodyResidualBatchPlanner.nextEnd(
                sizes.size(), 0, limits, index -> false, sizes::get);

        assertEquals(4, first);
        assertEquals(6, second);
        assertEquals(sizes.size(), noResiduals);
    }

    private static int nextEnd(List<Long> sizes, int start,
                               PgRoutineBodyBatchLimits limits) {
        return PgRoutineBodyResidualBatchPlanner.nextEnd(
                sizes.size(), start, limits, index -> sizes.get(index));
    }
}
