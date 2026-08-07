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
package org.pgcodekeeper.core.dependencieslist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.database.base.parser.ParserUtils;

/**
 * A {@code .pgcodekeeperdependencies} file that cannot be read must not be
 * mistaken for one that is not there.
 * <p>
 * The file adds edges to the dependency graph, so a silently empty read does
 * not fail anything - it reorders the migration script, or drops statements
 * from it, with a green run and no message anywhere. That is the same shape the
 * ignore lists were fixed for ({@code IgnoreParser}, which fails fast with
 * {@code IgnoreListParseException} on a syntax error and on an analysis
 * failure alike), and it is fixed the same way here.
 * <p>
 * The distinction the fix must keep is between <i>absent</i> and <i>broken</i>:
 * a project with no dependencies file declares no extra edges and that is
 * normal, while a project with a broken one declared edges nobody applied.
 */
class DependenciesReaderFailFastTest {

    /**
     * A file whose middle line names a type that does not exist. The two lines
     * around it are valid and load today, which is what makes the old answer
     * worse than partial: {@code getObjectType} throws, the whole read is
     * abandoned and every valid line goes with it.
     */
    private static final String BROKEN_TYPE = """
            TABLE public.emp_view -> TABLE public.emp;
            TABEL public.a -> TABLE public.b;
            TABLE public.c -> TABLE public.d;
            """;

    /**
     * The dangerous half, and the reason the syntax errors have to be collected
     * rather than logged: the middle line writes the arrow as {@code =>}, which
     * this grammar has no token for at all. The lexer reports it, ANTLR
     * recovery swallows the two stray characters and the parse goes on to
     * produce a full list of three dependencies - so nothing downstream has any
     * way to notice that a line was read as something other than what it says.
     * <p>
     * Deliberately not a typo that makes the analysis throw: that is a
     * different mechanism, held by
     * {@link #anUnknownObjectTypeFailsInsteadOfReturningNoDependencies}, and a
     * fixture that triggered both would let either one carry the test.
     * {@link #aRecoveredSyntaxErrorWouldOtherwisePassUnnoticed} pins that
     * separation.
     */
    private static final String BROKEN_SYNTAX = """
            TABLE public.emp_view -> TABLE public.emp;
            TABLE public.a => TABLE public.b;
            TABLE public.c -> TABLE public.d;
            """;

    @Test
    void anUnknownObjectTypeFailsInsteadOfReturningNoDependencies(@TempDir Path dir) throws Exception {
        Path deps = write(dir, BROKEN_TYPE);

        var ex = assertThrows(DependenciesListParseException.class,
                () -> DependenciesReader.getDependencies(deps));

        assertEquals(deps.toString(), ex.getListPath(), "the exception must name the file it came from");
        assertTrue(ex.getMessage().contains(deps.toString()),
                () -> "and so must its message, got: " + ex.getMessage());
    }

    @Test
    void aRecoveredSyntaxErrorFailsInsteadOfBeingReadAsSomethingElse(@TempDir Path dir) throws Exception {
        Path deps = write(dir, BROKEN_SYNTAX);

        var ex = assertThrows(DependenciesListParseException.class,
                () -> DependenciesReader.getDependencies(deps));

        assertEquals(deps.toString(), ex.getListPath(), "the exception must name the file it came from");
        assertTrue(ex.getMessage().contains(deps.toString()),
                () -> "and so must its message, got: " + ex.getMessage());
    }

    /**
     * Holds the property the fixture above is chosen for, so the two halves of
     * this class cannot start covering for each other. The syntax fixture must
     * reach the analysis without throwing - otherwise
     * {@link #aRecoveredSyntaxErrorFailsInsteadOfBeingReadAsSomethingElse}
     * would pass on the exception path and the collected syntax errors would go
     * untested. Measured as the mutation that dropped the error list: with the
     * old fixture nothing went red.
     */
    @Test
    void aRecoveredSyntaxErrorWouldOtherwisePassUnnoticed(@TempDir Path dir) throws Exception {
        Path deps = write(dir, BROKEN_SYNTAX);
        List<Object> ignoredErrors = new ArrayList<>();

        var parser = ParserUtils.createDependenciesListParser(deps, ignoredErrors);
        var recovered = parser.compileUnit().deps_definition();

        assertFalse(ignoredErrors.isEmpty(), "the fixture is supposed to be a syntax error");
        assertEquals(3, recovered.size(),
                "and ANTLR recovery is supposed to hand back a full list anyway - that is what makes it silent");
    }

    /**
     * The half that must not change. An absent file is the normal state of a
     * project that declares no additional dependencies, and turning that into a
     * failure would break every project that has none.
     */
    @Test
    void anAbsentFileIsStillNoDependencies(@TempDir Path dir) {
        assertTrue(DependenciesReader.getDependencies(dir.resolve("nothing.pgcodekeeperdependencies")).isEmpty(),
                "a project without the file declares no extra edges, which is not an error");
    }

    /**
     * And the readable file still reads. Without this the fix would be
     * indistinguishable from one that fails on every file.
     */
    @Test
    void aReadableFileStillLoadsEveryDependency() {
        Path path = TestUtils.getFilePath("deps.pgcodekeeperdependencies", DependenciesReaderTest.class);

        assertEquals(7, DependenciesReader.getDependencies(path).size(),
                "the readable fixture must still yield all of its dependencies");
    }

    private static Path write(Path dir, String content) throws Exception {
        return Files.writeString(dir.resolve("broken.pgcodekeeperdependencies"), content);
    }
}
