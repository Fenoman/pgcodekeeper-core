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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.base.schema.Argument;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.BodyType;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;
import org.pgcodekeeper.core.database.pg.schema.PgAggregate;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgProcedure;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;

class ProjectRoutineBodyCatalogTest {

    private static final RoutineBodyProfile PROFILE = RoutineBodyProfile.current(false);

    @Test
    void buildsExactFunctionProcedureAndOverloadIdentities() {
        var db = database();
        PgSchema schema = db.getSchema("Mixed Schema");
        PgFunction integerFunction = routine(schema, new PgFunction("calc\"value"), "integer");
        PgFunction textFunction = routine(schema, new PgFunction("calc\"value"), "text");
        PgProcedure procedure = routine(schema, new PgProcedure("refresh"), "integer");

        String integerCanonical = "$$SELECT 1$$";
        String textCanonical = "$$BEGIN RETURN; END$$";
        String procedureCanonical = "$$SELECT 2$$";
        integerFunction.setBody(integerCanonical);
        textFunction.setBody(textCanonical);
        procedure.setBody(procedureCanonical);
        addCandidate(db, integerFunction, "SELECT 1", integerCanonical,
                RoutineBodyRepresentation.SQL_TEXT);
        addCandidate(db, textFunction, "BEGIN RETURN; END", textCanonical,
                RoutineBodyRepresentation.PLPGSQL_TEXT);
        addCandidate(db, procedure, "SELECT 2", procedureCanonical,
                RoutineBodyRepresentation.SQL_TEXT);

        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(db);
        var integerIdentity = RoutineIdentity.from(integerFunction);
        var textIdentity = RoutineIdentity.from(textFunction);
        var procedureIdentity = RoutineIdentity.from(procedure);

        assertEquals(3, catalog.candidateCount());
        assertEquals(0, catalog.ambiguousCount());
        assertTrue(integerIdentity.signature().contains("integer"));
        assertTrue(textIdentity.signature().contains("text"));
        assertSame(RoutineBodyRepresentation.SQL_TEXT,
                catalog.removeCandidate(integerIdentity).authorization().representation());
        assertSame(RoutineBodyRepresentation.PLPGSQL_TEXT,
                catalog.removeCandidate(textIdentity).authorization().representation());
        assertSame(RoutineBodyRepresentation.SQL_TEXT,
                catalog.removeCandidate(procedureIdentity).authorization().representation());
        assertEquals(0, catalog.candidateCount());
    }

    @Test
    void exactCandidateSharesTheSameRawAndCanonicalReferences() {
        var db = database();
        PgFunction function = routine(db.getSchema("Mixed Schema"), new PgFunction("same_refs"));
        String raw = new String("SELECT 'Привет'");
        String canonical = new String("$$SELECT 'Привет'$$");
        function.setBody(canonical);
        addCandidate(db, function, raw, canonical, RoutineBodyRepresentation.SQL_TEXT);

        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(db);
        ProjectRoutineBodyCandidate candidate =
                catalog.removeCandidate(RoutineIdentity.from(function));
        var target = new DeferredRoutineBodySource(candidate.authorization());

        RoutineBody shared = candidate.shareTo(target);

        assertSame(raw, shared.raw());
        assertSame(canonical, shared.canonical());
        assertTrue(function.hasBodyReference(shared.canonical()));
        assertSame(shared, target.take());
    }

    @Test
    void rejectsStaleLibraryPayloadAndNonFinalTwin() {
        var db = database();
        PgSchema schema = db.getSchema("Mixed Schema");
        PgFunction finalFunction = routine(schema, new PgFunction("stale"));
        String finalCanonical = new String("$$SELECT 'project'$$");
        finalFunction.setBody(finalCanonical);
        addCandidate(db, finalFunction, "SELECT 'project'", finalCanonical,
                RoutineBodyRepresentation.SQL_TEXT);

        var library = database();
        PgFunction libraryFunction = routine(library.getSchema("Mixed Schema"),
                new PgFunction("stale"));
        String libraryCanonical = new String("$$SELECT 'library'$$");
        libraryFunction.setBody(libraryCanonical);
        addCandidate(library, libraryFunction, "SELECT 'library'", libraryCanonical,
                RoutineBodyRepresentation.SQL_TEXT);
        db.addLib(library, "test-library", null);

        PgFunction finalForeign = routine(schema, new PgFunction("foreign"));
        finalForeign.setBody("$$SELECT 'final'$$");
        var foreignDb = database();
        PgFunction foreignTwin = routine(foreignDb.getSchema("Mixed Schema"), new PgFunction("foreign"));
        String foreignCanonical = "$$SELECT 'foreign'$$";
        foreignTwin.setBody(foreignCanonical);
        addCandidate(db, foreignTwin, "SELECT 'foreign'", foreignCanonical,
                RoutineBodyRepresentation.SQL_TEXT);

        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(db);

        assertEquals(1, catalog.candidateCount());
        assertEquals(0, catalog.ambiguousCount());
        assertSame(RoutineBodyRepresentation.SQL_TEXT,
                catalog.removeCandidate(RoutineIdentity.from(finalFunction))
                        .authorization().representation());
    }

    @Test
    void equalLibraryTextWithDifferentReferenceDoesNotPoisonProjectCandidate() {
        var db = database();
        PgFunction projectFunction = routine(db.getSchema("Mixed Schema"),
                new PgFunction("same_value"));
        String projectRaw = new String("SELECT 1");
        String projectCanonical = new String("$$SELECT 1$$");
        projectFunction.setBody(projectCanonical);
        addCandidate(db, projectFunction, projectRaw, projectCanonical,
                RoutineBodyRepresentation.SQL_TEXT);

        var library = database();
        PgFunction libraryFunction = routine(library.getSchema("Mixed Schema"),
                new PgFunction("same_value"));
        String libraryRaw = new String("SELECT 1");
        String libraryCanonical = new String("$$SELECT 1$$");
        libraryFunction.setBody(libraryCanonical);
        addCandidate(library, libraryFunction, libraryRaw, libraryCanonical,
                RoutineBodyRepresentation.SQL_TEXT);

        db.addLib(library, "same-value-library", null);
        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(db);

        assertNotSame(projectCanonical, libraryCanonical);
        assertEquals(1, catalog.candidateCount());
        assertEquals(0, catalog.ambiguousCount());
        ProjectRoutineBodyCandidate candidate =
                catalog.removeCandidate(RoutineIdentity.from(projectFunction));
        var deferred = new DeferredRoutineBodySource(candidate.authorization());
        RoutineBody shared = candidate.shareTo(deferred);
        assertSame(projectRaw, shared.raw());
        assertSame(projectCanonical, shared.canonical());
    }

    @Test
    void excludesLibraryOnlyShallowCopyBecauseItIsNotTheParseOrigin() {
        var db = database();
        var library = database();
        PgFunction libraryFunction = routine(library.getSchema("Mixed Schema"),
                new PgFunction("library_only"));
        String canonical = "$$SELECT 'library only'$$";
        libraryFunction.setBody(canonical);
        addCandidate(library, libraryFunction, "SELECT 'library only'", canonical,
                RoutineBodyRepresentation.SQL_TEXT);

        db.addLib(library, "test-library", null);
        PgAbstractFunction finalTwin = db.getSchema("Mixed Schema")
                .getFunction(libraryFunction.getName());

        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(db);

        assertNotSame(finalTwin, libraryFunction);
        assertEquals(0, catalog.candidateCount());
        assertEquals(0, catalog.ambiguousCount());
    }

    @Test
    void duplicateValidIdentityIsAmbiguousAndNeverReusable() {
        var db = database();
        PgFunction function = routine(db.getSchema("Mixed Schema"), new PgFunction("duplicate"));
        String raw = "SELECT 1";
        String canonical = "$$SELECT 1$$";
        function.setBody(canonical);
        addCandidate(db, function, raw, canonical, RoutineBodyRepresentation.SQL_TEXT);
        addCandidate(db, function, raw, canonical, RoutineBodyRepresentation.SQL_TEXT);
        addCandidate(db, function, raw, canonical, RoutineBodyRepresentation.SQL_TEXT);

        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(db);
        RoutineIdentity identity = RoutineIdentity.from(function);

        assertEquals(0, catalog.candidateCount());
        assertEquals(1, catalog.ambiguousCount());
        assertNull(catalog.removeCandidate(identity));
        assertTrue(catalog.removeAmbiguous(identity));
        assertFalse(catalog.removeAmbiguous(identity));
    }

    @Test
    void excludesAggregatesStatementBodiesAndAnalysisOnlySources() {
        var db = database();
        PgSchema schema = db.getSchema("Mixed Schema");

        PgAggregate aggregate = routine(schema, new PgAggregate("total"), "integer");
        String aggregateCanonical = "$$SELECT 1$$";
        aggregate.setBody(aggregateCanonical);
        addCandidate(db, aggregate, "SELECT 1", aggregateCanonical,
                RoutineBodyRepresentation.SQL_TEXT);

        PgFunction statementBody = routine(schema, new PgFunction("statement_body"));
        statementBody.setBody("RETURN 1");
        db.addAnalysisLauncher(new PgFuncProcAnalysisLauncher(statementBody,
                OwnedRoutineBodySource.analysisOnly("RETURN 1", "RETURN 1"),
                BodyType.FUNCTION_BODY, "statement body", "test.sql", List.of(), true));

        PgFunction direct = routine(schema, new PgFunction("direct"));
        direct.setBody("$$SELECT 2$$");
        db.addAnalysisLauncher(new PgFuncProcAnalysisLauncher(direct, "SELECT 2",
                BodyType.SQL, "direct", "test.sql", List.of(), true));

        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(db);

        assertEquals(0, catalog.candidateCount());
        assertEquals(0, catalog.ambiguousCount());
    }

    @Test
    void closeClearsEveryCatalogReference() {
        var db = database();
        PgFunction candidateFunction = routine(db.getSchema("Mixed Schema"),
                new PgFunction("candidate"));
        String canonical = "$$SELECT 1$$";
        candidateFunction.setBody(canonical);
        addCandidate(db, candidateFunction, "SELECT 1", canonical,
                RoutineBodyRepresentation.SQL_TEXT);

        PgFunction duplicate = routine(db.getSchema("Mixed Schema"),
                new PgFunction("duplicate_close"));
        String duplicateCanonical = "$$SELECT 2$$";
        duplicate.setBody(duplicateCanonical);
        addCandidate(db, duplicate, "SELECT 2", duplicateCanonical,
                RoutineBodyRepresentation.SQL_TEXT);
        addCandidate(db, duplicate, "SELECT 2", duplicateCanonical,
                RoutineBodyRepresentation.SQL_TEXT);

        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(db);
        catalog.close();
        catalog.close();

        assertEquals(0, catalog.candidateCount());
        assertEquals(0, catalog.ambiguousCount());
    }

    @Test
    void buildTransfersParserDuplicateMarkersOutOfDatabase() {
        var db = database();
        PgFunction function = routine(db.getSchema("Mixed Schema"),
                new PgFunction("duplicate_marker"));
        String canonical = "$$SELECT 1$$";
        function.setBody(canonical);
        addCandidate(db, function, "SELECT 1", canonical,
                RoutineBodyRepresentation.SQL_TEXT);
        RoutineIdentity identity = RoutineIdentity.from(function);
        db.recordProjectRoutineBodyDuplicate(identity);

        ProjectRoutineBodyCatalog catalog = ProjectRoutineBodyCatalog.build(db);

        assertEquals(0, catalog.candidateCount());
        assertEquals(1, catalog.ambiguousCount());
        assertTrue(db.takeProjectRoutineBodyDuplicates().isEmpty());
    }

    @Test
    void capacityHintExcludesRetargetedAndIneligibleLaunchers() {
        var db = database();
        PgFunction main = routine(db.getSchema("Mixed Schema"),
                new PgFunction("main"));
        String mainCanonical = "$$SELECT 1$$";
        main.setBody(mainCanonical);
        addCandidate(db, main, "SELECT 1", mainCanonical,
                RoutineBodyRepresentation.SQL_TEXT);

        PgFunction analysisOnly = routine(db.getSchema("Mixed Schema"),
                new PgFunction("analysis_only"));
        analysisOnly.setBody("$$SELECT 2$$");
        db.addAnalysisLauncher(new PgFuncProcAnalysisLauncher(analysisOnly,
                "SELECT 2", BodyType.SQL, "analysis only", "test.sql",
                List.of(), true));

        var library = database();
        PgFunction libraryOnly = routine(library.getSchema("Mixed Schema"),
                new PgFunction("library_only"));
        String libraryCanonical = "$$SELECT 3$$";
        libraryOnly.setBody(libraryCanonical);
        addCandidate(library, libraryOnly, "SELECT 3", libraryCanonical,
                RoutineBodyRepresentation.SQL_TEXT);
        db.addLib(library, "test-library", null);

        assertEquals(1, ProjectRoutineBodyCatalog.countPotentialCandidates(
                db.getAnalysisLaunchers()));
    }

    @Test
    void reusableSnapshotCreatesIndependentExactCatalogsAndFailsClosedOnModelDrift() {
        var db = database();
        PgFunction function = routine(db.getSchema("Mixed Schema"),
                new PgFunction("reusable"), "integer");
        String raw = new String("SELECT $1 + 1");
        String canonical = new String("$$SELECT $1 + 1$$");
        function.setBody(canonical);
        addCandidate(db, function, raw, canonical,
                RoutineBodyRepresentation.SQL_TEXT);

        ReusableProjectRoutineBodySnapshot snapshot =
                ReusableProjectRoutineBodySnapshot.capture(db);
        db.clearAnalysisLaunchers();
        RoutineIdentity identity = RoutineIdentity.from(function);
        RoutineIdentity changedSignature = new RoutineIdentity(
                identity.schemaName(), identity.kind(), "reusable(text)");
        var foreignModel = database();
        PgFunction foreignFunction = routine(
                foreignModel.getSchema("Mixed Schema"),
                new PgFunction("reusable"), "integer");
        foreignFunction.setBody(canonical);

        assertFalse(snapshot.isCompatibleWith(foreignModel),
                "a snapshot must stay bound to its exact analyzed model generation");

        ProjectRoutineBodyCatalog first = snapshot.newCatalog(db);
        ProjectRoutineBodyCatalog second = snapshot.newCatalog(db);
        ProjectRoutineBodyCandidate firstCandidate =
                first.removeCandidate(identity);
        ProjectRoutineBodyCandidate secondCandidate =
                second.removeCandidate(identity);

        assertNotSame(firstCandidate, secondCandidate);
        assertNull(first.removeCandidate(changedSignature));
        var firstTarget = new DeferredRoutineBodySource(
                firstCandidate.authorization());
        var secondTarget = new DeferredRoutineBodySource(
                secondCandidate.authorization());
        assertSame(raw, firstCandidate.shareTo(firstTarget).raw());
        assertSame(raw, secondCandidate.shareTo(secondTarget).raw());

        var changedBody = OwnedRoutineBodySource.exchangeCandidate(
                "SELECT $1 + 2", "$$SELECT $1 + 2$$", PROFILE,
                RoutineBodyRepresentation.SQL_TEXT);
        assertFalse(secondCandidate.authorization().matches(
                changedBody.requireAuthorization()));

        function.setBody(new String(canonical));
        ProjectRoutineBodyCatalog drifted = snapshot.newCatalog(db);
        assertNull(drifted.removeCandidate(identity),
                "equal text with a different model reference must fall back to residual fetch");
    }

    private static PgDatabase database() {
        var db = new PgDatabase();
        db.addChild(new PgSchema("Mixed Schema"));
        return db;
    }

    private static <T extends PgAbstractFunction> T routine(PgSchema schema, T routine,
                                                            String... argumentTypes) {
        for (String argumentType : argumentTypes) {
            routine.addArgument(new Argument(null, argumentType));
        }
        schema.addChild(routine);
        return routine;
    }

    private static void addCandidate(PgDatabase db, PgAbstractFunction function,
                                     String raw, String canonical,
                                     RoutineBodyRepresentation representation) {
        BodyType bodyType = representation == RoutineBodyRepresentation.SQL_TEXT
                ? BodyType.SQL
                : BodyType.PLPGSQL;
        var source = OwnedRoutineBodySource.exchangeCandidate(
                raw, canonical, PROFILE, representation);
        db.addAnalysisLauncher(new PgFuncProcAnalysisLauncher(function, source,
                bodyType, "routine body", "test.sql", List.of(), true));
    }
}
