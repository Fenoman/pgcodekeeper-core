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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.base.parser.launcher.DeferredAnalysisStateException;

class RoutineBodySourceTest {

    @Test
    void exactMatchSharesOnePayloadAcrossIndependentOneShotLeases() {
        String raw = new String("SELECT 'Привет 😀';");
        String canonical = new String("$body$" + raw + "$body$");
        var owned = exchangeCandidate(raw, canonical);
        var deferred = new DeferredRoutineBodySource(owned.requireAuthorization());

        RoutineBody shared = owned.shareTo(deferred);
        RoutineBody jdbc = deferred.take();
        RoutineBody project = owned.take();

        assertAll(
                () -> assertSame(shared, jdbc),
                () -> assertSame(shared, project),
                () -> assertSame(raw, shared.raw()),
                () -> assertSame(canonical, shared.canonical()),
                () -> assertThrows(IllegalStateException.class, owned::take),
                () -> assertThrows(DeferredAnalysisStateException.class, deferred::take));
    }

    @Test
    void projectMayTakeAfterShareBeforeJdbcWithoutInvalidatingDeferredLease() {
        var owned = exchangeCandidate("SELECT 1", "$body$SELECT 1$body$");
        var deferred = new DeferredRoutineBodySource(owned.requireAuthorization());

        RoutineBody shared = owned.shareTo(deferred);
        RoutineBody project = owned.take();
        owned.close();
        RoutineBody jdbc = deferred.take();

        assertSame(shared, project);
        assertSame(shared, jdbc);
    }

    @Test
    void sharingAndResolutionAreOneShotAndDoNotConsumeOwnedOnRejectedTarget() {
        var owned = exchangeCandidate("SELECT 1", "$body$SELECT 1$body$");
        var first = new DeferredRoutineBodySource(owned.requireAuthorization());
        var alreadyResolved = new DeferredRoutineBodySource(owned.requireAuthorization());
        alreadyResolved.resolve("SELECT 1", "$body$SELECT 1$body$",
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);

        assertThrows(IllegalStateException.class, () -> owned.shareTo(alreadyResolved));
        RoutineBody shared = owned.shareTo(first);

        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> owned.shareTo(
                        new DeferredRoutineBodySource(owned.requireAuthorization()))),
                () -> assertSame(shared, owned.take()),
                () -> assertSame(shared, first.take()));
    }

    @Test
    void malformedOwnedBodyCannotAuthorizeReuseEvenWithReplacementDigest() {
        String malformed = "SELECT '\uD800';";
        var owned = exchangeCandidate(malformed, "$body$" + malformed + "$body$");
        var replacement = exchangeCandidate("SELECT '?';", "$body$SELECT '?'$body$");
        var deferred = new DeferredRoutineBodySource(replacement.requireAuthorization());

        assertThrows(IllegalStateException.class, () -> owned.shareTo(deferred));

        assertAll(
                () -> assertSame(malformed, owned.take().raw()),
                () -> assertThrows(DeferredAnalysisStateException.class, deferred::take));
    }

    @Test
    void closeAndFailureClearOnlyTheirOwnLeaseReferences() throws Exception {
        var owned = exchangeCandidate("SELECT 1", "$body$SELECT 1$body$");
        var deferred = new DeferredRoutineBodySource(owned.requireAuthorization());
        RoutineBody shared = owned.shareTo(deferred);

        owned.close();

        assertAll(
                () -> assertNull(readField(owned, "body")),
                () -> assertSame(shared, deferred.take()));

        var candidate = exchangeCandidate("SELECT 2", "$body$SELECT 2$body$");
        var failed = new DeferredRoutineBodySource(candidate.requireAuthorization());
        RuntimeException first = new IllegalStateException("first failure");
        failed.fail(first);
        failed.fail(new IllegalStateException("later failure"));
        assertSame(first, readField(failed, "failure"));

        DeferredAnalysisStateException observed = assertThrows(
                DeferredAnalysisStateException.class, failed::take);

        assertAll(
                () -> assertSame(first, observed.getCause()),
                () -> assertNull(readField(failed, "body")),
                () -> assertNull(readField(failed, "failure")),
                () -> assertThrows(IllegalStateException.class,
                        () -> failed.resolve("SELECT 2", "$body$SELECT 2$body$",
                                PROFILE, RoutineBodyRepresentation.SQL_TEXT)));
        assertDoesNotThrow(failed::close);
        assertDoesNotThrow(failed::close);
    }

    @Test
    void concurrentDeferredTakeHasExactlyOneWinnerAndClearsPayload() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var owned = exchangeCandidate("SELECT 1", "$body$SELECT 1$body$");
            var deferred = new DeferredRoutineBodySource(owned.requireAuthorization());
            RoutineBody shared = owned.shareTo(deferred);
            var start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<TakeResult> first = executor.submit(() -> takeAfter(start, deferred));
                Future<TakeResult> second = executor.submit(() -> takeAfter(start, deferred));
                start.countDown();

                List<TakeResult> results = List.of(
                        first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(result -> result.body() != null).count());
                assertEquals(1, results.stream()
                        .filter(result -> result.failure() instanceof DeferredAnalysisStateException)
                        .count());
                assertSame(shared, results.stream()
                        .map(TakeResult::body).filter(java.util.Objects::nonNull).findFirst().orElseThrow());
                assertNull(readField(deferred, "body"));
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        });
    }

    @Test
    void concurrentShareAndProjectTakeAreLinearizedWithoutLosingEitherLease() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                for (int i = 0; i < 100; i++) {
                    var owned = exchangeCandidate("SELECT " + i, "$body$SELECT " + i + "$body$");
                    var deferred = new DeferredRoutineBodySource(owned.requireAuthorization());
                    var start = new CountDownLatch(1);
                    Future<TakeResult> shared = executor.submit(() -> {
                        try {
                            start.await();
                            return new TakeResult(owned.shareTo(deferred), null);
                        } catch (Throwable ex) {
                            return new TakeResult(null, ex);
                        }
                    });
                    Future<TakeResult> project = executor.submit(() -> takeAfter(start, owned));
                    start.countDown();

                    TakeResult shareResult = shared.get(2, TimeUnit.SECONDS);
                    TakeResult projectResult = project.get(2, TimeUnit.SECONDS);
                    assertNotNull(projectResult.body());
                    assertNull(projectResult.failure());
                    if (shareResult.body() != null) {
                        assertSame(projectResult.body(), shareResult.body());
                        assertSame(projectResult.body(), deferred.take());
                    } else {
                        assertInstanceOf(IllegalStateException.class, shareResult.failure());
                        assertThrows(DeferredAnalysisStateException.class, deferred::take);
                    }
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        });
    }

    @Test
    void concurrentResolveAndFailAlwaysClearPayloadAndPublishFirstFailure()
            throws Exception {
        var expected = exchangeCandidate("SELECT 1", "$body$SELECT 1$body$");
        var deferred = new DeferredRoutineBodySource(expected.requireAuthorization());
        RuntimeException producerFailure = new IllegalStateException("producer failed");
        var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> resolve = executor.submit(() -> {
                try {
                    start.await();
                    deferred.resolve("SELECT 1", "$body$SELECT 1$body$",
                            PROFILE, RoutineBodyRepresentation.SQL_TEXT);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                } catch (IllegalStateException expectedRaceLoss) {
                    // fail() won the source monitor
                }
            });
            Future<?> fail = executor.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
                deferred.fail(producerFailure);
            });
            start.countDown();
            resolve.get(2, TimeUnit.SECONDS);
            fail.get(2, TimeUnit.SECONDS);

            DeferredAnalysisStateException observed = assertThrows(
                    DeferredAnalysisStateException.class, deferred::take);
            assertAll(
                    () -> assertSame(producerFailure, observed.getCause()),
                    () -> assertNull(readField(deferred, "body")),
                    () -> assertNull(readField(deferred, "failure")));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void fullAuthorizationRejectsEveryProfileRepresentationAndFingerprintMismatch() {
        var owned = exchangeCandidate("SELECT 1", "$body$SELECT 1$body$");
        RoutineBodyAuthorization expected = owned.requireAuthorization();
        RoutineFingerprint differentFingerprint = reusableMeasure(
                exchangeCandidate("SELECT 2", "$body$SELECT 2$body$"));

        List<RoutineBodyAuthorization> mismatches = List.of(
                authorization(new RoutineBodyProfile(3, false, 1,
                        RoutineBodyProfile.HashAlgorithm.SHA_256, 1),
                        RoutineBodyRepresentation.SQL_TEXT, expected.fingerprint()),
                authorization(new RoutineBodyProfile(2, true, 1,
                        RoutineBodyProfile.HashAlgorithm.SHA_256, 1),
                        RoutineBodyRepresentation.SQL_TEXT, expected.fingerprint()),
                authorization(new RoutineBodyProfile(2, false, 2,
                        RoutineBodyProfile.HashAlgorithm.SHA_256, 1),
                        RoutineBodyRepresentation.SQL_TEXT, expected.fingerprint()),
                authorization(new RoutineBodyProfile(2, false, 1,
                        RoutineBodyProfile.HashAlgorithm.SHA_256, 2),
                        RoutineBodyRepresentation.SQL_TEXT, expected.fingerprint()),
                authorization(PROFILE, RoutineBodyRepresentation.PLPGSQL_TEXT,
                        expected.fingerprint()),
                authorization(PROFILE, RoutineBodyRepresentation.SQL_TEXT,
                        differentFingerprint));

        for (RoutineBodyAuthorization mismatch : mismatches) {
            var target = new DeferredRoutineBodySource(mismatch);
            assertFalse(expected.matches(mismatch));
            assertThrows(IllegalArgumentException.class, () -> owned.shareTo(target));
            assertThrows(DeferredAnalysisStateException.class, target::take);
        }

        assertNotNull(owned.take());
    }

    @Test
    void directResidualResolveRehashesAndValidatesFullAuthorization() {
        var expectedSource = exchangeCandidate("SELECT 1", "$body$SELECT 1$body$");
        var deferred = new DeferredRoutineBodySource(expectedSource.requireAuthorization());

        assertThrows(IllegalArgumentException.class,
                () -> deferred.resolve("SELECT 2", "$body$SELECT 2$body$",
                        PROFILE, RoutineBodyRepresentation.SQL_TEXT));
        RoutineBody resolved = deferred.resolve("SELECT 1", "$body$SELECT 1$body$",
                PROFILE, RoutineBodyRepresentation.SQL_TEXT);

        assertSame(resolved, deferred.take());
    }

    @Test
    void unresolvedTakeIsTerminalAndResolveCanNeverRepairIt() throws Exception {
        var candidate = exchangeCandidate("SELECT 1", "$body$SELECT 1$body$");
        var deferred = new DeferredRoutineBodySource(candidate.requireAuthorization());

        DeferredAnalysisStateException failure = assertThrows(
                DeferredAnalysisStateException.class, deferred::take);

        assertAll(
                () -> assertNull(failure.getCause()),
                () -> assertNull(readField(deferred, "body")),
                () -> assertNull(readField(deferred, "failure")),
                () -> assertThrows(IllegalStateException.class,
                        () -> deferred.resolve("SELECT 1", "$body$SELECT 1$body$",
                                PROFILE, RoutineBodyRepresentation.SQL_TEXT)));
    }

    @Test
    void estimatedUtf8BytesSurvivesTakeCloseAndFailure() {
        String raw = "SELECT '😀';";
        long expectedBytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        var owned = exchangeCandidate(raw, "$body$" + raw + "$body$");
        var deferred = new DeferredRoutineBodySource(owned.requireAuthorization());

        owned.shareTo(deferred);
        owned.take();
        owned.close();
        deferred.fail(new IllegalStateException("cancelled"));
        deferred.close();

        assertAll(
                () -> assertEquals(expectedBytes, owned.estimatedUtf8Bytes()),
                () -> assertEquals(expectedBytes, deferred.estimatedUtf8Bytes()));
    }

    @Test
    void analysisOnlySourceCanBeConsumedButNeverShared() {
        String raw = new String("BEGIN ATOMIC SELECT 1; END");
        String canonical = new String(raw);
        var analysisOnly = OwnedRoutineBodySource.analysisOnly(raw, canonical);
        var candidate = exchangeCandidate("SELECT 1", "$body$SELECT 1$body$");
        var deferred = new DeferredRoutineBodySource(candidate.requireAuthorization());

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        analysisOnly::requireAuthorization),
                () -> assertThrows(IllegalStateException.class,
                        () -> analysisOnly.shareTo(deferred)),
                () -> assertSame(raw, analysisOnly.take().raw()));
    }

    @Test
    void sourceFactoriesRejectNullsAndIneligibleAuthorization() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> OwnedRoutineBodySource.analysisOnly(null, "canonical")),
                () -> assertThrows(NullPointerException.class,
                        () -> OwnedRoutineBodySource.analysisOnly("raw", null)),
                () -> assertThrows(NullPointerException.class,
                        () -> OwnedRoutineBodySource.exchangeCandidate(
                                "raw", "canonical", null, RoutineBodyRepresentation.SQL_TEXT)),
                () -> assertThrows(NullPointerException.class,
                        () -> OwnedRoutineBodySource.exchangeCandidate(
                                "raw", "canonical", PROFILE, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> OwnedRoutineBodySource.exchangeCandidate(
                                "raw", "canonical", PROFILE,
                                RoutineBodyRepresentation.SQL_STATEMENT_BODY)),
                () -> assertThrows(NullPointerException.class,
                        () -> new DeferredRoutineBodySource(null)));
    }

    private static TakeResult takeAfter(CountDownLatch start, RoutineBodySource source) {
        try {
            start.await();
            return new TakeResult(source.take(), null);
        } catch (Throwable ex) {
            return new TakeResult(null, ex);
        }
    }

    private static final RoutineBodyProfile PROFILE = RoutineBodyProfile.current(false);

    private static OwnedRoutineBodySource exchangeCandidate(String raw, String canonical) {
        return OwnedRoutineBodySource.exchangeCandidate(
                raw, canonical, PROFILE, RoutineBodyRepresentation.SQL_TEXT);
    }

    private static RoutineFingerprint reusableMeasure(OwnedRoutineBodySource source) {
        return assertInstanceOf(RoutineFingerprint.class, source.measure());
    }

    private static RoutineBodyAuthorization authorization(RoutineBodyProfile profile,
            RoutineBodyRepresentation representation, RoutineFingerprint fingerprint) {
        return new RoutineBodyAuthorization(profile, representation, fingerprint);
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private record TakeResult(RoutineBody body, Throwable failure) {
    }
}
