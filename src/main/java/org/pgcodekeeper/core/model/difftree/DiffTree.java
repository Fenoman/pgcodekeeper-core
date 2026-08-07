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
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IColumn;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.IStatementContainer;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.diff.Comparison;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.ignorelist.IgnoredObject;
import org.pgcodekeeper.core.ignorelist.IgnoredObject.AddStatus;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.PhaseTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for creating and managing diff trees that represent
 * differences between database schemas.
 */
public final class DiffTree {

    private static final Logger LOG = LoggerFactory.getLogger(DiffTree.class);

    /**
     * Creates a diff tree comparing two database schemas.
     *
     * @param settings the compare settings
     * @param left     the left (old) database schema
     * @param right    the right (new) database schema
     * @return the root TreeElement representing the diff tree
     * @throws InterruptedException if the operation is interrupted
     */
    public static TreeElement create(ISettings settings, IDatabase left, IDatabase right)
            throws InterruptedException {
        return create(settings, left, right, null);
    }

    /**
     * Creates a diff tree comparing two database schemas with progress monitoring.
     *
     * @param settings the compare settings
     * @param left     the left (old) database schema
     * @param right    the right (new) database schema
     * @param monitor  the progress monitor for tracking operation progress
     * @return the root TreeElement representing the diff tree
     * @throws InterruptedException if the operation is interrupted
     */
    public static TreeElement create(ISettings settings, IDatabase left, IDatabase right, IMonitor monitor)
            throws InterruptedException {
        long start = PhaseTimer.start();
        TreeElement root = new DiffTree(settings, monitor).createTree(left, right);
        PhaseTimer.end("difftree_create", start);
        return root;
    }

    /**
     * Adds column differences to the tree element list, managing every column.
     *
     * @param left   the left (old) column list
     * @param right  the right (new) column list
     * @param parent the parent tree element
     * @param list   the list to add column differences to
     */
    public static void addColumns(Collection<IColumn> left, Collection<IColumn> right,
                                  TreeElement parent, List<TreeElement> list) {
        addColumns(left, right, parent, list, ColumnVisibility.all());
    }

    /**
     * Adds the differences of the managed columns to the tree element list.
     * <p>
     * A hidden column produces no element, which is the whole of what keeps it
     * out of a migration: an element is the only way a column ever reaches the
     * script, so a column without one is never added, dropped or altered. The
     * decision is made by name and type alone, so it does not depend on which
     * of the two states was handed over as the left one - and the callers
     * disagree about that, see {@code DiffTableViewer}.
     *
     * @param left    the left (old) column list
     * @param right   the right (new) column list
     * @param parent  the parent tree element
     * @param list    the list to add column differences to
     * @param managed the columns the migration manages
     */
    public static void addColumns(Collection<IColumn> left, Collection<IColumn> right,
                                  TreeElement parent, List<TreeElement> list, ColumnVisibility managed) {
        Map<String, IColumn> leftByName = new HashMap<>();
        for (IColumn column : left) {
            leftByName.putIfAbsent(column.getName(), column);
        }

        Map<String, IColumn> rightByName = new HashMap<>();
        for (IColumn column : right) {
            rightByName.putIfAbsent(column.getName(), column);
        }

        for (IColumn sLeft : left) {
            IColumn foundRight = rightByName.get(sLeft.getName());

            if (!sLeft.equals(foundRight) && !managed.isHidden(sLeft)) {
                TreeElement col = new TreeElement(sLeft, foundRight != null ? DiffSide.BOTH : DiffSide.LEFT);
                col.setParent(parent);
                list.add(col);
            }
        }

        for (IColumn sRight : right) {
            if (!leftByName.containsKey(sRight.getName()) && !managed.isHidden(sRight)) {
                TreeElement col = new TreeElement(sRight, DiffSide.RIGHT);
                col.setParent(parent);
                list.add(col);
            }
        }
    }

    /**
     * Gets tables that have changed columns from the selected elements.
     *
     * @param oldDbFull the old database schema
     * @param newDbFull the new database schema
     * @param selected  the list of selected tree elements
     * @return a set of table elements that have changed columns
     */
    public static Set<TreeElement> getTablesWithChangedColumns(
            IDatabase oldDbFull, IDatabase newDbFull, List<TreeElement> selected) {
        return getTablesWithChangedColumns(oldDbFull, newDbFull, selected, ColumnVisibility.all());
    }

    /**
     * Gets tables that have a changed column the migration manages.
     *
     * @param oldDbFull the old database schema
     * @param newDbFull the new database schema
     * @param selected  the list of selected tree elements
     * @param managed   the columns the migration manages
     * @return a set of table elements that have changed columns
     */
    public static Set<TreeElement> getTablesWithChangedColumns(
            IDatabase oldDbFull, IDatabase newDbFull, List<TreeElement> selected, ColumnVisibility managed) {

        Set<TreeElement> tables = new HashSet<>();
        for (TreeElement el : selected) {
            if (el.getType() == DbObjType.TABLE) {
                List<TreeElement> columns = new ArrayList<>();
                DiffSide side = el.getSide();

                ITable oldTbl = side == DiffSide.LEFT || side == DiffSide.BOTH
                        ? (ITable) el.getStatement(oldDbFull) : null;
                Collection<IColumn> oldColumns = oldTbl == null ? Collections.emptyList() : oldTbl.getColumns();

                ITable newTbl = side == DiffSide.RIGHT || side == DiffSide.BOTH
                        ? (ITable) el.getStatement(newDbFull) : null;
                Collection<IColumn> newColumns = newTbl == null ? Collections.emptyList() : newTbl.getColumns();

                addColumns(oldColumns, newColumns, el, columns, managed.forPair(oldTbl, newTbl));

                if (!columns.isEmpty()) {
                    tables.add(el);
                }
            }
        }

        return tables;
    }

    private final IMonitor monitor;
    private final ISettings settings;

    /**
     * True when the ignore list of this run can hide something, so that the
     * bookkeeping the hiding pass needs is only paid for when it is used.
     */
    private final boolean hiding;

    /**
     * Elements that are in the tree only because some of their children differ;
     * the objects themselves are equal. Identity based on purpose: tree elements
     * compare by name and position, and several of them may be equal.
     */
    private final Set<TreeElement> childrenOnlyDiffs =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private DiffTree(ISettings settings, IMonitor monitor) {
        this.settings = settings;
        this.monitor = monitor;
        this.hiding = IgnoreListFilter.hidesAnything(settings.getIgnoreList());
    }

    /**
     * Creates a diff tree by comparing two database schemas and building a hierarchical
     * tree structure representing the differences between them.
     *
     * @param left  the left (old) database schema to compare
     * @param right the right (new) database schema to compare
     * @return the root TreeElement representing the complete diff tree with "Database"
     * as the root node and all schema differences as child nodes
     * @throws InterruptedException if the operation is cancelled via the progress monitor
     */
    public TreeElement createTree(IDatabase left, IDatabase right) throws InterruptedException {
        IMonitor.checkCancelled(monitor);
        TreeElement db = new TreeElement("Database", DbObjType.DATABASE, DiffSide.BOTH);
        addChildren(left, right, db);
        applyIgnoreList(db);

        return db;
    }

    /**
     * Drops the objects hidden by the ignore list from a freshly built tree, so
     * that every consumer of the tree - script generation, project export and the
     * object selection offered in the UI - sees the same objects.
     * <p>
     * Only objects that script generation would drop anyway are removed. The
     * decision uses {@link IgnoreListFilter}, the same implementation
     * {@link TreeFlattener} uses, resolved without a database name exactly like
     * {@code AbstractScriptBuilder} resolves it. An object hidden by itself but
     * holding visible descendants is kept, because a tree can only drop a node
     * together with its subtree; the flattener drops it again on the way to the
     * script.
     * <p>
     * The pass also drops the containers this hiding empties: an object that is
     * in the tree only because some of its children differ, and whose differing
     * children are all hidden, is not a change at all. Such a container never
     * reaches the script - it produces no statement of its own and it has no
     * visible child left to produce one - so removing it leaves the generated
     * script byte for byte the same while it stops the UI from offering a
     * change that can never be migrated.
     * <p>
     * What the pass takes away it also counts, see {@link HiddenObjects}, so
     * that a reader of the comparison can be told how much of it the rules are
     * holding back. The counting rides this walk and adds no walk of its own,
     * and it counts the two removals apart: an object a rule named is hidden,
     * while a container emptied by that hiding is an unchanged object with
     * nothing left to show and is nobody's doing.
     *
     * @param root the root of the tree to filter in place
     * @throws InterruptedException if the operation is cancelled via the progress monitor
     */
    private void applyIgnoreList(TreeElement root) throws InterruptedException {
        if (!hiding) {
            // nothing is hidden: a black list without rules shows everything
            return;
        }
        IgnoreList ignoreList = settings.getIgnoreList();
        HiddenObjects.Recorder hidden = settings.getHiddenObjects()
                .recorder(ignoreList, HiddenObjects.Pass.TREE);

        long start = PhaseTimer.start();
        if (!prune(root, new IgnoreListFilter(ignoreList, true, (String[]) null), hidden)) {
            // the root itself is hidden with its content: nothing survives
            root.retainChildren(List.of());
        }
        hidden.publish();
        PhaseTimer.end("difftree_ignore_list", start);
    }

    /**
     * Filters the subtree of an element in place.
     *
     * @return true if the element survives, either on its own or because a
     * descendant of it survives
     */
    private boolean prune(TreeElement el, IgnoreListFilter filter, HiddenObjects.Recorder hidden)
            throws InterruptedException {
        IMonitor.checkCancelled(monitor);
        AddStatus status = filter.getStatus(el);
        if (status == AddStatus.SKIP_SUBTREE) {
            logIgnored(el);
            hidden.hidWithSubtree(el, filter.decidedBy());
            return false;
        }

        boolean subtreeRoot = status == AddStatus.ADD_SUBTREE;
        IgnoredObject decidedBy = filter.decidedBy();
        if (subtreeRoot) {
            filter.enterSubtree(el);
        }
        pruneChildren(el, filter, hidden);
        if (subtreeRoot) {
            filter.leaveSubtree();
        }

        if (el.hasChildren()) {
            return true;
        }
        if (status == AddStatus.SKIP) {
            logIgnored(el);
            // nothing is left under it, so the node is all that goes
            hidden.hid(el, decidedBy);
            return false;
        }
        if (childrenOnlyDiffs.contains(el)) {
            // the object itself is unchanged and every differing child is gone
            logEmptied(el);
            return false;
        }
        return true;
    }

    private void pruneChildren(TreeElement el, IgnoreListFilter filter, HiddenObjects.Recorder hidden)
            throws InterruptedException {
        // a live view: read fully before the children are replaced below
        List<TreeElement> children = el.getChildren();
        int size = children.size();
        List<TreeElement> survivors = null;
        for (int i = 0; i < size; i++) {
            TreeElement child = children.get(i);
            if (prune(child, filter, hidden)) {
                if (survivors != null) {
                    survivors.add(child);
                }
            } else if (survivors == null) {
                survivors = new ArrayList<>(children.subList(0, i));
            }
        }

        if (survivors != null) {
            el.retainChildren(survivors);
        }
    }

    private static void logIgnored(TreeElement el) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(Messages.DiffTree_log_ignore_obj.formatted(el.getQualifiedName()));
        }
    }

    private static void logEmptied(TreeElement el) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(Messages.DiffTree_log_hidden_children_only.formatted(el.getQualifiedName()));
        }
    }

    private void addChildren(IStatementContainer left, IStatementContainer right, TreeElement parent)
            throws InterruptedException {
        for (CompareResult res : compareStatements(left, right)) {
            IMonitor.checkCancelled(monitor);
            TreeElement child = new TreeElement(res.getStatement(), res.getSide());
            parent.addChild(child);
            if (res.childrenOnly()) {
                childrenOnlyDiffs.add(child);
            }

            if (res.hasChildren()) {
                addChildren((IStatementContainer) res.left(), (IStatementContainer) res.right(), child);
            }
        }
    }

    /**
     * Compare lists and put elements onto appropriate sides.
     */
    private List<CompareResult> compareStatements(IStatementContainer left, IStatementContainer right) {
        List<CompareResult> rv = new ArrayList<>();

        // add LEFT and BOTH here
        // and RIGHT in a separate pass
        if (left != null) {
            left.getChildren().forEach(sLeft -> {
                IStatement foundRight = null;
                if (right != null) {
                    foundRight = right.getChild(sLeft.getName(), sLeft.getStatementType());
                }

                if (foundRight == null) {
                    rv.add(new CompareResult(sLeft, null, false));
                } else if (!Comparison.compare(settings, sLeft, foundRight)) {
                    // the extra comparison is only worth its cost when the
                    // hiding pass below can act on its answer
                    rv.add(new CompareResult(sLeft, foundRight,
                            hiding && Comparison.differsInChildrenOnly(settings, sLeft, foundRight)));
                }
            });
        }

        if (right != null) {
            right.getChildren().forEach(sRight -> {
                if (left == null || left.getChild(sRight.getName(), sRight.getStatementType()) == null) {
                    rv.add(new CompareResult(null, sRight, false));
                }
            });
        }

        return rv;
    }
}

/**
 * Represents the result of comparing two database statements during diff tree creation.
 * Contains references to the left and right statements and provides methods to
 * determine the comparison side and retrieve statement information.
 *
 * @param childrenOnly true when the two statements differ in their children
 *                     alone, so the object itself contributes nothing to a
 *                     migration script
 */
record CompareResult(IStatement left, IStatement right, boolean childrenOnly) {

    /**
     * Determines which side of the comparison this result represents.
     *
     * @return the diff side (LEFT, RIGHT, or BOTH)
     * @throws IllegalStateException if both sides are null
     */
    public DiffSide getSide() {
        if (left != null && right != null) {
            return DiffSide.BOTH;
        }
        if (left != null) {
            return DiffSide.LEFT;
        }
        if (right != null) {
            return DiffSide.RIGHT;
        }
        throw new IllegalStateException(Messages.DiffTree_both_diff_sides_are_null);
    }

    /**
     * Gets the statement from this comparison result.
     * Returns the left statement if available, otherwise the right statement.
     *
     * @return the statement from this comparison
     * @throws IllegalStateException if both sides are null
     */
    public IStatement getStatement() {
        if (left != null) {
            return left;
        }
        if (right != null) {
            return right;
        }
        throw new IllegalStateException(Messages.DiffTree_both_diff_sides_are_null);
    }

    /**
     * Checks if this comparison result has child statements.
     *
     * @return true if either the left or right statement has children, false otherwise
     */
    public boolean hasChildren() {
        if (left != null && left.hasChildren()) {
            return true;
        }

        return right != null && right.hasChildren();
    }
}
