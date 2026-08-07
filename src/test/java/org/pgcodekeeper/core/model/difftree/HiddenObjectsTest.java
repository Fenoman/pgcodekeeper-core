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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.TestUtils;
import org.pgcodekeeper.core.api.LoadedComparison;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.model.difftree.HiddenObjects.HiddenByRule;
import org.pgcodekeeper.core.model.difftree.HiddenObjects.Report;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * An object a rule hides whole leaves no trace: it is not a node of the tree, so
 * nothing about it can be marked, coloured or hovered over. The count is the
 * only thing that can be said about it, and these tests pin what it is allowed
 * to say.
 * <p>
 * Three properties carry the whole feature and each has its own case below.
 * <ul>
 * <li><b>It counts what the rules took, not what is missing.</b> An object equal
 * on both sides is absent from the tree as surely as a hidden one, and a
 * container left empty by hiding is absent too; counting either would turn the
 * number into a measure of the database rather than of the rules.</li>
 * <li><b>Zero is an answer, not silence.</b> A comparison whose rules hid
 * nothing is not the same as a comparison without rules, and the report tells
 * them apart - that difference is the entire reason for the feature.</li>
 * <li><b>It changes nothing.</b> The tree and the script are byte for byte what
 * they are with the counting switched off, which is asserted here by running
 * both ways over the same models.</li>
 * </ul>
 */
class HiddenObjectsTest {

    private static final String ORIGINAL = "hidden_trigger_original.sql";
    private static final String NEW = "hidden_trigger_new.sql";

    private static final String HIDDEN_TRIGGERS = "hidden_trigger.pgcodekeeperignore";
    private static final String HIDDEN_SCHEMA_CONTENT = "hidden_schema_content.pgcodekeeperignore";
    private static final String WHITELIST = "whitelist.pgcodekeeperignore";
    private static final String DB_SCOPED = "db_scoped.pgcodekeeperignore";
    private static final String COLUMN_ONLY = "column_only.pgcodekeeperignore";
    private static final String MIXED_COLUMN = "mixed_column_rule.pgcodekeeperignore";
    private static final String SAME_NAME_TWO_TYPES = "same_name_two_types.pgcodekeeperignore";

    /** The two rules of {@link #MIXED_COLUMN} as their file writes them. */
    private static final List<String> MIXED_RULES = List.of(
            "HIDE NONE t_audit_insert type=COLUMN,TRIGGER",
            "HIDE NONE t_audit_update db=production type=COLUMN,TRIGGER");

    private final PgDatabaseProvider provider = new PgDatabaseProvider();

    /**
     * Without rules there is nothing to say, and saying nothing is right: the
     * report is silent, which is what keeps the counter off the screen of every
     * project that never asked for one.
     */
    @Test
    void aComparisonWithoutRulesIsSilent() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = plain(loaded);

        DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());

        assertTrue(settings.getHiddenObjects().report().isSilent(),
                "a comparison with no rules has nothing to report");
        assertEquals(0, settings.getHiddenObjects().passesRecorded(),
                "a comparison that can hide nothing must not pay for a pass");
    }

    /**
     * The fixture loses three nodes to this list and only two of them are hidden:
     * the two triggers a rule names, and the table they hung on, which is
     * unchanged in itself and left the tree because nothing differing was left
     * under it. Counting that table would be counting an object no rule
     * mentioned.
     */
    @Test
    void onlyWhatARuleTookIsCounted() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, HIDDEN_TRIGGERS);

        TreeElement tree = DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());

        assertEquals(List.of("public|SCHEMA|BOTH", "public.plain|TABLE|BOTH"), snapshot(tree),
                "fixture must lose the two triggers and the table they emptied");

        Report report = settings.getHiddenObjects().report();
        assertEquals(2, report.total(), "the emptied table is not something a rule hid");
        assertEquals(Map.of(DbObjType.TRIGGER, 2), report.byType());
    }

    /**
     * A {@code CONTENT} rule takes a subtree, and a subtree is worth what is in
     * it. Reported as one object, a rule that quietly swallowed a schema of
     * thousands would look like a rule that took a single table.
     */
    @Test
    void aContentRuleIsWorthEverythingUnderIt() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, HIDDEN_SCHEMA_CONTENT);

        assertEquals(List.of(), snapshot(DiffTree.create(settings,
                loaded.oldDatabase(), loaded.newDatabase())),
                "the whole tree goes with the schema");

        Report report = settings.getHiddenObjects().report();
        assertEquals(5, report.total(), "the schema and everything the tree held under it");
        assertEquals(Map.of(DbObjType.SCHEMA, 1, DbObjType.TABLE, 2, DbObjType.TRIGGER, 2),
                report.byType());
        assertEquals(List.of("public"), report.firingRules().get(0).examples(),
                "the object a rule named is the example, not what came away with it");
    }

    /**
     * The case the whole feature exists for. Both sides hold the same objects, so
     * the rules have nothing to hide and the count is zero - but the report is
     * <em>not</em> silent, because the list holds rules that could have hidden
     * something. Told apart, the two answers are "the rules found nothing today"
     * and "there are no rules"; confused, they are the silence this replaces.
     */
    @Test
    void nothingHiddenIsStillAnAnswerWhileThereAreRules() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, ORIGINAL);
        ISettings settings = hiding(loaded, HIDDEN_TRIGGERS);

        DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());

        Report report = settings.getHiddenObjects().report();
        assertEquals(0, report.total());
        assertFalse(report.isSilent(), "rules that hid nothing must still be reported");
        assertEquals(2, report.idleRules(), "both rules of the list hid nothing");
        assertEquals(List.of(), report.firingRules());
    }

    /**
     * Every rule that could take an object is named, whether it took one or not,
     * and each one that did carries a few names of what it took. A count alone
     * says the rules are alive; the names say whether they are alive on the right
     * objects, which is the question somebody staring at a number actually has.
     */
    @Test
    void everyRuleIsNamedWithWhatItTook() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, HIDDEN_TRIGGERS);

        DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());

        Report report = settings.getHiddenObjects().report();
        assertEquals(List.of("HIDE NONE t_audit_insert type=TRIGGER", "HIDE NONE t_audit_update type=TRIGGER"),
                report.rules().stream().map(HiddenByRule::rule).toList(),
                "the rules are named as an ignore list file writes them, in the order it holds them");
        assertEquals(List.of(1, 1), report.rules().stream().map(HiddenByRule::hidden).toList());
        assertEquals(List.of("public.audited.t_audit_insert"),
                report.rules().get(0).examples());
    }

    /**
     * A rule that only ever names a column can hide no node of a tree, because a
     * column is not one - it is a part of the definition of its table. Reporting
     * such a rule as having hidden nothing would say the exact opposite of what
     * is happening, and what it really did is already on the screen beside the
     * columns themselves.
     */
    @Test
    void aRuleThatOnlyNamesColumnsIsNotInTheReport() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, COLUMN_ONLY);

        DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());

        assertEquals(List.of("HIDE NONE t_audit_insert type=TRIGGER"),
                settings.getHiddenObjects().report().rules().stream().map(HiddenByRule::rule).toList(),
                "a type=COLUMN rule belongs to the pane, not to this count");
    }

    /**
     * A white list hides everything it does not name, and what it hides that way
     * belongs to no rule. It is still counted - the objects are gone from the
     * comparison either way - and reported against a rule of {@code null}, which
     * a reader is told about as the mode of the list rather than as a rule.
     */
    @Test
    void aWhiteListHidesByBeingOneAndThatIsCountedToo() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, WHITELIST);

        DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());

        Report report = settings.getHiddenObjects().report();
        assertFalse(report.isSilent());
        List<HiddenByRule> firing = report.firingRules();
        assertEquals(1, firing.size());
        assertNull(firing.get(0).rule(), "no rule named these: the list hides what it does not name");
        // the table and its two triggers; the schema is named by no rule either
        // but stays as the path to the one table the list does show
        assertEquals(3, firing.get(0).hidden());
    }

    /**
     * The tree is built without the name of the database, so a {@code db=} rule
     * is undecided there and takes nothing; the list under the tree is drawn up
     * with the name in hand and takes it. The report is the sum, which is why
     * both passes report and why neither of them may report the other's work.
     */
    @Test
    void theSecondPassCountsWhatTheFirstCouldNotDecide() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, DB_SCOPED);

        TreeElement tree = DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());
        assertEquals(0, settings.getHiddenObjects().report().total(),
                "a database scoped rule decides nothing while the name is unknown");

        new TreeFlattener().useIgnoreList(settings.getIgnoreList(), "production")
                .countHiddenInto(settings.getHiddenObjects()).flatten(tree);

        assertEquals(1, settings.getHiddenObjects().report().total(),
                "the pass that knows the database name takes the object and says so");
    }

    /**
     * The two passes are not obliged to be given the same rules, and in one mode
     * they are not: a comparison loaded without analysis retires the {@code
     * type=COLUMN} facet of every rule that has one, between the tree being built
     * and the list under it being drawn up. A rule written {@code
     * type=TABLE,COLUMN} therefore applies as itself in the first pass and as
     * {@code type=TABLE} in the second, and it is one rule both times.
     * <p>
     * Reported by the line each pass saw, it becomes two records - one holding
     * everything the rule took and one that took nothing, which is precisely how
     * a rule that has stopped matching reads, and this counter exists to tell
     * those two apart. Both rules here are mixed and each fires in a different
     * pass, so nothing but a name settled once can put the two halves of either
     * one back together.
     */
    @Test
    void aRuleTheModeRetypedIsStillOneRule() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, MIXED_COLUMN);

        TreeElement tree = DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());
        retireColumnFacets(settings);
        new TreeFlattener().useIgnoreList(settings.getIgnoreList(), "production")
                .countHiddenInto(settings.getHiddenObjects()).flatten(tree);

        Report report = settings.getHiddenObjects().report();
        assertEquals(MIXED_RULES, report.rules().stream().map(HiddenByRule::rule).toList(),
                "a rule that lost a facet between the passes is one rule, named as its file writes it");
        assertEquals(List.of(1, 1), report.rules().stream().map(HiddenByRule::hidden).toList(),
                "each rule took one trigger, in a different pass");
        assertEquals(0, report.idleRules(), "neither rule is idle: both took a trigger");
        assertEquals(2, report.total());
        assertEquals(Map.of(DbObjType.TRIGGER, 2), report.byType());
    }

    /**
     * The same rules with nothing retired between the passes, which is every
     * comparison outside that mode: the report is what it always was, down to the
     * line each rule is named by and the order they are named in. The mode may
     * teach this counter to survive a rule being narrowed under it; it may not
     * make the counter answer differently to the comparisons that are not in it.
     */
    @Test
    void withNothingRetiredTheSameRulesReportExactlyAsBefore() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, MIXED_COLUMN);

        TreeElement tree = DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());
        new TreeFlattener().useIgnoreList(settings.getIgnoreList(), "production")
                .countHiddenInto(settings.getHiddenObjects()).flatten(tree);

        Report report = settings.getHiddenObjects().report();
        assertEquals(MIXED_RULES, report.rules().stream().map(HiddenByRule::rule).toList());
        assertEquals(List.of(1, 1), report.rules().stream().map(HiddenByRule::hidden).toList());
        assertEquals(0, report.idleRules());
        assertEquals(2, report.total());
        assertEquals(Map.of(DbObjType.TRIGGER, 2), report.byType());
    }

    /**
     * Two rules of one list naming the same object with different types are two
     * rules, and stay two however narrow either of them becomes. This is the
     * other half of the rule above and the half a careless reading of it would
     * break: recognising a narrowed rule by everything except its types would
     * also fold these two together and report one of them as having taken what
     * the other took.
     */
    @Test
    void twoRulesThatDifferOnlyInTypeAreNeverOne() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, SAME_NAME_TWO_TYPES);

        TreeElement tree = DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());
        retireColumnFacets(settings);
        new TreeFlattener().useIgnoreList(settings.getIgnoreList())
                .countHiddenInto(settings.getHiddenObjects()).flatten(tree);

        Report report = settings.getHiddenObjects().report();
        assertEquals(List.of("HIDE NONE t_audit_insert type=COLUMN,TRIGGER",
                "HIDE NONE t_audit_insert type=TRIGGER"),
                report.rules().stream().map(HiddenByRule::rule).toList(),
                "two rules of a list are two records, whatever they have in common");
        assertEquals(List.of(1, 0), report.rules().stream().map(HiddenByRule::hidden).toList(),
                "the first rule of the list decided the trigger, so the second one had nothing left");
    }

    /**
     * What the mode does to the rules of a comparison between the two passes,
     * reproduced here because it is core that has to sum the passes afterwards:
     * the facet is retired by {@code ProjectIgnoreLists#dropColumnRulesIfStructural}
     * in the UI, which core cannot call and cannot be called from.
     */
    private static void retireColumnFacets(ISettings settings) {
        IgnoreList ignoreList = settings.getIgnoreList();
        List<IgnoredObject> snapshot = new ArrayList<>(ignoreList.getList());
        ignoreList.clearList();
        for (IgnoredObject rule : snapshot) {
            Set<DbObjType> types = rule.getObjTypes();
            if (!types.contains(DbObjType.COLUMN)) {
                ignoreList.add(rule);
                continue;
            }
            Set<DbObjType> otherTypes = EnumSet.copyOf(types);
            otherTypes.remove(DbObjType.COLUMN);
            if (otherTypes.isEmpty()) {
                // a rule that named nothing but columns is dropped outright: an
                // empty type set reads as every type everywhere else
                continue;
            }
            ignoreList.add(new IgnoredObject(rule.getName(),
                    rule.getDbRegex() == null ? null : rule.getDbRegex().pattern(),
                    rule.isShow(), rule.isRegular(), rule.isIgnoreContent(), rule.isQualified(), otherTypes));
        }
    }

    /**
     * A tree is flattened more than once for one comparison - on the way to the
     * screen and again on the way to a script - and each pass replaces its own
     * record rather than adding to it. Were it otherwise, looking at a comparison
     * twice would double the number in front of the reader.
     */
    @Test
    void flatteningAgainReportsTheSameNumber() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, DB_SCOPED);
        TreeElement tree = DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());

        for (int run = 0; run < 3; run++) {
            new TreeFlattener().useIgnoreList(settings.getIgnoreList(), "production")
                    .countHiddenInto(settings.getHiddenObjects()).flatten(tree);
            assertEquals(1, settings.getHiddenObjects().report().total(),
                    "run " + run + " must report what one pass hid, not what every pass hid");
        }
    }

    /**
     * The regression this design exists to prevent, watched the way
     * {@link ColumnUsers#indexesBuilt()} watches its own: the answer is taken
     * while the rules are being applied and never again, so reading it - which a
     * pane does on every repaint, scroll and checkbox - costs nothing.
     */
    @Test
    void theAnswerIsTakenOncePerPassHoweverOftenItIsRead() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, HIDDEN_TRIGGERS);

        DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());
        for (int read = 0; read < 100; read++) {
            settings.getHiddenObjects().report();
        }

        assertEquals(1, settings.getHiddenObjects().passesRecorded(),
                "reading the report must never take it again");
    }

    /**
     * A flattening that was not asked to count must not touch the holder: script
     * generation flattens with the rules of the very same comparison, and letting
     * it publish would let the last pass to run decide the number the pane earned.
     */
    @Test
    void aFlatteningThatWasNotAskedToCountRecordsNothing() throws IOException, InterruptedException {
        LoadedComparison loaded = load(ORIGINAL, NEW);
        ISettings settings = hiding(loaded, DB_SCOPED);
        TreeElement tree = DiffTree.create(settings, loaded.oldDatabase(), loaded.newDatabase());

        new TreeFlattener().useIgnoreList(settings.getIgnoreList(), "production").flatten(tree);

        assertEquals(1, settings.getHiddenObjects().passesRecorded(),
                "only the pass that was asked to count may publish");
        assertEquals(0, settings.getHiddenObjects().report().total());
    }

    /**
     * The proof that this is display and nothing else: the same models, the same
     * rules, the tree built once by settings that count and once by settings that
     * do not. Neither the tree nor the script generated from it may differ.
     */
    @Test
    void countingMovesNeitherTheTreeNorTheScript() throws IOException, InterruptedException {
        for (String rules : List.of(HIDDEN_TRIGGERS, HIDDEN_SCHEMA_CONTENT, WHITELIST, DB_SCOPED, COLUMN_ONLY,
                MIXED_COLUMN, SAME_NAME_TWO_TYPES)) {
            LoadedComparison loaded = load(ORIGINAL, NEW);
            ISettings counting = hiding(loaded, rules);
            ISettings blind = blind(rules);

            TreeElement countedTree = DiffTree.create(counting, loaded.oldDatabase(), loaded.newDatabase());
            TreeElement blindTree = DiffTree.create(blind, loaded.oldDatabase(), loaded.newDatabase());

            assertEquals(snapshot(blindTree), snapshot(countedTree),
                    rules + ": counting must not move a single node");
            assertArrayEquals(script(loaded, blindTree, blind), script(loaded, countedTree, counting),
                    rules + ": counting must not move the script by a single byte");
        }
    }

    private byte[] script(LoadedComparison loaded, TreeElement tree, ISettings settings) throws IOException {
        tree.setAllChecked();
        return PgCodeKeeperApi.diff(provider, loaded.oldDatabase(), loaded.newDatabase(), settings, tree)
                .getBytes(StandardCharsets.UTF_8);
    }

    private LoadedComparison load(String original, String updated) throws IOException, InterruptedException {
        var settings = new CoreSettings();
        LoadedComparison loaded = PgCodeKeeperApi.loadForComparison(
                new ComparisonLoaderFactories(
                        sideSettings -> provider.getDumpLoader(path(original), sideSettings),
                        sideSettings -> provider.getDumpLoader(path(updated), sideSettings)),
                settings);
        TestUtils.assertErrors(settings.getErrors());
        return loaded;
    }

    private static ISettings plain(LoadedComparison loaded) {
        return loaded.comparisonSettings().copy();
    }

    private ISettings hiding(LoadedComparison loaded, String ignoreFile) throws IOException {
        ISettings settings = loaded.comparisonSettings().copy();
        settings.addIgnoreList(path(ignoreFile));
        return settings;
    }

    /**
     * Settings that record nothing, which is what every settings implementation
     * outside this project is, and what these settings were before there was a
     * count.
     */
    private ISettings blind(String ignoreFile) throws IOException {
        ISettings settings = new CoreSettings() {

            @Override
            public HiddenObjects getHiddenObjects() {
                return HiddenObjects.NONE;
            }
        };
        settings.addIgnoreList(path(ignoreFile));
        return settings;
    }

    private Path path(String fileName) {
        return TestUtils.getFilePath(fileName, getClass());
    }

    /**
     * Flattens the tree to {@code qualified name|type|side} in depth first order,
     * skipping the artificial database root.
     */
    private static List<String> snapshot(TreeElement root) {
        List<String> names = new ArrayList<>();
        collect(root, names);
        return names;
    }

    private static void collect(TreeElement el, List<String> names) {
        if (el.getType() != DbObjType.DATABASE) {
            names.add(el.getQualifiedName() + '|' + el.getType() + '|' + el.getSide());
        }
        for (TreeElement child : el.getChildren()) {
            collect(child, names);
        }
    }
}
