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

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link AntlrError} must accept any {@link Token} implementation. Lexers of
 * the small service grammars (IgnoreList, DependenciesList) emit plain
 * {@link CommonToken}s, and the old unconditional cast to
 * {@link CodeUnitToken} turned every syntax diagnostic in such files into a
 * {@link ClassCastException}.
 */
final class AntlrErrorTest {

    @Test
    void plainCommonTokenFallsBackToCharBasedOffsets() {
        CommonToken token = new CommonToken(1, "sample");
        token.setStartIndex(42);
        token.setStopIndex(44);

        AntlrError error = new AntlrError(token, "list.pgcodekeeperignore", 13, 11, "mismatched input");

        Assertions.assertEquals(42, error.getStart());
        Assertions.assertEquals(44, error.getStop());
        Assertions.assertEquals("sample", error.getText());
        Assertions.assertEquals(13, error.getLineNumber());
    }

    @Test
    void codeUnitTokenKeepsCodeUnitOffsets() {
        Token token = new CodeUnitToken(new Pair<>(null, null), 1, 0, 10, 12, 20, 22, 5);

        AntlrError error = new AntlrError(token, "file.sql", 2, 5, "msg");

        Assertions.assertEquals(20, error.getStart());
        Assertions.assertEquals(22, error.getStop());
    }

    @Test
    void nullTokenUsesSentinelOffsets() {
        AntlrError error = new AntlrError(null, "file.sql", 1, 0, "msg");

        Assertions.assertEquals(-1, error.getStart());
        Assertions.assertEquals(-1, error.getStop());
        Assertions.assertNull(error.getText());
    }
}
