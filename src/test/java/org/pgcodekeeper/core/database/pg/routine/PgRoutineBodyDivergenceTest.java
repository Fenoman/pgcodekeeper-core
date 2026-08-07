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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.base.parser.launcher.DeferredAnalysisStateException;
import org.pgcodekeeper.core.database.pg.utils.PgDiffUtils;
import org.pgcodekeeper.core.utils.Utils;

/**
 * Verifies the never-equal property of the divergent sentinel and the fail-closed
 * lifecycle of a divergent deferred routine-body lease: a body that was never
 * fetched can never compare equal to any real body and can never be parsed.
 */
class PgRoutineBodyDivergenceTest {

    private static final RoutineBodyProfile PROFILE = RoutineBodyProfile.current(false);

    @Test
    void divergentLeaseFailsClosedOnTakeAndStaysUnmatched() {
        var source = new DeferredRoutineBodySource(authorization("BEGIN RETURN 1; END"));
        source.markDivergent();

        assertDivergentFailClosed(source);
        // idempotent close after divergence
        source.close();
        assertThrows(DeferredAnalysisStateException.class, source::take);
    }

    private static void assertDivergentFailClosed(DeferredRoutineBodySource source) {
        assertTrue(source.isDivergent());
        assertFalse(source.isResolvedByProjectMatch(),
                "a divergent lease must never look project-matched");
        var thrown = assertThrows(DeferredAnalysisStateException.class, source::take);
        assertTrue(thrown.getMessage().contains("never fetched"), thrown::getMessage);
    }

    @Test
    void divergentLeaseIgnoresLateProducerFailures() {
        var source = new DeferredRoutineBodySource(authorization("BEGIN RETURN 1; END"));
        source.markDivergent();

        source.fail(new RuntimeException("late producer failure"));

        assertTrue(source.isDivergent());
    }

    @Test
    void divergenceIsTerminalAndUnreachableFromOtherStates() {
        String raw = "BEGIN RETURN 1; END";
        var resolved = new DeferredRoutineBodySource(authorization(raw));
        resolved.resolve(raw, canonical(raw), PROFILE,
                RoutineBodyRepresentation.PLPGSQL_TEXT);

        assertThrows(IllegalStateException.class, resolved::markDivergent);

        var divergent = new DeferredRoutineBodySource(authorization(raw));
        divergent.markDivergent();
        assertThrows(IllegalStateException.class, divergent::markDivergent);
        assertThrows(IllegalStateException.class, () -> divergent.resolve(
                raw, canonical(raw), PROFILE, RoutineBodyRepresentation.PLPGSQL_TEXT));
    }

    @Test
    void sentinelNeverEqualsAnyRealCanonicalBodyForm() {
        String raw = "BEGIN RETURN 1; END";
        String sentinel = PgRoutineBodyDivergencePolicy.divergentSentinel(
                fingerprint(raw));

        assertSentinelNeverEquals(
                sentinel,
                // dollar-quoted plain-text body
                canonical(raw),
                // quoted-string probin body
                Utils.quoteString("$libdir/plpgsql"),
                // BEGIN ATOMIC statement text is stored verbatim
                "BEGIN ATOMIC\n SELECT 1;\nEND");
    }

    private static void assertSentinelNeverEquals(String sentinel, String... canonicals) {
        assertTrue(sentinel.startsWith("-- pgcodekeeper: routine body not fetched"),
                sentinel);
        for (String canonical : canonicals) {
            assertNotEquals(canonical, sentinel);
        }
    }

    @Test
    void sentinelIsDeterministicPerBodyAndDistinctAcrossBodies() {
        String first = "BEGIN RETURN 1; END";
        String second = "BEGIN RETURN 2; END";

        assertEquals(
                PgRoutineBodyDivergencePolicy.divergentSentinel(fingerprint(first)),
                PgRoutineBodyDivergencePolicy.divergentSentinel(fingerprint(first)),
                "byte-identical bodies must produce the same sentinel");
        assertNotEquals(
                PgRoutineBodyDivergencePolicy.divergentSentinel(fingerprint(first)),
                PgRoutineBodyDivergencePolicy.divergentSentinel(fingerprint(second)),
                "byte-different bodies must produce different sentinels");
    }

    @Test
    void policyEligibilityFollowsRepresentation() {
        var plpgsqlOnly = new PgRoutineBodyDivergencePolicy(true, false);
        var both = new PgRoutineBodyDivergencePolicy(true, true);

        assertTrue(plpgsqlOnly.isDivergenceEligible(RoutineBodyRepresentation.PLPGSQL_TEXT));
        assertFalse(plpgsqlOnly.isDivergenceEligible(RoutineBodyRepresentation.SQL_TEXT));
        assertTrue(both.isDivergenceEligible(RoutineBodyRepresentation.SQL_TEXT));
        assertFalse(both.isDivergenceEligible(RoutineBodyRepresentation.SQL_STATEMENT_BODY));
    }

    private static RoutineBodyAuthorization authorization(String raw) {
        return new RoutineBodyAuthorization(PROFILE,
                RoutineBodyRepresentation.PLPGSQL_TEXT, fingerprint(raw));
    }

    private static RoutineFingerprint fingerprint(String raw) {
        return (RoutineFingerprint) RoutineBody.create(raw, canonical(raw)).measure();
    }

    private static String canonical(String raw) {
        return Utils.checkNewLines(PgDiffUtils.quoteStringDollar(raw), false);
    }
}
