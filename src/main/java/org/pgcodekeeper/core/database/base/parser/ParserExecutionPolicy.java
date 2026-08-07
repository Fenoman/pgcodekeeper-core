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
package org.pgcodekeeper.core.database.base.parser;

/**
 * Selects either the process-wide parser pool or an operation-owned bounded
 * pool. Dedicated policies are immutable and safe to copy with settings.
 *
 * @param workers number of dedicated workers, or zero for the shared pool
 * @param maxPending maximum submitted task count for one loading operation
 * @param maxPendingBytes maximum retained parser input for one loading operation
 */
public record ParserExecutionPolicy(int workers, int maxPending,
                                    long maxPendingBytes) {

    public static final ParserExecutionPolicy SHARED =
            new ParserExecutionPolicy(0, 0, 0);

    public ParserExecutionPolicy {
        if (workers < 0) {
            throw new IllegalArgumentException("workers must not be negative");
        }
        if (workers == 0 && (maxPending != 0 || maxPendingBytes != 0)) {
            throw new IllegalArgumentException(
                    "shared policy cannot override queue limits");
        }
        if (workers > 0 && maxPending < workers) {
            throw new IllegalArgumentException(
                    "maxPending must cover every worker");
        }
        if (maxPendingBytes < 0) {
            throw new IllegalArgumentException(
                    "maxPendingBytes must not be negative");
        }
    }

    public static ParserExecutionPolicy dedicated(int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be positive");
        }
        return new ParserExecutionPolicy(workers,
                Math.multiplyExact(workers, 2), 64L << 20);
    }

    public boolean shared() {
        return workers == 0;
    }
}
