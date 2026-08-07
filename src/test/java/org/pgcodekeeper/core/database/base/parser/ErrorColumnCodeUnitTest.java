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
package org.pgcodekeeper.core.database.base.parser;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.PrivilegesLexer;
import org.pgcodekeeper.core.database.pg.parser.generated.PrivilegesParser;

/**
 * The column of a syntax error must be counted in UTF-16 code units, like every
 * other coordinate the error carries.
 * <p>
 * ANTLR counts the position in line in code points, while {@code getStart()} and
 * {@code getStop()} come from a {@link CodeUnitToken} and the offset a deferred
 * sub parse is shifted by is a code unit position as well. Mixing the two put
 * the reported column one place to the left per astral character - an emoji, a
 * rare ideograph - earlier on the same line.
 * <p>
 * The tests below all put the whole statement on one line, where the offset of
 * a token from the start of the file and its position in its line are the very
 * same number. That makes {@code getStart()}, which was always right, the oracle
 * for the column beside it: the two must agree, and the size of the disagreement
 * is exactly the number of astral characters before the error.
 */
final class ErrorColumnCodeUnitTest {

    /** U+1F600, a single code point taking two UTF-16 code units */
    private static final String EMOJI = "😀";

    @Test
    void plainAsciiLineReportsTheSameColumnAsBefore() {
        AntlrError error = onlyErrorOf("SELECT 'ab' FROM ;");

        Assertions.assertAll(
                () -> Assertions.assertEquals(";", error.getText(), "offending token"),
                () -> Assertions.assertEquals(17, error.getStart(), "offset of the ';'"),
                () -> Assertions.assertEquals(17, error.getCharPositionInLine(), "column of the ';'"));
    }

    @Test
    void astralCharacterBeforeTheErrorDoesNotShiftTheColumn() {
        // 'SELECT ' is 7 chars, the literal '<emoji>' is 4 code units, ' FROM ' is 6
        AntlrError error = onlyErrorOf("SELECT '" + EMOJI + "' FROM ;");

        Assertions.assertAll(
                () -> Assertions.assertEquals(";", error.getText(), "offending token"),
                () -> Assertions.assertEquals(17, error.getStart(), "offset of the ';'"),
                () -> Assertions.assertEquals(17, error.getCharPositionInLine(),
                        "column of the ';' must be counted in code units, like its offset"));
    }

    @Test
    void twoAstralCharactersDoNotShiftTheColumnTwice() {
        AntlrError error = onlyErrorOf("SELECT '" + EMOJI + EMOJI + "' FROM ;");

        Assertions.assertAll(
                () -> Assertions.assertEquals(";", error.getText(), "offending token"),
                () -> Assertions.assertEquals(19, error.getStart(), "offset of the ';'"),
                () -> Assertions.assertEquals(19, error.getCharPositionInLine(),
                        "column of the ';' must not drift by the number of astral characters"));
    }

    @Test
    void astralCharacterInAQuotedIdentifierDoesNotShiftTheColumnEither() {
        // a quoted identifier goes through the very same lexer bookkeeping
        AntlrError error = onlyErrorOf("CREATE TABLE \"" + EMOJI + "\" (c text, d ??? );");

        Assertions.assertAll(
                () -> Assertions.assertEquals("???", error.getText(), "offending token"),
                () -> Assertions.assertEquals(error.getStart(), error.getCharPositionInLine(),
                        "column and offset of a token on the first line are the same number"));
    }

    /**
     * The other half of the same drift: an error the lexer reports carries no
     * offending token to read the code unit position off, so the position comes
     * from the lexer itself.
     * <p>
     * The SQL lexer cannot reach this - its last rule, {@code BAD: .;}, matches
     * any character, so every SQL error is a parser error over a real token. The
     * privileges lexer, which reads the ACL arrays of the catalog, has no such
     * rule and does report lexer errors.
     */
    @Test
    void aLexerErrorAfterAnAstralCharacterIsNotShiftedEither() {
        // a quoted identifier of the ACL syntax is backslash, quote, text,
        // backslash, quote: six code units here against five code points, so
        // the '%' that no rule matches sits at column 6 and not at 5
        String acl = "\\\"" + EMOJI + "\\\"%";

        List<Object> errors = new ArrayList<>();
        var lexer = new PrivilegesLexer(CharStreams.fromString(acl));
        var parser = new PrivilegesParser(new CommonTokenStream(lexer));
        ParserUtils.addErrorListener(lexer, parser, "jdbc privileges", errors, 0, 0, 0);
        lexer.getAllTokens();

        Assertions.assertEquals(1, errors.size(), () -> "expected one lexer error, got " + errors);
        AntlrError error = Assertions.assertInstanceOf(AntlrError.class, errors.get(0));
        Assertions.assertEquals(6, error.getCharPositionInLine(),
                "the column of a lexer error must be counted in code units too");
    }

    private static AntlrError onlyErrorOf(String sql) {
        List<Object> errors = new ArrayList<>();
        PgParserUtils.createSqlParser(sql, "one-liner.sql", errors).sql();

        Assertions.assertEquals(1, errors.size(), () -> "expected one syntax error in " + sql + ", got " + errors);
        return Assertions.assertInstanceOf(AntlrError.class, errors.get(0));
    }
}
