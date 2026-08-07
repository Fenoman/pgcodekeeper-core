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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.jgrapht.graph.DefaultEdge;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.schema.AbstractStatement;
import org.pgcodekeeper.core.database.ms.schema.MsAbstractFunction;
import org.pgcodekeeper.core.database.ms.schema.MsSourceStatement;
import org.pgcodekeeper.core.database.ms.schema.MsTable;
import org.pgcodekeeper.core.database.ms.schema.MsType;
import org.pgcodekeeper.core.database.ms.schema.MsView;
import org.pgcodekeeper.core.database.pg.schema.PgIndex;
import org.pgcodekeeper.core.database.pg.schema.PgSequence;
import org.pgcodekeeper.core.database.pg.schema.PgTypedTable;
import org.pgcodekeeper.core.dependencieslist.Dependency;
import org.pgcodekeeper.core.exception.MonitorCancelledRuntimeException;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.utils.DaemonThreadFactory;
import org.pgcodekeeper.core.utils.PhaseTimer;

/**
 * Core dependency resolution engine that determines database object changes required for schema migration.
 * Analyzes two database schemas and generates a complete set of CREATE, ALTER, and DROP actions
 * while respecting object dependencies and handling complex dependency chains.
 * <p>
 * Implementation notes:
 * <p>
 * General idea behind this class is graph passes that collect required actions.
 * addDropStatements starts a bottom-to-top pass in the old DB graph,
 * addCreateStatements starts a top-to-bottom pass in the new DB graph.
 * When these passes reach an object requiring an ALTER,
 * an "opposite direction" pass for that object is started.
 * This also allows us to treat alters as "drops" here.
 * Passes are eventually exhausted when all the actions have been collected
 * into actions set.
 * <p>
 * At the very end recreateDrops is called, which starts a "create pass"
 * for every object that was dropped but should not have been -
 * i.e. it was a dependency related drop. These passes are performed until
 * they stop generating new actions. This ensures that all dropped dependencies
 * have been recreated, and any dependency drops that may have been generated in the process
 * have also been accounted for.
 */
public final class DepcyResolver {

    /**
     * Daemon threads for the off-thread OLD dependency graph build, so a
     * lingering builder can never block JVM shutdown.
     */
    private static final ThreadFactory GRAPH_BUILDER_THREAD_FACTORY = new DaemonThreadFactory();

    private final IDatabase oldDb;
    private final IDatabase newDb;
    private final DepcyGraph oldDepcyGraph;
    private final DepcyGraph newDepcyGraph;
    private final Set<IStatement> toRefresh;

    private final Set<ActionContainer> actions = new LinkedHashSet<>();
    /**
     * Stores objects that have been processed for drop operations
     */
    private final Set<IStatement> droppedObjects = new HashSet<>();
    private final Set<IStatement> triedToDrop = new HashSet<>();

    /**
     * Stores objects that have been processed for create operations
     */
    private final Set<IStatement> createdObjects = new HashSet<>();

    /**
     * Stores the result of method appendAlterSQL. Key - {@link IStatement}, value - {@link ObjectState}
     */
    private final Map<IStatement, ObjectState> states = new HashMap<>();

    /**
     * Stores ALTER scripts built while evaluating object states. Key - the old
     * database {@link IStatement}, value - the script produced by its
     * appendAlterSQL call. Only retained for {@link ObjectState#ALTER} and
     * {@link ObjectState#ALTER_WITH_DEP} states so that
     * {@link ActionsToScriptConverter} can reuse them instead of rebuilding.
     */
    private final Map<IStatement, SQLScript> alterScripts = new HashMap<>();

    private final ISettings settings;

    public DepcyResolver(IDatabase oldDatabase, IDatabase newDatabase, ISettings settings, Set<IStatement> toRefresh) {
        this(oldDatabase, newDatabase, settings, toRefresh, null);
    }

    /**
     * Creates a resolver over a caller-supplied pair of dependency graphs, or over a freshly
     * built pair when {@code sharedGraphs} is {@code null}.
     * <p>
     * A graph is derived from its database model alone - {@link DepcyGraph} takes no settings
     * and the models are read-only here - so one pair serves every comparison of the same two
     * models, whatever the post-load settings of each. Handing the same pair to several
     * resolvers is what lets a caller that diffs one loaded comparison repeatedly, such as a
     * batch run, pay for the graphs once instead of once per output.
     * <p>
     * The pair arrives behind a supplier so that it is still built at the moment this
     * constructor would have built it, and not before: a comparison whose script turns out
     * empty never reaches here, and must not be charged for graphs no one asked for.
     *
     * @param sharedGraphs source of the graphs to reuse, or {@code null} to build a fresh pair
     */
    public DepcyResolver(IDatabase oldDatabase, IDatabase newDatabase, ISettings settings,
                         Set<IStatement> toRefresh, Supplier<DepcyGraphs> sharedGraphs) {
        this.oldDb = oldDatabase;
        this.newDb = newDatabase;
        DepcyGraphs graphs = sharedGraphs != null ? sharedGraphs.get()
                : buildDependencyGraphs(oldDatabase, newDatabase);
        this.oldDepcyGraph = graphs.oldGraph();
        this.newDepcyGraph = graphs.newGraph();
        this.toRefresh = toRefresh;
        this.settings = settings;
    }

    /**
     * Builds the OLD and NEW dependency graphs concurrently and joins before returning.
     * <p>
     * The graphs are derived from disjoint, read-only database models
     * ({@link DepcyGraph.Ownership#BORROW_READ_ONLY}). Each build only reads its
     * own model and mutates no shared state, so running them in parallel yields
     * a result byte-identical to a serial build while roughly halving the wall
     * time of this comparison phase. The OLD graph is built on a single daemon
     * worker; the NEW graph is built on the calling thread.
     * <p>
     * Exception semantics match the serial build, which built the OLD graph
     * first: if the OLD build fails, its throwable is propagated unchanged (with
     * any NEW failure attached as suppressed); otherwise a NEW failure is
     * propagated unchanged. A caller-thread interrupt while awaiting the worker
     * is surfaced as a {@link MonitorCancelledRuntimeException} with the
     * interrupt flag restored, and the worker is cancelled.
     *
     * @param oldDatabase read-only source model for the OLD graph
     * @param newDatabase read-only source model for the NEW graph
     * @return both freshly built dependency graphs
     */
    public static DepcyGraphs buildDependencyGraphs(IDatabase oldDatabase, IDatabase newDatabase) {
        ExecutorService executor = Executors.newSingleThreadExecutor(GRAPH_BUILDER_THREAD_FACTORY);
        try {
            Future<DepcyGraph> oldFuture = executor.submit(() -> {
                long start = PhaseTimer.start();
                try {
                    return new DepcyGraph(oldDatabase, false, DepcyGraph.Ownership.BORROW_READ_ONLY);
                } finally {
                    PhaseTimer.end("depcy_graph_old", start);
                }
            });

            Throwable newFailure = null;
            DepcyGraph newGraph = null;
            long start = PhaseTimer.start();
            try {
                newGraph = new DepcyGraph(newDatabase, false, DepcyGraph.Ownership.BORROW_READ_ONLY);
            } catch (RuntimeException | Error e) {
                newFailure = e;
            } finally {
                PhaseTimer.end("depcy_graph_new", start);
            }

            DepcyGraph oldGraph = joinOldGraph(oldFuture, newFailure);
            // Reaching here means the OLD build succeeded; surface any NEW failure now.
            if (newFailure != null) {
                rethrowUnchecked(newFailure);
            }
            return new DepcyGraphs(oldGraph, newGraph);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Awaits the worker OLD graph and returns it. On worker failure the cause is
     * rethrown unchanged - the OLD graph is primary because the serial build
     * produced it first - with {@code pendingNewFailure} attached as suppressed
     * when the NEW build also failed.
     *
     * @param oldFuture         handle to the worker OLD graph build
     * @param pendingNewFailure NEW build failure captured on the calling thread, or {@code null}
     * @return the built OLD graph
     */
    private static DepcyGraph joinOldGraph(Future<DepcyGraph> oldFuture, Throwable pendingNewFailure) {
        try {
            return oldFuture.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (pendingNewFailure != null && pendingNewFailure != cause) {
                cause.addSuppressed(pendingNewFailure);
            }
            rethrowUnchecked(cause);
            throw new AssertionError("unreachable");
        } catch (InterruptedException e) {
            oldFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new MonitorCancelledRuntimeException(e);
        }
    }

    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        // DepcyGraph construction declares no checked exceptions; defensive only.
        throw new IllegalStateException(failure);
    }

    /**
     * Pair of dependency graphs, one per comparison side.
     * <p>
     * Public so that a caller which diffs the same pair of loaded models more
     * than once can build the pair itself and hand it to every resolve run.
     *
     * @param oldGraph the OLD database dependency graph
     * @param newGraph the NEW database dependency graph
     */
    public record DepcyGraphs(DepcyGraph oldGraph, DepcyGraph newGraph) {
    }

    IDatabase getOldGraphSource() {
        return oldDepcyGraph.getDb();
    }

    IDatabase getNewGraphSource() {
        return newDepcyGraph.getDb();
    }

    private void fillObjects(List<DbObject> objects) {
        for (DbObject obj : objects) {
            if (obj.newStatement() == null) {
                addDropStatements(obj.oldStatement(), null);
            } else if (obj.oldStatement() == null) {
                addCreateStatements(obj.newStatement(), null);
            } else {
                addAlterStatements(obj.oldStatement(), obj.newStatement());
            }
        }
    }

    /**
     * Processes creation of an object in the new database by adding all required dependencies.
     * When an object exists in the new database but not in the old, this method initiates
     * its creation along with all dependencies required for proper operation.
     *
     * @param newStatement the object in the new database to be created
     * @param starter      the object that initiated this creation process
     */
    private void addCreateStatements(IStatement newStatement, IStatement starter) {
        if (!createdObjects.add(newStatement)) {
            return;
        }

        for (var dependency : GraphUtils.forward(newDepcyGraph, newStatement)) {
            tryToCreate(dependency, newStatement);
        }
        tryToCreate(newStatement, starter);
    }

    /**
     * Processes deletion of an object from the old database by adding all dependent objects for removal.
     * When an object exists in the old database but not in the new, this method initiates
     * its deletion along with all objects that depend on it, as they would be invalid without it.
     *
     * @param oldStatement the object in the old database to be deleted
     * @param starter      the object that initiated this deletion process
     */
    private void addDropStatements(IStatement oldStatement, IStatement starter) {
        if (!droppedObjects.add(oldStatement)) {
            return;
        }

        for (var dependent : GraphUtils.reverse(oldDepcyGraph, oldStatement)) {
            tryToDrop(dependent, oldStatement);
        }

        tryToDrop(oldStatement, starter);
        resolveCannotDrop(oldStatement);
    }

    private void resolveCannotDrop(IStatement oldStatement) {
        if (oldStatement.canDrop() || oldStatement.getParent().getTwin(newDb) == null) {
            return;
        }

        for (var dep : GraphUtils.forward(oldDepcyGraph, oldStatement)) {
            if (dep instanceof PgIndex) {
                addToListWithoutDepcies(ObjectState.DROP, dep, oldStatement);
                addDropStatements(dep, oldStatement);
            }
        }
    }

    /**
     * Adds statements for altering a database object.
     * Determines the appropriate action based on object state comparison
     * and handles dependency-related recreations when necessary.
     *
     * @param oldStatement the original object state
     * @param newStatement the target object state
     */
    private void addAlterStatements(IStatement oldStatement, IStatement newStatement) {
        ObjectState state = getObjectState(oldStatement, newStatement);
        if (state.in(ObjectState.RECREATE, ObjectState.ALTER_WITH_DEP)) {
            addDropStatements(oldStatement, null);
            return;
        }

        // add altered objects
        // skip table columns from drop list
        if (state == ObjectState.ALTER && !inDropsList(oldStatement)
                && (oldStatement.getStatementType() != DbObjType.COLUMN || !inDropsList(oldStatement.getParent()))) {
            addToListWithoutDepcies(ObjectState.ALTER, oldStatement, null);
        }

        alterMsTableColumns(oldStatement, newStatement);
    }

    private void alterMsTableColumns(IStatement oldStatement, IStatement newStatement) {
        // if no depcies were triggered for a MsTable alter
        // check for column layout changes and refresh views
        if (oldStatement instanceof MsTable tOld && newStatement instanceof MsTable tNew) {
            List<IColumn> cOld = tOld.getColumns();
            List<IColumn> cNew = tNew.getColumns();

            // first check for columns added or removed
            boolean colLayoutChanged = cOld.size() != cNew.size();
            if (!colLayoutChanged) {
                // second, columns replaced or reordered
                for (int i = 0; i < cOld.size(); ++i) {
                    if (!cOld.get(i).getName().equals(cNew.get(i).getName())) {
                        colLayoutChanged = true;
                        break;
                    }
                }
            }

            if (colLayoutChanged) {
                refreshDependents(tOld);
            }
        }
    }

    private void refreshDependents(AbstractStatement oldStatement) {
        for (var dependent : GraphUtils.reverse(oldDepcyGraph, oldStatement)) {
            if (dependent instanceof MsView && dependent.getTwin(newDb) != null) {
                toRefresh.add(dependent);
            }
        }
    }

    private void removeAlteredFromRefreshes() {
        toRefresh.removeIf(st ->
                actions.stream().anyMatch(action -> action.getState() == ObjectState.ALTER
                        && action.getOldObj() instanceof MsView
                        && action.getOldObj().equals(st))
        );
    }

    /**
     * Recreates previously dropped objects into their new state.
     * Handles cases where objects were dropped due to dependencies but should
     * actually exist in the target schema. Continues until no new actions are generated.
     */
    private void recreateDrops() {
        int oldActionsSize = -1;
        List<IStatement> toRecreate = new ArrayList<>();
        // since a recreate can trigger a drop via  dependency being altered
        // run recreates until no more statements are being added (may need optimization)
        while (actions.size() > oldActionsSize) {
            toRecreate.clear();
            oldActionsSize = actions.size();
            for (ActionContainer action : actions) {
                if (action.getState() == ObjectState.DROP) {
                    toRecreate.add(action.getOldObj());
                }
            }
            for (IStatement drop : toRecreate) {
                var newSt = drop.getTwin(newDb);
                if (newSt != null) {
                    // add views to emit refreshes others are to block drop+create pairs for unchanged statements
                    fillRefresh(drop, newSt);
                    addCreateStatements(newSt, null);
                }
            }
        }
    }

    private void fillRefresh(IStatement drop, IStatement newSt) {
        if (newSt instanceof MsSourceStatement) {
            if (newSt instanceof MsAbstractFunction && isMsTypeDep(newSt)) {
                return;
            }

            if (newSt instanceof MsView view && view.isSchemaBinding()) {
                return;
            }

            if (newSt.equals(drop) && !inDropsList(newSt.getParent())) {
                toRefresh.add(newSt);
            }
        }
    }

    // check if obj dependence of ms Type
    private boolean isMsTypeDep(IStatement newSt) {
        var graph = newDepcyGraph.getGraph();
        for (DefaultEdge edge : graph.edgeSet()) {
            var source = graph.getEdgeSource(edge);
            if (newSt.equals(source)) {
                var target = graph.getEdgeTarget(edge);
                if (target instanceof MsType && inDropsList(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void tryToDrop(IStatement oldObj, IStatement starter) {
        if (!triedToDrop.add(oldObj)) {
            return;
        }

        // Initially set action to drop the object
        ObjectState action = ObjectState.DROP;
        if (!oldObj.canDrop()) {
            addToListWithoutDepcies(action, oldObj, starter);
            return;
        }

        var newObj = oldObj.getTwin(newDb);
        if (newObj != null && !hasDroppedDependency(oldObj)) {
            action = getObjectState(oldObj, newObj);
            if (action == ObjectState.NOTHING) {
                return;
            }

            // when altering an object with dependencies,
            // first create the object with dependencies,
            // then alter it
            if (action == ObjectState.ALTER_WITH_DEP) {
                // do not add object if already in the list
                if (!createdObjects.contains(newObj)) {
                    addCreateStatements(newObj, null);
                    addToListWithoutDepcies(action, oldObj, starter);
                }
                return;
            }

            if (action == ObjectState.RECREATE) {
                action = ObjectState.DROP;
            }
        }

        // Columns are skipped when dropping the table
        if (oldObj.getStatementType() == DbObjType.COLUMN) {
            ITable oldTable = (ITable) oldObj.getParent();
            var newTable = oldObj.getParent().getTwin(newDb);

            if (newTable == null || getRecreatedObj(oldTable, (ITable) newTable)) {
                // case where dependency drop affects a column we don't handle
                // because the table is being dropped - drop the table instead
                addDropStatements(oldTable, oldObj);
                return;
            }

            // also skip during recreate
            ObjectState parentState = getObjectState(oldTable, newTable);
            if (parentState == ObjectState.RECREATE) {
                return;
            }

            if (isColumnChangeOverlap(oldTable, newTable)) {
                return;
            }
        }

        // skip sequence if its owned-by column is being dropped
        // sequence will be dropped implicitly with the column
        if (newObj == null && oldObj instanceof PgSequence seq) {
            var ownedBy = seq.getOwnedBy();
            if (ownedBy != null && newDb.getStatement(ownedBy) == null) {
                return;
            }
        }

        addToListWithoutDepcies(action, oldObj, starter);
    }

    private boolean hasDroppedDependency(IStatement oldState) {
        for (var dependency : GraphUtils.forward(oldDepcyGraph, oldState)) {
            DbObjType type = dependency.getStatementType();
            var newSt = dependency.getTwin(newDb);
            if (newSt == null) {
                if (type == DbObjType.FUNCTION && isDefaultsOnlyChange((IFunction) dependency)) {
                    // when function's signature changes it has no twin
                    // but the dependent object might be unchanged
                    // due to default arguments changing in the signature
                    return true;
                }
                if (isVanishingUniqueKeyBacking(oldState, dependency)) {
                    return true;
                }
                continue;
            }

            if (type.in(DbObjType.FUNCTION, DbObjType.PROCEDURE)
                    && !((IFunction) dependency).needDrop((IFunction) newSt)) {
                continue;
            }

            ObjectState state = getObjectState(dependency, newSt);
            if (state.in(ObjectState.RECREATE, ObjectState.ALTER_WITH_DEP)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Tells whether {@code dependency} is the unique index or unique/primary key constraint that backs
     * {@code dependent}'s referenced columns, in the case where that backing object is gone from the new database.
     * <p>
     * PostgreSQL pins a foreign key to exactly one such object and records the link in {@code pg_depend}, so it
     * refuses to drop the backing object while the key still references it. Whenever the backing object leaves the
     * schema - dropped, renamed, or swapped for the other flavour of uniqueness - the key has to be dropped ahead of
     * that change and recreated afterwards, even though the key itself did not change at all. Without this the script
     * emits a bare {@code DROP INDEX}/{@code DROP CONSTRAINT} that the server rejects on the spot.
     * <p>
     * The edge tested here is the one {@link DepcyGraph} builds between a foreign key and the unique object backing
     * it, and the test stays that narrow on purpose: a missing twin is not a general "this object is being dropped"
     * signal. A column of a plain table that is turned into a partition, for one, loses its own statement in the new
     * model while the column itself lives on, and treating that as a drop makes the script recreate constraints that
     * never needed touching.
     *
     * @param dependent  the object that survives into the new database
     * @param dependency its dependency that has no counterpart in the new database
     * @return true if a surviving foreign key lost the unique object backing it
     */
    private static boolean isVanishingUniqueKeyBacking(IStatement dependent, IStatement dependency) {
        if (!(dependent instanceof IConstraintFk)) {
            return false;
        }
        return dependency instanceof IConstraintPk
                || (dependency instanceof IIndex index && index.isUnique());
    }

    private boolean isDefaultsOnlyChange(IFunction oldFunc) {
        ISchema newSchema = newDb.getSchema(oldFunc.getSchemaName());
        if (newSchema == null) {
            return false;
        }

        // in the new database, search the function for which
        // the signature before first default argument will be the same
        // if there is such, then the drop is necessary,
        // if there is no such, then the drop is not necessary
        Function<IFunction, List<? extends IArgument>> argsBeforeDefaults = f -> {
            var args = f.getArguments();
            OptionalInt firstDefault = IntStream.range(0, args.size())
                    .filter(i -> args.get(i).getDefaultExpression() != null)
                    .findFirst();
            return firstDefault.isPresent() ? args.subList(0, firstDefault.getAsInt()) : args;
        };

        var oldArgs = argsBeforeDefaults.apply(oldFunc);

        var allFuncs = newSchema.getChildrenByType(DbObjType.FUNCTION);

        return allFuncs.stream()
                .map(IFunction.class::cast)
                .filter(f -> oldFunc.getBareName().equals(f.getBareName()))
                .map(argsBeforeDefaults)
                .anyMatch(oldArgs::equals);
    }

    private boolean isColumnChangeOverlap(IStatement oldTable, IStatement newTable) {
        // skip columns if table type changed
        if (!oldTable.getClass().equals(newTable.getClass())) {
            return true;
        }

        // columns are integrated into CREATE TABLE OF TYPE
        if (newTable instanceof PgTypedTable newTypedTable) {
            PgTypedTable oldTypedTable = (PgTypedTable) oldTable;
            return !Objects.equals(newTypedTable.getOfType(), oldTypedTable.getOfType());
        }

        return false;
    }

    /**
     * Removes actions that for some reason should not be included in the script
     */
    private void removeExtraActions() {
        Set<ActionContainer> toRemove = new HashSet<>();
        for (ActionContainer action : actions) {
            if (action.getState() != ObjectState.ALTER) {
                continue;
            }
            // case where the selected modified object was recreated due to a dependency
            var newObj = action.getNewObj();
            if (actions.contains(new ActionContainer(newObj, newObj, ObjectState.CREATE, null))) {
                toRemove.add(action);
            }
        }
        actions.removeAll(toRemove);
    }

    /**
     * Checks if an object exists in the list of previously dropped objects.
     *
     * @param statement the object to check
     * @return true if the object is in the drops list, false otherwise
     */
    private boolean inDropsList(IStatement statement) {
        IStatement oldObj = statement.getTwin(oldDb);
        IStatement newObj = statement.getTwin(newDb);

        // if owned-by column or table is already in drop list
        // then a removed sequence will also be dropped implicitly, return true.
        // A sequence that survives in NEW must retain its ALTER action so it can
        // be detached before the owning column or table is dropped.
        if (newObj == null && oldObj instanceof PgSequence seq) {
            var ownedBy = seq.getOwnedBy();
            if (ownedBy != null) {
                var column = oldDb.getStatement(ownedBy);
                return column != null && (inDropsList(column) || inDropsList(column.getParent()));
            }
        }

        return actions.contains(new ActionContainer(oldObj, oldObj, ObjectState.DROP, null));
    }

    /**
     * Adds an action to the script expressions list without processing dependencies.
     *
     * @param action  the action type to perform (see {@link ObjectState})
     * @param oldObj  the object from the old database state
     * @param starter the object that triggered this action
     */
    private void addToListWithoutDepcies(ObjectState action,
                                         IStatement oldObj, IStatement starter) {
        switch (action) {
            case CREATE, DROP -> actions.add(new ActionContainer(oldObj, oldObj, action, starter));
            case ALTER, ALTER_WITH_DEP -> actions
                    .add(new ActionContainer(oldObj, oldObj.getTwin(newDb), ObjectState.ALTER, starter));
            default -> throw new IllegalStateException(Messages.ActionsToScriptConverter_not_implemented_action);
        }
    }

    private void tryToCreate(IStatement newObj, IStatement starter) {
        // Initially set action to create the object
        ObjectState action = ObjectState.CREATE;
        // always create if droppped before
        if (inDropsList(newObj)) {
            createColumnDependencies(newObj);
            addToListWithoutDepcies(action, newObj, null);
            return;
        }

        if (newObj.getStatementType() == DbObjType.COLUMN) {
            var oldTable = newObj.getParent().getTwin(oldDb);
            ITable newTable = (ITable) newObj.getParent();
            if (oldTable == null || getRecreatedObj((ITable) oldTable, newTable)) {
                // columns are integrated into CREATE TABLE
                return;
            }

            if (isColumnChangeOverlap(oldTable, newTable)) {
                return;
            }
        }

        var oldObj = newObj.getTwin(oldDb);
        if (oldObj != null) {
            action = getObjectState(oldObj, newObj);
            if (action == ObjectState.NOTHING) {
                return;
            }

            // when altering object with dependencies
            if (action.in(ObjectState.RECREATE, ObjectState.ALTER_WITH_DEP)) {
                addDropStatements(oldObj, starter);
                if (action == ObjectState.ALTER_WITH_DEP) {
                    // add alter for old object
                    addToListWithoutDepcies(action, oldObj, starter);
                    return;
                }
                action = ObjectState.CREATE;
            }
        }

        // if object (table) is being created, initiate creation of its column dependencies
        // columns themselves will be created implicitly with the table
        if (action == ObjectState.CREATE) {
            createColumnDependencies(newObj);
        }

        // create column when creating sequence with owned-by relationship
        if (newObj instanceof PgSequence seq) {
            var ownedBy = seq.getOwnedBy();
            if (ownedBy != null && oldDb.getStatement(ownedBy) == null) {
                var col = newDb.getStatement(ownedBy);
                if (col != null) {
                    addCreateStatements(col, newObj);
                }
            }
        }

        addToListWithoutDepcies(action, newObj, starter);
    }

    private void createColumnDependencies(IStatement newObj) {
        if (newObj instanceof ITable table) {
            // create column dependencies before table
            for (IColumn col : table.getColumns()) {
                addCreateStatements(col, null);
            }
        }
    }

    private ObjectState getObjectState(IStatement oldSt, IStatement newSt) {
        ObjectState state = states.get(oldSt);
        if (state != null) {
            return state;
        }

        SQLScript alterScript = new SQLScript(settings, newSt.getSeparator());
        state = oldSt.appendAlterSQL(newSt, alterScript);
        states.put(oldSt, state);
        if (state.in(ObjectState.ALTER, ObjectState.ALTER_WITH_DEP)) {
            alterScripts.put(oldSt, alterScript);
        }
        return state;
    }

    private boolean getRecreatedObj(ITable oldTable, ITable newTable) {
        return getObjectState(oldTable, newTable) == ObjectState.RECREATE;
    }

    public static Set<ActionContainer> resolve(IDatabase oldDb,
                                               IDatabase newDb,
                                               List<Dependency> additionalDependenciesOldDb,
                                               List<Dependency> additionalDependenciesNewDb,
                                               Set<IStatement> toRefresh,
                                               List<DbObject> dbObjects,
                                               ISettings settings) {
        return resolveActions(oldDb, newDb, additionalDependenciesOldDb, additionalDependenciesNewDb,
                toRefresh, dbObjects, settings).actions();
    }

    /**
     * Resolves dependencies like {@link #resolve} and additionally returns the
     * ALTER scripts memoized during object state evaluation, so the script
     * converter can reuse them instead of rebuilding identical SQL.
     *
     * @return resolved actions together with the memoized ALTER scripts
     */
    public static ResolvedActions resolveActions(IDatabase oldDb,
                                                 IDatabase newDb,
                                                 List<Dependency> additionalDependenciesOldDb,
                                                 List<Dependency> additionalDependenciesNewDb,
                                                 Set<IStatement> toRefresh,
                                                 List<DbObject> dbObjects,
                                                 ISettings settings) {
        return resolveActions(oldDb, newDb, additionalDependenciesOldDb, additionalDependenciesNewDb,
                toRefresh, dbObjects, settings, null);
    }

    /**
     * Resolves dependencies like {@link #resolveActions(IDatabase, IDatabase, List, List, Set, List, ISettings)}
     * over a caller-supplied pair of dependency graphs.
     * <p>
     * The additional dependencies are applied to the shared graphs on every
     * run. That is safe to repeat: the graph is a simple directed graph, so
     * adding an edge it already holds changes nothing, and a caller that shares
     * one pair across several runs feeds it the same common-section
     * dependencies each time.
     *
     * @param sharedGraphs source of the graphs to reuse, or {@code null} to build a fresh pair
     * @return resolved actions together with the memoized ALTER scripts
     */
    public static ResolvedActions resolveActions(IDatabase oldDb,
                                                 IDatabase newDb,
                                                 List<Dependency> additionalDependenciesOldDb,
                                                 List<Dependency> additionalDependenciesNewDb,
                                                 Set<IStatement> toRefresh,
                                                 List<DbObject> dbObjects,
                                                 ISettings settings,
                                                 Supplier<DepcyGraphs> sharedGraphs) {
        long start = PhaseTimer.start();
        DepcyResolver depRes = new DepcyResolver(oldDb, newDb, settings, toRefresh, sharedGraphs);
        depRes.oldDepcyGraph.addCustomDepcies(additionalDependenciesOldDb);
        depRes.newDepcyGraph.addCustomDepcies(additionalDependenciesNewDb);
        depRes.fillObjects(dbObjects);
        depRes.recreateDrops();
        depRes.removeExtraActions();
        depRes.removeAlteredFromRefreshes();
        PhaseTimer.end("depcy_resolve", start);

        return new ResolvedActions(depRes.actions, Collections.unmodifiableMap(depRes.alterScripts));
    }

    /**
     * Result of dependency resolution.
     * <p>
     * The ALTER scripts are keyed by the old database statement and were built
     * with the resolver's settings and the statement's separator - exactly the
     * inputs {@link ActionsToScriptConverter} would otherwise use to rebuild
     * them, which guarantees byte-identical script output on reuse. Retained
     * only for {@link ObjectState#ALTER} and {@link ObjectState#ALTER_WITH_DEP}
     * states.
     *
     * @param actions      resolved actions in script order
     * @param alterScripts memoized ALTER scripts keyed by old database statement
     */
    public record ResolvedActions(Set<ActionContainer> actions, Map<IStatement, SQLScript> alterScripts) {
    }
}
