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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ParserUtilsTest {

    @Test
    void utf8LengthSupportsArbitraryCharSequenceWithoutMaterializingAString() {
        CharSequence input = new StringBuilder("a😀€\uD800");

        assertEquals(input.toString().getBytes(StandardCharsets.UTF_8).length,
                ParserUtils.getUtf8Length(input));
    }

    @ParameterizedTest
    @MethodSource("utf8Inputs")
    void utf8LengthMatchesJdkEncoding(String input) {
        assertEquals(input.getBytes(StandardCharsets.UTF_8).length,
                ParserUtils.getUtf8Length(input));
    }

    private static Stream<String> utf8Inputs() {
        return Stream.of(
                "",
                "ascii",
                "é",
                "Ж",
                "€",
                "😀",
                "a😀b",
                "\uD800",
                "\uDC00",
                "\uD800a",
                "\uD800\uD800\uDC00",
                "\uDC00\uD800\uDC00");
    }
}
