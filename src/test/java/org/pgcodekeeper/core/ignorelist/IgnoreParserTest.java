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
package org.pgcodekeeper.core.ignorelist;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.database.api.schema.DbObjType;

import java.nio.file.Path;
import java.util.Set;

/**
 * Fail-fast contract of {@link IgnoreParser}: a syntactically broken ignore
 * list file must produce a typed exception with file location info.
 */
final class IgnoreParserTest {

    private static final String MALFORMED_REGEX_NAME =
            "malformed_regex_name.pgcodekeeperignore";
    private static final String VALID_RULES = "valid_rules.pgcodekeeperignore";
    private static final String BROKEN_BAD_TYPE = "broken_bad_type.pgcodekeeperignore";

    @Test
    void brokenFileThrowsTypedExceptionWithFileAndLineInfo() {
        Path listFile = getFixture(MALFORMED_REGEX_NAME);
        var list = new IgnoreList();

        var ex = Assertions.assertThrows(IgnoreListParseException.class,
                () -> new IgnoreParser(list).parse(listFile));

        Assertions.assertEquals(listFile.toString(), ex.getListPath());
        Assertions.assertTrue(ex.getMessage().contains(listFile.toString()),
                "message must contain the file path: " + ex.getMessage());
        Assertions.assertTrue(ex.getMessage().contains("line 13"),
                "message must point at the broken line: " + ex.getMessage());
    }

    @Test
    void brokenFileThrowsSameTypedExceptionForIgnoreSchemaList() {
        Path listFile = getFixture(MALFORMED_REGEX_NAME);
        var schemaList = new IgnoreSchemaList();

        var ex = Assertions.assertThrows(IgnoreListParseException.class,
                () -> IIgnoreList.parseIgnoreList(listFile, schemaList));

        Assertions.assertEquals(listFile.toString(), ex.getListPath());
        Assertions.assertTrue(ex.getMessage().contains("line 13"),
                "message must point at the broken line: " + ex.getMessage());
    }

    @Test
    void brokenFileThrowsSameTypedExceptionViaParseLists() {
        Path listFile = getFixture(MALFORMED_REGEX_NAME);

        var ex = Assertions.assertThrows(IgnoreListParseException.class,
                () -> IgnoreParser.parseLists(Set.of(listFile.toString())));

        Assertions.assertEquals(listFile.toString(), ex.getListPath());
    }

    @Test
    void unknownObjectTypeThrowsTypedExceptionWithFilePath() {
        Path listFile = getFixture(BROKEN_BAD_TYPE);
        var list = new IgnoreList();

        var ex = Assertions.assertThrows(IgnoreListParseException.class,
                () -> new IgnoreParser(list).parse(listFile));

        Assertions.assertEquals(listFile.toString(), ex.getListPath());
        Assertions.assertTrue(ex.getMessage().contains(listFile.toString()),
                "message must contain the file path: " + ex.getMessage());
    }

    @Test
    void validFileLoadsAllTwentySixRules() throws Exception {
        Path listFile = getFixture(VALID_RULES);
        var list = new IgnoreList();

        new IgnoreParser(list).parse(listFile);

        Assertions.assertEquals(26, list.getList().size());
        // SHOW ALL black list keeps the default show behavior
        Assertions.assertTrue(list.isShow());

        var archiveRule = list.getList().stream()
                .filter(rule -> "archived_.*".equals(rule.getName()))
                .findAny()
                .orElseThrow(() -> new AssertionError(
                        "quoted 'archived_.*' rule must be loaded"));
        Assertions.assertTrue(archiveRule.isRegular());
        Assertions.assertFalse(archiveRule.isShow());
        Assertions.assertEquals(Set.of(DbObjType.TABLE), archiveRule.getObjTypes());
    }

    @Test
    void distributionPartitionRegexMatchesPartitionTables() {
        var rule = new IgnoredObject("event_archive_p\\d+_\\d{6}",
                true, false, false, Set.of(DbObjType.TABLE));

        Assertions.assertTrue(rule.match("event_archive_p1_202008"));
        Assertions.assertTrue(rule.match("analytics.event_archive_p1_202008"));
        Assertions.assertTrue(rule.match("event_archive_p12_202512"));

        Assertions.assertFalse(rule.match("event_archive_202008"));
        Assertions.assertFalse(rule.match("event_archive_p_202008"));
        Assertions.assertFalse(rule.match("event_archive_p1_2020"));
    }

    @Test
    void distributionMonthRegexMatchesMonthTables() {
        var rule = new IgnoredObject("event_archive_\\d{6}",
                true, false, false, Set.of(DbObjType.TABLE));

        Assertions.assertTrue(rule.match("event_archive_202008"));
        Assertions.assertTrue(rule.match("analytics.event_archive_202512"));

        Assertions.assertFalse(rule.match("event_archive_p1_202008"));
        Assertions.assertFalse(rule.match("event_archive_2020"));
    }

    private static Path getFixture(String resourceName) {
        return TestUtils.getFilePath(resourceName, IgnoreParserTest.class);
    }
}
