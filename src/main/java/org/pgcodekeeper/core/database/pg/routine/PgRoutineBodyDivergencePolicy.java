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
 * Old-side fetch-skip policy for fingerprint slots left unmatched by the
 * project catalog. An eligible unmatched slot resolves to a divergent
 * sentinel instead of fetching its body text: the hash-first fingerprint
 * mismatch is authoritative proof of inequality for the comparison, and the
 * old-side analysis skip guarantees the text is never parsed. The policy
 * exists only when the loaded database model is the OLD side of a
 * project-database comparison; every other load keeps fetching.
 *
 * @param plpgsqlEligible whether PL/pgSQL bodies may skip the fetch
 * @param sqlEligible     whether quoted SQL bodies may skip the fetch
 */
public record PgRoutineBodyDivergencePolicy(boolean plpgsqlEligible, boolean sqlEligible) {

    /**
     * Returns whether an unmatched slot of the given representation may skip
     * its residual fetch and resolve to the divergent sentinel.
     */
    public boolean isDivergenceEligible(RoutineBodyRepresentation representation) {
        return switch (representation) {
            case PLPGSQL_TEXT -> plpgsqlEligible;
            case SQL_TEXT -> sqlEligible;
            case SQL_STATEMENT_BODY -> false;
        };
    }

    /**
     * Builds the deterministic sentinel stored as the model body of a
     * divergent routine. The sentinel is derived from the exact fingerprint,
     * so byte-different bodies produce different sentinels, and its comment
     * prefix can never collide with a real canonical body, which always
     * starts with a dollar quote, a single quote or a {@code BEGIN ATOMIC}
     * statement text.
     *
     * @param fingerprint exact raw UTF-8 length and SHA-256 of the unfetched body
     * @return sentinel text that never compares equal to a fetched body
     */
    public static String divergentSentinel(RoutineFingerprint fingerprint) {
        return "-- pgcodekeeper: routine body not fetched, fingerprint mismatch"
                + " (sha256=%016x%016x%016x%016x, utf8_length=%d)".formatted(
                        fingerprint.hash0(), fingerprint.hash1(),
                        fingerprint.hash2(), fingerprint.hash3(),
                        fingerprint.utf8Length());
    }
}
