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
 * Stable, non-sensitive stages of the comparison pipeline.
 */
public enum ComparisonStage {
    PREPARE,
    OLD_STRUCTURAL_LOAD,
    NEW_STRUCTURAL_LOAD,
    STRUCTURAL_BARRIER,
    OLD_FULL_ANALYZE,
    NEW_FULL_ANALYZE,
    ANALYSIS_BARRIER,
    LOADERS_CLOSE,
    DATABASE_LOAD_TOTAL,
    DIFF_TREE_CREATE
}
