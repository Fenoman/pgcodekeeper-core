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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.parser.AntlrError;
import org.pgcodekeeper.core.database.base.parser.FullAnalyze;
import org.pgcodekeeper.core.database.base.parser.ParserUtils;
import org.pgcodekeeper.core.database.base.parser.launcher.AbstractAnalysisLauncher;
import org.pgcodekeeper.core.database.base.parser.launcher.DeferredAnalysisStateException;
import org.pgcodekeeper.core.database.base.schema.meta.MetaContainer;
import org.pgcodekeeper.core.database.base.schema.meta.MetaUtils;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.BodyType;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.ParseDiagnosticPolicy;
import org.pgcodekeeper.core.database.pg.routine.DeferredRoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.OwnedRoutineBodySource;
import org.pgcodekeeper.core.database.pg.routine.RoutineBody;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyProfile;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodyRepresentation;
import org.pgcodekeeper.core.database.pg.routine.RoutineBodySource;
import org.pgcodekeeper.core.database.pg.schema.PgColumn;
import org.pgcodekeeper.core.database.pg.schema.PgCompositeType;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.monitor.NullMonitor;
import org.pgcodekeeper.core.utils.Pair;

class PgFuncProcAnalysisLauncherTest {

    private static final Set<ObjectReference> EXPECTED_DEPENDENCIES = Set.of(
            new ObjectReference("public", DbObjType.SCHEMA),
            new ObjectReference("public", "lazy_dep", DbObjType.TABLE),
            new ObjectReference("public", "lazy_dep", "id", DbObjType.COLUMN));

    private static final Set<ObjectReference> EXPECTED_COMPOSITE_ARRAY_DEPENDENCIES = Set.of(
            new ObjectReference("app", DbObjType.SCHEMA),
            new ObjectReference("app", "item", DbObjType.TYPE));

    @Test
    void bodySourceTransfersExactRawPayloadOnceAndReleasesItsLease()
            throws ReflectiveOperationException {
        Fixture fixture = createFixture();
        String raw = new String("SELECT id FROM public.lazy_dep");
        String canonical = new String("$body$SELECT missing FROM nowhere$body$");
        var source = new ControlledBodySource(raw, canonical);
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), source, BodyType.SQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);

        assertAll(
                () -> assertSame(source, readBodySource(launcher)),
                () -> assertEquals(0, source.takeCalls),
                () -> assertEquals(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        launcher.getEstimatedParseBytes()));

        assertAll(
                () -> assertEquals(EXPECTED_DEPENDENCIES,
                        launcher.launchAnalyze(errors, fixture.meta())),
                () -> assertTrue(errors.isEmpty(), errors::toString),
                () -> assertEquals(1, source.takeCalls),
                () -> assertEquals(1, source.closeCalls),
                () -> assertNull(readBodySource(launcher)),
                () -> assertEquals(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        launcher.getEstimatedParseBytes()));
    }

    @Test
    void entryCancellationAndParentlessSkipCloseSourceWithoutTakingIt()
            throws ReflectiveOperationException {
        Fixture fixture = createFixture();
        var cancelledSource = new ControlledBodySource("SELECT 1", "canonical");
        var cancelled = new PgFuncProcAnalysisLauncher(
                fixture.function(), cancelledSource, BodyType.SQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);
        var monitor = new NullMonitor();
        monitor.setCancelled(true);

        assertThrows(InterruptedException.class,
                () -> cancelled.launchAnalyze(new ArrayList<>(), fixture.meta(), monitor));

        var orphanSource = new ControlledBodySource("SELECT 1", "canonical");
        var orphan = new PgFuncProcAnalysisLauncher(
                new PgFunction("orphan"), orphanSource, BodyType.SQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);
        var orphanErrors = new ArrayList<>();
        assertTrue(orphan.launchAnalyze(orphanErrors, fixture.meta()).isEmpty());

        assertAll(
                () -> assertEquals(0, cancelledSource.takeCalls),
                () -> assertEquals(1, cancelledSource.closeCalls),
                () -> assertNull(readBodySource(cancelled)),
                () -> assertEquals(0, orphanSource.takeCalls),
                () -> assertEquals(1, orphanSource.closeCalls),
                () -> assertNull(readBodySource(orphan)),
                () -> assertTrue(orphanErrors.isEmpty(), orphanErrors::toString));
    }

    @Test
    void unresolvedDeferredSourceIsHardFailureWithoutDiagnosticAndIsTerminal() {
        Fixture fixture = createFixture();
        var deferred = unresolvedSource("SELECT 1");
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), deferred, BodyType.SQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);

        assertThrows(DeferredAnalysisStateException.class,
                () -> launcher.launchAnalyze(errors, fixture.meta()));
        assertThrows(DeferredAnalysisStateException.class,
                () -> launcher.launchAnalyze(errors, fixture.meta()));

        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void unresolvedDeferredSourceRemainsHardThroughFullAnalyzeFutureBoundary() {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), unresolvedSource("SELECT 1"), BodyType.SQL,
                "body", "body", List.of(), true, ParseDiagnosticPolicy.REPORT);
        fixture.db().addAnalysisLauncher(launcher);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FullAnalyze.fullAnalyze(fixture.db(), fixture.meta(), errors));

        assertAll(
                () -> assertTrue(hasCause(failure, DeferredAnalysisStateException.class),
                        () -> "missing deferred failure in cause chain: " + failure),
                () -> assertTrue(errors.isEmpty(), errors::toString));
    }

    @Test
    void sourceCloseFailureAfterSuccessfulParseIsHardAndNotDiagnostic() {
        Fixture fixture = createFixture();
        RuntimeException closeFailure = new IllegalStateException("controlled close failure");
        var source = new ControlledBodySource(
                "SELECT id FROM public.lazy_dep", "canonical", closeFailure);
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), source, BodyType.SQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);

        DeferredAnalysisStateException failure = assertThrows(
                DeferredAnalysisStateException.class,
                () -> launcher.launchAnalyze(errors, fixture.meta()));

        assertAll(
                () -> assertSame(closeFailure, failure.getCause()),
                () -> assertEquals(1, source.takeCalls),
                () -> assertEquals(1, source.closeCalls),
                () -> assertTrue(errors.isEmpty(), errors::toString));
    }

    @Test
    void entryCancellationRemainsPrimaryWhenSourceCloseAlsoFails() {
        Fixture fixture = createFixture();
        RuntimeException closeFailure = new IllegalStateException("controlled close failure");
        var source = new ControlledBodySource("SELECT 1", "canonical", closeFailure);
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), source, BodyType.SQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);
        var monitor = new NullMonitor();
        monitor.setCancelled(true);

        InterruptedException primary = assertThrows(InterruptedException.class,
                () -> launcher.launchAnalyze(new ArrayList<>(), fixture.meta(), monitor));

        assertAll(
                () -> assertEquals(0, source.takeCalls),
                () -> assertEquals(1, source.closeCalls),
                () -> assertTrue(hasCauseInSuppressed(
                        primary, DeferredAnalysisStateException.class, closeFailure)));
    }

    @Test
    void asyncMonitorFailureRemainsTransportedPrimaryWhenSourceCloseAlsoFails()
            throws InterruptedException {
        Fixture fixture = createFixture();
        RuntimeException monitorFailure = new IllegalStateException("controlled monitor failure");
        RuntimeException closeFailure = new IllegalStateException("controlled close failure");
        var source = new ControlledBodySource("SELECT 1", "canonical", closeFailure);
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), source, BodyType.SQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                throw monitorFailure;
            }
        };
        var errors = new ArrayList<>();

        AbstractAnalysisLauncher.AnalysisTaskResult result =
                launcher.launchAnalyzeTask(errors, fixture.meta(), monitor);

        assertAll(
                () -> assertSame(monitorFailure, result.monitorFailure()),
                () -> assertNull(result.dependencies()),
                () -> assertEquals(0, source.takeCalls),
                () -> assertEquals(1, source.closeCalls),
                () -> assertTrue(hasCauseInSuppressed(
                        monitorFailure, DeferredAnalysisStateException.class, closeFailure)),
                () -> assertTrue(errors.isEmpty(), errors::toString));
    }

    @Test
    void monitorFailureAfterSourceDetachKeepsCleanupFailureSuppressedOnExactCause() {
        Fixture fixture = createFixture();
        RuntimeException monitorFailure = new IllegalStateException("controlled monitor failure");
        RuntimeException closeFailure = new IllegalStateException("controlled close failure");
        var source = new ControlledBodySource("SELECT 1", "canonical", closeFailure);
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), source, BodyType.SQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);
        var errors = new ArrayList<>();

        RuntimeException observed = assertThrows(RuntimeException.class,
                () -> launcher.launchAnalyze(
                        errors, fixture.meta(), new FailAfterChecksMonitor(2, monitorFailure)));

        assertAll(
                () -> assertSame(monitorFailure, observed),
                () -> assertEquals(1, source.takeCalls),
                () -> assertEquals(1, source.closeCalls),
                () -> assertTrue(hasCauseInSuppressed(
                        monitorFailure, DeferredAnalysisStateException.class, closeFailure)),
                () -> assertTrue(errors.isEmpty(), errors::toString));
    }

    @Test
    void lazyConstructorsRetainExactUtf8EstimateAfterAnalysis() {
        Fixture fixture = createFixture();
        String definition = "SELECT '😀\uD800'";
        long expected = ParserUtils.getUtf8Length(definition);
        var defaultPolicy = new PgFuncProcAnalysisLauncher(
                fixture.function(), definition, BodyType.SQL, "body", "body", List.of(), true);
        var explicitPolicy = new PgFuncProcAnalysisLauncher(
                fixture.function(), definition, BodyType.SQL, "body", "body", List.of(), true,
                ParseDiagnosticPolicy.REPORT);

        assertAll(
                () -> assertEquals(expected, defaultPolicy.getEstimatedParseBytes()),
                () -> assertEquals(expected, explicitPolicy.getEstimatedParseBytes()));

        defaultPolicy.launchAnalyze(new ArrayList<>(), fixture.meta());

        assertEquals(expected, defaultPolicy.getEstimatedParseBytes());
    }

    @Test
    void eagerConstructorsHaveNoDeferredParseWeight() {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(
                "BEGIN ATOMIC SELECT 1; END", "body", errors);

        var sql = new PgFuncProcAnalysisLauncher(
                fixture.function(), PgParserUtils.createSqlParser(
                        "SELECT 1", "body", errors).sql(),
                "body", List.of(), true);
        var functionBody = new PgFuncProcAnalysisLauncher(
                fixture.function(), parser.function_body(),
                "body", List.of(), true);
        var plpgsql = new PgFuncProcAnalysisLauncher(
                fixture.function(), PgParserUtils.createSqlParser(
                        "BEGIN RETURN 1; END", "body", errors).plpgsql_function(),
                "body", List.of(), true);

        assertAll(
                () -> assertEquals(0, sql.getEstimatedParseBytes()),
                () -> assertEquals(0, functionBody.getEstimatedParseBytes()),
                () -> assertEquals(0, plpgsql.getEstimatedParseBytes()));
    }

    @ParameterizedTest
    @MethodSource("validBodies")
    void lazyBodiesMaterializeOnlyDuringAnalysisAndPreserveDependencies(
            BodyType bodyType, String definition) throws ReflectiveOperationException {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), definition, bodyType, "body", "body", List.of(), true,
                ParseDiagnosticPolicy.REPORT);

        assertAll(
                () -> assertNull(readContext(launcher)),
                () -> assertEquals(EXPECTED_DEPENDENCIES,
                        launcher.launchAnalyze(errors, fixture.meta())),
                () -> assertTrue(errors.isEmpty(), errors::toString),
                () -> assertNull(readContext(launcher)));
    }

    @ParameterizedTest
    @MethodSource("malformedBodies")
    void reportPolicyPreservesDeferredParserDiagnostics(BodyType bodyType, String definition) {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), definition, bodyType, "body", "body", List.of(), true,
                ParseDiagnosticPolicy.REPORT);

        launcher.launchAnalyze(errors, fixture.meta());

        assertTrue(antlrErrorCount(errors) > 0, errors::toString);
    }

    @Test
    void statementBodyDiagnosticPolicyDistinguishesJdbcFromProjectReparse() {
        assertTrue(antlrErrorCount(parseStatementBodyErrors(ParseDiagnosticPolicy.REPORT)) > 0);
        assertEquals(0, antlrErrorCount(
                parseStatementBodyErrors(ParseDiagnosticPolicy.SUPPRESS_DUPLICATE)));
    }

    @Test
    void existingDescriptorConstructorPreservesProjectDiagnosticPolicy() {
        assertEquals(0, antlrErrorCount(parseStatementBodyErrorsWithExistingConstructor()));
        assertTrue(antlrErrorCount(parseSqlErrorsWithExistingConstructor()) > 0);
    }

    @Test
    void cancelledLazyBodyReleasesDefinitionWithoutParserDiagnostic()
            throws ReflectiveOperationException {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), "SELECT 1", BodyType.SQL,
                "body", "body", List.of(), true, ParseDiagnosticPolicy.REPORT);
        var monitor = new CancelAfterChecksMonitor(3);

        assertThrows(MonitorCancelledRuntimeException.class,
                () -> launcher.launchAnalyze(errors, fixture.meta(), monitor));

        assertNull(readBodySource(launcher));
        assertNull(readContext(launcher));
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void entryCancellationReleasesLazyDefinitionBeforeMaterialization()
            throws ReflectiveOperationException {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), "SELECT 1", BodyType.SQL,
                "body", "body", List.of(), true, ParseDiagnosticPolicy.REPORT);
        var monitor = new NullMonitor();
        monitor.setCancelled(true);

        assertThrows(InterruptedException.class,
                () -> launcher.launchAnalyze(errors, fixture.meta(), monitor));

        assertAll(
                () -> assertNull(readBodySource(launcher)),
                () -> assertNull(readContext(launcher)),
                () -> assertTrue(errors.isEmpty(), errors::toString));
    }

    @Test
    void entryMonitorFailureReleasesLazyDefinitionAndPreservesIdentity()
            throws ReflectiveOperationException {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), "SELECT 1", BodyType.SQL,
                "body", "body", List.of(), true, ParseDiagnosticPolicy.REPORT);
        RuntimeException monitorFailure = new IllegalStateException(
                "controlled entry monitor failure");
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                throw monitorFailure;
            }
        };

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> launcher.launchAnalyze(errors, fixture.meta(), monitor));

        assertAll(
                () -> assertSame(monitorFailure, thrown),
                () -> assertNull(readBodySource(launcher)),
                () -> assertNull(readContext(launcher)),
                () -> assertTrue(errors.isEmpty(), errors::toString));
    }

    @Test
    void successfulLazyAnalysisReleasesDefinitionAndContext()
            throws ReflectiveOperationException, InterruptedException {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), "SELECT 1", BodyType.SQL,
                "body", "body", List.of(), true, ParseDiagnosticPolicy.REPORT);

        launcher.launchAnalyze(errors, fixture.meta(), new NullMonitor());

        assertAll(
                () -> assertNull(readBodySource(launcher)),
                () -> assertNull(readContext(launcher)),
                () -> assertTrue(errors.isEmpty(), errors::toString));
    }

    @Test
    void cancellationStopsWideParserDiagnosticPublication()
            throws ReflectiveOperationException, InterruptedException {
        Fixture fixture = createFixture();
        String definition = "SELECT );\n".repeat(1024);
        var baselineErrors = new ArrayList<>();
        var baseline = new PgFuncProcAnalysisLauncher(
                fixture.function(), definition, BodyType.SQL,
                "wide body", "body", List.of(), true, ParseDiagnosticPolicy.REPORT);
        baseline.launchAnalyze(baselineErrors, fixture.meta(), new NullMonitor());
        assertTrue(baselineErrors.size() > 512,
                () -> "test input produced only " + baselineErrors.size() + " diagnostics");

        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), definition, BodyType.SQL,
                "wide body", "body", List.of(), true, ParseDiagnosticPolicy.REPORT);
        var monitor = new NullMonitor() {
            @Override
            public boolean isCancelled() {
                return errors.size() >= 256;
            }
        };

        assertThrows(InterruptedException.class,
                () -> launcher.launchAnalyze(errors, fixture.meta(), monitor));

        assertAll(
                () -> assertTrue(errors.size() >= 256),
                () -> assertTrue(errors.size() < baselineErrors.size(),
                        "all parser diagnostics were retained before cancellation"),
                () -> assertNull(readBodySource(launcher)),
                () -> assertNull(readContext(launcher)));
    }

    @Test
    void lazyBodyPropagatesMonitorFailureByIdentityWithoutDiagnostic()
            throws ReflectiveOperationException {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), "SELECT 1", BodyType.SQL,
                "body", "body", List.of(), true, ParseDiagnosticPolicy.REPORT);
        var monitorFailure = new IllegalStateException("controlled monitor failure");
        var monitor = new FailAfterChecksMonitor(2, monitorFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> launcher.launchAnalyze(errors, fixture.meta(), monitor));

        assertSame(monitorFailure, thrown);
        assertNull(readBodySource(launcher));
        assertNull(readContext(launcher));
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void unaliasedFromSubqueryAnalyzesWithoutErrorsAndKeepsDependencies() {
        // PostgreSQL 16+ allows subqueries in FROM without an alias.
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), "SELECT id FROM (SELECT id FROM public.lazy_dep)",
                BodyType.SQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);

        Set<ObjectReference> dependencies = launcher.launchAnalyze(errors, fixture.meta());

        assertAll(
                () -> assertTrue(errors.isEmpty(), errors::toString),
                () -> assertEquals(EXPECTED_DEPENDENCIES, dependencies));
    }

    @Test
    void unaliasedFromSubqueryInPlpgsqlForLoopAnalyzesWithoutErrors() {
        // minimized from a production PL/pgSQL body:
        // FOR ... IN (SELECT ... FROM (SELECT ... FROM src) /* no alias */) LOOP
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(),
                "BEGIN FOR cur IN (SELECT DISTINCT id FROM (SELECT id FROM public.lazy_dep))"
                        + " LOOP NULL; END LOOP; END",
                BodyType.PLPGSQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);

        Set<ObjectReference> dependencies = launcher.launchAnalyze(errors, fixture.meta());

        assertAll(
                () -> assertTrue(errors.isEmpty(), errors::toString),
                () -> assertEquals(EXPECTED_DEPENDENCIES, dependencies));
    }

    @Test
    void unaliasedRowsFromAnalyzesWithoutErrors() {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), "SELECT * FROM ROWS FROM (generate_series(1, 2))",
                BodyType.SQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);

        Set<ObjectReference> dependencies = launcher.launchAnalyze(errors, fixture.meta());

        assertAll(
                () -> assertTrue(errors.isEmpty(), errors::toString),
                () -> assertTrue(dependencies.isEmpty(), dependencies::toString));
    }

    @Test
    void recoveredSelectWithoutOperationsKeepsOnlyItsParserDiagnostic() {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(),
                "DECLARE _rows app.item[]; BEGIN WITH locked_rows AS (SELECT 1) (; END",
                BodyType.PLPGSQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);

        launcher.launchAnalyze(errors, fixture.meta());

        assertAll(
                () -> assertEquals(1, errors.size(), errors::toString),
                () -> assertEquals(1, antlrErrorCount(errors), errors::toString),
                () -> assertEquals(
                        "mismatched input ';' expecting 'VALUES', 'SELECT', 'TABLE', 'WITH', '('",
                        ((AntlrError) errors.get(0)).getMsg()));
    }

    @ParameterizedTest
    @MethodSource("recoveredFromBodies")
    void recoveredMissingFromItemKeepsParserDiagnosticsAndPartialDependencies(
            String definition, Set<ObjectReference> expectedDependencies) {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), definition,
                BodyType.SQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);

        Set<ObjectReference> dependencies = launcher.launchAnalyze(errors, fixture.meta());

        assertAll(
                () -> assertTrue(antlrErrorCount(errors) > 0, errors::toString),
                () -> assertTrue(errors.stream().allMatch(AntlrError.class::isInstance),
                        () -> "unexpected analyzer diagnostic: " + errors),
                () -> assertEquals(expectedDependencies, dependencies));
    }

    @Test
    void qualifiedCompositeArrayDeclarationAndCastKeepTypeDependencies() {
        Fixture fixture = createCompositeArrayFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), """
                        DECLARE _rows app.item[];
                        BEGIN
                            WITH locked_rows AS (SELECT 1)
                            SELECT array_agg(x::app.item) INTO _rows
                            FROM locked_rows x;
                        END
                        """,
                BodyType.PLPGSQL, "body", "body",
                List.of(), true, ParseDiagnosticPolicy.REPORT);

        Set<ObjectReference> dependencies = launcher.launchAnalyze(errors, fixture.meta());

        assertAll(
                () -> assertTrue(errors.isEmpty(), errors::toString),
                () -> assertEquals(EXPECTED_COMPOSITE_ARRAY_DEPENDENCIES, dependencies));
    }

    @Test
    void allConstructorsDefensivelyCopyFunctionArguments() throws ReflectiveOperationException {
        Pair<String, ObjectReference> argument =
                new Pair<>("value", new ObjectReference("pg_catalog", "int4", DbObjType.TYPE));
        Fixture fixture = createFixture();

        assertArgumentsCopied(argument, arguments -> new PgFuncProcAnalysisLauncher(
                fixture.function(), PgParserUtils.createSqlParser(
                        "SELECT $1", "body", new ArrayList<>()).sql(),
                "body", arguments, true));
        assertArgumentsCopied(argument, arguments -> new PgFuncProcAnalysisLauncher(
                fixture.function(), PgParserUtils.createSqlParser(
                        "BEGIN ATOMIC SELECT $1; END", "body", new ArrayList<>()).function_body(),
                "body", arguments, true));
        assertArgumentsCopied(argument, arguments -> new PgFuncProcAnalysisLauncher(
                fixture.function(), PgParserUtils.createSqlParser(
                        "BEGIN RETURN $1; END", "body", new ArrayList<>()).plpgsql_function(),
                "body", arguments, true));
        assertArgumentsCopied(argument, arguments -> new PgFuncProcAnalysisLauncher(
                fixture.function(), "SELECT $1", BodyType.SQL, "body", "body",
                arguments, true, ParseDiagnosticPolicy.REPORT));
        assertArgumentsCopied(argument, arguments -> new PgFuncProcAnalysisLauncher(
                fixture.function(), "SELECT $1", BodyType.SQL, "body", "body",
                arguments, true));
    }

    private static void assertArgumentsCopied(Pair<String, ObjectReference> argument,
            Function<List<Pair<String, ObjectReference>>, PgFuncProcAnalysisLauncher> factory)
            throws ReflectiveOperationException {
        var supplied = new ArrayList<Pair<String, ObjectReference>>();
        supplied.add(argument);
        PgFuncProcAnalysisLauncher launcher = factory.apply(supplied);

        supplied.clear();

        List<?> retained = readFunctionArguments(launcher);
        assertEquals(List.of(argument), retained);
        assertThrows(UnsupportedOperationException.class, retained::clear);
    }

    private static List<Object> parseStatementBodyErrors(ParseDiagnosticPolicy policy) {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), "BEGIN ATOMIC SELECT (; END", BodyType.FUNCTION_BODY,
                "body", "body", List.of(), true, policy);
        launcher.launchAnalyze(errors, fixture.meta());
        return errors;
    }

    private static List<Object> parseStatementBodyErrorsWithExistingConstructor() {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), "BEGIN ATOMIC SELECT (; END", BodyType.FUNCTION_BODY,
                "body", "body", List.of(), true);
        launcher.launchAnalyze(errors, fixture.meta());
        return errors;
    }

    private static List<Object> parseSqlErrorsWithExistingConstructor() {
        Fixture fixture = createFixture();
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(), "SELECT )", BodyType.SQL,
                "body", "body", List.of(), true);
        launcher.launchAnalyze(errors, fixture.meta());
        return errors;
    }

    private static long antlrErrorCount(List<Object> errors) {
        return errors.stream().filter(AntlrError.class::isInstance).count();
    }

    private static Stream<Arguments> validBodies() {
        return Stream.of(
                Arguments.of(BodyType.SQL, "SELECT id FROM public.lazy_dep"),
                Arguments.of(BodyType.PLPGSQL,
                        "BEGIN RETURN (SELECT id FROM public.lazy_dep); END"),
                Arguments.of(BodyType.FUNCTION_BODY,
                        "BEGIN ATOMIC SELECT id FROM public.lazy_dep; END"));
    }

    private static Stream<Arguments> malformedBodies() {
        return Stream.of(
                Arguments.of(BodyType.SQL, "SELECT )"),
                Arguments.of(BodyType.PLPGSQL, "BEGIN RETURN ); END"),
                Arguments.of(BodyType.FUNCTION_BODY, "BEGIN ATOMIC SELECT (; END"));
    }

    private static Stream<Arguments> recoveredFromBodies() {
        return Stream.of(
                Arguments.of("SELECT * FROM ()", Set.of()),
                Arguments.of(
                        "SELECT id FROM public.lazy_dep UNION SELECT * FROM ()",
                        EXPECTED_DEPENDENCIES),
                Arguments.of(
                        "SELECT * FROM () UNION SELECT id FROM public.lazy_dep",
                        EXPECTED_DEPENDENCIES));
    }

    private static Fixture createFixture() {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        var table = new PgSimpleTable("lazy_dep");
        var column = new PgColumn("id");
        column.setType("integer");
        table.addColumn(column);
        var function = new PgFunction("lazy_body");
        db.addChild(schema);
        schema.addChild(table);
        schema.addChild(function);
        return new Fixture(db, function,
                MetaUtils.createTreeFromDb(db, PgSupportedVersion.VERSION_16));
    }

    private static Fixture createCompositeArrayFixture() {
        Fixture fixture = createFixture();
        var app = new PgSchema("app");
        var item = new PgCompositeType("item");
        var id = new PgColumn("id");
        id.setType("integer");
        item.addAttr(id);
        fixture.db().addChild(app);
        app.addChild(item);
        return new Fixture(fixture.db(), fixture.function(),
                MetaUtils.createTreeFromDb(fixture.db(), PgSupportedVersion.VERSION_17));
    }

    private static Object readContext(PgFuncProcAnalysisLauncher launcher)
            throws ReflectiveOperationException {
        Field field = AbstractAnalysisLauncher.class.getDeclaredField("ctx");
        field.setAccessible(true);
        return field.get(launcher);
    }

    private static List<?> readFunctionArguments(PgFuncProcAnalysisLauncher launcher)
            throws ReflectiveOperationException {
        Field field = PgFuncProcAnalysisLauncher.class.getDeclaredField("funcArgs");
        field.setAccessible(true);
        return (List<?>) field.get(launcher);
    }

    private static Object readBodySource(PgFuncProcAnalysisLauncher launcher)
            throws ReflectiveOperationException {
        Field field = PgFuncProcAnalysisLauncher.class.getDeclaredField("bodySource");
        field.setAccessible(true);
        return field.get(launcher);
    }

    private static DeferredRoutineBodySource unresolvedSource(String raw) {
        var candidate = OwnedRoutineBodySource.exchangeCandidate(
                raw, "$body$" + raw + "$body$", RoutineBodyProfile.current(false),
                RoutineBodyRepresentation.SQL_TEXT);
        return new DeferredRoutineBodySource(candidate.requireAuthorization());
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCauseInSuppressed(
            Throwable primary, Class<? extends Throwable> type, Throwable cause) {
        for (Throwable suppressed : primary.getSuppressed()) {
            if (type.isInstance(suppressed) && suppressed.getCause() == cause) {
                return true;
            }
        }
        return false;
    }

    private static final class ControlledBodySource implements RoutineBodySource {

        private RoutineBody body;
        private final long estimatedUtf8Bytes;
        private final RuntimeException closeFailure;
        private int takeCalls;
        private int closeCalls;

        private ControlledBodySource(String raw, String canonical) {
            this(raw, canonical, null);
        }

        private ControlledBodySource(String raw, String canonical,
                                     RuntimeException closeFailure) {
            body = RoutineBody.create(raw, canonical);
            estimatedUtf8Bytes = body.measure().utf8Length();
            this.closeFailure = closeFailure;
        }

        @Override
        public RoutineBody take() {
            takeCalls++;
            if (body == null) {
                throw new DeferredAnalysisStateException("controlled body already released");
            }
            RoutineBody current = body;
            body = null;
            return current;
        }

        @Override
        public long estimatedUtf8Bytes() {
            return estimatedUtf8Bytes;
        }

        @Override
        public void close() {
            closeCalls++;
            body = null;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class CancelAfterChecksMonitor extends NullMonitor {

        private int checksRemaining;

        private CancelAfterChecksMonitor(int checksRemaining) {
            this.checksRemaining = checksRemaining;
        }

        @Override
        public boolean isCancelled() {
            return --checksRemaining == 0;
        }
    }

    private static final class FailAfterChecksMonitor extends NullMonitor {

        private int checksRemaining;
        private final RuntimeException failure;

        private FailAfterChecksMonitor(int checksRemaining, RuntimeException failure) {
            this.checksRemaining = checksRemaining;
            this.failure = failure;
        }

        @Override
        public boolean isCancelled() {
            if (--checksRemaining == 0) {
                throw failure;
            }
            return false;
        }
    }

    private record Fixture(PgDatabase db, PgFunction function, MetaContainer meta) {
    }
}
