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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.parser.FullAnalyze;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgProjectLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * Proves that a replayed model is indistinguishable from an analyzed one, down
 * to the migration script both produce, and that every way a payload can fail
 * to describe its target ends in a full analysis rather than a partially
 * replayed model.
 *
 * @see #assertMigrationsEqual
 */
class AnalysisReplayTest {

    /**
     * The fixture has to exercise the analysis, not merely load: a view and a
     * routine body are what produce dependency edges and analysis launchers in
     * the first place. {@link #assertFixtureIsAnalysable} guards against a
     * fixture that silently stops loading and turns every assertion vacuous.
     */
    private static final Map<String, String> PROJECT = Map.of(
            "SCHEMA/app/app.sql", "CREATE SCHEMA app;",
            // The default expression is deliberate: a column carries analysis
            // dependency edges of its own, and a column is not a child of its
            // table, so only a walk that reaches columns can capture them.
            "SCHEMA/app/TABLE/customer.sql", """
                    CREATE TABLE app.customer (
                        id bigint PRIMARY KEY,
                        name text NOT NULL,
                        label text DEFAULT app.default_label()
                    );""",
            "SCHEMA/app/FUNCTION/default_label.sql", """
                    CREATE FUNCTION app.default_label() RETURNS text
                        LANGUAGE sql AS $$ SELECT 'unnamed'::text $$;""",
            "SCHEMA/app/VIEW/customer_names.sql", """
                    CREATE VIEW app.customer_names AS
                        SELECT c.id, c.name FROM app.customer AS c;""",
            // Two overloads, which the project layout keeps in one file. Their
            // addresses must stay distinct, which is what makes the routine
            // signature part of the statement identity.
            "SCHEMA/app/FUNCTION/customer_count.sql", """
                    CREATE FUNCTION app.customer_count() RETURNS bigint
                        LANGUAGE sql AS $$ SELECT count(*) FROM app.customer $$;

                    CREATE FUNCTION app.customer_count(p_id bigint) RETURNS bigint
                        LANGUAGE sql AS $$
                            SELECT count(*) FROM app.customer WHERE id = p_id $$;""");

    private static final String OTHER_PROJECT_SCHEMA = "CREATE SCHEMA other;";

    private static final int MIN_STATEMENTS = 6;

    @Test
    void theFixtureActuallyExercisesTheAnalysis(@TempDir Path root) throws Exception {
        writeProject(root);
        PgDatabase structural = new PgProjectLoader(root, settings()).load();
        assertFixtureIsAnalysable(structural);
        PgDatabase analyzed = analyze(root);
        assertTrue(AnalysisReplay.capture(analyzed).dependencyCount() > 0,
                "the fixture must gain dependency edges from the analysis");
        Set<String> routines = StatementAddress.index(structural).keySet().stream()
                .map(address -> address.segments()
                        .get(address.segments().size() - 1).name())
                .filter(name -> name.startsWith("customer_count"))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("customer_count()", "customer_count(bigint)"), routines,
                "overloaded routines must get distinct addresses");
        assertTrue(columnDependencies(analyzed) > 0,
                "the fixture must give a column dependency edges of its own");
    }

    @Test
    void columnDependenciesSurviveTheReplay(@TempDir Path root) throws Exception {
        writeProject(root);
        PgDatabase analyzed = analyze(root);
        long expected = columnDependencies(analyzed);
        assertTrue(expected > 0, "the fixture must exercise column dependencies");

        PgProjectLoader loader = new PgProjectLoader(root, settings());
        loader.enableAnalysisReplay(decode(encode(AnalysisReplay.capture(analyzed))));
        PgDatabase replayed = loader.loadAndAnalyze();

        assertTrue(loader.isAnalysisReplayed());
        assertEquals(expected, columnDependencies(replayed),
                "a column is reachable only through its table, and its edges"
                        + " must be captured and replayed like any other");
        assertModelsEqual(analyzed, replayed);
    }

    /**
     * Counts dependency edges that hang off columns, which the plain child walk
     * of a table cannot see.
     */
    private static long columnDependencies(PgDatabase database) {
        return StatementAddress.statements(database)
                .filter(statement -> statement.getStatementType() == DbObjType.COLUMN)
                .mapToLong(statement -> statement.getDependencies().size()).sum();
    }

    @Test
    void replayedModelEqualsAnalyzedModel(@TempDir Path root) throws Exception {
        writeProject(root);
        PgDatabase analyzed = analyze(root);
        AnalysisReplayPayload payload = AnalysisReplay.capture(analyzed);

        PgProjectLoader loader = new PgProjectLoader(root, settings());
        loader.enableAnalysisReplay(payload);
        PgDatabase replayed = loader.loadAndAnalyze();

        assertTrue(loader.isAnalysisReplayed(), "analysis must be replayed");
        assertTrue(replayed.getAnalysisLaunchers().isEmpty(),
                "a replayed model must present itself as analyzed");
        assertModelsEqual(analyzed, replayed);
    }

    @Test
    void replayedModelSurvivesAnEncodingRoundTrip(@TempDir Path root) throws Exception {
        writeProject(root);
        PgDatabase analyzed = analyze(root);
        byte[] encoded = encode(AnalysisReplay.capture(analyzed));

        PgProjectLoader loader = new PgProjectLoader(root, settings());
        loader.enableAnalysisReplay(decode(encoded));
        PgDatabase replayed = loader.loadAndAnalyze();

        assertTrue(loader.isAnalysisReplayed());
        assertModelsEqual(analyzed, replayed);
        // The encoding is a function of the content alone, so a model that is
        // equal re-encodes to the very same bytes.
        assertArrayEqualsMessage(encoded, encode(AnalysisReplay.capture(replayed)));
    }

    @Test
    void aPayloadOfAnotherProjectIsRejectedAndTheModelIsAnalyzed(
            @TempDir Path root, @TempDir Path other) throws Exception {
        writeProject(root);
        write(other, "SCHEMA/other/other.sql", OTHER_PROJECT_SCHEMA);
        AnalysisReplayPayload foreign = AnalysisReplay.capture(analyze(other));

        PgProjectLoader loader = new PgProjectLoader(root, settings());
        loader.enableAnalysisReplay(foreign);
        PgDatabase model = loader.loadAndAnalyze();

        assertFalse(loader.isAnalysisReplayed(),
                "a payload of another project must not be applied");
        assertTrue(model.getAnalysisLaunchers().isEmpty(),
                "the rejected replay must fall back to a full analysis");
        assertModelsEqual(analyze(root), model);
    }

    @Test
    void aPayloadWithAnUnknownStatementIsRejectedWithoutWriting(
            @TempDir Path root) throws Exception {
        writeProject(root);
        PgDatabase analyzed = analyze(root);
        AnalysisReplayPayload payload = AnalysisReplay.capture(analyzed);
        var poisoned = new ArrayList<>(payload.dependencies());
        poisoned.add(new AnalysisReplayPayload.StatementDependencies(
                new StatementAddress(List.of(
                        new StatementAddress.Segment(DbObjType.SCHEMA, "ghost"))),
                List.of(new ObjectReference("ghost", DbObjType.SCHEMA))));
        var broken = new AnalysisReplayPayload(poisoned, payload.references(),
                payload.suppressedRoutines(), payload.statementCount());

        PgDatabase structural = new PgProjectLoader(root, settings()).load();
        assertFixtureIsAnalysable(structural);
        long before = totalDependencies(structural);
        assertFalse(AnalysisReplay.apply(structural, broken, null));
        assertEquals(before, totalDependencies(structural),
                "a rejected replay must not write a single edge");
        assertFalse(structural.getAnalysisLaunchers().isEmpty(),
                "a rejected replay must leave the model analyzable");
    }

    @Test
    void aPayloadForADifferentStatementCountIsRejected(@TempDir Path root)
            throws Exception {
        writeProject(root);
        AnalysisReplayPayload payload = AnalysisReplay.capture(analyze(root));
        var wrongShape = new AnalysisReplayPayload(payload.dependencies(),
                payload.references(), payload.suppressedRoutines(),
                payload.statementCount() + 1);

        PgDatabase structural = new PgProjectLoader(root, settings()).load();
        assertFalse(AnalysisReplay.apply(structural, wrongShape, null));
    }

    @Test
    void aTruncatedContainerIsReportedAsDamaged(@TempDir Path root) throws Exception {
        writeProject(root);
        byte[] encoded = encode(AnalysisReplay.capture(analyze(root)));
        byte[] truncated = new byte[encoded.length - 1];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);
        assertThrows(AnalysisReplayFormatException.class, () -> decode(truncated));
    }

    @Test
    void aFlippedByteIsCaughtByTheChecksum(@TempDir Path root) throws Exception {
        writeProject(root);
        byte[] encoded = encode(AnalysisReplay.capture(analyze(root)));
        encoded[encoded.length - 1] ^= 0x01;
        assertThrows(AnalysisReplayFormatException.class, () -> decode(encoded));
    }

    @Test
    void aSmallPayloadMayStillDescribeALargeModel() throws Exception {
        // statementCount is a magnitude, not a count of records that follow;
        // a bound derived from the remaining body would wrongly reject this.
        var payload = new AnalysisReplayPayload(
                List.of(), List.of(), List.of(), 100_000);
        assertEquals(100_000, decode(encode(payload)).statementCount());
    }

    @Test
    void aForeignMagicIsReportedAsDamaged() {
        byte[] foreign = "not a payload at all, really not".getBytes(
                StandardCharsets.US_ASCII);
        assertThrows(AnalysisReplayFormatException.class, () -> decode(foreign));
    }

    @Test
    void anotherFormatVersionIsReportedAsDamaged(@TempDir Path root) throws Exception {
        writeProject(root);
        byte[] encoded = encode(AnalysisReplay.capture(analyze(root)));
        encoded[11] = (byte) (AnalysisReplayCodec.FORMAT_VERSION + 1);
        assertThrows(AnalysisReplayFormatException.class, () -> decode(encoded));
    }

    @Test
    void everyLocationFieldSurvivesTheEncoding() throws Exception {
        ObjectLocation location = new ObjectLocation.Builder()
                .setFilePath("SCHEMA/app.sql")
                .setOffset(17)
                .setLineNumber(3)
                .setCharPositionInLine(5)
                .setLength(11)
                .setAction("SELECT")
                .setSql("SELECT 1")
                .setAlias("c")
                .setLocationType(LocationType.LOCAL_REF)
                .setReference(new ObjectReference("app", "customer", "id",
                        DbObjType.COLUMN))
                .build();
        location.setWarning(DangerStatement.DROP_TABLE);
        var payload = new AnalysisReplayPayload(List.of(),
                List.of(new AnalysisReplayPayload.FileReferences(
                        "SCHEMA/app.sql", List.of(location))),
                List.of(), 0);

        ObjectLocation decoded = decode(encode(payload)).references().get(0)
                .locations().get(0);
        assertEquals(location, decoded);
        assertEquals(location.getFilePath(), decoded.getFilePath());
        assertEquals(location.getOffset(), decoded.getOffset());
        assertEquals(location.getLineNumber(), decoded.getLineNumber());
        assertEquals(location.getCharPositionInLine(),
                decoded.getCharPositionInLine());
        assertEquals(location.getObjLength(), decoded.getObjLength());
        assertEquals(location.getAction(), decoded.getAction());
        assertEquals(location.getSql(), decoded.getSql());
        assertEquals(location.getAlias(), decoded.getAlias());
        assertEquals(location.getLocationType(), decoded.getLocationType());
        assertEquals(location.getDanger(), decoded.getDanger());
        assertEquals(location.getObjectReference(), decoded.getObjectReference());
    }

    @Test
    void statementAddressesAreUniqueAndSurviveAReload(@TempDir Path root)
            throws Exception {
        writeProject(root);
        Map<StatementAddress, IStatement> first =
                StatementAddress.index(new PgProjectLoader(root, settings()).load());
        Map<StatementAddress, IStatement> second =
                StatementAddress.index(new PgProjectLoader(root, settings()).load());
        assertNotNull(first, "a valid model must produce an address index");
        assertNotNull(second);
        assertEquals(first.keySet(), second.keySet(),
                "addresses must not depend on the load");
    }

    @Test
    void theDatabaseRootHasNoAddress(@TempDir Path root) throws Exception {
        writeProject(root);
        PgDatabase model = new PgProjectLoader(root, settings()).load();
        assertThrows(IllegalArgumentException.class,
                () -> StatementAddress.of(model));
    }

    private static void assertModelsEqual(PgDatabase expected, PgDatabase actual)
            throws Exception {
        Map<StatementAddress, IStatement> left = StatementAddress.index(expected);
        Map<StatementAddress, IStatement> right = StatementAddress.index(actual);
        assertNotNull(left);
        assertNotNull(right);
        assertEquals(left.keySet(), right.keySet(), "statement addresses");
        for (Map.Entry<StatementAddress, IStatement> entry : left.entrySet()) {
            assertEquals(
                    new ArrayList<>(entry.getValue().getDependencies()),
                    new ArrayList<>(right.get(entry.getKey()).getDependencies()),
                    "ordered dependencies of " + entry.getKey());
        }
        assertEquals(expected.getObjReferences().keySet(),
                actual.getObjReferences().keySet(), "reference files");
        for (Map.Entry<String, Set<ObjectLocation>> entry
                : expected.getObjReferences().entrySet()) {
            List<ObjectLocation> want = new ArrayList<>(entry.getValue());
            List<ObjectLocation> have = new ArrayList<>(
                    actual.getObjReferences().get(entry.getKey()));
            assertEquals(want, have, "ordered locations of " + entry.getKey());
            for (int i = 0; i < want.size(); i++) {
                assertEquals(want.get(i).getAlias(), have.get(i).getAlias());
                assertEquals(want.get(i).getObjLength(), have.get(i).getObjLength());
                assertEquals(want.get(i).getLocationType(),
                        have.get(i).getLocationType());
                assertEquals(want.get(i).getDanger(), have.get(i).getDanger());
                assertEquals(want.get(i).getFilePath(), have.get(i).getFilePath());
            }
        }
        assertMigrationsEqual(expected, actual);
    }

    /**
     * Compares what the tool actually delivers, the migration script, and not
     * only the model it was built from.
     * <p>
     * Everything above this point is read through {@link StatementAddress},
     * which is the very walk the replay itself uses, so a walk that stops
     * reaching a kind of statement makes the oracle and its subject agree by
     * construction. Worse, the model cannot be asked directly: a statement
     * compares and hashes without looking at its dependency edges at all, so a
     * model with edges is indistinguishable from the same model with none.
     * <p>
     * The script is not built through that walk. It is built through the
     * dependency graph, which is what turns a lost edge into an observable
     * difference: without the edge from the {@code label} column to
     * {@code app.default_label()} the table is emitted before the function its
     * default calls, because {@code TABLE} sorts before {@code FUNCTION} and
     * nothing is left to hoist the function.
     */
    private static void assertMigrationsEqual(PgDatabase expected, PgDatabase actual)
            throws Exception {
        String want = migrationScript(expected);
        // Without this the assertion above survives any change that makes both
        // scripts empty, and an empty script equals an empty script forever.
        assertFalse(want.isBlank(), "fixture must produce a non-empty migration");
        assertEquals(want, migrationScript(actual), "migration script");
    }

    /**
     * Builds the script that creates the whole model from an empty database,
     * which is the shortest comparison that still forces every object of the
     * fixture through the dependency ordering.
     */
    private static String migrationScript(PgDatabase model) throws Exception {
        var provider = new PgDatabaseProvider();
        return PgCodeKeeperApi.diff(provider, provider.createDatabase(), model,
                settings());
    }

    private static long totalDependencies(PgDatabase database) {
        return StatementAddress.index(database).values().stream()
                .mapToLong(statement -> statement.getDependencies().size()).sum();
    }

    private static void assertArrayEqualsMessage(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length, "encoded length");
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                assertEquals(expected[i], actual[i], "encoded byte " + i);
            }
        }
    }

    private static byte[] encode(AnalysisReplayPayload payload) throws Exception {
        var bytes = new ByteArrayOutputStream();
        AnalysisReplayCodec.write(payload, bytes);
        return bytes.toByteArray();
    }

    private static AnalysisReplayPayload decode(byte[] bytes) throws Exception {
        return AnalysisReplayCodec.read(new ByteArrayInputStream(bytes));
    }

    private static PgDatabase analyze(Path root) throws Exception {
        CoreSettings settings = settings();
        PgDatabase database = new PgProjectLoader(root, settings).load();
        FullAnalyze.fullAnalyze(database, settings.getErrors(),
                settings.getVersion());
        assertNull(assertNullIfEmpty(settings.getErrors()),
                "the fixture project must analyze without errors");
        return database;
    }

    private static Object assertNullIfEmpty(List<Object> errors) {
        return errors.isEmpty() ? null : errors.get(0);
    }

    private static CoreSettings settings() {
        var settings = new CoreSettings();
        settings.setInCharsetName(StandardCharsets.UTF_8.name());
        return settings;
    }

    private static void writeProject(Path root) throws Exception {
        for (Map.Entry<String, String> file : PROJECT.entrySet()) {
            write(root, file.getKey(), file.getValue());
        }
    }

    private static void write(Path root, String relativePath, String sql)
            throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(Objects.requireNonNull(file.getParent()));
        Files.writeString(file, sql + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    /**
     * Fails when the fixture stopped producing an analysable model, which would
     * otherwise make every equality assertion of this class trivially true.
     */
    private static void assertFixtureIsAnalysable(PgDatabase structural) {
        assertFalse(structural.getAnalysisLaunchers().isEmpty(),
                "the fixture must produce analysis launchers");
        assertTrue(StatementAddress.index(structural).size() >= MIN_STATEMENTS,
                "the fixture must load at least " + MIN_STATEMENTS
                        + " statements");
    }
}
