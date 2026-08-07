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
package org.pgcodekeeper.core.database.pg.parser.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.antlr.v4.runtime.WritableToken;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.SconstContext;

class PgParserAbstractUnquoteTest {

    @Test
    void singleDollarTokenIsReturnedWithoutAJoiningCopy() {
        SconstContext context = parse("$body$SELECT 1$body$");
        var token = context.Text_between_Dollar(0).getSymbol();
        String exactBody = new String("SELECT 1");
        ((WritableToken) token).setText(exactBody);

        var result = PgParserAbstract.unquoteQuotedString(context);

        assertSame(exactBody, result.getFirst());
        assertSame(token, result.getSecond());
    }

    @Test
    void multipleDollarTokensUseOneContiguousInputInterval() {
        String body = "left$middle$right";
        SconstContext context = parse("$$" + body + "$$");
        assertTrue(context.Text_between_Dollar().size() > 1);

        var result = PgParserAbstract.unquoteQuotedString(context);

        assertEquals(body, result.getFirst());
        assertSame(context.Text_between_Dollar(0).getSymbol(), result.getSecond());
    }

    @Test
    void emptyDollarBodyUsesClosingDelimiterAsItsSourceToken() {
        SconstContext context = parse("$empty$$empty$");

        var result = PgParserAbstract.unquoteQuotedString(context);

        assertEquals("", result.getFirst());
        assertSame(context.EndDollarStringConstant().getSymbol(), result.getSecond());
    }

    private static SconstContext parse(String input) {
        var errors = new ArrayList<>();
        var parser = PgParserUtils.createSqlParser(input, "string constant", errors);
        SconstContext context = parser.sconst();
        assertTrue(errors.isEmpty(), errors::toString);
        return context;
    }
}
