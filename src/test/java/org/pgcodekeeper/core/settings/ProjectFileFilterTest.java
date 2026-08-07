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
package org.pgcodekeeper.core.settings;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFileFilterTest {

    @Test
    void allowAllIncludesEveryPath() {
        assertAll(
                () -> assertTrue(ProjectFileFilter.ALLOW_ALL.isAllowed("SCHEMA/dbo/TABLE/x.sql")),
                () -> assertTrue(ProjectFileFilter.ALLOW_ALL.isAllowed("OVERRIDES/dbo/x.sql")),
                () -> assertTrue(ProjectFileFilter.ALLOW_ALL.isAllowed("anything")),
                () -> assertThrows(NullPointerException.class,
                        () -> ProjectFileFilter.ALLOW_ALL.isAllowed(null)));
    }

    @Test
    void emptyAndCommentOnlyFilesUseCanonicalAllowAll(@TempDir Path tempDir)
            throws IOException {
        ProjectFileFilter empty = parse(tempDir, "");
        ProjectFileFilter comments = parse(tempDir, "\n  # no filtering\n\t\n");

        assertAll(
                () -> assertSame(ProjectFileFilter.ALLOW_ALL, empty),
                () -> assertSame(ProjectFileFilter.ALLOW_ALL, comments));
    }

    @Test
    void noMatchingRuleKeepsInitialInclude(@TempDir Path tempDir) throws IOException {
        ProjectFileFilter filter = parse(tempDir, "EXCLUDE PATH SCHEMA/dbo/TABLE/x.sql");

        assertTrue(filter.isAllowed("SCHEMA/dbo/TABLE/y.sql"));
    }

    @Test
    void lastMatchingRuleWins(@TempDir Path tempDir) throws IOException {
        ProjectFileFilter filter = parse(tempDir, """
                # default is INCLUDE
                EXCLUDE REGEX (?i)^SCHEMA/generated/(?:TABLE|FUNCTION)/.*\\.sql$
                INCLUDE REGEX (?i)^SCHEMA/generated/TABLE/.*(?:metadata|report|security).*\\.sql$
                EXCLUDE PATH SCHEMA/generated/TABLE/security_archive.sql
                """);

        assertAll(
                () -> assertFalse(filter.isAllowed(
                        "SCHEMA/generated/FUNCTION/unrelated.sql")),
                () -> assertTrue(filter.isAllowed(
                        "SCHEMA/generated/TABLE/report_metadata.sql")),
                () -> assertFalse(filter.isAllowed(
                        "SCHEMA/generated/TABLE/security_archive.sql")),
                () -> assertTrue(filter.isAllowed("SCHEMA/public/TABLE/orders.sql")));
    }

    @Test
    void pathRulesAreExactAndNormalizeSlashes(@TempDir Path tempDir) throws IOException {
        ProjectFileFilter filter = parse(tempDir,
                "EXCLUDE PATH .//SCHEMA\\dbo//./TABLE\\x.sql");

        assertAll(
                () -> assertFalse(filter.isAllowed("SCHEMA/dbo/TABLE/x.sql")),
                () -> assertFalse(filter.isAllowed("SCHEMA\\dbo\\TABLE\\x.sql")),
                () -> assertFalse(filter.isAllowed("SCHEMA//./dbo///TABLE/x.sql")),
                () -> assertTrue(filter.isAllowed("SCHEMA/dbo/TABLE/x.sql.backup")));
    }

    @Test
    void regexRulesUseFullMatches(@TempDir Path tempDir) throws IOException {
        ProjectFileFilter filter = parse(tempDir, "EXCLUDE REGEX TABLE/.*\\.sql");

        assertAll(
                () -> assertFalse(filter.isAllowed("TABLE/x.sql")),
                () -> assertFalse(filter.isAllowed("TABLE\\nested\\x.sql")),
                () -> assertTrue(filter.isAllowed("SCHEMA/dbo/TABLE/x.sql")),
                () -> assertTrue(filter.isAllowed("TABLE/x.sql.backup")));
    }

    @Test
    void blankLinesAndCommentsAreIgnored(@TempDir Path tempDir) throws IOException {
        ProjectFileFilter filter = parse(tempDir,
                "\n    # ignored comment\n\t\nEXCLUDE PATH SCHEMA/dbo/TABLE/x.sql\n");

        assertAll(
                () -> assertFalse(filter.isAllowed("SCHEMA/dbo/TABLE/x.sql")),
                () -> assertTrue(filter.isAllowed("SCHEMA/dbo/TABLE/y.sql")));
    }

    @Test
    void invalidSyntaxReportsFileAndLine(@TempDir Path tempDir) throws IOException {
        Path file = writeFilter(tempDir, "# comment\nEXCLUDE PATH\n");

        assertInvalidAt(file, 2);
    }

    @Test
    void invalidActionReportsFileAndLine(@TempDir Path tempDir) throws IOException {
        Path file = writeFilter(tempDir, "exclude PATH SCHEMA/dbo/TABLE/x.sql\n");

        assertInvalidAt(file, 1);
    }

    @Test
    void invalidKindReportsFileAndLine(@TempDir Path tempDir) throws IOException {
        Path file = writeFilter(tempDir, "EXCLUDE GLOB SCHEMA/dbo/TABLE/*.sql\n");

        assertInvalidAt(file, 1);
    }

    @Test
    void invalidRegexReportsFileAndLine(@TempDir Path tempDir) throws IOException {
        Path file = writeFilter(tempDir, "\nEXCLUDE REGEX [unterminated\n");

        assertInvalidAt(file, 2);
    }

    @Test
    void absoluteOrTraversingPathRuleIsRejected(@TempDir Path tempDir) throws IOException {
        List<String> invalidPaths = List.of(
                "/SCHEMA/dbo/TABLE/x.sql",
                "\\\\server\\share\\x.sql",
                "C:\\SCHEMA\\dbo\\TABLE\\x.sql",
                "D:SCHEMA\\dbo\\TABLE\\x.sql",
                "./C:\\SCHEMA\\dbo\\TABLE\\x.sql",
                ".\\D:SCHEMA\\dbo\\TABLE\\x.sql",
                ".",
                "./",
                "SCHEMA/dbo/../secret.sql");

        for (int i = 0; i < invalidPaths.size(); i++) {
            Path file = tempDir.resolve("invalid-" + i + ".filter");
            Files.writeString(file, "EXCLUDE PATH " + invalidPaths.get(i) + "\n",
                    StandardCharsets.UTF_8);
            assertInvalidAt(file, 1);
        }
    }

    @Test
    void nonUtf8RuleFileIsFatal(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("non-utf8.filter");
        byte[] firstLine = "EXCLUDE PATH valid.sql\r".getBytes(StandardCharsets.UTF_8);
        byte[] secondLineStart = "EXCLUDE PATH SCHEMA/".getBytes(StandardCharsets.UTF_8);
        byte[] secondLineEnd = "/x.sql".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[firstLine.length + secondLineStart.length
                + 2 + secondLineEnd.length];
        System.arraycopy(firstLine, 0, content, 0, firstLine.length);
        System.arraycopy(secondLineStart, 0, content, firstLine.length,
                secondLineStart.length);
        int invalidOffset = firstLine.length + secondLineStart.length;
        content[invalidOffset] = (byte) 0xC3;
        content[invalidOffset + 1] = (byte) 0x28;
        System.arraycopy(secondLineEnd, 0, content, invalidOffset + 2,
                secondLineEnd.length);
        Files.write(file, content);

        IOException exception = assertInvalidAt(file, 2);
        assertAll(
                () -> assertTrue(exception.getMessage().contains("invalid UTF-8"),
                        exception::getMessage),
                () -> assertTrue(exception.getCause() instanceof CharacterCodingException));
    }

    private static ProjectFileFilter parse(Path tempDir, String content) throws IOException {
        return ProjectFileFilter.parse(writeFilter(tempDir, content));
    }

    private static Path writeFilter(Path tempDir, String content) throws IOException {
        Path file = tempDir.resolve("project.filter");
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static IOException assertInvalidAt(Path file, int line) {
        IOException exception = assertThrows(IOException.class,
                () -> ProjectFileFilter.parse(file));

        assertAll(
                () -> assertTrue(exception.getMessage().contains(file.toString()),
                        exception::getMessage),
                () -> assertTrue(exception.getMessage().contains(":" + line + ":"),
                        exception::getMessage));
        return exception;
    }
}
