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
import java.util.function.IntToLongFunction;
import java.util.function.IntPredicate;

/**
 * Computes one bounded index range without allocating sublists or retaining a
 * second corpus-sized collection.
 */
final class PgRoutineBodyResidualBatchPlanner {

    private PgRoutineBodyResidualBatchPlanner() {
    }

    static int nextEnd(int size, int start, PgRoutineBodyBatchLimits limits,
                       IntToLongFunction predictedUtf8Bytes) {
        return nextEnd(size, start, limits, index -> true, predictedUtf8Bytes);
    }

    static int nextEnd(int size, int start, PgRoutineBodyBatchLimits limits,
                       IntPredicate include,
                       IntToLongFunction predictedUtf8Bytes) {
        if (size < 0 || start < 0 || start > size) {
            throw new IndexOutOfBoundsException(
                    "Invalid residual body range: " + start + " of " + size);
        }
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(include, "include");
        Objects.requireNonNull(predictedUtf8Bytes, "predictedUtf8Bytes");
        if (start == size) {
            return size;
        }

        int end = start;
        int batchCount = 0;
        long batchBytes = 0L;
        while (end < size && batchCount < limits.maxCount()) {
            if (!include.test(end)) {
                end++;
                continue;
            }
            long nextBytes = predictedUtf8Bytes.applyAsLong(end);
            if (nextBytes < 0L) {
                throw new IllegalArgumentException(
                        "Predicted routine body bytes must be nonnegative");
            }
            if (batchCount > 0
                    && nextBytes > limits.maxPredictedUtf8Bytes() - batchBytes) {
                break;
            }

            batchBytes += nextBytes;
            batchCount++;
            end++;
            if (batchBytes >= limits.maxPredictedUtf8Bytes()) {
                break;
            }
        }
        return end;
    }
}
