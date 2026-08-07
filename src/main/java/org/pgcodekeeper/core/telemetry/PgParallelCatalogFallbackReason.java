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
package org.pgcodekeeper.core.telemetry;

/**
 * Reason the PostgreSQL lane-parallel catalog readers fell back to the
 * sequential flow. The reason is a closed enum on purpose: the failure text
 * may name servers, roles or paths and never leaves the log.
 */
public enum PgParallelCatalogFallbackReason {
    /** The worker-connection snapshot export probe failed. */
    SNAPSHOT_PROBE_FAILED,
    /** A worker connection could not be opened or synchronized. */
    LANE_SETUP_FAILED
}
