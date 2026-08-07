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

import java.util.ArrayDeque;
import java.util.Deque;

import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.ignorelist.IgnoredObject.AddStatus;

/**
 * Single implementation of the ignore list rules as they apply to a diff tree.
 * <p>
 * Every place that hides objects shares it: {@link DiffTree} drops hidden
 * objects while the tree is created, {@link TreeFlattener} drops them again
 * while the tree is flattened for script generation, {@link ChildVisibility}
 * answers for objects that never became a tree node at all, and
 * {@link ColumnVisibility} answers for the columns a migration stops managing.
 * Because there is exactly one implementation, the passes cannot drift apart.
 * <p>
 * The filter is stateful: {@link #getStatus(IgnoreRuleTarget)} depends on the
 * {@code CONTENT} subtree roots entered so far, so a single instance must be
 * used for one depth-first walk and elements must be visited in tree order.
 */
final class IgnoreListFilter {

    private final IgnoreList ignoreList;
    private final String[] dbNames;
    private final boolean unknownDbScope;

    private final Deque<IgnoreRuleTarget> addSubtreeRoots = new ArrayDeque<>();

    /**
     * The rule the last answer came from, {@code null} when no rule matched and
     * the answer was the default of the list. Valid immediately after
     * {@link #getStatus(IgnoreRuleTarget)} or
     * {@link #getMatchedStatus(IgnoreRuleTarget)} and until the next call.
     * <p>
     * Kept in a field rather than returned, because it is asked for only where
     * an element really is hidden - a handful of times in a walk that asks for a
     * status once per node - and a pair returned from every one of those calls
     * would allocate for every node of the tree to serve those few.
     */
    private IgnoredObject decidedBy;

    /**
     * Creates a filter that resolves rules exactly as they are written.
     *
     * @param ignoreList the ignore list to apply
     * @param dbNames    database names matched against the {@code db=} rule
     *                   attribute, may be {@code null} when unknown
     */
    IgnoreListFilter(IgnoreList ignoreList, String... dbNames) {
        this(ignoreList, false, dbNames);
    }

    /**
     * Creates a filter, optionally treating database-scoped rules as undecidable.
     *
     * @param ignoreList     the ignore list to apply
     * @param unknownDbScope when {@code true}, an element matched by the name and
     *                       type of a {@code db=} rule is always kept, because the
     *                       database name that would decide the rule is not known
     *                       here and a later pass that does know it may keep the
     *                       element
     * @param dbNames        database names matched against the {@code db=} rule
     *                       attribute, may be {@code null} when unknown
     */
    IgnoreListFilter(IgnoreList ignoreList, boolean unknownDbScope, String... dbNames) {
        this.ignoreList = ignoreList;
        this.unknownDbScope = unknownDbScope;
        this.dbNames = dbNames;
    }

    /**
     * Reports whether an ignore list can hide an object at all: a missing list,
     * and a black list without rules, show everything.
     *
     * @param ignoreList the list to inspect, may be {@code null}
     * @return true when at least one object can be hidden
     */
    static boolean hidesAnything(IgnoreList ignoreList) {
        return ignoreList != null && !(ignoreList.isShow() && ignoreList.getList().isEmpty());
    }

    /**
     * Determines the add status for an object based on ignore rules.
     * Evaluates all matching rules and applies precedence logic.
     *
     * @param el the object to evaluate
     * @return the final add status for the object
     */
    AddStatus getStatus(IgnoreRuleTarget el) {
        AddStatus status = getMatchedStatus(el);
        if (status != null) {
            return status;
        }

        return !addSubtreeRoots.isEmpty() || ignoreList.isShow() ? AddStatus.ADD : AddStatus.SKIP;
    }

    /**
     * Determines the add status the rules themselves give an object, leaving the
     * answer open when none of them mentions it.
     * <p>
     * {@link #getStatus(IgnoreRuleTarget)} closes that answer with the default of
     * the list - a white list hides what it does not name. A caller for whom that
     * default is wrong asks here instead: {@link ColumnVisibility} does, because
     * a column no rule names stays managed whatever the mode of the list.
     *
     * @param el the object to evaluate
     * @return the status the matching rules agree on, or {@code null} when no
     * rule matched the object
     */
    AddStatus getMatchedStatus(IgnoreRuleTarget el) {
        AddStatus status = null;
        decidedBy = null;
        for (IgnoredObject rule : ignoreList.getList()) {
            if (isUndecidable(rule, el)) {
                decidedBy = rule;
                return AddStatus.ADD_SUBTREE;
            }
            if (!match(rule, el)) {
                continue;
            }
            AddStatus newStatus = rule.getAddStatus();
            if (status == null) {
                status = newStatus;
                decidedBy = rule;
            } else if ((status == AddStatus.ADD || status == AddStatus.SKIP) &&
                    (newStatus == AddStatus.ADD_SUBTREE || newStatus == AddStatus.SKIP_SUBTREE)) {
                // use wider rule
                status = newStatus;
                decidedBy = rule;
            } else if (status == AddStatus.ADD && newStatus == AddStatus.SKIP ||
                    status == AddStatus.ADD_SUBTREE && newStatus == AddStatus.SKIP_SUBTREE) {
                // use hiding rule
                status = newStatus;
                decidedBy = rule;
            }
        }

        return status;
    }

    /**
     * The rule the last answer came from.
     *
     * @return the rule that decided the status last asked for, or {@code null}
     * when none of them mentioned the element and the answer was the default of
     * the list - which for a white list is itself a reason to hide
     */
    IgnoredObject decidedBy() {
        return decidedBy;
    }

    /**
     * Enters the subtree of an element whose status is {@link AddStatus#ADD_SUBTREE},
     * so that its descendants default to being added.
     *
     * @param el the subtree root
     */
    void enterSubtree(IgnoreRuleTarget el) {
        addSubtreeRoots.push(el);
    }

    /**
     * Leaves the innermost subtree entered by {@link #enterSubtree(IgnoreRuleTarget)}.
     */
    void leaveSubtree() {
        addSubtreeRoots.pop();
    }

    /**
     * Reports whether a rule cannot be decided here because it is scoped to a
     * database name this filter was not given.
     */
    private boolean isUndecidable(IgnoredObject rule, IgnoreRuleTarget el) {
        return unknownDbScope && rule.getDbRegex() != null && matchesNameAndType(rule, el);
    }

    /**
     * Checks if this ignore rule matches the given object and database names.
     *
     * @param rule the rule to test
     * @param el   the object to match against
     * @return true if the rule matches the object
     */
    private boolean match(IgnoredObject rule, IgnoreRuleTarget el) {
        if (!matchesNameAndType(rule, el)) {
            return false;
        }

        var pattern = rule.getDbRegex();
        if (pattern == null) {
            return true;
        }
        if (dbNames == null) {
            return false;
        }
        for (String dbName : dbNames) {
            if (dbName != null && pattern.matcher(dbName).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesNameAndType(IgnoredObject rule, IgnoreRuleTarget el) {
        if (!rule.match(rule.isQualified() ? el.getQualifiedName() : el.getName())) {
            return false;
        }

        var objTypes = rule.getObjTypes();
        return objTypes.isEmpty() || objTypes.contains(el.getType());
    }
}
