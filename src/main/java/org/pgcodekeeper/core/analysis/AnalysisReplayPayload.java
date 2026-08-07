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
package org.pgcodekeeper.core.analysis;

import java.util.List;
import java.util.Objects;

import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;

/**
 * Everything a full analysis leaves behind in one loaded project model.
 * <p>
 * The payload is the replay unit of the analyzed-model cache: it is captured
 * from a model that was analyzed in full and applied to a model that was only
 * loaded structurally, which turns the analysis phase of a warm run into a
 * bounded copy instead of a re-parse of every routine body and view.
 * <p>
 * The three parts mirror the three effects a full analysis has on the model:
 * dependency edges gained by statements, object locations gained by the
 * file-to-location reverse index, and the routines whose late-bound body was
 * skipped. Nothing else is written onto a statement during analysis - operator,
 * aggregate and view resolution all land in the throwaway metadata container,
 * which the comparison rebuilds and never reads back.
 * <p>
 * Dependency and location lists preserve the capture order, because both target
 * containers are insertion-ordered and the model is only identical to a cold
 * one when the order matches too.
 *
 * @param dependencies      per-statement dependency edges of the analyzed model
 * @param references        per-file object locations of the analyzed model
 * @param suppressedRoutines routines whose late-bound body analysis was skipped
 * @param statementCount    number of statements of the analyzed model, used to
 *                          reject a payload against a differently shaped model
 */
public record AnalysisReplayPayload(
        List<StatementDependencies> dependencies,
        List<FileReferences> references,
        List<StatementAddress> suppressedRoutines,
        int statementCount) {

    /**
     * Dependency edges of one statement, in their model order.
     *
     * @param address    identity of the statement inside the model
     * @param references dependency edges the statement carries
     */
    public record StatementDependencies(
            StatementAddress address, List<ObjectReference> references) {

        public StatementDependencies {
            Objects.requireNonNull(address, "address");
            references = List.copyOf(Objects.requireNonNull(references, "references"));
        }
    }

    /**
     * Object locations of one file, in their model order.
     *
     * @param filePath  key of the reverse index, as the model stores it
     * @param locations locations recorded for that file
     */
    public record FileReferences(String filePath, List<ObjectLocation> locations) {

        public FileReferences {
            Objects.requireNonNull(filePath, "filePath");
            locations = List.copyOf(Objects.requireNonNull(locations, "locations"));
        }
    }

    public AnalysisReplayPayload {
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        suppressedRoutines = List.copyOf(
                Objects.requireNonNull(suppressedRoutines, "suppressedRoutines"));
        if (statementCount < 0) {
            throw new IllegalArgumentException("statementCount must not be negative");
        }
    }

    /**
     * Returns the total number of dependency edges of this payload.
     *
     * @return dependency edge count
     */
    public long dependencyCount() {
        return dependencies.stream().mapToLong(entry -> entry.references().size()).sum();
    }

    /**
     * Returns the total number of object locations of this payload.
     *
     * @return object location count
     */
    public long locationCount() {
        return references.stream().mapToLong(entry -> entry.locations().size()).sum();
    }
}
