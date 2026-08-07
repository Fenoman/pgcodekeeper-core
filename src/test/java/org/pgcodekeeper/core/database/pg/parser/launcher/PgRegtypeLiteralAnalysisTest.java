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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.schema.meta.MetaContainer;
import org.pgcodekeeper.core.database.base.schema.meta.MetaUtils;
import org.pgcodekeeper.core.database.pg.jdbc.PgSupportedVersion;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.BodyType;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgFuncProcAnalysisLauncher.ParseDiagnosticPolicy;
import org.pgcodekeeper.core.database.pg.schema.PgCompositeType;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgFunction;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.utils.LogCapture;

class PgRegtypeLiteralAnalysisTest {

    @Test
    void plpgsqlRegtypeDoublePrecisionDoesNotEmitQNameWarning() {
        assertBuiltinLiteral("double precision");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "integer[]",
        "numeric(10, 2)",
        "timestamp(3) with time zone"
    })
    void plpgsqlRegtypeBuiltinTypeSyntaxDoesNotEmitParserWarning(String typeLiteral) {
        assertBuiltinLiteral(typeLiteral);
    }

    @Test
    void plpgsqlRegtypeQualifiedCustomTypeAddsDependencyWithoutParserWarning() {
        Fixture fixture = createFixture(true);
        AnalysisResult result = analyze("app.custom_type", fixture);

        assertTrue(result.errors().isEmpty(), result.errors()::toString);
        assertEquals(Set.of(
                new ObjectReference("app", DbObjType.SCHEMA),
                new ObjectReference("app", "custom_type", DbObjType.TYPE)),
                result.dependencies());
        assertNoLiteralParserWarning("app.custom_type", result.messages());
    }

    @Test
    void unreachableEmptyRegtypeLiteralDoesNotAbortAnalysis() {
        AnalysisResult result = analyzeBody(
                "BEGIN IF false THEN PERFORM ''::regtype; END IF; END",
                createFixture(false));

        assertTrue(result.errors().isEmpty(), result.errors()::toString);
        assertTrue(result.dependencies().isEmpty(), result.dependencies()::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "app.",
        "app.custom_type."
    })
    void incompleteRegtypeLiteralDoesNotPublishRecoveredPrefixDependency(String typeLiteral) {
        AnalysisResult result = analyze(typeLiteral, createFixture(true));

        assertTrue(result.errors().isEmpty(), result.errors()::toString);
        assertTrue(result.dependencies().isEmpty(), result.dependencies()::toString);
    }

    private static void assertBuiltinLiteral(String typeLiteral) {
        AnalysisResult result = analyze(typeLiteral, createFixture(false));

        assertTrue(result.errors().isEmpty(), result.errors()::toString);
        assertTrue(result.dependencies().isEmpty(), result.dependencies()::toString);
        assertNoLiteralParserWarning(typeLiteral, result.messages());
    }

    private static void assertNoLiteralParserWarning(String typeLiteral, List<String> messages) {
        List<String> warnings = messages.stream()
                .filter(message -> message.contains(typeLiteral))
                .toList();
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    private static AnalysisResult analyze(String typeLiteral, Fixture fixture) {
        return analyzeBody(
                "DECLARE _type oid := '" + typeLiteral + "'::regtype; BEGIN NULL; END",
                fixture);
    }

    private static AnalysisResult analyzeBody(String body, Fixture fixture) {
        var errors = new ArrayList<>();
        var launcher = new PgFuncProcAnalysisLauncher(
                fixture.function(),
                body,
                BodyType.PLPGSQL, "regtype literal", "regtype literal",
                List.of(), true, ParseDiagnosticPolicy.REPORT);

        try (LogCapture capture = LogCapture.start()) {
            Set<ObjectReference> dependencies = launcher.launchAnalyze(errors, fixture.meta());
            return new AnalysisResult(dependencies, List.copyOf(errors), capture.messages());
        }
    }

    private static Fixture createFixture(boolean withCustomType) {
        var db = new PgDatabase();
        var schema = new PgSchema("public");
        var function = new PgFunction("regtype_literal");
        schema.addChild(function);
        db.addChild(schema);

        if (withCustomType) {
            var app = new PgSchema("app");
            app.addChild(new PgCompositeType("custom_type"));
            db.addChild(app);
        }

        return new Fixture(function,
                MetaUtils.createTreeFromDb(db, PgSupportedVersion.VERSION_17));
    }

    private record Fixture(PgFunction function, MetaContainer meta) { }

    private record AnalysisResult(Set<ObjectReference> dependencies,
                                  List<Object> errors,
                                  List<String> messages) { }
}
