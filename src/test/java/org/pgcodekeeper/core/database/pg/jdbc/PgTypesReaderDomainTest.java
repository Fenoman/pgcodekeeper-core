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
import java.util.function.Function;

import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.jdbc.IJdbcConnector;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.loader.PgJdbcLoader;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.Domain_constraintContext;
import org.pgcodekeeper.core.database.pg.parser.statement.PgCreateDomain;
import org.pgcodekeeper.core.database.pg.schema.PgConstraintCheck;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgDomain;
import org.pgcodekeeper.core.database.pg.schema.PgSchema;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.utils.Utils;

/**
 * The two expression-shaped members of a domain, and the writers each of them
 * reaches the model from.
 * <p>
 * <b>The CHECK constraint</b> arrives through
 * {@link PgCreateDomain#parseDomainConstraint} from three independent call
 * sites: the {@code CREATE DOMAIN} parser, the {@code ALTER DOMAIN} parser, and
 * {@link PgTypesReader#getDomain}, which re-parses a
 * {@code pg_get_constraintdef()} result through the very template reproduced
 * here ({@code "ALTER DOMAIN noname ADD CONSTRAINT noname " + definition +
 * ';'}). Those tests drive all three shapes directly through the shared parsing
 * method and compare the resulting constraints, proving the paths are genuinely
 * one call site under the hood rather than three call sites that merely look
 * alike.
 * <p>
 * <b>The DEFAULT</b> has three writers: {@code PgCreateDomain},
 * {@code PgAlterDomain} - which covers both of the {@code ALTER} alternatives
 * that carry a default, {@code set_def_column} and {@code drop_def}
 * ({@code SQLParser.g4:674-675,719-725}) - and {@link PgTypesReader#getDomain},
 * which reaches it along two branches, an expression from
 * {@code dom_defaultbin} that is re-parsed, and a literal from
 * {@code typdefault} that is not. The project side is driven here
 * through {@link PgDumpLoader}, i.e. the whole project-file route including the
 * listener; the database side by running {@link PgTypesReader#processResult}
 * over a mocked catalog row, because the reader's own line is reachable no other
 * way and a helper that mirrors that line would be a copy of the reader rather
 * than the reader.
 * <p>
 * Every expression below is written so that it <i>differs from its own
 * normalized form</i>. A fixture already spelled canonically would make the raw
 * and the normalized halves byte-identical, every assertion would pass whether
 * or not normalization happened at all, and no mutation of the production code
 * could redden the test. {@link #theDefaultFixturesDifferFromTheirOwnNormalizedForm()}
 * asserts that property instead of assuming it.
 */
class PgTypesReaderDomainTest {

    @Test
    void createAlterAndCatalogReparseAgreeOnARecasedExpression() {
        PgConstraintCheck fromCreate = parseCreateDomainCheck(
                """
                CREATE DOMAIN public.dom AS numeric
                    CONSTRAINT dom_check CHECK ((VALUE is not null and VALUE > (0)::numeric));
                """);

        PgConstraintCheck fromAlter = parseAlterDomainCheck(
                """
                ALTER DOMAIN public.dom
                    ADD CONSTRAINT dom_check check ( ( VALUE is NOT NULL AND VALUE > (0)::numeric ) );
                """);

        // Exactly the shape PgTypesReader.getDomain() builds around a
        // pg_get_constraintdef() result before re-parsing it (ADD_CONSTRAINT
        // constant there is "ALTER DOMAIN noname ADD CONSTRAINT noname ").
        // "is" keeps the same case as the other two snippets throughout - IS
        // (404) sits outside the folded reserved-word range, so unlike NOT/AND
        // it is not case-normalized and must be held constant here to isolate
        // the fold-range comparison from that unrelated, pre-existing limit.
        PgConstraintCheck fromCatalogReparse = parseAlterDomainCheck(
                "ALTER DOMAIN noname ADD CONSTRAINT noname "
                        + "CHECK ((VALUE is NOT NULL AND VALUE > (0)::numeric));");

        assertConverge(fromCreate, fromAlter);
        assertConverge(fromCreate, fromCatalogReparse);
    }

    @Test
    void aGenuinelyDifferentConstantStillComparesAsChanged() {
        PgConstraintCheck original = parseCreateDomainCheck(
                "CREATE DOMAIN public.dom AS numeric CONSTRAINT dom_check CHECK ((VALUE > (0)::numeric));");
        PgConstraintCheck changed = parseCreateDomainCheck(
                "CREATE DOMAIN public.dom AS numeric CONSTRAINT dom_check CHECK ((VALUE > (1)::numeric));");

        assertFalse(original.compare(changed),
                "a genuinely different constant must still compare as changed");
        assertFalse(changed.compare(original),
                "and the other way round too");
    }

    private static void assertConverge(PgConstraintCheck a, PgConstraintCheck b) {
        assertTrue(a.compare(b), "expected the two parses to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged constraints must hash the same");
    }

    private static PgConstraintCheck parseCreateDomainCheck(String sql) {
        return parse(sql, p -> p.sql().statement(0).schema_statement().schema_create()
                .create_domain_statement().dom_constraint.get(0));
    }

    private static PgConstraintCheck parseAlterDomainCheck(String sql) {
        return parse(sql, p -> p.sql().statement(0).schema_statement().schema_alter()
                .alter_domain_statement().dom_constraint);
    }

    private static PgConstraintCheck parse(String sql, Function<SQLParser, Domain_constraintContext> extractor) {
        List<Object> errors = new ArrayList<>();
        SQLParser parser = PgParserUtils.createSqlParser(sql, "domain check convergence test", errors);
        Domain_constraintContext ctx = extractor.apply(parser);
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        var stream = (CommonTokenStream) parser.getTokenStream();
        var domain = new PgDomain("dom");
        var db = new PgDatabase();
        var constrCheck = new PgConstraintCheck("dom_check");
        PgCreateDomain.parseDomainConstraint(domain, constrCheck, ctx, db, "test", stream, new CoreSettings());
        return constrCheck;
    }

    // ------------------------------------------------------------------
    // the domain DEFAULT
    // ------------------------------------------------------------------

    private static final String SCHEMA_NAME = "public";
    private static final String DOMAIN_NAME = "positive_amount";
    private static final String DOMAIN_TYPE = "numeric";

    /**
     * How a project file might hold the default: tight around the parenthesis
     * and lower case throughout.
     */
    private static final String TIGHT = "cast(0 as numeric)";

    /** The same default as another branch might spell it. */
    private static final String SPACED = "CAST( 0 AS  numeric )";

    /**
     * What the normalizer makes of both of the above. Pinned as a literal
     * because the normalized field deliberately has no getter: a domain built by
     * hand with this text is the only way to observe from outside that the
     * writer stored exactly it.
     */
    private static final String NORMALIZED = "CAST (0 AS numeric)";

    /**
     * A default the expression grammar cannot read. Its only job is to fail the
     * parse, and {@link #aDefaultThatFailsToParseStillReachesTheModel()} asserts
     * that it does rather than assuming it - a fixture that quietly started
     * parsing would turn that test green for the wrong reason.
     */
    private static final String UNPARSABLE = "0 COLLATE";

    /**
     * The payload of the {@code typdefault} branch, quote included so the
     * quote-doubling of {@link Utils#quoteString} is exercised rather than
     * assumed away.
     */
    private static final String LITERAL_PAYLOAD = "it's";
    private static final String LITERAL_TYPE = "text";

    @Test
    void theDefaultFixturesDifferFromTheirOwnNormalizedForm() {
        assertNotEquals(TIGHT, NORMALIZED,
                "a canonically written fixture would make this whole test decorative");
        assertNotEquals(SPACED, NORMALIZED, "same for the second spelling");
        assertEquals(NORMALIZED, normalize(TIGHT),
                "the pinned normalized text must be what the normalizer actually produces");
        assertEquals(NORMALIZED, normalize(SPACED), "and both spellings must reach it");
    }

    @Test
    void aRespacedDomainDefaultReadsAsUnchanged() throws Exception {
        assertConverge(domainOf(createDomainWithDefault(TIGHT)), domainOf(createDomainWithDefault(SPACED)));
    }

    /**
     * The normalized text the parser stored, observed without a getter: a domain
     * carrying the pinned normalized form compares equal to a parsed one. Were
     * the parser to hand the raw text over as the normalized one, this domain
     * would hold {@code NORMALIZED} while the parsed one held {@code TIGHT}, and
     * the two would part.
     */
    @Test
    void theParserStoresTheNormalizedFormAndNotTheRawOne() throws Exception {
        // parented like the parser's own last line, because a statement's hash
        // covers the names of its parents (AbstractStatement.computeNamesHash);
        // an unparented twin fails on the hash alone and looks like a
        // normalization defect while being none
        assertConverge(parented(handBuilt(DOMAIN_TYPE, TIGHT, NORMALIZED, false)),
                domainOf(createDomainWithDefault(TIGHT)));
        assertConverge(parented(handBuilt(DOMAIN_TYPE, SPACED, NORMALIZED, false)),
                domainOf(createDomainWithDefault(SPACED)));
    }

    @Test
    void aGenuinelyDifferentDefaultStillComparesAsChanged() throws Exception {
        PgDomain original = domainOf(createDomainWithDefault("cast(0 as numeric)"));
        PgDomain changed = domainOf(createDomainWithDefault("cast(1 as numeric)"));

        assertFalse(original.compare(changed), "a genuinely different default must still compare as changed");
        assertFalse(changed.compare(original), "and the other way round too");
    }

    /**
     * The copy trap. {@link PgDomain#getCopy()} moves fields one at a time, so a
     * forgotten normalized half is lost in silence and nothing else in the tree
     * notices - a copy would simply start reading as changed against its own
     * original.
     */
    @Test
    void copyingCarriesTheNormalizedDefault() throws Exception {
        PgDomain original = domainOf(createDomainWithDefault(TIGHT));
        PgDomain copy = (PgDomain) original.deepCopy();
        // a copy comes out of deepCopy unparented, and a statement's hash covers
        // the names of its parents (AbstractStatement.computeNamesHash)
        copy.setParent(original.getParent());

        assertConverge(copy, original);
        // and the copy must still converge with a differently spelled parse,
        // which a copy that silently dropped the normalized text could not do
        assertConverge(copy, domainOf(createDomainWithDefault(SPACED)));
    }

    /**
     * The output side: both emitters carry the author's own spelling, never the
     * normalized one. The normalized text exists for comparison only; letting it
     * reach the DDL would rewrite user expressions into migrations.
     * <p>
     * Two lines emit a domain default and they are asked separately here: the
     * {@code DEFAULT} clause of {@code CREATE DOMAIN}, and the {@code SET
     * DEFAULT} that an {@code ALTER DOMAIN} writes.
     */
    @Test
    void theAuthorsOwnSpellingIsWhatReachesTheScript() throws Exception {
        String creation = creationScript(domainOf(createDomainWithDefault(TIGHT)));
        assertTrue(creation.contains("DEFAULT " + TIGHT),
                () -> "the author's spelling must reach the domain definition, got:\n" + creation);
        assertFalse(creation.contains(NORMALIZED),
                () -> "the normalized form must never reach the script, got:\n" + creation);

        String changed = "cast(1 as numeric)";
        // measured, not predicted: what this second spelling must never turn into
        String changedNormalized = normalize(changed);
        assertNotEquals(changed, changedNormalized, "or this assertion would hold for the wrong reason");

        String alter = alterScript(domainOf(createDomainWithDefault(TIGHT)),
                domainOf(createDomainWithDefault(changed)));
        assertTrue(alter.contains("SET DEFAULT " + changed),
                () -> "and the same for the SET DEFAULT of an ALTER, got:\n" + alter);
        assertFalse(alter.contains(changedNormalized),
                () -> "the normalized form must never reach the script, got:\n" + alter);
    }

    /**
     * The seam between the comparison and the emission gate, measured rather
     * than estimated.
     * <p>
     * {@code PgDomain.appendAlterSQL} decides whether to write a {@code SET
     * DEFAULT} by comparing the raw halves, while the comparison that decides
     * whether the domain is altered at all reads the normalized ones. The
     * mismatch is deliberate - see the field javadoc - and this is its whole
     * price, taken from a full pipeline run rather than from reasoning: a
     * re-spelled default alone produces no script, and a re-spelled default
     * riding along with a genuine change adds exactly one redundant statement to
     * a script that had to be written anyway.
     */
    @Test
    void aRespelledDefaultOnlyEverJoinsAnAlterThatWasNeededAnyway() throws Exception {
        String none = pipeline(createDomainWithDefault(TIGHT), createDomainWithDefault(SPACED));
        assertEquals("", none.trim(), () -> "a re-spelled default alone must produce no script, got:\n" + none);

        // pinned whole, because the bound is the whole script: the NOT NULL that
        // had to be written, and exactly one redundant statement beside it,
        // carrying the author's own spelling
        String script = pipeline(createDomainWithDefault(TIGHT), createDomainNotNullWithDefault(SPACED));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER DOMAIN %1$s.%2$s
                \tSET DEFAULT %3$s;

                ALTER DOMAIN %1$s.%2$s
                \tSET NOT NULL;""".formatted(SCHEMA_NAME, DOMAIN_NAME, SPACED), script);
    }

    /**
     * The defect this route was written for, stated directly: a project file
     * that declares its default through {@code ALTER DOMAIN ... SET DEFAULT}
     * against a database that already carries that default must produce no
     * script at all.
     * <p>
     * Before {@code PgAlterDomain} read the clause the outcome was not a
     * silently missing improvement but active damage: the model came out with no
     * default, the comparison called the domain changed, and
     * {@code PgDomain.appendAlterSQL} wrote an {@code ALTER DOMAIN ... DROP
     * DEFAULT} - the tool removed from the database the very default the project
     * file declares.
     */
    @Test
    void aDefaultDeclaredByAnAlterIsNotDroppedFromTheDatabase() throws Exception {
        String script = pipeline(createDomainWithDefault(TIGHT), setDefaultByAlter(TIGHT));
        assertEquals("", script.trim(),
                () -> "a default declared by ALTER must read as the one the database holds, got:\n" + script);
    }

    /**
     * The mirror of the same rule, and the reason {@code drop_def} may not
     * simply be ignored: a {@code DROP DEFAULT} in a project file has to clear
     * the value, or the asymmetry reappears the other way round - a project
     * saying "no default" while the model still carries one.
     * <p>
     * The file creates the domain <i>with</i> the default and drops it
     * afterwards, so the assertion cannot pass by the branch being inert: a
     * project file that never set one would compare as changed whether
     * {@code drop_def} was read or not.
     */
    @Test
    void aDropDefaultInAProjectFileClearsTheDefault() throws Exception {
        String script = pipeline(createDomainWithDefault(TIGHT), dropDefaultByAlter(TIGHT));
        assertEquals("""
                SET search_path = pg_catalog;

                ALTER DOMAIN %s.%s
                \tDROP DEFAULT;""".formatted(SCHEMA_NAME, DOMAIN_NAME), script.trim());
    }

    /**
     * Both halves, on the new route too. The normalized one has no getter, so it
     * is observed the way the rest of this class observes it - through a
     * hand-built twin carrying the pinned normalized text - and through the two
     * routes converging on each other while spelling the default differently.
     */
    @Test
    void theAlterRouteStoresBothHalvesOfTheDefault() throws Exception {
        assertConverge(parented(handBuilt(DOMAIN_TYPE, TIGHT, NORMALIZED, false)),
                domainOf(setDefaultByAlter(TIGHT)));
        assertConverge(domainOf(setDefaultByAlter(SPACED)), domainOf(createDomainWithDefault(TIGHT)));
    }

    /**
     * The output side of the same route: what an {@code ALTER} put into the
     * model is written back out as its author spelled it, never as the
     * normalizer rewrote it.
     */
    @Test
    void theAlterRouteKeepsTheAuthorsOwnSpelling() throws Exception {
        String ddl = creationScript(domainOf(setDefaultByAlter(TIGHT)));
        assertTrue(ddl.contains("DEFAULT " + TIGHT),
                () -> "the author's spelling must reach the script, got:\n" + ddl);
        assertFalse(ddl.contains(NORMALIZED),
                () -> "the normalized form must never reach the script, got:\n" + ddl);
    }

    /**
     * The reader itself, over a catalog row - the test that names
     * {@link PgTypesReader} by running it rather than by mirroring it.
     * <p>
     * The stream the reader hands to the normalizer is reachable from nowhere
     * else, and in a running program replacing it with {@code null} is not a
     * quiet defect but a failed database read: the normalizer dereferences it
     * and the NPE comes out of the finalizer. The quiet variant - a parse
     * written into an object nobody keeps - is equally invisible from a mirrored
     * helper. Both die here: this drives {@code processResult} over one mocked
     * row, finishes the loader's parse queue exactly as a real load does, and
     * compares what came out with a domain built by hand.
     */
    @Test
    void theReaderItselfNormalizesTheDefaultItReparses() throws Exception {
        var settings = new CoreSettings();
        PgDomain loaded = read(SPACED, null, DOMAIN_TYPE, settings);

        assertTrue(settings.getErrors().isEmpty(),
                () -> "the catalog default must parse, otherwise the finalizer never runs: " + settings.getErrors());

        assertConverge(parented(handBuilt(DOMAIN_TYPE, SPACED, NORMALIZED, true)), loaded);
        // and the catalog spelling a project file holds tight must read as one domain
        assertConverge(loaded, parented(handBuilt(DOMAIN_TYPE, TIGHT, NORMALIZED, true)));

        // the raw half survives the reader path too
        String ddl = creationScript(loaded);
        assertTrue(ddl.contains("DEFAULT " + SPACED),
                () -> "the catalog's own spelling must reach the script, got:\n" + ddl);
    }

    /**
     * The other branch of the reader: {@code typdefault} holds an already
     * external-form literal, which the reader only quotes. A quoted literal is a
     * single token and therefore its own normalized form, so the branch stores
     * the same text in both halves and needs no normalizer - asserted here
     * rather than reasoned about, and asserted against the reader's real output
     * rather than against a rewritten copy of its line.
     */
    @Test
    void theLiteralBranchIsItsOwnNormalizedForm() throws Exception {
        String quoted = Utils.quoteString(LITERAL_PAYLOAD);
        assertEquals(quoted, normalize(quoted),
                "a quoted literal must be a fixed point of the normalizer, or this branch needs one");

        var settings = new CoreSettings();
        PgDomain loaded = read(null, LITERAL_PAYLOAD, LITERAL_TYPE, settings);

        assertConverge(parented(handBuilt(LITERAL_TYPE, quoted, quoted, true)), loaded);
        // and a project file spelling the same literal reads as the same domain,
        // which it could not if the branch left the normalized half empty
        assertConverge(loaded, domainOf(domainFile(LITERAL_TYPE, quoted, true)));

        String ddl = creationScript(loaded);
        assertTrue(ddl.contains("DEFAULT " + quoted),
                () -> "the quoted literal must reach the script, got:\n" + ddl);
    }

    /**
     * The finalizer trap, guarded rather than trusted. The loader runs an ANTLR
     * finalizer only for a parse that reported no errors
     * ({@code AbstractJdbcLoader.submitAntlrTask}), so the raw half has to be
     * assigned before the task is submitted and unconditionally - as it already
     * was before it grew a normalized sibling. Folding that assignment into the
     * finalizer would cost the domain its default entirely.
     */
    @Test
    void aDefaultThatFailsToParseStillReachesTheModel() throws Exception {
        var settings = new CoreSettings();
        PgDomain loaded = read(UNPARSABLE, null, DOMAIN_TYPE, settings);

        assertFalse(settings.getErrors().isEmpty(),
                "this default is supposed to fail the expression grammar, otherwise the test proves nothing");
        String ddl = creationScript(loaded);
        assertTrue(ddl.contains("DEFAULT " + UNPARSABLE),
                () -> "the default must survive a failed parse, got:\n" + ddl);
    }

    /**
     * The sharper half of the same trap: an unreadable default must not be
     * mistaken for no default at all.
     * <p>
     * {@code PgDomain.compare} and {@code computeHash} read only the normalized
     * half, and an empty normalized half is exactly what a domain without a
     * {@code DEFAULT} carries. Leaving that half empty on a failed parse
     * therefore makes a domain the database holds compare <i>equal</i> to a
     * project file whose domain has no default: no node in the diff tree, no
     * line in the script, and a default left standing in the database with
     * nothing in the project answering for it. So the reader fills both halves
     * with the catalog's own text before it submits the parse, and the finalizer
     * overwrites the normalized one when the parse succeeds.
     * <p>
     * {@link Comparison#compare} is asked alongside {@code compare} because it
     * is what the diff tree calls, and it gates on the hash before it looks at
     * anything else.
     */
    @Test
    void anUnreadableDefaultIsNotTheSameAsNoDefaultAtAll() throws Exception {
        var settings = new CoreSettings();
        PgDomain withDefault = read(UNPARSABLE, null, DOMAIN_TYPE, settings);

        assertFalse(settings.getErrors().isEmpty(),
                "this default is supposed to fail the expression grammar, otherwise the test proves nothing");

        // both catalog columns null is how a domain with no default arrives
        PgDomain withoutDefault = read(null, null, DOMAIN_TYPE, new CoreSettings());
        assertFalse(withDefault.compare(withoutDefault),
                "an unreadable default must not compare equal to no default at all");
        assertFalse(withoutDefault.compare(withDefault), "compare must be symmetric");
        assertFalse(Comparison.compare(new CoreSettings(), withDefault, withoutDefault),
                "and the entry point the diff tree uses must see the difference too");
    }

    private static void assertConverge(PgDomain a, PgDomain b) {
        assertTrue(a.compare(b), "expected the two domains to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged domains must hash the same");
    }

    private static PgDatabase createDomainWithDefault(String expression) throws Exception {
        return domainFile(DOMAIN_TYPE, expression, false);
    }

    private static PgDatabase createDomainNotNullWithDefault(String expression) throws Exception {
        return domainFile(DOMAIN_TYPE, expression, true);
    }

    private static PgDatabase domainFile(String type, String expression, boolean notNull) throws Exception {
        return loadProjectFile("CREATE DOMAIN %s.%s AS %s DEFAULT %s%s;"
                .formatted(SCHEMA_NAME, DOMAIN_NAME, type, expression, notNull ? " NOT NULL" : ""));
    }

    /** A project file whose domain gets its default from a separate {@code ALTER}. */
    private static PgDatabase setDefaultByAlter(String expression) throws Exception {
        return loadProjectFile("""
                CREATE DOMAIN %1$s.%2$s AS %3$s;
                ALTER DOMAIN %1$s.%2$s SET DEFAULT %4$s;
                """.formatted(SCHEMA_NAME, DOMAIN_NAME, DOMAIN_TYPE, expression));
    }

    /**
     * A project file that creates the domain with a default and then drops it.
     * Written that way on purpose: a file that never declared one would compare
     * as changed against a database that has one whether or not
     * {@code drop_def} is read at all.
     */
    private static PgDatabase dropDefaultByAlter(String expression) throws Exception {
        return loadProjectFile("""
                CREATE DOMAIN %1$s.%2$s AS %3$s DEFAULT %4$s;
                ALTER DOMAIN %1$s.%2$s DROP DEFAULT;
                """.formatted(SCHEMA_NAME, DOMAIN_NAME, DOMAIN_TYPE, expression));
    }

    /**
     * The project-file side, whole: {@link PgDumpLoader} over the text, so the
     * listener picks the parser class exactly as it does for a file on disk.
     */
    private static PgDatabase loadProjectFile(String sql) throws IOException, InterruptedException {
        return new PgDumpLoader(() -> new ByteArrayInputStream(sql.getBytes(UTF_8)),
                "domain default test", new CoreSettings()).load();
    }

    private static PgDomain domainOf(PgDatabase db) {
        return db.getChildren()
                .filter(PgSchema.class::isInstance)
                .map(PgSchema.class::cast)
                .map(s -> s.getDomain(DOMAIN_NAME))
                .filter(d -> d != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no domain was parsed"));
    }

    /**
     * A twin carrying every value the writers set on this domain - the data type
     * and the {@code NOT NULL} flag among them, which on the reader side come
     * from catalog columns and not from the parse. A twin missing one of those
     * fails in a way that looks exactly like a normalization defect.
     */
    private static PgDomain handBuilt(String dataType, String expression, String normalized, boolean notNull) {
        var domain = new PgDomain(DOMAIN_NAME);
        domain.setDataType(dataType);
        domain.setDefaultValue(expression, normalized);
        domain.setNotNull(notNull);
        return domain;
    }

    /**
     * Mirrors what both writers do last: the parser hands the domain to its
     * schema ({@code PgCreateDomain.parseObject}) and so does the reader
     * ({@code PgTypesReader.processResult}). Both sides of a comparison must be
     * parented the same way, because a statement's hash covers the names of
     * everything above it.
     */
    private static PgDomain parented(PgDomain domain) {
        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);
        schema.addChild(domain);
        return domain;
    }

    private static String creationScript(PgDomain domain) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, domain.getSeparator());
        domain.getCreationSQL(script);
        return script.getFullScript();
    }

    private static String alterScript(PgDomain oldDomain, PgDomain newDomain) {
        var settings = new CoreSettings();
        var script = new SQLScript(settings, oldDomain.getSeparator());
        oldDomain.appendAlterSQL(newDomain, script);
        return script.getFullScript();
    }

    private static String pipeline(PgDatabase oldDb, PgDatabase newDb) throws IOException, InterruptedException {
        return PgCodeKeeperApi.diff(new PgDatabaseProvider(), oldDb, newDb, new CoreSettings());
    }

    /**
     * Runs the normalizer over the same context the project-side writer hands it
     * - the {@code def_value} of a {@code CREATE DOMAIN} - so the pinned text is
     * measured against the production entry point rather than against a
     * convenient stand-in.
     */
    private static String normalize(String expression) {
        List<Object> errors = new ArrayList<>();
        SQLParser parser = PgParserUtils.createSqlParser("CREATE DOMAIN %s.%s AS %s DEFAULT %s;"
                .formatted(SCHEMA_NAME, DOMAIN_NAME, DOMAIN_TYPE, expression),
                "domain default normalizer probe", errors);
        var def = parser.sql().statement(0).schema_statement().schema_create()
                .create_domain_statement().def_value;
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        return PgParserUtils.normalizeWhitespaceUnquoted(def, (CommonTokenStream) parser.getTokenStream());
    }

    /**
     * Runs {@link PgTypesReader#processResult} over one mocked catalog row and
     * finishes the loader's parse queue, which runs the deferred finalizer
     * exactly as a real load does. Returns the domain the reader hung off the
     * schema.
     * <p>
     * {@code dom_defaultbin} and {@code typdefault} are the two branches of the
     * default: the reader prefers the first and quotes the second.
     */
    private static PgDomain read(String defaultBin, String typDefault, String dataType, CoreSettings settings)
            throws Exception {
        settings.setIgnorePrivileges(true);

        ResultSet res = mock(ResultSet.class);
        when(res.getString("typtype")).thenReturn("d");
        when(res.getString("typname")).thenReturn(DOMAIN_NAME);
        when(res.getString("dom_basetypefmt")).thenReturn(dataType);
        when(res.getLong("dom_basetype")).thenReturn(SYSTEM_TYPE_OID);
        when(res.getString("dom_defaultbin")).thenReturn(defaultBin);
        when(res.getString("typdefault")).thenReturn(typDefault);
        when(res.getBoolean("dom_notnull")).thenReturn(true);
        // typcollation stays 0, which is the reader's "same as the base type"
        // case, and dom_connames stays null, which is "no constraints"

        var schema = new PgSchema(SCHEMA_NAME);
        new PgDatabase().addChild(schema);

        try (TestLoader loader = new TestLoader(settings)) {
            new PgTypesReader(loader).processResult(res, schema);
            loader.drain();
        }

        PgDomain domain = schema.getDomain(DOMAIN_NAME);
        assertTrue(domain != null, "the reader added no domain");
        return domain;
    }

    private static final long SYSTEM_TYPE_OID = 1700L;
    private static final long LAST_SYS_OID = 10000L;
    private static final String TYPE_STORAGE = "m";

    /**
     * {@code finishLoaders} is protected on {@code AbstractLoader}, so a subclass
     * reaches it without reflection, and the type cache is normally filled by a
     * query this test never runs, so the one lookup the reader makes is answered
     * here. Nothing queries - the reader is handed its row directly - so the
     * connector exists only to satisfy the constructor.
     */
    private static final class TestLoader extends PgJdbcLoader {

        private TestLoader(CoreSettings settings) {
            super(offlineConnector(), null, settings);
        }

        @Override
        public PgJdbcType getCachedTypeByOid(Long oid) {
            return new PgJdbcType(oid, DOMAIN_TYPE, 0L, 1231L, "pg_catalog", null,
                    LAST_SYS_OID, TYPE_STORAGE, 0L);
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
