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
 **
 * Copyright 2006 StartNet s.r.o.
 *
 * Distributed under MIT license
 *******************************************************************************/
package org.pgcodekeeper.core.database.base.script;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.api.script.IScriptBuilder;
import org.pgcodekeeper.core.dependencieslist.Dependency;
import org.pgcodekeeper.core.ignorelist.IgnoreList;
import org.pgcodekeeper.core.model.difftree.ColumnVisibility;
import org.pgcodekeeper.core.model.difftree.CompareTree;
import org.pgcodekeeper.core.model.difftree.DiffTree;
import org.pgcodekeeper.core.model.difftree.RecasedColumns;
import org.pgcodekeeper.core.model.difftree.TreeElement;
import org.pgcodekeeper.core.model.difftree.TreeFlattener;
import org.pgcodekeeper.core.model.difftree.TreeElement.DiffSide;
import org.pgcodekeeper.core.model.graph.ActionsToScriptConverter;
import org.pgcodekeeper.core.model.graph.DbObject;
import org.pgcodekeeper.core.model.graph.DepcyResolver;
import org.pgcodekeeper.core.model.graph.DepcyResolver.DepcyGraphs;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.PhaseTimer;

public abstract class AbstractScriptBuilder implements IScriptBuilder {

    private static final String EMPTY_SCRIPT = ""; // $NON-NLS-1$

    protected final ISettings settings;

    /**
     * Creates a new AbstractScriptBuilder instance with the specified settings.
     *
     * @param settings configuration settings
     */
    protected AbstractScriptBuilder(ISettings settings) {
        this.settings = settings;
    }

    @Override
    public String createScript(TreeElement root, IDatabase oldDb, IDatabase newDb) throws IOException {
        return createScript(root, oldDb, newDb, null);
    }

    @Override
    public String createScript(TreeElement root, IDatabase oldDb, IDatabase newDb,
                               Supplier<DepcyGraphs> sharedGraphs) throws IOException {
        long start = PhaseTimer.start();
        List<TreeElement> selected = getSelectedElements(root, settings.getIgnoreList());
        PhaseTimer.end("tree_flatten", start);
        if (selected.isEmpty()) {
            return EMPTY_SCRIPT;
        }
        reportPartiallyAppliedColumnRules(selected, oldDb, newDb);

        Set<IStatement> toRefresh = new LinkedHashSet<>();
        var resolved = resolveDependencies(selected, oldDb, newDb, settings.getAdditionalDependencies(),
                settings.getAdditionalDependencies(), toRefresh, sharedGraphs);
        if (resolved.actions().isEmpty()) {
            ActionsToScriptConverter.validateEmptyActions(oldDb, newDb, selected);
            return EMPTY_SCRIPT;
        }

        start = PhaseTimer.start();
        String script = getScript(resolved, toRefresh, selected, oldDb, newDb);
        PhaseTimer.end("script_build", start);
        return script;
    }

    private List<TreeElement> getSelectedElements(TreeElement root, IgnoreList ignoreList) {
        return new TreeFlattener()
                .onlySelected()
                .useIgnoreList(ignoreList)
                .onlyTypes(getSettings().getAllowedTypes())
                .flatten(root);
    }

    private DepcyResolver.ResolvedActions resolveDependencies(
            List<TreeElement> selected, IDatabase oldDb, IDatabase newDb,
            List<Dependency> additionalDependenciesOldDb,
            List<Dependency> additionalDependenciesNewDb, Set<IStatement> toRefresh,
            Supplier<DepcyGraphs> sharedGraphs) {
        addColumnsAsElements(oldDb, newDb, selected);

        selected.sort(new CompareTree());

        List<DbObject> objects = new ArrayList<>();
        for (TreeElement st : selected) {
            IStatement oldStatement = null;
            IStatement newStatement = null;
            switch (st.getSide()) {
            case LEFT:
                oldStatement = st.getStatement(oldDb);
                break;
            case BOTH:
                oldStatement = st.getStatement(oldDb);
                newStatement = st.getStatement(newDb);
                break;
            case RIGHT:
                newStatement = st.getStatement(newDb);
                break;
            }
            objects.add(new DbObject(oldStatement, newStatement));
        }
        return DepcyResolver.resolveActions(oldDb, newDb, additionalDependenciesOldDb, additionalDependenciesNewDb,
                toRefresh, objects, settings, sharedGraphs);
    }

    /**
     * Adds the columns of the selected tables to the selection, since the tree
     * itself never holds a column.
     * <p>
     * A hidden column is left out here, and this is the only thing anywhere that
     * keeps it out of a script: it produces no {@code ADD}, no {@code DROP} and
     * no {@code ALTER} because nothing downstream is ever told about it. The
     * generator is not told about the rules at all - it writes every column of
     * whatever table it is handed - so a column that got past this line would be
     * migrated in full. Hiding is this line.
     * <p>
     * This is also where a column becomes a {@code DROP} and another one a
     * {@code ADD}, so it is where a pair of them that is really one renamed
     * column can still be recognised as such - further down there is only a
     * removal and an addition of two unrelated columns, see
     * {@link RecasedColumns}.
     */
    private void addColumnsAsElements(IDatabase oldDb, IDatabase newDb, List<TreeElement> selected) {
        ColumnVisibility managed = ColumnVisibility.of(settings);
        boolean recasedIsRename = isRecasedColumnARename();
        List<TreeElement> tempColumns = new ArrayList<>();
        for (TreeElement el : selected) {
            if (el.getType() == DbObjType.TABLE && el.getSide() == DiffSide.BOTH) {
                ITable oldTbl = (ITable) el.getStatement(oldDb);
                ITable newTbl = (ITable) el.getStatement(newDb);
                ColumnVisibility pair = managed.forPair(oldTbl, newTbl);
                DiffTree.addColumns(oldTbl.getColumns(), newTbl.getColumns(), el, tempColumns, pair);
                if (recasedIsRename) {
                    RecasedColumns.report(oldTbl, newTbl, pair);
                }
            }
        }
        selected.addAll(tempColumns);
    }

    /**
     * Whether two column names of this dialect that differ in case alone are
     * one column an operator spelled twice rather than two columns of their own.
     * <p>
     * The verdict belongs to the dialect because the two dialects that answer
     * yes answer it for different reasons, and the third answers no. In
     * PostgreSQL an upper-case name survives only inside quotes, so a case-only
     * pair is something a person went out of their way to write. In MS SQL under
     * an ordinary collation the server does not tell such names apart at all, so
     * they cannot be two columns whatever was meant. Where neither holds - where
     * case is simply part of an ordinary name, as in ClickHouse - a case-only
     * pair carries no more meaning than any other pair of names, and treating it
     * as a rename would put a false warning on a plain removal, see
     * {@link RecasedColumns}.
     *
     * @return {@code true} unless the dialect names columns case-sensitively
     * without any act of quoting
     */
    protected boolean isRecasedColumnARename() {
        return true;
    }

    /**
     * Says out loud where a {@code type=COLUMN} rule did not apply.
     * <p>
     * The rule hides a column only while its table can spare it, so the same
     * rule may hide a column here and leave it alone there. Whoever reads the
     * output of a migration must be able to see which of the two happened and
     * why, without having to reconstruct it from the schema.
     */
    private void reportPartiallyAppliedColumnRules(List<TreeElement> selected, IDatabase oldDb, IDatabase newDb) {
        ColumnVisibility managed = ColumnVisibility.of(settings);
        if (!managed.hidesAnything()) {
            return;
        }

        for (TreeElement el : selected) {
            if (el.getType() != DbObjType.TABLE) {
                continue;
            }
            DiffSide side = el.getSide();
            ITable oldTbl = side == DiffSide.LEFT || side == DiffSide.BOTH
                    ? (ITable) el.getStatement(oldDb) : null;
            ITable newTbl = side == DiffSide.RIGHT || side == DiffSide.BOTH
                    ? (ITable) el.getStatement(newDb) : null;
            managed.reportPinnedColumns(oldTbl, newTbl);
        }
    }

    protected ISettings getSettings() {
        return settings;
    }

    protected abstract String getScript(DepcyResolver.ResolvedActions resolved, Set<IStatement> toRefresh,
                                        List<TreeElement> selected,
                                        IDatabase oldDb, IDatabase newDb) throws IOException;
}
