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
package org.pgcodekeeper.core.model.graph;

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.pg.schema.PgAbstractFunction;
import org.pgcodekeeper.core.dependencieslist.Dependency;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;

import java.util.*;

/**
 * Finds dependent elements in tree based on user selection using dependency resolution mechanism.
 *
 * @author botov_av
 */
public final class DepcyTreeExtender {

    private final IDatabase dbSource;
    private final IDatabase dbTarget;
    private final SimpleDepcyResolver depRes;
    private final TreeElement root;
    /**
     * Elements selected by user for deployment to Project
     */
    private final List<TreeElement> userSelection;
    /**
     * Dependent elements from created/edited objects (contain user selection)
     */
    private final List<TreeElement> treeDepcyNewEdit = new ArrayList<>();
    /**
     * Dependent elements from deleted objects (contain user selection)
     */
    private final List<TreeElement> treeDepcyDelete = new ArrayList<>();
    /**
     * Set when a closure was collected through a routine whose body was not
     * analyzed, i.e. the closure may stop short at that routine
     */
    private boolean bodyDependencyTruncated;

    /**
     * Creates a new dependency tree extender.
     *
     * @param dbSource               source database schema
     * @param dbTarget               target database schema
     * @param root                   root element of the tree to analyze
     * @param additionalDependencies list of additional dependencies
     */
    public DepcyTreeExtender(IDatabase dbSource, IDatabase dbTarget, TreeElement root,
            List<Dependency> additionalDependencies) {
        this.dbSource = dbSource;
        this.dbTarget = dbTarget;
        this.root = root;
        userSelection = new TreeFlattener().onlySelected().flatten(root);
        depRes = new SimpleDepcyResolver(dbSource, dbTarget, false, additionalDependencies);
    }

    /**
     * For edited state or created object, pulls dependencies from above
     * for creating or modifying the object
     */
    private void fillDepcyOfNewEdit() {
        IStatement markedToCreate;
        Set<IStatement> newEditDepcy = new HashSet<>();
        for (TreeElement sel : userSelection) {
            if (sel.getSide() != DiffSide.LEFT
                    && (markedToCreate = sel.getStatement(dbTarget)) != null) {
                newEditDepcy.addAll(depRes.getCreateDepcies(markedToCreate));
            }
        }
        markBodyDependencyTruncation(newEditDepcy, dbTarget);
        fillTreeDepcies(treeDepcyNewEdit, newEditDepcy);
    }

    /**
     * When deleting an object, pulls dependencies from below
     */
    private void fillDepcyOfDeleted() {
        IStatement markedToDelete;
        Set<IStatement> deleteDepcy = new HashSet<>();
        for (TreeElement sel : userSelection) {
            if (sel.getSide() == DiffSide.LEFT
                    && sel.getType() != DbObjType.SEQUENCE
                    && (markedToDelete = sel.getStatement(dbSource)) != null) {
                deleteDepcy.addAll(depRes.getDropDepcies(markedToDelete));
            }
        }
        markBodyDependencyTruncation(deleteDepcy, dbSource);
        fillTreeDepcies(treeDepcyDelete, deleteDepcy);
    }

    /**
     * Detects a closure that stops short at a routine whose body was not
     * analyzed: such a routine contributes no body-derived edges, so nothing
     * reachable only through its body is in the collected closure.
     * <p>
     * Must run on the raw resolver output, before {@link #fillTreeDepcies}:
     * that filter drops elements equal on both sides, which is the usual state
     * of a routine matched by its body, so the truncation point itself would
     * be invisible in the resulting tree.
     * <p>
     * The resolver traverses a deep copy of the database and the suppressed
     * state is deliberately not copied along with the statement, so every
     * candidate is resolved back to its analyzed twin before being read.
     *
     * @param dependencies statements traversed while collecting the closure
     * @param analyzed     database the closure was collected from
     */
    private void markBodyDependencyTruncation(Collection<IStatement> dependencies, IDatabase analyzed) {
        if (bodyDependencyTruncated) {
            return;
        }
        for (IStatement depcy : dependencies) {
            if (depcy instanceof PgAbstractFunction
                    && depcy.getTwin(analyzed) instanceof PgAbstractFunction routine
                    && routine.isBodyDependencyStateSuppressed()) {
                bodyDependencyTruncated = true;
                return;
            }
        }
    }

    /**
     * Extracts objects from tree for dependencies
     *
     * @param treeDepcy list to add dependent tree elements to
     * @param dependencies collection of database statement dependencies
     */
    private void fillTreeDepcies(List<TreeElement> treeDepcy, Collection<IStatement> dependencies) {
        for (IStatement depcy : dependencies) {
            TreeElement finded = root.findElement(depcy);
            if (finded != null) {
                if (finded.getSide() == DiffSide.BOTH) {
                    if (!finded.getStatement(dbSource).compare(finded.getStatement(dbTarget))) {
                        treeDepcy.add(finded);
                    }
                } else {
                    treeDepcy.add(finded);
                }
            }
        }
    }

    /**
     * Returns all dependent elements based on user selection.
     * Analyzes both create/edit and delete dependencies.
     *
     * @return set of dependent elements excluding user-selected objects
     */
    public Set<TreeElement> getDepcies() {
        Set<TreeElement> res = new HashSet<>();
        fillDepcyOfNewEdit();
        fillDepcyOfDeleted();
        res.addAll(treeDepcyNewEdit);
        res.addAll(treeDepcyDelete);
        // remove all objects selected by user
        userSelection.forEach(res::remove);
        return res;
    }

    /**
     * Returns whether the closures collected by {@link #getDepcies()} traversed
     * a routine whose body was not analyzed, i.e. whether the returned
     * dependencies may be incomplete beyond that routine. Meaningful only after
     * {@link #getDepcies()} has been called.
     *
     * @return true if a closure was truncated at a routine with an unanalyzed body
     */
    public boolean isBodyDependencyTruncated() {
        return bodyDependencyTruncated;
    }
}
