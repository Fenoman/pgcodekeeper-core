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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable ordered rules for accepting top-level project files before parsing.
 */
public final class ProjectFileFilter {

    public static final ProjectFileFilter ALLOW_ALL = new ProjectFileFilter(List.of());

    private final List<Rule> rules;

    private ProjectFileFilter(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static ProjectFileFilter parse(Path filterFile) throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(filterFile, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ex) {
            throw invalidRule(filterFile, findInvalidUtf8Line(filterFile),
                    "invalid UTF-8", ex);
        }

        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (line.isBlank() || stripped.startsWith("#")) {
                continue;
            }

            int lineNumber = i + 1;
            String[] parts = stripped.split("\\s+", 3);
            if (parts.length != 3 || parts[2].isBlank()) {
                throw invalidRule(filterFile, lineNumber,
                        "expected ACTION KIND VALUE", null);
            }

            Decision decision;
            try {
                decision = Decision.valueOf(parts[0]);
            } catch (IllegalArgumentException ex) {
                throw invalidRule(filterFile, lineNumber,
                        "invalid action: " + parts[0], ex);
            }

            MatchKind kind;
            try {
                kind = MatchKind.valueOf(parts[1]);
            } catch (IllegalArgumentException ex) {
                throw invalidRule(filterFile, lineNumber,
                        "invalid match kind: " + parts[1], ex);
            }

            String value = parts[2];
            if (kind == MatchKind.PATH) {
                validateRulePath(filterFile, lineNumber, value);
                rules.add(new Rule(decision, kind, normalizePath(value), null));
            } else {
                try {
                    rules.add(new Rule(decision, kind, null, Pattern.compile(value)));
                } catch (PatternSyntaxException ex) {
                    throw invalidRule(filterFile, lineNumber,
                            "invalid regular expression", ex);
                }
            }
        }
        return rules.isEmpty() ? ALLOW_ALL : new ProjectFileFilter(rules);
    }

    public boolean isAllowed(String rootRelativePath) {
        Objects.requireNonNull(rootRelativePath, "rootRelativePath");
        if (this == ALLOW_ALL) {
            return true;
        }
        String candidate = normalizePath(rootRelativePath);
        boolean isAllowed = true;
        for (Rule rule : rules) {
            boolean matches = rule.kind == MatchKind.PATH
                    ? rule.path.equals(candidate)
                    : rule.pattern.matcher(candidate).matches();
            if (matches) {
                isAllowed = rule.decision == Decision.INCLUDE;
            }
        }
        return isAllowed;
    }

    private static void validateRulePath(Path filterFile, int lineNumber, String path)
            throws IOException {
        String slashPath = path.replace('\\', '/');
        String normalizedPath = normalizePath(slashPath);
        boolean hasDrivePrefix = normalizedPath.length() >= 2
                && Character.isLetter(normalizedPath.charAt(0))
                && normalizedPath.charAt(1) == ':';
        boolean hasTraversal = false;
        for (String segment : slashPath.split("/", -1)) {
            if ("..".equals(segment)) {
                hasTraversal = true;
                break;
            }
        }
        if (slashPath.startsWith("/") || normalizedPath.isEmpty()
                || hasDrivePrefix || hasTraversal) {
            throw invalidRule(filterFile, lineNumber,
                    "PATH must be a non-empty top-level-root-relative path without '..': "
                            + path, null);
        }
    }

    private static String normalizePath(String path) {
        StringBuilder normalized = new StringBuilder(path.length());
        int segmentStart = 0;
        for (int i = 0; i <= path.length(); i++) {
            boolean atEnd = i == path.length();
            if (!atEnd) {
                char current = path.charAt(i);
                if (current != '/' && current != '\\') {
                    continue;
                }
            }

            int segmentLength = i - segmentStart;
            if (segmentLength == 0
                    || (segmentLength == 1 && path.charAt(segmentStart) == '.')) {
                segmentStart = i + 1;
                continue;
            }
            if (!normalized.isEmpty()) {
                normalized.append('/');
            }
            normalized.append(path, segmentStart, i);
            segmentStart = i + 1;
        }
        return normalized.toString();
    }

    private static int findInvalidUtf8Line(Path filterFile) throws IOException {
        byte[] bytes = Files.readAllBytes(filterFile);
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i <= bytes.length; i++) {
            boolean atEnd = i == bytes.length;
            boolean atLineBreak = !atEnd && (bytes[i] == '\n' || bytes[i] == '\r');
            if (!atEnd && !atLineBreak) {
                continue;
            }
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .decode(ByteBuffer.wrap(bytes, lineStart, i - lineStart));
            } catch (CharacterCodingException ex) {
                return line;
            }
            if (atEnd) {
                break;
            }
            if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') {
                i++;
            }
            line++;
            lineStart = i + 1;
        }
        return 1;
    }

    private static IOException invalidRule(Path source, int line, String message,
            Exception cause) {
        return new IOException(source + ":" + line + ": " + message, cause);
    }

    private enum Decision {
        INCLUDE,
        EXCLUDE
    }

    private enum MatchKind {
        PATH,
        REGEX
    }

    private record Rule(Decision decision, MatchKind kind, String path, Pattern pattern) {
    }
}
