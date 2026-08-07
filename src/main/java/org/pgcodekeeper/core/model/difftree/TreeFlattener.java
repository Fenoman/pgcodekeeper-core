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

import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.ignorelist.IgnoredObject.AddStatus;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Utility class for flattening tree structures with filtering capabilities.
 * Provides methods to filter tree elements based on selection status, edit state,
 * ignore lists, and object types while maintaining proper hierarchy traversal.
 * <p>
 * This is the second of the two passes that hide objects, and the only one that
 * knows the name of the database, so it is where a rule scoped with {@code db=}
 * is finally decided. A caller about to show the result to somebody may ask for
 * what it leaves out to be counted, see {@link #countHiddenInto(HiddenObjects)}.
 */
public final class TreeFlattener {

    private static final Logger LOG = LoggerFactory.getLogger(TreeFlattener.class);

    private boolean onlySelected;
    private boolean onlyEdits;
    private IDatabase dbSource;
    private IDatabase dbTarget;
    private IgnoreList ignoreList;
    private String[] dbNames;
    private Collection<DbObjType> onlyTypes;
    private HiddenObjects hiddenObjects = HiddenObjects.NONE;

    private final List<TreeElement> result = new ArrayList<>();
    private IgnoreListFilter filter;
    private HiddenObjects.Recorder hidden;

    /**
     * Configures the flattener to include only selected elements.
     *
     * @return this TreeFlattener for method chaining
     */
    public TreeFlattener onlySelected() {
        onlySelected = true;
        return this;
    }

    /**
     * Configures whether to include only selected elements.
     *
     * @param onlySelected true to include only selected elements
     * @return this TreeFlattener for method chaining
     */
    public TreeFlattener onlySelected(boolean onlySelected) {
        this.onlySelected = onlySelected;
        return this;
    }

    /**
     * Configures the flattener to include only edited elements.
     *
     * @param dbSource source database for comparison
     * @param dbTarget target database for comparison
     * @return this TreeFlattener for method chaining
     */
    public TreeFlattener onlyEdits(IDatabase dbSource, IDatabase dbTarget) {
        onlyEdits = dbSource != null && dbTarget != null;
        this.dbSource = dbSource;
        this.dbTarget = dbTarget;
        return this;
    }

    /**
     * Configures the flattener to use an ignore list for filtering.
     *
     * @param ignoreList the ignore list to apply
     * @return this TreeFlattener for method chaining
     */
    public TreeFlattener useIgnoreList(IgnoreList ignoreList) {
        return useIgnoreList(ignoreList, (String[]) null);
    }

    /**
     * Configures the flattener to use an ignore list with database name filtering.
     *
     * @param ignoreList the ignore list to apply
     * @param dbNames    database names for rule matching
     * @return this TreeFlattener for method chaining
     */
    public TreeFlattener useIgnoreList(IgnoreList ignoreList, String... dbNames) {
        this.ignoreList = ignoreList;
        this.dbNames = dbNames;
        return this;
    }

    /**
     * Configures the flattener to include only specific object types.
     *
     * @param onlyTypes collection of object types to include
     * @return this TreeFlattener for method chaining
     */
    public TreeFlattener onlyTypes(Collection<DbObjType> onlyTypes) {
        this.onlyTypes = onlyTypes;
        return this;
    }

    /**
     * Asks this flattening to count the objects its ignore list leaves out, for
     * a caller that is about to show the result to somebody, see
     * {@link HiddenObjects}.
     * <p>
     * Opt in, and deliberately so. A tree is flattened on the way to a script,
     * on the way to an export and on the way to the screen, all three with the
     * same rules but not always the same database names, and only the one that
     * feeds the screen describes what a reader is looking at. Counting every
     * flattening into one holder would let the last one to run decide the number
     * the first one earned.
     *
     * @param hiddenObjects the holder of the operation, see
     *                      {@code ISettings.getHiddenObjects()}
     * @return this TreeFlattener for method chaining
     */
    public TreeFlattener countHiddenInto(HiddenObjects hiddenObjects) {
        this.hiddenObjects = hiddenObjects == null ? HiddenObjects.NONE : hiddenObjects;
        return this;
    }

    /**
     * Flattens the tree structure applying all configured filters.
     *
     * @param root the root element to start flattening from
     * @return list of filtered tree elements
     */
    public List<TreeElement> flatten(TreeElement root) {
        result.clear();
        filter = ignoreList == null ? null : new IgnoreListFilter(ignoreList, dbNames);
        hidden = hiddenObjects.recorder(ignoreList, HiddenObjects.Pass.LIST);
        LOG.info(Messages.TreeFlattener_log_filter_obj);
        recurse(root);
        hidden.publish();
        return result;
    }

    private void recurse(TreeElement el) {
        AddStatus status = filter != null ? filter.getStatus(el) : AddStatus.ADD;

        if (status == AddStatus.SKIP) {
            var msg = Messages.TreeFlattener_log_ignore_obj.formatted(el.getName());
            LOG.debug(msg);
            // the children go on being visited below and may well be listed, so
            // this leaves out the object itself and nothing more
            hidden.hid(el, filter.decidedBy());
        }
        if (status == AddStatus.SKIP_SUBTREE) {
            hidden.hidWithSubtree(el, filter.decidedBy());
            if (LOG.isDebugEnabled()) {
                var msg = Messages.TreeFlattener_log_ignore_obj.formatted(el.getName());
                LOG.debug(msg);
                writeChildrenInLog(el);
            }
            return;
        }

        if (status == AddStatus.ADD_SUBTREE) {
            filter.enterSubtree(el);
        }
        for (TreeElement sub : el.getChildren()) {
            recurse(sub);
        }
        if (status == AddStatus.ADD_SUBTREE) {
            filter.leaveSubtree();
        }

        if (el.getType() == DbObjType.DATABASE) {
            return;
        }

        if ((status == AddStatus.ADD || status == AddStatus.ADD_SUBTREE)
                && (!onlySelected || el.isSelected())
                && (onlyTypes == null || onlyTypes.isEmpty() || onlyTypes.contains(el.getType()))
                && (!onlyEdits || el.getSide() != DiffSide.BOTH
                || !el.getStatement(dbSource).compare(el.getStatement(dbTarget)))) {
            result.add(el);
        }
    }

    private void writeChildrenInLog(TreeElement el) {
        for (TreeElement sub : el.getChildren()) {
            var msg = Messages.TreeFlattener_log_ignore_children.formatted(sub.getName());
            LOG.debug(msg);
            if (!sub.getChildren().isEmpty()) {
                writeChildrenInLog(sub);
            }
        }
    }
}
