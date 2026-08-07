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

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject.AddStatus;

/**
 * Tells which children of one container an ignore list keeps visible.
 * <p>
 * {@link DiffTree} and {@link TreeFlattener} answer the same question while they
 * walk a whole tree, and both drop what they hide. A consumer that renders a
 * single object cannot walk anything: it holds one node of an already filtered
 * tree and reads the children of that object straight from the loaded model,
 * where nothing was ever dropped and where a hidden child has no tree node to be
 * recognised by. This class gives that consumer the very same answer, computed
 * by the very same {@link IgnoreListFilter}, so a second reading of the rules
 * never has to be written.
 * <p>
 * An instance is bound to one container. Creating it replays the rules along the
 * path from the root of the tree down to that container, because a
 * {@code CONTENT} rule on an ancestor decides what its descendants default to.
 * The instance is immutable afterwards and answers in any order.
 */
public final class ChildVisibility {

    /** No list, or a black list without rules: nothing can be hidden. */
    private static final ChildVisibility ALL_VISIBLE = new ChildVisibility(null, "", true);

    /** An ancestor of the container is hidden with its content. */
    private static final ChildVisibility NONE_VISIBLE = new ChildVisibility(null, "", false);

    /** {@code null} when the answer is the same for every child. */
    private final IgnoreListFilter filter;

    /** The qualified name a {@code QUALIFIED} rule matches children against. */
    private final String containerQName;

    private final boolean answerWithoutRules;

    private ChildVisibility(IgnoreListFilter filter, String containerQName, boolean answerWithoutRules) {
        this.filter = filter;
        this.containerQName = containerQName;
        this.answerWithoutRules = answerWithoutRules;
    }

    /**
     * Binds the ignore list to one container of a diff tree.
     *
     * @param ignoreList the ignore list to apply, may be {@code null}
     * @param container  the container whose children are asked about, may be
     *                   {@code null} for "no container, nothing to hide"
     * @param dbNames    database names matched against the {@code db=} rule
     *                   attribute; a rule scoped to a database is decided here
     *                   rather than deferred, so pass the names the object
     *                   selection is resolved with
     * @return visibility of the children of that container
     */
    public static ChildVisibility of(IgnoreList ignoreList, TreeElement container, String... dbNames) {
        if (container == null || !IgnoreListFilter.hidesAnything(ignoreList)) {
            return ALL_VISIBLE;
        }

        IgnoreListFilter filter = new IgnoreListFilter(ignoreList, dbNames);
        for (TreeElement ancestor : pathFromRoot(container)) {
            AddStatus status = filter.getStatus(ancestor);
            if (status == AddStatus.SKIP_SUBTREE) {
                return NONE_VISIBLE;
            }
            if (status == AddStatus.ADD_SUBTREE) {
                filter.enterSubtree(ancestor);
            }
        }

        return new ChildVisibility(filter, qualifiedNameOf(container), false);
    }

    /**
     * Reports whether a child of the container is visible.
     *
     * @param child a child read from a loaded model, which may have no node in
     *              the diff tree at all
     * @return true when the ignore list keeps the child
     */
    public boolean isVisible(IStatement child) {
        if (filter == null) {
            return answerWithoutRules;
        }
        String name = child.getName();
        return isKept(new Child(name, qualify(name), child.getStatementType()));
    }

    /**
     * Reports whether a child of the container is visible.
     *
     * @param child a child node of the container in the diff tree
     * @return true when the ignore list keeps the child
     */
    public boolean isVisible(TreeElement child) {
        return filter == null ? answerWithoutRules : isKept(child);
    }

    private boolean isKept(IgnoreRuleTarget child) {
        AddStatus status = filter.getStatus(child);
        return status == AddStatus.ADD || status == AddStatus.ADD_SUBTREE;
    }

    /**
     * Builds the child name exactly as {@link TreeElement#getQualifiedName()}
     * would build it, so a {@code QUALIFIED} rule matches a rendered child and
     * its tree node alike.
     */
    private String qualify(String childName) {
        return containerQName.isEmpty() ? childName : containerQName + '.' + childName;
    }

    /**
     * The qualified name of the container as its children see it: the artificial
     * database root contributes nothing to a qualified name.
     */
    private static String qualifiedNameOf(TreeElement container) {
        return container.getType() == DbObjType.DATABASE ? "" : container.getQualifiedName();
    }

    /**
     * The container and its ancestors, root first, in the order the tree passes
     * visit them.
     */
    private static Deque<TreeElement> pathFromRoot(TreeElement container) {
        Deque<TreeElement> path = new ArrayDeque<>();
        for (TreeElement el = container; el != null; el = el.getParent()) {
            path.push(el);
        }
        return path;
    }

    /**
     * A child of the container that has no tree node of its own.
     */
    private record Child(String name, String qualifiedName, DbObjType type) implements IgnoreRuleTarget {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getQualifiedName() {
            return qualifiedName;
        }

        @Override
        public DbObjType getType() {
            return type;
        }
    }
}
