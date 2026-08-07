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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.When_triggerContext;
import org.pgcodekeeper.core.database.pg.parser.statement.PgCreateTrigger;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.database.pg.schema.PgTrigger;
import org.pgcodekeeper.core.utils.Pair;

/**
 * A trigger WHEN condition reaches {@link PgCreateTrigger#parseWhen} from two
 * independent call sites: the project-side {@code CREATE TRIGGER} parser
 * ({@link PgCreateTrigger#parseObject()}, which already holds the context and
 * its stream as fields), and {@link PgTriggersReader#processResult}, which
 * re-parses a {@code pg_get_triggerdef()} result and bundles the resulting
 * context and stream into a {@link Pair} because {@code submitAntlrTask}
 * extracts the context in one call and consumes it in another, on the
 * deferred task queue.
 * <p>
 * These tests drive both shapes directly through the shared parsing method
 * and compare the resulting triggers, proving the paths are genuinely one
 * call site under the hood rather than two call sites that merely look
 * alike.
 */
class PgTriggersReaderWhenClauseTest {

    @Test
    void projectFileAndCatalogReparseAgreeOnARecasedCondition() {
        PgTrigger fromProjectFile = parseAsProjectFile(
                """
                CREATE TRIGGER status_change_trg
                    AFTER UPDATE ON public.orders
                    FOR EACH ROW
                    WHEN (old.status is distinct from new.status and new.status is not null)
                    EXECUTE FUNCTION public.log_status_change();
                """);

        // Exactly the shape PgTriggersReader.processResult() re-parses: a
        // complete CREATE TRIGGER statement, as pg_get_triggerdef() would
        // return it, cased and spaced differently than the project file
        // above. "is" keeps the same case in both snippets throughout - IS
        // (404) sits outside the folded reserved-word range, so unlike
        // DISTINCT/FROM/AND/NOT it is not case-normalized and must be held
        // constant here to isolate the fold-range comparison from that
        // unrelated, pre-existing limit. Likewise "old"/"new" are unreserved
        // keywords usable as identifiers (token types 185/168), also outside
        // the fold range, so their case is held constant too.
        PgTrigger fromCatalogReparse = parseAsCatalogReparse(
                """
                CREATE TRIGGER status_change_trg
                    AFTER UPDATE ON public.orders
                    FOR EACH ROW
                    WHEN (old.status is DISTINCT FROM new.status AND new.status is NOT NULL)
                    EXECUTE FUNCTION public.log_status_change();
                """);

        assertConverge(fromProjectFile, fromCatalogReparse);
    }

    @Test
    void aGenuinelyDifferentConditionStillComparesAsChanged() {
        PgTrigger original = parseAsProjectFile(
                """
                CREATE TRIGGER status_change_trg
                    AFTER UPDATE ON public.orders
                    FOR EACH ROW
                    WHEN (new.status = 'active')
                    EXECUTE FUNCTION public.log_status_change();
                """);
        PgTrigger changed = parseAsProjectFile(
                """
                CREATE TRIGGER status_change_trg
                    AFTER UPDATE ON public.orders
                    FOR EACH ROW
                    WHEN (new.status = 'inactive')
                    EXECUTE FUNCTION public.log_status_change();
                """);

        assertFalse(original.compare(changed),
                "a genuinely different WHEN condition must still compare as changed");
        assertFalse(changed.compare(original),
                "and the other way round too");
    }

    private static void assertConverge(PgTrigger a, PgTrigger b) {
        assertTrue(a.compare(b), "expected the two parses to compare as unchanged");
        assertTrue(b.compare(a), "compare must be symmetric");
        assertEquals(a.hashCode(), b.hashCode(), "unchanged triggers must hash the same");
    }

    /**
     * Mirrors {@link PgCreateTrigger#parseObject()}: the context and its
     * stream are both available directly, no intermediate {@link Pair} is
     * needed to carry them across a deferred task boundary.
     */
    private static PgTrigger parseAsProjectFile(String sql) {
        List<Object> errors = new ArrayList<>();
        SQLParser parser = PgParserUtils.createSqlParser(sql, "trigger when project-file test", errors);
        When_triggerContext ctx = parser.sql().statement(0).schema_statement()
                .schema_create().create_trigger_statement().when_trigger();
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        var stream = (CommonTokenStream) parser.getTokenStream();
        var db = new PgDatabase();
        var trigger = new PgTrigger("status_change_trg");
        PgCreateTrigger.parseWhen(ctx, trigger, db, "test", stream);
        return trigger;
    }

    /**
     * Mirrors {@link PgTriggersReader}'s {@code submitAntlrTask} call: the
     * context-extraction function and the processing callback run apart, so
     * the context and the stream that produced it travel together in a
     * {@link Pair}.
     */
    private static PgTrigger parseAsCatalogReparse(String sql) {
        List<Object> errors = new ArrayList<>();
        SQLParser parser = PgParserUtils.createSqlParser(sql, "trigger when catalog-reparse test", errors);
        Pair<When_triggerContext, CommonTokenStream> pair = new Pair<>(
                parser.sql().statement(0).schema_statement()
                        .schema_create().create_trigger_statement().when_trigger(),
                (CommonTokenStream) parser.getTokenStream());
        assertTrue(errors.isEmpty(), () -> "parse must not report errors, got: " + errors);

        var db = new PgDatabase();
        var trigger = new PgTrigger("status_change_trg");
        PgCreateTrigger.parseWhen(pair.getFirst(), trigger, db, "test", pair.getSecond());
        return trigger;
    }
}
