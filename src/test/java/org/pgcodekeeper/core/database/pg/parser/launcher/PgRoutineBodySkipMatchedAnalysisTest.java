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
package org.pgcodekeeper.core.database.pg.parser.launcher;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.parser.AntlrError;
import org.pgcodekeeper.core.database.base.parser.FullAnalyze;
import org.pgcodekeeper.core.database.base.parser.launcher.DeferredAnalysisStateException;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.BodyType;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.ParseDiagnosticPolicy;
import org.pgcodekeeper.core.database.pg.routine.DeferredRoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.OwnedRoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.PgRoutineBodyAnalysisStats;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyProfile;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyRepresentation;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Verifies the analysis-skip contract for hash-first matched routine bodies:
 * eligibility by language and settings, fail-open launcher behavior, launcher
 * lifecycle after a skip, and the FullAnalyze accounting counters.
 */
@Isolated("reads and resets the process-wide routine body analysis counters")
class PgRoutineBodySkipMatchedAnalysisTest {

    private static final RoutineBodyProfile PROFILE = RoutineBodyProfile.current(false);

    @Test
    void skipIsOnByDefaultForLateBoundBodiesOnly() {
        var defaults = new CoreSettings();

        assertAll(
                () -> assertTrue(org.pgcodekeeper.core.settings.ISettings
                        .DEFAULT_PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS),
                () -> assertTrue(defaults.isPgRoutineBodySkipMatchedAnalysis()),
                () -> assertTrue(PgFuncProcAnalysisLauncher
                        .isSkipMatchedBodyAnalysisEligible(defaults, BodyType.PLPGSQL)),
                // quoted sql still needs disabled function body checks
                () -> assertFalse(PgFuncProcAnalysisLauncher
                        .isSkipMatchedBodyAnalysisEligible(defaults, BodyType.SQL)),
                () -> assertFalse(PgFuncProcAnalysisLauncher
                        .isSkipMatchedBodyAnalysisEligible(defaults, BodyType.FUNCTION_BODY)));
    }

    @ParameterizedTest
    @MethodSource("eligibilityMatrix")
    void eligibilityFollowsLanguageBindingAndSettings(boolean skipFlag,
            boolean disableCheckFunctionBodies, boolean functionBodiesDependencies,
            BodyType bodyType, boolean expected) {
        var settings = new CoreSettings();
        settings.setPgRoutineBodySkipMatchedAnalysis(skipFlag);
        settings.setDisableCheckFunctionBodies(disableCheckFunctionBodies);
        settings.setEnableFunctionBodiesDependencies(functionBodiesDependencies);

        assertEquals(expected, PgFuncProcAnalysisLauncher
                .isSkipMatchedBodyAnalysisEligible(settings, bodyType));
    }

    private static Stream<Arguments> eligibilityMatrix() {
        return Stream.of(
                // flag off: never eligible
                Arguments.of(false, true, false, BodyType.PLPGSQL, false),
                Arguments.of(false, true, false, BodyType.SQL, false),
                Arguments.of(false, true, false, BodyType.FUNCTION_BODY, false),
                // plpgsql is late-bound regardless of check_function_bodies
                Arguments.of(true, false, false, BodyType.PLPGSQL, true),
                Arguments.of(true, true, false, BodyType.PLPGSQL, true),
                // quoted sql is validated at CREATE unless checks are disabled
                Arguments.of(true, false, false, BodyType.SQL, false),
                Arguments.of(true, true, false, BodyType.SQL, true),
                // BEGIN ATOMIC bodies are stored parsed with real dependencies
                Arguments.of(true, true, false, BodyType.FUNCTION_BODY, false),
                Arguments.of(true, false, false, BodyType.FUNCTION_BODY, false),
                // explicit body dependency collection keeps the analysis
                Arguments.of(true, true, true, BodyType.PLPGSQL, false),
                Arguments.of(true, true, true, BodyType.SQL, false));
    }

    @Test
    void armingRejectsStatementBodyLaunchers() {
        var function = new PgFunction("atomic_body");
        var launcher = new PgFuncProcAnalysisLauncher(function,
                OwnedRoutineBodySource.analysisOnly("SELECT 1", "SELECT 1"),
                BodyType.FUNCTION_BODY, "body", "body", List.of(), false);

        assertThrows(IllegalStateException.class,
                launcher::enableSkipMatchedBodyAnalysis);
        assertFalse(launcher.skipMatchedBodyAnalysis());
    }

    @Test
    void projectSideSkipsOnlyAfterItsBodyWasSharedWithAMatchedConsumer() {
        PgRoutineBodyAnalysisStats.reset();
        String raw = "BEGIN RETURN 1; END";
        var source = exchangeCandidate(raw, RoutineBodyRepresentation.PLPGSQL_TEXT);
        var launcher = armedLauncher(new PgFunction("matched"), source, BodyType.PLPGSQL);

        // no comparison or no match: fail-open, the launcher stays runnable
        assertFalse(launcher.skipMatchedBodyAnalysis());

        var consumer = new DeferredRoutineBodySource(source.requireAuthorization());
        source.shareTo(consumer);

        assertTrue(launcher.skipMatchedBodyAnalysis());
        assertAll(
                () -> assertFalse(launcher.skipMatchedBodyAnalysis(),
                        "a successful skip must consume the launcher source"),
                () -> assertEquals(1, PgRoutineBodyAnalysisStats.getSkippedBodies()),
                () -> assertEquals(raw.getBytes(StandardCharsets.UTF_8).length,
                        PgRoutineBodyAnalysisStats.getSkippedBytes()),
                () -> assertEquals(0, PgRoutineBodyAnalysisStats.getParsedBodies()),
                // the matched peer keeps its own independent lease
                () -> assertEquals(raw, consumer.take().raw()));
    }

    @Test
    void jdbcSideSkipsOnlyProjectMatchedLeasesAndNeverResidualOnes() {
        String raw = "BEGIN RETURN 1; END";
        var projectSource = exchangeCandidate(raw, RoutineBodyRepresentation.PLPGSQL_TEXT);

        var matchedLease = new DeferredRoutineBodySource(
                projectSource.requireAuthorization());
        var matchedLauncher = armedLauncher(
                new PgFunction("matched"), matchedLease, BodyType.PLPGSQL);
        // unresolved lease: the exchange has not decided yet - fail-open
        assertFalse(matchedLauncher.skipMatchedBodyAnalysis());
        projectSource.shareTo(matchedLease);
        assertTrue(matchedLauncher.skipMatchedBodyAnalysis());

        var residualSource = exchangeCandidate(raw, RoutineBodyRepresentation.PLPGSQL_TEXT);
        var residualLease = new DeferredRoutineBodySource(
                residualSource.requireAuthorization());
        residualLease.resolve(raw, "$body$" + raw + "$body$",
                PROFILE, RoutineBodyRepresentation.PLPGSQL_TEXT);
        var residualLauncher = armedLauncher(
                new PgFunction("changed"), residualLease, BodyType.PLPGSQL);

        assertFalse(residualLauncher.skipMatchedBodyAnalysis(),
                "server-fetched bodies are changed relative to the project"
                        + " and must be analyzed");
        assertEquals(raw, residualLease.take().raw());
    }

    @Test
    void oldSideArmedLauncherSkipsUnmatchedBodiesAndClassifiesMatchedOnes() {
        PgRoutineBodyAnalysisStats.reset();
        String raw = "BEGIN RETURN 1; END";

        // unmatched old-side body: skipped without any match verdict
        var unmatchedProject = exchangeCandidate(raw, RoutineBodyRepresentation.PLPGSQL_TEXT);
        var unmatchedLease = new DeferredRoutineBodySource(
                unmatchedProject.requireAuthorization());
        var unmatchedFunction = new PgFunction("changed");
        var unmatchedLauncher = armedLauncher(unmatchedFunction, unmatchedLease, BodyType.PLPGSQL);
        unmatchedLauncher.enableSkipOldSideBodyAnalysis();
        assertEquals(PgFuncProcAnalysisLauncher.BodySkipOutcome.SKIPPED_OLD_SIDE,
                unmatchedLauncher.skipBodyAnalysis());
        assertEquals(PgFuncProcAnalysisLauncher.BodySkipOutcome.ANALYZED,
                unmatchedLauncher.skipBodyAnalysis(),
                "a successful skip must consume the launcher source");

        // matched old-side body: still accounted as a matched skip
        var matchedProject = exchangeCandidate(raw, RoutineBodyRepresentation.PLPGSQL_TEXT);
        var matchedLease = new DeferredRoutineBodySource(
                matchedProject.requireAuthorization());
        var matchedLauncher = armedLauncher(new PgFunction("matched"), matchedLease, BodyType.PLPGSQL);
        matchedLauncher.enableSkipOldSideBodyAnalysis();
        matchedProject.shareTo(matchedLease);
        assertEquals(PgFuncProcAnalysisLauncher.BodySkipOutcome.SKIPPED_MATCHED,
                matchedLauncher.skipBodyAnalysis());

        long rawBytes = raw.getBytes(StandardCharsets.UTF_8).length;
        assertAll(
                () -> assertEquals(1, PgRoutineBodyAnalysisStats.getSkippedBodies()),
                () -> assertEquals(rawBytes, PgRoutineBodyAnalysisStats.getSkippedBytes()),
                () -> assertEquals(1, PgRoutineBodyAnalysisStats.getSkippedOldSideBodies()),
                () -> assertEquals(rawBytes, PgRoutineBodyAnalysisStats.getSkippedOldSideBytes()),
                () -> assertEquals(0, PgRoutineBodyAnalysisStats.getParsedBodies()));
    }

    @Test
    void projectSideWithoutOldSideArmingNeverSkipsUnmatchedBodies() {
        String raw = "BEGIN RETURN 1; END";
        var source = exchangeCandidate(raw, RoutineBodyRepresentation.PLPGSQL_TEXT);
        var launcher = armedLauncher(new PgFunction("unmatched"), source, BodyType.PLPGSQL);

        assertEquals(PgFuncProcAnalysisLauncher.BodySkipOutcome.ANALYZED,
                launcher.skipBodyAnalysis(),
                "matched-only arming must stay fail-open for unmatched bodies");
        assertEquals(raw, takeBodySource(launcher));
    }

    @Test
    void oldSideArmingRejectsStatementBodyLaunchers() {
        var function = new PgFunction("atomic_body");
        var launcher = new PgFuncProcAnalysisLauncher(function,
                OwnedRoutineBodySource.analysisOnly("SELECT 1", "SELECT 1"),
                BodyType.FUNCTION_BODY, "body", "body", List.of(), false);

        assertThrows(IllegalStateException.class,
                launcher::enableSkipOldSideBodyAnalysis);
        assertEquals(PgFuncProcAnalysisLauncher.BodySkipOutcome.ANALYZED,
                launcher.skipBodyAnalysis());
    }

    @Test
    void retargetedStatementNeverSkipsOldSide() {
        String raw = "BEGIN RETURN 1; END";
        var db = fixtureDatabase();
        var function = new PgFunction("retargeted");
        db.getSchema("public").addChild(function);
        var twinDb = fixtureDatabase();
        var twin = new PgFunction("retargeted");
        twinDb.getSchema("public").addChild(twin);

        var source = exchangeCandidate(raw, RoutineBodyRepresentation.PLPGSQL_TEXT);
        var launcher = armedLauncher(function, source, BodyType.PLPGSQL);
        launcher.enableSkipOldSideBodyAnalysis();
        launcher.updateStmt(twinDb);

        assertEquals(PgFuncProcAnalysisLauncher.BodySkipOutcome.ANALYZED,
                launcher.skipBodyAnalysis());
    }

    @Test
    void skippedBodySuppressesDependencyDrivenAlterCeremony() {
        String raw = "BEGIN RETURN 1; END";
        var oldFunction = new PgFunction("routine");
        new PgSchema("public").addChild(oldFunction);
        var newFunction = new PgFunction("routine");
        new PgSchema("public").addChild(newFunction);
        newFunction.setBody("$body$BEGIN RETURN 2; END$body$");
        newFunction.addDependency(new ObjectReference("public", "dep_table", DbObjType.TABLE));

        var source = exchangeCandidate(raw, RoutineBodyRepresentation.PLPGSQL_TEXT);
        var launcher = armedLauncher(oldFunction, source, BodyType.PLPGSQL);
        launcher.enableSkipOldSideBodyAnalysis();
        assertEquals(PgFuncProcAnalysisLauncher.BodySkipOutcome.SKIPPED_OLD_SIDE,
                launcher.skipBodyAnalysis());

        var settings = new CoreSettings();
        var script = new org.pgcodekeeper.core.script.SQLScript(settings, ";");
        assertEquals(org.pgcodekeeper.core.database.api.schema.ObjectState.ALTER,
                oldFunction.appendAlterSQL(newFunction, script),
                "an old-side skipped late-bound body must not trigger the"
                        + " dependency-driven alter ceremony");
    }

    @Test
    void analyzedBodyKeepsDependencyDrivenAlterCeremony() {
        var oldFunction = new PgFunction("routine");
        new PgSchema("public").addChild(oldFunction);
        oldFunction.setBody("$body$BEGIN RETURN 1; END$body$");
        var newFunction = new PgFunction("routine");
        new PgSchema("public").addChild(newFunction);
        newFunction.setBody("$body$BEGIN RETURN 2; END$body$");
        newFunction.addDependency(new ObjectReference("public", "dep_table", DbObjType.TABLE));

        var settings = new CoreSettings();
        var script = new org.pgcodekeeper.core.script.SQLScript(settings, ";");
        assertEquals(org.pgcodekeeper.core.database.api.schema.ObjectState.ALTER_WITH_DEP,
                oldFunction.appendAlterSQL(newFunction, script));
    }

    @Test
    void fullAnalyzeCountsOldSideSkipsSeparately() throws Exception {
        PgRoutineBodyAnalysisStats.reset();
        var db = fixtureDatabase();
        PgSchema schema = db.getSchema("public");

        var oldSideFunction = new PgFunction("old_side_changed");
        schema.addChild(oldSideFunction);
        String oldSideRaw = "BEGIN PERFORM * FROM public.dep_table; RETURN 1; END";
        var oldSideProject = exchangeCandidate(
                oldSideRaw, RoutineBodyRepresentation.PLPGSQL_TEXT);
        var oldSideLease = new DeferredRoutineBodySource(
                oldSideProject.requireAuthorization());
        var oldSideLauncher = armedLauncher(oldSideFunction, oldSideLease, BodyType.PLPGSQL);
        oldSideLauncher.enableSkipOldSideBodyAnalysis();
        db.addAnalysisLauncher(oldSideLauncher);

        var parsedFunction = new PgFunction("parsed");
        schema.addChild(parsedFunction);
        var parsedSource = exchangeCandidate(
                "SELECT id FROM public.dep_table", RoutineBodyRepresentation.SQL_TEXT);
        db.addAnalysisLauncher(armedLauncher(parsedFunction, parsedSource, BodyType.SQL));

        var errors = new ArrayList<>();
        FullAnalyze.fullAnalyze(db, errors, PgSupportedVersion.VERSION_16);

        assertAll(
                () -> assertTrue(errors.isEmpty(), errors::toString),
                () -> assertEquals(Set.of(), oldSideFunction.getDependencies(),
                        "old-side skipped body must contribute no dependencies"),
                () -> assertFalse(parsedFunction.getDependencies().isEmpty()),
                () -> assertEquals(0, PgRoutineBodyAnalysisStats.getSkippedBodies()),
                () -> assertEquals(1, PgRoutineBodyAnalysisStats.getSkippedOldSideBodies()),
                () -> assertEquals(oldSideRaw.getBytes(StandardCharsets.UTF_8).length,
                        PgRoutineBodyAnalysisStats.getSkippedOldSideBytes()),
                () -> assertEquals(1, PgRoutineBodyAnalysisStats.getParsedBodies()));
    }

    @Test
    void unarmedLauncherNeverSkipsEvenWhenItsBodyMatched() {
        String raw = "BEGIN RETURN 1; END";
        var source = exchangeCandidate(raw, RoutineBodyRepresentation.PLPGSQL_TEXT);
        var launcher = new PgFuncProcAnalysisLauncher(new PgFunction("matched"), source,
                BodyType.PLPGSQL, "body", "body", List.of(), false,
                ParseDiagnosticPolicy.REPORT);
        source.shareTo(new DeferredRoutineBodySource(source.requireAuthorization()));

        assertFalse(launcher.skipMatchedBodyAnalysis());
        assertEquals(raw, takeBodySource(launcher));
    }

    @Test
    void retargetedStatementNeverSkips() {
        String raw = "BEGIN RETURN 1; END";
        var db = fixtureDatabase();
        var function = new PgFunction("retargeted");
        db.getSchema("public").addChild(function);
        var twinDb = fixtureDatabase();
        var twin = new PgFunction("retargeted");
        twinDb.getSchema("public").addChild(twin);

        var source = exchangeCandidate(raw, RoutineBodyRepresentation.PLPGSQL_TEXT);
        var launcher = armedLauncher(function, source, BodyType.PLPGSQL);
        launcher.updateStmt(twinDb);
        source.shareTo(new DeferredRoutineBodySource(source.requireAuthorization()));

        assertFalse(launcher.skipMatchedBodyAnalysis());
    }

    @Test
    void skippedLauncherIsReleasedAndMustNotRunAfterwards() throws Exception {
        var db = fixtureDatabase();
        var function = new PgFunction("matched");
        db.getSchema("public").addChild(function);
        var source = exchangeCandidate(
                "BEGIN RETURN 1; END", RoutineBodyRepresentation.PLPGSQL_TEXT);
        var launcher = armedLauncher(function, source, BodyType.PLPGSQL);
        source.shareTo(new DeferredRoutineBodySource(source.requireAuthorization()));

        assertTrue(launcher.skipMatchedBodyAnalysis());

        assertNull(readBodySource(launcher), "skip must release the body source");
        var errors = new ArrayList<>();
        assertThrows(DeferredAnalysisStateException.class,
                () -> launcher.launchAnalyze(errors,
                        org.pgcodekeeper.core.database.base.schema.meta.MetaUtils
                                .createTreeFromDb(db, PgSupportedVersion.VERSION_16)));
        assertTrue(errors.isEmpty());
    }

    @Test
    void fullAnalyzeSkipsMatchedParsesChangedAndKeepsDiagnosticsAndDependencies()
            throws Exception {
        PgRoutineBodyAnalysisStats.reset();
        var db = fixtureDatabase();
        PgSchema schema = db.getSchema("public");

        // matched, armed: analysis skipped, no body-derived dependencies
        var matchedFunction = new PgFunction("matched");
        schema.addChild(matchedFunction);
        String matchedRaw = "BEGIN PERFORM * FROM public.dep_table; RETURN 1; END";
        var matchedSource = exchangeCandidate(
                matchedRaw, RoutineBodyRepresentation.PLPGSQL_TEXT);
        db.addAnalysisLauncher(armedLauncher(
                matchedFunction, matchedSource, BodyType.PLPGSQL));
        matchedSource.shareTo(new DeferredRoutineBodySource(
                matchedSource.requireAuthorization()));

        // changed, armed but unmatched: analyzed with dependencies
        var changedFunction = new PgFunction("changed");
        schema.addChild(changedFunction);
        var changedSource = exchangeCandidate(
                "SELECT id FROM public.dep_table", RoutineBodyRepresentation.SQL_TEXT);
        db.addAnalysisLauncher(armedLauncher(
                changedFunction, changedSource, BodyType.SQL));

        // broken and unmatched: parser diagnostics must be preserved
        var brokenFunction = new PgFunction("broken");
        schema.addChild(brokenFunction);
        var brokenSource = exchangeCandidate(
                "BEGIN RETURN ); END", RoutineBodyRepresentation.PLPGSQL_TEXT);
        db.addAnalysisLauncher(armedLauncher(
                brokenFunction, brokenSource, BodyType.PLPGSQL));

        var errors = new ArrayList<>();
        FullAnalyze.fullAnalyze(db, errors, PgSupportedVersion.VERSION_16);

        assertAll(
                () -> assertEquals(Set.of(), matchedFunction.getDependencies(),
                        "skipped body must contribute no dependencies"),
                () -> assertEquals(Set.of(
                        new ObjectReference("public", DbObjType.SCHEMA),
                        new ObjectReference("public", "dep_table", DbObjType.TABLE),
                        new ObjectReference("public", "dep_table", "id", DbObjType.COLUMN)),
                        changedFunction.getDependencies(),
                        "changed body must keep its dependencies"),
                () -> assertEquals(1, errors.stream()
                        .filter(AntlrError.class::isInstance).count(),
                        () -> "broken changed body must keep its diagnostic: " + errors),
                () -> assertEquals(1, PgRoutineBodyAnalysisStats.getSkippedBodies()),
                () -> assertEquals(matchedRaw.getBytes(StandardCharsets.UTF_8).length,
                        PgRoutineBodyAnalysisStats.getSkippedBytes()),
                () -> assertEquals(2, PgRoutineBodyAnalysisStats.getParsedBodies()),
                () -> assertTrue(db.getAnalysisLaunchers().isEmpty()));
    }

    @Test
    void fullAnalyzeWithoutMatchesKeepsEveryBodyAnalyzed() throws Exception {
        PgRoutineBodyAnalysisStats.reset();
        var db = fixtureDatabase();
        PgSchema schema = db.getSchema("public");
        var function = new PgFunction("unmatched");
        schema.addChild(function);
        var source = exchangeCandidate(
                "SELECT id FROM public.dep_table", RoutineBodyRepresentation.SQL_TEXT);
        db.addAnalysisLauncher(armedLauncher(function, source, BodyType.SQL));

        var errors = new ArrayList<>();
        FullAnalyze.fullAnalyze(db, errors, PgSupportedVersion.VERSION_16);

        assertAll(
                () -> assertTrue(errors.isEmpty(), errors::toString),
                () -> assertEquals(0, PgRoutineBodyAnalysisStats.getSkippedBodies()),
                () -> assertEquals(1, PgRoutineBodyAnalysisStats.getParsedBodies()),
                () -> assertFalse(function.getDependencies().isEmpty()));
    }

    private static PgDatabase fixtureDatabase() {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        var table = new PgSimpleTable("dep_table");
        var column = new PgColumn("id");
        column.setType("integer");
        table.addColumn(column);
        db.addChild(schema);
        schema.addChild(table);
        return db;
    }

    private static OwnedRoutineBodySource exchangeCandidate(
            String raw, RoutineBodyRepresentation representation) {
        return OwnedRoutineBodySource.exchangeCandidate(
                raw, "$body$" + raw + "$body$", PROFILE, representation);
    }

    private static PgFuncProcAnalysisLauncher armedLauncher(PgFunction function,
            org.pgcodekeeper.core.database.pg.routine.RoutineBodySource source,
            BodyType bodyType) {
        var launcher = new PgFuncProcAnalysisLauncher(function, source, bodyType,
                "body", "body", List.of(), false, ParseDiagnosticPolicy.REPORT);
        launcher.enableSkipMatchedBodyAnalysis();
        return launcher;
    }

    private static String takeBodySource(PgFuncProcAnalysisLauncher launcher) {
        try {
            Field field = PgFuncProcAnalysisLauncher.class.getDeclaredField("bodySource");
            field.setAccessible(true);
            var source = (org.pgcodekeeper.core.database.pg.routine.RoutineBodySource)
                    field.get(launcher);
            return source.take().raw();
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Object readBodySource(PgFuncProcAnalysisLauncher launcher)
            throws ReflectiveOperationException {
        Field field = PgFuncProcAnalysisLauncher.class.getDeclaredField("bodySource");
        field.setAccessible(true);
        return field.get(launcher);
    }
}
