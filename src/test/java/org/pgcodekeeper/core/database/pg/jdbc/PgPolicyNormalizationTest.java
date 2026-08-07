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
package org.pgcodekeeper.core.database.pg.jdbc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.api.schema.EventType;
import org.pgcodekeeper.core.database.api.schema.IStatementContainer;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgPolicy;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.database.pg.schema.PgSimpleTable;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;

/**
 * The two expression-shaped members of a PostgreSQL policy, {@code USING} and
 * {@code WITH CHECK}, and the two writers each of them reaches the model from:
 * {@code PgCreatePolicy} for a project file and {@link PgPoliciesReader} for a
 * catalog row. {@code PgAlterOther.alterPolicy} is not a third writer - it only
 * records object references, so an {@code ALTER POLICY} in a project file leaves
 * both fields untouched, which is pre-existing and not this test's subject.
 * <p>
 * The project side is driven through {@link PgDumpLoader}, i.e. the whole
 * project-file route including {@code PgCustomParserListener}, because the token
 * stream this task threads is handed over on that listener's line and a test
 * that built {@code PgCreatePolicy} itself would supply the stream it is
 * supposed to be checking. The database side is driven by running
 * {@link PgPoliciesReader#processResult} over a mocked catalog row, for the same
 * reason in reverse: a helper that mirrors the reader's line is a copy of the
 * reader, not the reader.
 * <p>
 * Every expression below is written so that it <i>differs from its own
 * normalized form</i>. A fixture already spelled canonically would make the raw
 * and the normalized halves byte-identical, every assertion would pass whether
 * or not normalization happened at all, and no mutation of the production code
 * could redden the test. {@link #theFixturesDifferFromTheirOwnNormalizedForm()}
 * asserts that property instead of assuming it.
 * <p>
 * <b>The reader's own parentheses.</b> {@code PgPoliciesReader} stores
 * {@code '(' + pg_get_expr(...) + ')'} rather than the catalog text as it comes.
 * They are load-bearing and they stay: {@code pg_get_expr} parenthesizes an
 * operator expression but not a bare {@code Var}, {@code Const} or function
 * call, while {@code CREATE POLICY ... USING} requires parentheses always - both
 * halves of that are visible in {@code it/jdbc/pg/pg_16_dump_test.sql:152-154},
 * where {@code USING (true)} carries only the parentheses the reader added and
 * {@code WITH CHECK ((1 > 0))} carries a pair from each side. So the normalized
 * half is wrapped the same way the raw half is, and the two sides converge on
 * the spelling an export produces rather than on the one a hand writes -
 * {@link #theTwoSidesAgreeOnTheSpellingAnExportWrote()} and
 * {@link #aHandWrittenSingleParenthesisIsStillADifference()} pin both ends of
 * that.
 */
class PgPolicyNormalizationTest {

    private static final String SCHEMA_NAME = "public";
    private static final String TABLE_NAME = "t1";
    private static final String POLICY_NAME = "p_visible";

    /**
     * How a project file might hold the {@code USING} filter: tight around the
     * operators and lower case throughout.
     */
    private static final String USING_TIGHT = "(t1.id>0 and t1.status<>'hidden')";

    /** The same filter as another branch - or another author - might spell it. */
    private static final String USING_SPACED = "( t1.id > 0 AND t1.status <> 'hidden' )";

    /**
     * What the normalizer makes of both of the above. Pinned as a literal
     * because the normalized field deliberately has no getter: a policy built by
     * hand with this text is the only way to observe from outside that the
     * writer stored exactly it.
     */
    private static final String USING_NORMALIZED = "(t1.id > 0 AND t1.status <> 'hidden')";

    /** The same three spellings for {@code WITH CHECK}, deliberately a different
     * expression from the {@code USING} one so that a writer normalizing only
     * one of the two fields cannot pass by accident. */
    private static final String CHECK_TIGHT = "(t1.amount>0 or t1.is_draft)";
    private static final String CHECK_SPACED = "( t1.amount > 0 OR t1.is_draft )";
    private static final String CHECK_NORMALIZED = "(t1.amount > 0 OR t1.is_draft)";

    /**
     * An expression the {@code vex} grammar cannot read. Its only job is to fail
     * the parse, and {@link #expressionsThatFailToParseStillReachTheModel()}
     * asserts that it does rather than assuming it - a fixture that quietly
     * started parsing would turn that test green for the wrong reason.
     */
    private static final String UNPARSABLE = "0 COLLATE";

    @Test
    void theFixturesDifferFromTheirOwnNormalizedForm() {
        assertNotEquals(USING_TIGHT, USING_NORMALIZED,
                "a canonically written fixture would make this whole test decorative");
        assertNotEquals(USING_SPACED, USING_NORMALIZED, "same for the second spelling");
        assertEquals(USING_NORMALIZED, normalize(USING_TIGHT),
                "the pinned normalized text must be what the normalizer actually produces");
        assertEquals(USING_NORMALIZED, normalize(USING_SPACED), "and both spellings must reach it");

        assertNotEquals(CHECK_TIGHT, CHECK_NORMALIZED, "and the same for the WITH CHECK fixtures");
        assertNotEquals(CHECK_SPACED, CHECK_NORMALIZED, "same for the second spelling");
        assertEquals(CHECK_NORMALIZED, normalize(CHECK_TIGHT), "measured, not predicted");
        assertEquals(CHECK_NORMALIZED, normalize(CHECK_SPACED), "and both spellings must reach it");

        assertNotEquals(USING_NORMALIZED, CHECK_NORMALIZED,
                "the two fields must carry different expressions, or a writer that fills"
                        + " one of them from the other would pass unnoticed");
    }

    @Test
    void aRespacedUsingReadsAsUnchanged() throws Exception {
        // WITH CHECK is held byte-identical, so only the USING field can account
        // for a failure here
        assertConverge(policyOf(policyFile(USING_TIGHT, CHECK_TIGHT)),
                policyOf(policyFile(USING_SPACED, CHECK_TIGHT)));
    }

    @Test
    void aRespacedCheckReadsAsUnchanged() throws Exception {
        assertConverge(policyOf(policyFile(USING_TIGHT, CHECK_TIGHT)),
                policyOf(policyFile(USING_TIGHT, CHECK_SPACED)));
    }

    /**
     * The normalized text the parser stored, observed without a getter: a policy
     * carrying the pinned normalized form compares equal to a parsed one. Were
     * the parser to hand the raw text over as the normalized one, this policy
     * would hold the normalized spelling while the parsed one held the tight
     * spelling, and the two would part.
     */
    @Test
    void theParserStoresTheNormalizedFormAndNotTheRawOne() throws Exception {
        assertConverge(parented(handBuilt(USING_TIGHT, USING_NORMALIZED, CHECK_TIGHT, CHECK_NORMALIZED, null)),
                policyOf(policyFile(USING_TIGHT, CHECK_TIGHT)));
        assertConverge(parented(handBuilt(USING_SPACED, USING_NORMALIZED, CHECK_SPACED, CHECK_NORMALIZED, null)),
                policyOf(policyFile(USING_SPACED, CHECK_SPACED)));
    }

    @Test
    void genuinelyDifferentExpressionsStillCompareAsChanged() throws Exception {
        PgPolicy original = policyOf(policyFile(USING_TIGHT, CHECK_TIGHT));
        PgPolicy changedUsing = policyOf(policyFile("(t1.id>1 and t1.status<>'hidden')", CHECK_TIGHT));
        PgPolicy changedCheck = policyOf(policyFile(USING_TIGHT, "(t1.amount>1 or t1.is_draft)"));

        assertFalse(original.compare(changedUsing), "a genuinely different USING must still compare as changed");
        assertFalse(changedUsing.compare(original), "and the other way round too");
        assertFalse(original.compare(changedCheck), "a genuinely different WITH CHECK must still compare as changed");
        assertFalse(changedCheck.compare(original), "and the other way round too");
    }

    /**
     * The copy trap. {@code PgPolicy.getCopy()} moves fields one at a time, so a
     * forgotten normalized half is lost in silence and nothing else in the tree
     * notices - a copy would simply start reading as changed against its own
     * original.
     */
    @Test
    void copyingCarriesBothNormalizedHalves() throws Exception {
        PgPolicy original = policyOf(policyFile(USING_TIGHT, CHECK_TIGHT));
        PgPolicy copy = (PgPolicy) original.deepCopy();
        // a copy comes out of deepCopy unparented, and a statement's hash covers
        // the names of its parents (AbstractStatement.computeNamesHash)
        copy.setParent(original.getParent());

        assertConverge(copy, original);
        // and the copy must still converge with differently spelled parses, one
        // field at a time, which a copy that dropped either normalized half
        // could not do
        assertConverge(copy, policyOf(policyFile(USING_SPACED, CHECK_TIGHT)));
        assertConverge(copy, policyOf(policyFile(USING_TIGHT, CHECK_SPACED)));
    }

    /**
     * The output side: both emitters carry the author's own spelling, never the
     * normalized one. The normalized text exists for comparison only; letting it
     * reach the DDL would rewrite user expressions into migrations, and the
     * fixture corpus does not catch that on its own - a compare fixture is
     * asserted empty, so a rewritten filter inside a script that is never
     * generated stays invisible.
     * <p>
     * Four lines emit a policy expression and they are asked separately here:
     * the {@code USING} and {@code WITH CHECK} clauses of {@code CREATE POLICY},
     * and the two of {@code ALTER POLICY}.
     */
    @Test
    void theAuthorsOwnSpellingIsWhatReachesTheScript() throws Exception {
        String creation = creationScript(policyOf(policyFile(USING_TIGHT, CHECK_TIGHT)));
        assertTrue(creation.contains("USING " + USING_TIGHT),
                () -> "the author's USING must reach the script, got:\n" + creation);
        assertTrue(creation.contains("WITH CHECK " + CHECK_TIGHT),
                () -> "and the author's WITH CHECK too, got:\n" + creation);
        assertFalse(creation.contains(USING_NORMALIZED),
                () -> "the normalized USING must never reach the script, got:\n" + creation);
        assertFalse(creation.contains(CHECK_NORMALIZED),
                () -> "the normalized WITH CHECK must never reach the script, got:\n" + creation);

        String changedUsing = "(t1.id>1 and t1.status<>'hidden')";
        String changedCheck = "(t1.amount>1 or t1.is_draft)";
        // measured, not predicted: what these spellings must never turn into
        String changedUsingNormalized = normalize(changedUsing);
        String changedCheckNormalized = normalize(changedCheck);
        assertNotEquals(changedUsing, changedUsingNormalized, "or this assertion would hold for the wrong reason");
        assertNotEquals(changedCheck, changedCheckNormalized, "same here");

        String alter = alterScript(policyOf(policyFile(USING_TIGHT, CHECK_TIGHT)),
                policyOf(policyFile(changedUsing, changedCheck)));
        assertTrue(alter.contains("USING " + changedUsing),
                () -> "and the same for the USING of an ALTER, got:\n" + alter);
        assertTrue(alter.contains("WITH CHECK " + changedCheck),
                () -> "and for its WITH CHECK, got:\n" + alter);
        assertFalse(alter.contains(changedUsingNormalized),
                () -> "the normalized form must never reach the script, got:\n" + alter);
        assertFalse(alter.contains(changedCheckNormalized),
                () -> "the normalized form must never reach the script, got:\n" + alter);
    }

    /**
     * The seam between the comparison and the emission gate, measured rather
     * than estimated.
     * <p>
     * {@code PgPolicy.appendAlterSQL} decides what to write by comparing the raw
     * halves, while the comparison that decides whether the policy differs at
     * all reads the normalized ones. The mismatch is deliberate - see the field
     * javadoc - and this is its price, taken from a full pipeline run rather
     * than from reasoning: a re-spelled policy alone produces no script, and a
     * re-spelled policy riding along with a genuine change adds its two
     * redundant clauses to a statement that had to be written anyway, in the
     * author's own spelling.
     */
    @Test
    void aRespelledPolicyOnlyEverJoinsAnAlterThatWasNeededAnyway() throws Exception {
        String none = pipeline(policyFile(USING_TIGHT, CHECK_TIGHT), policyFile(USING_SPACED, CHECK_SPACED));
        assertEquals("", none.trim(), () -> "a re-spelled policy alone must produce no script, got:\n" + none);

        // pinned whole, because the bound is the whole script: the role change
        // that had to be written, and the two redundant clauses beside it
        String script = pipeline(policyFile(USING_TIGHT, CHECK_TIGHT),
                policyFile(USING_SPACED, CHECK_SPACED, "test_user_2"));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER POLICY %1$s ON %2$s.%3$s
                  TO test_user_2
                  USING %4$s
                  WITH CHECK %5$s;"""
                .formatted(POLICY_NAME, SCHEMA_NAME, TABLE_NAME, USING_SPACED, CHECK_SPACED), script.trim());
    }

    /**
     * The reader itself, over a catalog row - the test that names
     * {@link PgPoliciesReader} by running it rather than by mirroring it.
     * <p>
     * The stream the reader hands to the normalizer is reachable from nowhere
     * else, and in a running program replacing it with {@code null} is not a
     * quiet defect but a failed database read: the normalizer dereferences it
     * and the NPE comes out of the finalizer. The quiet variant - a parse
     * written into an object nobody keeps - is equally invisible from a mirrored
     * helper. Both die here: this drives {@code processResult} over one mocked
     * row, finishes the loader's parse queue exactly as a real load does, and
     * compares what came out with a policy built by hand.
     */
    @Test
    void theReaderItselfNormalizesTheExpressionsItReparses() throws Exception {
        var settings = new CoreSettings();
        PgPolicy loaded = read(USING_TIGHT, CHECK_TIGHT, settings);

        assertTrue(settings.getErrors().isEmpty(),
                () -> "the catalog expressions must parse, otherwise the finalizer never runs: "
                        + settings.getErrors());

        // the reader wraps both halves in parentheses of its own, so the twin
        // carries the wrapped raw text and the wrapped normalized text; polcmd
        // comes from a catalog column and not from the parse, so the twin has to
        // carry the event that column stands for
        assertConverge(parented(handBuilt(wrapped(USING_TIGHT), wrapped(USING_NORMALIZED),
                wrapped(CHECK_TIGHT), wrapped(CHECK_NORMALIZED), EventType.UPDATE)), loaded);

        // and the same catalog text spelled loosely reads as the same policy,
        // which is the whole point of the field
        PgPolicy respelled = read(USING_SPACED, CHECK_SPACED, new CoreSettings());
        assertConverge(loaded, respelled);

        // the raw halves survive the reader path too
        String ddl = creationScript(loaded);
        assertTrue(ddl.contains("USING " + wrapped(USING_TIGHT)),
                () -> "the catalog's own USING must reach the script, got:\n" + ddl);
        assertTrue(ddl.contains("WITH CHECK " + wrapped(CHECK_TIGHT)),
                () -> "and its WITH CHECK too, got:\n" + ddl);
    }

    /**
     * The finalizer trap, guarded rather than trusted. The loader runs an ANTLR
     * finalizer only for a parse that reported no errors
     * ({@code AbstractJdbcLoader.submitAntlrTask}), so the raw halves have to be
     * assigned before the tasks are submitted and unconditionally - as they
     * already were before they grew normalized siblings. Folding either
     * assignment into its finalizer would cost the policy its filter entirely,
     * which for a row policy means the row restriction silently disappears.
     */
    @Test
    void expressionsThatFailToParseStillReachTheModel() throws Exception {
        var settings = new CoreSettings();
        PgPolicy loaded = read(UNPARSABLE, UNPARSABLE, settings);

        assertFalse(settings.getErrors().isEmpty(),
                "these expressions are supposed to fail the vex grammar, otherwise the test proves nothing");
        String ddl = creationScript(loaded);
        assertTrue(ddl.contains("USING " + wrapped(UNPARSABLE)),
                () -> "the USING must survive a failed parse, got:\n" + ddl);
        assertTrue(ddl.contains("WITH CHECK " + wrapped(UNPARSABLE)),
                () -> "and the WITH CHECK too, got:\n" + ddl);
    }

    /**
     * The sharper half of the same trap: an unreadable filter must not be
     * mistaken for no filter at all.
     * <p>
     * {@code PgPolicy.compare} and {@code computeHash} read only the
     * normalized halves, and an empty normalized half is exactly what a policy
     * without a {@code USING} carries. Leaving that half empty on a failed parse
     * therefore makes a policy the database holds compare <i>equal</i> to a
     * project file whose policy has no filter at all: no node in the diff tree,
     * no line in the script, and a row restriction that stays in the database
     * with nothing in the project answering for it. The reader fills both halves
     * before it submits the parse for exactly this reason, and the finalizer
     * overwrites the normalized one when the parse succeeds.
     */
    @Test
    void anUnreadableUsingIsNotTheSameAsNoUsingAtAll() throws Exception {
        var settings = new CoreSettings();
        PgPolicy withFilter = read(UNPARSABLE, null, settings);

        assertFalse(settings.getErrors().isEmpty(),
                "this filter is supposed to fail the vex grammar, otherwise the test proves nothing");
        assertDiverge(withFilter, read(null, null, new CoreSettings()));
    }

    /**
     * The same for {@code WITH CHECK}, asked separately so that a fix applied to
     * one of the reader's two branches cannot pass for both.
     */
    @Test
    void anUnreadableCheckIsNotTheSameAsNoCheckAtAll() throws Exception {
        var settings = new CoreSettings();
        PgPolicy withCheck = read(null, UNPARSABLE, settings);

        assertFalse(settings.getErrors().isEmpty(),
                "this expression is supposed to fail the vex grammar, otherwise the test proves nothing");
        assertDiverge(withCheck, read(null, null, new CoreSettings()));
    }

    /**
     * Why the reader's own parentheses exist, and why normalization does not
     * remove them: {@code pg_get_expr} renders an operator expression
     * parenthesized but a bare {@code Const} or function call not, while
     * {@code CREATE POLICY ... USING} takes a parenthesized expression always.
     * Without the wrap the reader would produce a policy whose emitted DDL a
     * server rejects.
     */
    @Test
    void theReadersOwnParenthesesAreLoadBearingForTheScript() throws Exception {
        PgPolicy loaded = read("is_admin()", "true", new CoreSettings());

        String ddl = creationScript(loaded);
        assertTrue(ddl.contains("USING (is_admin())"),
                () -> "an unparenthesized catalog filter must still be emitted parenthesized, got:\n" + ddl);
        assertTrue(ddl.contains("WITH CHECK (true)"),
                () -> "and the same for WITH CHECK, got:\n" + ddl);
    }

    /**
     * The two sides, agreeing. A project file that spells the expression the way
     * an export of this very database wrote it - i.e. with the reader's own
     * parentheses included - converges with the database object, and goes on
     * converging after the file is re-spaced and re-cased by hand. That last
     * part is what this task adds: before it, the two halves had to match byte
     * for byte.
     */
    @Test
    void theTwoSidesAgreeOnTheSpellingAnExportWrote() throws Exception {
        PgPolicy fromDatabase = read(USING_TIGHT, CHECK_TIGHT, new CoreSettings());
        PgPolicy fromExportedFile = policyOf(
                policyFile(wrapped(USING_TIGHT), wrapped(CHECK_TIGHT), null, EventType.UPDATE));
        PgPolicy fromRespelledFile = policyOf(
                policyFile(wrapped(USING_SPACED), wrapped(CHECK_SPACED), null, EventType.UPDATE));

        assertConverge(fromDatabase, fromExportedFile);
        assertConverge(fromDatabase, fromRespelledFile);
    }

    /**
     * The wall this task does not remove, pinned so that nobody removes the
     * reader's parentheses by mistaking it for a defect. A hand-written file
     * that parenthesizes the filter once still differs from the database, whose
     * text carries a pair from the deparser and a pair from the reader. Dropping
     * the reader's pair from the normalized half would close this gap and open a
     * worse one: an exported file - the far more common case, and the one
     * {@code PgJdbcLoaderTest} rides on - would start reading as changed against
     * the database it came from.
     */
    @Test
    void aHandWrittenSingleParenthesisIsStillADifference() throws Exception {
        PgPolicy fromDatabase = read(USING_TIGHT, CHECK_TIGHT, new CoreSettings());
        PgPolicy handWritten = policyOf(policyFile(USING_TIGHT, CHECK_TIGHT, null, EventType.UPDATE));

        assertFalse(fromDatabase.compare(handWritten),
                "a paren level is still a difference - see the javadoc for why that is the lesser evil");
    }

    private static void assertConverge(PgPolicy a, PgPolicy b) {
        assertTrue(a.compare(b), "expected the two policies to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged policies must hash the same");
    }

    /**
     * {@link Comparison#compare} is asked alongside {@code compare} because it
     * is what the diff tree calls, and it gates on the hash before it looks at
     * anything else - so a difference the hash cannot see is one the tree cannot
     * see either.
     */
    private static void assertDiverge(PgPolicy a, PgPolicy b) {
        assertFalse(a.compare(b), "an unreadable expression must not compare equal to no expression at all");
        assertFalse(b.compare(a), "compare must be symmetric");
        assertFalse(Comparison.compare(new CoreSettings(), a, b),
                "and the entry point the diff tree uses must see the difference too");
    }

    private static String wrapped(String expression) {
        return '(' + expression + ')';
    }

    private static PgDatabase policyFile(String using, String check) throws Exception {
        return policyFile(using, check, null, null);
    }

    private static PgDatabase policyFile(String using, String check, String role) throws Exception {
        return policyFile(using, check, role, null);
    }

    private static PgDatabase policyFile(String using, String check, String role, EventType event) throws Exception {
        return loadProjectFile("""
                CREATE TABLE %1$s.%2$s (id integer, status text, amount numeric, is_draft boolean);

                CREATE POLICY %3$s ON %1$s.%2$s%4$s%5$s
                  USING %6$s
                  WITH CHECK %7$s;
                """.formatted(SCHEMA_NAME, TABLE_NAME, POLICY_NAME,
                event == null ? "" : "\n  FOR " + event,
                role == null ? "" : "\n  TO " + role,
                using, check));
    }

    /**
     * The project-file side, whole: {@link PgDumpLoader} over the text, so the
     * listener picks the parser class - and hands it the token stream - exactly
     * as it does for a file on disk.
     */
    private static PgDatabase loadProjectFile(String sql) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "policy normalization test", new CoreSettings()).load();
    }

    private static PgPolicy policyOf(PgDatabase db) {
        return db.getChildren()
                .filter(PgSchema.class::isInstance)
                .map(PgSchema.class::cast)
                .map(s -> s.getStatementContainer(TABLE_NAME))
                .filter(c -> c != null)
                .flatMap(IStatementContainer::getChildren)
                .filter(PgPolicy.class::isInstance)
                .map(PgPolicy.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no policy was parsed"));
    }

    /**
     * A twin carrying every value the writers set on this policy - the event
     * among them, which on the reader side comes from the {@code polcmd} column
     * and not from the parse. A twin missing one of those fails in a way that
     * looks exactly like a normalization defect.
     * <p>
     * {@code isPermissive} keeps its default on both sides: the project side
     * sets it from the absence of {@code RESTRICTIVE}, and the reader sets it
     * from {@code polpermissive} only when the server version reaches the one
     * {@code PgSupportedVersion.GP_VERSION_7} names - 12.12 - while the offline
     * loader here reports 0.
     */
    private static PgPolicy handBuilt(String using, String usingNormalized,
            String check, String checkNormalized, EventType event) {
        var policy = new PgPolicy(POLICY_NAME);
        policy.setEvent(event);
        policy.setUsing(using, usingNormalized);
        policy.setCheck(check, checkNormalized);
        return policy;
    }

    /**
     * Mirrors what both writers do last: the parser hands the policy to the
     * table it is declared on ({@code PgCreatePolicy.parseObject}) and so does
     * the reader ({@code PgPoliciesReader.processResult}). Both sides of a
     * comparison must be parented the same way, because a statement's hash
     * covers the names of everything above it.
     */
    private static PgPolicy parented(PgPolicy policy) {
        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);
        table.addChild(policy);
        return policy;
    }

    private static String creationScript(PgPolicy policy) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, policy.getSeparator());
        policy.getCreationSQL(script);
        return script.getFullScript();
    }

    private static String alterScript(PgPolicy oldPolicy, PgPolicy newPolicy) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, oldPolicy.getSeparator());
        oldPolicy.appendAlterSQL(newPolicy, script);
        return script.getFullScript();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }

    /**
     * Runs the normalizer over the same context the project-side writer hands it
     * - the {@code using} of a {@code CREATE POLICY} - so the pinned text is
     * measured against the production entry point rather than against a
     * convenient stand-in.
     */
    private static String normalize(String expression) {
        List<Object> errors = new ArrayList<>();
        SQLParser parser = PgParserUtils.createSqlParser(
                "CREATE POLICY %s ON %s.%s USING %s;".formatted(POLICY_NAME, SCHEMA_NAME, TABLE_NAME, expression),
                "policy expression normalizer probe", errors);
        var using = parser.sql().statement(0).schema_statement().schema_create()
                .create_policy_statement().using;
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        return PgParserUtils.normalizeWhitespaceUnquoted(using, (CommonTokenStream) parser.getTokenStream());
    }

    /**
     * Runs {@link PgPoliciesReader#processResult} over one mocked catalog row
     * and finishes the loader's parse queue, which runs the deferred finalizers
     * exactly as a real load does. Returns the policy the reader hung off the
     * table.
     * <p>
     * The two expression columns hold what {@code pg_get_expr} would return,
     * i.e. without the parentheses the reader adds - spelled non-canonically on
     * purpose, since a canonical fixture could not tell a reader that normalizes
     * from one that does not.
     */
    private static PgPolicy read(String polqual, String polwithcheck, CoreSettings settings) throws Exception {
        ResultSet res = mock(ResultSet.class);
        when(res.getString("relname")).thenReturn(TABLE_NAME);
        when(res.getString("polname")).thenReturn(POLICY_NAME);
        when(res.getString("polcmd")).thenReturn("w");
        when(res.getString("polqual")).thenReturn(polqual);
        when(res.getString("polwithcheck")).thenReturn(polwithcheck);
        // polroles stays null, which is the reader's "no roles" case

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        var table = new PgSimpleTable(TABLE_NAME);
        schema.addChild(table);

        try (TestLoader loader = new TestLoader(settings)) {
            new PgPoliciesReader(loader).processResult(res, schema);
            loader.drain();
        }

        return table.getChildren()
                .filter(PgPolicy.class::isInstance)
                .map(PgPolicy.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reader added no policy"));
    }

    /**
     * {@code finishLoaders} is protected on {@code AbstractLoader}, so a subclass
     * reaches it without reflection. Nothing here queries - the reader is handed
     * its row directly - so the connector exists only to satisfy the
     * constructor.
     */
    private static final class TestLoader extends PgJdbcLoader {

        private TestLoader(CoreSettings settings) {
            super(offlineConnector(), null, settings);
        }

        private void drain() throws InterruptedException, IOException {
            finishLoaders();
        }
    }

    private static IJdbcConnector offlineConnector() {
        return new IJdbcConnector() {

            @Override
            public Connection getConnection() throws IOException {
                throw new AssertionError("this test must not open a connection");
            }

            @Override
            public String getBatchDelimiter() {
                return null;
            }

            @Override
            public String getUrl() {
                return "jdbc:test";
            }

            @Override
            public String getDbName() {
                return "test";
            }
        };
    }
}
