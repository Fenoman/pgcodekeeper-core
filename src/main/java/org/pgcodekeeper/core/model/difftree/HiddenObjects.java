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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;

/**
 * How many objects the ignore rules kept out of one comparison, and which rule
 * kept out which.
 * <p>
 * <b>Why anything at all.</b> Every other thing a rule does to a comparison is
 * now visible where it happens: a column a rule names stays on the screen and
 * wears a mark, see {@link ColumnMark} and {@link SqlMarkup}. One kind of hiding
 * cannot be marked, and it is the widest one - an object a rule hides whole
 * never becomes a node of the difference tree, so there is no line to write a
 * mark beside. A table, a function, a schema simply is not there. That silence
 * reads exactly the same whether the rules are doing their job or have stopped
 * doing it: an object renamed, a typo in a pattern, a {@code db=} that no longer
 * matches, and the comparison looks precisely as it does when everything is
 * well. This is the number that ends the silence.
 * <p>
 * <b>What is counted, and what deliberately is not.</b> Only a node a rule
 * removed. An object that is the same on both sides is absent from the tree too,
 * and confusing the two would make the number meaningless - it would grow with
 * the size of the database rather than with the reach of the rules. So the count
 * is taken where the removal happens, from the passes that do the removing, and
 * a container dropped because every differing child of it was hidden is not
 * counted either: that object holds no change of its own, and no rule named it.
 * <p>
 * <b>A whole subtree counts as what it is.</b> A {@code CONTENT} rule on a
 * schema takes the schema and everything under it, and reporting that as one
 * object would understate it by three orders of magnitude on the projects this
 * exists for. So the node the rule named and every node below it are counted,
 * which is also what makes this number comparable with the object count it
 * stands beside: both count the rows the comparison would have offered.
 * <p>
 * <b>Where the cost is.</b> Nowhere that a rendering can reach. Nothing here
 * walks a database, or a tree, of its own: the counting rides the two passes
 * that already walk the tree to hide things - {@link DiffTree} while the tree is
 * built and {@link TreeFlattener} while the list under it is drawn up - and each
 * of them adds one increment per node it was about to drop anyway. The reading
 * is an immutable {@link Report} built once per pass, so a repaint, a scroll or
 * a checkbox costs nothing at all. This follows {@link ColumnUsers}, which had
 * to learn the same lesson the expensive way: the answer is computed once per
 * operation and held by the settings of that operation, see
 * {@code ISettings.getHiddenObjects()}.
 * <p>
 * <b>The lifetime is one operation</b>, for the reason {@link ColumnUsers}
 * spells out: the settings are built fresh for every comparison and no copy of
 * them carries this over, so a number can never outlive the tree it describes.
 * <p>
 * <b>Two passes, two slots, one sum.</b> The rules are applied twice on the way
 * to the screen and the two are not the same pass. The tree is built without
 * knowing the name of the database, so a rule scoped with {@code db=} is left
 * undecided there and hides nothing; the list under the tree is drawn up with
 * the name in hand and decides it. Each pass therefore records into its own slot
 * and replaces whatever it recorded before, and the report is the sum of the
 * slots. Replacing rather than adding is what makes a second run of the same
 * pass - and there are several, since a flattening also happens on the way to a
 * script - report the same number instead of twice the number.
 * <p>
 * <b>The rules are named once for the whole operation.</b> A rule is reported by
 * the line an ignore list file writes it as, and that line is not the same in
 * both passes: a comparison loaded without analysis retires the
 * {@code type=COLUMN} facet of every rule that has one, and it does so after the
 * tree is built and before the list under it is drawn up, because that is the
 * first moment every source of rules has had its say. So the pass that hid a
 * table by {@code type=TABLE,COLUMN} and the pass that saw {@code type=TABLE} are
 * looking at one rule wearing two lines, and summing the passes by that line
 * would turn it into two records - one holding everything it took and one that
 * took nothing, which reads as a rule that has stopped working and is the very
 * misreading this class was built to end. The name is therefore settled where a
 * rule is met, not where the passes are summed: a pass files what it hid under
 * the name this operation already reports that rule by, see
 * {@link #nameRules(List)}.
 *
 * @see #report() what a reader of the comparison is told
 */
public final class HiddenObjects {

    /** How many names of what a rule hid are kept, per rule. */
    static final int EXAMPLES_PER_RULE = 3;

    /** A rule that names this and nothing else can hide no node of a tree. */
    private static final Set<DbObjType> COLUMNS_ONLY = Set.of(DbObjType.COLUMN);

    /**
     * The holder that records nothing, for a settings implementation of
     * somebody else's and for every caller with no comparison to scope a count
     * to. Every pass writing into it writes into nothing, and it reports an
     * empty {@link Report} - the same answer as a comparison without rules.
     */
    public static final HiddenObjects NONE = new HiddenObjects(false);

    /**
     * Which pass of the rules a record came from. The two hide different things
     * and neither sees what the other hid, see the class comment.
     */
    enum Pass {
        /** Building the difference tree: what never became a node. */
        TREE,
        /** Drawing up the list under the tree: what a node was made of and the list leaves out. */
        LIST
    }

    private final boolean recording;

    /** What each pass last recorded. Guarded by {@code this}. */
    private final Map<Pass, Report> passes = new EnumMap<>(Pass.class);

    /**
     * The name each rule this operation has met is reported under, in the order
     * it was met. Guarded by {@code this}.
     */
    private final Map<IgnoredObject, String> ruleNames = new LinkedHashMap<>();

    /** How many pass records were published. Guarded by {@code this}. */
    private int recorded;

    private HiddenObjects(boolean recording) {
        this.recording = recording;
    }

    /**
     * A holder for one operation - one comparison and the list drawn up from
     * it.
     *
     * @return a holder that records what the rules of this operation hide
     */
    public static HiddenObjects forOperation() {
        return new HiddenObjects(true);
    }

    /**
     * How many pass records were published into this holder, which is what the
     * cost of the whole mechanism is measured in.
     * <p>
     * Two at most for one comparison shown in a pane - one per pass - however
     * many times that pane is drawn, scrolled or checked. It is here for the
     * same reason {@link ColumnUsers#indexesBuilt()} is: the one regression this
     * design exists to prevent is a count taken per rendering, and this is what
     * a test can watch to see it happen.
     *
     * @return the number of published records
     */
    public synchronized int passesRecorded() {
        return recorded;
    }

    /**
     * What the rules of this comparison hid, summed over the passes that hid it.
     *
     * @return the report, empty when nothing was recorded
     */
    public synchronized Report report() {
        if (passes.isEmpty()) {
            return Report.EMPTY;
        }
        Report sum = null;
        for (Report pass : passes.values()) {
            sum = sum == null ? pass : sum.plus(pass);
        }
        return sum;
    }

    /**
     * Opens a record for one pass of the rules over one tree.
     *
     * @param ignoreList the rules that pass applies, may be {@code null}
     * @param pass       which pass it is
     * @return the recorder to report every hidden node to, and to
     * {@link Recorder#publish()} at the end of the pass
     */
    Recorder recorder(IgnoreList ignoreList, Pass pass) {
        return new Recorder(recording ? this : null, ignoreList, pass);
    }

    private synchronized void publish(Pass pass, Report report) {
        passes.put(pass, report);
        recorded++;
    }

    /**
     * Names the rules a pass is about to apply, so that what every pass hid can
     * be summed rule by rule, see the class comment.
     * <p>
     * A rule the operation has already met keeps the name it was met under -
     * the same rule reported under two names is two records of one rule, and
     * one of them says that a working rule took nothing. A rule met for the
     * first time is named as an ignore list file writes it, which is what makes
     * the report readable at all: a reader looks for the line they wrote.
     * <p>
     * The rules of one list are matched by what they are before anything looser
     * is tried, so a list that reaches both passes unchanged - every list
     * outside the mode that retires facets - names its rules exactly as it
     * always did, and no two rules of it can be taken for one.
     *
     * @param rules the rules of this pass, in the order the list holds them
     * @return the name each of them is reported under, in the same order
     */
    private synchronized Map<IgnoredObject, String> nameRules(List<IgnoredObject> rules) {
        Map<IgnoredObject, String> named = new LinkedHashMap<>();
        Set<String> claimed = new HashSet<>();
        List<IgnoredObject> unnamed = new ArrayList<>();

        for (IgnoredObject rule : rules) {
            String name = ruleNames.get(rule);
            // the place in the order is taken now and filled in below, once
            // every rule that is what it always was has claimed its own name
            named.put(rule, name);
            if (name == null) {
                unnamed.add(rule);
            } else {
                claimed.add(name);
            }
        }

        for (IgnoredObject rule : unnamed) {
            String name = retypedName(rule, claimed);
            if (name == null) {
                name = rule.toString();
            }
            // met under this name from here on, so a later pass - a tree is
            // flattened more than once - resolves it outright
            ruleNames.put(rule, name);
            named.put(rule, name);
            claimed.add(name);
        }
        return named;
    }

    /**
     * The name of the rule this operation already knows that the given rule is
     * the same rule as, one facet of it retired.
     *
     * @param rule    a rule of this pass that is nothing this operation has met
     * @param claimed the names the rules of this pass have already taken, which
     *                a rule of the same list is holding and this one may not
     *                join
     * @return the name to report it under, or {@code null} when it belongs to
     * no rule met before or to more than one, where guessing would merge two
     * records that are not one rule
     */
    private String retypedName(IgnoredObject rule, Set<String> claimed) {
        String found = null;
        for (Map.Entry<IgnoredObject, String> known : ruleNames.entrySet()) {
            String name = known.getValue();
            if (claimed.contains(name) || found != null && found.equals(name)
                    || !isSameRuleRetyped(known.getKey(), rule)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = name;
        }
        return found;
    }

    /**
     * Reports whether two rules are one rule that lost or gained a type, which
     * is what retiring a facet of a rule leaves behind - and the only difference
     * one operation is allowed to make to its own rules. Which of the two is the
     * narrower one is not asked: the passes meet them in whichever order they
     * run in, and it is one rule either way.
     */
    private static boolean isSameRuleRetyped(IgnoredObject known, IgnoredObject rule) {
        Set<DbObjType> knownTypes = known.getObjTypes();
        Set<DbObjType> types = rule.getObjTypes();
        if (!knownTypes.containsAll(types) && !types.containsAll(knownTypes)) {
            return false;
        }
        if (!Objects.equals(known.getName(), rule.getName())) {
            // asked ahead of the answer below and of nothing else: a rule of a
            // long list is told apart from most of it by its name alone, and
            // the answer costs a rule to build
            return false;
        }
        // everything else about the two has to agree, and that is asked of
        // equals itself rather than of a list of getters copied out of it: a
        // field added to a rule is then not quietly left out of the question
        IgnoredObject retyped = known.copy(known.getName());
        retyped.setObjTypes(types);
        return retyped.equals(rule);
    }

    /**
     * Collects what one pass hides while that pass walks the tree.
     * <p>
     * Not thread safe and not meant to be: it belongs to one walk, exactly as
     * the {@link IgnoreListFilter} it stands beside does.
     */
    static final class Recorder {

        /** {@code null} when nothing is being recorded, which is the common case. */
        private final HiddenObjects holder;
        private final Pass pass;

        /** What this operation reports each rule of this pass as, see {@link HiddenObjects#nameRules(List)}. */
        private final Map<IgnoredObject, String> names;

        private final Map<String, RuleTally> byRule;
        private final Map<DbObjType, Integer> byType = new EnumMap<>(DbObjType.class);
        private int total;

        private Recorder(HiddenObjects holder, IgnoreList ignoreList, Pass pass) {
            this.holder = holder;
            this.pass = pass;
            this.names = holder == null ? Map.of() : holder.nameRules(reportableRules(ignoreList));
            this.byRule = holder == null ? Map.of() : tallies(names.values());
        }

        /**
         * Records one node the rules removed, the node alone.
         *
         * @param el   the removed node
         * @param rule the rule that removed it, {@code null} when no rule named
         *             it and the list hides what it does not name
         */
        void hid(TreeElement el, IgnoredObject rule) {
            if (holder == null) {
                return;
            }
            count(el, tallyOf(rule), true);
        }

        /**
         * Records one node the rules removed together with everything under it,
         * which is what a {@code CONTENT} rule takes.
         *
         * @param el   the removed node, the one the rule named
         * @param rule the rule that removed it, {@code null} when no rule named
         *             it and the list hides what it does not name
         */
        void hidWithSubtree(TreeElement el, IgnoredObject rule) {
            if (holder == null) {
                return;
            }
            RuleTally tally = tallyOf(rule);
            count(el, tally, true);
            countDescendants(el, tally);
        }

        /**
         * Publishes what this pass hid, replacing whatever the same pass
         * published before. Call once, at the end of the pass.
         */
        void publish() {
            if (holder == null) {
                return;
            }
            List<HiddenByRule> rules = new ArrayList<>(byRule.size());
            for (RuleTally tally : byRule.values()) {
                rules.add(tally.frozen());
            }
            holder.publish(pass, new Report(total,
                    Collections.unmodifiableMap(new EnumMap<>(byType)), List.copyOf(rules)));
        }

        private void countDescendants(TreeElement el, RuleTally tally) {
            for (TreeElement child : el.getChildren()) {
                // the example is the object the rule named, not everything that
                // came away with it: three qualified names of a schema say
                // nothing that its own name does not
                count(child, tally, false);
                countDescendants(child, tally);
            }
        }

        private void count(TreeElement el, RuleTally tally, boolean named) {
            if (el.getType() == DbObjType.DATABASE) {
                // the root of a tree is an artificial node standing for the
                // comparison itself; it is no object and no list ever offered it
                return;
            }
            total++;
            byType.merge(el.getType(), 1, Integer::sum);
            tally.hidden++;
            if (named && tally.examples.size() < EXAMPLES_PER_RULE) {
                tally.examples.add(el.getQualifiedName());
            }
        }

        private RuleTally tallyOf(IgnoredObject rule) {
            return byRule.computeIfAbsent(nameOf(rule), RuleTally::new);
        }

        /**
         * What the report calls a rule that hid something: the name the
         * operation knows it by, or the line an ignore list file writes it as
         * for a rule the pass never offered to be named - one that hides
         * nothing a reader can be told about in advance, such as a rule scoped
         * to columns alone.
         */
        private String nameOf(IgnoredObject rule) {
            if (rule == null) {
                // no rule named these: a white list hides what it does not name
                return null;
            }
            String name = names.get(rule);
            return name != null ? name : rule.toString();
        }

        /** One tally per named rule, each starting at nothing hidden. */
        private static Map<String, RuleTally> tallies(Collection<String> names) {
            Map<String, RuleTally> byRule = new LinkedHashMap<>();
            for (String name : names) {
                byRule.computeIfAbsent(name, RuleTally::new);
            }
            return byRule;
        }

        /**
         * Every rule of the list that could hide a node of a tree.
         * <p>
         * They are collected before the pass rather than when they first fire,
         * because a rule that fires never is the whole reason this class exists
         * and it would otherwise be the one rule the report does not mention. In
         * the order the list holds them, which is the order they were written in
         * and the order a reader will look for them in.
         * <p>
         * A rule that only ever names a column is left out on purpose. Columns
         * are not nodes of a difference tree - they are a part of the definition
         * of their table - so such a rule can hide nothing here however well it
         * works, and reporting it as having hidden nothing would say the exact
         * opposite of the truth. What it did is on the screen already, beside
         * the columns themselves, see {@link ColumnVisibility}.
         */
        private static List<IgnoredObject> reportableRules(IgnoreList ignoreList) {
            if (ignoreList == null) {
                return List.of();
            }
            List<IgnoredObject> rules = new ArrayList<>();
            for (IgnoredObject rule : ignoreList.getList()) {
                if (!rule.isShow() && !COLUMNS_ONLY.equals(rule.getObjTypes())) {
                    rules.add(rule);
                }
            }
            return rules;
        }
    }

    /** One rule while it is being counted. */
    private static final class RuleTally {

        private final String rule;
        private final List<String> examples = new ArrayList<>(EXAMPLES_PER_RULE);
        private int hidden;

        private RuleTally(String rule) {
            this.rule = rule;
        }

        private HiddenByRule frozen() {
            return new HiddenByRule(rule, hidden, List.copyOf(examples));
        }
    }

    /**
     * What one rule kept out of the comparison.
     *
     * @param rule     the rule exactly as it is written in an ignore list file -
     *                 a rule this operation retired a facet of is named as it
     *                 was written rather than as it ended up, because a reader
     *                 goes looking for it in their own file - or {@code null}
     *                 for the objects no rule named at all, which a list in
     *                 white list mode hides by being one
     * @param hidden   how many objects it kept out, zero for a rule that matched
     *                 nothing in this comparison
     * @param examples up to {@link #EXAMPLES_PER_RULE} qualified names of the
     *                 objects it named, in the order the comparison met them,
     *                 so that a reader can see what a rule is really taking
     *                 rather than only how much
     */
    public record HiddenByRule(String rule, int hidden, List<String> examples) {
    }

    /**
     * What the rules kept out of one comparison, as a reader of it is told.
     *
     * @param total  how many objects left the comparison
     * @param byType how many of each kind, which is the first thing that tells a
     *               reader whether the rules took what they were meant to take
     * @param rules  every rule that could hide an object, in the order the list
     *               holds them, each with what it hid - <b>including the rules
     *               that hid nothing</b>, which are the ones worth looking at
     *               when a number is smaller than it should be
     */
    public record Report(int total, Map<DbObjType, Integer> byType, List<HiddenByRule> rules) {

        /** Nothing was hidden and no rule could have hidden anything. */
        public static final Report EMPTY = new Report(0, Map.of(), List.of());

        /**
         * Reports whether this comparison has nothing to say about hiding at
         * all: no object left it and no rule of its list could have taken one.
         * <p>
         * The distinction this draws is the point of the whole report. A
         * comparison with rules that hid nothing is <em>not</em> empty: that is
         * a comparison whose rules may have stopped working, and it is exactly
         * the case that used to look identical to a comparison with no rules.
         *
         * @return true when the reader is to be told nothing
         */
        public boolean isSilent() {
            return total == 0 && rules.isEmpty();
        }

        /**
         * The rules that hid something, most first.
         *
         * @return the firing rules in descending order of what they hid
         */
        public List<HiddenByRule> firingRules() {
            List<HiddenByRule> firing = new ArrayList<>();
            for (HiddenByRule rule : rules) {
                if (rule.hidden() > 0) {
                    firing.add(rule);
                }
            }
            firing.sort((left, right) -> Integer.compare(right.hidden(), left.hidden()));
            return firing;
        }

        /**
         * How many rules of the list could have hidden an object and did not.
         * <p>
         * Not by itself a fault: a rule written for a partition that this
         * comparison happens to agree about has nothing to hide and is perfectly
         * well. It is the number to read when the total is lower than expected,
         * and the only place a rule that quietly stopped matching can be found.
         *
         * @return the count of rules that hid nothing
         */
        public int idleRules() {
            int idle = 0;
            for (HiddenByRule rule : rules) {
                if (rule.hidden() == 0) {
                    idle++;
                }
            }
            return idle;
        }

        Report plus(Report other) {
            if (other.isSilent()) {
                return this;
            }
            if (isSilent()) {
                return other;
            }

            Map<DbObjType, Integer> types = new EnumMap<>(DbObjType.class);
            types.putAll(byType);
            other.byType.forEach((type, count) -> types.merge(type, count, Integer::sum));

            // by the name of the rule, which the operation settles once and both
            // passes file under - a rule whose line changed between the passes
            // is still the one rule, see nameRules
            Map<String, HiddenByRule> merged = new LinkedHashMap<>();
            rules.forEach(rule -> merged.put(rule.rule(), rule));
            other.rules.forEach(rule -> merged.merge(rule.rule(), rule, Report::join));

            return new Report(total + other.total, Collections.unmodifiableMap(types),
                    List.copyOf(merged.values()));
        }

        private static HiddenByRule join(HiddenByRule left, HiddenByRule right) {
            List<String> examples = new ArrayList<>(left.examples());
            for (String example : right.examples()) {
                if (examples.size() >= EXAMPLES_PER_RULE) {
                    break;
                }
                examples.add(example);
            }
            return new HiddenByRule(left.rule(), left.hidden() + right.hidden(), List.copyOf(examples));
        }
    }
}
