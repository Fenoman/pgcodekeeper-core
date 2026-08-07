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
package org.pgcodekeeper.core.model.difftree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.model.difftree.SqlMarkup.Marked;

/**
 * Which parts of a rendering a comparison has something to say about - the
 * columns a rule names and the values the settings overlook, see
 * {@link SqlMarkup}.
 * <p>
 * Every fixture here is text a generator of this project really writes; the
 * shapes are pinned against live renderings by {@link
 * org.pgcodekeeper.core.it.difftree.ShownColumnsAreMarkedTest} and {@link
 * org.pgcodekeeper.core.it.difftree.IgnoredValuesAreMarkedTest}, and this
 * decides what is made of them. Two things are being proved and they pull in
 * opposite directions: nothing a project file would lose with the column may be
 * left unmarked, and nothing that merely mentions a column or a word of the
 * dialect may be marked as if it were about it.
 */
class SqlMarkupTest {

    private static final String HIDDEN = "s_create_date";
    private static final String KEPT = "s_owner";

    private static final Map<String, ColumnMark> AUDIT_MARKS = Map.of(
            HIDDEN, ColumnMark.HIDDEN,
            "s_creator", ColumnMark.HIDDEN,
            KEPT, ColumnMark.PINNED);

    /** Both settings on, for a table whose columns are all on both sides. */
    private static final IgnoredValues VALUES = new IgnoredValues(true,
            Set.of("id", "title", "payload"), Set.of("title", "payload"), Set.of());

    /** One column whose whole difference is a collation no script can express. */
    private static final IgnoredValues COLLATIONS =
            new IgnoredValues(false, Set.of(), Set.of(), Set.of("title"));

    private static final String CREATE = """
            CREATE TABLE chk.sd_close_periods (
            \tid bigint NOT NULL,
            \tc_host_name text NOT NULL,
            \ts_create_date timestamp without time zone,
            \ts_creator text,
            \ts_owner text
            );""";

    /**
     * The declaration of a marked column, and only of a marked column: the
     * columns no rule names are the ones a reader came to read.
     */
    @Test
    void theDeclarationOfAMarkedColumnIsMarked() {
        assertEquals(List.of(
                "H \ts_create_date timestamp without time zone,",
                "H \ts_creator text,",
                "P \ts_owner text"),
                marked(CREATE, AUDIT_MARKS));
    }

    /**
     * Everything a project file loses together with a hidden column: its
     * comment, its statistics target, its storage mode, a privilege granted on
     * it alone and the {@code NOT NULL} it holds under a name. One of those left
     * unmarked would be a column half accounted for.
     */
    @Test
    void everyStatementAboutAMarkedColumnIsMarked() {
        String sql = """
                ALTER TABLE chk.sd_close_periods ALTER COLUMN s_create_date SET STORAGE EXTERNAL;

                ALTER TABLE chk.sd_close_periods ALTER COLUMN s_owner SET (n_distinct=5);

                ALTER TABLE chk.sd_close_periods
                \tADD CONSTRAINT nn_s_create_date NOT NULL s_create_date NOT VALID;

                GRANT SELECT(s_create_date) ON TABLE chk.sd_close_periods TO reader;

                ALTER TABLE ONLY chk.sd_close_periods ALTER COLUMN s_create_date SET STATISTICS 100;

                COMMENT ON COLUMN chk.sd_close_periods.s_create_date IS 'when the row appeared';""";

        assertEquals(List.of(
                "H ALTER TABLE chk.sd_close_periods ALTER COLUMN s_create_date SET STORAGE EXTERNAL;",
                "P ALTER TABLE chk.sd_close_periods ALTER COLUMN s_owner SET (n_distinct=5);",
                "H ALTER TABLE chk.sd_close_periods\n\tADD CONSTRAINT nn_s_create_date NOT NULL s_create_date NOT VALID;",
                "H GRANT SELECT(s_create_date) ON TABLE chk.sd_close_periods TO reader;",
                "H ALTER TABLE ONLY chk.sd_close_periods ALTER COLUMN s_create_date SET STATISTICS 100;",
                "H COMMENT ON COLUMN chk.sd_close_periods.s_create_date IS 'when the row appeared';"),
                marked(sql, AUDIT_MARKS));
    }

    /**
     * A statement that brings another object into being is left alone, even
     * where it names a marked column. Such an object is the very reason the
     * column is kept rather than hidden, and colouring a whole index or a whole
     * key would say that the index is somehow provisional, which it is not.
     */
    @Test
    void aStatementThatDefinesAnotherObjectIsNotMarked() {
        String sql = """
                CREATE INDEX sd_close_periods_owner_idx ON chk.sd_close_periods (s_owner);

                ALTER TABLE chk.sd_close_periods
                \tADD CONSTRAINT sd_close_periods_owner_key UNIQUE (s_owner);

                ALTER TABLE chk.sd_close_periods
                \tADD CONSTRAINT sd_close_periods_owner_check CHECK ((s_owner IS NOT NULL));

                CREATE POLICY p ON chk.sd_close_periods USING ((s_owner = CURRENT_USER));

                CREATE TRIGGER t BEFORE UPDATE OF s_owner ON chk.sd_close_periods
                \tFOR EACH ROW EXECUTE PROCEDURE chk.f();""";

        assertEquals(List.of(), marked(sql, AUDIT_MARKS));
    }

    /**
     * The column default a Microsoft SQL table keeps as a constraint of its own
     * is a part of the column and goes with it, unlike the key beside it in the
     * same shape of statement.
     */
    @Test
    void aColumnDefaultWrittenAsAConstraintGoesWithItsColumn() {
        String sql = """
                ALTER TABLE [dbo].[doc] ADD CONSTRAINT [DF_doc_owner] DEFAULT (0) FOR [s_owner]
                GO
                ALTER TABLE [dbo].[doc] ADD CONSTRAINT [PK_doc] PRIMARY KEY CLUSTERED ([s_owner])
                GO""";

        assertEquals(List.of("P ALTER TABLE [dbo].[doc] ADD CONSTRAINT [DF_doc_owner] DEFAULT (0) FOR [s_owner]"),
                marked(sql, AUDIT_MARKS));
    }

    /** A name written quoted is the same name, in every dialect that quotes. */
    @Test
    void aQuotedNameIsTheName() {
        Map<String, ColumnMark> marks = Map.of("S_Quoted Col", ColumnMark.HIDDEN, "s_creator", ColumnMark.HIDDEN);

        assertEquals(List.of("H \t\"S_Quoted Col\" text,"), marked("""
                CREATE TABLE chk.t (
                \tid bigint,
                \t"S_Quoted Col" text,
                \tc_host_name text
                );""", marks));

        assertEquals(List.of("H \t[s_creator] [nvarchar](50) NULL"), marked("""
                CREATE TABLE [dbo].[doc](
                \t[id] [bigint] NOT NULL,
                \t[s_creator] [nvarchar](50) NULL
                ) ON [PRIMARY]""", marks));

        assertEquals(List.of("H \t`s_creator` String COMMENT 'made by'"), marked("""
                CREATE TABLE default.doc
                (
                \t`id` Int64,
                \t`s_creator` String COMMENT 'made by'
                )
                ENGINE = Log;""", marks));
    }

    /**
     * A name is matched whole. {@code s_create_date} does not occur in
     * {@code s_create_date_2} any more than it occurs in {@code x_s_create_date},
     * and a table that holds both must not have the wrong line coloured.
     */
    @Test
    void aLongerNameIsAnotherName() {
        assertEquals(List.of("H \ts_create_date timestamp,"), marked("""
                CREATE TABLE chk.t (
                \ts_create_date timestamp,
                \ts_create_date_2 timestamp,
                \tx_s_create_date timestamp
                );""", AUDIT_MARKS));
    }

    /**
     * A name inside a literal or a comment is text and not a name. A comment
     * that happens to speak about a column is not a statement about it, and
     * marking it would follow the wrong thing entirely.
     */
    @Test
    void aNameInsideALiteralOrACommentIsNotAName() {
        assertEquals(List.of(), marked("""
                COMMENT ON TABLE chk.t IS 'holds s_create_date and s_creator';

                -- s_create_date is written here and nowhere else
                ALTER TABLE chk.t OWNER TO postgres;

                /* s_creator */
                ALTER TABLE chk.t ENABLE ROW LEVEL SECURITY;""", AUDIT_MARKS));
    }

    /** A body written in a dollar-quoted literal is a literal and is not read. */
    @Test
    void aBodyInALiteralIsNotRead() {
        assertEquals(List.of(), marked("""
                CREATE FUNCTION chk.f() RETURNS trigger
                \tLANGUAGE plpgsql
                \tAS $$
                BEGIN
                \tNEW.s_create_date := now();

                \tRETURN NEW;
                END;
                $$;""", AUDIT_MARKS));
    }

    /**
     * A blank line inside a body separates nothing: a statement ends where its
     * parentheses are closed, and a rendering may put air anywhere.
     */
    @Test
    void aBlankLineInsideABodySeparatesNothing() {
        assertEquals(List.of("H \ts_create_date timestamp"), marked("""
                CREATE TABLE chk.t (
                \tid bigint,

                \ts_create_date timestamp
                );""", AUDIT_MARKS));
    }

    /**
     * A batch separator ends a statement where no semicolon does, which is the
     * only thing holding the statements of a Microsoft SQL rendering apart. Its
     * {@code CREATE TABLE} arrives behind two settings and a blank line arrives
     * nowhere, so without this the whole batch would be one statement beginning
     * with {@code SET} and no body would be found in it at all.
     */
    @Test
    void aBatchSeparatorEndsAStatement() {
        assertEquals(List.of("H \t[s_creator] [nvarchar](50) NULL"), marked("""
                SET QUOTED_IDENTIFIER ON
                GO
                SET ANSI_NULLS ON
                GO
                CREATE TABLE [dbo].[doc](
                \t[id] [bigint] NOT NULL,
                \t[s_creator] [nvarchar](50) NULL
                ) ON [PRIMARY]
                GO""", AUDIT_MARKS));
    }

    /**
     * A statement that names a kept column and a hidden one is a statement that
     * stays where it is, because the kept column keeps it, so it is marked as
     * the calmer of the two.
     */
    @Test
    void aKeptColumnWinsTheLineItShares() {
        assertEquals(List.of("P GRANT SELECT(s_create_date), UPDATE(s_owner) ON TABLE chk.t TO reader;"),
                marked("GRANT SELECT(s_create_date), UPDATE(s_owner) ON TABLE chk.t TO reader;", AUDIT_MARKS));
    }

    /** Whole lines, in order, never overlapping, delimiters left alone. */
    @Test
    void theMarkedStretchesAreWholeLinesInOrderAndDisjoint() {
        List<Marked> ranges = SqlMarkup.rangesIn(CREATE, AUDIT_MARKS, IgnoredValues.NONE);

        assertEquals(3, ranges.size(), "one per marked column of the body");
        int after = 0;
        for (Marked range : ranges) {
            assertTrue(range.offset() >= after, "the stretches come in the order of the text: " + range);
            assertTrue(range.offset() == 0 || CREATE.charAt(range.offset() - 1) == '\n',
                    "a stretch begins a line: " + range);
            String text = CREATE.substring(range.offset(), range.offset() + range.length());
            assertEquals(-1, text.indexOf('\n'), "and ends with one: " + text);
            after = range.offset() + range.length();
        }
    }

    /**
     * An ignore list that names no column asks for nothing and is answered with
     * nothing, which is the answer for very nearly every project there is.
     */
    @Test
    void nothingToLookForIsNothingToDo() {
        assertEquals(List.of(), SqlMarkup.rangesIn(CREATE, Map.of(), IgnoredValues.NONE));
        assertEquals(List.of(), SqlMarkup.rangesIn(CREATE, null, null));
        assertEquals(List.of(), SqlMarkup.rangesIn("", AUDIT_MARKS, VALUES));
        assertEquals(List.of(), SqlMarkup.rangesIn(null, AUDIT_MARKS, VALUES));
        assertEquals(List.of(), SqlMarkup.rangesIn(CREATE, Map.of(HIDDEN, ColumnMark.MANAGED), IgnoredValues.NONE));
    }

    // ------------------------------------------------- the values overlooked

    /**
     * The cache of a sequence is one clause among several that are compared as
     * usual, so the line stating it is marked and the {@code CREATE SEQUENCE}
     * around it is not: everything else in that statement is a difference a
     * migration would carry.
     */
    @Test
    void onlyTheCacheClauseOfASequenceIsMarked() {
        assertEquals(List.of("V \tCACHE 10;"), marked("""
                CREATE SEQUENCE chk.s_id
                \tSTART WITH 1
                \tINCREMENT BY 1
                \tNO MAXVALUE
                \tNO MINVALUE
                \tCACHE 10;""", Map.of(), VALUES));
    }

    /**
     * The cache of the identity sequence of a column stands inside the clause
     * that declares the identity, and is marked exactly as a standalone one -
     * the same setting decides both, and the same line states it.
     */
    @Test
    void theCacheOfAnIdentityIsMarkedToo() {
        assertEquals(List.of("V \tCACHE 7"), marked("""
                ALTER TABLE chk.doc ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
                \tSEQUENCE NAME chk.doc_id_seq
                \tSTART WITH 1
                \tINCREMENT BY 1
                \tNO MAXVALUE
                \tNO MINVALUE
                \tCACHE 7
                );""", Map.of(), VALUES));
    }

    /**
     * Microsoft SQL states the absence of caching in words, and the clause is
     * still the clause: the line carries whether it holds a number or not.
     */
    @Test
    void aCacheWithoutANumberIsStillACache() {
        assertEquals(List.of("V \tNO CACHE"), marked("""
                CREATE SEQUENCE [dbo].[s_id]
                \tAS [bigint]
                \tSTART WITH 1
                \tNO CACHE
                GO""", Map.of(), VALUES));
    }

    /**
     * A statistics target is a statement of its own and is marked whole, header
     * and all. Marking the second line alone would leave an {@code ALTER TABLE}
     * standing unmarked above it, reading like a change of its own.
     */
    @Test
    void theWholeStatementOfAStatisticsTargetIsMarked() {
        assertEquals(List.of(
                "V ALTER TABLE ONLY chk.doc\n\tALTER COLUMN title SET STATISTICS 100;",
                "V ALTER TABLE ONLY chk.doc ALTER COLUMN payload SET STATISTICS 500;"),
                marked("""
                        ALTER TABLE ONLY chk.doc
                        \tALTER COLUMN title SET STATISTICS 100;

                        ALTER TABLE ONLY chk.doc ALTER COLUMN payload SET STATISTICS 500;""", Map.of(), VALUES));
    }

    /**
     * A value of a column the settings do not name is a value a migration will
     * carry, and a mark on it would be a lie of exactly the kind the marking
     * exists to prevent.
     */
    @Test
    void aValueOfAnotherColumnIsNotMarked() {
        IgnoredValues onlyTitle = new IgnoredValues(false, Set.of("title"), Set.of("title"), Set.of());

        assertEquals(List.of("V ALTER TABLE ONLY chk.doc ALTER COLUMN title SET STATISTICS 100;"), marked("""
                ALTER TABLE ONLY chk.doc ALTER COLUMN title SET STATISTICS 100;

                ALTER TABLE ONLY chk.doc ALTER COLUMN payload SET STATISTICS 500;""", Map.of(), onlyTitle));

        assertEquals(List.of("V \tCACHE 7"), marked("""
                ALTER TABLE chk.doc ALTER COLUMN title ADD GENERATED ALWAYS AS IDENTITY (
                \tSEQUENCE NAME chk.doc_title_seq
                \tCACHE 7
                );

                ALTER TABLE chk.doc ALTER COLUMN payload ADD GENERATED ALWAYS AS IDENTITY (
                \tSEQUENCE NAME chk.doc_payload_seq
                \tCACHE 9
                );""", Map.of(), onlyTitle));
    }

    /**
     * The cache of a sequence that is an object of its own and the cache of an
     * identity are told apart, because they are kept apart: a column named by
     * neither setting leaves the identity alone, and a comparison shown a table
     * has no standalone sequence in it to speak about.
     */
    @Test
    void aStandaloneCacheAndAnIdentityCacheAreTwoAnswers() {
        String identity = """
                ALTER TABLE chk.doc ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
                \tSEQUENCE NAME chk.doc_id_seq
                \tCACHE 7
                );""";
        String standalone = """
                CREATE SEQUENCE chk.s_id
                \tSTART WITH 1
                \tCACHE 10;""";

        IgnoredValues sequenceOnly = new IgnoredValues(true, Set.of(), Set.of(), Set.of());
        assertEquals(List.of(), marked(identity, Map.of(), sequenceOnly));
        assertEquals(List.of("V \tCACHE 10;"), marked(standalone, Map.of(), sequenceOnly));

        IgnoredValues identityOnly = new IgnoredValues(false, Set.of("id"), Set.of(), Set.of());
        assertEquals(List.of("V \tCACHE 7"), marked(identity, Map.of(), identityOnly));
        assertEquals(List.of(), marked(standalone, Map.of(), identityOnly));
    }

    /**
     * A word of the dialect written in a literal or a comment is text. A comment
     * that speaks about caching and a table that holds a column called
     * {@code cache} are not statements about a value the settings overlook.
     */
    @Test
    void aWordInsideALiteralOrAnotherStatementIsNotAClause() {
        assertEquals(List.of(), marked("""
                COMMENT ON SEQUENCE chk.s_id IS 'CACHE 10 is deliberate, and SET STATISTICS 500 below';

                -- SEQUENCE CACHE 10
                ALTER TABLE chk.doc OWNER TO postgres;

                CREATE TABLE chk.sequence_settings (
                \tcache bigint,
                \tstatistics bigint
                );""", Map.of(), VALUES));
    }

    /**
     * The extended statistics of a schema are an object of their own and share
     * nothing with the target of a column but the word, so nothing about them is
     * marked.
     */
    @Test
    void anExtendedStatisticsObjectIsNotAStatisticsTarget() {
        assertEquals(List.of(), marked("""
                CREATE STATISTICS chk.doc_stat ON title, payload FROM chk.doc;

                ALTER STATISTICS chk.doc_stat SET STATISTICS 200;""", Map.of(), VALUES));
    }

    /**
     * A column a rule names and something needs wins a line it shares with an
     * overlooked value, because it is the answer that claims the least: what it
     * says of the line - that the comparison manages it - is the safe half of
     * the truth, and the other mark would tell a reader to stop looking.
     */
    @Test
    void aKeptColumnWinsTheLineItSharesWithAValue() {
        assertEquals(List.of("P ALTER TABLE ONLY chk.t ALTER COLUMN s_owner SET STATISTICS 100;"),
                marked("ALTER TABLE ONLY chk.t ALTER COLUMN s_owner SET STATISTICS 100;",
                        AUDIT_MARKS, new IgnoredValues(false, Set.of(), Set.of(KEPT), Set.of())));
    }

    // ------------------------------------- the collation nothing can migrate

    /**
     * A collation has no clause of its own - it stands in the middle of the line
     * that declares its column - and the declaration is what carries the mark.
     * The answer is only ever given for a column whose whole difference is that
     * collation, so the line is honest: nothing on it is migrating.
     */
    @Test
    void theDeclarationOfAColumnWithAnUnmigratableCollationIsMarked() {
        assertEquals(List.of("U \ttitle text COLLATE pg_catalog.\"ru_RU\" NOT NULL,"), marked("""
                CREATE TABLE chk.doc (
                \tid bigint NOT NULL,
                \ttitle text COLLATE pg_catalog."ru_RU" NOT NULL,
                \tpayload text
                );""", Map.of(), COLLATIONS));
    }

    /**
     * A column shown on its own is rendered as the {@code ADD COLUMN} that would
     * create it, and that statement declares the column and declares nothing
     * else, so its {@code ALTER TABLE} header belongs to the mark as much as the
     * header of a statistics target belongs to its own.
     */
    @Test
    void theAddColumnOfAStandaloneColumnIsMarkedWhole() {
        assertEquals(List.of("U ALTER TABLE chk.doc\n\tADD COLUMN title text COLLATE pg_catalog.\"ru_RU\";"),
                marked("""
                        ALTER TABLE chk.doc
                        \tADD COLUMN title text COLLATE pg_catalog."ru_RU";""", Map.of(), COLLATIONS));

        assertEquals(List.of("U ALTER TABLE chk.doc\n\tADD COLUMN IF NOT EXISTS title text;"), marked("""
                ALTER TABLE chk.doc
                \tADD COLUMN IF NOT EXISTS title text;""", Map.of(), COLLATIONS),
                "the guard the settings may put in front of the name is not the name");

        assertEquals(List.of(), marked("""
                ALTER TABLE chk.doc
                \tADD COLUMN IF NOT EXISTS payload text;""", Map.of(), COLLATIONS),
                "and the guard is not read as a name of its own either");
    }

    /**
     * Everything else written about that column is compared and migrated as
     * usual, so the wide name match the column rules use is deliberately not
     * used here: a comment, a privilege or a statistics target of the same
     * column is a difference the migration carries, and marking it would tell a
     * reader to stop looking at a line that is about to be written.
     */
    @Test
    void nothingElseAboutThatColumnIsMarked() {
        assertEquals(List.of(), marked("""
                COMMENT ON COLUMN chk.doc.title IS 'the title';

                ALTER TABLE ONLY chk.doc ALTER COLUMN title SET STATISTICS 100;

                ALTER TABLE chk.doc ALTER COLUMN title SET STORAGE EXTERNAL;

                GRANT SELECT(title) ON TABLE chk.doc TO reader;

                CREATE INDEX doc_title ON chk.doc USING btree (title);

                ALTER TABLE chk.doc DROP COLUMN title;""", Map.of(), COLLATIONS));
    }

    /**
     * A column that leaves the project takes its whole line with it, collation
     * included, so that is the larger fact and the one worth naming. A column
     * something needs is calmer still and wins outright, exactly as it does
     * against an overlooked value.
     */
    @Test
    void aColumnMarkWinsTheDeclarationItShares() {
        IgnoredValues both = new IgnoredValues(false, Set.of(), Set.of(), Set.of(HIDDEN, KEPT));

        assertEquals(List.of(
                "H \ts_create_date timestamp without time zone,",
                "H \ts_creator text,",
                "P \ts_owner text"),
                marked(CREATE, AUDIT_MARKS, both));
    }

    /**
     * The two reasons a line may be passed over keep their own mark on one
     * rendering: they are read the same way by anyone who only wants to know
     * whether the line takes part, and differently by anyone who wants to know
     * whether it ever will.
     */
    @Test
    void theTwoReasonsAreMarkedApartOnOneRendering() {
        assertEquals(List.of(
                "U \ttitle text COLLATE pg_catalog.\"ru_RU\",",
                "V ALTER TABLE ONLY chk.doc ALTER COLUMN payload SET STATISTICS 500;"), marked("""
                CREATE TABLE chk.doc (
                \ttitle text COLLATE pg_catalog."ru_RU",
                \tpayload text
                );

                ALTER TABLE ONLY chk.doc ALTER COLUMN payload SET STATISTICS 500;""", Map.of(),
                new IgnoredValues(false, Set.of(), Set.of("payload"), Set.of("title"))));
    }

    /**
     * The marked stretches of a rendering, each as the letter of its mark and
     * the text it covers.
     */
    private static List<String> marked(String sql, Map<String, ColumnMark> marks) {
        return marked(sql, marks, IgnoredValues.NONE);
    }

    private static List<String> marked(String sql, Map<String, ColumnMark> marks, IgnoredValues values) {
        List<String> lines = new ArrayList<>();
        for (Marked range : SqlMarkup.rangesIn(sql, marks, values)) {
            lines.add(letterOf(range.mark()) + " "
                    + sql.substring(range.offset(), range.offset() + range.length()));
        }
        return lines;
    }

    /** One letter per mark, so that a whole rendering fits in an assertion. */
    private static char letterOf(SqlMark mark) {
        return switch (mark) {
            case COLUMN_LEAVING -> 'H';
            case COLUMN_KEPT -> 'P';
            case VALUE_IGNORED -> 'V';
            case VALUE_UNMIGRATABLE -> 'U';
        };
    }
}
