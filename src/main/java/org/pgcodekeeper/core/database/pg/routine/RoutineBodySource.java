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

/**
 * One-shot lease for a routine body retained until deferred analysis.
 */
public interface RoutineBodySource extends AutoCloseable {

    /**
     * Transfers this lease's payload to its analysis owner.
     *
     * @return the exact retained payload
     */
    RoutineBody take();

    /**
     * Returns the cached raw UTF-8 size without consuming the lease.
     */
    long estimatedUtf8Bytes();

    /**
     * Clears all retained payload and failure references. Safe to repeat.
     */
    @Override
    void close();
}
